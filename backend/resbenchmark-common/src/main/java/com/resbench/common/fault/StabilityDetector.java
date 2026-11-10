package com.resbench.common.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 负载稳定性检测器
 * 通过分析Monitor生成的TPS数据，检测系统负载是否进入稳定状态
 */
public class StabilityDetector {
  private static final Logger LOG = LoggerFactory.getLogger(StabilityDetector.class);

  private final long checkIntervalMs;
  private final long maxWaitTimeMs;
  private final int windowSize;
  private final double stabilityThreshold;
  private final AutoTimingConfig config;

  private final String monitorFilePath;
  private final String metricsFilePath;
  private long checkIndex = 0;
  private double effectiveThreshold;
  private double avgCvFirstBaselineChecks = Double.NaN;
  private double avgCvBeforeFirstInjection = Double.NaN;
  private boolean firstInjectionThresholdSnapshotTaken = false;
  private double lastObservedCv = Double.NaN;
  private boolean dynamicThresholdReady = false;
  private int lastLoggedBaselineChecks = -1;

  public StabilityDetector(String monitorFilePath, String metricsFilePath, AutoTimingConfig config) {
    this.monitorFilePath = monitorFilePath;
    this.metricsFilePath = metricsFilePath;
    this.config = config;
    this.checkIntervalMs = config.getCheckIntervalMs();
    this.maxWaitTimeMs = config.getMaxWaitMs();
    this.windowSize = config.getWindowSize();
    this.stabilityThreshold = config.getCvThreshold();
    this.effectiveThreshold = stabilityThreshold;
    LOG.info("Stability metrics CSV will be written to: {}", metricsFilePath);
  }

  public void markFirstInjectionMoment() {
    if (firstInjectionThresholdSnapshotTaken) {
      return;
    }
    this.avgCvBeforeFirstInjection = Double.isNaN(lastObservedCv) ? this.effectiveThreshold : lastObservedCv;
    this.firstInjectionThresholdSnapshotTaken = true;
    LOG.info("Captured avg CV before first injection: {}", avgCvBeforeFirstInjection);
  }

