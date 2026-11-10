package com.resbench.common.constant;

import java.nio.file.Paths;

public interface PathConstant {
    String RESULTS_PATH = "results";

    String CONFIG_PATH = "config";

    String MONITOR_NAME = "monitor.csv";

    String TMP_PATH = "tmp";

    String AUTO_TIMING_PATH = "auto-timing";

    String STABILITY_METRIC_NAME = "stability-metrics.csv";

    String AUTO_TIMING_CONFIG_NAME = "auto-timing-config.json";

    String METRIC_NAME = "metric.json";

    String FAULT_INJECT_NAME = "faultInject.json";

    String JOB_STATE_NAME = "job.json";

    static String getJobStateName() {
        return JOB_STATE_NAME;
    }

    static String getMonitorName() {
        return MONITOR_NAME;
    }

    static String getResultsPath() {
        return RESULTS_PATH;
    }

    static String getTmpPath() {
        return TMP_PATH;
    }

    static String getFaultInjectName() {
        return FAULT_INJECT_NAME;
    }

    static String getFaultInjectPath(String historyName) {
        return Paths.get(PathConstant.RESULTS_PATH)
                .resolve(historyName)
                .resolve(PathConstant.FAULT_INJECT_NAME)
                .normalize()
                .toString();
    }

    static String getMetricPath(String historyName) {
        return Paths.get(PathConstant.RESULTS_PATH)
                .resolve(historyName)
                .resolve(PathConstant.FAULT_INJECT_NAME)
                .normalize()
                .toString();
    }

    static String getTmpMetricPath() {
        return Paths.get(PathConstant.TMP_PATH).resolve(PathConstant.METRIC_NAME).normalize().toString();
    }

    static String getTmpFaultInjectPath() {
        return Paths.get(PathConstant.TMP_PATH)
                .resolve(PathConstant.FAULT_INJECT_NAME)
                .normalize()
                .toString();
    }

    static String getHistoryMonitorPath(String historyName) {
        return Paths.get(PathConstant.RESULTS_PATH)
                .resolve(historyName)
                .resolve(PathConstant.MONITOR_NAME)
                .normalize()
                .toString();
    }

    static String getHistoryAutoTimingPath(String historyName) {
        return Paths.get(PathConstant.RESULTS_PATH)
                .resolve(historyName)
                .resolve(PathConstant.AUTO_TIMING_PATH)
                .normalize()
                .toString();
    }

    static String getHistoryStabilityMetricsPath(String historyName) {
        return Paths.get(getHistoryAutoTimingPath(historyName))
                .resolve(PathConstant.STABILITY_METRIC_NAME)
                .normalize()
                .toString();
    }

    static String getHistoryAutoTimingConfigPath(String historyName) {
        return Paths.get(getHistoryAutoTimingPath(historyName))
                .resolve(PathConstant.AUTO_TIMING_CONFIG_NAME)
                .normalize()
                .toString();
    }

    static String getDBTypePath(String DBType) {
        return Paths.get(PathConstant.CONFIG_PATH)
                .resolve(DBType)
                .normalize()
                .toString();
    }

    static String getTmpJobPath() {
        return Paths.get(PathConstant.TMP_PATH).resolve(PathConstant.JOB_STATE_NAME).normalize().toString();
    }
}
