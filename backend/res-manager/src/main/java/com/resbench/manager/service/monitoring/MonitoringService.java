package com.resbench.manager.service.monitoring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.resbench.manager.config.properties.MonitoringProperties;
import com.resbench.manager.model.monitoring.MonitoringModels.CapabilityResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.CatalogCategory;
import com.resbench.manager.model.monitoring.MonitoringModels.CatalogMetric;
import com.resbench.manager.model.monitoring.MonitoringModels.CatalogResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.CatalogSubcategory;
import com.resbench.manager.model.monitoring.MonitoringModels.CreateSessionRequest;
import com.resbench.manager.model.monitoring.MonitoringModels.MetricDefinition;
import com.resbench.manager.model.monitoring.MonitoringModels.MetricSeries;
import com.resbench.manager.model.monitoring.MonitoringModels.SeriesResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.Session;
import com.resbench.manager.model.monitoring.MonitoringModels.Target;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class MonitoringService {
  private static final String DEFAULT_TARGET_ID = "db-primary-01";
  private static final Set<String> NON_DELETABLE_STATES = Set.of("PENDING", "ARMING", "ACTIVE", "FINALIZING");
  /** 只允许普通容器名/短 ID，避免把任意内容拼进 PromQL。 */
  private static final Pattern CONTAINER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

  private final MonitoringProperties properties;
  private final MonitoringCatalog catalog;
  private final MonitoringRepository repository;
  private final AgentMonitoringClient agentClient;
  private final PrometheusMonitoringClient prometheusClient;

  public MonitoringService(
      MonitoringProperties properties,
      MonitoringCatalog catalog,
      MonitoringRepository repository,
      AgentMonitoringClient agentClient,
      PrometheusMonitoringClient prometheusClient) {
    this.properties = properties;
    this.catalog = catalog;
    this.repository = repository;
    this.agentClient = agentClient;
    this.prometheusClient = prometheusClient;
  }

  public CapabilityResponse capabilities() {
    boolean agentAvailable = properties.isEnabled() && agentClient.isHealthy();
    boolean prometheusAvailable = properties.isEnabled() && prometheusClient.isHealthy();
    JsonNode agentCapabilities = null;
    if (agentAvailable) {
      try {
        agentCapabilities = agentClient.capabilities();
      } catch (RuntimeException ex) {
        agentAvailable = false;
      }
    }
    boolean cadvisorAvailable = prometheusAvailable && prometheusClient.isCadvisorAvailable();
    List<String> supportedCategories = new ArrayList<>(List.of("host", "database", "ebpf"));
    if (cadvisorAvailable) {
      supportedCategories.add("container");
    }
    Target target = new Target(
        DEFAULT_TARGET_ID,
        properties.getPrometheusInstance(),
        "primary",
        agentAvailable ? "UP" : "DOWN",
        prometheusAvailable ? "UP" : "DOWN",
        System.currentTimeMillis(),
        List.copyOf(supportedCategories));
    return new CapabilityResponse(
        properties.isEnabled(), agentAvailable, prometheusAvailable,
        properties.getMaxMetrics(), properties.getMaxPoints(), properties.getArchiveRetentionDays(),
        agentCapabilities, List.of(target));
  }

  public CatalogResponse catalog() {
    boolean cadvisorAvailable = properties.isEnabled() && prometheusClient.isCadvisorAvailable();
    AgentCapability capability = readAgentCapability(cadvisorAvailable);
    Map<String, Map<String, List<CatalogMetric>>> grouped = new LinkedHashMap<>();
    for (MetricDefinition definition : catalog.all()) {
      boolean available = capability.supports(definition);
      String reason = available ? "" : capability.unavailableReason(definition);
      CatalogMetric metric = new CatalogMetric(
          definition.metricId(), definition.name(), definition.category(), definition.categoryName(),
          definition.subcategory(), definition.subcategoryName(), definition.unit(), definition.costLevel(),
          definition.defaultSelected(), available, reason, definition.description());
      grouped
          .computeIfAbsent(definition.category(), ignored -> new LinkedHashMap<>())
          .computeIfAbsent(definition.subcategory(), ignored -> new ArrayList<>())
          .add(metric);
    }
    List<CatalogCategory> categories = new ArrayList<>();
    for (String categoryId : List.of("host", "database", "ebpf", "container")) {
      Map<String, List<CatalogMetric>> subcategories = grouped.getOrDefault(categoryId, Map.of());
      List<CatalogSubcategory> children = new ArrayList<>();
      for (Map.Entry<String, List<CatalogMetric>> entry : subcategories.entrySet()) {
        String name = entry.getValue().isEmpty() ? entry.getKey() : entry.getValue().get(0).subcategoryName();
        children.add(new CatalogSubcategory(entry.getKey(), name, entry.getValue()));
      }
      String categoryName = switch (categoryId) {
        case "host" -> "主机";
        case "database" -> "数据库";
        case "container" -> "容器";
        default -> "eBPF";
      };
      categories.add(new CatalogCategory(categoryId, categoryName, children));
    }
    return new CatalogResponse(categories);
  }

  public List<Target> discoverTargets() {
    CapabilityResponse response = capabilities();
    return response.targets();
  }

  public synchronized Session createSession(CreateSessionRequest request) {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "monitoring is disabled");
    }
    String runId = requireRunId(request.runId());
    Optional<Session> existing = repository.find(runId);
    if (existing.isPresent()) {
      return existing.get();
    }
    List<String> metricIds = normalizedMetricIds(request.metricIds());
    if (metricIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one metricId is required");
    }
    if (metricIds.size() > properties.getMaxMetrics()) {
      // 这个拒绝发生 repository.save() 之前：没有会话目录、没有曲线、没有归档，
      // 事后完全查不到痕迹。前端又只显示「部分缺失」，所以必须在这里留日志。
      log.warn("Rejected monitoring session for {}: {} metricIds exceed res.monitoring.max-metrics={}",
          runId, metricIds.size(), properties.getMaxMetrics());
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "too many metrics selected: " + metricIds.size() + " > " + properties.getMaxMetrics());
    }
    List<MetricDefinition> definitions = metricIds.stream().map(this::requireDefinition).toList();
    // 同一个指标可能有两种产品采集器（og_tps / mysql_tps）。agent 收到未知采集器会
    // 拒绝整个会话，所以只下发它 /api/v1/capabilities 里声明过的那些。
    Set<String> agentCollectors = advertisedCollectors();
    List<String> collectors = definitions.stream()
        .flatMap(definition -> definition.collectorIds().stream())
        .distinct()
        .filter(id -> agentCollectors == null || agentCollectors.contains(id))
        .sorted()
        .toList();
    List<String> probes = definitions.stream()
        .map(MetricDefinition::nativeProbe)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .sorted()
        .toList();

    long now = System.currentTimeMillis();
    Session session = new Session();
    session.setRunId(runId);
    session.setEnabled(true);
    session.setStatus("PENDING");
    session.setTargetIds(request.targets() == null || request.targets().isEmpty()
        ? List.of(DEFAULT_TARGET_ID)
        : List.copyOf(request.targets()));
    session.setMetricIds(metricIds);
    session.setContainerName(normalizeContainerName(request.containerName()));
    session.setDbType(normalizeDbType(request.dbType()));
    session.setTestStartAt(now);
    session.setCreatedAt(now);
    session.setUpdatedAt(now);
    session.setExpiresAt(now + properties.getSessionTtlSec() * 1000L);
    // 默认 3 秒一个点，与前端 ObservabilityPoller 的打点步长保持一致；不传时按 3 秒建会话。
    session.setQueryStepSec(clampStep(request.queryStepSec() == null ? 3 : request.queryStepSec()));
    session.setArchiveEnabled(!Boolean.FALSE.equals(request.archiveEnabled()));
    session.setArchiveStatus("NONE");
    session.setStatus("ARMING");
    repository.save(session);

    try {
      agentClient.createSession(com.resbench.manager.model.monitoring.MonitoringModels.agentSessionRequest(
          runId, properties.getSessionTtlSec(), collectors, probes));
      session.setStatus("ACTIVE");
    } catch (RuntimeException ex) {
      session.setStatus("DEGRADED");
      session.getWarnings().add("Agent 会话创建失败，压测不受影响");
      log.warn("Monitoring Agent session creation failed for {}: {}", runId, ex.getClass().getSimpleName());
    }
    session.setUpdatedAt(System.currentTimeMillis());
    repository.save(session);
    return session;
  }

  public Session getSession(String runId) {
    Session session = requireSession(runId);
    if ("ACTIVE".equals(session.getStatus()) || "DEGRADED".equals(session.getStatus())) {
      heartbeat(session);
    }
    return session;
  }

  public SeriesResponse series(
      String runId, Collection<String> requestedMetricIds, Long start, Long end, Integer requestedStep) {
    Session session = requireSession(runId);
    if ("DELETED".equals(session.getStatus()) || repository.hasTombstone(runId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitoring data was deleted");
    }
    heartbeat(session);
    List<String> metricIds = requestedMetricIds == null || requestedMetricIds.isEmpty()
        ? session.getMetricIds()
        : normalizedMetricIds(requestedMetricIds);
    if (!session.getMetricIds().containsAll(metricIds)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metricIds must be a subset of the session");
    }
    long now = System.currentTimeMillis();
    long queryStart = start == null ? session.getTestStartAt() : Math.max(start, session.getTestStartAt());
    long sessionEnd = session.getTestEndAt() > 0 ? session.getTestEndAt() : now;
    long queryEnd = end == null ? sessionEnd : Math.min(end, sessionEnd);
    if (queryEnd < queryStart) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "end must not be before start");
    }
    int step = adjustedStep(queryStart, queryEnd, requestedStep == null ? session.getQueryStepSec() : requestedStep);
    return querySeries(session, metricIds, queryStart, queryEnd, step);
  }

  public synchronized Session completeSession(String runId) {
    Session session = requireSession(runId);
    if ("COMPLETED".equals(session.getStatus()) || "EXPIRED".equals(session.getStatus())) {
      return session;
    }
    session.setStatus("FINALIZING");
    if (session.getTestEndAt() <= 0) {
      session.setTestEndAt(System.currentTimeMillis());
    }
    session.setUpdatedAt(System.currentTimeMillis());
    session.setArchiveStatus(session.isArchiveEnabled() ? "PENDING" : "NONE");
    repository.save(session);

    if (session.isArchiveEnabled()) {
      try {
        int step = adjustedStep(session.getTestStartAt(), session.getTestEndAt(), session.getQueryStepSec());
        SeriesResponse response = querySeries(
            session, session.getMetricIds(), session.getTestStartAt(), session.getTestEndAt(), step);
        repository.writeSeries(runId, response);
        long populated = response.series().stream().filter(item -> !item.points().isEmpty()).count();
        session.setDataCompleteness(session.getMetricIds().isEmpty()
            ? 1.0
            : Math.min(1.0, populated / (double) session.getMetricIds().size()));
        session.setArchiveStatus("SUCCESS");
      } catch (RuntimeException ex) {
        session.setArchiveStatus("FAILED");
        session.getWarnings().add("监控曲线归档失败，可在 Prometheus 保留期内重试");
        log.warn("Monitoring archive failed for {}: {}", runId, ex.getClass().getSimpleName());
      }
    }
    try {
      agentClient.deleteSession(runId);
    } catch (RuntimeException ex) {
      session.getWarnings().add("Agent 会话关闭失败，将由 TTL 自动回收");
    }
    session.setStatus("COMPLETED");
    session.setUpdatedAt(System.currentTimeMillis());
    repository.save(session);
    return session;
  }

  public List<Session> history() {
    return repository.list();
  }

  public Session history(String runId) {
    return requireSession(runId);
  }

  public SeriesResponse historySeries(String runId) {
    if (repository.hasTombstone(runId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitoring data was deleted");
    }
    Optional<SeriesResponse> archived = repository.readSeries(runId);
    if (archived.isPresent()) {
      return archived.get();
    }
    Session session = requireSession(runId);
    long end = session.getTestEndAt() > 0 ? session.getTestEndAt() : System.currentTimeMillis();
    return querySeries(
        session, session.getMetricIds(), session.getTestStartAt(), end,
        adjustedStep(session.getTestStartAt(), end, session.getQueryStepSec()));
  }

  public synchronized void deleteHistory(String runId) {
    Session session = repository.find(requireRunId(runId)).orElse(null);
    if (session == null) {
      return;
    }
    if (NON_DELETABLE_STATES.contains(session.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "running monitoring data cannot be deleted");
    }
    repository.delete(runId);
  }

  @Scheduled(fixedDelay = 30000)
  public void expireUnattendedSessions() {
    long now = System.currentTimeMillis();
    for (Session session : repository.list()) {
      if (("ACTIVE".equals(session.getStatus()) || "DEGRADED".equals(session.getStatus()))
          && session.getExpiresAt() > 0
          && now >= session.getExpiresAt()) {
        try {
          completeSession(session.getRunId());
          session.setStatus("EXPIRED");
          repository.save(session);
        } catch (RuntimeException ex) {
          log.warn("Cannot expire monitoring session {}: {}", session.getRunId(), ex.getClass().getSimpleName());
        }
      }
    }
  }

  @Scheduled(cron = "0 23 3 * * *")
  public void enforceRetention() {
    long cutoff = System.currentTimeMillis() - Duration.ofDays(properties.getArchiveRetentionDays()).toMillis();
    for (Session session : repository.list()) {
      long reference = session.getTestEndAt() > 0 ? session.getTestEndAt() : session.getCreatedAt();
      if (!NON_DELETABLE_STATES.contains(session.getStatus()) && reference > 0 && reference < cutoff) {
        try {
          repository.delete(session.getRunId());
        } catch (RuntimeException ex) {
          log.warn("Cannot delete expired monitoring archive {}", session.getRunId());
        }
      }
    }
  }

  private SeriesResponse querySeries(
      Session session, List<String> metricIds, long start, long end, int step) {
    List<MetricSeries> allSeries = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String targetId = session.getTargetIds().isEmpty() ? DEFAULT_TARGET_ID : session.getTargetIds().get(0);
    for (String metricId : metricIds) {
      MetricDefinition definition = requireDefinition(metricId);
      try {
        List<MetricSeries> result = prometheusClient.queryRange(
            definition, start, end, step, targetId, session.getContainerName(), session.getDbType());
        if (result.isEmpty()) {
          allSeries.add(new MetricSeries(
              metricId, targetId, Map.of(), definition.unit(), List.of(), "NO_DATA"));
        } else {
          allSeries.addAll(result);
        }
      } catch (RuntimeException ex) {
        allSeries.add(new MetricSeries(
            metricId, targetId, Map.of(), definition.unit(), List.of(), "QUERY_FAILED"));
        warnings.add(metricId + " 查询失败");
      }
    }
    return new SeriesResponse(session.getRunId(), start, end, allSeries, warnings);
  }

  private void heartbeat(Session session) {
    try {
      agentClient.heartbeat(session.getRunId());
      session.setExpiresAt(System.currentTimeMillis() + properties.getSessionTtlSec() * 1000L);
      session.setUpdatedAt(System.currentTimeMillis());
      if ("DEGRADED".equals(session.getStatus())) {
        session.setStatus("ACTIVE");
      }
      repository.save(session);
    } catch (RuntimeException ex) {
      if ("ACTIVE".equals(session.getStatus())) {
        session.setStatus("DEGRADED");
        session.getWarnings().add("Agent 心跳失败，压测不受影响");
        repository.save(session);
      }
    }
  }

  /**
   * Returns the collector ids the agent advertises, or null when the list cannot
   * be read (monitoring disabled, agent down, or an empty answer). Null means
   * "no filtering": the session request keeps every candidate and the agent call
   * is the one that reports the problem.
   */
  private Set<String> advertisedCollectors() {
    if (!properties.isEnabled()) {
      return null;
    }
    try {
      JsonNode root = agentClient.capabilities();
      Set<String> collectors = new LinkedHashSet<>();
      root.path("collectors").forEach(item -> collectors.add(item.asText()));
      return collectors.isEmpty() ? null : collectors;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private AgentCapability readAgentCapability(boolean cadvisorAvailable) {
    if (!properties.isEnabled()) {
      return new AgentCapability(false, Set.of(), Map.of(), cadvisorAvailable, "监控功能未启用");
    }
    try {
      JsonNode root = agentClient.capabilities();
      Set<String> collectors = new LinkedHashSet<>();
      root.path("collectors").forEach(item -> collectors.add(item.asText()));
      Map<String, Boolean> probes = new LinkedHashMap<>();
      root.path("nativeProbes").forEach(item -> probes.put(item.path("name").asText(), item.path("loaded").asBoolean()));
      return new AgentCapability(true, collectors, probes, cadvisorAvailable, "");
    } catch (RuntimeException ex) {
      return new AgentCapability(false, Set.of(), Map.of(), cadvisorAvailable, "Agent 不可用");
    }
  }

  private String normalizeContainerName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (!CONTAINER_NAME.matcher(trimmed).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid containerName");
    }
    return trimmed;
  }

  /**
   * 被测库类型；只用于给 db.* 指标选数据源（mysql_* / og_*）。
   *
   * <p>传空时返回 null，会话保持旧行为（查询模板原样执行，由 PromQL 的 or 决定）。
   */
  private String normalizeDbType(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case "mysql", "postgresql", "opengauss", "gaussdb", "oceanbase" -> normalized;
      default -> null;
    };
  }

  private Session requireSession(String runId) {
    return repository.find(requireRunId(runId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "monitoring session not found"));
  }

  private MetricDefinition requireDefinition(String metricId) {
    return catalog.find(metricId)
        .orElseThrow(() -> {
          log.warn("Rejected monitoring session: unsupported metricId {}", metricId);
          return new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported metricId: " + metricId);
        });
  }

  private String requireRunId(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,160}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid runId");
    }
    return runId;
  }

  private List<String> normalizedMetricIds(Collection<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  private int clampStep(int step) {
    // 下限 1s：agent 主机采集器是 1s 桶、Prometheus 也按 1s 抓取，
    // 30s 故障窗口需要 1s 步长才能看到峰值（3s 步长只有 10 个点）。
    // 长跑由 adjustedStep 按 max-points 自动加粗，不会无限增长。
    return Math.max(1, Math.min(step, 300));
  }

  private int adjustedStep(long start, long end, int requested) {
    long durationSec = Math.max(0, (end - start) / 1000L);
    int minimumForPointLimit = (int) Math.max(1, Math.ceil(durationSec / (double) properties.getMaxPoints()));
    return clampStep(Math.max(requested, minimumForPointLimit));
  }

  private record AgentCapability(
      boolean available,
      Set<String> collectors,
      Map<String, Boolean> probes,
      boolean cadvisorAvailable,
      String reason) {
    private boolean supports(MetricDefinition definition) {
      if ("container".equals(definition.category())) {
        return cadvisorAvailable;
      }
      if (!available) {
        return false;
      }
      List<String> candidates = definition.collectorIds();
      if (!candidates.isEmpty()) {
        // og_* 与 mysql_* 两种产品的采集器：任一存在即可查询（PromQL 用 or 连接）。
        return candidates.stream().anyMatch(collectors::contains);
      }
      if (definition.nativeProbe() != null && !definition.nativeProbe().isBlank()) {
        return Boolean.TRUE.equals(probes.get(definition.nativeProbe()));
      }
      // 不依赖任何 agent 采集器的指标：序列由我们自己的导出器直接暴露给 Prometheus
      // （目前只有 net.qdisc.*，见 scripts/monitoring/qdisc_exporter.py）。agent 的
      // 采集器清单里本来就不会有它，不能因此判成「collector 未启用」——真的没部署时，
      // 会以空曲线 / QUERY_FAILED 的 dataStatus 暴露出来，那才是该看到的现象。
      return true;
    }

    private String unavailableReason(MetricDefinition definition) {
      if ("container".equals(definition.category())) {
        return "cAdvisor 未就绪";
      }
      if (!available) {
        return reason;
      }
      if (definition.nativeProbe() != null && !definition.nativeProbe().isBlank()) {
        return "native probe 未加载";
      }
      return "collector 未启用";
    }
  }
}
