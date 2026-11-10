package com.resbench.manager.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class FaultInfoResponse {
  private String faultID;

  private String faultName;

  private long faultDelay;

  private long faultDuration;

  private String injectionCMD;

  private String recoveryCMD;

  private boolean isAutoRecovery;

  private long startTime;

  private String startTimeFormat;

  private long endTime;

  private String endTimeFormat;

  private String groupKey;

  /** 注入命令在各自 agent 上的执行结果（含返回原文与成败） */
  private List<FaultResultResponse> faultResults;

  /**
   * 恢复命令的执行结果。
   *
   * <p><b>为 null 的语义要看 isAutoRecovery</b>：自动恢复的故障引擎本来就不发恢复命令；
   * 非自动恢复的故障在恢复到点前也是 null —— "未发出"要结合 startTime + faultDuration 判断。
   */
  private List<FaultResultResponse> recoveryResult;

  /** 注入后验证结果（v2.1 故障验证机制） */
  private FaultVerifyResponse verification;

  private FaultVerifyResponse recoveryVerification;
}
