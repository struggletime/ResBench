package com.resbench.manager.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class TestSuitRequest {
  @NotBlank(message = "envInfo不能为空")
  public String envInfo;

  public List<String> args;

  public TestSuitRequest(String dbType, List<String> args) {
    this.envInfo = dbType;
    this.args = args;
  }

  public String getEnvInfo() {
    return envInfo;
  }

  public void setEnvInfo(String envInfo) {
    this.envInfo = envInfo;
  }

  public List<String> getArgs() {
    return args;
  }

  public void setArgs(List<String> args) {
    this.args = args;
  }
}
