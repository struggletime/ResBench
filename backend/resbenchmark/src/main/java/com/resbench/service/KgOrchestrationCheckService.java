package com.resbench.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.knowledge.KgProperties;
import com.resbench.knowledge.Neo4jProperties;
import com.resbench.pojo.dto.OrchestrationCheckRequest;
import com.resbench.pojo.dto.OrchestrationCheckResult;

/**
 * 故障编排合理性校验：生成 startByForm 同款的两份 XML，调用 kg_orchestration.py 判定。
 *
 * <p>脚本只读 XML 与 Neo4j，不注入故障、不启动压测。图谱不可用或脚本失败时返回
 * {@code checked=false}，由前端按「无法判定」展示，不影响引擎其他功能。
 */
@Service
public class KgOrchestrationCheckService {

  private static final Logger LOG = LoggerFactory.getLogger(KgOrchestrationCheckService.class);

  private static final String STATUS_PASS = "PASS_CHECKED_CONSTRAINTS";
  private static final String STATUS_VIOLATION = "VIOLATION";
  private static final String STATUS_INVALID = "INVALID_INPUT";
  private static final String STATUS_INDETERMINATE = "INDETERMINATE";
  private static final String STATUS_NO_RULES = "NO_APPLICABLE_RULES";

  private final StartByFormService startByFormService;
  private final Neo4jProperties neo4jProperties;
  private final KgProperties kgProperties;
  private final ObjectMapper objectMapper;

  public KgOrchestrationCheckService(StartByFormService startByFormService,
      Neo4jProperties neo4jProperties, KgProperties kgProperties, ObjectMapper objectMapper) {
    this.startByFormService = startByFormService;
    this.neo4jProperties = neo4jProperties;
    this.kgProperties = kgProperties;
    this.objectMapper = objectMapper;
  }

  public OrchestrationCheckResult check(OrchestrationCheckRequest request) {
    if (request == null || request.getFaults() == null || request.getFaults().isEmpty()) {
      return OrchestrationCheckResult.pass("neo4j", "没有配置故障，无需校验。");
    }

    StartByFormService.PreparedCheck prepared = null;
    try {
      prepared = startByFormService.prepareForCheck(request);
      return runCheck(prepared);
    } catch (IllegalArgumentException e) {
      return OrchestrationCheckResult.error("config", "编排校验失败：" + e.getMessage());
    } catch (Exception e) {
      LOG.error("orchestration check failed", e);
      return OrchestrationCheckResult.error("neo4j", "编排校验失败：" + rootMessage(e));
    } finally {
      if (prepared != null) {
        if (kgProperties.isKeepArtifacts()) {
          LOG.info("编排校验临时产物保留在：{}", prepared.directory().toAbsolutePath());
        } else {
          startByFormService.cleanup(prepared);
        }
      }
    }
  }

