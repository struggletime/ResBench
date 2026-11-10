package com.resbench.pojo.dto;

import lombok.Data;

@Data
public class TestConnectionRequest {
  private String envInfo;

  private String dbType;

  private String deploymentType;

  private StandaloneDatabase standalone = new StandaloneDatabase();

  private DistributedDatabase distributed = new DistributedDatabase();

  @Data
  public static class StandaloneDatabase {
    private String ip;

    private String port;

    private String username;

    private String password;
  }

  @Data
  public static class DistributedDatabase {
    private String ip;

    private String port;

    private String username;

    private String password;
  }
}
