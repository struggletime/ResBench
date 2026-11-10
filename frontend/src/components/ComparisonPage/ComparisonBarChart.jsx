import React, { useEffect, useRef, useMemo } from 'react';
import * as echarts from 'echarts';
import styles from './ComparisonBarChart.module.css';
import { useLanguage } from '../../i18n';

const BAR_METRIC_KEYS = [
  'throughput', 'latency', 'stability', 'resistance',
  'recovery', 'adaptability', 'deviation', 'disturbance',
];

const COLOR_POOL = ['#1890ff', '#52c41a', '#faad14', '#f5222d', '#722ed1'];

function getScoreValue(scores, metricKey) {
  if (!Array.isArray(scores)) return 0;
  const entry = scores.find((s) => s.metricKey === metricKey);
  return entry && Number.isFinite(entry.value) ? Math.round(entry.value) : 0;
}

const ComparisonBarChart = ({ selectedRecords, recordsData = {}, dimension = 'database' }) => {
  const chartRef = useRef(null);
  const instanceRef = useRef(null);
  const exportFnRef = useRef(null);
  const { translate } = useLanguage();

  const METRICS = useMemo(
    () => BAR_METRIC_KEYS.map((key) => translate(`comparison.metrics.${key}`)),
    [translate],
  );

  const recordsWithScores = useMemo(
    () => selectedRecords.map((r) => ({
      ...r,
      data: recordsData[r.id],
      scores: recordsData[r.id]?.scores || null,
      loading: recordsData[r.id]?.loading,
    })),
    [selectedRecords, recordsData],
  );

  useEffect(() => {
    if (!chartRef.current || selectedRecords.length === 0) return;

    if (instanceRef.current) {
      instanceRef.current.dispose();
    }

    const myChart = echarts.init(chartRef.current);
    instanceRef.current = myChart;

    const getLegendName = (record) => {
      if (dimension === 'database') {
        return `${record.testName || record.id} · ${record.dbTypeVersion}`;
      }
      return record.testName || record.id;
    };

    const maxValues = Array.from({ length: BAR_METRIC_KEYS.length }, () => 0);

    const seriesList = recordsWithScores.map((record, index) => {
      const scores = record.scores;
      const data = BAR_METRIC_KEYS.map((key, metricIndex) => {
        const value = scores ? getScoreValue(scores, key) : 0;
        if (value > maxValues[metricIndex]) maxValues[metricIndex] = value;
        return {
          value,
          itemStyle: { color: COLOR_POOL[index % COLOR_POOL.length] },
          label: {
            show: true,
            position: 'right',
            formatter: () => '', // set after maxValues computed
            fontSize: 16,
            fontWeight: 'bold',
            color: '#ff4d4f',
          },
        };
      });

      return {
        name: getLegendName(record),
        type: 'bar',
        data,
        emphasis: { focus: 'series' },
        itemStyle: { color: COLOR_POOL[index % COLOR_POOL.length] },
        label: {
          show: true,
          position: 'right',
          fontSize: 16,
          fontWeight: 'bold',
          color: '#ff4d4f',
          formatter: (params) => {
            const v = params.value?.value ?? params.value;
            return v === maxValues[params.dataIndex] && v > 0 ? '★' : '';
          },
        },
      };
    });

    const getTitle = () => {
      if (dimension === 'database') return translate('comparison.chart.title.database');
      if (dimension === 'workload') return translate('comparison.chart.title.workload');
      if (dimension === 'fault') return translate('comparison.chart.title.fault');
      return translate('comparison.chart.title.default');
    };

    const option = {
      title: {
        text: getTitle(),
        left: 'center',
        textStyle: { fontSize: 16 },
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params) => {
          let result = `${params[0]?.name || ''}<br/>`;
          params.forEach((param) => {
            const v = param.value?.value ?? param.value;
            result += `${param.marker} ${param.seriesName}: ${v}<br/>`;
          });
          return result;
        },
      },
      legend: {
        data: seriesList.map((s) => s.name),
        bottom: 10,
        itemGap: 20,
        textStyle: { fontSize: 12 },
      },
      xAxis: {
        type: 'value',
        min: 0,
        max: 100,
        interval: 20,
        nameLocation: 'middle',
        nameGap: 24,
        splitLine: { lineStyle: { type: 'dashed' } },
      },
      yAxis: {
        type: 'category',
        data: METRICS,
        nameLocation: 'middle',
        nameGap: 40,
        axisLabel: { fontSize: 12, fontWeight: 'bold' },
        axisTick: { alignWithLabel: true },
      },
      series: seriesList,
      grid: { left: 100, right: 30, top: 60, bottom: 60 },
      barGap: '30%',
      barCategoryGap: '50%',
    };

    myChart.setOption(option);

    exportFnRef.current = () => {
      if (myChart.isDisposed()) return;
      const imgData = myChart.getDataURL({
        type: 'png',
        pixelRatio: 2,
        backgroundColor: '#fff',
      });
      const link = document.createElement('a');
      link.download = `${translate('comparison.chart.export.filename')}_${new Date().toLocaleDateString()}.png`;
      link.href = imgData;
      link.click();
    };

    return () => { myChart.dispose(); };
  }, [recordsWithScores, dimension, METRICS, translate]);

  if (selectedRecords.length === 0) return null;

  const someLoading = recordsWithScores.some((r) => r.loading);
  const someError = recordsWithScores.some((r) => r.data?.error);

  return (
    <div className={styles.container}>
      <h3 className={styles.title}>
        {translate('comparison.chart.componentTitle')}
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

export default ComparisonBarChart;
