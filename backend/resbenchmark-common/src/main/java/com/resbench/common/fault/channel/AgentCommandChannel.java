package com.resbench.common.fault.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.remote.RestfulCaller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一条常驻 WebSocket 命令通道（引擎 → agent 的 {@value #PATH}）。
 *
 * <p><b>为什么需要它</b>：filemove 的恢复几乎必然失败，失败在 TCP 层（{@code Connect timed out}）——
 * 注入在 t+0（网络健康）成功，恢复在 t+30（库目录被搬走、200 个 worker 持续重试）时
 * <b>新建连接</b>建不起来；而同期引擎到 agent 的<b>已有连接</b>完全健康（走 TCP 重传）。
 * 于是把恢复也放上一条在 t+0 就建好的连接，问题就不存在了。
 *
 * <p><b>连接靠谁保活</b>：agent（uvicorn 默认参数）每 {@code ws_ping_interval=20s} 发一次
 * WS Ping，20 秒内收不到 Pong 就关连接。JDK 的 {@code java.net.http.WebSocket} <b>自动回 Pong</b>
 * （{@code WebSocketImpl.processPing()} 在把 Ping 交给监听器之前就已 {@code sendPong}），
 * 所以引擎侧一个心跳都不用发，30 秒空窗期不会把连接判死。
 *
 * <p><b>不重连</b>：通道断了就断了，后续调用由 {@link ChannelRegistry} 的调用方退回 HTTP。
 * 刻意不在故障窗口里重连——那正是"新建连接"，是本次要避开的操作。
 */
public class AgentCommandChannel {
    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandChannel.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 握手超时；建连本身在负载健康时发生，5 秒足够 */
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** agent 上的命令通道端点（{@code backend/agent/faults/channel.py}） */
    public static final String PATH = "/fault_channel";

    /** agent 侧 {@code ok=false} 时用来喂给 markResult 的字面量（与 HTTP 非 200 时一致） */
    private static final String FALSE_LITERAL = "false";

    private final String endpoint;
    private final URI uri;
    private final ConcurrentHashMap<String, CompletableFuture<Reply>> pending = new ConcurrentHashMap<>();
    private final CountDownLatch openLatch = new CountDownLatch(1);

    private volatile WebSocket socket;
    private volatile boolean open;
    private volatile boolean closing;
    private volatile String closedReason;

    AgentCommandChannel(String endpoint) {
        this.endpoint = endpoint;
        this.uri = URI.create("ws://" + endpoint + PATH);
        // 异步建连：调用方（FaultRunner 的预热）随后用 awaitConnected 决定要不要等。
        HTTP.newWebSocketBuilder()
                .buildAsync(uri, new Listener())
                .whenComplete(
                        (ws, err) -> {
                            if (err != null) {
                                // 握手就没成（agent 没起 / 209 没装 websockets / 端口不通）。
                                // 这里没有 WebSocket 实例，所以 onError 不会被调用 —— 得自己收尾，
                                // 否则 closedReason 一直是 null，post() 那句错误就没有原因可写。
                                markClosed("handshake failed: " + rootMessage(err));
                            }
                            // 成功路径由 Listener.onOpen 打日志
                        });
    }

    /** 端点（{@code host:port}） */
    public String getEndpoint() {
        return endpoint;
    }

    /** 发送那一刻通道是否可写 */
    public boolean isOpen() {
        return open;
    }

    /** 通道断掉的原因；从未断过时为 null */
    public String getClosedReason() {
        return closedReason;
    }

    /** 等握手完成，最多等 {@code timeoutMs}。建连本身不会新建连接之外的开销，健康时可立即返回。 */
    public boolean awaitConnected(long timeoutMs) {
        try {
            return openLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && open;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 在通道上发一条命令并等回复，返回结构与 HTTP 路径同形，好让 {@code FaultInjector.callAgent}
     * 的判定树完全不用改。
     *
     * <p>与 HTTP 路径的关键差别：<b>这里没有 connect 这一步</b>，所以整段限额都花在等回复上，
     * 而不是被握手吃掉（原来 connect 与 read 共用同一个限额，整整 10 秒连一个字节都没交换）。
     */
    public RestfulCaller.HttpResult post(String path, String param, int timeoutMs) {
        long start = System.currentTimeMillis();
        WebSocket ws = socket;
        if (ws == null || !open) {
            return new RestfulCaller.HttpResult(
                    false,
                    0,
                    "",
                    elapsed(start),
                    "channel " + endpoint + " is not open"
                            + (closedReason == null ? "" : " (" + closedReason + ")"),
                    null,
                    "ws",
                    Boolean.FALSE);
        }

        String id = "c-" + UUID.randomUUID();
        CompletableFuture<Reply> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            // param 本身就是 Jackson 序列化出来的 JSON 对象，原样嵌入，不二次编码——
            // 保证 agent 收到的 body 与 HTTP 那次逐字节一致（结果字符串才能保持一致）。
            String body = (param == null || param.trim().isEmpty()) ? "{}" : param;
            String frame =
                    "{\"id\":"
                            + quote(id)
                            + ",\"path\":"
                            + quote(path)
                            + ",\"param\":"
                            + body
                            + "}";
            ws.sendText(frame, true)
                    .whenComplete(
                            (w, err) -> {
                                // 发送失败要立刻叫醒等待方，别让它干等满限额。
                                // 注意 sendText 完成只代表"交给传输层"，不代表送达——只有应用层回复才算数。
                                if (err != null) {
                                    future.completeExceptionally(err);
                                }
                            });

            Reply reply;
            try {
                reply = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return new RestfulCaller.HttpResult(
                        false,
                        0,
                        "",
                        elapsed(start),
                        "no reply over channel " + endpoint + " within " + timeoutMs + "ms",
                        null,
                        "ws",
                        Boolean.TRUE);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return new RestfulCaller.HttpResult(
                        false,
                        0,
                        "",
                        elapsed(start),
                        "channel " + endpoint + " send failed: " + rootMessage(cause),
                        null,
                        "ws",
                        Boolean.TRUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new RestfulCaller.HttpResult(
                        false,
                        0,
                        "",
                        elapsed(start),
                        "interrupted while waiting on channel " + endpoint,
                        null,
                        "ws",
                        Boolean.TRUE);
            }

            if (!reply.ok) {
                // 协议层失败（帧解析不了等）——包装成与 HTTP 非 200 同形，让 markResult 判成失败
                return new RestfulCaller.HttpResult(
                        true, 500, FALSE_LITERAL, elapsed(start), reply.error, null, "ws", Boolean.TRUE);
            }
            // 与 agent 的 /inject 返回同形：{"result":"Executed: ..."}
            String envelope = MAPPER.writeValueAsString(Collections.singletonMap("result", reply.result));
            return new RestfulCaller.HttpResult(true, 200, envelope, elapsed(start), null, null, "ws", Boolean.TRUE);
        } catch (JsonProcessingException e) {
            return new RestfulCaller.HttpResult(
                    false,
                    0,
                    "",
                    elapsed(start),
                    "failed to encode channel reply: " + e.getMessage(),
                    null,
                    "ws",
                    Boolean.TRUE);
        } finally {
            pending.remove(id);
        }
    }

    /** 干净关闭；由 {@link ChannelRegistry} 的 shutdown hook 调用 */
    public void close() {
        WebSocket ws = socket;
        open = false;
        closing = true;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "job finished");
            } catch (Exception e) {
                LOG.debug("Fault channel {} close failed: {}", endpoint, e.getMessage());
            }
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "\"\"";
        }
    }

    /**
     * 把异常拆到信息量最大的那一层。
     *
     * <p>{@link WebSocketHandshakeException#getMessage()} 对 403 和 404 <b>都返回 null</b>
     * （已实测）——状态码只在 {@code getResponse().statusCode()} 里。以前这里直接退回类名，
     * 于是日志只剩一个光秃秃的 {@code WebSocketHandshakeException}：
     * 分不清"这个路由不存在(404)"还是"uvicorn 没有 WS 库(400)"，
     * 而这恰恰是排障时唯一需要的那个数。
     */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof WebSocketHandshakeException) {
            HttpResponse<?> response = ((WebSocketHandshakeException) cause).getResponse();
            if (response != null) {
                return "handshake rejected with HTTP " + response.statusCode();
            }
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    /** 关掉通道并让所有在途调用立刻失败，别让它们干等满限额 */
    private void markClosed(String reason) {
        boolean expected = closing;
        open = false;
        socket = null;
        closedReason = reason;
        // 我们自己发起的关闭是正常收尾，不该在日志里刷成 ERROR——
        // 这里的 ERROR 是要当证据用的（"故障窗口里通道断了"），不能被噪声稀释。
        if (expected) {
            LOG.info("Fault channel {} closed: {}", endpoint, reason);
        } else {
            LOG.error("Fault channel {} {}; subsequent calls fall back to HTTP", endpoint, reason);
        }
        // 握手失败也走这条路：让 awaitConnected 立刻返回 false，而不是让预热在那里干等满超时。
        openLatch.countDown();
        if (pending.isEmpty()) {
            return;
        }
        RuntimeException cause = new RuntimeException(reason);
        for (Map.Entry<String, CompletableFuture<Reply>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(cause);
        }
        pending.clear();
    }

    /** 一条回复帧 */
    private static final class Reply {
        final boolean ok;
        final String result;
        final String error;

        Reply(boolean ok, String result, String error) {
            this.ok = ok;
            this.result = result;
            this.error = error;
        }
    }

    private void handle(String message) {
        String id = null;
        try {
            JsonNode node = MAPPER.readTree(message);
            id = node.path("id").asText(null);
            CompletableFuture<Reply> future = id == null ? null : pending.remove(id);
            if (future == null) {
                // 迟到的回复：对应的 FaultResult 已经定稿，绝不能改写它
                LOG.debug("Fault channel {} dropped a reply for unknown id {}", endpoint, id);
                return;
            }
            future.complete(
                    new Reply(
                            node.path("ok").asBoolean(true),
                            node.path("result").asText(""),
                            node.path("error").asText(null)));
        } catch (Exception e) {
            LOG.warn("Fault channel {} could not parse a reply (id {}): {}", endpoint, id, e.getMessage());
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            open = true;
            // 把 demand 一次性拉满。JDK 只在 demand > 0 时才去读下一帧；demand 归零后连 ping
            // 都收不到，自动 Pong 也就不会发，通道会在下一个 ping 周期静默死掉——
            // 而且看起来就像"WebSocket 也没用"。所以这里不能省，也无需在每个回调里补 request(1)。
            webSocket.request(Long.MAX_VALUE);
            LOG.info("Fault channel {} connected", endpoint);
            openLatch.countDown();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handle(message);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // uvicorn 每 ws_ping_interval(20s) 发一次；Pong 由 JDK 传输层自动回。
            // 这里只留一行证据：故障窗口里出现它，就说明"恢复发出前通道还活着"。
            LOG.info("Fault channel {} ping (still alive)", endpoint);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            markClosed("closed by peer: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            markClosed("error: " + rootMessage(error));
        }
    }
}
