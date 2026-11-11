import { useEffect, useMemo, useRef } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useLanguage } from '../../i18n';
import {
  CONTAINER_DEFAULT_METRIC_IDS,
  completeMonitoringSession,
  createMonitoringSession,
  fetchMonitoringCapabilities,
  fetchMonitoringCatalog,
  fetchMonitoringHistory,
  fetchMonitoringHistorySeries,
  fetchMonitoringSeries,
  fetchMonitoringSession,
} from '../../api/monitoringApi';
import {
  setCapabilities,
  setCatalog,
  setMonitoringError,
  setMonitoringHistory,
  setMonitoringSession,
  upsertMonitoringSeries,
} from '../../store/modules/observabilitySlice';

// 曲线打点步长。agent 主机采集器是 1s 桶、Prometheus 也是 1s 抓取，
// 这里必须同步用 1s，否则 30 秒的故障窗口只落 3 个点，峰值会被磨平。
// 真实分辨率仍受 Prometheus 抓取间隔限制——抓取比 step 慢时，多出来的点只是重复值。
const SERIES_STEP_SEC = 1;
const SERIES_INTERVAL_MS = SERIES_STEP_SEC * 1000;
// 增量查询向前重叠 2 个 step，避免 Prometheus 摄入延迟导致最新几个点漏采
const SERIES_OVERLAP_MS = SERIES_STEP_SEC * 2000;
const HEARTBEAT_INTERVAL_MS = 30000;
const COMPLETE_RETRY_MS = 30000;

