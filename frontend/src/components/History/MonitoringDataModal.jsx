import { useEffect, useMemo, useState } from 'react';
import styles from './HistoryTable.module.css';
import MetricChart from '../MonitoringPage/MetricChart';
import { fetchMonitoringCatalog, fetchMonitoringHistorySeries } from '../../api/monitoringApi';
import { fetchFaultInjectInfo } from '../../api/suiteApi';
import { useLanguage } from '../../i18n';
import { monitoringMetricName } from '../../utils/monitoringI18n';

/**
 * 历史记录里的「监控数据」弹窗：把这个 runId 归档下来的 Prometheus 曲线直接摊开。
 *
 * 数据来自 /monitoring/history/{runId}/series（manager 侧的监控归档），不是引擎的
 * monitor.csv —— 前者是故障窗口判定用的同一份数据，看的和判的才是同一件事。
 * 老运行可能压根没有监控归档（部署监控之前跑的，或批量跑时监控不可用），
 * 那种情况接口返回 404，这里只提示，不报错。
 */
const CATEGORY_ORDER = ['container', 'host', 'database', 'ebpf'];
const CATEGORY_NAME_KEYS = {
  container: 'monitoringPage.category.container',
  host: 'monitoringPage.category.host',
  database: 'monitoringPage.category.database',
  ebpf: 'monitoringPage.category.ebpf',
};

// 指标目录只用来把 metricId 翻成中文名和单位；取不到就退回 metricId，不影响出图。
// 一次取到就一直用（进程内缓存），失败不缓存，下次打开再试。
let catalogCache = null;
function loadCatalog() {
  if (!catalogCache) {
    catalogCache = fetchMonitoringCatalog()
      .then((data) => {
        const map = new Map();
        // /monitoring/catalog 返回的是 { categories: [...] }，不是数组。
        (data?.categories || []).forEach((category) =>
          (category.subcategories || []).forEach((subcategory) =>
            (subcategory.metrics || []).forEach((metric) => map.set(metric.metricId, metric))));
        return map;
      })
      .catch(() => {
        catalogCache = null;
        return new Map();
      });
  }
  return catalogCache;
}

function categoryOf(metricId, metric) {
  if (metric?.category) return metric.category;
  if (metricId.startsWith('container.')) return 'container';
  if (metricId.startsWith('db.')) return 'database';
  if (metricId.startsWith('ebpf.') || metricId.startsWith('syscall.')) return 'ebpf';
  return 'host';
}

