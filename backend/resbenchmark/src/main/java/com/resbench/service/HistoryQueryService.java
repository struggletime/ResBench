package com.resbench.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.constant.PathConstant;
import com.resbench.util.DbTypeUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 历史测试检索：扫描 results/&lt;historyName&gt;/，把每轮的元信息（run-meta.json）、
 * 运行状态（job.json）、汇总（*.summary.json）、指标（metric.json）、故障注入（faultInject.json）
 * 与产物完整性汇总成可筛选、可分页的行。
 *
 * <p>不搬运原始数据：只读结果目录，原始文件（monitor.csv / raw.csv / PNG …）保持原位，
 * 检索到之后仍通过 /web/bench/monitor、/web/bench/newest_result、/web/bench/inject/info
 * 或直接读文件取用（绘制曲线与韧性三角所需的数据都在那里）。
 */
@Service
public class HistoryQueryService {
  private static final Logger LOG = LoggerFactory.getLogger(HistoryQueryService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter NAME_TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  private static final String RUN_META_NAME = "run-meta.json";

  public Map<String, Object> query(Map<String, String> filters) {
    Map<String, String> f = filters == null ? Map.of() : filters;
    List<Map<String, Object>> rows = new ArrayList<>();
    Path root = Path.of(PathConstant.RESULTS_PATH);
    if (Files.isDirectory(root)) {
      try (Stream<Path> dirs = Files.list(root)) {
        dirs.filter(Files::isDirectory).forEach(dir -> {
          Map<String, Object> row = buildRow(dir);
          if (row != null && matches(row, f)) {
            rows.add(row);
          }
        });
      } catch (IOException e) {
        LOG.warn("Failed to list results directory: {}", e.getMessage());
      }
    }

    boolean asc = "start_asc".equalsIgnoreCase(f.getOrDefault("sort", ""));
    rows.sort((a, b) -> {
      Long ta = asLong(a.get("startTimestamp"), Long.MIN_VALUE);
      Long tb = asLong(b.get("startTimestamp"), Long.MIN_VALUE);
      return asc ? Long.compare(ta, tb) : Long.compare(tb, ta);
    });

    int size = (int) clamp(asLong(f.get("size"), 20L), 1, 200);
    int total = rows.size();
    int pageCount = Math.max(1, (int) Math.ceil(total / (double) size));
    int page = (int) clamp(asLong(f.get("page"), 1L), 1, pageCount);
    int from = Math.min((page - 1) * size, total);
    int to = Math.min(from + size, total);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("total", total);
    response.put("page", page);
    response.put("size", size);
    response.put("pageCount", pageCount);
    response.put("rows", new ArrayList<>(rows.subList(from, to)));
    return response;
  }

  private Map<String, Object> buildRow(Path dir) {
    String historyName = dir.getFileName().toString();
    try {
      JsonNode meta = readJson(dir.resolve(RUN_META_NAME));
      JsonNode job = readJson(dir.resolve(PathConstant.getJobStateName()));
      Path summaryPath = findFirst(dir, "summary.json");
      JsonNode summary = summaryPath == null ? null : readJson(summaryPath);
      JsonNode metric = readJson(dir.resolve("metric.json"));
      JsonNode faultInject = readJson(dir.resolve(PathConstant.getFaultInjectName()));

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("historyName", historyName);
      row.put("runId", text(meta, "runId", historyName));
      row.put("testSuite", firstNonBlank(text(meta, "testSuite", null), prefixOf(historyName)));
      row.put("dbKey", text(meta, "dbKey", null));
      row.put("orchestrationId", text(meta, "orchestrationId", null));
      row.put("source", text(meta, "source", "ui"));
      row.put("labels", jsonToList(meta, "labels"));
      row.put("note", text(meta, "note", null));
      row.put("autoTimingMode", text(meta, "autoTimingMode", null));

      row.put("dbTypeRaw", text(summary, "DBMS Type", null));
      row.put("dbType", lower(firstNonBlank(text(summary, "DBMS Type", null), nestedText(meta, "database", "dbType"))));
      row.put("dbVersion", text(summary, "DBMS Version", null));
      row.put("workload", firstNonBlank(text(summary, "Benchmark Type", null), nestedText(meta, "workload", "name")));
      row.put("workloadParams", child(meta, "workload", "params"));
      row.put("dbParams", child(meta, "database", "params"));
      row.put("deploymentType", nestedText(meta, "database", "deploymentType"));
      // Docker / 资源快照（老归档没有这些键 → null，前端显示“未归档”）
      row.put("containerName", nestedText(meta, "database", "containerName"));
      row.put("dockerized", nestedBoolean(meta, "database", "dockerized"));
      row.put("resources", child(meta, "database", "resources"));
      row.put("connectionArchived", nestedBoolean(meta, "database", "connectionArchived"));
      row.put("createdAt", meta != null && meta.hasNonNull("createdAt") ? meta.get("createdAt").asLong() : null);
      row.put("runOptions", nodeValue(meta, "runOptions"));
      row.put("faults", faultItems(meta, faultInject));
      row.put("faultOutcome", faultOutcome(meta, faultInject));

      Long start = parseStart(historyName, job);
      Long end = parseEnd(job);
      row.put("startTimestamp", start);
      row.put("endTimestamp", end);
      row.put("elapsedMs", (start != null && end != null) ? end - start : null);
      row.put("success", job != null && job.hasNonNull("success") ? job.get("success").asBoolean() : null);
      row.put("state", job != null ? text(job, "curState", null) : null);

      Map<String, Object> artifacts = new LinkedHashMap<>();
      boolean hasMonitor = Files.isRegularFile(dir.resolve(PathConstant.getMonitorName()));
      boolean hasMetric = metric != null;
      boolean hasSummary = summary != null;
      boolean hasFaultInject = faultInject != null;
      boolean hasRunMeta = meta != null;
      boolean hasRaw = findFirst(dir, ".raw.csv") != null;
      artifacts.put("hasRunMeta", hasRunMeta);
      artifacts.put("hasSummary", hasSummary);
      artifacts.put("hasMonitor", hasMonitor);
      artifacts.put("hasMetric", hasMetric);
      artifacts.put("hasFaultInject", hasFaultInject);
      artifacts.put("hasRaw", hasRaw);
      // 完整性口径：画曲线（monitor）与算韧性三角（summary + 指标）所需数据齐全。
      // faultInject.json 只在注入过故障的运行里存在，因此不参与 complete 判定。
      artifacts.put("complete", hasSummary && hasMonitor && hasMetric);
      long[] size = dirSize(dir);
      artifacts.put("sizeBytes", size[0]);
      artifacts.put("rawBytes", size[1]);
      row.put("artifacts", artifacts);

      row.put("metrics", aggregateMetrics(metric, faultInject));
      return row;
    } catch (Exception e) {
      LOG.warn("Failed to build history row for {}: {}", historyName, e.getMessage());
      return null;
    }
  }

  /**
   * 指标阈值筛选：只对"有 metric.json"的运行有意义（缺指标的运行在设了阈值时被排除）。
   * 支持 minStability / minDefence / minRecovery / minThroughputRate / maxRto / maxOutageSeconds。
   */
  private boolean matchesMetricThresholds(Map<String, Object> row, Map<String, String> f) {
    Object metrics = row.get("metrics");
    Map<?, ?> map = metrics instanceof Map<?, ?> m ? m : Map.of();
    return atLeast(map, f.get("minStability"), "stability")
        && atLeast(map, f.get("minDefence"), "defence")
        && atLeast(map, f.get("minRecovery"), "recovery")
        && atLeast(map, f.get("minThroughputRate"), "throughputRate")
        && atMost(map, f.get("maxRto"), "rto")
        && atMost(map, f.get("maxOutageSeconds"), "outageSeconds");
  }

  private static boolean atLeast(Map<?, ?> metrics, String filter, String key) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    Double want = parseDouble(filter);
    Double have = parseDouble(metrics.get(key));
    return want != null && have != null && have >= want;
  }

