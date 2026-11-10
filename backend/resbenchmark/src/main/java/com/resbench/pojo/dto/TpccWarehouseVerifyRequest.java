package com.resbench.pojo.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * TPCC 仓数检查请求：连上目标库数出实际仓数，与配置仓数比对。
 *
 * <p>params 沿用提交压测时 {@code workload.params} 的语义（键 {@code warehouses}），
 * 保证"检查所依据的配置"和"真跑时用的配置"是同一个字段。
 */
@Data
public class TpccWarehouseVerifyRequest {
  private String envInfo;

  private String dbType;

  private String deploymentType = "standalone";

  private StandaloneDatabase standalone = new StandaloneDatabase();

  private DistributedDatabase distributed = new DistributedDatabase();

  /** 语义化键 → 值，如 {"warehouses": "1000"} */
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