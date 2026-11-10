package com.resbench.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.constant.PathConstant;
import com.resbench.common.util.FileUtil;
import com.resbench.common.util.JSONUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MetricUtils {

  /**
   * @param historyName 根据测试执行用例的历史名称获取
   * @return 整合数据 or ""
   */
  public static String getHistoryMetric(String historyName) {
    File target = FileUtil.getTagetDir(historyName);
    if (target == null) {
      return "";
    }
    File[] files = target.listFiles();
    if (files == null || files.length == 0) {
      return "";
    }
    String testSuiteName = target.getName().split("_")[0];
    File resultJsonFile = null;
    for (File file : files) {
      if (file.isFile() && file.getName().equals(PathConstant.METRIC_NAME)) {
        resultJsonFile = file;
      }
    }
    if (resultJsonFile == null) {
      log.warn("No metric file found for test suite {}", testSuiteName);
      return "";
    }
    return createMetricResult(resultJsonFile, testSuiteName);
  }

  private static double parseDoubleSafe(Object value) {
    if (value == null) return 0.0;
    try {
      double parsed = Double.parseDouble(String.valueOf(value));
      return Double.isFinite(parsed) ? parsed : 0.0;
    } catch (NumberFormatException e) {
      return 0.0;
    }
  }

  private static String createMetricResult(File resultJsonFile, String testSuiteName) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      String metricJson = FileUtil.readFileAsString(resultJsonFile.getPath())
          .replaceAll("\\b(?:NaN|Infinity|-Infinity)\\b", "0");
      List<Map<String, Object>> mapList =
          mapper.readValue(metricJson, new TypeReference<>() {});
      if (mapList.isEmpty()) {
        return "";
      }
      Map<String, Object> result = new HashMap<>();
      double data_loss = 0.0;
      double outage_secs = 0.0;
      double recovery_time_factor = 0.0;
      double throughput_rate = 0.0;
      double latency_rate = 0.0;
      double desired_throughput = 0.0;
      double fault_throughput = 0.0;
      double desired_latency = 0.0;
      double fault_latency = 0.0;
      double rpo = 0.0;
      double rto = 0.0;
      double throughput = 0.0;
      double latency = 0.0;
      double stability = 0.0;
      double defence = 0.0;
      double recovery = 0.0;
      double adaptability = 0.0;
      double volatility = 0.0;
      for (Map<String, Object> map : mapList) {
        data_loss += parseDoubleSafe(map.get("data_loss"));
        outage_secs += parseDoubleSafe(map.get("outage_seconds"));
        recovery_time_factor += parseDoubleSafe(map.get("recovery_time_factor"));
        throughput_rate += parseDoubleSafe(map.get("throughput_rate"));
        latency_rate += parseDoubleSafe(map.get("latency_rate"));
        rpo += parseDoubleSafe(map.get("rpo"));
        rto += parseDoubleSafe(map.get("rto"));
        throughput += parseDoubleSafe(map.get("throughput"));
        latency += parseDoubleSafe(map.get("latency"));
        stability += parseDoubleSafe(map.get("stability"));
        defence += parseDoubleSafe(map.get("defence"));
        recovery += parseDoubleSafe(map.get("recovery"));
        adaptability += parseDoubleSafe(map.get("adaptability"));
        volatility += parseDoubleSafe(map.get("volatility"));
      }
      data_loss /= mapList.size();
      outage_secs /= mapList.size();
      recovery_time_factor /= mapList.size();
      throughput_rate /= mapList.size();
      latency_rate /= mapList.size();
//      desired_throughput /= mapList.size();
//      desired_latency /= mapList.size();
//      fault_throughput /= mapList.size();
//      fault_latency /= mapList.size();
      rpo /= mapList.size();
      rto /= mapList.size();
      throughput /= mapList.size();
      latency /= mapList.size();
      stability /= mapList.size();
      defence /= mapList.size();
      recovery /= mapList.size();
      adaptability /= mapList.size();
      volatility /= mapList.size();
      result.put("test_suite", testSuiteName);
      result.put("data_loss", data_loss);
      result.put("outage_secs", outage_secs);
      result.put("recovery_time_factor", recovery_time_factor);
      result.put("throughput_rate", throughput_rate);
      result.put("latency_rate", latency_rate);
//      result.put("desired_throughput", desired_throughput);
//      result.put("desired_latency", desired_latency);
//      result.put("fault_throughput", fault_throughput);
//      result.put("fault_latency", fault_latency);
      result.put("rpo", rpo);
      result.put("rto", rto);
      result.put("throughput", throughput);
      result.put("latency", latency);
      result.put("stability", stability);
      result.put("defence", defence);
      result.put("recovery", recovery);
      result.put("adaptability", adaptability);
      result.put("volatility", volatility);

      // 收集每个故障的独立评分，供前端选择展示
      List<Map<String, Object>> faultScores = new ArrayList<>();
      for (Map<String, Object> map : mapList) {
        Map<String, Object> fs = new HashMap<>();
        fs.put("faultName", map.get("Fault Name"));
        fs.put("throughput", map.get("throughput"));
        fs.put("latency", map.get("latency"));
        fs.put("stability", map.get("stability"));
        fs.put("defence", map.get("defence"));
        fs.put("recovery", map.get("recovery"));
        fs.put("adaptability", map.get("adaptability"));
        fs.put("volatility", map.get("volatility"));
        fs.put("rto", map.get("rto"));
        faultScores.add(fs);
      }
      if (!faultScores.isEmpty()) {
        result.put("faultScores", JSONUtil.writeValueAsString(faultScores));
        log.info("[SCORE_DEBUG] Exposing {} fault scores for testSuite={}", faultScores.size(), testSuiteName);
      }

      // 收集每个故障的韧性三角数据（由 ResilienceMetricReport.py 计算并写入 metric.json）
      List<Map<String, Object>> faultTriangles = new ArrayList<>();
      for (Map<String, Object> map : mapList) {
        if (map.containsKey("t_start_ms")) {
          Map<String, Object> tri = new HashMap<>();
          tri.put("faultName", map.get("Fault Name"));
          tri.put("tStartMs", map.get("t_start_ms"));
          tri.put("tMinMs", map.get("t_min_ms"));
          tri.put("tEndMs", map.get("t_end_ms"));
          tri.put("tStartTps", map.get("t_start_tps"));
          tri.put("tMinTps", map.get("t_min_tps"));
          tri.put("tEndTps", map.get("t_end_tps"));
          tri.put("baseline", map.get("baseline_tps"));
          // fault-only 三角形（前端展示用，基于实际注入区间计算）
          if (map.containsKey("fault_t_start_ms")) {
            tri.put("faultTStartMs", map.get("fault_t_start_ms"));
            tri.put("faultTMinMs", map.get("fault_t_min_ms"));
            tri.put("faultTEndMs", map.get("fault_t_end_ms"));
            tri.put("faultTStartTps", map.get("fault_t_start_tps"));
            tri.put("faultTMinTps", map.get("fault_t_min_tps"));
            tri.put("faultTEndTps", map.get("fault_t_end_tps"));
            tri.put("faultBaseline", map.get("fault_baseline_tps"));
          }
          faultTriangles.add(tri);
        }
      }
      if (!faultTriangles.isEmpty()) {
        result.put("faultTriangles", JSONUtil.writeValueAsString(faultTriangles));
        log.info("[TRIANGLE_DEBUG] Exposing {} fault triangles for testSuite={}", faultTriangles.size(), testSuiteName);
      } else {
        log.warn("[TRIANGLE_DEBUG] No fault triangles found in metric.json for testSuite={}", testSuiteName);
      }

      return JSONUtil.writeValueAsString(result);
    } catch (IOException e) {
      log.error("create Metric Result err: {} ", e.getMessage());
    }
    return "";
  }

  /**
   * 根据历史名称返回event数据，如果历史名称为空返回最新
   *
   * @param historyName 历史名称， 如果为空 返回最新
   * @return 返回event数据
   */
  public static String getEvent(String historyName) {
    File resultsDir = new File("results");
    if (!resultsDir.exists()) {
      if (!resultsDir.mkdir()) {
        return "";
      }
    }
    File targetDir = FileUtil.getTagetDir(historyName);

    if (targetDir == null) {
      return "";
    }
    File[] files = targetDir.listFiles();
    if (files == null || files.length == 0) {
      return "";
    }

    File resultJsonFile = null;
    for (File file : files) {
      if (file.isFile() && file.getName().equals(PathConstant.METRIC_NAME)) {
        resultJsonFile = file;
      }
    }
    if (resultJsonFile == null) {
      return "";
    }
    return FileUtil.readFileAsString(resultJsonFile.getPath());
  }
}
