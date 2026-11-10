package com.resbench.manager.model.dto;

import lombok.Data;

@Data
public class TestConnectionResponse {
  private boolean success;

  private String message;
}
