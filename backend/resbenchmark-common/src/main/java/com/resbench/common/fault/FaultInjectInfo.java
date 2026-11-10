package com.resbench.common.fault;

import com.resbench.common.util.TimestampConverter;
import java.util.List;

public class FaultInjectInfo {
  private String faultID;

  private String faultName;

  private long faultDelay;

  private long faultDuration;

  private String injectionCMD;

  private List<FaultResult> faultResults;

  private String recoveryCMD;

  private List<FaultResult> recoveryResult;

  private boolean isAutoRecovery;

  private long startTime;

  private String startTimeFormat;

  private long endTime;

  private String endTimeFormat;

  private String groupKey;

  /** 注入后验证结果（设计文档 §7.2，可空，老数据兼容） */
  private FaultVerificationResult verification;

  /** 预留：恢复后验证（本期不填） */
  private FaultVerificationResult recoveryVerification;

  public FaultInjectInfo() {
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

  public long getFaultDelay() {
    return faultDelay;
  }

  public void setFaultDelay(long faultDelay) {
    this.faultDelay = faultDelay;
  }

  public long getFaultDuration() {
    return faultDuration;
  }

  public void setFaultDuration(long faultDuration) {
    this.faultDuration = faultDuration;
  }

  public String getInjectionCMD() {
    return injectionCMD;
  }

  public void setInjectionCMD(String injectionCMD) {
    this.injectionCMD = injectionCMD;
  }

  public List<FaultResult> getFaultResults() {
    return faultResults;
  }

  public void setFaultResults(List<FaultResult> faultResults) {
    this.faultResults = faultResults;
  }

  public String getRecoveryCMD() {
    return recoveryCMD;
  }

  public void setRecoveryCMD(String recoveryCMD) {
    this.recoveryCMD = recoveryCMD;
  }

  public List<FaultResult> getRecoveryResult() {
    return recoveryResult;
  }

  public void setRecoveryResult(List<FaultResult> recoveryResult) {
    this.recoveryResult = recoveryResult;
  }

  public boolean isAutoRecovery() {
    return isAutoRecovery;
  }

  public void setAutoRecovery(boolean autoRecovery) {
    isAutoRecovery = autoRecovery;
  }

  public long getStartTime() {
    return startTime;
  }

  public String getStartTimeFormat() {
    return startTimeFormat;
  }

  public void setStartTimeFormat(String startTimeFormat) {
    this.startTimeFormat = startTimeFormat;
  }

  public long getEndTime() {
    return endTime;
  }

  public String getEndTimeFormat() {
    return endTimeFormat;
  }

  public void setEndTimeFormat(String endTimeFormat) {
    this.endTimeFormat = endTimeFormat;
  }

  public void setStartTime(long startTime) {
    this.startTime = startTime;
    this.startTimeFormat = TimestampConverter.convertTimestamp(startTime);
  }

  public void setEndTime(long endTime) {
    this.endTime = endTime;
    this.endTimeFormat = TimestampConverter.convertTimestamp(endTime);
  }

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
  }

  public FaultVerificationResult getVerification() {
    return verification;
  }

  public void setVerification(FaultVerificationResult verification) {
    this.verification = verification;
  }

  public FaultVerificationResult getRecoveryVerification() {
    return recoveryVerification;
  }

  public void setRecoveryVerification(FaultVerificationResult recoveryVerification) {
    this.recoveryVerification = recoveryVerification;
  }

  @Override
  public String toString() {
    return "FaultInjectInfo{" +
            "faultID='" + faultID + '\'' +
            ", faultName='" + faultName + '\'' +
            ", faultDelay=" + faultDelay +
            ", faultDuration=" + faultDuration +
            ", injectionCMD='" + injectionCMD + '\'' +
            ", faultResults=" + faultResults +
            ", recoveryCMD='" + recoveryCMD + '\'' +
            ", recoveryResult=" + recoveryResult +
            ", isAutoRecovery=" + isAutoRecovery +
            ", startTime=" + startTime +
            ", startTimeFormat='" + startTimeFormat + '\'' +
            ", endTime=" + endTime +
            ", endTimeFormat='" + endTimeFormat + '\'' +
            ", groupKey='" + groupKey + '\'' +
            '}';
  }
}
