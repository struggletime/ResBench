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
 * 网络故障信号指标的守卫测试。
 *
 * <p>这些 PromQL 里的指标名都已在本环境 Prometheus（instance=127.0.0.1:19091）
 * 实测返回 success 且有序列（2026-10-24）。这里只守「定义没被写坏」：
 * 采集器必须是 agent 实际会上报的那两个，查询必须带实例模板。
 */
class MonitoringCatalogTest {

  /** 监控 agent 的 /api/v1/capabilities 实际上报的相关采集器 */
  private static final Set<String> NETWORK_COLLECTORS =
      Set.of("node_network_stack", "node_network", "node_ping");

  /** 不依赖 agent 采集器的序列前缀（自建导出器 netem_*，以及 agent 的 ICMP 探针） */
  private static final Set<String> SELF_EXPORTED_PREFIXES = Set.of("netem_", "net_ping_");

  private final Map<String, MetricDefinition> byId =
      new MonitoringCatalog().all().stream()
          .collect(Collectors.toMap(MetricDefinition::metricId, definition -> definition));

  @Test
  void networkSignalMetricsAreAllPresent() {
    for (String metricId : List.of(
        "net.tcp.retrans.ratio", "net.tcp.rto.rate", "net.tcp.lost_retrans.rate",
        "net.tcp.synretrans.rate", "net.tcp.oo.rate", "net.tcp.reorder.rate",
        "net.tcp.dsack.rate", "net.tcp.conn.fail.rate", "net.packet.rx", "net.packet.tx",
        "net.qdisc.reorder.rate", "net.qdisc.delay", "net.qdisc.reorder.percent",
        "net.qdisc.drop.rate", "net.qdisc.drop.percent", "net.qdisc.present",
        "net.qdisc.loss.percent", "net.qdisc.duplicate.percent",
        "net.ping.loss", "net.ping.rtt", "net.ping.up")) {
      assertTrue(byId.containsKey(metricId), "缺少网络信号指标: " + metricId);
    }
  }

  @Test
  void networkSignalMetricsUseCollectorsTheAgentReports() {
    for (MetricDefinition definition : byId.values()) {
      if (!definition.metricId().startsWith("net.")) {
        continue;
      }
      String collectorId = definition.collectorId();
      boolean selfExported = collectorId == null || collectorId.isBlank();
      assertTrue(
          selfExported || NETWORK_COLLECTORS.contains(collectorId),
          definition.metricId() + " 的采集器不在 agent 上报列表里: " + collectorId);
      // 没有采集器依赖就必须指向自建导出器的序列，否则这条指标永远是空曲线
      if (selfExported) {
        assertTrue(
            SELF_EXPORTED_PREFIXES.stream().anyMatch(definition.queryTemplate()::contains),
            definition.metricId() + " 不依赖 agent 采集器，查询就必须引用自建序列: " + SELF_EXPORTED_PREFIXES);
      }
    }
  }

  @Test
  void networkSignalMetricsQueryTheHostInstance() {
    for (MetricDefinition definition : byId.values()) {
      if (!definition.metricId().startsWith("net.")) {
        continue;
      }
      String query = definition.queryTemplate();
      assertTrue(query.contains("{{instance}}"), definition.metricId() + " 没按实例过滤");
      assertTrue(
          query.contains("node_netstat_") || query.contains("node_network_")
              || SELF_EXPORTED_PREFIXES.stream().anyMatch(query::contains),
          definition.metricId() + " 不是 node_exporter / 自建导出器口径");
      assertFalse(definition.description().isBlank(), definition.metricId() + " 缺说明");
      assertFalse(definition.unit().isBlank(), definition.metricId() + " 缺单位");
    }
  }

  /** 默认勾选决定每个 run 会归档哪些序列 —— 网络三件套必须默认开启，否则又只能看业务影响 */
  @Test
  void coreNetworkSignalsAreSelectedByDefault() {
    for (String metricId : List.of(
        "net.tcp.retrans.ratio", "net.tcp.oo.rate", "net.tcp.dsack.rate",
        "net.qdisc.drop.percent", "net.ping.loss", "net.ping.rtt")) {
      assertTrue(byId.get(metricId).defaultSelected(), metricId + " 应默认勾选");
    }
    // 其余保持可选：归档体积不应该被一次改动整体抬高
    for (String metricId : List.of(
        "net.tcp.rto.rate", "net.tcp.reorder.rate", "net.packet.rx", "net.packet.tx")) {
      assertFalse(byId.get(metricId).defaultSelected(), metricId + " 不应默认勾选");
    }
  }

  @Test
  void retransRatioIsNormalisedByTraffic() {
    // 重传率必须除以发送段数：绝对重传计数会随负载漂移，正是 §5.1 那个坑
    String query = byId.get("net.tcp.retrans.ratio").queryTemplate();
    assertTrue(query.contains("node_netstat_tcp_outsegs"), "重传率没有按 outsegs 归一化");
    assertEquals("%", byId.get("net.tcp.retrans.ratio").unit());
  }
}
