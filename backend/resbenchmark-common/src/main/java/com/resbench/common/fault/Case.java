package com.resbench.common.fault;

import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;

// 不适用@Date 会导致重新hash 导致无法找到对象

public class Case {
  private Fault fault;
  private long delay;
  private long duration;
  private long startTime;
  private String groupKey;
  private boolean autoTimingEnabled;

  public Case() {}

  public Case(HierarchicalConfiguration<ImmutableNode> configuration, Fault fault, boolean autoTimingEnabled) {
    this.fault = fault;
    this.autoTimingEnabled = autoTimingEnabled;
    if (autoTimingEnabled) {
      // 自动时序模式下，不需要 planning，delay 设为 0
      this.delay = 0;
    } else {
      // 固定时间模式下，读取 planning
      int planning = configuration.getInt("planning");
      this.delay = planning;
    }
    int during = configuration.getInt("during");
    this.duration = during;
    this.groupKey = configuration.getString("groupKey", "");
  }

  /**
   * 兼容旧版构造方法
   */
  public Case(HierarchicalConfiguration<ImmutableNode> configuration, Fault fault) {
    this(configuration, fault, false);
  }

  @Override
  public boolean equals(Object object) {
    if (object == null || getClass() != object.getClass()) {
      return false;
    }

    Case aCase = (Case) object;
    return fault.equals(aCase.fault);
  }

  @Override
  public int hashCode() {
    return fault.hashCode();
  }

  public Fault getFault() {
    return fault;
  }

  public void setFault(Fault fault) {
    this.fault = fault;
  }

  public long getDelay() {
    return delay;
  }

  public void setDelay(long delay) {
    this.delay = delay;
  }

  public long getDuration() {
    return duration;
  }

  public void setDuration(long duration) {
    this.duration = duration;
  }

  public long getStartTime() {
    return startTime;
  }

  public void setStartTime(long startTime) {
    this.startTime = startTime;
  }

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
  }

  public boolean isAutoTimingEnabled() {
    return autoTimingEnabled;
  }

  public void setAutoTimingEnabled(boolean autoTimingEnabled) {
    this.autoTimingEnabled = autoTimingEnabled;
  }

  @Override
  public String toString() {
    return "Case{" +
            "fault=" + fault +
            ", delay=" + delay +
            ", duration=" + duration +
            ", startTime=" + startTime +
            ", groupKey='" + groupKey + '\'' +
            ", autoTimingEnabled=" + autoTimingEnabled +
            '}';
  }
}
