package com.resbench.manager.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public class FaultEventInfoResponse {

  @JsonProperty("Fault Name")
  private String faultName;

  @JsonProperty("data_loss")
  private int dataLoss;

  @JsonProperty("duration_milliseconds")
  private long durationMilliseconds;

  @JsonProperty("fault_end_ts_milliseconds")
  private long faultEndTsMilliseconds;

  @JsonProperty("latency_rate")
  private double latencyRate;

  @JsonProperty("outage_secs")
  @JsonAlias("outage_seconds")
  private int outageSeconds;

  @JsonProperty("recovery_time_factor")
  private int recoveryTimeFactor;

  @JsonProperty("stage_end_ts_milliseconds")
  private long stageEndTsMilliseconds;

  @JsonProperty("start_ts_milliseconds")
  private long startTsMilliseconds;

  @JsonProperty("throughput_rate")
  private double throughputRate;

  @JsonProperty("desired_throughput")
  private double desiredThroughput;

  @JsonProperty("desired_latency")
  private double desiredLatency;

  @JsonProperty("fault_throughput")
  @JsonAlias("throughput_after_recover")
  private double factThroughput;

  @JsonProperty("fault_latency")
  @JsonAlias("latency_after_recover")
  private double factLatency;

  @JsonProperty("rpo")
  private double rpo;

  @JsonProperty("rto")
  private double rto;

  @JsonProperty("throughput")
  private double throughput;

  @JsonProperty("latency")
  private double latency;

  @JsonProperty("stability")
  private double stability;

  @JsonProperty("defence")
  private double defence;

  @JsonProperty("recovery")
  private double recovery;

  @JsonProperty("adaptability")
  private double adaptability;

  @JsonProperty("volatility")
  private double volatility;

  @Override
  public String toString() {
    return "FaultEventInfoResponse{" +
      "faultName='" + faultName + '\'' +
      ", dataLoss=" + dataLoss +
      ", durationMilliseconds=" + durationMilliseconds +
      ", faultEndTsMilliseconds=" + faultEndTsMilliseconds +
      ", latencyRate=" + latencyRate +
      ", outageSeconds=" + outageSeconds +
      ", recoveryTimeFactor=" + recoveryTimeFactor +
      ", stageEndTsMilliseconds=" + stageEndTsMilliseconds +
      ", startTsMilliseconds=" + startTsMilliseconds +
      ", throughputRate=" + throughputRate +
      ", desiredThroughput=" + desiredThroughput +
      ", desiredLatency=" + desiredLatency +
      ", factThroughput=" + factThroughput +
      ", factLatency=" + factLatency +
      ", rpo=" + rpo +
      ", rto=" + rto +
      ", throughput=" + throughput +
      ", latency=" + latency +
      ", stability=" + stability +
      ", defence=" + defence +
      ", recovery=" + recovery +
      ", adaptability=" + adaptability +
      ", volatility=" + volatility +
      '}';
  }
}
