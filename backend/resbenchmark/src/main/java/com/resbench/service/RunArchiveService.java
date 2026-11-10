package com.resbench.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.constant.PathConstant;
import com.resbench.pojo.dto.StartByFormRequest;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 运行归档：把一轮测试的输入快照写成 results/&lt;historyName&gt;/run-meta.json。
 *
 * <p>用途：历史测试的可检索元信息（数据库配置参数、负载配置、故障编排、标签等）。
 * 原始数据（monitor.csv / metric.json / faultInject.json / summary.json / raw.csv …）仍由
 * DBWorkload 收尾阶段原地归档在 results/&lt;historyName&gt;/ 下，本服务不搬运、不复制，
 * 只负责补一份“这一轮到底跑了什么”的结构化记录，供 /web/bench/history/query 检索。
 *
 * <p>注意：连接信息（ip / port / username / password）不会写入归档，只有 dbType、
 * deploymentType、用户声明的数据库配置参数（shared_buffers、max_connections 等），
 * 以及配了 Docker 容器注入时的容器名（containerName）。
 */
@Service
public class RunArchiveService {
  private static final Logger LOG = LoggerFactory.getLogger(RunArchiveService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  public static final String RUN_META_NAME = "run-meta.json";

  private final DbResourceCollector resourceCollector;

  public RunArchiveService(DbResourceCollector resourceCollector) {
    this.resourceCollector = resourceCollector;
  }

  public void archiveRunMeta(StartByFormRequest request, String historyName, String runId) {
    if (isBlank(historyName)) {
      return;
    }
    try {
      Path dir = Path.of(PathConstant.RESULTS_PATH, historyName);
      Files.createDirectories(dir);

      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("historyName", historyName);
      meta.put("runId", isBlank(runId) ? historyName : runId);
      meta.put("testSuite", request.getWorkload() == null ? null : request.getWorkload().getName());
      meta.put("dbKey", request.getDbKey());
      meta.put("orchestrationId", request.getOrchestrationId());
      meta.put("source", isBlank(request.getSource()) ? "ui" : request.getSource());
      meta.put("labels", request.getLabels() == null ? new ArrayList<String>() : request.getLabels());
      meta.put("note", request.getNote());
      meta.put("autoTimingMode", isBlank(request.getAutoTimingMode()) ? "fixed-time" : request.getAutoTimingMode());
      meta.put("createdAt", System.currentTimeMillis());

      Map<String, Object> workload = new LinkedHashMap<>();
      if (request.getWorkload() != null) {
        workload.put("name", request.getWorkload().getName());
        workload.put("params", request.getWorkload().getParams());
      }
      meta.put("workload", workload);

      Map<String, Object> database = new LinkedHashMap<>();
      database.put("dbType", request.getDbType());
      if (request.getDatabase() != null) {
        database.put("deploymentType", request.getDatabase().getDeploymentType());
        database.put("params", request.getDatabase().getParams());
      }
      // 库跑在 Docker 容器里时记下容器名 —— 事后要能分辨这一轮的故障是打进容器还是打进宿主机。
      // 没配容器就不写这个键：存量归档的 run-meta.json 保持逐字节不变。
      if (!isBlank(request.getContainerName())) {
        database.put("containerName", request.getContainerName());
      }
      // Docker / 资源快照：只读采集（nproc、/proc/meminfo、docker inspect），失败不影响测试。
      database.put("dockerized", !isBlank(request.getContainerName()));
      database.put("resources", resourceCollector.collect(request));
      // 刻意不写 ip / port / username / password：连接信息不入归档
      database.put("connectionArchived", false);
      meta.put("database", database);

      List<Map<String, Object>> faults = new ArrayList<>();
      if (request.getFaults() != null) {
        for (StartByFormRequest.FaultItem fault : request.getFaults()) {
          if (fault == null) {
            continue;
          }
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("nameKey", fault.getNameKey());
          item.put("groupKey", fault.getGroupKey());
          item.put("delaySeconds", fault.getDelaySeconds());
          item.put("planningSeconds", fault.getPlanningSeconds());
          item.put("params", fault.getParams());
          faults.add(item);
        }
      }
      meta.put("faults", faults);

      Map<String, Object> runOptions = new LinkedHashMap<>();
      if (request.getRunOptions() != null) {
        runOptions.put("monitorIntervalSec", request.getRunOptions().getMonitorIntervalSec());
        runOptions.put("create", request.getRunOptions().getCreate());
        runOptions.put("load", request.getRunOptions().getLoad());
        runOptions.put("execute", request.getRunOptions().getExecute());
        runOptions.put("reload", request.getRunOptions().getReload());
      }
      meta.put("runOptions", runOptions);

      Files.writeString(
          dir.resolve(RUN_META_NAME),
          MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta),
          StandardCharsets.UTF_8);
      LOG.info("Archived run meta for {} at {}", historyName, dir.resolve(RUN_META_NAME));
    } catch (Exception e) {
      // 归档失败不能影响测试本身
      LOG.warn("Failed to archive run meta for {}: {}", historyName, e.getMessage());
    }
  }

