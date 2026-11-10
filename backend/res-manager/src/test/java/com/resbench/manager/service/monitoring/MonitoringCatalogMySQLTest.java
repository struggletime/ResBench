package com.resbench.manager.service.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.resbench.manager.model.monitoring.MonitoringModels.MetricDefinition;

import org.junit.jupiter.api.Test;

/**
 * MySQL 监控指标的守卫测试。
 *
 * <p>同一个 metricId 要同时服务 openGauss（og_*）和 MySQL（mysql_*）：
 * 查询用 `or` 连接两套序列，collectorId 写两个候选采集器。这里守三件事：
 * 1) MySQL 分支的序列名与 agent 的 mysql_* 采集器真实产出一致；
 * 2) collectorId 能被正确拆成候选列表（会话建立时按 agent 能力过滤）；
 * 3) 查询仍然带 {{instance}}，不会把别的实例的序列画进本实例的图。
 */
class MonitoringCatalogMySQLTest {

  private final Map<String, MetricDefinition> byId =
      new MonitoringCatalog().all().stream()
          .collect(Collectors.toMap(MetricDefinition::metricId, definition -> definition));

  /** metricId -> MySQL 侧必须出现的序列前缀 */
  private static final Map<String, String> MYSQL_BRANCHES = Map.of(
      "db.tps.agent", "mysql_tps",
      "db.rollback.rate", "mysql_database_xact_rollback_total",
      "db.connections.usage", "mysql_connections_usage_ratio",
      "db.cache.hit", "mysql_innodb_buffer_pool_read_requests_total",
      "db.lock.wait.max", "mysql_lock_wait_seconds_max",
      "db.sessions", "mysql_sessions",
      "db.slow_sql.max", "mysql_slow_sql_max_elapsed_seconds");

  @Test
  void sharedDatabaseMetricsCarryBothProducts() {
    for (Map.Entry<String, String> entry : MYSQL_BRANCHES.entrySet()) {
      MetricDefinition definition = byId.get(entry.getKey());
      assertTrue(definition != null, "缺少数据库指标: " + entry.getKey());
      assertTrue(definition.queryTemplate().contains(entry.getValue()),
          entry.getKey() + " 没有 MySQL 分支 " + entry.getValue());
      assertTrue(definition.queryTemplate().contains(" or "),
          entry.getKey() + " 应该用 or 连接两个产品的序列");
      assertTrue(definition.collectorIds().stream().anyMatch(id -> id.startsWith("mysql_")),
          entry.getKey() + " 的 collectorIds 缺少 mysql_* 采集器: " + definition.collectorIds());
    }
  }

  @Test
  void collectorIdsAreSplitAndTrimmed() {
    MetricDefinition definition = byId.get("db.tps.agent");
    assertEquals(List.of("og_tps", "mysql_tps"), definition.collectorIds());
    // 没有采集器依赖的指标（qdisc 自建导出器）返回空列表而不是 [""]
    assertTrue(byId.get("net.qdisc.delay").collectorIds().isEmpty());
  }

  @Test
  void mysqlOnlySignalsUseMysqlCollectors() {
    for (String metricId : List.of("db.threads.running", "db.blocked.sessions", "db.replication.lag")) {
      MetricDefinition definition = byId.get(metricId);
      assertTrue(definition != null, "缺少 MySQL 专有指标: " + metricId);
      assertTrue(definition.collectorId().startsWith("mysql_"),
          metricId + " 的采集器应为 mysql_*，实际 " + definition.collectorId());
      assertTrue(definition.queryTemplate().contains("mysql_"),
          metricId + " 查询的不是 mysql_* 序列");
      assertFalse(definition.defaultSelected(), metricId + " 不应默认勾选（避免抬高归档体积）");
    }
  }

  @Test
  void databaseMetricsStayInstanceScoped() {
    for (MetricDefinition definition : byId.values()) {
      if (!definition.metricId().startsWith("db.")) {
        continue;
      }
      assertTrue(definition.queryTemplate().contains("{{instance}}"),
          definition.metricId() + " 没按实例过滤");
      assertFalse(definition.unit().isBlank(), definition.metricId() + " 缺单位");
      assertFalse(definition.description().isBlank(), definition.metricId() + " 缺说明");
    }
  }

  /**
   * 采集器候选必须是 agent 真的会上报的名字。MySQL 采集器清单与
   * monitor_module/internal/agent/agent.go 的 register 调用一一对应。
   */
  @Test
  void mysqlCollectorNamesMatchAgentTasks() {
    Set<String> agentTasks = Set.of(
        "mysql_tps", "mysql_database_stats", "mysql_connections", "mysql_sessions",
        "mysql_locks", "mysql_replication", "mysql_slow_sql", "mysql_table_stats");
    for (MetricDefinition definition : byId.values()) {
      for (String collectorId : definition.collectorIds()) {
        if (collectorId.startsWith("mysql_")) {
          assertTrue(agentTasks.contains(collectorId),
              definition.metricId() + " 引用了 agent 不存在的采集器: " + collectorId);
        }
      }
    }
  }
}
