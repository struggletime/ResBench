package com.resbench.common.fault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.fault.bdserver.DBService;
import com.resbench.common.fault.channel.AgentCommandChannel;
import com.resbench.common.fault.channel.ChannelRegistry;
import com.resbench.common.constant.PathConstant;
import com.resbench.common.remote.exception.RemoteAccessException;
import com.resbench.common.remote.RestfulCaller;
import com.resbench.common.util.FileUtil;
import com.resbench.common.util.JSONUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FaultInjector {
    private static final Logger LOG = LoggerFactory.getLogger(FaultInjector.class);

    /**
     * 注入/恢复的默认往返限额：请求发出后 {@value} ms 内没收到 agent 回复，就判定为「未应答」——
     * 按约定，十秒没有返回就说明出现了意料之外的事情，要在前端标注出来。
     *
     * <p><b>这是唯一真源</b>：{@code timeoutMs <= 0} 一律解析成它，
     * 不再有「0 = 无限等待」这种语义 —— 那正是命令能晚到 68 秒而无人察觉的原因。
     * 需要放宽时用系统属性 {@code fault.inject.timeoutMs} / {@code fault.recover.timeoutMs}。
     */
    public static final int DEFAULT_ROUND_TRIP_TIMEOUT_MS = 10_000;

    /**
     * 恢复命令里代表「注入返回的 chaosblade uid」的占位符。
     *
     * <p><b>为什么 disk fill 的撤销必须按 uid</b>：chaosblade（exec-os）的 disk fill 撤销
     * {@code stopFill} 会先 {@code GetPidsByProcessName("disk fill")}，再 {@code kill -9}
     * 所有命令行里含这个子串的进程（只排除调用者自己）。而按 target/action 写的撤销命令
     * {@code blade destroy disk fill --path ...} 自己的命令行就命中这个子串 ——
     * 同一时刻只要还有另一条 disk fill 撤销（引擎排的这条 / {@code --timeout} 到点拉起的那条），
     * 互相就会把对方 SIGKILL 掉：agent 只能拿到 {@code Error 137}、stdout/stderr 全空，
     * 引擎据此把「其实已经恢复」的 disk fill 记成恢复失败。
     * 改成 {@code blade destroy <uid>} 后命令行不含该子串，撤销能正常回执。
     */
    public static final String UID_PLACEHOLDER = "{{uid}}";

    /** chaosblade 成功响应里的 {@code "result":"<uid>"}（uid 是 16 位十六进制） */
    private static final java.util.regex.Pattern BLADE_UID =
            java.util.regex.Pattern.compile("\"result\"\\s*:\\s*\"([0-9a-zA-Z]{8,})\"");

    /** chaosblade 的「实验记录不存在」：{@code spec.DataNotFound = 67002} */
    private static final int BLADE_RECORD_NOT_FOUND = 67002;

    /**
     * 按 uid 撤销时 agent 侧的硬超时（秒）：到点 agent 直接 SIGKILL 撤销进程。
     *
     * <p><b>为什么需要它</b>：{@code blade destroy} 只接受 UID，没有 {@code --timeout} 旗标
     * （create 才有），所以「撤销本身卡住」只能由调用方兜底。agent 的 {@code exec()} 默认
     * 60 秒才杀，而引擎的往返限额默认只有 10 秒 —— 10 秒一到引擎先放弃，agent 还在傻等，
     * 回执再也回不来。这里显式给一个偏短的硬上限，让 agent 先杀、先回
     * {@code TimeoutExpired}，引擎才能把「撤销超时」如实记下来；
     * 真正的清理兜底仍是注入时追加的 {@code --timeout}（那边到点会按 uid 再撤一次）。
     *
     * <p>可用系统属性 {@value #UID_RECOVERY_AGENT_TIMEOUT_PROPERTY} 覆盖。
     */
    public static final int DEFAULT_UID_RECOVERY_AGENT_TIMEOUT_SEC = 30;

    public static final String UID_RECOVERY_AGENT_TIMEOUT_PROPERTY =
            "fault.recover.agentTimeoutSec";

    /** 引擎比 agent 硬超时多等的余量：保证先收到 agent 的 TimeoutExpired 回执再放弃 */
    public static final int UID_RECOVERY_GRACE_MS = 5_000;

    /**
     * 执行 agent 调用用的守护线程池：调用跑在这上面，调用方线程只负责掐卡死线。
     *
     * <p>必须这么做：{@code HttpURLConnection} 没有写超时，{@code setConnectTimeout/ReadTimeout}
     * 盖不住「请求写入卡死」那种情况（实测有过 68.9 秒），只有从调用方计时才是真正的限额。
     * 守护线程 + 缓存池：孤儿调用最多泄漏少量线程，JVM 仍可正常退出。
     * 不复用 {@code FaultRunner.verifyExecutor} —— 那个会被 {@code close()} 关掉，
     * 而这些静态方法可能活得比某个 runner 长。
     */
    private static final ExecutorService CALL_POOL =
            Executors.newCachedThreadPool(
                    new ThreadFactory() {
                        private final AtomicInteger seq = new AtomicInteger();

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "fault-call-" + seq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** 解析实际生效的限额：{@code <= 0} 一律用默认值，不再表示「无限等」 */
    public static int effectiveTimeoutMs(int configured) {
        return configured > 0 ? configured : DEFAULT_ROUND_TRIP_TIMEOUT_MS;
    }

    /** 系统属性逃生口（缺省 0 → 默认 {@value #DEFAULT_ROUND_TRIP_TIMEOUT_MS} ms） */
    static int configuredTimeoutMs(String property) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Illegal system property {}={}, fall back to {}ms",
                    property, raw, DEFAULT_ROUND_TRIP_TIMEOUT_MS);
            return 0;
        }
    }

    /** 一次 agent 调用：返回结构化结果，不抛异常 */
    interface AgentPost {
        RestfulCaller.HttpResult post(String path, String param, int timeoutMs);
    }

    /**
     * 带真实卡死线地调用 agent 的 /inject，把往返结果写进 FaultResult。
     *
     * <p>后台线程只返回 {@link RestfulCaller.HttpResult}，<b>FaultResult 由调用方线程写</b> ——
     * 这样限额到点之后迟到的孤儿调用不会篡改已经定稿的记录。
     */
    static FaultResult callAgent(String endPoint, AgentPost call, String param, int configured) {
        return callAgent(endPoint, call, param, configured, "http", null);
    }

    /**
     * 上面那个的完整版，多两个「传输方式提示」。
     *
     * <p>提示只在<b>调用方线程的卡死线先到</b>时才有用：那一刻我们连 {@code HttpResult} 都没拿到
     * （后台线程还在跑），只能靠调用方告诉归档这次走的是哪条路。否则归档里会出现一条
     * {@code transport=null} 的恢复失败——而"走的是新连接的 HTTP 还是常驻通道"恰恰是本次要分辨的。
     *
     * @param transportHint {@code "http"} 或 {@code "ws"}
     * @param channelAliveHint 走通道时发送那一刻是否 OPEN；走 HTTP 时为 {@code null}
     */
    static FaultResult callAgent(
            String endPoint,
            AgentPost call,
            String param,
            int configured,
            String transportHint,
            Boolean channelAliveHint) {
        int deadline = effectiveTimeoutMs(configured);
        long sentAt = System.currentTimeMillis();
        FaultResult faultResult = new FaultResult();
        faultResult.setEndPoint(endPoint);
        faultResult.setTimeoutMs(deadline);
        faultResult.setSentAt(sentAt);
        // 新记录一律显式给值，别留 null —— null 的语义是「老数据，没记录过往返」
        faultResult.setDeadlineExceeded(false);

        Future<RestfulCaller.HttpResult> future = null;
        try {
            future = CALL_POOL.submit(() -> call.post("/inject", param, deadline));
            RestfulCaller.HttpResult http = future.get(deadline, TimeUnit.MILLISECONDS);
            applyHttpResult(faultResult, http, endPoint);
        } catch (TimeoutException e) {
            // 卡死线先到。后台调用自己也按 deadline 收尾，谁先到是纯粹的竞态——一点也不宽限的话，
            // 这里会稳定地抢在它前面，把 "Connect timed out" 这类**传输层原因**换成那句没有信息量的
            // "No reply from agent within 10000ms"（两者以前共用同一个 deadline，原因就是这样被抹掉的）。
            RestfulCaller.HttpResult late = awaitJustPastLimit(future);
            if (late != null) {
                LOG.warn("Agent {} replied {}ms past the {}ms limit; recording the actual reply",
                        endPoint, System.currentTimeMillis() - sentAt - deadline, deadline);
                applyHttpResult(faultResult, late, endPoint);
            } else {
                // 发出去了，但限额内没有回复 —— 这就是「意料之外」，要一路报到前端
                faultResult.setReplied(false);
                faultResult.setHttpStatus(0);
                faultResult.setDeadlineExceeded(true);
                faultResult.setError(true);
                faultResult.setResult("No reply from agent within " + deadline + "ms");
                LOG.error("Agent {} sent the request but did not reply within {}ms", endPoint, deadline);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            faultResult.setReplied(false);
            faultResult.setHttpStatus(0);
            faultResult.setError(true);
            faultResult.setResult(String.valueOf(cause.getMessage()));
            LOG.error("Agent {} call failed: {}", endPoint, cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            faultResult.setReplied(false);
            faultResult.setHttpStatus(0);
            faultResult.setError(true);
            faultResult.setResult("Interrupted while waiting for agent " + endPoint);
        } finally {
            if (future != null && !future.isDone()) {
                // 孤儿调用：不再等它。它可能还会迟到地生效，所以调用方仍要照常做验证和恢复。
                future.cancel(true);
                LOG.warn("Agent {} call abandoned after {}ms; it may still land later", endPoint, deadline);
            }
            // 没拿到 HttpResult 的分支（上面那条卡死线先到）只能用调用方给的提示补齐，
            // 否则归档会分不清这次失败在新建连接上还是通道上。
            if (faultResult.getTransport() == null) {
                faultResult.setTransport(transportHint == null ? "http" : transportHint);
                faultResult.setChannelAlive(channelAliveHint);
            }
            faultResult.setRespondedAt(System.currentTimeMillis());
            faultResult.setElapsedMs(faultResult.getRespondedAt() - sentAt);
        }
        return faultResult;
    }

    /**
     * 卡死线到点后再等的一小会儿（ms），见 {@link #callAgent}。
     *
     * <p>必须<b>小</b>：它只用来消掉「谁先到」这个线程调度量级的竞态，不是放宽
     * 「限额到点即判未应答」这条语义——真慢的调用仍要如实判成未应答。
     * 200ms 也确保不会把测试里「后台睡 500ms、限额 100ms」那类场景误判成已应答。
     */
    private static final long JUST_PAST_LIMIT_MS = 200;

    /**
     * 把一次后台调用的结构化结果写进 FaultResult —— 正常返回与「刚过点捡回来」两条路共用，
     * 免得两边判定分叉。{@code FaultResult} 由调用方线程写，孤儿调用改不了它。
     */
    private static void applyHttpResult(
            FaultResult faultResult, RestfulCaller.HttpResult http, String endPoint) {
        faultResult.setReplied(http.replied);
        faultResult.setTransport(http.transport);
        faultResult.setChannelAlive(http.channelAlive);
        if (http.replied) {
            faultResult.setHttpStatus(http.status);
            faultResult.setResult(http.body);
            // 收到了回复，才谈得上按回复内容判失败
            markResult(faultResult, http.body);
        } else {
            // 连不上 / 连接或读超时：同样是「没收到回复」，与卡死线合并成同一个判定
            faultResult.setHttpStatus(0);
            faultResult.setError(true);
            faultResult.setResult("No response from agent: " + http.error);
            LOG.error("Agent {} did not respond: {}", endPoint, http.error);
        }
    }

    /** 见 {@link #JUST_PAST_LIMIT_MS}；没捡到返回 {@code null}，由调用方按未应答处理 */
    private static RestfulCaller.HttpResult awaitJustPastLimit(Future<RestfulCaller.HttpResult> future) {
        try {
            return future.get(JUST_PAST_LIMIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (ExecutionException e) {
            // 后台自己抛了：这条路的原样处理就是「未应答」，返回 null 即可
            LOG.warn("Agent call failed just past the deadline: {}", e.getCause());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 按通道健康状态挑传输：通道 OPEN 就把命令放上 t+0 建好的那条连接，否则走今天的 HTTP 路径。
     *
     * <p>刻意<b>不</b>在通道不可用时重连——重连就是"在故障窗口里新建连接"，
     * 正是本次要避开的操作。通道断掉就记下原因、退回 HTTP，让归档如实反映"这次没能骑上旧连接"。
     */
    static FaultResult callAgentOnBestTransport(String endPoint, String param, int configured) {
        AgentCommandChannel channel = ChannelRegistry.get(endPoint);
        if (channel != null) {
            if (channel.isOpen()) {
                return callAgent(endPoint, channel::post, param, configured, "ws", Boolean.TRUE);
            }
            // 只有在真的见过它（或一直在等它）时才值得刷一行；纯粹没开启时 channel 为 null
            LOG.warn("Fault channel {} is not open ({}); {} falls back to a new HTTP connection",
                    channel.getEndpoint(), channel.getClosedReason(), endPoint);
        }
        RestfulCaller caller = new RestfulCaller(endPoint);
        return callAgent(endPoint, caller::restCallerPostWithTimeoutResult, param, configured, "http", null);
    }

    public static List<FaultResult> injectFaults(Fault fault, DBService dbservice) {
        return injectFaultsWithTimeout(fault, dbservice, 0);
    }

    public static List<FaultResult> injectFaultsWithTimeout(Fault fault, DBService dbservice, int timeoutMs) {
        LOG.info("Injecting Fault: {} (timeout: {}ms)", fault, timeoutMs);
        try {
            Class.forName(dbservice.getDriverClass());
        } catch (ClassNotFoundException ex) {
            LOG.error("Injecting Fault err: Failed to initialize JDBC driver {}, err {}", dbservice.getDriverClass(), ex.getMessage());
            return Collections.emptyList();
        }
        Command injection = fault.INJECTION;
        List<RestfulCaller> callers = new ArrayList<>();
        for (String endpoint : resolveAgentHostPorts(fault, dbservice)) {
            String host = "http://" + endpoint;
            if (!fault.getInject().contains(host)) {
                callers.add(new RestfulCaller(host));
                fault.getInject().add(host);
            }
        }
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> command = new HashMap<>();
        command.put("cmd", injection.getCmd());
        command.put("args", injection.getArgs());
        // v2.2：ASYNC=true（DBChaos）→ agent /inject dispatch（启动即返回）；否则 wait（同步等待）
        command.put("mode", fault.ASYNC ? "dispatch" : "wait");
        // 注入也要取回 stdout/stderr：chaosblade 的失败原因（如 disk fill 的
        // code 46000 "path must be a directory"）只写在子进程输出里。老 agent 会忽略
        // 该字段，行为退化成原来的 None: None，不影响兼容。
        command.put("capture", true);
        try {
            String param = objectMapper.writeValueAsString(command);
            ArrayList<FaultResult> faultResults = new ArrayList<>();
            for (RestfulCaller caller : callers) {
                LOG.info("Begin to inject fault: {} (round-trip limit {}ms)",
                        param, effectiveTimeoutMs(timeoutMs));
                FaultResult faultResult =
                        callAgentOnBestTransport(caller.getHostPort(), param, timeoutMs);
                if (faultResult.isError()) {
                    LOG.error("Agent {} Fault injected err: {}",
                            caller.getHostPort(), faultResult.getResult());
                } else {
                    // 记下 chaosblade 返回的 uid：恢复若是 {{uid}} 形式，要靠它撤销
                    String uid = extractBladeUid(faultResult.getResult());
                    if (uid != null) {
                        fault.putInjectionUid(caller.getHostPort(), uid);
                        LOG.info("Fault injected with: {}, uid: {}", caller.getHostPort(), uid);
                    } else {
                        LOG.info("Fault injected with: {}", caller.getHostPort());
                    }
                }
                faultResults.add(faultResult);
            }
            return faultResults;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 解析该故障对应的 agent 端点列表（host:port），注入与验证共用同一套地址。
     */
    public static List<String> resolveAgentHostPorts(Fault fault, DBService dbservice) {
        List<String> endpoints = new ArrayList<>();
        List<Fault.FaultAgent> faultAgents = fault.faultAgents;
        for (Fault.FaultAgent faultAgent : faultAgents) {
            switch (faultAgent.getFaultPoint()) {
                case MASTER:
                    for (String masterIP : dbservice.getClusteringMasters()) {
                        endpoints.add(masterIP + ":" + faultAgent.getPort());
                    }
                    break;
                case SLAVE:
                    for (String slaveIP : dbservice.getClusteringSlaves()) {
                        endpoints.add(slaveIP + ":" + faultAgent.getPort());
                    }
                    break;
                case ALL:
                    for (String clusterMem : dbservice.getAllClusteringMems()) {
                        endpoints.add(clusterMem + ":" + faultAgent.getPort());
                    }
                    break;
                case IP:
                    endpoints.add(faultAgent.getIp() + ":" + faultAgent.getPort());
                    break;
                default:
                    break;
            }
        }
        return endpoints;
    }

    private static final ObjectMapper MARK_MAPPER = new ObjectMapper();

    /**
     * chaosblade 的失败 JSON 是扁平的 {@code {"code":63063,"success":false,"error":"..."}}，
     * 可能整串返回，也可能被 agent 包成 {@code Executed: Error 1: : {...}}（stderr 在最后一段）。
     * 这里只认同时出现 {@code "success":false} 的串，避免把成功结果里的 code 也当失败。
     */
    private static final java.util.regex.Pattern BLADE_SUCCESS_FALSE =
            java.util.regex.Pattern.compile("\"success\"\\s*:\\s*false");
    private static final java.util.regex.Pattern BLADE_CODE =
            java.util.regex.Pattern.compile("\"code\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern BLADE_ERROR =
            java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** chaosblade 找不到 chaos_os 的固定文案（原文有拼写错误 destory，按原文匹配） */
    private static final String CHAOS_OS_MISSING = "cannot get the chaos_os program";

    /**
     * 依据 agent /inject 返回的内容给 FaultResult 打错误/退出码标记，
     * 供验证阶段判断 ERROR（注入命令本身失败）。
     *
     * <p><b>要处理两种形态</b>：
     * <ol>
     *   <li>agent 的 <b>JSON 信封</b> {@code {"result":"Executed: ..."}} —— 生产路径实际收到的就是这个
     *       （{@code backend/agent/main.py} 返回 {@code {"result": result}}）。此前这里只按裸字符串判，
     *       于是 {@code startsWith("Executed:")} 永不命中，{@code error} 恒为 false、{@code returncode}
     *       恒为 null —— 也就是"失败被记成成功"。前端那枚"注入失败"徽章因此永远不亮。</li>
     *   <li>裸字符串 —— 老的调用方与单测仍按这个形态喂进来，保持兼容。</li>
     * </ol>
     *
     * <p>判为失败的几类：{@code TimeoutExpired}、{@code CommandNotFound}、{@code Error:}、
     * {@code Executed: Error <N>}、HTTP 非 200 时 RestfulCaller 返回的字面量 {@code "false"}，
     * 以及 chaosblade 自己的失败 JSON（{@code {"code":63063,"success":false,...}}，
     * 只有 agent 把子进程 stdout 回传后才拿得到）。
     */
    static void markResult(FaultResult faultResult, String result) {
        if (result == null) {
            faultResult.setError(true);
            return;
        }
        String r = unwrapAgentResult(result).trim();
        if (r.isEmpty()) {
            faultResult.setError(true);
            return;
        }
        // 先把 chaosblade 的失败详情捞出来：恢复时要靠它区分「已超时自愈」和真正的恢复失败
        markBladeFailure(faultResult, r);
        if (r.startsWith("TimeoutExpired")) {
            faultResult.setError(true);
            faultResult.setTimedOut(true);
            return;
        }
        if (r.startsWith("CommandNotFound") || r.startsWith("Error:")) {
            faultResult.setError(true);
            return;
        }
        // HTTP 非 200：RestfulCaller 原样返回字面量 "false"
        if ("false".equalsIgnoreCase(r)) {
            faultResult.setError(true);
            return;
        }
        // chaosblade 自己的失败 JSON
        if (isBladeFailure(r)) {
            faultResult.setError(true);
            return;
        }
        // exec 输出形如 "Executed: Successful: ..." / "Executed: Error 1: ..."
        if (r.startsWith("Executed: Dispatched")) {
            // v2.2 dispatch 模式：进程已后台启动（/inject 秒回），进程内成败由 /check 验证判定
            faultResult.setReturncode(null);
            return;
        }
        if (r.startsWith("Executed: Error")) {
            faultResult.setError(true);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("Executed: Error (\\d+)").matcher(r);
            if (m.find()) {
                faultResult.setReturncode(Integer.parseInt(m.group(1)));
            }
            return;
        }
        if (r.startsWith("Executed: Successful")) {
            faultResult.setReturncode(0);
        }
    }

    /** agent 的 /inject 返回 {@code {"result":"..."}} 信封；取出里面的 result。不是 JSON 就原样返回。 */
    private static String unwrapAgentResult(String raw) {
        String text = raw.trim();
        if (!text.startsWith("{")) {
            return raw;
        }
        try {
            Map<?, ?> node = MARK_MAPPER.readValue(text, Map.class);
            Object inner = node.get("result");
            // 没有 result 字段（例如 chaosblade 自己的 JSON）→ 原样返回，交给下面的规则判
            return inner == null ? raw : String.valueOf(inner);
        } catch (JsonProcessingException e) {
            return raw;
        }
    }

    /**
     * 解析结果串里的 chaosblade 失败 JSON，填 {@code bladeCode / bladeError / selfRecovered}。
     *
     * <p>必须同时满足「{@code "success":false} + code + error 文本」才算数；只凭 code 不行——
     * 实测 chaosblade 1.7.2 在 endpoint 不可达时也返回 63063，只是 error 是
     * {@code command exec failed, exit status 2}。所以 {@code selfRecovered} 只在
     * code=63063 且 error 命中 {@code cannot get the chaos_os program} 时为 true。
     */
    static void markBladeFailure(FaultResult faultResult, String text) {
        if (!BLADE_SUCCESS_FALSE.matcher(text).find()) {
            return;
        }
        java.util.regex.Matcher codeMatcher = BLADE_CODE.matcher(text);
        if (!codeMatcher.find()) {
            return;
        }
        int code = Integer.parseInt(codeMatcher.group(1));
        faultResult.setBladeCode(code);

        String error = null;
        java.util.regex.Matcher errMatcher = BLADE_ERROR.matcher(text);
        if (errMatcher.find()) {
            error = unescapeJson(errMatcher.group(1));
            faultResult.setBladeError(error);
        }
        faultResult.setSelfRecovered(
                code == 63063 && error != null && error.contains(CHAOS_OS_MISSING));
    }

    private static String unescapeJson(String raw) {
        try {
            return MARK_MAPPER.readValue("\"" + raw + "\"", String.class);
        } catch (Exception e) {
            return raw.replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }

    /** chaosblade 自己的失败 JSON：{@code {"code":63063,"success":false,"error":"..."}}。 */
    private static boolean isBladeFailure(String text) {
        if (!text.startsWith("{")) {
            return false;
        }
        try {
            Map<?, ?> node = MARK_MAPPER.readValue(text, Map.class);
            Object success = node.get("success");
            return Boolean.FALSE.equals(success) || "false".equals(String.valueOf(success));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 构造"验证中"（VERIFYING）的占位验证结果：注入返回后即写盘，
     * 前端实时页在轮询期间能看到"验证中"徽章；验证线程完成后用真实四态覆盖。
     */
    static FaultVerificationResult verifyingState(Fault fault) {
        FaultVerificationResult result = new FaultVerificationResult();
        result.setFaultID(String.valueOf(fault.ID));
        result.setFaultName(fault.faultDescription);
        result.setStatus(FaultVerificationStatus.VERIFYING);
        result.setPhase("INJECT");
        return result;
    }

    public static List<FaultResult> recoverFaults(Fault fault) {
        return recoverFaultsWithTimeout(fault, 0);
    }

    public static List<FaultResult> recoverFaultsWithTimeout(Fault fault, int timeoutMs) {
        Command recover = fault.RECOVERY;
        List<RestfulCaller> callers = new ArrayList<>();
        for (String url : fault.getInject()) {
            callers.add(new RestfulCaller(url));
        }
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            ArrayList<FaultResult> faultResults = new ArrayList<>();
            for (RestfulCaller caller : callers) {
                // 恢复命令可能带 {{uid}}（按 uid 撤销）：每个端点拿到的是各自注入返回的 uid
                List<String> args = resolveRecoveryArgs(fault, caller.getHostPort(), recover.getArgs());
                if (args == null) {
                    LOG.error("Agent {} has no injection uid; skip the uid-based recovery "
                            + "(the experiment is left to chaosblade's --timeout)",
                            caller.getHostPort());
                    faultResults.add(missingUidResult(caller.getHostPort()));
                    continue;
                }
                // 按 uid 撤销的恢复要自带硬超时：agent 到点 SIGKILL，引擎再多等余量，
                // 保证先收到「撤销超时」的回执，而不是在 10s 默认限额上先放弃。
                boolean uidRecovery = hasUidPlaceholder(recover.getArgs());
                int agentTimeoutSec = uidRecoveryAgentTimeoutSec();
                Map<String, Object> command =
                        recoveryPayload(recover, args, fault.ASYNC, uidRecovery, agentTimeoutSec);
                String param = objectMapper.writeValueAsString(command);
                int deadlineMs =
                        uidRecovery ? uidRecoveryClientDeadlineMs(timeoutMs) : effectiveTimeoutMs(timeoutMs);
                LOG.info("Begin to recover fault: {} (round-trip limit {}ms)", param, deadlineMs);
                // 恢复同样要判「有没有在限额内收到回复」—— 否则前端分不清
                // 「恢复成功 / 恢复失败 / 恢复根本没被应答」，而故障可能还留在机器上。
                // 走常驻通道而不是新建连接：t+30 那会儿新建连接正是建不起来的那个操作。
                FaultResult faultResult =
                        callAgentOnBestTransport(caller.getHostPort(), param, deadlineMs);
                if (uidRecovery) {
                    markRecordAlreadyGone(faultResult);
                    if (markUidRecoveryTimedOut(faultResult, agentTimeoutSec)) {
                        LOG.warn("Agent {} uid-based destroy did not return within {}s; "
                                + "the experiment is left to chaosblade's --timeout",
                                caller.getHostPort(), agentTimeoutSec);
                    }
                }
                if (faultResult.isError()) {
                    LOG.error("Agent {} Fault recover err: {}",
                            caller.getHostPort(), faultResult.getResult());
                } else {
                    LOG.info("Fault recover with: {}", caller.getHostPort());
                }
                faultResults.add(faultResult);
            }
            return faultResults;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 解析恢复命令里的 {@code {{uid}}} 占位。
     *
     * @return 不含占位 → 原样返回；含占位且有 uid → 替换后的参数；含占位但没拿到 uid → {@code null}
     *         （调用方跳过这条撤销，交给 chaosblade 的 {@code --timeout} 兜底）
     */
    static List<String> resolveRecoveryArgs(Fault fault, String endpoint, String[] args) {
        List<String> resolved = new ArrayList<>();
        if (args == null || args.length == 0) {
            return resolved;
        }
        if (!hasUidPlaceholder(args)) {
            Collections.addAll(resolved, args);
            return resolved;
        }
        String uid = fault == null ? null : fault.getInjectionUid(endpoint);
        if (uid == null || uid.isEmpty()) {
            return null;
        }
        for (String arg : args) {
            resolved.add(arg == null ? null : arg.replace(UID_PLACEHOLDER, uid));
        }
        return resolved;
    }

    /**
     * 恢复命令的下发体。
     *
     * <p>按 uid 撤销时额外带 {@code timeout}（秒）：agent 的 exec 到点 SIGKILL 撤销进程并回
     * {@code TimeoutExpired}，引擎才能如实记录「撤销超时」。老 agent 忽略未知字段，行为不变。
     */
    static Map<String, Object> recoveryPayload(
        Command recover, List<String> args, boolean async, boolean uidRecovery, int agentTimeoutSec) {
        Map<String, Object> command = new HashMap<>();
        command.put("cmd", recover.getCmd());
        command.put("args", args);
        // 恢复命令与注入同模式（DBChaos dispatch / chaosblade wait）
        command.put("mode", async ? "dispatch" : "wait");
        // 恢复命令要求 agent 回传 stdout/stderr：chaosblade 的 63063 / cannot get the chaos_os
        // 只写在 stderr 里，不取回来就分不清「已超时自愈」和「真正的恢复失败」。
        // 老 agent 会忽略这个字段，行为退化成原来的 None: None，不影响兼容。
        command.put("capture", true);
        if (uidRecovery) {
            command.put("timeout", agentTimeoutSec);
        }
        return command;
    }

    /** 按 uid 撤销时 agent 侧的硬超时（秒）；系统属性 {@value #UID_RECOVERY_AGENT_TIMEOUT_PROPERTY} 可覆盖 */
    static int uidRecoveryAgentTimeoutSec() {
        String raw = System.getProperty(UID_RECOVERY_AGENT_TIMEOUT_PROPERTY);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException e) {
                LOG.warn("Illegal system property {}={}, fall back to {}s",
                        UID_RECOVERY_AGENT_TIMEOUT_PROPERTY, raw,
                        DEFAULT_UID_RECOVERY_AGENT_TIMEOUT_SEC);
            }
        }
        return DEFAULT_UID_RECOVERY_AGENT_TIMEOUT_SEC;
    }

    /**
     * 按 uid 撤销时引擎的等待上限：至少要盖住 agent 的硬超时再加余量。
     * 否则默认 10s 的往返限额先放弃，agent 还在等自己的 60s，回执永远收不到。
     */
    static int uidRecoveryClientDeadlineMs(int configured) {
        return Math.max(
                effectiveTimeoutMs(configured),
                uidRecoveryAgentTimeoutSec() * 1000 + UID_RECOVERY_GRACE_MS);
    }

    /**
     * agent 到点杀掉了按 uid 的撤销：如实记成「撤销未完成」（error 保持 true，不伪造成功），
     * 只补一句说明 —— 实验本身仍由注入时追加的 {@code --timeout} 到点按 uid 清理。
     *
     * @return 是否确实是「超时」形态
     */
    static boolean markUidRecoveryTimedOut(FaultResult faultResult, int agentTimeoutSec) {
        if (faultResult == null || !faultResult.isTimedOut()) {
            return false;
        }
        String base = faultResult.getResult() == null ? "" : faultResult.getResult();
        faultResult.setResult(base
                + " [agent killed the uid-based destroy after " + agentTimeoutSec + "s; "
                + "the experiment's --timeout is the fallback cleanup]");
        return true;
    }

    /** 恢复命令模板里是否带 {@code {{uid}}} 占位 */
    private static boolean hasUidPlaceholder(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (arg != null && arg.contains(UID_PLACEHOLDER)) {
                return true;
            }
        }
        return false;
    }

    /** 注入成功但没解析出 uid 时的占位结果：明确记成「没做撤销」，不假装成功 */
    private static FaultResult missingUidResult(String endPoint) {
        FaultResult faultResult = new FaultResult();
        faultResult.setEndPoint(endPoint);
        faultResult.setReplied(false);
        faultResult.setHttpStatus(0);
        faultResult.setError(true);
        faultResult.setResult("Injection returned no uid; the uid-based recovery was skipped "
                + "(chaosblade --timeout will clean up)");
        return faultResult;
    }

    /**
     * 按 uid 撤销时，chaosblade 报 {@code 67002 record not found} = 实验记录已经不在
     * （{@code --timeout} 到点先把它撤了）。这不是恢复失败，按「已自愈」处理，
     * 和 63063/cannot get the chaos_os 的判定口径一致。
     */
    static void markRecordAlreadyGone(FaultResult faultResult) {
        if (faultResult == null || !faultResult.isError()) {
            return;
        }
        if (faultResult.getBladeCode() == null || faultResult.getBladeCode() != BLADE_RECORD_NOT_FOUND) {
            return;
        }
        faultResult.setError(false);
        faultResult.setSelfRecovered(true);
        faultResult.setResult(faultResult.getResult()
                + " [experiment record is gone: already destroyed by --timeout]");
    }

    /**
     * 从注入结果里取 chaosblade 返回的 uid。
     * 形态是 agent 信封 {@code {"result":"Executed: Successful: {\"code\":200,...,\"result\":\"<uid>\"}"}}，
     * 也可能是裸串；失败响应里没有 uid，返回 {@code null}。
     */
    static String extractBladeUid(String result) {
        if (result == null) {
            return null;
        }
        String r = unwrapAgentResult(result);
        if (r == null) {
            return null;
        }
        r = r.trim();
        if (r.isEmpty() || r.startsWith("Executed: Error")
                || r.startsWith("TimeoutExpired") || r.startsWith("CommandNotFound")) {
            return null;
        }
        // 裸 JSON / 信封里 result 直接就是 uid（老一点的结果形态）
        if (r.matches("[0-9a-zA-Z]{8,}")) {
            return r;
        }
        java.util.regex.Matcher m = BLADE_UID.matcher(r);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 归档用：把恢复命令里已经解析好的 uid 写出来（原先是占位符）。
     * 没解析到 uid 时原样返回模板，便于看出「这轮没有按 uid 撤销」。
     */
    public static String describeRecovery(Fault fault) {
        if (fault == null || fault.RECOVERY == null) {
            return null;
        }
        String[] args = fault.RECOVERY.getArgs();
        if (!hasUidPlaceholder(args) || fault.getInjectionUids().isEmpty()) {
            return fault.RECOVERY.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fault.getInjectionUids().entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(fault.RECOVERY.getCmd());
            for (String arg : args) {
                sb.append(' ').append(arg == null ? null : arg.replace(UID_PLACEHOLDER, entry.getValue()));
            }
        }
        return sb.toString();
    }

    public static class FaultRunner implements Runnable {
        private static final Logger LOG = LoggerFactory.getLogger(FaultRunner.class);

        private TestSuite testSuite;

        private DBService dbservice;

        private final Timer timer = new Timer();

        /** fixed-time 模式验证专用线程池：注入/恢复调度与验证解耦，验证不阻塞 Timer 线程 */
        private final java.util.concurrent.ExecutorService verifyExecutor =
                java.util.concurrent.Executors.newCachedThreadPool();

        private Path basePath;
        private FaultManager faultManager;
        private final String historyName;

        HashMap<Case, FaultInjectInfo> faultMap = new HashMap<>();

        public FaultRunner(
                FaultManager faultManager,
                Path basePath,
                TestSuite testSuite,
                DBService dbservice,
                String historyName) {
            this.basePath = basePath;
            this.testSuite = testSuite;
            this.dbservice = dbservice;
            this.faultManager = faultManager;
            this.historyName = historyName;

            testSuite
                    .getWorks()
                    .forEach(
                            faultCase -> {
                                FaultInjectInfo faultInjectInfo = new FaultInjectInfo();
                                String injection =
                                        faultCase.getFault().INJECTION == null
                                                ? "null"
                                                : faultCase.getFault().INJECTION.toString();
                                String recovery =
                                        faultCase.getFault().RECOVERY == null
                                                ? "null"
                                                : faultCase.getFault().RECOVERY.toString();
                                faultInjectInfo.setFaultID(String.valueOf(faultCase.getFault().ID));
                                faultInjectInfo.setFaultName(faultCase.getFault().faultDescription);
                                faultInjectInfo.setFaultDelay(faultCase.getDelay());
                                faultInjectInfo.setFaultDuration(faultCase.getDuration());
                                faultInjectInfo.setInjectionCMD(injection);
                                faultInjectInfo.setRecoveryCMD(recovery);
                                faultInjectInfo.setAutoRecovery(faultCase.getFault().AUTO_RECOVERY);
                                faultInjectInfo.setGroupKey(faultCase.getGroupKey());
                                faultMap.put(faultCase, faultInjectInfo);
                            });
        }

        /** 等通道握手的时间；连不上就不等，那条通道的调用自然退回 HTTP */
        private static final long CHANNEL_PREWARM_MS = 3000;

        /**
         * 预热命令通道：给本套件会用到的每个 agent 端点建一条常驻 WebSocket 并等握手。
         *
         * <p>由 {@code fault.channel.enabled} 控制（默认开）。握手失败不抛异常、不重试——
         * 只是这一轮用不上通道而已，行为退回改动之前。
         */
        private void prewarmChannels() {
            if (!ChannelRegistry.enabled()) {
                LOG.info(
                        "Fault channel disabled (fault.channel.enabled=false); "
                                + "injection and recovery go over newly opened HTTP connections");
                return;
            }
            Set<String> endpoints = new LinkedHashSet<>();
            for (Case work : testSuite.getWorks()) {
                endpoints.addAll(resolveAgentHostPorts(work.getFault(), dbservice));
            }
            if (endpoints.isEmpty()) {
                return;
            }
            LOG.info("Prewarming fault channels for {} endpoint(s): {}", endpoints.size(), endpoints);
            ChannelRegistry.prewarm(endpoints, CHANNEL_PREWARM_MS);
        }

        private void writeFaults(Path basePath) {
            Path faultInjectPath = basePath.resolve(PathConstant.getTmpFaultInjectPath());
            List<FaultInjectInfo> infos =
                    faultMap.values().stream()
                            .sorted(Comparator.comparingLong(FaultInjectInfo::getFaultDelay))
                            .collect(Collectors.toList());
            try {
                String content = JSONUtil.writeValueAsString(infos);
                FileUtil.writeWithLock(faultInjectPath.normalize().toString(), content);
                if (historyName != null && !historyName.isEmpty()) {
                    String historyFaultPath = PathConstant.getFaultInjectPath(historyName);
                    FileUtil.writeWithLock(historyFaultPath, content);
                }
            } catch (JsonProcessingException e) {
                LOG.error(
                        "Failed to convert the object to JSON. FaultInfo: {}, err {}", infos, e.getMessage());
            } catch (IOException e) {
                LOG.error("write to faults.json error! {}", e.getMessage());
            }
        }

        @Override
        public void run() {
            writeFaults(this.basePath);
            // 趁网络还健康，先把到各 agent 的命令通道建好。必须在注入之前——
            // 通道的价值全在"t+0 建立、t+30 复用"，晚一步就退化成今天这个失败的翻版。
            prewarmChannels();
            Queue<Case> works = testSuite.getWorks();
            LOG.info("Starting fault injection... TestSuite mode: {}", testSuite.getMode());

            // 判断是否为自动时序模式
            if (testSuite.isAutoTimingMode()) {
                runAutoTimingMode(works);
            } else {
                runFixedTimeMode(works);
            }
        }

        /**
         * 本次注入的往返限额（ms）：系统属性 &gt; testSuite 配置 &gt; 默认 10 秒。
         *
         * <p>固定时序模式拿不到 {@code <autoTiming>} 节点，但 {@link TestSuite} 会初始化一份默认配置，
         * 所以这里不会是 null。{@code <= 0} 一律落回默认值 —— 不再有「0 = 无限等」，
         * 那正是固定时序模式此前完全没有超时兜底、命令能晚到 68 秒的原因。
         */
        private int injectTimeoutMs() {
            int override = configuredTimeoutMs("fault.inject.timeoutMs");
            if (override > 0) {
                return FaultInjector.effectiveTimeoutMs(override);
            }
            AutoTimingConfig config = testSuite.getAutoTimingConfig();
            return FaultInjector.effectiveTimeoutMs(config == null ? 0 : config.getInjectTimeoutMs());
        }

        /** 本次恢复的往返限额（ms），规则同 {@link #injectTimeoutMs()} */
        private int recoverTimeoutMs() {
            int override = configuredTimeoutMs("fault.recover.timeoutMs");
            if (override > 0) {
                return FaultInjector.effectiveTimeoutMs(override);
            }
            AutoTimingConfig config = testSuite.getAutoTimingConfig();
            return FaultInjector.effectiveTimeoutMs(config == null ? 0 : config.getRecoverTimeoutMs());
        }

        /**
         * 自动时序模式：等待负载稳定后顺序注入故障
         */
        private void runAutoTimingMode(Queue<Case> works) {
            LOG.info("Running in AUTO-TIMING mode");
            AutoTimingConfig autoTimingConfig = testSuite.getAutoTimingConfig();
            autoTimingConfig.sanitize();

            // 初始化稳定性检测器
            String monitorFilePath = basePath.resolve("tmp/monitor.csv").toString();
            String metricsFilePath = historyName == null || historyName.isEmpty()
                    ? basePath.resolve("tmp/stability-metrics.csv").toString()
                    : PathConstant.getHistoryStabilityMetricsPath(historyName);
            StabilityDetector stabilityDetector =
                    new StabilityDetector(monitorFilePath, metricsFilePath, autoTimingConfig);
            writeAutoTimingConfig(autoTimingConfig);

            LOG.info("all works are {}", works);
            for (Case work : works) {
                if (work.isAutoTimingEnabled()) {
                    // 等待负载稳定
                    boolean stable = stabilityDetector.waitForStability();

                    if (!stable) {
                        LOG.warn("Stability timeout, skipping fault: {} - {}",
                                work.getFault().ID, work.getFault().faultDescription);
                        continue;  // 跳过当前，继续下一个
                    }

                    // 执行故障注入（带超时）
                    try {
                        long now = System.currentTimeMillis();
                        FaultManager.addFaultInfo(new FaultManager.FaultInfo(
                                work.getFault().faultDescription,
                                now,
                                work.getDuration() * 1000));
                        work.setStartTime(now);
                        FaultInjectInfo faultInjectInfo = faultMap.get(work);
                        faultInjectInfo.setStartTime(now);

                        stabilityDetector.markFirstInjectionMoment();

                        // 带超时的注入
                        List<FaultResult> infos =
                                FaultInjector.injectFaultsWithTimeout(
                                        work.getFault(), dbservice, injectTimeoutMs());
                        faultInjectInfo.setFaultResults(infos);
                        // 恢复命令里若有 {{uid}} 占位，注入返回后已能解析，归档里写实际下发的命令
                        faultInjectInfo.setRecoveryCMD(describeRecovery(work.getFault()));
                        LOG.info("Fault injected with: {}, result {}", work.getFault(), infos);
                        if (infos.stream().anyMatch(FaultResult::isError)) {
                            LOG.error("Fault [{}] 注入命令执行失败: {}", work.getFault().faultDescription, infos);
                        }
                        // 注入返回后立即写盘：startTime/endTime 一起入盘（红区为有限区间，与旧版一致；
                        // 不产生"endTime=0 中间态"，避免前端红区一直延伸到窗口右缘）
                        if (work.getFault().AUTO_RECOVERY) {
                            faultInjectInfo.setEndTime(now + work.getDuration() * 1000);
                        }
                        // 写盘前先落"验证中"状态：实时页轮询期间显示 VERIFYING 徽章
                        faultInjectInfo.setVerification(verifyingState(work.getFault()));
                        writeFaults(basePath);

                        // 注入完成后进行效果验证（设计文档 §6.1；窗口 = duration + grace）
                        faultInjectInfo.setVerification(
                                FaultVerifier.verifyAfterInjection(
                                        work.getFault(),
                                        infos,
                                        FaultInjector.resolveAgentHostPorts(work.getFault(), dbservice),
                                        work.getDuration() * 1000L));
                        LOG.info("Fault {} verification: {}",
                                work.getFault().faultDescription, faultInjectInfo.getVerification());

                        // 等待故障剩余时长：验证窗口已覆盖 duration+grace，
                        // 这里只补足到故障自然结束；若验证已跨过结束时刻则立即进入恢复
                        long elapsed = System.currentTimeMillis() - now;
                        long remain = work.getDuration() * 1000L - elapsed;
                        if (remain > 0) {
                            Thread.sleep(remain);
                        }
                        LOG.info("fault duration window done, elapsed={}ms", elapsed);

                        // 执行恢复（如果不是自动恢复）
                        if (!work.getFault().AUTO_RECOVERY) {
                            long end = System.currentTimeMillis();
                            List<FaultResult> recoverInfos =
                                    FaultInjector.recoverFaultsWithTimeout(
                                            work.getFault(), recoverTimeoutMs());
                            faultInjectInfo.setEndTime(end);
                            faultInjectInfo.setRecoveryResult(recoverInfos);
                            LOG.info("Fault recovered with: {} result: {}", work.getFault(), recoverInfos);
                        } else {
                            faultInjectInfo.setEndTime(now + work.getDuration() * 1000);
                        }

                        writeFaults(basePath); // 最终版：附 verification/endTime/recovery（徽章数据）
                        LOG.info("Fault injected end");

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.error("Fault injection interrupted: {}", e.getMessage());
                        break;
                    } catch (Exception e) {
                        LOG.error("Fault injection failed: {}", e.getMessage());
                        // 继续下一个故障
                    }
                } else {
                    // 兼容：如果Case不是自动时序模式，使用固定时间模式
                    scheduleFixedTimeCase(work);
                }
            }

            LOG.info("Auto-timing fault injection completed");
        }

        private void writeAutoTimingConfig(AutoTimingConfig autoTimingConfig) {
            if (historyName == null || historyName.isEmpty()) {
                return;
            }
            try {
                FileUtil.writeWithLock(
                        PathConstant.getHistoryAutoTimingConfigPath(historyName),
                        JSONUtil.writeValueAsString(autoTimingConfig));
            } catch (IOException e) {
                LOG.error("Failed to write auto timing config, err {}", e.getMessage());
            }
        }

        /**
         * 固定时间模式：使用Timer调度
         */
        private void runFixedTimeMode(Queue<Case> works) {
            LOG.info("Running in FIXED-TIME mode");
            for (Case work : works) {
                scheduleFixedTimeCase(work);
            }
        }

        /**
         * 调度单个故障（固定时间模式）
         */
        private void scheduleFixedTimeCase(Case work) {
            TimerTask injectionTask =
                    new TimerTask() {
                        public void run() {
                            try {
                                long now = System.currentTimeMillis();
                                FaultManager.addFaultInfo(new FaultManager.FaultInfo(
                                        work.getFault().faultDescription,
                                        now,
                                        work.getDuration() * 1000));
                                work.setStartTime(now);
                                FaultInjectInfo faultInjectInfo = faultMap.get(work);
                                faultInjectInfo.setStartTime(now);

                                // 固定时序模式此前调的是无超时的重载，命令卡住就无限等 ——
                                // 这就是 68.9 秒那次没有任何记录的原因，改为带往返限额
                                List<FaultResult> infos =
                                        FaultInjector.injectFaultsWithTimeout(
                                                work.getFault(), dbservice, injectTimeoutMs());
                                faultInjectInfo.setFaultResults(infos);
                                // 恢复命令里若有 {{uid}} 占位，注入返回后已能解析，归档里写实际下发的命令
                                faultInjectInfo.setRecoveryCMD(describeRecovery(work.getFault()));
                                LOG.info("Fault injected with: {}, result {}", work.getFault(), infos);
                                if (infos.stream().anyMatch(FaultResult::isError)) {
                                    LOG.error("Fault [{}] 注入命令执行失败: {}", work.getFault().faultDescription, infos);
                                }
                                // 注入返回后立即写盘：startTime/endTime 一起入盘（红区为有限区间，与旧版一致；
                                // 不产生"endTime=0 中间态"，避免前端红区一直延伸到窗口右缘）
                                if (work.getFault().AUTO_RECOVERY) {
                                    faultInjectInfo.setEndTime(now + work.getDuration() * 1000);
                                }
                                // 写盘前先落"验证中"状态：实时页轮询期间显示 VERIFYING 徽章
                                faultInjectInfo.setVerification(verifyingState(work.getFault()));
                                writeFaults(basePath);

                                // 验证异步执行：不阻塞 Timer 调度线程（否则首个故障的验证窗口
                                // 会推迟后续故障的注入/恢复，造成"第一个故障占据、其他故障不显示"）
                                verifyExecutor.execute(() -> {
                                    try {
                                        FaultVerificationResult verification =
                                                FaultVerifier.verifyAfterInjection(
                                                        work.getFault(),
                                                        infos,
                                                        FaultInjector.resolveAgentHostPorts(work.getFault(), dbservice),
                                                        work.getDuration() * 1000L);
                                        faultInjectInfo.setVerification(verification);
                                        LOG.info("Fault {} verification: {}",
                                                work.getFault().faultDescription, verification);
                                        // 最终版：附 verification（徽章数据）
                                        writeFaults(basePath);
                                    } catch (Exception e) {
                                        LOG.error("Fault verification failed: {}", e.getMessage());
                                    }
                                });
                            } catch (Exception e) {
                                LOG.error("Fault injection failed: {}", e.getMessage());
                            }
                        }
                    };

            // 异步创建并安排恢复任务，不等待注入完成
            if (!work.getFault().AUTO_RECOVERY) {
                TimerTask recoveryTask =
                        new TimerTask() {
                            public void run() {
                                try {
                                    long now = System.currentTimeMillis();
                                    List<FaultResult> recoverInfos =
                                            FaultInjector.recoverFaultsWithTimeout(
                                                    work.getFault(), recoverTimeoutMs());
                                    faultManager.getMemento().removeRecoverTask(this);
                                    FaultInjectInfo faultInjectInfo = faultMap.get(work);
                                    faultInjectInfo.setEndTime(now);
                                    faultInjectInfo.setRecoveryResult(recoverInfos);
                                    writeFaults(basePath);
                                    LOG.info(
                                            "Fault recovered with: {} result: {}",
                                            work.getFault(),
                                            recoverInfos);
                                } catch (Exception e) {
                                    LOG.error("Fault recovering failed: {}", e.getMessage());
                                }
                            }
                        };
                faultManager.getMemento().addRecoverTask(recoveryTask);
                // 计算恢复任务的执行时间：延迟时间 + 持续时间
                long recoveryDelay = work.getDelay() * 1000 + work.getDuration() * 1000;
                timer.schedule(recoveryTask, recoveryDelay);
            }

            // 安排注入任务
            timer.schedule(injectionTask, work.getDelay() * 1000);
        }

        public void close() {
            timer.cancel();
            verifyExecutor.shutdown();
        }
    }
}