  /**
   * 给一轮历史运行打标签/写备注（归档动作）。旧运行没有 run-meta.json 时自动补建一份最小元信息。
   */
  public Map<String, Object> updateLabels(String historyName, List<String> labels, String note) throws IOException {
    Path dir = requireRunDir(historyName);
    Path metaPath = dir.resolve(RUN_META_NAME);
    Map<String, Object> meta = new LinkedHashMap<>();
    if (Files.isRegularFile(metaPath)) {
      try {
        meta = MAPPER.readValue(Files.readString(metaPath, StandardCharsets.UTF_8),
            new TypeReference<LinkedHashMap<String, Object>>() {});
      } catch (Exception e) {
        LOG.warn("run-meta.json 解析失败，将重建：{} ({})", historyName, e.getMessage());
      }
    }
    meta.putIfAbsent("historyName", historyName);
    meta.putIfAbsent("runId", historyName);
    meta.putIfAbsent("testSuite", historyName.split("_")[0]);
    meta.putIfAbsent("source", "ui");
    meta.putIfAbsent("createdAt", System.currentTimeMillis());
    meta.put("labels", labels == null ? new ArrayList<String>() : labels);
    meta.put("note", note);
    meta.put("updatedAt", System.currentTimeMillis());

    Files.writeString(metaPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta),
        StandardCharsets.UTF_8);
    LOG.info("Updated archive labels for {}: {}", historyName, labels);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("historyName", historyName);
    result.put("labels", labels == null ? new ArrayList<String>() : labels);
    result.put("note", note);
    return result;
  }

  /**
   * 把一轮运行的归档产物打成 zip（默认排除 raw.csv：单轮可达 200 MB+，其余产物合计通常只有几百 KB）。
   *
   * @param includeParam 逗号分隔的类别：summary,monitor,metric,fault,config,charts,detail,other,raw,all；
   *                     留空 = 除 raw 外全部（detail/other 也在内）
   * @return 生成的 zip 路径（在 tmp/archive 下，同名覆盖）
   */
  public Path exportZip(String historyName, String includeParam) throws IOException {
    Path dir = requireRunDir(historyName);
    Set<String> include = parseInclude(includeParam);

    Path outDir = Path.of(PathConstant.getTmpPath(), "archive");
    Files.createDirectories(outDir);
    Path zipPath = outDir.resolve(historyName + ".zip");

    try (ZipOutputStream zip = new ZipOutputStream(
        new BufferedOutputStream(Files.newOutputStream(zipPath, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
      // 元信息永远打包，便于拿到归档后仍能追溯这一轮跑了什么
      addToZip(zip, dir, dir.resolve(RUN_META_NAME));
      addToZip(zip, dir, dir.resolve(PathConstant.getJobStateName()));
      try (Stream<Path> walk = Files.walk(dir)) {
        List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
        for (Path file : files) {
          String name = file.getFileName().toString();
          if (RUN_META_NAME.equals(name) || PathConstant.getJobStateName().equals(name)) {
            continue;
          }
          if (categoryOf(name) != null && include.contains(categoryOf(name))) {
            addToZip(zip, dir, file);
          }
        }
      }
    }
    LOG.info("Exported archive zip for {} -> {} ({} bytes, include={})",
        historyName, zipPath, Files.size(zipPath), include);
    return zipPath;
  }

  private static void addToZip(ZipOutputStream zip, Path root, Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    String entryName = root.relativize(file).toString().replace('\\', '/');
    ZipEntry entry = new ZipEntry(entryName);
    entry.setTime(Files.getLastModifiedTime(file).toMillis());
    zip.putNextEntry(entry);
    Files.copy(file, zip);
    zip.closeEntry();
  }

  /** 文件类别；每个文件都能归类，保证 include 的选择是精确的（未识别的归 other，默认包含）。 */
  private static String categoryOf(String name) {
    if (name.endsWith(".raw.csv")) {
      return "raw";
    }
    if (name.endsWith("summary.json")) {
      return "summary";
    }
    if (PathConstant.getMonitorName().equals(name)) {
      return "monitor";
    }
    if (PathConstant.METRIC_NAME.equals(name) || "metric.csv".equals(name) || name.endsWith(".metrics.json")) {
      return "metric";
    }
    if (PathConstant.FAULT_INJECT_NAME.equals(name) || name.endsWith(".faults.csv") || name.endsWith(".params.json")) {
      return "fault";
    }
    if (name.endsWith(".config.xml")) {
      return "config";
    }
    if (name.toLowerCase().endsWith(".png")) {
      return "charts";
    }
    // 明细/文本（samples.csv、results.*.csv、每故障目录下的 result.txt 等）
    if (name.endsWith(".csv") || name.endsWith(".txt") || name.endsWith(".json")
        || name.endsWith(".xlsx") || name.endsWith(".log")) {
      return "detail";
    }
    return "other";
  }

  private static Set<String> parseInclude(String includeParam) {
    Set<String> all = new LinkedHashSet<>(List.of(
        "summary", "monitor", "metric", "fault", "config", "charts", "detail", "other", "raw"));
    if (isBlank(includeParam)) {
      Set<String> defaults = new LinkedHashSet<>(all);
      defaults.remove("raw");
      return defaults;
    }
    Set<String> include = new LinkedHashSet<>();
    for (String part : includeParam.split(",")) {
      String value = part.trim().toLowerCase(Locale.ROOT);
      if (value.isEmpty()) {
        continue;
      }
      if ("all".equals(value)) {
        return all;
      }
      include.add(value);
    }
    return include;
  }

  private static Path requireRunDir(String historyName) {
    if (isBlank(historyName) || historyName.contains("/") || historyName.contains("\\") || historyName.contains("..")) {
      throw new IllegalArgumentException("非法的历史运行名：" + historyName);
    }
    Path dir = Path.of(PathConstant.RESULTS_PATH, historyName);
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("找不到该历史运行：" + historyName);
    }
    return dir;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}