package com.resbench.manager.model.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 一条注入/恢复命令在某个 agent 上的执行结果（引擎 {@code FaultResult} 的网关镜像）。
 *
 * <p>网关此前没有这两个字段，引擎返回的 {@code faultResults} / {@code recoveryResult} 在反序列化时
 * 被<b>静默丢弃</b>，前端因此永远看不到"注入成功没成功"——那枚"注入失败"徽章从不亮。
 */
@Getter
@Setter
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class FaultResultResponse {
  /** agent 地址 */
  private String endPoint;

  /** 命令返回的原文（agent 的 {@code {"result":"..."}} 信封） */
  private String result;

  /** 是否失败（agent 不可达 / 退出码非 0 / 超时 / chaosblade 内部失败） */
  private boolean error;

  /** 退出码；未知时为 null */
  private Integer returncode;

  private boolean timedOut;

  // ---- chaosblade 失败详情（agent 回传 stdout/stderr 后才有） ----

  /** chaosblade 失败 JSON 里的 code（如 63063）；没解析到为 null */
  private Integer bladeCode;

  /** chaosblade 失败 JSON 里的 error 文本；没解析到为 null */
  private String bladeError;

  /**
   * 恢复时 chaosblade 报 63063 / {@code cannot get the chaos_os program}：
   * 找不到 chaos_os 进程，故障在恢复命令到达前已结束（超时自愈）。
   * 只有 code + 这段特定文本同时命中才为 true，单看 code 会和真正的恢复失败混淆。
   */
  private Boolean selfRecovered;

  // ---- 往返存活：判定「这次注入/恢复成没成」的主依据 ----
  // 一律用包装类型：老归档没有这些字段，反序列化后要保持 null（= 老数据），
  // 用原始类型会被填成 false/0，前端就会把每条老记录都读成「未应答」。

  /** 是否在限额内收到了 agent 的 HTTP 回复；{@code null} = 老数据，没记录过 */
  private Boolean replied;

  /** HTTP 状态码；没收到响应时为 0 */
  private Integer httpStatus;

  /** 请求提交时刻（epoch ms） */
  private Long sentAt;

  /** 拿到回复 / 判定超时 / 捕获异常的时刻（epoch ms） */
  private Long respondedAt;

  /** 从提交到返回的耗时（ms）—— 成功失败都要给前端看 */
  private Long elapsedMs;

  /** 本次实际生效的限额（ms） */
  private Integer timeoutMs;

  /** 是否因为超出限额而放弃等待 */
  private Boolean deadlineExceeded;

  // ---- 骑的是哪条路：常驻 WebSocket 命令通道，还是每次都新建的 HTTP 连接 ----
  // 同上一组，一律用包装类型：老归档没有这两个键，反序列化后要保持 null（= 老数据），
  // 用原始类型会被填成 false，前端就会把每条老记录都读成「没走通道」。

  /**
   * {@code "ws"}（骑在 t+0 建好的常驻命令通道上）或 {@code "http"}（每次新建 TCP 连接）；
   * {@code null} = 老归档没记过。
   *
   * <p>这是「长连接到底有没有用上」的唯一判据：filemove 恢复失败的根因就是在故障窗口里新建连接
   * 建不起来，而 {@code "http"} 恰恰意味着这一次又新建了连接。
   */
  private String transport;

  /**
   * 走常驻通道时，发送那一刻通道是否 OPEN；走 HTTP 或老归档时为 {@code null}。
   *
   * <p>注意 {@code transport="ws"} 且 {@code channelAlive=false} 是可能的：健康检查与发送之间
   * 通道刚好断掉。这时命令<b>并没有</b>骑上通道，不能算「用上了长连接」。
   */
  private Boolean channelAlive;
}
