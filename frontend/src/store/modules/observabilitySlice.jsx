import { createSlice } from '@reduxjs/toolkit';

const MAX_POINTS_PER_SERIES = 2000;

function seriesKey(series) {
  const labels = Object.entries(series.labels || {}).sort(([a], [b]) => a.localeCompare(b));
  return `${series.targetId || ''}|${JSON.stringify(labels)}`;
}

function mergePoints(current = [], incoming = []) {
  const byTimestamp = new Map(current.map((point) => [Number(point[0]), point]));
  incoming.forEach((point) => {
    const timestamp = Number(point?.[0]);
    if (Number.isFinite(timestamp)) byTimestamp.set(timestamp, point);
  });
  return Array.from(byTimestamp.values())
    .sort((a, b) => Number(a[0]) - Number(b[0]))
    .slice(-MAX_POINTS_PER_SERIES);
}

const initialState = {
  // 采集开关只存在浏览器 localStorage 里，一旦为 false，压测期间前端根本不会调
  // /monitoring/sessions，manager 端连这次运行都不知道，曲线和归档都是零，而且
  // 事后没有任何痕迹（tpcc_20261025_152634 就是这么丢的）。所以默认开着：
  // 关掉它属于显式选择，不能是「从没开过」的默认状态。
  enabled: true,
  selectedTargets: [],
  selectedMetricIds: [],
  selectedRunId: '',
  capabilities: null,
  catalog: [],
  history: [],
  sessionsByRunId: {},
  seriesByRunId: {},
  // 故障注入窗口不来自监控归档，按 runId 单独缓存（看历史记录/批量跑的结果时用）
  faultRegionsByRunId: {},
  loading: false,
  error: '',
  lastUpdatedAt: 0,
};

const observabilitySlice = createSlice({
  name: 'observability',
  initialState,
  reducers: {
    setMonitoringEnabled: (state, action) => {
      state.enabled = Boolean(action.payload);
    },
    setSelectedTargets: (state, action) => {
      state.selectedTargets = Array.isArray(action.payload) ? action.payload : [];
    },
    setSelectedMetricIds: (state, action) => {
      state.selectedMetricIds = Array.isArray(action.payload) ? action.payload : [];
    },
    setSelectedRunId: (state, action) => {
      state.selectedRunId = action.payload || '';
    },
    setCapabilities: (state, action) => {
      state.capabilities = action.payload || null;
      if (state.selectedTargets.length === 0 && action.payload?.targets?.[0]?.targetId) {
        state.selectedTargets = [action.payload.targets[0].targetId];
      }
    },
    setCatalog: (state, action) => {
      state.catalog = action.payload?.categories || [];
      if (state.selectedMetricIds.length === 0) {
        state.selectedMetricIds = state.catalog
          .flatMap((category) => category.subcategories || [])
          .flatMap((subcategory) => subcategory.metrics || [])
          .filter((metric) => metric.available && metric.defaultSelected && metric.category !== 'container')
          .map((metric) => metric.metricId);
      }
    },
    setMonitoringHistory: (state, action) => {
      state.history = Array.isArray(action.payload) ? action.payload : [];
    },
    setMonitoringSession: (state, action) => {
      const session = action.payload;
      if (session?.runId) {
        state.sessionsByRunId[session.runId] = session;
        if (!state.selectedRunId) state.selectedRunId = session.runId;
      }
    },
    upsertMonitoringSeries: (state, action) => {
      const response = action.payload;
      if (!response?.runId) return;
      const runStore = state.seriesByRunId[response.runId] || {};
      (response.series || []).forEach((incoming) => {
        const metricStore = runStore[incoming.metricId] || [];
        const key = seriesKey(incoming);
        const index = metricStore.findIndex((item) => seriesKey(item) === key);
        if (index >= 0) {
          metricStore[index] = {
            ...metricStore[index],
            ...incoming,
            points: mergePoints(metricStore[index].points, incoming.points),
          };
        } else {
          metricStore.push({ ...incoming, points: mergePoints([], incoming.points) });
        }
        runStore[incoming.metricId] = metricStore;
      });
      state.seriesByRunId[response.runId] = runStore;
      state.lastUpdatedAt = Date.now();
      state.error = response.warnings?.join('；') || '';
    },
    setFaultRegionsForRun: (state, action) => {
      const { runId, regions } = action.payload || {};
      if (!runId) return;
      state.faultRegionsByRunId[runId] = Array.isArray(regions) ? regions : [];
    },
    clearMonitoringRun: (state, action) => {
      const runId = action.payload;
      delete state.sessionsByRunId[runId];
      delete state.seriesByRunId[runId];
      delete state.faultRegionsByRunId[runId];
      state.history = state.history.filter((item) => item.runId !== runId);
      if (state.selectedRunId === runId) {
        state.selectedRunId = state.history[0]?.runId || '';
      }
    },
    setMonitoringLoading: (state, action) => {
      state.loading = Boolean(action.payload);
    },
    setMonitoringError: (state, action) => {
      state.error = action.payload || '';
    },
  },
});

export const {
  setMonitoringEnabled,
  setSelectedTargets,
  setSelectedMetricIds,
  setSelectedRunId,
  setCapabilities,
  setCatalog,
  setMonitoringHistory,
  setMonitoringSession,
  upsertMonitoringSeries,
  setFaultRegionsForRun,
  clearMonitoringRun,
  setMonitoringLoading,
  setMonitoringError,
} = observabilitySlice.actions;

export default observabilitySlice.reducer;
