import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useSelector } from 'react-redux';
import { Pagination } from 'antd';
import styles from './HistoryTable.module.css';
import { useLanguage } from '../../i18n';
import { resolveFaultDisplayName } from '../../config/faultConfig';
import RunChartsModal from './RunChartsModal';
import MonitoringDataModal from './MonitoringDataModal';
import ReportPreview from './ReportPreview';
import { LabelDialog, ExportDialog } from './ArchiveDialogs';
import {
  fetchHistoryList,
  fetchHistoryQuery,
  fetchSuiteMonitor,
  fetchSuiteResult,
  fetchFaultInjectInfo,
  saveHistoryLabel,
  exportHistoryArchive,
} from '../../api/suiteApi';
import { getEnvInfo } from '../../api/client';
import { mapBackendScores } from '../../utils/comparisonHelpers';

const DEFAULT_PAGE_SIZE = 20;
const PAGE_SIZE_OPTIONS = [10, 20, 50, 100, 200];
const ERROR_AUTO_HIDE_MS = 6000;
const NOTICE_AUTO_HIDE_MS = 3000;
const KEYWORD_DEBOUNCE_MS = 350;

// 数据库类型内置常见值（后端 dbType 已统一小写，这里用小写便于等值匹配）
const BUILTIN_DB_TYPES = [
  { value: 'opengauss', label: 'openGauss' },
  { value: 'postgresql', label: 'PostgreSQL' },
  { value: 'mysql', label: 'MySQL' },
  { value: 'mariadb', label: 'MariaDB' },
  { value: 'oracle', label: 'Oracle' },
  { value: 'sqlserver', label: 'SQL Server' },
  { value: 'dm', label: 'DM', labelKey: 'history.dbType.dm' },
  { value: 'kingbase', label: 'KingbaseES' },
  { value: 'gaussdb', label: 'GaussDB' },
  { value: 'tidb', label: 'TiDB' },
];

// 同一类数据库在不同来源里拼写不一致：启动参数写 postgresql，
// 基准驱动写进 summary 的 DBMS Type 是 POSTGRES，归档行归一化后是 postgres。
// 检索/分页都按「族」比较，否则选 PostgreSQL 反而会把 postgres 的记录全部过滤掉。
function normalizeDbType(value) {
  const key = String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '');
  if (!key) return '';
  // 前缀匹配，兼容 "PostgreSQL 10.21 (Debian …)" 这类把版本串当类型传进来的旧数据
  if (key.startsWith('postgres') || key.startsWith('pgsql') || key === 'pg') return 'postgresql';
  if (key.startsWith('sqlserver') || key.startsWith('mssql')) return 'sqlserver';
  if (key.startsWith('kingbase')) return 'kingbase';
  if (key === 'dm' || key.startsWith('dameng')) return 'dm';
  return key;
}

const SOURCE_OPTIONS = [
  { value: 'ui', labelKey: 'history.source.ui' },
  { value: 'script', labelKey: 'history.source.script' },
  { value: 'matrix', labelKey: 'history.source.matrix' },
];

