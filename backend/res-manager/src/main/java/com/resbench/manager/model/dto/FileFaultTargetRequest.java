package com.resbench.manager.model.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * file 故障目标查询请求（管理端镜像，透传给引擎 /web/bench/faults/file-targets）。
 *
 * <p>形状与 {@link TpccWarehouseVerifyRequest} 一致 —— 同一套连接信息、同一个 params 语义，
 * 前端两处调用可以共用同一份表单数据。
 *
 * <p>{@code params} 里：{@code workload}（必填，定位 benchmark 的 DDL 才知道哪些表算数）；
 * {@code nameKey} / {@code level}（可选，用来把"这一种故障+这一档"标成推荐）。
 */
@Data
public class FileFaultTargetRequest {
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
