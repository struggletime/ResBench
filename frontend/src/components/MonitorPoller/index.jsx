import { useEffect, useRef } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { fetchSuiteMonitor, fetchSuiteStatus, fetchFaultInjectInfo } from '../../api/suiteApi';
import { setMonitorData, setFaultRegions, setFaultInjectInfo, clearMonitorData } from '../../store/modules/monitorSlice';
import { finishRunState } from '../../store/modules/testRunSlice';

let didInit = false;

// eslint-disable-next-line react-refresh/only-export-components
export function initMonitorPoller() {
  didInit = true;
}

// eslint-disable-next-line react-refresh/only-export-components
export function isMonitorPollerReady() {
  return didInit;
}

const POLL_INTERVAL_MS = 2000;
const FAULT_POLL_INTERVAL_MS = 3000;

function normalizeMonitorData(items) {
  if (!Array.isArray(items)) return [];
  return items
    .map((item, index) => {
      const throughput = Number.parseFloat(item?.tps ?? item?.throughput ?? 0);
      const latency = Number.parseFloat(item?.avgLatencyMs ?? 0);
      const timestamp = item?.timestamp ?? index;
      return {
        id: `${timestamp}-${index}`,
        timestamp: Number(timestamp),
        throughput: Number.isFinite(throughput) ? Number(throughput.toFixed(2)) : 0,
        avgLatencyMs: Number.isFinite(latency) ? Number(latency.toFixed(2)) : 0,
      };
    })
    .filter((item) => Number.isFinite(item.throughput))
    .sort((a, b) => a.timestamp - b.timestamp);
}

const MonitorPoller = () => {
  const dispatch = useDispatch();
  const currentRunId = useSelector((state) => state.testRun?.currentRunId || '');
  const manuallyStopped = useSelector((state) => state.testRun?.manuallyStopped || false);

  const finishedRunRef = useRef('');
  const timerRef = useRef(null);
  const faultTimerRef = useRef(null);
  const fetchingRef = useRef(false);

  useEffect(() => {
    if (!currentRunId || manuallyStopped) {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      if (faultTimerRef.current) {
        clearTimeout(faultTimerRef.current);
        faultTimerRef.current = null;
      }
      if (!currentRunId) {
        dispatch(clearMonitorData());
      }
      return undefined;
    }

    finishedRunRef.current = '';

    const poll = async () => {
      if (fetchingRef.current) return;
      fetchingRef.current = true;

      try {
        const [status, monitor] = await Promise.all([
          fetchSuiteStatus(currentRunId),
          fetchSuiteMonitor(currentRunId),
        ]);

        const statusValue = String(status?.status || status?.state || '').toLowerCase();
        const normalized = normalizeMonitorData(monitor);

        dispatch(setMonitorData({
          data: normalized,
          suiteState: statusValue || 'running',
          runId: currentRunId,
        }));

        const isEnded = status?.end === true
          || statusValue === 'stopped'
          || statusValue === 'end'
          || statusValue === 'success'
          || statusValue === 'error';

        if (isEnded && finishedRunRef.current !== currentRunId) {
          finishedRunRef.current = currentRunId;
          dispatch(finishRunState({ runId: currentRunId }));
          return;
        }
      } catch {
        // silently retry
      } finally {
        fetchingRef.current = false;
      }

      timerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
    };

    const pollFaults = async () => {
      try {
        // 运行中不传 historyName → 后端读 tmp/faultInject.json
        const faultHistoryName = finishedRunRef.current || '';
        const faultInfo = await fetchFaultInjectInfo(faultHistoryName);

        if (!Array.isArray(faultInfo) || faultInfo.length === 0) {
          dispatch(setFaultRegions([]));
          dispatch(setFaultInjectInfo([]));
        } else {
          // startTime 必须 > 1e9（至少是 2001 年之后的有效时间戳）
          const regions = faultInfo
            .filter((f) => typeof f.startTime === 'number' && f.startTime > 1e9)
            .map((f) => ({
              faultID: f.faultID,
              faultName: f.faultName,
              startTime: f.startTime,
              endTime: typeof f.endTime === 'number' && f.endTime > 1e9 ? f.endTime : 0,
              groupKey: f.groupKey || '',
            }));

          dispatch(setFaultRegions(regions));
          // 完整 faultInjectInfo（含 verification）→ 实时页徽章轮询状态
          dispatch(setFaultInjectInfo(faultInfo));
        }
      } catch {
        // silently retry
      }

      if (!finishedRunRef.current || finishedRunRef.current !== currentRunId) {
        faultTimerRef.current = setTimeout(pollFaults, FAULT_POLL_INTERVAL_MS);
      }
    };

    poll();
    pollFaults();

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      if (faultTimerRef.current) {
        clearTimeout(faultTimerRef.current);
        faultTimerRef.current = null;
      }
    };
  }, [currentRunId, manuallyStopped, dispatch]);

  return null;
};

export default MonitorPoller;