  /**
   * 等待系统负载进入稳定状态
   *
   * @return true - 检测到稳定状态；false - 超时未检测到稳定
   */
  public boolean waitForStability() {
    long startTime = System.currentTimeMillis();
    LOG.info("Starting stability detection, max wait time: {}s", maxWaitTimeMs / 1000);

    while (System.currentTimeMillis() - startTime < maxWaitTimeMs) {
      StabilitySnapshot snapshot = evaluateStability();
      writeMetrics(snapshot);
      if (snapshot.isStable) {
        LOG.info("Stability detected after {}s", (System.currentTimeMillis() - startTime) / 1000);
        return true;
      }

      try {
        Thread.sleep(checkIntervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.warn("Stability detection interrupted");
        return false;
      }
    }

    LOG.warn("Stability detection timeout after {}s", maxWaitTimeMs / 1000);
    return false;
  }

  /**
   * 检查当前是否处于稳定状态
   */
  private StabilitySnapshot evaluateStability() {
    refreshEffectiveThreshold();
    List<Double> tpsValues = readRecentTpsValues(windowSize);

    if (tpsValues.size() < windowSize) {
      LOG.info("Not enough data for stability check, current: {}/{}", tpsValues.size(), windowSize);
      return StabilitySnapshot.insufficientData(nextCheckIndex(), windowSize, stabilityThreshold, tpsValues);
    }

    double avg = calculateAverage(tpsValues);
    if (avg == 0) {
      return StabilitySnapshot.noSignal(nextCheckIndex(), windowSize, stabilityThreshold, tpsValues);
    }

    double variance = calculateVariance(tpsValues, avg);
    double cv = Math.sqrt(variance) / avg; // 变异系数
    this.lastObservedCv = cv;

    if (config.isDynamicThresholdEnabled() && !dynamicThresholdReady) {
      LOG.info("Dynamic threshold not ready yet, skip stability decision. current CV: {}", cv);
      return StabilitySnapshot.fromMetrics(
          nextCheckIndex(),
          windowSize,
          Double.NaN,
          avg,
          variance,
          cv,
          tpsValues,
          false,
          avgCvFirstBaselineChecks,
          avgCvBeforeFirstInjection);
    }

    LOG.info("TPS average: {}, CV: {}%, threshold: {}", avg, cv * 100, effectiveThreshold);

    return StabilitySnapshot.fromMetrics(
        nextCheckIndex(),
        windowSize,
        effectiveThreshold,
        avg,
        variance,
        cv,
        tpsValues,
        cv < effectiveThreshold,
        avgCvFirstBaselineChecks,
        avgCvBeforeFirstInjection);
  }

  private long nextCheckIndex() {
    checkIndex += 1;
    return checkIndex;
  }

  private void writeMetrics(StabilitySnapshot snapshot) {
    Path metricsPath = Paths.get(metricsFilePath);
    try {
      Path metricsParent = metricsPath.getParent();
      if (metricsParent != null) {
        Files.createDirectories(metricsParent);
      }
      if (Files.notExists(metricsPath)) {
        String header =
            "ts_ms,check_index,stable,window_size,threshold,avg,variance,cv,samples,"
                + "avg_cv_first_baseline_checks,avg_cv_before_first_injection,tps_window\n";
        Files.write(metricsPath, header.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      }
      StringBuilder line = new StringBuilder();
      line.append(snapshot.timestampMs)
          .append(',')
          .append(snapshot.checkIndex)
          .append(',')
          .append(snapshot.isStable)
          .append(',')
          .append(snapshot.windowSize)
          .append(',')
          .append(snapshot.threshold)
          .append(',')
          .append(snapshot.avg)
          .append(',')
          .append(snapshot.variance)
          .append(',')
          .append(snapshot.cv)
          .append(',')
          .append(snapshot.samples)
          .append(',')
          .append(snapshot.avgCvFirstBaselineChecks)
          .append(',')
          .append(snapshot.avgCvBeforeFirstInjection)
          .append(',');

      for (int i = 0; i < snapshot.tpsWindow.size(); i++) {
        if (i > 0) {
          line.append(';');
        }
        line.append(snapshot.tpsWindow.get(i));
      }
      line.append('\n');

      Files.write(metricsPath, line.toString().getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      LOG.error("Failed to write stability metrics: {}", e.getMessage());
    }
  }

  /**
   * 读取最近的TPS数据
   */
  private List<Double> readRecentTpsValues(int count) {
    List<Double> values = new ArrayList<>();

    try (BufferedReader reader = new BufferedReader(new FileReader(monitorFilePath))) {
      String line;
      List<String> lines = new ArrayList<>();

      // 跳过标题行
      reader.readLine();

      // 读取所有行
      while ((line = reader.readLine()) != null) {
        if (!line.trim().isEmpty()) {
          lines.add(line);
        }
      }

      // 取最后count行
      int start = Math.max(0, lines.size() - count);
      for (int i = start; i < lines.size(); i++) {
        String[] parts = lines.get(i).split(",");
        if (parts.length > 1) {
          try {
            double tps = Double.parseDouble(parts[1]);
            values.add(tps);
          } catch (NumberFormatException e) {
            LOG.info("Failed to parse TPS value: {}", parts[1]);
          }
        }
      }
    } catch (IOException e) {
      LOG.error("Failed to read monitor file: {}", e.getMessage());
    }

    return values;
  }

  private double calculateAverage(List<Double> values) {
    if (values.isEmpty()) {
      return 0;
    }
    double sum = 0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.size();
  }

  private double calculateVariance(List<Double> values, double avg) {
    if (values.size() < 2) {
      return 0;
    }
    double sum = 0;
    for (double v : values) {
      sum += Math.pow(v - avg, 2);
    }
    return sum / values.size();
  }

  private void refreshEffectiveThreshold() {
    if (!config.isDynamicThresholdEnabled()) {
      effectiveThreshold = stabilityThreshold;
      dynamicThresholdReady = true;
      return;
    }

    if (dynamicThresholdReady) {
      return;
    }

    List<Double> cvList = readFirstWindowCvValues(config.getDynamicBaselineChecks());
    int baselineChecks = config.getDynamicBaselineChecks();
    if (cvList.size() < baselineChecks) {
      dynamicThresholdReady = false;
      if (lastLoggedBaselineChecks != cvList.size()) {
        lastLoggedBaselineChecks = cvList.size();
        LOG.info(
            "Dynamic threshold waiting: baseline checks {}/{} not ready yet",
            cvList.size(),
            baselineChecks);
      }
      return;
    }

    double sum = 0;
    for (int i = 0; i < baselineChecks; i++) {
      sum += cvList.get(i);
    }
    avgCvFirstBaselineChecks = sum / baselineChecks;
    double rawThreshold = avgCvFirstBaselineChecks * config.getDynamicThresholdScaleK();
    effectiveThreshold = clamp(
        rawThreshold,
        config.getDynamicThresholdLowerBound(),
        config.getDynamicThresholdUpperBound());
    dynamicThresholdReady = true;
    LOG.info(
        "Dynamic threshold enabled, avg first {} checks CV: {}, raw: {}, effective: {}",
        baselineChecks,
        avgCvFirstBaselineChecks,
        rawThreshold,
        effectiveThreshold);
  }

  private List<Double> readFirstWindowCvValues(int sampleCount) {
    List<Double> tpsSeries = readAllTpsValues();
    List<Double> cvValues = new ArrayList<>();
    if (tpsSeries.size() < windowSize) {
      return cvValues;
    }
    for (int end = windowSize; end <= tpsSeries.size() && cvValues.size() < sampleCount; end++) {
      List<Double> window = tpsSeries.subList(end - windowSize, end);
      double avg = calculateAverage(window);
      if (avg == 0) {
        continue;
      }
      double variance = calculateVariance(window, avg);
      cvValues.add(Math.sqrt(variance) / avg);
    }
    return cvValues;
  }

  private List<Double> readAllTpsValues() {
    List<Double> values = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(monitorFilePath))) {
      String line;
      reader.readLine();
      while ((line = reader.readLine()) != null) {
        if (line.trim().isEmpty()) {
          continue;
        }
        String[] parts = line.split(",");
        if (parts.length > 1) {
          try {
            values.add(Double.parseDouble(parts[1]));
          } catch (NumberFormatException e) {
            LOG.info("Failed to parse TPS value: {}", parts[1]);
          }
        }
      }
    } catch (IOException e) {
      LOG.error("Failed to read monitor file: {}", e.getMessage());
    }
    return values;
  }

  private static double clamp(double value, double lowerBound, double upperBound) {
    if (value < lowerBound) {
      return lowerBound;
    }
    if (value > upperBound) {
      return upperBound;
    }
    return value;
  }

  private static final class StabilitySnapshot {
    private final long timestampMs;
    private final long checkIndex;
    private final boolean isStable;
    private final int windowSize;
    private final double threshold;
    private final double avg;
    private final double variance;
    private final double cv;
    private final int samples;
    private final double avgCvFirstBaselineChecks;
    private final double avgCvBeforeFirstInjection;
    private final List<Double> tpsWindow;

    private StabilitySnapshot(
        long timestampMs,
        long checkIndex,
        boolean isStable,
        int windowSize,
        double threshold,
        double avg,
        double variance,
        double cv,
        int samples,
        double avgCvFirstBaselineChecks,
        double avgCvBeforeFirstInjection,
        List<Double> tpsWindow) {
      this.timestampMs = timestampMs;
      this.checkIndex = checkIndex;
      this.isStable = isStable;
      this.windowSize = windowSize;
      this.threshold = threshold;
      this.avg = avg;
      this.variance = variance;
      this.cv = cv;
      this.samples = samples;
      this.avgCvFirstBaselineChecks = avgCvFirstBaselineChecks;
      this.avgCvBeforeFirstInjection = avgCvBeforeFirstInjection;
      this.tpsWindow = tpsWindow;
    }

    private static StabilitySnapshot fromMetrics(
        long checkIndex,
        int windowSize,
        double threshold,
        double avg,
        double variance,
        double cv,
        List<Double> tpsWindow,
        boolean isStable,
        double avgCvFirstBaselineChecks,
        double avgCvBeforeFirstInjection) {
      return new StabilitySnapshot(
          System.currentTimeMillis(),
          checkIndex,
          isStable,
          windowSize,
          threshold,
          avg,
          variance,
          cv,
          tpsWindow.size(),
          avgCvFirstBaselineChecks,
          avgCvBeforeFirstInjection,
          new ArrayList<>(tpsWindow));
    }

    private static StabilitySnapshot insufficientData(
        long checkIndex,
        int windowSize,
        double threshold,
        List<Double> tpsWindow) {
      return new StabilitySnapshot(
          System.currentTimeMillis(),
          checkIndex,
          false,
          windowSize,
          threshold,
          0,
          0,
          Double.NaN,
          tpsWindow.size(),
          Double.NaN,
          Double.NaN,
          new ArrayList<>(tpsWindow));
    }

    private static StabilitySnapshot noSignal(
        long checkIndex,
        int windowSize,
        double threshold,
        List<Double> tpsWindow) {
      return new StabilitySnapshot(
          System.currentTimeMillis(),
          checkIndex,
          false,
          windowSize,
          threshold,
          0,
          0,
          Double.NaN,
          tpsWindow.size(),
          Double.NaN,
          Double.NaN,
          new ArrayList<>(tpsWindow));
    }
  }
}