  private OrchestrationCheckResult runCheck(StartByFormService.PreparedCheck prepared)
      throws IOException, InterruptedException {
    Path script = resolveScript();
    if (script == null) {
      return OrchestrationCheckResult.error("python",
          "找不到 kg_orchestration.py，请确认 Engine 发行目录中包含该脚本。");
    }

    String python = resolvePython();
    String password = resolvePassword();
    if (StringUtils.isBlank(password)) {
      return OrchestrationCheckResult.error("neo4j",
          "Neo4j 密码未配置：请设置 NEO4J_PASSWORD，或确认 " + passwordCandidates() + " 存在。");
    }

    Path reportPath = prepared.directory().resolve("report.json");
    Path logPath = prepared.directory().resolve("check.log");

    List<String> command = new ArrayList<>();
    command.add(python);
    command.add(script.toAbsolutePath().toString());
    command.add("--plan");
    command.add(prepared.planPath().toAbsolutePath().normalize().toString());
    command.add("--config");
    command.add(prepared.configPath().toAbsolutePath().normalize().toString());
    command.add("--neo4j-uri");
    command.add(StringUtils.defaultIfBlank(neo4jProperties.getUri(), "bolt://127.0.0.1:7687"));
    command.add("--neo4j-user");
    command.add(StringUtils.defaultIfBlank(neo4jProperties.getUsername(), "neo4j"));
    command.add("--neo4j-database");
    command.add(StringUtils.defaultIfBlank(neo4jProperties.getDatabase(), "neo4j"));
    command.add("--graph-version");
    command.add(StringUtils.defaultIfBlank(kgProperties.getGraphVersion(), "resbench-kg-v1"));
    command.add("--relation-mode");
    command.add(StringUtils.defaultIfBlank(kgProperties.getRelationMode(), "strict"));
    command.add("--json-out");
    command.add(reportPath.toAbsolutePath().toString());

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(script.toAbsolutePath().getParent().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(logPath.toFile());
    builder.environment().put("NEO4J_PASSWORD", password);
    builder.environment().put("PYTHONUNBUFFERED", "1");
    builder.environment().put("PYTHONIOENCODING", "utf-8");

    LOG.info("Running orchestration check: {}", String.join(" ", command));
    Process process = builder.start();
    long timeout = Math.max(10, kgProperties.getTimeoutSeconds());
    if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      return OrchestrationCheckResult.error("neo4j",
          "编排校验超时（" + timeout + "s）。请确认 Neo4j 已启动且图谱已导入。");
    }

    String output = readLog(logPath);
    if (!Files.isRegularFile(reportPath)) {
      LOG.warn("orchestration check produced no report, exit={}, output={}",
          process.exitValue(), output);
      return OrchestrationCheckResult.error("neo4j",
          "编排校验脚本未生成报告（exit=" + process.exitValue() + "）：" + abbreviate(output));
    }

    JsonNode report;
    try {
      report = objectMapper.readTree(reportPath.toFile());
    } catch (IOException e) {
      return OrchestrationCheckResult.error("neo4j",
          "编排校验报告解析失败：" + e.getMessage());
    }
    return toResult(report, output);
  }

  private OrchestrationCheckResult toResult(JsonNode report, String output) {
    String status = report.path("status").asText("");
    String mode = "neo4j";
    JsonNode summary = report.path("summary");
    int errors = summary.path("error_count").asInt(0);
    int warnings = summary.path("warning_count").asInt(0);
    int checked = summary.path("checked_relation_count").asInt(0);

    List<OrchestrationCheckResult.Violation> violations = collectErrors(report.path("diagnostics"));

    switch (status) {
      case STATUS_PASS: {
        String message = "编排校验通过：检查了 " + checked + " 条规则，无冲突。";
        if (warnings > 0) {
          message += "另有 " + warnings + " 条提示（不影响结论）。";
        }
        return OrchestrationCheckResult.pass(mode, message);
      }
      case STATUS_VIOLATION:
        return OrchestrationCheckResult.fail(mode,
            "编排不合理：发现 " + Math.max(errors, violations.size()) + " 处冲突。", violations);
      case STATUS_INVALID:
        return OrchestrationCheckResult.error(mode,
            "编排无法通过输入校验：" + firstErrorMessage(violations, output));
      case STATUS_INDETERMINATE:
        return OrchestrationCheckResult.error(mode,
            "无法判定：部分规则缺少可判定的计划条件。");
      case STATUS_NO_RULES:
        return OrchestrationCheckResult.error(mode,
            "未找到适用的图谱规则，无法判定该编排是否合理。");
      default:
        return OrchestrationCheckResult.error(mode,
            "编排校验返回未知状态：" + status + "。" + abbreviate(output));
    }
  }

  private static List<OrchestrationCheckResult.Violation> collectErrors(JsonNode diagnostics) {
    List<OrchestrationCheckResult.Violation> violations = new ArrayList<>();
    if (!diagnostics.isArray()) {
      return violations;
    }
    for (JsonNode diagnostic : diagnostics) {
      if (!"ERROR".equalsIgnoreCase(diagnostic.path("severity").asText())) {
        continue;
      }
      OrchestrationCheckResult.Violation violation = new OrchestrationCheckResult.Violation();
      violation.setType(diagnostic.path("code").asText("VIOLATION"));
      violation.setMessage(diagnostic.path("message").asText(""));

      List<String> refs = new ArrayList<>();
      for (JsonNode ref : diagnostic.path("case_ids")) {
        refs.add(ref.asText());
      }
      if (refs.isEmpty() && diagnostic.hasNonNull("relation_id")) {
        refs.add(diagnostic.path("relation_id").asText());
      }
      violation.setNameKeys(refs);
      violations.add(violation);
    }
    return violations;
  }

