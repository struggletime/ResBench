package com.resbench.manager.model.monitoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

public final class MonitoringModels {
  private MonitoringModels() {
  }

  public record MetricDefinition(
      String metricId,
      String name,
      String category,
      String categoryName,
      String subcategory,
      String subcategoryName,
      String unit,
      String collectorId,
      String nativeProbe,
      String queryTemplate,
      String costLevel,
      boolean defaultSelected,
      String description) {

    /**
     * 一个指标可能同时由不同数据库产品的采集器提供（openGauss 的 og_* 与 MySQL 的
     * mysql_*）。collectorId 用逗号分隔候选采集器，会话建立时只把 agent 实际声明
     * 支持的采集器下发过去——agent 对未知采集器是直接拒绝整个会话的。
     */
    public List<String> collectorIds() {
      if (collectorId == null || collectorId.isBlank()) {
        return List.of();
      }
      return java.util.Arrays.stream(collectorId.split(","))
          .map(String::trim)
          .filter(value -> !value.isEmpty())
          .toList();
    }
  }

  public record CatalogMetric(
      String metricId,
      String name,
      String category,
      String categoryName,
      String subcategory,
      String subcategoryName,
      String unit,
      String costLevel,
      boolean defaultSelected,
      boolean available,
      String unavailableReason,
      String description) {
  }

  public record CatalogSubcategory(String id, String name, List<CatalogMetric> metrics) {
  }

  public record CatalogCategory(String id, String name, List<CatalogSubcategory> subcategories) {
  }

  public record CatalogResponse(List<CatalogCategory> categories) {
  }

  public record CreateSessionRequest(
      String runId,
      List<String> targets,
      List<String> metricIds,
      Integer uiRefreshSec,
      Integer queryStepSec,
      Boolean archiveEnabled,
      String containerName,
      // 被测库类型（mysql / postgresql / opengauss / gaussdb / oceanbase）。
      // 数据库类指标同时有 og_* 与 mysql_* 两套序列，必须靠它选数据源；不传则按旧行为。
      String dbType) {
  }

  @Data
  public static class Session {
    private String runId;
    private boolean enabled;
    private String status;
    private List<String> targetIds = new ArrayList<>();
    private List<String> metricIds = new ArrayList<>();
    /** 非空表示这次测试的数据库在 Docker 容器里，监控展示容器资源而不是宿主机资源。 */
    private String containerName;
    /** 被测库类型；db.* 指标用它决定查 og_* 还是 mysql_* 序列。null = 旧会话，沿用原查询。 */
    private String dbType;
    private long testStartAt;
    private long testEndAt;
    private long createdAt;
    private long updatedAt;
    private long expiresAt;
    private int queryStepSec;
    private boolean archiveEnabled;
    private String archiveStatus;
    private long deletedAt;
    private double dataCompleteness;
    private List<String> warnings = new ArrayList<>();
  }

  public record Point(long timestamp, Double value) {
  }

  public record MetricSeries(
      String metricId,
      String targetId,
      Map<String, String> labels,
      String unit,
      List<List<Object>> points,
      String dataStatus) {
  }

  public record SeriesResponse(
      String runId,
      long start,
      long end,
      List<MetricSeries> series,
      List<String> warnings) {
  }

  public record Target(
      String targetId,
      String displayName,
      String role,
      String agentStatus,
      String scrapeStatus,
      long lastScrapeAt,
      List<String> supportedCategories) {
  }

  public record CapabilityResponse(
      boolean monitoringEnabled,
      boolean agentAvailable,
      boolean prometheusAvailable,
      int maxMetrics,
      int maxPoints,
      int archiveRetentionDays,
      Object agentCapabilities,
      List<Target> targets) {
  }

  public static Map<String, Object> agentSessionRequest(
      String runId, int ttlSec, List<String> collectors, List<String> probes) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sessionId", runId);
    body.put("ttlSec", ttlSec);
    body.put("collectors", collectors);
    body.put("nativeProbes", probes);
    // bucket3 是最细的聚合档：前端按 3 秒打点，agent 只有在会话里被要求维护 3 秒桶时
    // 才会按 3 秒刷新 og_* 指标（2026-10-25 在 209 上实测会话接受该档位，og_tps 3 秒内即变化）。
    body.put("intervals", Map.of(
        "bucket3", 3,
        "bucket10", 10,
        "bucket15", 15,
        "bucket30", 30,
        "bucket60", 60));
    return body;
  }
}
