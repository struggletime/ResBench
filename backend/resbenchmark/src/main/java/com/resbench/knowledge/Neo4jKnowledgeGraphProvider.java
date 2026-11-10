package com.resbench.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 懒加载的 {@link Neo4jKnowledgeGraph} 提供者。
 *
 * <ul>
 *   <li>{@code resbench.neo4j.uri} 为空：使用内置内存示例图谱（首次打 WARN）；</li>
 *   <li>配置了 uri：首次使用时创建 Driver 并校验连通性；失败不缓存，下次请求可重试。</li>
 * </ul>
 */
@Component
public class Neo4jKnowledgeGraphProvider {

  private static final Logger LOG = LoggerFactory.getLogger(Neo4jKnowledgeGraphProvider.class);

  private final Neo4jProperties properties;

  private volatile Neo4jKnowledgeGraph graph;

  public Neo4jKnowledgeGraphProvider(Neo4jProperties properties) {
    this.properties = properties;
  }

  /**
   * 获取当前图谱。配置了 Neo4j 但连接失败时抛 {@link IllegalStateException}，
   * 由调用方转成 {@code checked=false} 的结果。
   */
  public Neo4jKnowledgeGraph get() {
    Neo4jKnowledgeGraph current = graph;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (graph != null) {
        return graph;
      }
      if (properties.getUri() == null || properties.getUri().isBlank()) {
        LOG.warn("[OrchestrationCheck] resbench.neo4j.uri 未配置，"
            + "使用内置内存知识图谱（仅示例关系，mode=in-memory）");
        graph = Neo4jKnowledgeGraph.fakeSeed();
        return graph;
      }

      Neo4jKnowledgeGraph candidate = Neo4jKnowledgeGraph.connect(
          properties.getUri(),
          properties.getUsername(),
          properties.getPassword(),
          properties.getDatabase(),
          properties.getConnectionTimeoutMs());
      try {
        candidate.verifyConnectivity();
      } catch (RuntimeException e) {
        candidate.close();
        throw new IllegalStateException(
            "无法连接 Neo4j 知识图谱 (" + properties.getUri() + ")：" + e.getMessage(), e);
      }
      LOG.info("[OrchestrationCheck] 已连接 Neo4j 知识图谱 {} (database={})",
          properties.getUri(), properties.getDatabase());
      graph = candidate;
      return graph;
    }
  }

  @PreDestroy
  public void close() {
    Neo4jKnowledgeGraph current = graph;
    if (current != null) {
      current.close();
    }
  }
}
