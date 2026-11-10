/**
 * Build real metric explanation data from monitor CSV rows + backend scores.
 * Every builder always returns all keys the MetricExplainModal rendering functions expect.
 */
export function buildMetricExplanation(metricKey, { monitorData = [], scores = [] } = {}) {
  const tpsSeries = (monitorData || [])
    .filter((d) => Number.isFinite(d?.throughput))
    .map((d) => d.throughput);

  const latencySeries = (monitorData || [])
    .filter((d) => Number.isFinite(d?.avgLatencyMs))
    .map((d) => d.avgLatencyMs);

  const scoreMap = {};
  if (Array.isArray(scores)) {
    scores.forEach((s) => {
      scoreMap[s.metricKey] = s.value;
    });
  }

  const realScore = scoreMap[metricKey] ?? 0;

  switch (metricKey) {
    case 'throughput':
      return buildThroughput(tpsSeries, realScore);
    case 'latency':
      return buildLatency(latencySeries, realScore);
    case 'stability':
      return buildStability(tpsSeries, realScore);
    case 'resistance':
      return buildResistanceRecovery(tpsSeries, realScore, false);
    case 'recovery':
      return buildResistanceRecovery(tpsSeries, realScore, true);
    case 'adaptability':
      return buildAdaptability(scoreMap, realScore);
    case 'deviation':
      return buildDeviation(scoreMap, realScore);
    case 'disturbance':
      return buildDisturbance(scoreMap, realScore);
    default:
      return buildFallback(metricKey, realScore);
  }
}

// ---- helpers ----

function safeAvg(arr, fallback = 0) {
  if (!arr || arr.length === 0) return fallback;
  const s = arr.reduce((a, b) => a + b, 0);
  const r = s / arr.length;
  return Number.isFinite(r) ? r : fallback;
}

function safeMin(arr, fallback = 0) {
  if (!arr || arr.length === 0) return fallback;
  const m = Math.min(...arr);
  return Number.isFinite(m) ? m : fallback;
}

function safeMax(arr, fallback = 0) {
  if (!arr || arr.length === 0) return fallback;
  const m = Math.max(...arr);
  return Number.isFinite(m) ? m : fallback;
}

function findDropPoint(series) {
  if (!series || series.length < 3) return 1;
  const avg = safeAvg(series, 1);
  if (avg <= 0) return 1;
  const threshold = avg * 0.7;
  for (let i = 1; i < series.length; i++) {
    if (series[i] < threshold) return i;
  }
  return Math.max(1, Math.floor(series.length / 3));
}

function findRecoveryPoint(series, dropIdx) {
  if (!series || dropIdx >= series.length) return Math.max(0, (series || []).length - 1);
  const preSlice = series.slice(0, dropIdx);
  const preAvg = safeAvg(preSlice, 1);
  if (preAvg <= 0) return Math.min(dropIdx + 1, series.length - 1);
  const threshold = preAvg * 0.85;
  for (let i = dropIdx + 1; i < series.length; i++) {
    if (series[i] >= threshold) return i;
  }
  return Math.max(dropIdx + 1, series.length - 1);
}

function ensureArray(arr, minLen = 1, fill = 0) {
  if (!Array.isArray(arr) || arr.length === 0) return new Array(minLen).fill(fill);
  return arr;
}

// ---- fallback ----

function buildFallback(metricKey, score) {
  return {
    score,
    values: [],
    chart: { type: 'line', series: { name: metricKey, data: [score || 0] } },
  };
}

// ---- builders ----

function buildThroughput(tpsSeries, score) {
  const series = ensureArray(tpsSeries, 1, 0);
  const baseline = safeAvg(series.slice(0, Math.max(1, Math.floor(series.length * 0.2))), 1);
  const actualTotal = series.reduce((a, b) => a + b, 0);
  const duration = Math.max(1, series.length);

  return {
    score,
    values: [
      { key: 'baseline', value: baseline },
      { key: 'actualTotal', value: actualTotal },
      { key: 'duration', value: duration },
    ],
    chart: {
      type: 'area',
      series: { name: 'TPS', data: series },
    },
  };
}

function buildLatency(latencySeries, score) {
  const series = ensureArray(latencySeries, 1, 0);
  const baseline = safeAvg(series.slice(0, Math.max(1, Math.floor(series.length * 0.2))), 1);
  const actual = safeAvg(series, 1);

  return {
    score,
    values: [
      { key: 'expected', value: baseline },
      { key: 'actual', value: actual },
      { key: 'baseline', value: baseline },
    ],
    chart: {
      type: 'area',
      series: { name: 'Latency(ms)', data: series },
    },
  };
}

