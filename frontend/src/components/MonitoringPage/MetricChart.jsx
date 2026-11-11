import ReactECharts from 'echarts-for-react';
import { Empty } from 'antd';
import { useLanguage } from '../../i18n';
import { resolveFaultDisplayName } from '../../config/faultConfig';
import { monitoringMetricName } from '../../utils/monitoringI18n';
import styles from './MonitoringPage.module.css';

// 伪文件系统挂载点不是真实磁盘占用：proc/sysfs/cgroup 这类 size=0 的挂载点会被
// (1 - avail/size) 算成恒定 100%，tmpfs 恒定 0%，叠在图上就是一堆无意义的平线。
// 后端 PromQL 已加 fstype 过滤，这里再兜一层，历史归档里已经采集到的旧数据也能过滤掉。
const PSEUDO_FSTYPES = new Set([
  'tmpfs', 'devtmpfs', 'overlay', 'proc', 'sysfs', 'cgroup', 'cgroup2', 'debugfs',
  'tracefs', 'securityfs', 'pstore', 'configfs', 'fusectl', 'mqueue', 'hugetlbfs',
  'devpts', 'rpc_pipefs', 'binfmt_misc', 'autofs', 'squashfs', 'ramfs', 'nsfs',
]);
// 同一 target 上恒定不变的标签，放进图例只会把真正有用的挂载点/设备名挤掉。
const NOISE_LABELS = new Set(['instance', 'job', 'role', 'service', 'target_id']);

const isPseudoFilesystem = (series) => series.metricId === 'host.disk.usage'
  && PSEUDO_FSTYPES.has(String(series.labels?.fstype || ''));

function seriesName(series, index) {
  const labels = series.labels || {};
  const parts = [];
  if (labels.mountpoint) {
    parts.push(labels.mountpoint);
  }
  Object.entries(labels)
    .filter(([key]) => key !== 'mountpoint' && !NOISE_LABELS.has(key))
    .forEach(([key, value]) => parts.push(`${key}=${value}`));
  return parts.join(', ') || series.targetId || `series-${index + 1}`;
}

const MetricChart = ({ metric, series, faultRegions = [] }) => {
  const { translate, lang } = useLanguage();
  const metricName = monitoringMetricName(metric, translate);
  const drawable = (series || []).filter((item) => (
    Array.isArray(item.points) && item.points.length > 0 && !isPseudoFilesystem(item)
  ));

  if (drawable.length === 0) {
    return (
      <section className={styles.chartCard}>
        <div className={styles.chartHeader}>
          <span>{metricName}</span>
          <span className={styles.unit}>{metric.unit}</span>
        </div>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={translate('metricChart.noData')} />
      </section>
    );
  }

  // 历史归档里故障可能没写 endTime（注入中/注入没恢复）：画到曲线的最后一个点，
  // 不要用 Date.now() —— 那会把几周前的一轮拉长到"现在"。
  const lastX = drawable.reduce((latest, item) => {
    const last = item.points[item.points.length - 1];
    return Math.max(latest, Number(last?.[0]) || 0);
  }, 0);

  const option = {
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 4 },
    grid: { left: 55, right: 24, top: 38, bottom: 50 },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', name: metric.unit, scale: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 8 }],
    toolbox: { right: 8, feature: { saveAsImage: { title: translate('metricChart.export') } } },
    series: drawable.map((item, index) => ({
      name: seriesName(item, index),
      type: 'line',
      showSymbol: false,
      connectNulls: false,
      data: item.points,
      markArea: index === 0 && faultRegions.length > 0 ? {
        silent: true,
        itemStyle: { color: 'rgba(255, 99, 71, 0.12)' },
        data: faultRegions
          .filter((region) => region.startTime)
          .map((region) => {
            const end = region.endTime && region.endTime > region.startTime
              ? region.endTime
              : Math.max(region.startTime, lastX);
            return [
              {
                name: resolveFaultDisplayName(region, translate, lang) || translate('metricChart.faultFallback'),
                xAxis: region.startTime,
              },
              { xAxis: end },
            ];
          }),
      } : undefined,
    })),
  };

  return (
    <section className={styles.chartCard}>
      <div className={styles.chartHeader}>
        <span>{metricName}</span>
        <span className={styles.unit}>{metric.unit}</span>
      </div>
      <ReactECharts option={option} style={{ height: 300 }} notMerge={false} lazyUpdate />
    </section>
  );
};

export default MetricChart;
