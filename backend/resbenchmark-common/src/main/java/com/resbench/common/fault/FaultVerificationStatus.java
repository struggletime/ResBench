package com.resbench.common.fault;

/**
 * 故障验证状态（设计文档 §4.1；VERIFYING 为 v2.2 实时页中间态）。
 */
public enum FaultVerificationStatus {
  /** 自检 enforced=true：故障效果在数据库上达成 */
  ENFORCED,
  /** 注入命令成功，但自检持续 enforced=false 至超时（注入了没效果） */
  NOT_ENFORCED,
  /**
   * 注入命令本身失败（收到了回复，但 returncode!=0 / chaosblade 内部报错），
   * 或注入未在限额内收到应答、且自检也无法判定 agent 状态（error 或超时）。
   *
   * <p>注意：单纯的「没收到应答」<b>不再</b>直接判 ERROR —— 应答丢了不等于故障没生效，
   * 会继续轮询自检，确认生效则改判 {@link #ENFORCED}（并在结论里注明注入未应答）。
   */
  ERROR,
  /** 未配置 check 命令 / check 返回不合法 / 故障类型暂不支持自检 */
  UNKNOWN,
  /** 已注入、验证轮询进行中（实时页"验证中"徽章） */
  VERIFYING
}