package com.resbench.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StartByFormResponse {
  private boolean accepted;

  private String runId;

  private String historyName;

  private String configPath;

  private String message;
}
