package com.resbench.manager.model.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * 数据库参数应用请求（管理端镜像，透传给引擎 /web/bench/db/params）。
 *
 * <p>params 用语义化键：{@code bufferPool}（缓冲池大小）、{@code maxConnections}（最大连接数上限）。
 */
@Data
public class DbParamRequest {
  private String envInfo;

  private String dbType;

  private String deploymentType = "standalone";

  private StandaloneDatabase standalone = new StandaloneDatabase();

  private Map<String, String> params = new LinkedHashMap<>();

  private Restart restart = new Restart();

  private Integer agentPort = 8000;

  @Data
  public static class StandaloneDatabase {
    private String ip;

    private String port;

    private String username;

    private String password;
  }

  @Data
  public static class Restart {
    private String command;

    private Integer waitSec = 180;
  }
}