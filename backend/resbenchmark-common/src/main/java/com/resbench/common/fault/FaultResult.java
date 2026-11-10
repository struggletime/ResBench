package com.resbench.common.fault;

/**
 * 一条注入/恢复命令在某个 agent 上的执行结果。
 *
 * <p>判定「这次注入/恢复成没成」的<b>主判据是往返存活</b>：请求发出去了，并且在限额内
 * 收到了 agent 的回复（{@link #replied}）。回复的<b>内容</b>（退出码、chaosblade 的 JSON）
 * 只作为附加信息（{@link #error} / {@link #returncode}）——内容不可靠，但「有没有回复」是确定的。
 *
 * <p>这些字段一律用包装类型：老归档文件里没有它们，反序列化后应当保持 {@code null}
 * （=「老数据，没记录过往返」），而不是被当成 {@code false}（=「确认没回复」）。
 */
public class FaultResult {
  private String endPoint;

  private String result;

  /** 是否失败（agent 不可达 / returncode!=0 / 超时 / chaosblade 内部失败），供验证阶段 ERROR 判定 */
  private boolean error;

  /** 注入命令退出码；未知时为 null */
  private Integer returncode;

  /** agent 自己的命令超时（它回了 {@code TimeoutExpired}）—— 注意那<b>是一个回复</b> */
  private boolean timedOut;

  // ---- chaosblade 失败详情（agent 回传 stdout/stderr 后才有） ----

  /** chaosblade 失败 JSON 里的 code（如 63063）；没解析到为 null */
  private Integer bladeCode;

  /** chaosblade 失败 JSON 里的 error 文本；没解析到为 null */
  private String bladeError;

  /**
   * 恢复时 chaosblade 报 {@code 63063 / cannot get the chaos_os program}：
   * 找不到 chaos_os 进程，说明故障在恢复命令到达前已经结束（超时自愈）。
   * <b>只有 code + 这段特定文本同时命中才置 true</b>——63063 也可能只是命令执行失败，
   * 单看 code 会把真正的恢复失败误判成自愈。
   */
  private Boolean selfRecovered;

  // ---- 往返存活（本次新增的判定依据） ----

  /** 是否在限额内收到了 agent 的 HTTP 回复。{@code null} = 老数据，没记录过 */
  private Boolean replied;

  /** HTTP 状态码；没收到响应时为 0 */
  private Integer httpStatus;

  /** 请求提交时刻（epoch ms） */
  private Long sentAt;

  /** 拿到回复 / 判定超时 / 捕获异常的时刻（epoch ms） */
  private Long respondedAt;

  /** 从提交到返回的耗时（ms）—— 无论成功还是失败都要给前端看 */
  private Long elapsedMs;

  /** 本次实际生效的限额（ms） */
  private Integer timeoutMs;

  /** 是否是因为超出限额而放弃等待（发出了但没在限额内收到回复） */
  private Boolean deadlineExceeded;

  // ---- 走的是哪条路（用来区分「新建连接失败」与「通道上的别的问题」） ----

  /** {@code "http"}（每次新建 TCP 连接）或 {@code "ws"}（常驻命令通道）；{@code null} = 老数据 */
  private String transport;

  /** 走常驻通道时，发送那一刻通道是否 OPEN；走 HTTP 或老数据时为 null */
  private Boolean channelAlive;

  public FaultResult() {}

  public FaultResult(String endPoint, String result) {
    this.endPoint = endPoint;
    this.result = result;
  }

  public String getEndPoint() {
    return endPoint;
  }

  public void setEndPoint(String endPoint) {
    this.endPoint = endPoint;
  }

  public String getResult() {
    return result;
  }

  public void setResult(String result) {
    this.result = result;
  }

  public boolean isError() {
    return error;
  }

  public void setError(boolean error) {
    this.error = error;
  }

  public Integer getReturncode() {
    return returncode;
  }

  public void setReturncode(Integer returncode) {
    this.returncode = returncode;
  }

  public boolean isTimedOut() {
    return timedOut;
  }

  public void setTimedOut(boolean timedOut) {
    this.timedOut = timedOut;
  }

  public Integer getBladeCode() {
    return bladeCode;
  }

  public void setBladeCode(Integer bladeCode) {
    this.bladeCode = bladeCode;
  }

  public String getBladeError() {
    return bladeError;
  }

  public void setBladeError(String bladeError) {
    this.bladeError = bladeError;
  }

  public Boolean getSelfRecovered() {
    return selfRecovered;
  }

  public void setSelfRecovered(Boolean selfRecovered) {
    this.selfRecovered = selfRecovered;
  }

  public Boolean getReplied() {
    return replied;
  }

  public void setReplied(Boolean replied) {
    this.replied = replied;
  }

  public Integer getHttpStatus() {
    return httpStatus;
  }

  public void setHttpStatus(Integer httpStatus) {
    this.httpStatus = httpStatus;
  }

  public Long getSentAt() {
    return sentAt;
  }

  public void setSentAt(Long sentAt) {
    this.sentAt = sentAt;
  }

  public Long getRespondedAt() {
    return respondedAt;
  }

  public void setRespondedAt(Long respondedAt) {
    this.respondedAt = respondedAt;
  }

  public Long getElapsedMs() {
    return elapsedMs;
  }

  public void setElapsedMs(Long elapsedMs) {
    this.elapsedMs = elapsedMs;
  }

  public Integer getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(Integer timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  public Boolean getDeadlineExceeded() {
    return deadlineExceeded;
  }

  public void setDeadlineExceeded(Boolean deadlineExceeded) {
    this.deadlineExceeded = deadlineExceeded;
  }

  public String getTransport() {
    return transport;
  }

  public void setTransport(String transport) {
    this.transport = transport;
  }

  public Boolean getChannelAlive() {
    return channelAlive;
  }

  public void setChannelAlive(Boolean channelAlive) {
    this.channelAlive = channelAlive;
  }

  /** 确认收到了 agent 的回复 */
  public boolean hasReply() {
    return Boolean.TRUE.equals(replied);
  }

  /** 确认没收到回复（发出了但限额内没有应答 / 连不上） */
  public boolean hasNoReply() {
    return Boolean.FALSE.equals(replied);
  }

  /** 老归档数据：没记录过往返，只能退回按内容判 */
  public boolean isLegacyResult() {
    return replied == null;
  }

  @Override
  public String toString() {
    return "FaultResult{"
        + "endPoint='" + endPoint + '\''
        + ", result='" + result + '\''
        + ", error=" + error
        + ", returncode=" + returncode
        + ", timedOut=" + timedOut
        + ", bladeCode=" + bladeCode
        + ", bladeError='" + bladeError + '\''
        + ", selfRecovered=" + selfRecovered
        + ", replied=" + replied
        + ", httpStatus=" + httpStatus
        + ", sentAt=" + sentAt
        + ", respondedAt=" + respondedAt
        + ", elapsedMs=" + elapsedMs
        + ", timeoutMs=" + timeoutMs
        + ", deadlineExceeded=" + deadlineExceeded
        + ", transport=" + transport
        + ", channelAlive=" + channelAlive
        + '}';
  }
}
