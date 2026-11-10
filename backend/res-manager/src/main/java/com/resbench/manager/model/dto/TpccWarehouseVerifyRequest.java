package com.resbench.manager.model.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * TPCC 仓数检查请求（管理端镜像，透传给引擎 /web/bench/tpcc/warehouse/verify）。
 *
 * <p>params 沿用提交压测时 {@code workload.params} 的语义（键 {@code warehouses}）。
 */
@Data
public class TpccWarehouseVerifyRequest {
  private String envInfo;

  private String dbType;

  private String deploymentType = "standalone";

  private StandaloneDatabase standalone = new StandaloneDatabase();

  private DistributedDatabase distributed = new DistributedDatabase();

  private Map<String, String> params = new LinkedHashMap<>();

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