// 故障验证状态对应的颜色 chip（verification 可能为 null = 未注入/待验证）
const VERIFICATION_META = {
  ENFORCED: { textKey: 'verification.status.ENFORCED', color: '#15803d', bg: '#dcfce7', border: '#86efac' },
  NOT_ENFORCED: { textKey: 'verification.status.NOT_ENFORCED', color: '#b91c1c', bg: '#fee2e2', border: '#fca5a5' },
  ERROR: { textKey: 'verification.status.ERROR', color: '#c2410c', bg: '#ffedd5', border: '#fdba74' },
  UNKNOWN: { textKey: 'verification.status.UNKNOWN', color: '#525252', bg: '#f5f5f5', border: '#d4d4d4' },
  PENDING: { textKey: 'verification.status.PENDING', color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb' },
};

const ARTIFACT_DEFS = [
  { key: 'hasSummary', label: 'summary' },
  { key: 'hasMonitor', label: 'monitor' },
  { key: 'hasMetric', label: 'metric' },
  { key: 'hasFaultInject', label: 'fault' },
  { key: 'hasRaw', label: 'raw' },
];

const EMPTY_FILTERS = {
  keyword: '',
  since: '',
  until: '',
  dbType: '',
  workload: '',
  orchestration: '',
  dbKey: '',
  label: '',
  fault: '',
  faultOutcome: '',
  source: '',
  success: '',
  complete: false,
};

function formatTimestamp(raw) {
  if (raw === null || raw === undefined || raw === '') return '--';
  const numeric = Number(raw);
  if (Number.isFinite(numeric) && numeric <= 0) return '--';
  const date = new Date(Number.isFinite(numeric) ? numeric : raw);
  if (Number.isNaN(date.getTime())) return '--';
  return date.toLocaleString('zh-CN', { hour12: false });
}

function parseHistoryStartTime(historyName) {
  if (!historyName) return { timestamp: Number.NaN, text: '--' };
  const match = String(historyName).match(/_(\d{8})_(\d{6})$/);
  if (!match) return { timestamp: Number.NaN, text: '--' };

  const [, datePart, timePart] = match;
  const year = Number(datePart.slice(0, 4));
  const month = Number(datePart.slice(4, 6)) - 1;
  const day = Number(datePart.slice(6, 8));
  const hour = Number(timePart.slice(0, 2));
  const minute = Number(timePart.slice(2, 4));
  const second = Number(timePart.slice(4, 6));
  const parsedDate = new Date(year, month, day, hour, minute, second);

  if (Number.isNaN(parsedDate.getTime())) {
    return { timestamp: Number.NaN, text: '--' };
  }

  const text = `${datePart.slice(0, 4)}-${datePart.slice(4, 6)}-${datePart.slice(6, 8)} ${timePart.slice(0, 2)}:${timePart.slice(2, 4)}:${timePart.slice(4, 6)}`;
  return {
    timestamp: parsedDate.getTime(),
    text,
  };
}

function deriveStatus(statusData, startTimestamp) {
  if (statusData && typeof statusData === 'object') {
    if (statusData.end === false) return 'running';
    if (statusData.end === true && statusData.success === true) return 'normal';

    const rawMessage = String(statusData.message || '').toLowerCase();
    const rawStatus = String(statusData.status || statusData.state || '').toLowerCase();
    const stoppedByMessage = ['停止', 'stopped', 'stop', 'manual'].some((keyword) => rawMessage.includes(keyword));
    const stoppedByState = ['stopped', 'manual', 'cancelled', 'canceled'].includes(rawStatus);

    if (statusData.end === true && (stoppedByMessage || stoppedByState)) return 'stopped';
    if (statusData.end === true && statusData.success === false) return 'error';

    if (['running', 'normal', 'error', 'stopped'].includes(rawStatus)) {
      return rawStatus;
    }
  }

  // No status payload available — infer from start time
  if (startTimestamp && Number.isFinite(startTimestamp)) {
    const fiveMinutes = 5 * 60 * 1000;
    const elapsed = Date.now() - startTimestamp;
    if (elapsed < fiveMinutes) return 'running';
    return 'error';
  }

  return 'unknown';
}

function getStatusLabel(status, translate) {
  const statusKey = String(status || '').toLowerCase();
  if (statusKey === 'normal') return translate('history.status.normal');
  if (statusKey === 'error') return translate('history.status.error');
  if (statusKey === 'running') return translate('history.status.running');
  if (statusKey === 'stopped') return translate('history.status.stopped');
  return translate('history.status.unknown');
}

function formatDuration(ms) {
  const numeric = Number(ms);
  if (!Number.isFinite(numeric) || numeric <= 0) return '--';
  const totalSeconds = Math.round(numeric / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

function formatBytes(bytes) {
  const numeric = Number(bytes);
  if (!Number.isFinite(numeric) || numeric <= 0) return '--';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = numeric;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

function formatMetric(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return '--';
  return numeric.toFixed(2);
}

function formatParams(params) {
  if (!params || typeof params !== 'object') return '';
  return Object.entries(params)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${key}=${typeof value === 'object' ? JSON.stringify(value) : value}`)
    .join(', ');
}

function verificationMeta(status) {
  // 后端新归档返回完整 verification 对象，老数据是状态字符串，两种都兼容
  const raw = status && typeof status === 'object' ? status.status : status;
  const key = String(raw || '').toUpperCase();
  if (!key) return { ...VERIFICATION_META.PENDING };
  return VERIFICATION_META[key] || { ...VERIFICATION_META.UNKNOWN };
}

function sourceLabel(source, translate) {
  const found = SOURCE_OPTIONS.find((item) => item.value === String(source || '').toLowerCase());
  return found ? translate(found.labelKey) : source;
}

function statusFromSuccess(success, state) {
  if (success === true) return 'normal';
  if (success === false) return 'error';
  const rawState = String(state || '').toLowerCase();
  if (rawState.includes('running') || rawState.includes('executing')) return 'running';
  return 'unknown';
}

function faultTitle(fault, translate, lang) {
  const parts = [];
  const displayName = resolveFaultDisplayName(fault, translate, lang);
  if (displayName) parts.push(translate('history.faultTitle.fault', { name: displayName }));
  if (fault?.nameKey) parts.push(translate('history.faultTitle.nameKey', { key: fault.nameKey }));
  if (fault?.groupKey) parts.push(translate('history.faultTitle.groupKey', { key: fault.groupKey }));
  if (fault?.delaySeconds !== null && fault?.delaySeconds !== undefined) {
    parts.push(translate('history.faultTitle.delay', { seconds: fault.delaySeconds }));
  }
  const paramsText = formatParams(fault?.params);
  if (paramsText) parts.push(translate('history.faultTitle.params', { params: paramsText }));
  const meta = verificationMeta(fault?.verification);
  parts.push(translate('history.faultTitle.verification', { status: translate(meta.textKey) }));
  return parts.join('\n');
}

/** 服务端检索行 → 组件内统一运行记录（表格 / 报告 / 评分面板共用） */
function normalizeQueryRow(row) {
  const historyName = row?.historyName || row?.id || '';
  const parsedStart = parseHistoryStartTime(historyName);
  const rawStart = Number(row?.startTimestamp);
  const startTimestamp = Number.isFinite(rawStart) && rawStart > 0 ? rawStart : parsedStart.timestamp;
  // 归档行里的 testSuite 记录的是工作负载实现名（如 benchmarkSQL），
  // 而引擎 /newest_result、/monitor 要求传目录前缀（tpcc / smallbank），
  // 传错会被引擎判成不属于该测试套件而返回 null，报告里就没有评分和曲线。
  // 所以以 historyName 的前缀为准。
  const suiteName = historyName.split('_')[0] || row?.testSuite || '';
  const dbType = row?.dbType || '';
  const dbVersion = row?.dbVersion || '';
  const rawEnd = Number(row?.endTimestamp);
  const rawElapsed = Number(row?.elapsedMs);

  return {
    id: historyName,
    historyName,
    runId: row?.runId || historyName,
    suiteName,
    runName: suiteName || historyName,
    DBVersion: [dbType, dbVersion].filter(Boolean).join(' ') || '-',
    dbType,
    dbTypeRaw: row?.dbTypeRaw || dbType,
    dbVersion,
    dbKey: row?.dbKey || '',
    workload: row?.workload || '',
    workloadParams: row?.workloadParams || null,
    dbParams: row?.dbParams || null,
    deploymentType: row?.deploymentType || '',
    // 归档时的 Docker / 资源快照（老归档没有这些字段，报告里显示“未归档”）
    containerName: row?.containerName || '',
    dockerized: typeof row?.dockerized === 'boolean' ? row.dockerized : null,
    resources: row?.resources || null,
    runOptions: row?.runOptions || null,
    connectionArchived: row?.connectionArchived === true,
    createdAt: row?.createdAt || '',
    orchestrationId: row?.orchestrationId || '',
    source: row?.source || '',
    labels: Array.isArray(row?.labels) ? row.labels : [],
    note: row?.note || '',
    autoTimingMode: row?.autoTimingMode || '',
    faults: Array.isArray(row?.faults) ? row.faults : [],
    startTimestamp: Number.isFinite(startTimestamp) ? startTimestamp : Number.NaN,
    endTimestamp: Number.isFinite(rawEnd) ? rawEnd : null,
    elapsedMs: Number.isFinite(rawElapsed) ? rawElapsed : null,
    success: row?.success === undefined ? null : row.success,
    state: row?.state || '',
    status: statusFromSuccess(row?.success, row?.state),
    artifacts: row?.artifacts || null,
    metrics: row?.metrics || null,
    startTime: formatTimestamp(startTimestamp),
    endTime: formatTimestamp(rawEnd),
    fromServer: true,
  };
}

/** 旧的 /suite/history/list 数据 → 同一套运行记录（回退用） */
function normalizeHistoryRecords(data) {
  const historyMap = data?.historyMap || {};
  const statusMap = data?.statusMap || {};
  const records = [];

  Object.entries(historyMap).forEach(([suiteName, historyNames]) => {
    if (!Array.isArray(historyNames)) return;

    historyNames.forEach((historyName) => {
      const statusPayload = statusMap[historyName];
      const parsedStart = parseHistoryStartTime(historyName);
      const startTime = formatTimestamp(statusPayload?.startTimestamp);
      const endTime = formatTimestamp(statusPayload?.endTimestamp);
      const startTimestamp = Number(statusPayload?.startTimestamp);
      const resolvedSuite = suiteName || historyName.split('_')[0] || '';

      // 旧 statusMap 里能拿到的额外信息：负载类型/参数、故障列表（fault key 字符串）
      let workloadParams = null;
      try {
        workloadParams = statusPayload?.workloadConfig ? JSON.parse(statusPayload.workloadConfig) : null;
      } catch {
        workloadParams = null;
      }
      const legacyFaults = Array.isArray(statusPayload?.faults)
        ? statusPayload.faults.map((name) => ({ nameKey: name, faultName: null, verification: null }))
        : [];

      records.push({
        id: historyName,
        historyName,
        suiteName: resolvedSuite,
        runName: resolvedSuite || parsedStart.text || historyName,
        DBVersion: statusPayload?.dbType || '-',
        dbType: statusPayload?.dbType || '',
        dbVersion: statusPayload?.dbVersion || '',
        dbKey: '',
        workload: statusPayload?.workloadType || '',
        workloadParams,
        dbParams: null,
        deploymentType: '',
        orchestrationId: '',
        source: '',
        labels: [],
        note: '',
        autoTimingMode: '',
        faults: legacyFaults,
        startTimestamp: Number.isFinite(startTimestamp) ? startTimestamp : parsedStart.timestamp,
        endTimestamp: Number(statusPayload?.endTimestamp) || null,
        elapsedMs: null,
        success: statusPayload?.success === undefined ? null : statusPayload.success,
        state: statusPayload?.state || '',
        status: deriveStatus(statusPayload, startTimestamp),
        artifacts: null,
        metrics: null,
        startTime: startTime !== '--' ? startTime : parsedStart.text,
        endTime,
        rawStatus: statusPayload || null,
        fromServer: false,
      });
    });
  });

  return records.sort((left, right) => {
    const leftTime = Number.isFinite(left.startTimestamp) ? left.startTimestamp : -Infinity;
    const rightTime = Number.isFinite(right.startTimestamp) ? right.startTimestamp : -Infinity;
    if (leftTime !== rightTime) {
      return rightTime - leftTime;
    }
    return String(right.historyName).localeCompare(String(left.historyName));
  });
}

function toEpochMs(value) {
  if (!value) return '';
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms : '';
}

function toLocalInputValue(date) {
  const pad = (value) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function buildQueryParams(filters, page, size, sort) {
  const params = { page, size };
  const setIf = (key, value) => {
    if (value === undefined || value === null) return;
    const text = String(value).trim();
    if (text === '') return;
    params[key] = text;
  };

  setIf('keyword', filters.keyword);
  setIf('since', toEpochMs(filters.since));
  setIf('until', toEpochMs(filters.until));
  setIf('dbType', filters.dbType);
  setIf('workload', filters.workload);
  setIf('orchestration', filters.orchestration);
  setIf('dbKey', filters.dbKey);
  setIf('label', filters.label);
  setIf('fault', filters.fault);
  setIf('faultOutcome', filters.faultOutcome);
  setIf('source', filters.source);
  // success 只支持 true/false；「未知」没有对应参数，在本地对当前页做过滤
  if (filters.success === 'true' || filters.success === 'false') {
    params.success = filters.success;
  }
  if (filters.complete) {
    params.complete = 'true';
  }
  if (sort) {
    params.sort = sort;
  }
  return params;
}

function normalizeFaultToken(value) {
  return String(value || '')
    .toLowerCase()
    .replace(/[.\s]+/g, '');
}

/** 回退模式下的本地过滤（字段缺失时不参与限制） */
function matchesLocalRun(run, filters, legacySearch) {
  const keyword = String(filters.keyword || '').trim().toLowerCase();
  if (keyword) {
    const haystack = [
      run.historyName,
      run.suiteName,
      run.dbKey,
      run.orchestrationId,
      run.workload,
      run.note,
      (run.labels || []).join(' '),
    ]
      .join(' ')
      .toLowerCase();
    if (!haystack.includes(keyword)) return false;
  }

  const legacyId = String(legacySearch?.id || '').trim().toLowerCase();
  if (legacyId && !String(run.historyName || '').toLowerCase().includes(legacyId)) return false;
  const legacyDb = String(legacySearch?.dbVersion || '').trim().toLowerCase();
  if (legacyDb) {
    const legacyFamily = normalizeDbType(legacyDb);
    const runFamily = normalizeDbType(run.dbType);
    const versionText = String(run.DBVersion || '').toLowerCase();
    const hit = legacyFamily && runFamily ? runFamily === legacyFamily : versionText.includes(legacyDb);
    if (!hit) return false;
  }

  const equalsIgnoreCase = (left, right) =>
    String(left || '').toLowerCase() === String(right || '').toLowerCase();
  const containsIgnoreCase = (left, right) =>
    String(left || '').toLowerCase().includes(String(right || '').toLowerCase());

  if (filters.dbType && normalizeDbType(run.dbType) !== normalizeDbType(filters.dbType)) return false;
  if (filters.workload && !containsIgnoreCase(run.workload, filters.workload)) return false;
  if (filters.orchestration && !containsIgnoreCase(run.orchestrationId, filters.orchestration)) return false;
  if (filters.dbKey && !containsIgnoreCase(run.dbKey, filters.dbKey)) return false;
  if (filters.source && !equalsIgnoreCase(run.source, filters.source)) return false;
  if (filters.label && !containsIgnoreCase((run.labels || []).join(','), filters.label)) return false;
  if (filters.fault) {
    const token = normalizeFaultToken(filters.fault);
    const hit = (run.faults || []).some(
      (fault) =>
        normalizeFaultToken(fault?.nameKey).includes(token) ||
        normalizeFaultToken(fault?.faultName).includes(token)
    );
    if (!hit) return false;
  }
  if (filters.success === 'true' && run.success !== true) return false;
  if (filters.success === 'false' && run.success !== false) return false;
  if (filters.success === 'unknown' && run.success !== null) return false;
  // 回退数据没有逐故障执行结果，faultOutcome 由调用方清空（控件同时禁用）
  if (filters.faultOutcome === 'all-ok' && run.faultOutcome?.allOk !== true) return false;
  if (filters.complete && !run.artifacts?.complete) return false;

  const since = Number(toEpochMs(filters.since));
  if (Number.isFinite(since) && since > 0 && !(Number(run.startTimestamp) >= since)) return false;
  const until = Number(toEpochMs(filters.until));
  if (Number.isFinite(until) && until > 0 && !(Number(run.startTimestamp) <= until)) return false;

  return true;
}

function triggerBlobDownload(blob, filename) {
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename || 'archive.zip';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(url);
}

function useDebouncedValue(value, delay) {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [value, delay]);

  return debounced;
}

function HistoryTable() {
  const { currentRunId, lastRunId } = useSelector((state) => state.testRun);
  const { translate, lang } = useLanguage();

  const [reportModalRun, setReportModalRun] = useState(null);
  const [monitorModalRun, setMonitorModalRun] = useState(null);
  const [selectedRun, setSelectedRun] = useState(null);
  const [loadedScores, setLoadedScores] = useState(null);
  const [selectedMonitorData, setSelectedMonitorData] = useState([]);
  const [selectedFaultInjectInfo, setSelectedFaultInjectInfo] = useState([]);
  const [selectedTriangles, setSelectedTriangles] = useState([]);
  const [selectedFaultScores, setSelectedFaultScores] = useState([]);
  const [selectedFaultIdx, setSelectedFaultIdx] = useState(-1);
  const [scoresLoading, setScoresLoading] = useState(false);
  const [scoresError, setScoresError] = useState('');
  const [reportResult, setReportResult] = useState(null);
  const [reportScores, setReportScores] = useState(null);
  const [reportMonitorData, setReportMonitorData] = useState([]);
  const [reportTriangles, setReportTriangles] = useState([]);
  const [reportFaultInjectInfo, setReportFaultInjectInfo] = useState([]);
  const [reportLoading, setReportLoading] = useState(false);

  // ---- 检索 / 分页 / 回退状态 ----
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [legacySearch, setLegacySearch] = useState({ id: '', dbVersion: '' });
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState('');
  const [serverRows, setServerRows] = useState([]);
  const [serverTotal, setServerTotal] = useState(0);
  const [serverPageCount, setServerPageCount] = useState(1);
  const [localRuns, setLocalRuns] = useState([]);
  const [mode, setMode] = useState('server');
  const [queryLoading, setQueryLoading] = useState(false);
  const [queryError, setQueryError] = useState('');
  const [notice, setNotice] = useState('');
  const [reloadToken, setReloadToken] = useState(0);

  // ---- 归档动作状态 ----
  const [labelTarget, setLabelTarget] = useState(null);
  const [labelSaving, setLabelSaving] = useState(false);
  const [labelError, setLabelError] = useState('');
  const [exportTarget, setExportTarget] = useState(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState('');

  const debouncedKeyword = useDebouncedValue(filters.keyword, KEYWORD_DEBOUNCE_MS);

  const queryParams = useMemo(
    () =>
      buildQueryParams(
        {
          keyword: debouncedKeyword,
          since: filters.since,
          until: filters.until,
          dbType: filters.dbType,
          workload: filters.workload,
          orchestration: filters.orchestration,
          dbKey: filters.dbKey,
          label: filters.label,
          fault: filters.fault,
          faultOutcome: filters.faultOutcome,
          source: filters.source,
          success: filters.success,
          complete: filters.complete,
        },
        page,
        size,
        sort
      ),
    [
      debouncedKeyword,
      filters.since,
      filters.until,
      filters.dbType,
      filters.workload,
      filters.orchestration,
      filters.dbKey,
      filters.label,
      filters.fault,
      filters.faultOutcome,
      filters.source,
      filters.success,
      filters.complete,
      page,
      size,
      sort,
    ]
  );

  // 服务端检索；失败时回退到旧的 /suite/history/list + 本地过滤/分页
  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      setQueryLoading(true);
      try {
        const data = await fetchHistoryQuery(queryParams);
        if (cancelled) return;
        const rows = Array.isArray(data?.rows) ? data.rows.map(normalizeQueryRow) : [];
        setServerRows(rows);
        setServerTotal(Number(data?.total) || 0);
        setServerPageCount(Math.max(1, Number(data?.pageCount) || 1));
        // 后端可能把 page 钳制到有效范围，回写避免分页器与数据不一致
        const clampedPage = Number(data?.page);
        if (Number.isFinite(clampedPage) && clampedPage > 0) {
          setPage((prev) => (prev === clampedPage ? prev : clampedPage));
        }
        setMode('server');
        setQueryError('');
      } catch (error) {
        if (cancelled) return;
        try {
          const fallbackData = await fetchHistoryList({});
          if (cancelled) return;
          setLocalRuns(normalizeHistoryRecords(fallbackData));
          setMode('local');
          setQueryError(
            `${error?.message || translate('history.queryUnavailable')}${translate('history.queryFallback')}`
          );
        } catch (fallbackError) {
          if (cancelled) return;
          setQueryError(
            fallbackError?.message || error?.message || translate('history.loadFailed')
          );
        }
      } finally {
        if (!cancelled) setQueryLoading(false);
      }
    };

    load();
    return () => {
      cancelled = true;
    };
  }, [queryParams, reloadToken, currentRunId, lastRunId, translate]);

  useEffect(() => {
    if (!queryError) return undefined;
    const timer = window.setTimeout(() => setQueryError(''), ERROR_AUTO_HIDE_MS * 4);
    return () => window.clearTimeout(timer);
  }, [queryError]);

  useEffect(() => {
    if (!notice) return undefined;
    const timer = window.setTimeout(() => setNotice(''), NOTICE_AUTO_HIDE_MS);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const updateFilter = useCallback((key, value) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
    setPage(1);
  }, []);

  const handleResetFilters = useCallback(() => {
    setFilters(EMPTY_FILTERS);
    setLegacySearch({ id: '', dbVersion: '' });
    setSort('');
    setPage(1);
  }, []);

  const handleQuickRange = useCallback((hours) => {
    const now = new Date();
    const since = new Date(now.getTime() - hours * 60 * 60 * 1000);
    setFilters((prev) => ({
      ...prev,
      since: toLocalInputValue(since),
      until: toLocalInputValue(now),
    }));
    setPage(1);
  }, []);

  const activeFilterCount = useMemo(() => {
    let count = 0;
    if (filters.keyword.trim()) count += 1;
    if (filters.since || filters.until) count += 1;
    if (filters.dbType) count += 1;
    if (filters.workload.trim()) count += 1;
    if (filters.orchestration.trim()) count += 1;
    if (filters.dbKey.trim()) count += 1;
    if (filters.label.trim()) count += 1;
    if (filters.fault.trim()) count += 1;
    if (filters.faultOutcome) count += 1;
    if (filters.source) count += 1;
    if (filters.success) count += 1;
    if (filters.complete) count += 1;
    return count;
  }, [filters]);

  const dbTypeOptions = useMemo(() => {
    const options = new Map();
    // 以族键做去重，postgres / postgresql / POSTGRES 只出现一次，展示内置的规范名
    BUILTIN_DB_TYPES.forEach((item) =>
      options.set(normalizeDbType(item.value), item.labelKey ? translate(item.labelKey) : item.label)
    );
    const collect = (value) => {
      const key = normalizeDbType(value);
      if (!key) return;
      if (!options.has(key)) options.set(key, String(value).trim());
    };
    if (mode === 'server') {
      serverRows.forEach((run) => collect(run.dbType));
    } else {
      localRuns.forEach((run) => collect(run.dbType));
    }
    return [
      { value: '', label: translate('common.all') },
      ...[...options.entries()].map(([value, label]) => ({ value, label })),
    ];
  }, [mode, serverRows, localRuns, translate]);

  // 回退模式：本地过滤 + 本地分页
  // 注意：/suite/history/list 没有产物完整性信息，complete 在回退模式下不参与过滤（控件同时禁用）
  const localFiltered = useMemo(() => {
    if (mode !== 'local') return [];
    return localRuns.filter((run) =>
      matchesLocalRun(run, { ...filters, complete: false, faultOutcome: '' }, legacySearch)
    );
  }, [mode, localRuns, filters, legacySearch]);

  const localPageCount = Math.max(1, Math.ceil(localFiltered.length / size));
  const localPageRows = useMemo(() => {
    const safePage = Math.min(page, localPageCount);
    const offset = (safePage - 1) * size;
    return localFiltered.slice(offset, offset + size);
  }, [localFiltered, page, size, localPageCount]);

  const baseRows = mode === 'server' ? serverRows : localPageRows;
  const totalRecords = mode === 'server' ? serverTotal : localFiltered.length;
  const pageCount = mode === 'server' ? serverPageCount : localPageCount;

  // 「未知」状态没有对应的服务端参数，只对当前页做本地过滤
  const unknownOnly = mode === 'server' && filters.success === 'unknown';
  const displayRows = useMemo(
    () => (unknownOnly ? baseRows.filter((run) => run.success === null) : baseRows),
    [unknownOnly, baseRows]
  );

  useEffect(() => {
    if (page > pageCount) {
      setPage(pageCount);
    }
  }, [page, pageCount]);

  const scores = loadedScores;

  const faultToScores = useCallback((fault) => {
    if (!fault) return null;
    const keys = [
      { metricKey: 'throughput', name: translate('RadarChart.throughput') },
      { metricKey: 'latency', name: translate('RadarChart.latency') },
      { metricKey: 'stability', name: translate('RadarChart.stability') },
      { metricKey: 'resistance', backendKey: 'defence', name: translate('RadarChart.resistance') },
      { metricKey: 'recovery', name: translate('RadarChart.recovery') },
      { metricKey: 'adaptability', name: translate('RadarChart.adaptability') },
      { metricKey: 'deviation', backendKey: 'volatility', name: translate('RadarChart.deviation') },
      { metricKey: 'disturbance', backendKey: 'rto', name: translate('RadarChart.disturbance') },
    ];
    return keys.map((k) => ({
      metricKey: k.metricKey,
      name: k.name,
      value: Number(fault[k.backendKey || k.metricKey]) || 0,
    }));
  }, [translate]);

  const groupedFaultOptions = useMemo(() => {
    const groups = {};
    const standalone = [];
    for (let i = 0; i < selectedFaultScores.length; i++) {
      const fs = selectedFaultScores[i];
      const info = selectedFaultInjectInfo[i];
      const gk = info?.groupKey;
      if (gk) {
        if (!groups[gk]) groups[gk] = { faultNames: [], scores: [] };
        groups[gk].faultNames.push(resolveFaultDisplayName(fs, translate, lang));
        groups[gk].scores.push(fs);
      } else {
        standalone.push(fs);
      }
    }
    const options = [];
    for (const [, group] of Object.entries(groups)) {
      const first = group.scores[0];
      options.push({ ...first, faultName: group.faultNames.join(' + ') });
    }
    for (const fs of standalone) {
      options.push({ ...fs, faultName: resolveFaultDisplayName(fs, translate, lang) });
    }
    return options;
  }, [selectedFaultScores, selectedFaultInjectInfo, translate, lang]);

  const displayScores = useMemo(() => {
    if (selectedFaultIdx >= 0 && selectedFaultIdx < groupedFaultOptions.length) {
      return faultToScores(groupedFaultOptions[selectedFaultIdx]);
    }
    if (groupedFaultOptions.length > 0) {
      const avg = {};
      const keys = ['throughput', 'latency', 'stability', 'defence', 'recovery', 'adaptability', 'volatility', 'rto'];
      for (const k of keys) {
        const sum = groupedFaultOptions.reduce((s, fs) => s + (Number(fs[k]) || 0), 0);
        avg[k] = Math.round((sum / groupedFaultOptions.length) * 100) / 100;
      }
      return faultToScores(avg);
    }
    return scores;
  }, [selectedFaultIdx, groupedFaultOptions, faultToScores, scores]);

  const mapResultToScores = useCallback((result) => {
    return mapBackendScores(result, translate);
  }, [translate]);

  const suiteNameOf = useCallback(
    (run) => run?.historyName?.split('_')[0] || run?.suiteName || run?.runName,
    []
  );

  useEffect(() => {
    if (!selectedRun) return undefined;

    let cancelled = false;
    setScoresLoading(true);
    setScoresError('');

    (async () => {
      try {
        const [result, monitor, injectInfo] = await Promise.all([
          fetchSuiteResult(getEnvInfo(), suiteNameOf(selectedRun), selectedRun.historyName),
          fetchSuiteMonitor(selectedRun.historyName),
          fetchFaultInjectInfo(selectedRun.historyName),
        ]);
        if (cancelled) return;
        const mapped = mapResultToScores(result);
        if (mapped && mapped.length > 0) {
          setLoadedScores(mapped);
          setScoresError('');
        } else {
          setLoadedScores(null);
          const detail = result === null || result === undefined
            ? translate('history.missingResponse')
            : translate('history.responseType', {
              type: typeof result,
              keys: Object.keys(result).join(', ') || translate('common.none'),
            });
          setScoresError(translate('history.scoreMappingFailed', { detail }));
        }
        const monitorRows = Array.isArray(monitor?.data)
          ? monitor.data
          : (Array.isArray(monitor) ? monitor : []);
        setSelectedMonitorData(monitorRows);
        setSelectedFaultInjectInfo(Array.isArray(injectInfo) ? injectInfo : []);
        // 从 result 中提取后端计算的韧性三角数据
        let parsedTriangles = [];
        try {
          const resultObj = typeof result === 'string' ? JSON.parse(result) : result;
          if (resultObj && resultObj.faultTriangles) {
            const raw = typeof resultObj.faultTriangles === 'string'
              ? JSON.parse(resultObj.faultTriangles)
              : resultObj.faultTriangles;
            if (Array.isArray(raw)) {
              parsedTriangles = raw;
            }
          }
        } catch (e) {
          console.warn('[TRIANGLE_DEBUG] Failed to parse faultTriangles from result:', e.message);
        }
        setSelectedTriangles(parsedTriangles);
        // 从 result 中提取每个故障的独立评分
        let parsedFaultScores = [];
        try {
          const resultObj = typeof result === 'string' ? JSON.parse(result) : result;
          if (resultObj && resultObj.faultScores) {
            const raw = typeof resultObj.faultScores === 'string'
              ? JSON.parse(resultObj.faultScores)
              : resultObj.faultScores;
            if (Array.isArray(raw)) {
              parsedFaultScores = raw;
            }
          }
        } catch (e) {
          console.warn('[SCORE_DEBUG] Failed to parse faultScores from result:', e.message);
        }
        setSelectedFaultScores(parsedFaultScores);
        // 不在这里重置 selectedFaultIdx，避免因为轮询刷新导致下拉框被重置
      } catch (err) {
        const errMsg = err?.message || String(err);
        console.error('Failed to load suite result/monitor:', selectedRun.runName, selectedRun.historyName, err);
        if (!cancelled) {
          setLoadedScores(null);
          setSelectedMonitorData([]);
          setScoresError(translate('history.scoreLoadingFailed', { error: errMsg }));
        }
      } finally {
        if (!cancelled) {
          setScoresLoading(false);
        }
      }
    })();

    return () => { cancelled = true; };
  }, [selectedRun, mapResultToScores, suiteNameOf, translate]);

  useEffect(() => {
    if (!reportModalRun) {
      setReportResult(null);
      setReportScores(null);
      setReportMonitorData([]);
      setReportFaultInjectInfo([]);
      setReportLoading(false);
      return undefined;
    }

    let cancelled = false;
    setReportLoading(true);
    // 先清空上一轮的数据，避免新报告的配置/曲线里混入上一个运行的内容
    setReportResult(null);
    setReportScores(null);
    setReportMonitorData([]);
    setReportTriangles([]);
    setReportFaultInjectInfo([]);

    (async () => {
      try {
        const [result, monitor, injectInfo] = await Promise.all([
          fetchSuiteResult(getEnvInfo(), suiteNameOf(reportModalRun), reportModalRun.historyName),
          fetchSuiteMonitor(reportModalRun.historyName),
          fetchFaultInjectInfo(reportModalRun.historyName),
        ]);
        if (cancelled) return;
        setReportResult(result || null);
        setReportScores(mapResultToScores(result) || null);
        const monitorRows = Array.isArray(monitor?.data)
          ? monitor.data
          : (Array.isArray(monitor) ? monitor : []);
        setReportMonitorData(monitorRows);
        setReportFaultInjectInfo(Array.isArray(injectInfo) ? injectInfo : []);
        let parsedTriangles = [];
        try {
          const resultObj = typeof result === 'string' ? JSON.parse(result) : result;
          if (resultObj && resultObj.faultTriangles) {
            const raw = typeof resultObj.faultTriangles === 'string'
              ? JSON.parse(resultObj.faultTriangles)
              : resultObj.faultTriangles;
            if (Array.isArray(raw)) {
              parsedTriangles = raw;
            }
          }
        } catch (e) {
          console.warn('[TRIANGLE_DEBUG] Report: failed to parse faultTriangles:', e.message);
        }
        setReportTriangles(parsedTriangles);
      } catch {
        if (cancelled) return;
        setReportResult(null);
        setReportScores(null);
        setReportMonitorData([]);
      } finally {
        if (!cancelled) {
          setReportLoading(false);
        }
      }
    })();

    return () => { cancelled = true; };
  }, [reportModalRun, mapResultToScores, suiteNameOf]);

  const handleRunNameClick = (run) => {
    setSelectedRun((prev) => (prev?.id === run.id ? null : run));
    setSelectedFaultIdx(-1);
  };

  // ---- 归档动作：打标签 / 导出 ----
  const handleSaveLabels = async ({ labels, note }) => {
    if (!labelTarget) return;
    setLabelSaving(true);
    setLabelError('');
    try {
      await saveHistoryLabel({ historyName: labelTarget.historyName, labels, note });
      setServerRows((prev) =>
        prev.map((run) => (run.id === labelTarget.historyName ? { ...run, labels, note } : run))
      );
      setLocalRuns((prev) =>
        prev.map((run) => (run.id === labelTarget.historyName ? { ...run, labels, note } : run))
      );
      setSelectedRun((prev) =>
        prev && prev.id === labelTarget.historyName ? { ...prev, labels, note } : prev
      );
      setLabelTarget(null);
      setNotice(translate('history.labelSaved'));
      setReloadToken((token) => token + 1); // 刷新列表（后端会补建旧运行的 run-meta.json）
    } catch (error) {
      setLabelError(error?.message || translate('history.saveFailed'));
    } finally {
      setLabelSaving(false);
    }
  };

  const handleExport = async (include) => {
    if (!exportTarget) return;
    setExporting(true);
    setExportError('');
    try {
      const { blob, filename } = await exportHistoryArchive(exportTarget.historyName, include);
      triggerBlobDownload(blob, filename);
      setExportTarget(null);
      setNotice(translate('history.downloadStarted', { filename }));
    } catch (error) {
      setExportError(error?.message || translate('history.exportFailed'));
    } finally {
      setExporting(false);
    }
  };

  const showInitialLoading = queryLoading && displayRows.length === 0 && totalRecords === 0;
  const initialLoadingText = translate('history.loadingInitial');

  return (
    <div className={styles.historyTable}>
      {/* 多维度检索筛选栏 */}
      <div className={styles.searchBar} data-tour="history-search">
        <div className={styles.searchItem}>
          <label>{translate('history.testId')}</label>
          <input
            type="text"
            placeholder={translate('history.keywordPlaceholder')}
            value={filters.keyword}
            onChange={(event) => updateFilter('keyword', event.target.value)}
            className={styles.searchInput}
          />
        </div>
        <div className={styles.searchItem}>
          <label>{translate('history.databaseType')}</label>
          <select
            value={filters.dbType}
            onChange={(event) => updateFilter('dbType', event.target.value)}
            className={styles.searchInput}
          >
            {dbTypeOptions.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </div>
        <div className={styles.searchItem}>
          <label>{translate('history.statusLabel')}</label>
          <select
            value={filters.success}
            onChange={(event) => updateFilter('success', event.target.value)}
            className={styles.searchInput}
            style={{ width: 120 }}
          >
            <option value="">{translate('common.all')}</option>
            <option value="true">{translate('history.statusSuccess')}</option>
            <option value="false">{translate('history.statusFailed')}</option>
            <option value="unknown">{translate('common.unknown')}</option>
          </select>
        </div>
        <div className={styles.searchItem}>
          <label>{translate('history.startTimeFilter')}</label>
          <input
            type="datetime-local"
            value={filters.since}
            onChange={(event) => updateFilter('since', event.target.value)}
            className={styles.searchInput}
          />
        </div>
        <div className={styles.searchItem}>
          <label>{translate('history.endTimeFilter')}</label>
          <input
            type="datetime-local"
            value={filters.until}
            onChange={(event) => updateFilter('until', event.target.value)}
            className={styles.searchInput}
          />
        </div>
        <button className={styles.searchBtn} onClick={() => setReloadToken((token) => token + 1)}>
          {translate('history.search')}
        </button>
        <button className={styles.clearBtn} onClick={handleResetFilters}>
          {translate('history.clear')}
        </button>
        <button className={styles.filterToggle} onClick={() => setFiltersOpen((open) => !open)}>
          {filtersOpen ? translate('history.filtersCollapse') : translate('history.filtersMore')}
          {activeFilterCount > 0 ? <span className={styles.filterCount}>{activeFilterCount}</span> : null}
        </button>
      </div>

      {filtersOpen ? (
        <div className={styles.filterAdvanced}>
          <div className={styles.searchItem}>
            <label>{translate('history.workload')}</label>
            <input
              type="text"
              placeholder={translate('history.workloadPlaceholder')}
              value={filters.workload}
              onChange={(event) => updateFilter('workload', event.target.value)}
              className={styles.searchInput}
            />
          </div>
          <div className={styles.searchItem}>
            <label>{translate('history.orchestration')}</label>
            <input
              type="text"
              placeholder="orchestrationId"
              value={filters.orchestration}
              onChange={(event) => updateFilter('orchestration', event.target.value)}
              className={styles.searchInput}
            />
          </div>
          <div className={styles.searchItem}>
            <label>{translate('history.dbKey')}</label>
            <input
              type="text"
              placeholder="dbKey"
              value={filters.dbKey}
              onChange={(event) => updateFilter('dbKey', event.target.value)}
              className={styles.searchInput}
            />
          </div>
          <div className={styles.searchItem}>
            <label>{translate('history.fault')}</label>
            <input
              type="text"
              placeholder={translate('history.faultPlaceholder')}
              value={filters.fault}
              onChange={(event) => updateFilter('fault', event.target.value)}
              className={styles.searchInput}
            />
          </div>
          <div className={styles.searchItem}>
            <label>{translate('history.faultOutcome')}</label>
            <select
              value={filters.faultOutcome}
              disabled={mode === 'local'}
              onChange={(event) => updateFilter('faultOutcome', event.target.value)}
              className={styles.searchInput}
              style={{ width: 200 }}
              title={
                mode === 'local'
                  ? translate('history.faultOutcomeLocalUnavailable')
                  : translate('history.faultOutcomeHint')
              }
            >
              <option value="">{translate('common.all')}</option>
              <option value="all-ok">{translate('history.faultOutcomeAll')}</option>
            </select>
          </div>
          <div className={styles.searchItem}>
            <label>{translate('history.source')}</label>
            <select
              value={filters.source}
              onChange={(event) => updateFilter('source', event.target.value)}
              className={styles.searchInput}
              style={{ width: 140 }}
            >
              <option value="">{translate('common.all')}</option>
              {SOURCE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>{translate(opt.labelKey)}</option>
              ))}
            </select>
          </div>
          <div className={styles.searchItem}>
            <label>{translate('history.label')}</label>
            <input
              type="text"
              placeholder={translate('history.labelPlaceholder')}
              value={filters.label}
              onChange={(event) => updateFilter('label', event.target.value)}
              className={styles.searchInput}
            />
          </div>
          <label
            className={styles.checkboxItem}
            title={mode === 'local' ? translate('history.completeLocalUnavailable') : translate('history.completeHint')}
          >
            <input
              type="checkbox"
              checked={filters.complete}
              disabled={mode === 'local'}
              onChange={(event) => updateFilter('complete', event.target.checked)}
            />
            {translate('history.completeOnly')}
          </label>
          <div className={styles.searchItem}>
            <label>{translate('history.sort')}</label>
            <select
              value={sort}
              onChange={(event) => { setSort(event.target.value); setPage(1); }}
              className={styles.searchInput}
              style={{ width: 150 }}
            >
              <option value="">{translate('history.sortDesc')}</option>
              <option value="start_asc">{translate('history.sortAsc')}</option>
            </select>
          </div>
          <div className={styles.searchItem}>
            <label>{translate('history.quickRange')}</label>
            <button className={styles.quickRangeBtn} onClick={() => handleQuickRange(24)}>{translate('history.quick24h')}</button>
            <button className={styles.quickRangeBtn} onClick={() => handleQuickRange(24 * 7)}>{translate('history.quick7d')}</button>
            <button className={styles.quickRangeBtn} onClick={() => handleQuickRange(24 * 30)}>{translate('history.quick30d')}</button>
          </div>
        </div>
      ) : null}

      {/* 回退模式的旧本地搜索框（/suite/history/list 数据可用） */}
      {mode === 'local' ? (
        <div className={styles.legacySearchBar}>
          <span className={styles.legacyTag}>{translate('history.localFallbackSearch')}</span>
          <input
            type="text"
            placeholder={translate('history.placeholder.testId')}
            value={legacySearch.id}
            onChange={(event) => {
              setLegacySearch((prev) => ({ ...prev, id: event.target.value }));
              setPage(1);
            }}
            className={styles.searchInput}
          />
          <input
            type="text"
            placeholder={translate('history.placeholder.dbVersion')}
            value={legacySearch.dbVersion}
            onChange={(event) => {
              setLegacySearch((prev) => ({ ...prev, dbVersion: event.target.value }));
              setPage(1);
            }}
            className={styles.searchInput}
          />
          <span className={styles.legacyHint}>{translate('history.localFallbackHint')}</span>
        </div>
      ) : null}

      {(showInitialLoading || queryLoading || queryError || notice || unknownOnly || mode === 'local') ? (
        <div className={styles.historyStatusRow}>
          {showInitialLoading ? <div className={styles.loadingBanner}>{initialLoadingText}</div> : null}
          {!showInitialLoading && queryLoading ? (
            <div className={styles.loadingBanner}>{translate('history.searching')}</div>
          ) : null}
          {mode === 'local' ? <div className={styles.modeHint}>{translate('history.localFallbackMode')}</div> : null}
          {unknownOnly ? (
            <div className={styles.modeHint}>{translate('history.unknownOnlyHint')}</div>
          ) : null}
          {notice ? <div className={styles.noticeBanner}>{notice}</div> : null}
          {queryError ? <div className={styles.errorBanner}>{queryError}</div> : null}
        </div>
      ) : null}

      <div className={styles.tableScroll}>
        <table className={styles.runsTable}>
          <thead>
            <tr>
              <th>{translate('history.table.runName')}</th>
              <th>{translate('history.startTime')}</th>
              <th>{translate('history.table.database')}</th>
              <th>{translate('history.table.workload')}</th>
              <th>{translate('history.table.orchestration')}</th>
              <th>{translate('history.table.fault')}</th>
              <th>{translate('history.table.metrics')}</th>
              <th>{translate('history.table.artifacts')}</th>
              <th>{translate('history.table.labels')}</th>
              <th>{translate('history.table.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {displayRows.length === 0 ? (
              <tr>
                <td colSpan={10} style={{ textAlign: 'center', padding: '16px' }}>
                  {queryLoading ? translate('history.loadingShort') : translate('history.noRecords')}
                </td>
              </tr>
            ) : (
              displayRows.map((run, index) => {
                const statusKey = String(run.status || '').toLowerCase();
                const workloadParamsText = formatParams(run.workloadParams);
                return (
                  <tr key={run.id}>
                    <td>
                      <button
                        className={`${styles.runNameButton} ${selectedRun?.id === run.id ? styles.activeRunNameButton : ''}`}
                        onClick={() => handleRunNameClick(run)}
                        title={run.historyName}
                      >
                        {run.historyName}
                      </button>
                      <div className={styles.runCellMeta}>
                        <span className={`${styles.statusBadge} ${styles[statusKey] || ''}`}>
                          {getStatusLabel(run.status, translate)}
                        </span>
                        {run.autoTimingMode ? (
                          <span className={styles.cellSecondary}>{translate('history.autoTiming')}{run.autoTimingMode}</span>
                        ) : null}
                      </div>
                    </td>
                    <td>
                      <div>{run.startTime}</div>
                      {run.elapsedMs ? (
                        <div className={styles.cellSecondary}>{translate('history.elapsed')} {formatDuration(run.elapsedMs)}</div>
                      ) : null}
                    </td>
                    <td>
                      <div>
                        {run.dbType || '-'}
                        {run.dbVersion ? ` · ${run.dbVersion}` : ''}
                      </div>
                      {run.dbKey ? (
                        <div className={styles.cellSecondary} title={run.dbKey}>{run.dbKey}</div>
                      ) : null}
                    </td>
                    <td>
                      <div>{run.workload || '-'}</div>
                      {workloadParamsText ? (
                        <div className={styles.cellSecondary} title={workloadParamsText}>{workloadParamsText}</div>
                      ) : null}
                    </td>
                    <td>
                      <div>{run.orchestrationId || '-'}</div>
                      {run.source ? <span className={styles.sourceBadge}>{sourceLabel(run.source, translate)}</span> : null}
                    </td>
                    <td>
                      {run.faults.length === 0 ? (
                        <span className={styles.cellMuted}>{translate('history.noFaults')}</span>
                      ) : (
                        <div className={styles.faultList}>
                          <span className={styles.faultCount}>{translate('history.faultCount', { count: run.faults.length })}</span>
                          {run.faults.slice(0, 3).map((fault, faultIndex) => {
                            const meta = verificationMeta(fault.verification);
                            return (
                              <span
                                key={`${fault.nameKey || fault.faultName || 'fault'}-${faultIndex}`}
                                className={styles.faultChip}
                                title={faultTitle(fault, translate, lang)}
                              >
                                <span className={styles.faultName}>
                                  {resolveFaultDisplayName(fault, translate, lang)
                                    || fault.nameKey
                                    || `#${faultIndex + 1}`}
                                </span>
                                <span
                                  className={styles.verifChip}
                                  style={{ color: meta.color, background: meta.bg, borderColor: meta.border }}
                                >
                                  {translate(meta.textKey)}
                                </span>
                              </span>
                            );
                          })}
                          {run.faults.length > 3 ? (
                            <span className={styles.cellMuted}>+{run.faults.length - 3}</span>
                          ) : null}
                        </div>
                      )}
                    </td>
                    <td>
                      {run.metrics ? (
                        <div className={styles.metricGrid}>
                          <span>{translate('history.metric.stability')} <b>{formatMetric(run.metrics.stability)}</b></span>
                          <span>{translate('history.metric.resistance')} <b>{formatMetric(run.metrics.defence)}</b></span>
                          <span>{translate('history.metric.recovery')} <b>{formatMetric(run.metrics.recovery)}</b></span>
                          <span>RTO <b>{formatMetric(run.metrics.rto)}</b></span>
                        </div>
                      ) : (
                        <span className={styles.cellMuted}>--</span>
                      )}
                    </td>
                    <td>
                      {run.artifacts ? (
                        <>
                          <div className={styles.artifactRow}>
                            {ARTIFACT_DEFS.map((def) => (
                              <span
                                key={def.key}
                                className={`${styles.artifactChip} ${run.artifacts[def.key] ? styles.chipOn : styles.chipOff}`}
                              >
                                {def.label}
                              </span>
                            ))}
                            {run.artifacts.complete ? (
                              <span className={`${styles.artifactChip} ${styles.chipComplete}`}>{translate('history.artifact.complete')}</span>
                            ) : null}
                          </div>
                          {run.artifacts.sizeBytes ? (
                            <div className={styles.cellSecondary}>{translate('history.totalSize', { size: formatBytes(run.artifacts.sizeBytes) })}</div>
                          ) : null}
                        </>
                      ) : (
                        <span className={styles.cellMuted}>--</span>
                      )}
                    </td>
                    <td>
                      {run.labels.length === 0 && !run.note ? (
                        <span className={styles.cellMuted}>--</span>
                      ) : (
                        <div className={styles.labelRow}>
                          {run.labels.map((label) => (
                            <span key={label} className={styles.labelTag}>{label}</span>
                          ))}
                          {run.note ? <span className={styles.noteTag} title={run.note}>📝</span> : null}
                        </div>
                      )}
                    </td>
                    <td
                      className={styles.actionsCell}
                      data-tour={index === 0 ? 'history-report-btn' : undefined}
                    >
                      <button
                        className={`${styles.operationBtn} ${styles.view}`}
                        onClick={() => setReportModalRun(run)}
                      >
                        {translate('history.download')}
                      </button>
                      <button
                        className={`${styles.operationBtn} ${styles.monitor}`}
                        onClick={() => setMonitorModalRun(run)}
                        title={translate('history.monitorDataHint')}
                      >
                        {translate('history.monitorData')}
                      </button>
                      <button
                        className={`${styles.operationBtn} ${styles.archive}`}
                        onClick={() => {
                          setLabelError('');
                          setLabelTarget(run);
                        }}
                      >
                        {translate('history.labelsNote')}
                      </button>
                      <button
                        className={`${styles.operationBtn} ${styles.export}`}
                        onClick={() => {
                          setExportError('');
                          setExportTarget(run);
                        }}
                      >
                        {translate('history.exportZip')}
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {totalRecords > 0 && (
        <div className={styles.paginationContainer}>
          <Pagination
            current={Math.min(page, pageCount)}
            pageSize={size}
            total={totalRecords}
            onChange={(nextPage, nextPageSize) => {
              if (nextPageSize && nextPageSize !== size) {
                setSize(nextPageSize);
                setPage(1);
              } else {
                setPage(nextPage);
              }
            }}
            showSizeChanger
            pageSizeOptions={PAGE_SIZE_OPTIONS}
            showQuickJumper
            showTotal={(total) => translate('history.paginationTotal', { total })}
          />
        </div>
      )}

      <div className={styles.totalInfo}>
        {translate('history.total')} {totalRecords} {translate('history.records')}
      </div>

      {selectedRun ? (
        <RunChartsModal
          run={selectedRun}
          scoresLoading={scoresLoading}
          scoresError={scoresError}
          scores={displayScores}
          monitorData={selectedMonitorData}
          faultInjectInfo={selectedFaultInjectInfo}
          triangles={selectedTriangles}
          faultOptions={groupedFaultOptions}
          selectedFaultIdx={selectedFaultIdx}
          onSelectFaultIdx={setSelectedFaultIdx}
          onClose={() => setSelectedRun(null)}
        />
      ) : null}

      {monitorModalRun ? (
        <MonitoringDataModal
          run={monitorModalRun}
          onClose={() => setMonitorModalRun(null)}
        />
      ) : null}

      {reportModalRun ? (
        <ReportPreview
          run={reportModalRun}
          radarScores={reportScores}
          scoresLoading={reportLoading}
          reportResult={reportResult}
          reportMonitorData={reportMonitorData}
          reportTriangles={reportTriangles}
          reportFaultInjectInfo={reportFaultInjectInfo}
          onClose={() => setReportModalRun(null)}
        />
      ) : null}

      {labelTarget ? (
        <LabelDialog
          run={labelTarget}
          saving={labelSaving}
          error={labelError}
          onCancel={() => setLabelTarget(null)}
          onSubmit={handleSaveLabels}
        />
      ) : null}

      {exportTarget ? (
        <ExportDialog
          run={exportTarget}
          exporting={exporting}
          error={exportError}
          onCancel={() => setExportTarget(null)}
          onSubmit={handleExport}
        />
      ) : null}
    </div>
  );
}

export default HistoryTable;