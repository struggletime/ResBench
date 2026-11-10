package com.resbench.common.fault;

public class AutoTimingConfig {
  public static final long DEFAULT_CHECK_INTERVAL_MS = 5000L;
  public static final long DEFAULT_MAX_WAIT_MS = 300000L;
  public static final int DEFAULT_WINDOW_SIZE = 30;
  public static final double DEFAULT_CV_THRESHOLD = 0.05D;
  /**
   * 注入/恢复的默认往返限额，与 {@link FaultInjector#DEFAULT_ROUND_TRIP_TIMEOUT_MS} 是同一个数 ——
   * 单一真源，免得自动时序模式和固定时序模式给出两个不同的「合理时间」。
   */
  public static final int DEFAULT_INJECT_TIMEOUT_MS = FaultInjector.DEFAULT_ROUND_TRIP_TIMEOUT_MS;
  public static final int DEFAULT_RECOVER_TIMEOUT_MS = FaultInjector.DEFAULT_ROUND_TRIP_TIMEOUT_MS;
  public static final boolean DEFAULT_DYNAMIC_THRESHOLD_ENABLED = false;
  public static final int DEFAULT_DYNAMIC_BASELINE_CHECKS = 30;
  public static final double DEFAULT_DYNAMIC_THRESHOLD_SCALE_K = 1.0D;
  public static final double DEFAULT_DYNAMIC_THRESHOLD_LOWER_BOUND = 0.08D;
  public static final double DEFAULT_DYNAMIC_THRESHOLD_UPPER_BOUND = 0.35D;

  private long checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
  private long maxWaitMs = DEFAULT_MAX_WAIT_MS;
  private int windowSize = DEFAULT_WINDOW_SIZE;
  private double cvThreshold = DEFAULT_CV_THRESHOLD;
  private int injectTimeoutMs = DEFAULT_INJECT_TIMEOUT_MS;
  private int recoverTimeoutMs = DEFAULT_RECOVER_TIMEOUT_MS;
  private boolean dynamicThresholdEnabled = DEFAULT_DYNAMIC_THRESHOLD_ENABLED;
  private int dynamicBaselineChecks = DEFAULT_DYNAMIC_BASELINE_CHECKS;
  private double dynamicThresholdScaleK = DEFAULT_DYNAMIC_THRESHOLD_SCALE_K;
  private double dynamicThresholdLowerBound = DEFAULT_DYNAMIC_THRESHOLD_LOWER_BOUND;
  private double dynamicThresholdUpperBound = DEFAULT_DYNAMIC_THRESHOLD_UPPER_BOUND;

  public static AutoTimingConfig createDefault() {
    return new AutoTimingConfig();
  }

  public long getCheckIntervalMs() {
    return checkIntervalMs;
  }

  public void setCheckIntervalMs(long checkIntervalMs) {
    this.checkIntervalMs = checkIntervalMs;
  }

  public long getMaxWaitMs() {
    return maxWaitMs;
  }

  public void setMaxWaitMs(long maxWaitMs) {
    this.maxWaitMs = maxWaitMs;
  }

  public int getWindowSize() {
    return windowSize;
  }

  public void setWindowSize(int windowSize) {
    this.windowSize = windowSize;
  }

  public double getCvThreshold() {
    return cvThreshold;
  }

  public void setCvThreshold(double cvThreshold) {
    this.cvThreshold = cvThreshold;
  }

  public int getInjectTimeoutMs() {
    return injectTimeoutMs;
  }

  public void setInjectTimeoutMs(int injectTimeoutMs) {
    this.injectTimeoutMs = injectTimeoutMs;
  }

  public int getRecoverTimeoutMs() {
    return recoverTimeoutMs;
  }

  public void setRecoverTimeoutMs(int recoverTimeoutMs) {
    this.recoverTimeoutMs = recoverTimeoutMs;
  }

  public boolean isDynamicThresholdEnabled() {
    return dynamicThresholdEnabled;
  }

  public void setDynamicThresholdEnabled(boolean dynamicThresholdEnabled) {
    this.dynamicThresholdEnabled = dynamicThresholdEnabled;
  }

