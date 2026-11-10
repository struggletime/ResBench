package com.resbench.manager.model.external.benchmark;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BenchMarkStatusResponse {
  private String startTimestamp;

  private String endTimestamp;

  private boolean end = true;

  private boolean success = false;

  private String message;

  private String dbType;

  private String workloadType;

  private String workloadConfig;

  private List<String> faults;
}