  private static String firstErrorMessage(List<OrchestrationCheckResult.Violation> violations,
      String output) {
    if (!violations.isEmpty() && StringUtils.isNotBlank(violations.get(0).getMessage())) {
      return violations.get(0).getMessage();
    }
    return abbreviate(output);
  }

  private Path resolveScript() {
    String userDir = System.getProperty("user.dir", ".");
    List<Path> candidates = new ArrayList<>();
    String configured = kgProperties.getScript();
    if (StringUtils.isNotBlank(configured)) {
      Path path = Path.of(configured.trim());
      if (path.isAbsolute()) {
        return Files.isRegularFile(path) ? path : null;
      }
      candidates.add(path);
    }
    candidates.add(Path.of(userDir, "kg_orchestration.py"));
    candidates.add(Path.of(userDir, "resbenchmark", "kg_orchestration.py"));
    candidates.add(Path.of(userDir, "..", "kg_orchestration.py"));
    candidates.add(Path.of(userDir, "..", "resbenchmark", "kg_orchestration.py"));
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private String resolvePython() {
    if (StringUtils.isNotBlank(kgProperties.getPython())) {
      return kgProperties.getPython().trim();
    }
    String configured = System.getProperty("resbench.python");
    if (StringUtils.isBlank(configured)) {
      configured = System.getenv("RESBENCH_PYTHON");
    }
    if (StringUtils.isNotBlank(configured)) {
      return configured.trim();
    }
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win") ? "python" : "python3";
  }

  private String resolvePassword() {
    if (StringUtils.isNotBlank(neo4jProperties.getPassword())) {
      return neo4jProperties.getPassword();
    }
    String env = System.getenv("NEO4J_PASSWORD");
    if (StringUtils.isNotBlank(env)) {
      return env.trim();
    }
    for (Path candidate : passwordPaths()) {
      if (!Files.isReadable(candidate)) {
        continue;
      }
      try {
        String value = Files.readString(candidate, StandardCharsets.UTF_8).trim();
        if (StringUtils.isNotBlank(value)) {
          return value;
        }
      } catch (IOException e) {
        LOG.warn("读取 Neo4j 密码文件失败：{}", candidate, e);
      }
    }
    return "";
  }

  private List<Path> passwordPaths() {
    List<Path> paths = new ArrayList<>();
    String configured = kgProperties.getPasswordFile();
    if (StringUtils.isNotBlank(configured)) {
      paths.add(Path.of(configured.trim()));
    }
    String envFile = System.getenv("RESBENCH_NEO4J_PASSWORD_FILE");
    if (StringUtils.isNotBlank(envFile)) {
      paths.add(Path.of(envFile.trim()));
    }
    String userDir = System.getProperty("user.dir", ".");
    paths.add(Path.of(userDir, "neo4j", ".password"));
    paths.add(Path.of(userDir, "..", "neo4j", ".password"));
    paths.add(Path.of(userDir, "..", "..", "neo4j", ".password"));
    return paths;
  }

  private String passwordCandidates() {
    StringBuilder builder = new StringBuilder();
    for (Path path : passwordPaths()) {
      if (builder.length() > 0) {
        builder.append("、");
      }
      builder.append(path.toAbsolutePath().normalize());
    }
    return builder.toString();
  }

  private static String readLog(Path logPath) {
    try {
      if (Files.isRegularFile(logPath)) {
        return Files.readString(logPath, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      LOG.warn("读取编排校验日志失败：{}", logPath, e);
    }
    return "";
  }

  private static String abbreviate(String text) {
    if (StringUtils.isBlank(text)) {
      return "（无输出）";
    }
    String trimmed = text.trim();
    return trimmed.length() <= 2000 ? trimmed : trimmed.substring(0, 2000) + "...";
  }

  private static String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return StringUtils.defaultIfBlank(current.getMessage(), current.getClass().getSimpleName());
  }
}
