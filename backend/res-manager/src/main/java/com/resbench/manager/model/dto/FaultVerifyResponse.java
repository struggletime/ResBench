package com.resbench.manager.model.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 与 benchmark 侧 FaultVerificationResult 对齐的网关 DTO */
@Getter
@Setter
public class FaultVerifyResponse {
  private String faultID;
  private String faultName;
  private String nameKey;
  private String phase;
  private String status;
  private String summary;
  private List<CheckEvidenceResponse> evidence;
  private String endPoint;
  private long warmupMs;
  private long pollIntervalMs;
  private long timeoutMs;
  private long verifiedAt;
  private String error;
}