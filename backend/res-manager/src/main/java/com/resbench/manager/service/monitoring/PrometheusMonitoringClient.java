package com.resbench.manager.service.monitoring;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.resbench.manager.config.properties.MonitoringProperties;
import com.resbench.manager.model.monitoring.MonitoringModels.MetricDefinition;
import com.resbench.manager.model.monitoring.MonitoringModels.MetricSeries;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Component
public class PrometheusMonitoringClient {
  private final RestTemplate restTemplate;
  private final MonitoringProperties properties;

  public PrometheusMonitoringClient(
      @Qualifier("monitoringRestTemplate") RestTemplate restTemplate,
      MonitoringProperties properties) {
    this.restTemplate = restTemplate;
    this.properties = properties;
  }

  public boolean isHealthy() {
    try {
      String result = restTemplate.getForObject(properties.getPrometheusUrl() + "/-/ready", String.class);
      return result != null && result.toLowerCase().contains("ready");
    } catch (RestClientException ex) {
      return false;
    }
  }

  /** cAdvisor 的 scrape job 是否有存活目标；容器指标只有它可用时才有意义。 */
  public boolean isCadvisorAvailable() {
    try {
      URI uri = UriComponentsBuilder
          .fromHttpUrl(properties.getPrometheusUrl() + "/api/v1/query")
          .queryParam("query", "up{job=\"cadvisor\"}")
          .build()
          .encode()
          .toUri();
      JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
      if (root == null || !"success".equals(root.path("status").asText())) {
        return false;
      }
      for (JsonNode item : root.path("data").path("result")) {
        if ("1".equals(item.path("value").path(1).asText())) {
          return true;
        }
      }
      return false;
    } catch (RestClientException ex) {
      return false;
    }
  }

  private static final Set<String> MYSQL_DB_TYPES = Set.of("mysql");
  private static final Set<String> OG_DB_TYPES = Set.of("postgresql", "opengauss", "gaussdb");
  private static final Pattern WRAPPED_TEMPLATE =
      Pattern.compile("^\\((.*)\\)(\\s*\\*\\s*100)\\s*$", Pattern.DOTALL);

  /**
   * 按被测库类型从模板里的 `og_... or mysql_...` 中挑出对应分支。
   *
   * <p>不含 og_/mysql_ 的模板（主机、网络、容器类）原样返回；dbType 为空（旧会话 /
   * UI 未传）也原样返回，保持旧行为。若模板只提供另一种产品的序列，返回空串，
   * 由调用方当作 NO_DATA——宁可不给数据，也不能把 PG 的值冒充成 MySQL 的。
   */
  static String selectTemplate(String template, String dbType) {
    if (template == null || dbType == null) {
      return template;
    }
    boolean mysql = MYSQL_DB_TYPES.contains(dbType);
    boolean og = OG_DB_TYPES.contains(dbType);
    if (!mysql && !og) {
      return template;
    }
    if (!template.contains("og_") && !template.contains("mysql_")) {
      return template;
    }
    String selected = pickBranch(template, mysql ? "mysql_" : "og_", mysql ? "og_" : "mysql_");
    return selected == null ? "" : selected;
  }

  private static String pickBranch(String expression, String want, String skip) {
    String trimmed = expression.trim();
    // 形如 `(og_x{...} or mysql_x{...}) * 100`：剥掉外层括号与后缀，选完再包回去。
    Matcher wrapped = WRAPPED_TEMPLATE.matcher(trimmed);
    if (wrapped.matches()) {
      String inner = pickBranch(wrapped.group(1), want, skip);
      return inner == null ? null : "(" + inner + ")" + wrapped.group(2);
    }
    if (!trimmed.contains(" or ")) {
      return trimmed.contains(want) && !trimmed.contains(skip) ? expression : null;
    }
    String[] parts = trimmed.split("\\s+or\\s+", 2);
    String left = parts[0].trim();
    String right = parts[1].trim();
    if (left.contains(want) && !left.contains(skip)) {
      return left;
    }
    if (right.contains(want) && !right.contains(skip)) {
      return right;
    }
    return null;
  }

  public List<MetricSeries> queryRange(
      MetricDefinition definition, long startMs, long endMs, int stepSec,
      String targetId, String containerName, String dbType) {
    // db.* 指标同时存在 og_*（openGauss/PG）与 mysql_* 两套序列，模板里写成
    // `og_x or mysql_x`。两条序列的标签集相同，PromQL 的 or 会优先返回左侧，
    // 所以监控 MySQL 时无论故障怎么打都只会返回 PG 的值。这里按会话的 dbType
    // 先挑出对应分支，再替换 instance。
    String template = selectTemplate(definition.queryTemplate(), dbType);
    if (template.isEmpty()) {
      // 该指标没有被测库产品对应的序列（例如 MySQL 没有 openGauss 的等待事件视图）。
      return List.of();
    }
    if (template.contains("{{container}}") && (containerName == null || containerName.isBlank())) {
      return List.of();
    }
    String query = template
        .replace("{{instance}}", escapeLabelValue(properties.getPrometheusInstance()))
        .replace("{{container}}", escapeLabelValue(containerName));
    // UriComponentsBuilder.encode() 不会转义 '+'：Prometheus 会把查询串里的 '+' 当空格解码，
    // 于是 `rate(a) + rate(b)` 变成 `rate(a) rate(b)`，整个查询 400（host.netstat.reorder
    // 这种复合指标就是这样全部 QUERY_FAILED 的）。先按 query 参数编码，再把 '+' 显式写成 %2B。
    String encodedQuery = UriUtils.encodeQueryParam(query, StandardCharsets.UTF_8)
        .replace("+", "%2B");
    URI uri = UriComponentsBuilder
        .fromHttpUrl(properties.getPrometheusUrl() + "/api/v1/query_range")
        .queryParam("query", encodedQuery)
        .queryParam("start", startMs / 1000.0)
        .queryParam("end", endMs / 1000.0)
        .queryParam("step", stepSec)
        .build(true)
        .toUri();
    JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
    if (root == null || !"success".equals(root.path("status").asText())) {
      return List.of();
    }
    JsonNode result = root.path("data").path("result");
    if (!result.isArray()) {
      return List.of();
    }

    List<MetricSeries> series = new ArrayList<>();
    for (JsonNode item : result) {
      Map<String, String> labels = labels(item.path("metric"));
      List<List<Object>> points = new ArrayList<>();
      for (JsonNode value : item.path("values")) {
        if (!value.isArray() || value.size() < 2) {
          continue;
        }
        double parsed;
        try {
          parsed = Double.parseDouble(value.get(1).asText());
        } catch (NumberFormatException ex) {
          continue;
        }
        if (!Double.isFinite(parsed)) {
          continue;
        }
        points.add(List.of(Math.round(value.get(0).asDouble() * 1000), parsed));
      }
      series.add(new MetricSeries(
          definition.metricId(), targetId, labels, definition.unit(), points,
          points.isEmpty() ? "NO_DATA" : "OK"));
    }
    return series;
  }

  private String escapeLabelValue(String value) {
    return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private Map<String, String> labels(JsonNode metric) {
    Map<String, String> labels = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = metric.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (!"__name__".equals(field.getKey()) && !"job".equals(field.getKey())) {
        labels.put(field.getKey(), field.getValue().asText());
      }
    }
    return labels;
  }
}
