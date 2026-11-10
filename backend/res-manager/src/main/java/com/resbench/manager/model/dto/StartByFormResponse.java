package com.resbench.manager.model.dto;

import lombok.Data;

@Data
public class StartByFormResponse {
  private boolean accepted;

  private String runId;

  private String historyName;

  private String configPath;

  private String message;
}
