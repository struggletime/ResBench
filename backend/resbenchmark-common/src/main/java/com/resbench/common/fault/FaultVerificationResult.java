package com.resbench.common.fault;

import java.util.ArrayList;
import java.util.List;

/**
 * 故障验证结果（设计文档 §4.2）。
 * 挂载于 {@link FaultInjectInfo#getVerification()}，随 getFaultInjectionInfo 一起返回前端。
 */
public class FaultVerificationResult {
  private String faultID;
  private String faultName;
  /** 如 faultinject.fault.txn.deadlock_storm */
  private String nameKey;
  /** INJECT | RECOVER（本期只做 INJECT） */
  private String phase;
  private FaultVerificationStatus status;
  private String summary;
  private List<CheckEvidence> evidence;
  /** 执行自检的 agent 地址 */
  private String endPoint;
  private long warmupMs;
  private long pollIntervalMs;
  private long timeoutMs;
  private long verifiedAt;
  private String error;

  public FaultVerificationResult() {
    this.evidence = new ArrayList<>();
  }

  public String getFaultID() {
    return faultID;
  }

  public void setFaultID(String faultID) {
    this.faultID = faultID;
  }

  public String getFaultName() {
    return faultName;
  }

  public void setFaultName(String faultName) {
    this.faultName = faultName;
  }

  public String getNameKey() {
    return nameKey;
  }

  public void setNameKey(String nameKey) {
    this.nameKey = nameKey;
  }

  public String getPhase() {
    return phase;
  }

  public void setPhase(String phase) {
    this.phase = phase;
  }

  public FaultVerificationStatus getStatus() {
    return status;
  }

  public void setStatus(FaultVerificationStatus status) {
    this.status = status;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public List<CheckEvidence> getEvidence() {
    return evidence;
  }

  public void setEvidence(List<CheckEvidence> evidence) {
    this.evidence = evidence == null ? new ArrayList<>() : evidence;
  }

  public String getEndPoint() {
    return endPoint;
  }

  public void setEndPoint(String endPoint) {
    this.endPoint = endPoint;
  }

  public long getWarmupMs() {
    return warmupMs;
  }

  public void setWarmupMs(long warmupMs) {
    this.warmupMs = warmupMs;
  }

  public long getPollIntervalMs() {
    return pollIntervalMs;
  }

  public void setPollIntervalMs(long pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
  }

  public long getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(long timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  public long getVerifiedAt() {
    return verifiedAt;
  }

  public void setVerifiedAt(long verifiedAt) {
    this.verifiedAt = verifiedAt;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  @Override
  public String toString() {
    return "FaultVerificationResult{faultID='" + faultID + "', nameKey='" + nameKey + "', phase='"
        + phase + "', status=" + status + ", summary='" + summary + "', evidence=" + evidence
        + ", endPoint='" + endPoint + "', error='" + error + "'}";
  }
}