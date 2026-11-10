package com.resbench.common.fault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.remote.RestfulCaller;
import com.resbench.common.remote.exception.RemoteAccessException;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一验证执行器（设计文档 §6）。
 *
 * <p>注入成功后由 {@link FaultInjector.FaultRunner} 调用；也供 REST 手动补验使用
 * （{@link #verifyOnce}）。框架不解析故障细节，只按统一协议轮询 agent /check。
 *
 * <p>配置项（系统属性，缺省即默认值；优先级：XML <check> 属性 > 系统属性 > 默认）：
 * <ul>
 *   <li>fault.verification.enabled=true|false</li>
 *   <li>fault.verification.warmupMs=3000</li>
 *   <li>fault.verification.pollIntervalMs=7000</li>
 *   <li>fault.verification.timeoutMs 不设置(0)时用公式：clamp(故障时长+30s, 7s, 300s)；显式设置则绝对覆盖</li>
 *   <li>fault.verification.maxRequestTimeoutMs=15000</li>
 * </ul>
 */
public class FaultVerifier {
  private static final Logger LOG = LoggerFactory.getLogger(FaultVerifier.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * v2.2 §6.4：验证窗口与故障持续时间联动 —— 故障恢复（duration 到点）即终止轮询、给出结果，
   * 不再额外等待 grace：effectiveTimeoutMs = clamp(durationMs, minTimeoutMs(7s), capMs(300s))。
   *
   * <p>explicit &gt; 0（系统属性或 per-fault XML 覆盖）时绝对生效。
   */
  private static final long DEFAULT_CAP_MS = 300_000L;
  private static final long DEFAULT_MIN_TIMEOUT_MS = 7_000L;

  private FaultVerifier() {}

  public static boolean isEnabled() {
    return Boolean.parseBoolean(System.getProperty("fault.verification.enabled", "true"));
  }

  public static long warmupMs() {
    return Long.parseLong(System.getProperty("fault.verification.warmupMs", "3000"));
  }

  public static long pollIntervalMs() {
    return Long.parseLong(System.getProperty("fault.verification.pollIntervalMs", "7000"));
  }

  /** 显式窗口（ms）；0 = 未设置 → 用 {@link #effectiveTimeoutMs} 公式 */
  public static long timeoutMs() {
    return Long.parseLong(System.getProperty("fault.verification.timeoutMs", "0"));
  }

  public static int maxRequestTimeoutMs() {
    return Integer.parseInt(System.getProperty("fault.verification.maxRequestTimeoutMs", "15000"));
  }

  /**
   * v2.2 §6.4：验证窗口与故障持续时间联动。
   *
   * <p>explicit &gt; 0（系统属性或 per-fault XML 覆盖）时绝对生效；
   * 否则 effectiveTimeoutMs = clamp(durationMs, minTimeoutMs(7s), capMs(300s))。
   * 窗口在故障恢复（duration 到点）时结束，立即给出最终结论，不再追加 grace。
   */
  public static long effectiveTimeoutMs(long explicit, long durationMs) {
    if (explicit > 0) {
      return explicit;
    }
    long timeout = Math.max(durationMs, 0);
    timeout = Math.max(timeout, DEFAULT_MIN_TIMEOUT_MS);
    return Math.min(timeout, DEFAULT_CAP_MS);
  }

  private static FaultVerificationResult newBaseResult(Fault fault, String phase) {
    FaultVerificationResult result = new FaultVerificationResult();
    result.setFaultID(String.valueOf(fault.ID));
    result.setFaultName(fault.faultDescription);
    result.setNameKey(fault.faultDescription); // 框架侧 nameKey 如需精确值由配置层填充
    result.setPhase(phase);
    result.setWarmupMs(warmupMs());
    result.setPollIntervalMs(pollIntervalMs());
    result.setTimeoutMs(timeoutMs());
    return result;
  }

  /**
   * 注入成功后验证（设计文档 §6.1 流程；窗口时长见 v2.2 §6.4）。
   *
   * @param fault 已注入故障（含 CHECK 命令）
   * @param injectResults 注入结果，用于 ERROR 判定
   * @param agentEndpoints agent 地址列表（host:port），与注入时一致
   * @param durationMs 本故障持续时间（ms），用于联动验证窗口（durationMs + grace，clamp 后）
   * @return 验证结果；fault.verification.enabled=false 时返回 null（完全恢复旧行为）
   */
  public static FaultVerificationResult verifyAfterInjection(
      Fault fault,
      List<FaultResult> injectResults,
      List<String> agentEndpoints,
      long durationMs) {
    if (!isEnabled()) {
      LOG.info("[FaultVerifier] fault.verification.enabled=false, skip verification for {}",
          fault.faultDescription);
      return null;
    }
    FaultVerificationResult result = newBaseResult(fault, "INJECT");

    if (fault.CHECK == null || fault.CHECK.getCmd() == null || fault.CHECK.getCmd().trim().isEmpty()) {
      result.setStatus(FaultVerificationStatus.UNKNOWN);
      result.setError("未配置自检命令（无 check 模板）");
      LOG.info("[FaultVerifier] {} has no check command -> UNKNOWN", fault.faultDescription);
      return result;
    }

    // 注入命令本身失败（收到了回复、但命令报错）：不进入验证阶段（ERROR）
    if (injectResults != null
        && injectResults.stream().anyMatch(r -> r.isError() && !r.hasNoReply())) {
      result.setStatus(FaultVerificationStatus.ERROR);
      result.setError("注入命令本身失败，不进入验证阶段");
      LOG.warn("[FaultVerifier] injection failed for {}, verification=ERROR", fault.faultDescription);
      return result;
    }

    // 「没收到应答」不等于「故障没生效」—— 请求可能已经在 agent 上生效了，只是应答丢在链路上。
    // 所以这里<b>不短路</b>，继续轮询 /check：真生效了就必须说出来，
    // 否则一个已经种下去的故障会悄无声息地留在机器上（恢复早已发过，不会再来一次）。
    String noReplyNote = null;
    if (injectResults != null) {
      for (FaultResult injectResult : injectResults) {
        if (injectResult.hasNoReply()) {
          noReplyNote = "注入未在 " + injectResult.getTimeoutMs() + "ms 内收到 agent 应答";
          LOG.warn(
              "[FaultVerifier] {} inject had no reply within {}ms; verification continues anyway",
              fault.faultDescription, injectResult.getTimeoutMs());
          break;
        }
      }
    }

    if (agentEndpoints == null || agentEndpoints.isEmpty()) {
      result.setStatus(FaultVerificationStatus.UNKNOWN);
      result.setError("无可用 agent 端点");
      return result;
    }

    // v2.2 §6.3/§6.4：窗口 = 故障时长，故障恢复（duration 到点）即出结果，clamp [7s, 300s]；
    // 优先级 per-fault (XML <check> 属性) > 系统属性 > 默认
    long overrideTimeout = fault.CHECK_TIMEOUT_MS != null ? fault.CHECK_TIMEOUT_MS : timeoutMs();
    long timeout = effectiveTimeoutMs(overrideTimeout, durationMs);
    long warmup = fault.CHECK_WARMUP_MS != null
        ? fault.CHECK_WARMUP_MS
        : Math.min(warmupMs(), Math.max(timeout, 1));
    long poll = fault.CHECK_POLL_INTERVAL_MS != null
        ? fault.CHECK_POLL_INTERVAL_MS
        : Math.min(pollIntervalMs(), Math.max(timeout, 1));
    result.setWarmupMs(warmup);
    result.setPollIntervalMs(poll);
    result.setTimeoutMs(timeout);

    long deadline = System.currentTimeMillis() + timeout;
    sleep(warmup);

    String lastError = null;
    boolean sawValidResponse = false;
    CheckEvidenceList lastEvidence = null;
    while (System.currentTimeMillis() < deadline) {
      for (String endpoint : agentEndpoints) {
        try {
          String body = postCheck(endpoint, fault.CHECK);
          CheckResponse parsed = parseCheckResponse(body);
          if (parsed.error != null) {
            // 语义（v2.2 修订）：agent 明确返回了错误值（非 JSON 输出 / rc!=0 / agent
            // 明确报错）= 轮询功能出问题 → ERROR（错误），不是未知；同一 check 命令重试
            // 仍是同一错误，立即终止，不再空等窗口。
            LOG.warn("[FaultVerifier] {} check error from {}: {}", fault.faultDescription, endpoint, parsed.error);
            result.setStatus(FaultVerificationStatus.ERROR);
            result.setError(parsed.error);
            result.setVerifiedAt(System.currentTimeMillis());
            return result;
          }
          sawValidResponse = true;
          lastEvidence = parsed.evidence;
          if (Boolean.TRUE.equals(parsed.enforced)) {
            result.setStatus(FaultVerificationStatus.ENFORCED);
            result.setSummary(
                noReplyNote == null
                    ? parsed.summary
                    : noReplyNote + "，但自检显示故障已生效"
                        + (parsed.summary == null ? "" : "：" + parsed.summary));
            result.setEvidence(parsed.evidence.items);
            result.setEndPoint(endpoint);
            result.setVerifiedAt(System.currentTimeMillis());
            LOG.info("[FaultVerifier] {} ENFORCED via {}: {}", fault.faultDescription, endpoint, parsed.summary);
            return result;
          }
          LOG.info("[FaultVerifier] {} not enforced yet from {}: {}", fault.faultDescription, endpoint, parsed.summary);
        } catch (RuntimeException e) {
          // 语义（v2.2 修订）：超时 / 连接失败 / 读超时 —— 完全不知道 agent 端发生了什么 → 未知；
          // 可能是瞬时网络问题，继续重试到窗口结束，窗口耗尽仍无有效响应则判为 UNKNOWN。
          String msg = String.valueOf(e.getMessage());
          lastError = msg;
          LOG.warn("[FaultVerifier] {} check call failed from {}: {}", fault.faultDescription, endpoint, msg);
        }
      }
      if (deadline - System.currentTimeMillis() > 0) {
        sleep(Math.min(poll, deadline - System.currentTimeMillis()));
      }
    }

    result.setVerifiedAt(System.currentTimeMillis());
    if (sawValidResponse) {
      result.setStatus(FaultVerificationStatus.NOT_ENFORCED);
      result.setSummary("轮询至超时（" + timeout + "ms），故障效果始终未达成");
      result.setError(noReplyNote == null ? lastError : noReplyNote + "；" + lastError);
      if (lastEvidence != null) {
        result.setEvidence(lastEvidence.items);
      }
      LOG.warn("[FaultVerifier] {} NOT_ENFORCED after {}ms", fault.faultDescription, timeout);
    } else if (noReplyNote != null) {
      // 应答没收到、自检又给不出结论 —— 归到 ERROR（它的定义本就包含「/inject 超时」）
      result.setStatus(FaultVerificationStatus.ERROR);
      result.setError(noReplyNote + "，且自检无法判定 agent 状态: "
          + (lastError == null ? "unknown" : lastError));
      LOG.warn("[FaultVerifier] {} ERROR (no inject reply, no usable check): {}",
          fault.faultDescription, lastError);
    } else {
      result.setStatus(FaultVerificationStatus.UNKNOWN);
      result.setError("未知：自检调用超时/异常，无法获知 agent 状态: " + (lastError == null ? "unknown" : lastError));
      LOG.warn("[FaultVerification] {} UNKNOWN (no valid check response): {}", fault.faultDescription, lastError);
    }
    return result;
  }

  /**
   * 手动/单次补验：即时执行一次 check（REST POST /web/fault/verify 使用）。
   *
   * @param checkCommand 已渲染好的 check 命令
   * @param hostPort agent 地址（ip:port）
   */
  public static FaultVerificationResult verifyOnce(
      String faultID, String faultName, String nameKey, Command checkCommand, String hostPort) {
    FaultVerificationResult result = new FaultVerificationResult();
    result.setFaultID(faultID);
    result.setFaultName(faultName);
    result.setNameKey(nameKey);
    result.setPhase("INJECT");
    result.setWarmupMs(0);
    result.setPollIntervalMs(0);
    result.setTimeoutMs(0);
    result.setEndPoint(hostPort);
    result.setVerifiedAt(System.currentTimeMillis());

    if (checkCommand == null || checkCommand.getCmd() == null || checkCommand.getCmd().trim().isEmpty()) {
      result.setStatus(FaultVerificationStatus.UNKNOWN);
      result.setError("未配置自检命令（无 check 模板）");
      return result;
    }

    try {
      String body = postCheck(hostPort, checkCommand);
      CheckResponse parsed = parseCheckResponse(body);
      if (parsed.error != null) {
        // 明确错误值（非 JSON / rc!=0 / agent 明确报错）→ ERROR，不是未知
        result.setStatus(FaultVerificationStatus.ERROR);
        result.setError(parsed.error);
      } else if (Boolean.TRUE.equals(parsed.enforced)) {
        result.setStatus(FaultVerificationStatus.ENFORCED);
        result.setSummary(parsed.summary);
        result.setEvidence(parsed.evidence.items);
      } else {
        result.setStatus(FaultVerificationStatus.NOT_ENFORCED);
        result.setSummary(parsed.summary);
        result.setEvidence(parsed.evidence.items);
      }
    } catch (RuntimeException e) {
      result.setStatus(FaultVerificationStatus.UNKNOWN);
      result.setError("check 调用失败: " + e.getMessage());
    }
    return result;
  }

  // ---------------------------------------------------------------------
  // agent /check 调用与契约解析
  // ---------------------------------------------------------------------

  private static String postCheck(String hostPort, Command check) throws RemoteAccessException {
    java.util.Map<String, Object> command = new java.util.HashMap<>();
    command.put("cmd", check.getCmd());
    command.put("args", check.getArgs());
    String param;
    try {
      param = MAPPER.writeValueAsString(command);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    RestfulCaller caller = new RestfulCaller("http://" + hostPort);
    return caller.restCallerPostWithTimeout("/check", param, maxRequestTimeoutMs());
  }

  static CheckResponse parseCheckResponse(String body) {
    if (body == null || body.trim().isEmpty() || "false".equals(body.trim())) {
      return CheckResponse.error("empty/invalid response from agent");
    }
    try {
      JsonNode node = MAPPER.readTree(body);
      if (node == null || !node.has("enforced") || !node.get("enforced").isBoolean()) {
        String error = node != null && node.has("error") ? node.get("error").asText() : "missing enforced field";
        return CheckResponse.error(error);
      }
      String summary = node.has("summary") && !node.get("summary").isNull()
          ? node.get("summary").asText() : null;
      CheckEvidenceList evidence = new CheckEvidenceList();
      JsonNode checks = node.get("checks");
      if (checks != null && checks.isArray()) {
        for (JsonNode item : checks) {
          evidence.items.add(new CheckEvidence(
              item.has("name") ? item.get("name").asText() : null,
              item.has("observed") ? item.get("observed").asText() : null,
              item.has("expected") ? item.get("expected").asText() : null,
              item.has("matched") && item.get("matched").asBoolean()));
        }
      }
      return CheckResponse.ok(node.get("enforced").asBoolean(), summary, evidence);
    } catch (Exception e) {
      return CheckResponse.error("invalid self-check output: " + abbreviate(body));
    }
  }

  private static String abbreviate(String text) {
    String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
    return t.length() > 200 ? t.substring(0, 200) : t;
  }

  private static void sleep(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static final class CheckEvidenceList {
    final List<CheckEvidence> items = new ArrayList<>();
  }

  static final class CheckResponse {
    final Boolean enforced;
    final String summary;
    final CheckEvidenceList evidence;
    final String error;

    private CheckResponse(Boolean enforced, String summary, CheckEvidenceList evidence, String error) {
      this.enforced = enforced;
      this.summary = summary;
      this.evidence = evidence;
      this.error = error;
    }

    static CheckResponse ok(boolean enforced, String summary, CheckEvidenceList evidence) {
      return new CheckResponse(enforced, summary, evidence, null);
    }

    static CheckResponse error(String error) {
      return new CheckResponse(null, null, null, error);
    }
  }
}