const METRIC_MAPPING = [
  { metric: 'throughput', keys: ['throughput', 'tpsScore'] },
  { metric: 'latency', keys: ['latency', 'latencyScore'] },
  { metric: 'stability', keys: ['stability', 'stabilityScore'] },
  { metric: 'resistance', keys: ['defence', 'resistance', 'resistanceScore'] },
  { metric: 'recovery', keys: ['recovery', 'recoveryScore'] },
  { metric: 'adaptability', keys: ['adaptability', 'adaptabilityScore'] },
  { metric: 'deviation', keys: ['volatility', 'deviation', 'deviationScore'] },
  { metric: 'disturbance', keys: ['rto', 'disturbance', 'disturbanceScore'] },
];

function pickScoreContainer(result) {
  if (!result || typeof result !== 'object') return {};
  if (result.data && typeof result.data === 'object') {
    if (result.data.scores && typeof result.data.scores === 'object') return result.data.scores;
    return result.data;
  }
  if (result.scores && typeof result.scores === 'object') return result.scores;
  return result;
}

export function mapBackendScores(result, translateFn) {
  const dataObj = pickScoreContainer(result);
  if (!dataObj || typeof dataObj !== 'object') return null;
  // 后端没有任何评分字段时返回 null，让报告显示“暂无评分数据”，
  // 而不是渲染一张所有顶点都缩在圆心的空雷达图
  const hasAnyScore = METRIC_MAPPING.some(({ keys }) =>
    keys.some((key) => dataObj[key] !== undefined && dataObj[key] !== null && dataObj[key] !== '')
  );
  if (!hasAnyScore) return null;
  return METRIC_MAPPING.map(({ metric, keys }) => {
    const hit = keys.find((key) => dataObj[key] !== undefined && dataObj[key] !== null && dataObj[key] !== '');
    const raw = hit ? Number(dataObj[hit]) : Number.NaN;
    return {
      metricKey: metric,
      name: translateFn ? translateFn(`RadarChart.${metric}`) : metric,
      value: Number.isFinite(raw) ? Math.round(raw * 100) / 100 : 0,
    };
  });
}

export function mapMonitorToTps(monitorData) {
  const pickArray = (payload) => {
    if (Array.isArray(payload)) return payload;
    if (!payload || typeof payload !== 'object') return [];
    if (Array.isArray(payload.data)) return payload.data;
    if (Array.isArray(payload.monitorData)) return payload.monitorData;
    if (payload.data && typeof payload.data === 'object') {
      if (Array.isArray(payload.data.monitorData)) return payload.data.monitorData;
      if (Array.isArray(payload.data.records)) return payload.data.records;
      if (Array.isArray(payload.data.list)) return payload.data.list;
    }
    if (Array.isArray(payload.records)) return payload.records;
    if (Array.isArray(payload.list)) return payload.list;
    return [];
  };

  const dataArr = pickArray(monitorData);
  if (dataArr.length === 0) return [];

  const normalizeTime = (row, fallbackIndex) => {
    const raw = row?.timestamp ?? row?.time ?? row?.ts ?? fallbackIndex;
    const num = Number(raw);
    return Number.isFinite(num) ? num : fallbackIndex;
  };

  const normalizeTps = (row) => {
    const raw = row?.tps ?? row?.throughput ?? row?.value ?? row?.tpmC;
    const num = Number(raw);
    return Number.isFinite(num) ? num : Number.NaN;
  };

  const firstTime = normalizeTime(dataArr[0], 0);
  const base = firstTime;
  const useEpoch = Math.abs(base) > 1e10;

  return dataArr
    .map((d, index) => {
      const current = normalizeTime(d, index);
      const tps = normalizeTps(d);
      return {
        time: useEpoch ? (current - base) / 1000 : current,
        throughput: Math.max(0, tps),
      };
    })
    .filter((d) => Number.isFinite(d.time) && Number.isFinite(d.throughput))
    .sort((a, b) => a.time - b.time);
}
