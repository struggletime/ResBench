import React, { useEffect, useMemo, useRef } from 'react';
import * as echarts from 'echarts';
import { useLanguage } from '../../i18n';
import styles from './ComparisonRadarChart.module.css';

const RADAR_METRIC_KEYS = [
  'throughput', 'latency', 'stability', 'resistance',
  'recovery', 'adaptability', 'deviation', 'disturbance',
];

const COLOR_POOL = ['#1890ff', '#52c41a', '#faad14', '#f5222d', '#722ed1'];

function getScoreValue(scores, metricKey) {
  if (!Array.isArray(scores)) return 0;
  const entry = scores.find((s) => s.metricKey === metricKey);
  return entry && Number.isFinite(entry.value) ? Math.round(entry.value) : 0;
}

const ComparisonRadarChart = ({ selectedRecords, recordsData = {}, dimension = 'database' }) => {
  const chartRef = useRef(null);
  const instanceRef = useRef(null);
  const exportFnRef = useRef(null);
  const { translate } = useLanguage();

  const metricLabels = useMemo(
    () => RADAR_METRIC_KEYS.map((key) => translate(`comparison.metrics.${key}`)),
    [translate],
  );

  const recordsWithScores = useMemo(
    () => selectedRecords.map((r) => ({
      ...r,
      data: recordsData[r.id],
      scores: recordsData[r.id]?.scores || null,
    })),
    [selectedRecords, recordsData],
  );

  useEffect(() => {
    if (!chartRef.current || selectedRecords.length < 2) return undefined;

    if (instanceRef.current) {
      instanceRef.current.dispose();
    }

    const chart = echarts.init(chartRef.current);
    instanceRef.current = chart;

    const getLegendName = (record) => {
      if (dimension === 'database') {
        return `${record.testName || record.id} · ${record.dbTypeVersion}`;
      }
      return record.testName || record.id;
    };

    const series = recordsWithScores.map((record, index) => {
      const scoreValues = record.scores
        ? RADAR_METRIC_KEYS.map((key) => getScoreValue(record.scores, key))
        : RADAR_METRIC_KEYS.map(() => 0);

      return {
        name: getLegendName(record),
        type: 'radar',
        data: [{ value: scoreValues, name: getLegendName(record) }],
        areaStyle: { opacity: 0.12 },
        lineStyle: { width: 2, color: COLOR_POOL[index % COLOR_POOL.length] },
        symbol: 'circle',
        symbolSize: 5,
        itemStyle: { color: COLOR_POOL[index % COLOR_POOL.length] },
      };
    });

    const option = {
      title: {
        text: translate('comparison.radar.overlay.title'),
        left: 'center',
        textStyle: { fontSize: 16 },
      },
      tooltip: { trigger: 'item' },
      legend: {
        data: series.map((item) => item.name),
        bottom: 8,
      },
      radar: {
        radius: '62%',
        indicator: metricLabels.map((name) => ({ name, max: 100 })),
        splitNumber: 5,
        axisName: { color: '#334155', fontSize: 12 },
        splitArea: { areaStyle: { color: ['#ffffff', '#f8fafc'] } },
      },
      series,
    };

    chart.setOption(option);

    exportFnRef.current = () => {
      if (chart.isDisposed()) return;
      const imgData = chart.getDataURL({
        type: 'png',
        pixelRatio: 2,
        backgroundColor: '#fff',
      });
      const link = document.createElement('a');
      link.download = `${translate('comparison.chart.export.filename')}_radar_${new Date().toLocaleDateString()}.png`;
      link.href = imgData;
      link.click();
    };

    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
    };
  }, [recordsWithScores, dimension, metricLabels, translate]);

  if (selectedRecords.length < 2) return null;

  const someLoading = recordsWithScores.some((r) => r.data?.loading);
  const someError = recordsWithScores.some((r) => r.data?.error);

  return (
    <div className={styles.container}>
      <h3 className={styles.title}>
        {translate('comparison.radar.sectionTitle')}
        {someLoading && (
          <span style={{ marginLeft: '12px', fontSize: '12px', color: '#94a3b8', fontWeight: 400 }}>
            {translate('comparison.messages.loadingScores')}
          </span>
        )}
        {someError && !someLoading && (
          <span style={{ marginLeft: '12px', fontSize: '12px', color: '#f5222d', fontWeight: 400 }}>
            {translate('comparison.messages.loadFailed')}
          </span>
        )}
      </h3>
      <div className={styles.chartWrapper}>
        <div ref={chartRef} className={styles.chart} />
        <button
          className={styles.exportButton}
          onClick={() => exportFnRef.current?.()}
        >
          {translate('comparison.chart.export.button')}
        </button>
      </div>
    </div>
  );
};

export default ComparisonRadarChart;