function buildStability(tpsSeries, score) {
  const series = ensureArray(tpsSeries, 2, 1);
  const mx = Math.max(safeMax(series, 1), 1);
  const norm = series.map((v) => v / mx);

  const half = Math.max(1, Math.floor(norm.length / 2));
  const firstHalf = norm.slice(0, half);
  const secondHalf = norm.slice(half);

  const variance = (arr) => {
    if (arr.length === 0) return 0;
    const m = safeAvg(arr, 0);
    return arr.reduce((s, v) => s + (v - m) ** 2, 0) / arr.length;
  };

  return {
    score,
    values: [
      { key: 'var1', value: variance(firstHalf) },
      { key: 'var2', value: variance(secondHalf) },
      { key: 'alpha', value: 1 },
    ],
    chart: {
      type: 'line',
      series: { name: 'P_norm', data: norm },
    },
  };
}

function buildResistanceRecovery(tpsSeries, score, isRecovery) {
  const series = ensureArray(tpsSeries, 3, 0);

  const dropIdx = findDropPoint(series);
  const recoveryIdx = findRecoveryPoint(series, dropIdx);

  const baselineTps = safeAvg(series.slice(0, dropIdx), 1);
  const minTps = Math.min(safeMin(series.slice(dropIdx), baselineTps), baselineTps);
  const actualTotal = series.reduce((a, b) => a + b, 0);
  const recoveryDuration = Math.max(1, recoveryIdx - dropIdx);

  const targetIdx = isRecovery
    ? Math.min(recoveryIdx, series.length - 1)
    : Math.max(0, dropIdx - 1);
  const targetVal = series[targetIdx] ?? baselineTps;
  const safeTargetPoint = [targetIdx, Number.isFinite(targetVal) ? targetVal : baselineTps];
  const safeMinPoint = [dropIdx, Number.isFinite(minTps) ? minTps : 0];

  const baselineData = Array.from({ length: series.length }, () => baselineTps);

  return {
    score,
    values: [
      { key: 'w1_time_recovery', value: 0.3 },
      { key: 'w2_depth_retention', value: 0.4 },
      { key: 'w3_total_loss', value: 0.3 },
      { key: 'baseline_tps', value: baselineTps },
      { key: 'min_tps', value: minTps },
      { key: 'actual_total_tps', value: actualTotal },
      { key: 'recovery_duration', value: recoveryDuration },
      { key: isRecovery ? 'recovery_point' : 'baselinepoint', value: safeTargetPoint },
      { key: 'minpoint', value: safeMinPoint },
      { key: 'chart_segment', value: { startIndex: dropIdx, endIndex: recoveryIdx, color: '#E34E59', label: `${recoveryDuration}s` } },
    ],
    chart: {
      type: 'area',
      series: [
        { name: 'Baseline', data: baselineData },
        { name: 'Actual', data: series },
      ],
    },
  };
}

function buildAdaptability(scoreMap, score) {
  const dims = ['throughput', 'stability', 'latency', 'resistance', 'recovery'];
  const averages = dims.map((d) => scoreMap[d] ?? 0);
  const deviations = dims.map((d) => {
    const v = scoreMap[d] ?? 0;
    return v > 0 ? Math.max(0, 100 - v) : 0;
  });
  const chartData = dims.map((d) => scoreMap[d] ?? 0);

  return {
    score,
    values: [
      { key: 'averages', value: averages },
      { key: 'deviations', value: deviations },
      { key: 'tps_w', value: 0.2 },
      { key: 'stability_w', value: 0.2 },
      { key: 'latency_w', value: 0.2 },
      { key: 'resistance_w', value: 0.2 },
      { key: 'recovery_w', value: 0.2 },
    ],
    chart: {
      type: 'line',
      series: dims.map((d) => ({
        name: d.charAt(0).toUpperCase() + d.slice(1),
        data: chartData.length > 0 ? chartData : [0],
      })),
    },
  };
}

function buildDeviation(scoreMap, score) {
  const dims = ['throughput', 'stability', 'latency', 'resistance', 'recovery'];
  const compare2firsttime = dims.map((d) => {
    const v = scoreMap[d] ?? 0;
    return v > 0 ? Math.max(0, 100 - v) : 0;
  });
  const chartData = dims.map((d) => scoreMap[d] ?? 0);

  return {
    score,
    values: [
      { key: 'compare2firsttime', value: compare2firsttime },
      { key: 'tps_w', value: 0.2 },
      { key: 'stability_w', value: 0.2 },
      { key: 'latency_w', value: 0.2 },
      { key: 'resistance_w', value: 0.2 },
      { key: 'recovery_w', value: 0.2 },
    ],
    chart: {
      type: 'line',
      series: dims.map((d) => ({
        name: d.charAt(0).toUpperCase() + d.slice(1),
        data: chartData.length > 0 ? chartData : [0],
      })),
    },
  };
}

function buildDisturbance(scoreMap, score) {
  const rtoScore = scoreMap.disturbance ?? score;
  const lambda = 0.1;
  const impliedDuration = rtoScore > 0 ? Math.max(1, -Math.log(Math.max(rtoScore, 1) / 100) / lambda) : 10;
  const durations = [Math.max(1, Math.round(impliedDuration))];

  return {
    score,
    values: [
      { key: 'lambda', value: lambda },
      { key: 'durations', value: durations },
    ],
    chart: {
      type: 'line',
      series: { name: 'Disturbance Duration', data: durations },
    },
  };
}
