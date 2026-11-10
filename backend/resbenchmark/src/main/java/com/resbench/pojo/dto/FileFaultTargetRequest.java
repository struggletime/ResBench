package com.resbench.pojo.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * 问引擎"file 类故障该用哪个文件"的请求。
 *
 * <p>字段形状与 {@link TpccWarehouseVerifyRequest} 保持一致（同一套连接信息、同一个 {@code params} 语义），
 * 这样前端两处调用可以共用同一份表单数据。
 *
 * <p>{@code params} 里：
 * <ul>
 *   <li>{@code workload}（必填）—— 用来定位 benchmark 的 DDL 资源，从而知道"哪些表算数"；</li>
 *   <li>{@code nameKey} / {@code level}（可选）—— 带上就把"这一种故障+这一档"该填的那个标成推荐。</li>
 * </ul>
 */
@Data
public class FileFaultTargetRequest {
  private String envInfo;

  private String dbType;

  private String deploymentType = "standalone";

  private StandaloneDatabase standalone = new StandaloneDatabase();

  private DistributedDatabase distributed = new DistributedDatabase();

  /** 语义化键 → 值，如 {"workload": "tpcc"} */
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