  public int getDynamicBaselineChecks() {
    return dynamicBaselineChecks;
  }

  public void setDynamicBaselineChecks(int dynamicBaselineChecks) {
    this.dynamicBaselineChecks = dynamicBaselineChecks;
  }

  public double getDynamicThresholdScaleK() {
    return dynamicThresholdScaleK;
  }

  public void setDynamicThresholdScaleK(double dynamicThresholdScaleK) {
    this.dynamicThresholdScaleK = dynamicThresholdScaleK;
  }

  public double getDynamicThresholdLowerBound() {
    return dynamicThresholdLowerBound;
  }

  public void setDynamicThresholdLowerBound(double dynamicThresholdLowerBound) {
    this.dynamicThresholdLowerBound = dynamicThresholdLowerBound;
  }

  public double getDynamicThresholdUpperBound() {
    return dynamicThresholdUpperBound;
  }

  public void setDynamicThresholdUpperBound(double dynamicThresholdUpperBound) {
    this.dynamicThresholdUpperBound = dynamicThresholdUpperBound;
  }

  public void sanitize() {
    if (checkIntervalMs <= 0) {
      checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
    }
    if (maxWaitMs <= 0) {
      maxWaitMs = DEFAULT_MAX_WAIT_MS;
    }
    if (windowSize <= 0) {
      windowSize = DEFAULT_WINDOW_SIZE;
    }
    if (cvThreshold <= 0 || cvThreshold >= 1) {
      cvThreshold = DEFAULT_CV_THRESHOLD;
    }
    // <= 0 都归到默认值：0 曾经表示「无限等待」，那正是注入命令能晚到 68 秒
    // 而没有任何记录可查的原因，这个语义必须堵死。
    if (injectTimeoutMs <= 0) {
      injectTimeoutMs = DEFAULT_INJECT_TIMEOUT_MS;
    }
    if (recoverTimeoutMs <= 0) {
      recoverTimeoutMs = DEFAULT_RECOVER_TIMEOUT_MS;
    }
    if (dynamicBaselineChecks <= 0) {
      dynamicBaselineChecks = DEFAULT_DYNAMIC_BASELINE_CHECKS;
    }
    if (dynamicThresholdScaleK <= 0) {
      dynamicThresholdScaleK = DEFAULT_DYNAMIC_THRESHOLD_SCALE_K;
    }
    if (dynamicThresholdLowerBound <= 0 || dynamicThresholdLowerBound >= 1) {
      dynamicThresholdLowerBound = DEFAULT_DYNAMIC_THRESHOLD_LOWER_BOUND;
    }
    if (dynamicThresholdUpperBound <= 0 || dynamicThresholdUpperBound >= 1) {
      dynamicThresholdUpperBound = DEFAULT_DYNAMIC_THRESHOLD_UPPER_BOUND;
    }
    if (dynamicThresholdLowerBound > dynamicThresholdUpperBound) {
      dynamicThresholdLowerBound = DEFAULT_DYNAMIC_THRESHOLD_LOWER_BOUND;
      dynamicThresholdUpperBound = DEFAULT_DYNAMIC_THRESHOLD_UPPER_BOUND;
    }
  }

  @Override
  public String toString() {
    return "AutoTimingConfig{"
        + "checkIntervalMs="
        + checkIntervalMs
        + ", maxWaitMs="
        + maxWaitMs
        + ", windowSize="
        + windowSize
        + ", cvThreshold="
        + cvThreshold
        + ", injectTimeoutMs="
        + injectTimeoutMs
        + ", recoverTimeoutMs="
        + recoverTimeoutMs
        + ", dynamicThresholdEnabled="
        + dynamicThresholdEnabled
        + ", dynamicBaselineChecks="
        + dynamicBaselineChecks
        + ", dynamicThresholdScaleK="
        + dynamicThresholdScaleK
        + ", dynamicThresholdLowerBound="
        + dynamicThresholdLowerBound
        + ", dynamicThresholdUpperBound="
        + dynamicThresholdUpperBound
        + '}';
  }
}
