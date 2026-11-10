package com.resbench.common.fault;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class TestSuite {
  private String arrival = "REGULAR";

  private String name;

  private String description;

  private Integer time;

  private String rate;

  private List<Integer> weights;

  private Integer terminal;

  private Queue<Case> works = new LinkedList<>();

  private String mode = "fixed-time"; // 默认固定时间模式

  private AutoTimingConfig autoTimingConfig = AutoTimingConfig.createDefault();

  public static final String MODE_FIXED_TIME = "fixed-time";
  public static final String MODE_AUTO_TIMING = "auto-timing";

  public void addWork(Case work) {
    works.add(work);
  }

  public String getArrival() {
    return arrival;
  }

  public void setArrival(String arrival) {
    this.arrival = arrival;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Integer getTime() {
    return time;
  }

  public void setTime(Integer time) {
    this.time = time;
  }

  public String getRate() {
    return rate;
  }

  public void setRate(String rate) {
    this.rate = rate;
  }

  public List<Integer> getWeights() {
    return weights;
  }

  public void setWeights(List<Integer> weights) {
    this.weights = weights;
  }

  public Integer getTerminal() {
    return terminal;
  }

  public void setTerminal(Integer terminal) {
    this.terminal = terminal;
  }

  public Queue<Case> getWorks() {
    return works;
  }

  public void setWorks(Queue<Case> works) {
    this.works = works;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public boolean isAutoTimingMode() {
    return MODE_AUTO_TIMING.equals(this.mode);
  }

  public AutoTimingConfig getAutoTimingConfig() {
    return autoTimingConfig;
  }

  public void setAutoTimingConfig(AutoTimingConfig autoTimingConfig) {
    this.autoTimingConfig = autoTimingConfig;
  }

  @Override
  public String toString() {
    return "TestSuite{" +
            "arrival='" + arrival + '\'' +
            ", name='" + name + '\'' +
            ", description='" + description + '\'' +
            ", time=" + time +
            ", rate='" + rate + '\'' +
            ", weights=" + weights +
             ", terminal=" + terminal +
             ", works=" + works +
             ", mode='" + mode + '\'' +
             ", autoTimingConfig=" + autoTimingConfig +
             '}';
  }
}
