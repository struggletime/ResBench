import { Alert, Button, message, Modal, Select, Space, Switch, Tag } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import {
  CONTAINER_DEFAULT_METRIC_IDS,
  deleteMonitoringHistory,
  fetchMonitoringHistory,
  fetchMonitoringHistorySeries,
} from '../../api/monitoringApi';
import { fetchFaultInjectInfo } from '../../api/suiteApi';
import {
  clearMonitoringRun,
  setFaultRegionsForRun,
  setMonitoringEnabled,
  setMonitoringHistory,
  setSelectedMetricIds,
  setSelectedRunId,
  setSelectedTargets,
  upsertMonitoringSeries,
} from '../../store/modules/observabilitySlice';
import MetricChart from './MetricChart';
import MetricSelector from './MetricSelector';
import { useLanguage } from '../../i18n';
import { localizeBackendText } from '../../utils/backendMessages';
import styles from './MonitoringPage.module.css';

const HOST_CATEGORY_ORDER = ['host', 'database', 'ebpf'];
const CONTAINER_CATEGORY_ORDER = ['container', 'host', 'database'];

const MonitoringPage = () => {
  const dispatch = useDispatch();
  const { translate, lang } = useLanguage();
  const [selectorOpen, setSelectorOpen] = useState(false);
  const [messageApi, contextHolder] = message.useMessage();
  const observability = useSelector((state) => state.observability);
  const { currentRunId, currentContainerName, hasTestStarted } = useSelector((state) => state.testRun);
  const liveFaultRegions = useSelector((state) => state.monitor?.faultRegions || []);

  const selectedRunId = observability.selectedRunId || currentRunId || observability.history[0]?.runId || '';
  // 实时轮次的注入窗口由 MonitorPoller 维护；历史轮次（含命令行批量跑的）按 runId 单独取
  const usingLiveRun = Boolean(currentRunId) && selectedRunId === currentRunId && hasTestStarted;
  const faultRegionsByRunId = observability.faultRegionsByRunId;
  const faultRegions = usingLiveRun
    ? liveFaultRegions
    : (faultRegionsByRunId?.[selectedRunId] || []);
  const selectedSession = observability.sessionsByRunId[selectedRunId]
    || observability.history.find((item) => item.runId === selectedRunId);
  const sessionMetricIds = selectedSession?.metricIds;
  // 历史记录以会话里存的容器名为准；只有看当前运行时才回退到本次启动快照。
  const containerName = selectedSession?.containerName
    || (selectedRunId === currentRunId ? currentContainerName : '')
    || '';
  const containerMode = Boolean(containerName);
  const displayMetricIds = useMemo(() => {
    const base = sessionMetricIds?.length ? sessionMetricIds : observability.selectedMetricIds;
    if (!containerMode || base.some((metricId) => metricId.startsWith('container.'))) {
      return base;
    }
    return Array.from(new Set([...base, ...CONTAINER_DEFAULT_METRIC_IDS]));
  }, [containerMode, observability.selectedMetricIds, sessionMetricIds]);

  const metricMap = useMemo(() => {
    const map = new Map();
    (observability.catalog || []).forEach((category) =>
      (category.subcategories || []).forEach((subcategory) =>
        (subcategory.metrics || []).forEach((metric) => map.set(metric.metricId, metric))));
    return map;
  }, [observability.catalog]);

  const groupedMetrics = useMemo(() => {
    const categoryOrder = containerMode ? CONTAINER_CATEGORY_ORDER : HOST_CATEGORY_ORDER;
    return categoryOrder.map((categoryId) => ({
      categoryId,
      categoryName: categoryId === 'host' ? translate('monitoringPage.category.host')
        : categoryId === 'database' ? translate('monitoringPage.category.database')
          : categoryId === 'container' ? translate('monitoringPage.category.container') : 'eBPF',
      metrics: displayMetricIds
        .map((metricId) => metricMap.get(metricId))
        .filter((metric) => metric?.category === categoryId),
    })).filter((group) => group.metrics.length > 0);
  }, [containerMode, displayMetricIds, metricMap, translate]);

  useEffect(() => {
    if (!observability.selectedRunId && selectedRunId) dispatch(setSelectedRunId(selectedRunId));
  }, [dispatch, observability.selectedRunId, selectedRunId]);

  // 历史记录可能没有监控归档（部署监控前的旧测试），404 属于预期情况，静默处理
  const isNotFound = (error) => String(error?.message || '').includes('not found');

  useEffect(() => {
    if (!selectedRunId
      || (selectedRunId === currentRunId && hasTestStarted)
      || observability.seriesByRunId[selectedRunId]) return;
    let cancelled = false;
    fetchMonitoringHistorySeries(selectedRunId)
      .then((response) => {
        if (!cancelled) dispatch(upsertMonitoringSeries(response));
      })
      .catch((error) => {
        if (!cancelled && !isNotFound(error)) messageApi.error(error.message || translate('monitoringPage.loadFailed'));
      });
    return () => { cancelled = true; };
  }, [currentRunId, dispatch, hasTestStarted, messageApi, observability.seriesByRunId, selectedRunId, translate]);

  // 故障窗口不在监控归档里：看历史轮次时按 runId 去引擎取一次注入记录（批量跑的结果也走这里）。
  // 查不到就留空 —— 曲线照常展示，只是没有红色阴影。
  useEffect(() => {
    if (!selectedRunId || usingLiveRun) return undefined;
    if (faultRegionsByRunId
      && Object.prototype.hasOwnProperty.call(faultRegionsByRunId, selectedRunId)) return undefined;
    let cancelled = false;
    fetchFaultInjectInfo(selectedRunId)
      .then((info) => {
        if (cancelled) return;
        const regions = (Array.isArray(info) ? info : [])
          .filter((item) => typeof item.startTime === 'number' && item.startTime > 1e9)
          .map((item) => ({
            faultID: item.faultID,
            faultName: item.faultName,
            startTime: item.startTime,
            endTime: typeof item.endTime === 'number' && item.endTime > 1e9 ? item.endTime : 0,
            groupKey: item.groupKey || '',
          }));
        dispatch(setFaultRegionsForRun({ runId: selectedRunId, regions }));
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [dispatch, faultRegionsByRunId, selectedRunId, usingLiveRun]);

  const handleRunChange = async (runId) => {
    dispatch(setSelectedRunId(runId));
    if (runId && (runId !== currentRunId || !hasTestStarted)) {
      try {
        const response = await fetchMonitoringHistorySeries(runId);
        dispatch(upsertMonitoringSeries(response));
      } catch (error) {
        if (!isNotFound(error)) messageApi.error(error.message || translate('monitoringPage.loadFailed'));
      }
    }
  };

  const handleDelete = () => {
    if (!selectedSession || ['ACTIVE', 'PENDING', 'ARMING', 'FINALIZING'].includes(selectedSession.status)) return;
    Modal.confirm({
      title: translate('monitoringPage.deleteTitle'),
      content: translate('monitoringPage.deleteContent', { runId: selectedRunId }),
      okText: translate('common.delete'),
      okButtonProps: { danger: true },
      cancelText: translate('common.cancel'),
      onOk: async () => {
        await deleteMonitoringHistory(selectedRunId);
        dispatch(clearMonitoringRun(selectedRunId));
        dispatch(setMonitoringHistory(await fetchMonitoringHistory()));
        messageApi.success(translate('monitoringPage.deleted'));
      },
    });
  };

  const status = selectedSession?.status || (hasTestStarted ? translate('monitoringPage.status.preparing') : translate('monitoringPage.status.noTest'));
  const dataStatus = observability.error
    ? translate('monitoringPage.dataStatus.partial')
    : observability.lastUpdatedAt
      ? translate('monitoringPage.dataStatus.normal')
      : translate('monitoringPage.dataStatus.waiting');
  const seriesForRun = observability.seriesByRunId[selectedRunId] || {};
  const deleteDisabled = !selectedSession || ['ACTIVE', 'PENDING', 'ARMING', 'FINALIZING'].includes(selectedSession.status);

  return (
    <div className={styles.page}>
      {contextHolder}
      <div className={styles.toolbar}>
        <Space wrap size="middle">
          <Space>
            <span>{translate('monitoringPage.enable')}</span>
            <Switch
              checked={observability.enabled}
              onChange={(checked) => dispatch(setMonitoringEnabled(checked))}
            />
          </Space>
          <Select
            mode="multiple"
            style={{ minWidth: 210 }}
            placeholder={translate('monitoringPage.targetPlaceholder')}
            value={observability.selectedTargets}
            options={(observability.capabilities?.targets || []).map((target) => ({
              value: target.targetId,
              label: target.displayName,
            }))}
            onChange={(value) => dispatch(setSelectedTargets(value))}
          />
          <Button onClick={() => setSelectorOpen(true)}>
            {translate('monitoringPage.selectMetrics', { count: observability.selectedMetricIds.length })}
          </Button>
          <Button type="primary" onClick={() => messageApi.success(translate('monitoringPage.configSaved'))}>
            {translate('monitoringPage.save')}
          </Button>
          <Select
            style={{ minWidth: 230 }}
            placeholder={translate('monitoringPage.runPlaceholder')}
            value={selectedRunId || undefined}
            onChange={handleRunChange}
            options={[
              ...(currentRunId ? [{
                value: currentRunId,
                label: `${currentRunId}${hasTestStarted ? translate('monitoringPage.runningSuffix') : ''}`,
              }] : []),
              ...observability.history
                .filter((item) => item.runId !== currentRunId)
                .map((item) => ({ value: item.runId, label: item.runId })),
            ]}
          />
          <Button danger disabled={deleteDisabled} onClick={handleDelete}>
            {translate('monitoringPage.deleteData')}
          </Button>
        </Space>
        <div className={styles.statusLine}>
          {translate('monitoringPage.runLabel')}{selectedRunId || '-'} <Tag>{status}</Tag>{' '}
          {translate('monitoringPage.dataLabel')}<Tag color={observability.error ? 'orange' : 'green'}>{dataStatus}</Tag>
          {containerMode && <Tag color="blue">{translate('monitoringPage.containerLabel')}{containerName}</Tag>}
        </div>
      </div>

      {/* 会话建不起来时（指标超限、指标不支持、manager 拒绝……）只有这里能看到原始原因。
          以前只把状态标成「部分缺失」，用户根本不知道是哪个请求失败了。 */}
      {observability.error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 18 }}
          message={translate('monitoringPage.errorTitle')}
          description={localizeBackendText(observability.error, translate, lang)}
        />
      ) : null}

      {!observability.enabled && !selectedSession ? (
        <div className={styles.placeholder}>{translate('monitoringPage.emptyDisabled')}</div>
      ) : groupedMetrics.length === 0 ? (
        <div className={styles.placeholder}>{translate('monitoringPage.emptyNoMetrics')}</div>
      ) : (
        groupedMetrics.map((group) => (
          <section key={group.categoryId} className={styles.categorySection}>
            <h3>{group.categoryName}</h3>
            <div className={styles.chartGrid}>
              {group.metrics.map((metric) => (
                <MetricChart
                  key={metric.metricId}
                  metric={metric}
                  series={seriesForRun[metric.metricId] || []}
                  faultRegions={faultRegions}
                />
              ))}
            </div>
          </section>
        ))
      )}

      <MetricSelector
        key={selectorOpen ? 'open' : 'closed'}
        open={selectorOpen}
        catalog={observability.catalog}
        selected={observability.selectedMetricIds}
        maxMetrics={observability.capabilities?.maxMetrics}
        containerExtraIds={currentContainerName ? CONTAINER_DEFAULT_METRIC_IDS : []}
        onCancel={() => setSelectorOpen(false)}
        onConfirm={(value) => {
          dispatch(setSelectedMetricIds(value));
          setSelectorOpen(false);
        }}
      />
    </div>
  );
};

export default MonitoringPage;
