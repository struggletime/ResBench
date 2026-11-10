package com.resbench.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Neo4j 知识图谱连接配置。
 *
 * <p>{@code resbench.neo4j.uri} 为空时使用内置内存示例图谱，不会尝试连接任何服务器。
 */
@Data
@Component
@ConfigurationProperties(prefix = "resbench.neo4j")
public class Neo4jProperties {

  /** Bolt 地址，例如 bolt://127.0.0.1:7687；为空则使用内存假图谱。 */
  private String uri = "";

  private String username = "neo4j";

  private String password = "";

  private String database = "neo4j";

  /** 连接超时（毫秒）。图谱不可用时接口最多等待这么久。 */
  private long connectionTimeoutMs = 3000;
}
