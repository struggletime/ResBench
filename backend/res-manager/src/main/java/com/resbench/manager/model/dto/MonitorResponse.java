package com.resbench.manager.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MonitorResponse {

  public long timestamp;

  public String tps;

  public double avgLatencyMs;

  public double maxLatencyMs;

  public double minLatencyMs;

  public double latencyMs95th;

  public double latencyMs5th;
}
