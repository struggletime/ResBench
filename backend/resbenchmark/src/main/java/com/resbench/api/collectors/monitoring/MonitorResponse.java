package com.resbench.api.collectors.monitoring;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MonitorResponse {
  public long timestamp;

  public Double tps;

  public double avgLatencyMs;

  public double latencyMs95th;

  public double latencyMs5th;

  public double maxLatencyMs;

  public double minLatencyMs;
}
