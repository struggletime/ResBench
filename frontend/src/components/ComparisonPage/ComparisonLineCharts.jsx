import React, { useEffect, useRef, useMemo } from 'react';
import * as echarts from 'echarts';
import styles from './ComparisonLineCharts.module.css';
import { useLanguage } from '../../i18n';

function getLegendName(record, dimension) {
  if (dimension === 'database') {
    return `${record.testName || record.id} · ${record.dbTypeVersion}`;
  }
  return record.testName || record.id;
}

const ComparisonLineCharts = ({ selectedRecords, recordsData = {}, dimension = 'database' }) => {
  const independentRefs = useRef([]);
  const overlayRef = useRef(null);
  const chartInstances = useRef([]);
  const overlayInstance = useRef(null);
  const { translate } = useLanguage();

  const recordsWithData = useMemo(
    () => selectedRecords.map((r) => ({
      ...r,
      data: recordsData[r.id],
      tpsData: recordsData[r.id]?.tpsData || [],
      loading: recordsData[r.id]?.loading,
    })),
    [selectedRecords, recordsData],
  );

  useEffect(() => {
    recordsWithData.forEach((record, index) => {
      const chartDom = independentRefs.current[index];
      if (!chartDom) return;

      if (chartInstances.current[index]) {
        chartInstances.current[index].dispose();
      }

      const tpsArray = record.tpsData;
      const chartData = tpsArray.length > 0
        ? tpsArray.map((d) => [d.time, d.throughput])
        : [];

      const myChart = echarts.init(chartDom);
      chartInstances.current[index] = myChart;

      const option = {
        title: {
          text: `${getLegendName(record, dimension)} ${translate('comparison.lineCharts.realtimeTps')}`,
          left: 'center',
          textStyle: { fontSize: 14 },
        },
        tooltip: {
          trigger: 'axis',
          formatter: (params) => {
            const param = params[0];
            if (!param?.value) return '';
            return `${param.value[0].toFixed(1)}s<br/>TPS: ${param.value[1].toFixed(2)}`;
          },
        },
        xAxis: {
          type: 'value',
          name: translate('comparison.lineCharts.timeAxis'),
          nameLocation: 'middle',
          nameGap: 24,
        },
        yAxis: {
          type: 'value',
          name: translate('comparison.lineCharts.tpsAxis'),
          nameLocation: 'middle',
          nameGap: 40,
        },
        series: [{
          type: 'line',
          data: chartData,
          showSymbol: false,
          smooth: false,
          lineStyle: { width: 1.5 },
          areaStyle: { opacity: 0.08 },
        }],
        grid: { left: 60, right: 30, top: 60, bottom: 60 },
      };

      if (!record.loading && chartData.length === 0) {
        myChart.showLoading('default', {
          text: translate('comparison.messages.noRealData'),
          maskColor: 'rgba(255, 255, 255, 0.85)',
        });
      }

      if (record.loading) {
        myChart.showLoading('default', {
          text: translate('comparison.messages.loading'),
          maskColor: 'rgba(255, 255, 255, 0.8)',
        });
      } else {
        myChart.hideLoading();
      }

      myChart.setOption(option);
    });

    return () => {
      chartInstances.current.forEach((inst) => { try { inst?.dispose(); } catch { /* ignore */ } });
      chartInstances.current = [];
    };
  }, [recordsWithData, translate, dimension]);

  useEffect(() => {
    if (!overlayRef.current) return;

    if (overlayInstance.current) {
      overlayInstance.current.dispose();
    }

    const myChart = echarts.init(overlayRef.current);
    overlayInstance.current = myChart;

    const colors = ['#1890ff', '#52c41a', '#faad14', '#f5222d', '#722ed1'];
    const seriesList = recordsWithData.map((record, index) => {
      const tpsArray = record.tpsData;
      const chartData = tpsArray.length > 0
        ? tpsArray.map((d) => [d.time, d.throughput])
        : [];
      return {
        name: getLegendName(record, dimension),
        type: 'line',
        data: chartData,
        showSymbol: false,
        smooth: false,
        lineStyle: { width: 2, color: colors[index % colors.length] },
      };
    });

    const option = {
      title: {
        text: translate('comparison.lineCharts.overlayTitle'),
        left: 'center',
        textStyle: { fontSize: 16 },
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          if (!params || params.length === 0) return '';
          let result = `${params[0].value?.[0]?.toFixed?.(1) ?? ''}s<br/>`;
          params.forEach((param) => {
            result += `${param.marker} ${param.seriesName}: ${param.value?.[1]?.toFixed?.(2) ?? '-'}<br/>`;
          });
          return result;
        },
      },
      legend: {
        data: seriesList.map((s) => s.name),
        bottom: 10,
      },
      xAxis: {
        type: 'value',
        name: translate('comparison.lineCharts.timeAxis'),
        nameLocation: 'middle',
        nameGap: 24,
      },
      yAxis: {
        type: 'value',
        name: translate('comparison.lineCharts.tpsAxis'),
        nameLocation: 'middle',
        nameGap: 40,
      },
      series: seriesList,
      grid: { left: 60, right: 30, top: 60, bottom: 60 },
    };

    myChart.setOption(option);
    const hasAnyData = seriesList.some((series) => Array.isArray(series.data) && series.data.length > 0);
    if (!hasAnyData) {
      myChart.showLoading('default', {
        text: translate('comparison.messages.noRealData'),
        maskColor: 'rgba(255, 255, 255, 0.85)',
      });
    } else {
      myChart.hideLoading();
    }

    return () => {
      try { overlayInstance.current?.dispose(); } catch { /* ignore */ }
      overlayInstance.current = null;
    };
  }, [recordsWithData, translate, dimension]);

  if (selectedRecords.length === 0) return null;

  return (
    <div className={styles.chartsContainer}>
      <h3 className={styles.sectionTitle}>
        {translate('comparison.lineCharts.independentTitle')}
      </h3>
      <div className={styles.independentCharts}>
        {recordsWithData.map((record, index) => (
          <div key={record.id} className={styles.chartWrapper}>
            <div className={styles.chartTitle}>
              {getLegendName(record, dimension)}
              {record.loading && (
                <span style={{ marginLeft: '8px', fontSize: '12px', color: '#94a3b8' }}>
                  {translate('comparison.messages.loading')}
                </span>
              )}
              {record.data?.error && !record.loading && (
                <span style={{ marginLeft: '8px', fontSize: '12px', color: '#f5222d' }}>
                  {translate('comparison.messages.loadFailedShort')}
                </span>
              )}
            </div>
            <div
              ref={(el) => (independentRefs.current[index] = el)}
              className={styles.chart}
            />
          </div>
        ))}
      </div>

      <h3 className={styles.sectionTitle}>
        {translate('comparison.lineCharts.overlaySectionTitle')}
      </h3>
      <div className={styles.overlayChart}>
        <div ref={overlayRef} className={styles.chart} />
      </div>
    </div>
  );
};

export default ComparisonLineCharts;
