package com.resbench.api.collectors.monitoring;

import com.resbench.BenchmarkState;
import com.resbench.WorkloadConfiguration;
import com.resbench.api.BenchmarkModule;
import com.resbench.api.Worker;
import com.resbench.common.config.MonitorInfo;
import com.resbench.common.util.TimeUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Monitor generator that picks the appropriate monitoring implemnetation based on the database
 * type.
 */
public class MonitorGen {
  public static Monitor getMonitor(
      MonitorInfo monitorInfo,
      BenchmarkState testState,
      List<? extends Worker<? extends BenchmarkModule>> workers,
      WorkloadConfiguration conf) {
    String historyName = buildHistoryName(conf);
    conf.setMonitoringHistoryName(historyName);
    switch (monitorInfo.getMonitoringType()) {
      case ADVANCED:
        {
          switch (conf.getDatabaseType()) {
            case SQLSERVER:
              return new SQLServerMonitor(monitorInfo, testState, workers, conf, historyName);
            case POSTGRES:
              return new PostgreSQLMonitor(monitorInfo, testState, workers, conf, historyName);
            default:
              return new Monitor(monitorInfo, testState, workers, historyName);
          }
        }
      default:
        return new Monitor(monitorInfo, testState, workers, historyName);
    }
  }

  private static String buildHistoryName(WorkloadConfiguration conf) {
    if (conf.getMonitoringHistoryName() != null && !conf.getMonitoringHistoryName().isEmpty()) {
      return conf.getMonitoringHistoryName();
    }
    String testSuiteName = "unknown";
    if (!conf.getPhases().isEmpty() && conf.getPhases().get(0).getTestSuite() != null) {
      testSuiteName = conf.getPhases().get(0).getTestSuite().getName();
    }
    String benchType = conf.getBenchmarkName() == null ? "benchmark" : conf.getBenchmarkName();
    String timeString = TimeUtil.getCurrentTimeString();
    return testSuiteName + "_" + benchType + "_" + timeString;
  }
}