const ObservabilityPoller = () => {
  const dispatch = useDispatch();
  const { translate } = useLanguage();
  const enabled = useSelector((state) => state.observability.enabled);
  const selectedTargets = useSelector((state) => state.observability.selectedTargets);
  const selectedMetricIds = useSelector((state) => state.observability.selectedMetricIds);
  const activeTab = useSelector((state) => state.tab.activeTab);
  const { currentRunId, currentContainerName, currentDbType, hasTestStarted } = useSelector((state) => state.testRun);
  // 容器测试默认追加容器资源；用户显式选中的宿主机指标保留 —— e1 的网络/磁盘/进程故障
  // 是在宿主机上注入的，只有在宿主机曲线上才看得见。宿主测试不看容器指标。
  const effectiveMetricIds = useMemo(() => {
    if (currentContainerName) {
      return Array.from(new Set([...selectedMetricIds, ...CONTAINER_DEFAULT_METRIC_IDS]));
    }
    return selectedMetricIds.filter((metricId) => !metricId.startsWith('container.'));
  }, [currentContainerName, selectedMetricIds]);
  const createdRunsRef = useRef(new Set());
  const completedRunsRef = useRef(new Set());
  // 只有本页真正看着跑起来的 run 才去初始化/归档。currentRunId 是持久化的，
  // 打开页面时它可能是上一轮早就结束的 runId —— 那种情况归档由 manager 的 TTL 兜底，
  // 不该在这里拿当前时刻新建一个空会话。
  const startedInPageRef = useRef(new Set());
  const latestTimestampRef = useRef(new Map());

  useEffect(() => {
    let cancelled = false;
    let retryTimer = null;
    // manager 重启或者代理刚起来时这三次 GET 可能整体失败。catalog 取不到，
    // selectedMetricIds 就一直是空的，之后每一轮测试都不会建监控会话，页面也不报错。
    // 所以对 catalog 做退避重试，拿到就停。
    const RETRY_DELAYS_MS = [2000, 5000, 15000, 60000];
    const load = (attempt) => {
      Promise.allSettled([
        fetchMonitoringCapabilities().then((data) => dispatch(setCapabilities(data))),
        fetchMonitoringCatalog().then((data) => dispatch(setCatalog(data))),
        fetchMonitoringHistory().then((data) => dispatch(setMonitoringHistory(data))),
      ]).then((results) => {
        if (cancelled || results[1].status === 'fulfilled') return;
        const delay = RETRY_DELAYS_MS[attempt];
        if (delay === undefined) return;
        retryTimer = setTimeout(() => load(attempt + 1), delay);
      });
    };
    load(0);
    return () => {
      cancelled = true;
      if (retryTimer) clearTimeout(retryTimer);
    };
  }, [dispatch]);

  useEffect(() => {
    if (!enabled || !currentRunId || effectiveMetricIds.length === 0) return undefined;
    let cancelled = false;
    let seriesTimer = null;
    let heartbeatTimer = null;
    let completeTimer = null;

    const ensureSession = async () => {
      if (!createdRunsRef.current.has(currentRunId)) {
        const session = await createMonitoringSession({
          runId: currentRunId,
          targets: selectedTargets,
          metricIds: effectiveMetricIds,
          uiRefreshSec: SERIES_STEP_SEC,
          queryStepSec: SERIES_STEP_SEC,
          archiveEnabled: true,
          containerName: currentContainerName || undefined,
          // 让 manager 按被测库类型选 db.* 数据源（mysql_* / og_*）；不传会一直取 PG 的值。
          dbType: currentDbType || undefined,
        });
        createdRunsRef.current.add(currentRunId);
        if (!cancelled) dispatch(setMonitoringSession(session));
      }
    };

    const heartbeat = async () => {
      try {
        await ensureSession();
        const session = await fetchMonitoringSession(currentRunId);
        if (!cancelled) {
          dispatch(setMonitoringSession(session));
          // 上一轮失败留下的红色横幅不能挂在这一轮上：会话能读到就说明链路已经通了。
          dispatch(setMonitoringError(''));
        }
      } catch (error) {
        if (!cancelled) dispatch(setMonitoringError(error.message || translate('observability.linkUnavailable')));
      }
      if (!cancelled && hasTestStarted) {
        heartbeatTimer = setTimeout(heartbeat, HEARTBEAT_INTERVAL_MS);
      }
    };

    const pollSeries = async () => {
      try {
        await ensureSession();
        const last = latestTimestampRef.current.get(currentRunId) || 0;
        const response = await fetchMonitoringSeries(currentRunId, {
          metricIds: effectiveMetricIds.join(','),
          start: last > 0 ? Math.max(0, last - SERIES_OVERLAP_MS) : undefined,
          end: Date.now(),
          stepSec: SERIES_STEP_SEC,
        });
        const responseLatest = Math.max(
          last,
          ...(response.series || []).flatMap((series) =>
            (series.points || []).map((point) => Number(point?.[0]) || 0)),
        );
        latestTimestampRef.current.set(currentRunId, responseLatest);
        if (!cancelled) dispatch(upsertMonitoringSeries(response));
      } catch (error) {
        if (!cancelled) dispatch(setMonitoringError(error.message || translate('observability.seriesFailed')));
      }
      if (!cancelled && hasTestStarted && activeTab === 'monitoring') {
        seriesTimer = setTimeout(pollSeries, SERIES_INTERVAL_MS);
      }
    };

    const complete = async () => {
      if (!startedInPageRef.current.has(currentRunId)) return;
      if (completedRunsRef.current.has(currentRunId)) return;
      completedRunsRef.current.add(currentRunId);
      try {
        await ensureSession();
        const session = await completeMonitoringSession(currentRunId);
        const response = await fetchMonitoringHistorySeries(currentRunId);
        if (!cancelled) {
          dispatch(setMonitoringSession(session));
          dispatch(upsertMonitoringSeries(response));
          const history = await fetchMonitoringHistory();
          if (!cancelled) dispatch(setMonitoringHistory(history));
        }
      } catch (error) {
        completedRunsRef.current.delete(currentRunId);
        if (!cancelled) {
          dispatch(setMonitoringError(error.message || translate('observability.archiveFailed')));
          // 归档只有这一次机会：失败就把归档窗口丢了（manager 重启、Prometheus 抖一下都会失败）。
          // 过 30 秒再试一次；effect 重跑前一直有效。
          completeTimer = setTimeout(complete, COMPLETE_RETRY_MS);
        }
      }
    };

    if (hasTestStarted) {
      startedInPageRef.current.add(currentRunId);
      heartbeat();
      if (activeTab === 'monitoring') pollSeries();
    } else {
      complete();
    }

    return () => {
      cancelled = true;
      if (seriesTimer) clearTimeout(seriesTimer);
      if (heartbeatTimer) clearTimeout(heartbeatTimer);
      if (completeTimer) clearTimeout(completeTimer);
    };
  }, [
    activeTab,
    currentContainerName,
    currentDbType,
    currentRunId,
    dispatch,
    effectiveMetricIds,
    enabled,
    hasTestStarted,
    selectedTargets,
    translate,
  ]);

  return null;
};

export default ObservabilityPoller;