const MonitoringDataModal = ({ run, onClose }) => {
  const { lang, translate } = useLanguage();
  const isZh = lang !== 'en';
  const runId = run?.runId || run?.historyName || '';
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [archive, setArchive] = useState(null);
  const [metricMeta, setMetricMeta] = useState(() => new Map());
  const [selected, setSelected] = useState([]);
  const [faultRegions, setFaultRegions] = useState([]);

  useEffect(() => {
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    if (!runId) return undefined;
    let cancelled = false;
    setLoading(true);
    setError('');
    setArchive(null);
    setSelected([]);
    setFaultRegions([]);

    Promise.all([
      fetchMonitoringHistorySeries(runId),
      loadCatalog(),
      // 注入窗口只用来在曲线上打红色阴影；取不到就画纯曲线。
      fetchFaultInjectInfo(runId).catch(() => []),
    ])
      .then(([data, catalog, faults]) => {
        if (cancelled) return;
        const series = Array.isArray(data?.series) ? data.series : [];
        setArchive(data || null);
        setMetricMeta(catalog);
        setSelected(Array.from(new Set(series.map((item) => item.metricId))));
        setFaultRegions(
          (Array.isArray(faults) ? faults : [])
            .filter((item) => typeof item.startTime === 'number' && item.startTime > 1e9)
            .map((item) => ({
              faultID: item.faultID,
              faultName: item.faultName,
              startTime: item.startTime,
              endTime: typeof item.endTime === 'number' && item.endTime > 1e9 ? item.endTime : 0,
            })),
        );
        setLoading(false);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(String(err?.message || err || ''));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [runId]);

  // 一个指标可能对应多条序列（同一 metricId 分不同挂载点/网卡），按 metricId 归堆。
  const seriesByMetric = useMemo(() => {
    const map = new Map();
    (archive?.series || []).forEach((item) => {
      if (!map.has(item.metricId)) map.set(item.metricId, []);
      map.get(item.metricId).push(item);
    });
    return map;
  }, [archive]);

  const metrics = useMemo(() => {
    return Array.from(seriesByMetric.entries())
      .map(([metricId, series]) => {
        const meta = metricMeta.get(metricId);
        return {
          metricId,
          name: monitoringMetricName({ ...meta, metricId }, translate),
          unit: meta?.unit || series[0]?.unit || '',
          category: categoryOf(metricId, meta),
          seriesCount: series.length,
          pointCount: series.reduce((sum, item) => sum + (item.points?.length || 0), 0),
        };
      })
      .sort((left, right) => {
        const byCategory = CATEGORY_ORDER.indexOf(left.category) - CATEGORY_ORDER.indexOf(right.category);
        return byCategory !== 0 ? byCategory : left.name.localeCompare(right.name);
      });
  }, [metricMeta, seriesByMetric, translate]);

  const groups = useMemo(() => {
    const byCategory = new Map();
    metrics.forEach((metric) => {
      if (!byCategory.has(metric.category)) byCategory.set(metric.category, []);
      byCategory.get(metric.category).push(metric);
    });
    return Array.from(byCategory.entries()).map(([categoryId, list]) => ({
      categoryId,
      categoryName: CATEGORY_NAME_KEYS[categoryId] ? translate(CATEGORY_NAME_KEYS[categoryId]) : categoryId,
      metrics: list,
    }));
  }, [metrics, translate]);

  const totalPoints = useMemo(
    () => metrics.reduce((sum, metric) => sum + metric.pointCount, 0),
    [metrics],
  );

  const toggleMetric = (metricId) => {
    setSelected((prev) => (
      prev.includes(metricId)
        ? prev.filter((item) => item !== metricId)
        : [...prev, metricId]
    ));
  };

  if (!run) return null;

  const visibleCount = metrics.filter((metric) => selected.includes(metric.metricId)).length;

  return (
    <div className={styles.chartModalOverlay} onClick={onClose}>
      <div
        className={styles.chartModal}
        role="dialog"
        aria-modal="true"
        aria-label={isZh ? '监控数据' : 'Monitoring data'}
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles.chartModalHeader}>
          <div>
            <h2>{isZh ? '监控数据' : 'Monitoring Data'}</h2>
            <p className={styles.chartModalSub}>{run.historyName || runId}</p>
          </div>
          <button type="button" className={styles.chartModalClose} onClick={onClose}>
            {isZh ? '关闭' : 'Close'}
          </button>
        </div>

        <div className={styles.chartModalBody}>
          {loading ? (
            <div className={styles.monitorHint}>
              {isZh ? '监控曲线加载中…' : 'Loading monitoring series…'}
            </div>
          ) : error ? (
            <div className={`${styles.monitorHint} ${styles.monitorHintError}`}>
              <div>{isZh ? '这一轮没有可用的监控归档。' : 'No monitoring archive for this run.'}</div>
              <div className={styles.monitorHintDetail}>
                {isZh
                  ? '可能是部署监控之前跑的，或者批量运行时监控不可用。原始提示：'
                  : 'It may predate monitoring, or monitoring was unavailable during the batch. Details: '}
                {error}
              </div>
            </div>
          ) : metrics.length === 0 ? (
            <div className={styles.monitorHint}>
              {isZh ? '归档里没有曲线数据。' : 'The archive contains no series.'}
            </div>
          ) : (
            <>
              <div className={styles.monitorToolbar}>
                <span className={styles.monitorSummary}>
                  {isZh
                    ? `共 ${metrics.length} 个指标 · ${totalPoints} 个采样点 · 已选 ${visibleCount} 个`
                    : `${metrics.length} metrics · ${totalPoints} points · ${visibleCount} selected`}
                </span>
                <div className={styles.monitorActions}>
                  <button
                    type="button"
                    className={styles.monitorAction}
                    onClick={() => setSelected(metrics.map((metric) => metric.metricId))}
                  >
                    {isZh ? '全选' : 'Select all'}
                  </button>
                  <button
                    type="button"
                    className={styles.monitorAction}
                    onClick={() => setSelected([])}
                  >
                    {isZh ? '清空' : 'Clear'}
                  </button>
                </div>
              </div>

              <div className={styles.monitorChips}>
                {metrics.map((metric) => {
                  const active = selected.includes(metric.metricId);
                  return (
                    <button
                      key={metric.metricId}
                      type="button"
                      className={`${styles.monitorChip} ${active ? styles.monitorChipOn : ''}`}
                      onClick={() => toggleMetric(metric.metricId)}
                      title={metric.metricId}
                    >
                      {metric.name}
                      {metric.seriesCount > 1 ? (
                        <span className={styles.monitorChipCount}>×{metric.seriesCount}</span>
                      ) : null}
                    </button>
                  );
                })}
              </div>

              {visibleCount === 0 ? (
                <div className={styles.monitorHint}>
                  {isZh ? '请选择要展示的指标。' : 'Select at least one metric.'}
                </div>
              ) : (
                groups.map((group) => {
                  const visible = group.metrics.filter((metric) => selected.includes(metric.metricId));
                  if (visible.length === 0) return null;
                  return (
                    <section key={group.categoryId} className={styles.monitorCategory}>
                      <h3>{group.categoryName}</h3>
                      <div className={styles.monitorChartGrid}>
                        {visible.map((metric) => (
                          <MetricChart
                            key={metric.metricId}
                            metric={metric}
                            series={seriesByMetric.get(metric.metricId) || []}
                            faultRegions={faultRegions}
                          />
                        ))}
                      </div>
                    </section>
                  );
                })
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default MonitoringDataModal;