  private static boolean atMost(Map<?, ?> metrics, String filter, String key) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    Double want = parseDouble(filter);
    Double have = parseDouble(metrics.get(key));
    return want != null && have != null && have <= want;
  }

  private static Double parseDouble(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private boolean matches(Map<String, Object> row, Map<String, String> f) {
    String keyword = lower(f.get("keyword"));
    if (!keyword.isEmpty()) {
      String haystack = String.join(" ",
          String.valueOf(row.getOrDefault("historyName", "")),
          String.valueOf(row.getOrDefault("runId", "")),
          String.valueOf(row.getOrDefault("testSuite", "")),
          String.valueOf(row.getOrDefault("dbKey", "")),
          String.valueOf(row.getOrDefault("orchestrationId", "")),
          String.valueOf(row.getOrDefault("note", "")),
          String.valueOf(row.getOrDefault("labels", ""))).toLowerCase(Locale.ROOT);
      if (!haystack.contains(keyword)) {
        return false;
      }
    }
    // dbType 按「族」匹配：摘要里的 POSTGRES 与筛选值 postgresql 视为同一类，
    // 避免选 PostgreSQL 时把 DBMS Type=POSTGRES 的归档全部过滤掉。
    if (!DbTypeUtils.sameFamily(textOf(row.get("dbType")), f.get("dbType"))) {
      return false;
    }
    if (!containsIgnoreCaseFilter(row.get("workload"), f.get("workload"))) {
      return false;
    }
    if (!containsIgnoreCaseFilter(row.get("orchestrationId"), f.get("orchestration"))) {
      return false;
    }
    if (!containsIgnoreCaseFilter(row.get("dbKey"), f.get("dbKey"))) {
      return false;
    }
    if (!equalsIgnoreCaseFilter(row.get("source"), f.get("source"))) {
      return false;
    }
    String label = lower(f.get("label"));
    if (!label.isEmpty() && !String.valueOf(row.getOrDefault("labels", "")).toLowerCase(Locale.ROOT).contains(label)) {
      return false;
    }
    String fault = normalize(f.get("fault"));
    if (!fault.isEmpty()) {
      boolean hit = false;
      Object items = row.get("faults");
      if (items instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof Map<?, ?> map
              && (normalize(String.valueOf(map.get("nameKey"))).contains(fault)
                  || normalize(String.valueOf(map.get("faultName"))).contains(fault))) {
            hit = true;
            break;
          }
        }
      }
      if (!hit) {
        return false;
      }
    }
    String faultOutcome = lower(f.get("faultOutcome"));
    if ("all-ok".equals(faultOutcome) || "allok".equals(faultOutcome)) {
      Object outcome = row.get("faultOutcome");
      if (!(outcome instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("allOk"))) {
        return false;
      }
    }
    if (f.containsKey("success") && !f.get("success").isBlank()) {
      Boolean want = Boolean.parseBoolean(f.get("success"));
      if (!want.equals(row.get("success"))) {
        return false;
      }
    }
    if ("true".equalsIgnoreCase(f.get("complete"))) {
      Object artifacts = row.get("artifacts");
      if (!(artifacts instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("complete"))) {
        return false;
      }
    }
    if (!matchesMetricThresholds(row, f)) {
      return false;
    }
    Long start = asLongObj(row.get("startTimestamp"));
    if (start != null) {
      Long since = asLongObj(f.get("since"));
      if (since != null && start < since) {
        return false;
      }
      Long until = asLongObj(f.get("until"));
      if (until != null && start > until) {
        return false;
      }
    }
    return true;
  }

  /**
   * 合并「用户配置的故障编排」（run-meta.json）与「实际注入记录」（faultInject.json）。
   *
   * <p>关键点：StartByFormService 调度时会给空 groupKey 补 {@code fault-<index>}，
   * 所以 run-meta 里的 {@code groupKey=null} 与 faultInject 里的 {@code groupKey="fault-0"}
   * 是同一个故障。这里统一用 effectiveGroupKey 对齐，否则同一故障会重复出现两次。
   *
   * <p>除 nameKey / groupKey / params 外，还把实际注入的完整记录（命令、起止时间、验证结论）
   * 一并带出，历史报告不必再依赖额外接口就能展示故障参数。
   */
  private List<Map<String, Object>> faultItems(JsonNode meta, JsonNode faultInject) {
    List<Map<String, Object>> items = new ArrayList<>();
    if (meta != null && meta.has("faults") && meta.get("faults").isArray()) {
      int index = 0;
      for (JsonNode fault : meta.get("faults")) {
        Map<String, Object> item = new LinkedHashMap<>();
        String declaredGroup = text(fault, "groupKey", null);
        item.put("nameKey", text(fault, "nameKey", null));
        item.put("groupKey", declaredGroup);
        item.put("effectiveGroupKey", isBlank(declaredGroup) ? "fault-" + index : declaredGroup);
        item.put("delaySeconds", fault.hasNonNull("delaySeconds") ? fault.get("delaySeconds").asInt() : null);
        item.put("params", fault.has("params") ? MAPPER.convertValue(fault.get("params"), Map.class) : Map.of());
        item.put("faultName", null);
        item.put("verification", null);
        items.add(item);
        index++;
      }
    }

    List<JsonNode> infos = new ArrayList<>();
    if (faultInject != null && faultInject.isArray()) {
      faultInject.forEach(infos::add);
    }
    boolean[] matched = new boolean[items.size()];

    // 1) 按 effectiveGroupKey 精确配对（新归档都有 key）
    List<JsonNode> remaining = new ArrayList<>();
    for (JsonNode info : infos) {
      String groupKey = text(info, "groupKey", null);
      int target = -1;
      if (!isBlank(groupKey)) {
        for (int i = 0; i < items.size(); i++) {
          if (!matched[i] && groupKey.equals(items.get(i).get("effectiveGroupKey"))) {
            target = i;
            break;
          }
        }
      }
      if (target >= 0) {
        enrichFaultItem(items.get(target), info);
        matched[target] = true;
      } else {
        remaining.add(info);
      }
    }

    // 2) 没有任何 groupKey 的老归档：按注入顺序与未匹配的配置项一一配对
    List<JsonNode> extra = new ArrayList<>();
    for (JsonNode info : remaining) {
      String groupKey = text(info, "groupKey", null);
      int target = -1;
      if (isBlank(groupKey)) {
        for (int i = 0; i < items.size(); i++) {
          if (!matched[i]) {
            target = i;
            break;
          }
        }
      }
      if (target >= 0) {
        enrichFaultItem(items.get(target), info);
        matched[target] = true;
      } else {
        extra.add(info);
      }
    }

    // 3) 配置里没有、但确实注入过的故障：补一条，保证不丢信息
    for (JsonNode info : extra) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("nameKey", null);
      item.put("groupKey", text(info, "groupKey", null));
      item.put("effectiveGroupKey", text(info, "groupKey", null));
      item.put("delaySeconds", info.hasNonNull("faultDelay") ? info.get("faultDelay").asInt() : null);
      item.put("params", Map.of());
      item.put("faultName", null);
      item.put("verification", null);
      enrichFaultItem(item, info);
      items.add(item);
    }
    return items;
  }

  private static void enrichFaultItem(Map<String, Object> item, JsonNode info) {
    item.put("faultID", text(info, "faultID", null));
    item.put("faultName", text(info, "faultName", null));
    if (item.get("delaySeconds") == null && info.hasNonNull("faultDelay")) {
      item.put("delaySeconds", info.get("faultDelay").asInt());
    }
    item.put("faultDuration", info.hasNonNull("faultDuration") ? info.get("faultDuration").asInt() : null);
    item.put("startTime", info.hasNonNull("startTime") ? info.get("startTime").asLong() : null);
    item.put("endTime", info.hasNonNull("endTime") ? info.get("endTime").asLong() : null);
    item.put("startTimeFormat", text(info, "startTimeFormat", null));
    item.put("endTimeFormat", text(info, "endTimeFormat", null));
    item.put("injectionCMD", text(info, "injectionCMD", null));
    item.put("recoveryCMD", text(info, "recoveryCMD", null));
    item.put("autoRecovery", info.hasNonNull("autoRecovery") ? info.get("autoRecovery").asBoolean() : null);
    item.put("faultResults", nodeValue(info, "faultResults"));
    item.put("recoveryResult", nodeValue(info, "recoveryResult"));
    item.put("verification", nodeValue(info, "verification"));
    item.put("recoveryVerification", nodeValue(info, "recoveryVerification"));
  }

  /**
   * 故障执行结论汇总：本次运行声明的故障是否全部注入成功、并且全部恢复成功。
   *
   * <p>判定口径与前端 {@code VerificationBadges} 的绿色徽章保持一致：
   *
   * <ul>
   *   <li>注入：每条记录都收到了 agent 回复（{@code replied=true}；老归档没有该字段则无法确认）、
   *       没有 {@code error}、result 不是失败文本，并且记录了往返耗时；</li>
   *   <li>恢复：{@code autoRecovery=true} 且没有恢复记录 → 视为由 chaosblade {@code --timeout}
   *       自行恢复；否则按同样的成功条件判断（{@code selfRecovered=true} 的超时自愈也算恢复完成）；</li>
   *   <li>声明了故障却没有对应的 faultInject 记录 → 不算成功。</li>
   * </ul>
   *
   * <p>没有故障的基准运行 {@code hasFaults=false}，不会命中 {@code all-ok} 筛选。
   */
  private static Map<String, Object> faultOutcome(JsonNode meta, JsonNode faultInject) {
    int declared = meta != null && meta.has("faults") && meta.get("faults").isArray()
        ? meta.get("faults").size() : 0;
    List<JsonNode> infos = new ArrayList<>();
    if (faultInject != null && faultInject.isArray()) {
      faultInject.forEach(infos::add);
    }
    boolean hasFaults = declared > 0 || !infos.isEmpty();
    boolean allInjected = hasFaults && !infos.isEmpty() && infos.size() >= declared;
    boolean allRecovered = allInjected;
    int injectedOk = 0;
    int recoveredOk = 0;
    for (JsonNode info : infos) {
      boolean injected = resultsSucceeded(info.get("faultResults"), false);
      boolean recovered = recoverySucceeded(info);
      if (injected) {
        injectedOk++;
      }
      if (recovered) {
        recoveredOk++;
      }
      allInjected &= injected;
      allRecovered &= recovered;
    }
    Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("hasFaults", hasFaults);
    outcome.put("declaredFaults", declared);
    outcome.put("recordedFaults", infos.size());
    outcome.put("injectedOk", injectedOk);
    outcome.put("recoveredOk", recoveredOk);
    outcome.put("allInjected", allInjected);
    outcome.put("allRecovered", allRecovered);
    outcome.put("allOk", hasFaults && allInjected && allRecovered);
    return outcome;
  }

  private static boolean recoverySucceeded(JsonNode info) {
    JsonNode results = info.get("recoveryResult");
    if (results == null || !results.isArray() || results.isEmpty()) {
      // 网络类故障不显式下发 destroy，靠 --timeout 到点自愈
      return info.path("autoRecovery").asBoolean(false);
    }
    return resultsSucceeded(results, true);
  }

  private static boolean resultsSucceeded(JsonNode results, boolean allowSelfRecovered) {
    if (results == null || !results.isArray() || results.isEmpty()) {
      return false;
    }
    boolean hasVerdict = false;
    boolean hasElapsed = false;
    for (JsonNode result : results) {
      if (result == null || result.isNull()) {
        return false;
      }
      boolean healed = allowSelfRecovered && result.path("selfRecovered").asBoolean(false);
      if (!healed) {
        if (result.path("error").asBoolean(false)
            || failureText(result.path("result").asText(null))) {
          return false;
        }
      }
      if (result.has("replied") && result.get("replied").isBoolean()) {
        if (!result.get("replied").asBoolean()) {
          return false;
        }
        hasVerdict = true;
      }
      if (result.hasNonNull("elapsedMs")) {
        hasElapsed = true;
      }
    }
    // 老归档没有 replied/elapsedMs，无法证明「确实得到 agent 成功回复」，按严格口径排除
    return hasVerdict && hasElapsed;
  }

  private static boolean failureText(String text) {
    if (text == null || text.isBlank()) {
      return true;
    }
    String lower = text.toLowerCase(Locale.ROOT);
    return "false".equals(lower.trim())
        || lower.contains("executed: error")
        || lower.contains("\"success\":false")
        || lower.contains("no response from agent")
        || lower.contains("no reply from agent");
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private Map<String, Object> aggregateMetrics(JsonNode metric, JsonNode faultInject) {
    Map<String, Object> aggregate = new LinkedHashMap<>();
    int enforced = countEnforced(faultInject);
    if (metric == null || !metric.isArray() || metric.isEmpty()) {
      aggregate.put("enforcedFaultCount", enforced);
      return aggregate;
    }
    double stability = 0;
    double defence = 0;
    double recovery = 0;
    double rto = 0;
    double throughputRate = 0;
    double outageSeconds = 0;
    int count = metric.size();
    for (JsonNode item : metric) {
      stability += item.path("stability").asDouble(0);
      defence += item.path("defence").asDouble(0);
      recovery += item.path("recovery").asDouble(0);
      rto += item.path("rto").asDouble(0);
      throughputRate += item.path("throughput_rate").asDouble(0);
      outageSeconds += item.path("outage_seconds").asDouble(0);
    }
    aggregate.put("faultCount", count);
    aggregate.put("stability", round(stability / count));
    aggregate.put("defence", round(defence / count));
    aggregate.put("recovery", round(recovery / count));
    aggregate.put("rto", round(rto / count));
    aggregate.put("throughputRate", round(throughputRate / count));
    aggregate.put("outageSeconds", round(outageSeconds));
    aggregate.put("enforcedFaultCount", enforced);
    return aggregate;
  }

  private static int countEnforced(JsonNode faultInject) {
    int enforced = 0;
    if (faultInject != null && faultInject.isArray()) {
      for (JsonNode info : faultInject) {
        JsonNode verification = info.get("verification");
        if (verification != null && verification.hasNonNull("status")
            && "ENFORCED".equalsIgnoreCase(verification.get("status").asText())) {
          enforced++;
        }
      }
    }
    return enforced;
  }

  private static double round(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private static long[] dirSize(Path dir) {
    long total = 0;
    long raw = 0;
    try (Stream<Path> files = Files.walk(dir, 2)) {
      for (Path path : files.filter(Files::isRegularFile).toList()) {
        long length = Files.size(path);
        total += length;
        if (path.getFileName().toString().endsWith(".raw.csv")) {
          raw += length;
        }
      }
    } catch (IOException | RuntimeException e) {
      LOG.debug("Failed to compute size for {}: {}", dir, e.getMessage());
    }
    return new long[] {total, raw};
  }

  private static Path findFirst(Path dir, String suffix) {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(suffix))
          .findFirst()
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  private static JsonNode readJson(Path path) {
    try {
      if (path == null || !Files.isRegularFile(path)) {
        return null;
      }
      return MAPPER.readTree(Files.readString(path));
    } catch (Exception e) {
      return null;
    }
  }

  private static Long parseStart(String historyName, JsonNode job) {
    if (job != null && job.hasNonNull("startTimeStamp")) {
      try {
        return Long.parseLong(job.get("startTimeStamp").asText());
      } catch (NumberFormatException ignored) {
        // fall through to name parsing
      }
    }
    int idx = historyName.lastIndexOf('_', historyName.lastIndexOf('_') - 1);
    if (idx > 0 && idx + 16 <= historyName.length()) {
      String stamp = historyName.substring(idx + 1);
      try {
        return LocalDateTime.parse(stamp, NAME_TS_FORMAT)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();
      } catch (Exception ignored) {
        return null;
      }
    }
    return null;
  }

  private static Long parseEnd(JsonNode job) {
    if (job != null && job.hasNonNull("stopTimeStamp")) {
      try {
        return Long.parseLong(job.get("stopTimeStamp").asText());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String prefixOf(String historyName) {
    int idx = historyName.indexOf('_');
    return idx > 0 ? historyName.substring(0, idx) : historyName;
  }

  private static String text(JsonNode node, String field, String fallback) {
    if (node == null) {
      return fallback;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? fallback : text;
  }

  private static String nestedText(JsonNode node, String parent, String child) {
    if (node == null) {
      return null;
    }
    JsonNode parentNode = node.get(parent);
    return parentNode == null ? null : text(parentNode, child, null);
  }

  private static Boolean nestedBoolean(JsonNode node, String parent, String child) {
    if (node == null) {
      return null;
    }
    JsonNode parentNode = node.get(parent);
    if (parentNode == null || !parentNode.hasNonNull(child)) {
      return null;
    }
    JsonNode value = parentNode.get(child);
    return value.isBoolean() ? value.asBoolean() : null;
  }

  private static Object nodeValue(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    return MAPPER.convertValue(node.get(field), Object.class);
  }

  private static Object child(JsonNode node, String parent, String child) {
    if (node == null) {
      return null;
    }
    JsonNode parentNode = node.get(parent);
    if (parentNode == null || !parentNode.has(child)) {
      return null;
    }
    return MAPPER.convertValue(parentNode.get(child), Object.class);
  }

  private static List<Object> jsonToList(JsonNode node, String field) {
    if (node == null || !node.has(field) || !node.get(field).isArray()) {
      return new ArrayList<>();
    }
    List<Object> list = new ArrayList<>();
    node.get(field).forEach(item -> list.add(item.asText()));
    return list;
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    return second;
  }

  private static boolean equalsIgnoreCaseFilter(Object value, String filter) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return value != null && filter.trim().equalsIgnoreCase(String.valueOf(value).trim());
  }

  private static String textOf(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static boolean containsIgnoreCaseFilter(Object value, String filter) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
  }

  private static String lower(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  /** 归一化：忽略大小写与分隔符，使 "cpu.load" / "CPU Load" / "faultinject.fault.cpu.load" 互相可匹配。 */
  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private static long clamp(long value, long min, long max) {
    return Math.max(min, Math.min(max, value));
  }

  private static Long asLong(Object value, Long fallback) {
    Long parsed = asLongObj(value);
    return parsed == null ? fallback : parsed;
  }

  private static Long asLongObj(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}