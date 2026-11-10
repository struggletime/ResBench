package com.resbench.common.fault.channel;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 endpoint 缓存的常驻命令通道表 —— 整个 job 生命周期内**一个 agent 一条连接**。
 *
 * <p><b>为什么必须缓存</b>：这条通道存在的全部意义是"在 t+0 把连接建好、t+30 复用"。
 * 每次调用新建一条 WebSocket，就等于又回到了"在故障窗口里新建连接"，
 * 只是把 HTTP 换成了 WS —— 该失败的照样失败。所以注入之前必须先 {@link #prewarm}。
 *
 * <p>开关：{@code -Dfault.channel.enabled}（默认 true）。关掉即完全退回 HTTP 路径，
 * 与本轮改动之前逐字一致。
 */
public final class ChannelRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelRegistry.class);

    private static final Map<String, AgentCommandChannel> CHANNELS = new ConcurrentHashMap<>();

    private static volatile boolean shutdownHookInstalled;

    private ChannelRegistry() {}

    /** 通道功能是否开启（默认开；关掉即回到纯 HTTP 行为） */
    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("fault.channel.enabled", "true"));
    }

    /**
     * 归一化 endpoint 作为缓存 key。
     *
     * <p>注入侧存的是 {@code http://ip:port}，{@code resolveAgentHostPorts} 返回的是裸
     * {@code ip:port} —— 不归一化就会给同一个 agent 开两条连接，而多出来的那条是在
     * 故障窗口里新开的，正好踩中我们要避开的坑。
     */
    public static String key(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String k = endpoint.trim();
        int scheme = k.indexOf("://");
        if (scheme >= 0) {
            k = k.substring(scheme + 3);
        }
        while (k.endsWith("/")) {
            k = k.substring(0, k.length() - 1);
        }
        return k;
    }

    /**
     * 取（必要时创建）到某个 agent 的通道。
     *
     * @return 通道；功能被关掉时返回 {@code null}，调用方据此走 HTTP
     */
    public static AgentCommandChannel get(String endpoint) {
        if (!enabled()) {
            return null;
        }
        String key = key(endpoint);
        if (key.isEmpty()) {
            return null;
        }
        installShutdownHook();
        // computeIfAbsent 里不做阻塞等待：建连是异步的，等待交给调用方的 awaitConnected。
        return CHANNELS.computeIfAbsent(key, k -> new AgentCommandChannel(k));
    }

    /**
     * 预热：给每个 endpoint 开一条通道并等握手。
     *
     * <p>必须在<b>网络健康的时候</b>调用（{@code FaultRunner.run()} 开头），否则就失去了意义。
     * 握手失败不抛异常——那只是说明这个 endpoint 这轮用不上通道，调用方会自然退回 HTTP。
     *
     * @return 握上手且仍然 OPEN 的 endpoint 数
     */
    public static int prewarm(Collection<String> endpoints, long awaitMs) {
        if (!enabled() || endpoints == null || endpoints.isEmpty()) {
            return 0;
        }
        int ready = 0;
        for (String endpoint : endpoints) {
            AgentCommandChannel channel = get(endpoint);
            if (channel == null) {
                continue;
            }
            if (channel.awaitConnected(awaitMs)) {
                ready++;
            } else {
                LOG.warn(
                        "Fault channel {} not ready within {}ms; this endpoint falls back to HTTP",
                        channel.getEndpoint(),
                        awaitMs);
            }
        }
        LOG.info("Fault channel prewarm: {}/{} endpoint(s) connected", ready, endpoints.size());
        return ready;
    }

    /** 关掉所有通道（job 结束时） */
    public static void closeAll() {
        for (AgentCommandChannel channel : CHANNELS.values()) {
            channel.close();
        }
        CHANNELS.clear();
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        synchronized (ChannelRegistry.class) {
            if (shutdownHookInstalled) {
                return;
            }
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(ChannelRegistry::closeAll, "fault-channel-shutdown"));
            } catch (IllegalStateException e) {
                // 已经在关停流程里了，通道本来就会被进程带走
                LOG.debug("Skip fault channel shutdown hook: {}", e.getMessage());
            }
            shutdownHookInstalled = true;
        }
    }
}
