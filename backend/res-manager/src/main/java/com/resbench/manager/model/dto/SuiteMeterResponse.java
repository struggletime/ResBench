package com.resbench.manager.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SuiteMeterResponse {
  private String test_suite;

  private double data_loss;

  private double outage_secs;

  private double recovery_time_factor;

  private double throughput_rate;

  private double latency_rate;

  private double desired_throughput;

  private double fault_throughput;

  private double desired_latency;

  private double fault_latency;

  private double rpo;

  private double rto;

  private double throughput;

  private double latency;

  private double stability;

  private double defence;

  private double recovery;

  private double adaptability;

  private double volatility;

  private String faultTriangles;

  private String faultScores;
}
