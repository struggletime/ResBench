package com.resbench.pojo.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * 数据库参数应用请求（只做单机 openGauss / PostgreSQL / MySQL）。
 *
 * <p>params 用<b>语义化键</b>，不让调用方写 GUC 名 —— 参数名由服务端按 dbType 映射，避免写错名字被静默忽略：
 * <ul>
 *   <li>{@code bufferPool}：缓冲池大小（og/pg → shared_buffers，mysql → innodb_buffer_pool_size）</li>
 *   <li>{@code maxConnections}：最大连接数上限（都是 max_connections）</li>
 * </ul>
 * 值为空字符串/空白的键表示"这个参数不动"。
 */
@Data
public class DbParamRequest {
  private String envInfo;

  private String dbType;

  private String deploymentType = "standalone";

  private StandaloneDatabase standalone = new StandaloneDatabase();

  /** 语义化键 → 目标值，如 {"bufferPool": "2GB", "maxConnections": "500"} */
  private Map<String, String> params = new LinkedHashMap<>();

  private Restart restart = new Restart();

  /** agent 与数据库同机（仅重启时用到），默认 8000 */
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
    /**
     * 重启命令，<b>必须显式给出</b>（不同部署方式差别太大，服务端不猜）。
     *
     * <p>必须写成 detach（如 {@code nohup systemctl restart postgresql >/tmp/resbench_restart.log 2>&1 &}），
     * 否则 agent 侧 30 秒的等待上限会把命令提前 kill。
     */
    private String command;

    /** 重启后等待数据库恢复连接的上限（秒） */
    private Integer waitSec = 180;
  }
}