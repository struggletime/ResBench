import React, { useEffect, useRef } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  LineChart,
  ReferenceArea,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { buildMetricExplanation } from '../../api/metricDetail';
import { useLanguage } from '../../i18n';
import styles from './MetricExplainModal.module.css';

const COLOR = {
  baseline: '#2563eb',
  actual: '#dc2626',
  time: '#d97706',
  result: '#15803d',
  weight: '#7c3aed',
  variance: '#0f766e',
  neutral: '#334155',
};

const DIMENSION_NAMES = ['TPS', 'Stability', 'Latency', 'Resistance', 'Recovery'];

const METRIC_META = {
  throughput: {
    nameKey: 'RadarChart.throughput',
    formula: 'S_{\\mathrm{Thr}} = 100 \\times \\frac{\\int P(t)\\,dt}{P_0 \\times \\Delta t}',
  },
  latency: {
    nameKey: 'RadarChart.latency',
    formula: 'S_{\\mathrm{Lat}} = 100 \\times \\frac{\\int TL(t)\\,dt}{\\int L(t)\\,dt}',
  },
  stability: {
    nameKey: 'RadarChart.stability',
    formula: 'S_{\\mathrm{Sta}} = 100 \\times \\left(1 - \\left(\\frac{1}{K} \\times \\sum_{j=1}^{K} \\mathrm{Var}(P_{\\mathrm{norm}}(t))_{t \\in I_j} / 0.25\\right)^a\\right)',
  },
  resistance: {
    nameKey: 'RadarChart.resistance',
    formula: 'S_{\\mathrm{Res}} = 100 \\times \\left( \\omega_1 \\times e^{-\\lambda (t_d - t_0)} + \\omega_2 \\times \\frac{P_d}{P_0} + \\omega_3 \\times \\frac{\\int_{t_0}^{t_d} P(t) dt}{P_0 \\times (t_d - t_0)} \\right)',
  },
  recovery: {
    nameKey: 'RadarChart.recovery',
    formula: 'S_{\\mathrm{Rec}} = 100 \\times \\left( \\omega_1 \\times e^{-\\lambda (t_r - t_d)} + \\omega_2 \\times \\frac{P_r}{P_0} + \\omega_3 \\times \\frac{\\int_{t_d}^{t_r} P(t) dt}{P_0 \\times (t_r - t_d)} \\right)',
  },
  adaptability: {
    nameKey: 'RadarChart.adaptability',
    formula: 'S_{\\mathrm{Adp}} = 100 \\times \\sum_{j=1}^{5} \\left( \\omega_j \\times \\frac{\\sum_{i=1}^{n} |S_j^i - \\bar{S}_j|}{n \\times \\bar{S}_j} \\right)',
  },
  disturbance: {
    nameKey: 'RadarChart.disturbance',
    formula: 'S_{\\mathrm{Per}} = 100 \\times \\frac{\\sum_{i=1}^{n} e^{-\\lambda \\times (t_d^i - t_0^i)}}{n}',
  },
  deviation: {
    nameKey: 'RadarChart.deviation',
    formula: 'S_{\\mathrm{Dev}} = 100 \\times \\sum_{j=1}^{5} \\left( \\omega_j \\times \\frac{\\sum_{i=1}^{n} (S_j^i - S_j^1)}{5 \\times n} \\right)',
  },
};

function formatNumber(value, digits = 2) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '--';
  if (Number.isInteger(value)) return value.toLocaleString();
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 0,
    maximumFractionDigits: digits,
  });
}

function latexColor(color, value) {
  return `\\color{${color}}{${value}}`;
}

function buildValueMap(values = []) {
  return values.reduce((accumulator, item) => {
    accumulator[item.key] = item.value;
    return accumulator;
  }, {});
}

function makeSeriesChart(name, data) {
  return {
    type: 'line',
    series: { name, data },
  };
}

function buildChartData(chart) {
  if (!chart) return [];
  const series = Array.isArray(chart.series) ? chart.series : [chart.series];
  return series[0].data.map((_, index) => {
    const point = { index: index + 1, name: `${index + 1}` };
    series.forEach((item) => {
      point[item.name] = item.data[index];
    });
    return point;
  });
}

function getChartDomain(chart) {
  const series = Array.isArray(chart.series) ? chart.series : [chart.series];
  const values = series.flatMap((item) => item.data);
  const min = Math.min(...values);
  const max = Math.max(...values);
  if (min === max) {
    const padding = max === 0 ? 1 : Math.abs(max) * 0.08;
    return [Math.max(0, min - padding), max + padding];
  }
  const padding = (max - min) * 0.1;
  return [Math.max(0, min - padding), max + padding];
}

function useMetricText(lang) {
  return lang === 'en'
    ? {
        runPrefix: 'Run',
        loading: 'Loading...',
        loadFailed: 'Failed to load',
        actualChart: 'Actual',
        baselineChart: 'Baseline',
        latencyChart: 'Latency',
        actualLatency: 'Actual latency',
        expectedTotal: 'Expected total',
        actualTotal: 'Actual total',
        timeWindow: 'Time window',
        scoreTrend: 'Scores across tests',
        variance: 'Variance curve',
        resistanceEffect: 'Resistance effect',
        recoveryEffect: 'Recovery effect',
        resistanceTime: 'Resistance time',
        recoveryTime: 'Recovery time',
        timeAxis: 'Time',
        throughputAxis: 'Throughput',
        latencyAxis: 'Latency',
        scoreAxis: 'Score',
        varianceAxis: 'Normalized',
        durationAxis: 'Duration',
        tableMetric: 'Metric',
        tableAverage: 'Average',
        tableDeviation: 'Deviation',
        tableChange: 'Change vs first',
      }
    : {
        runPrefix: '测试记录',
        loading: '加载中...',
        loadFailed: '加载失败',
        actualChart: '实际图',
        baselineChart: '基线图',
        latencyChart: '延迟图',
        actualLatency: '实际延迟图',
        expectedTotal: '预期总量',
        actualTotal: '实际总量',
        timeWindow: '时间项',
        scoreTrend: '各次测试评分',
        variance: '归一化方差曲线',
        resistanceEffect: '抵抗效果',
        recoveryEffect: '恢复效果',
        resistanceTime: '抵抗时间',
        recoveryTime: '恢复时间',
        timeAxis: '时间',
        throughputAxis: '吞吐量',
        latencyAxis: '延迟',
        scoreAxis: '评分',
        varianceAxis: '归一化性能',
        durationAxis: '持续时间',
        tableMetric: '指标',
        tableAverage: '均值',
        tableDeviation: '偏差和',
        tableChange: '相对第一次变化量',
      };
}

function MetricNumber({ label, value, color }) {
  return (
    <div className={styles.metricNumber}>
      <span className={styles.metricNumberLabel}>{label}</span>
      <span className={styles.metricNumberValue} style={{ color }}>{value}</span>
    </div>
  );
}

function InlineWeights({ values }) {
  return (
    <div className={styles.inlineWeights}>
      <MetricNumber label="w1" value={formatNumber(values.w1_time_recovery)} color={COLOR.weight} />
      <MetricNumber label="w2" value={formatNumber(values.w2_depth_retention)} color={COLOR.weight} />
      <MetricNumber label="w3" value={formatNumber(values.w3_total_loss)} color={COLOR.weight} />
    </div>
  );
}

function FormulaStrip({ formula, substituted }) {
  return (
    <div className={styles.formulaStrip}>
      <div className={styles.formulaLine}>{`\\[${formula}\\]`}</div>
      {substituted ? <div className={styles.formulaLine}>{`\\[${substituted}\\]`}</div> : null}
    </div>
  );
}

function ChartBlock({
  title,
  chart,
  chartType = 'line',
  yAxisLabel,
  domain,
  stats = [],
  note = '',
  colorMap = {},
  referenceDots = [],
  referenceArea = null,
  showAreaFill = [],
  height = 188,
}) {
  const chartData = buildChartData(chart);
  const series = Array.isArray(chart.series) ? chart.series : [chart.series];
  const ChartComponent = chartType === 'area' ? AreaChart : LineChart;
  const resolvedDomain = domain || getChartDomain(chart);

  return (
    <div className={styles.chartBlock}>
      <div className={styles.blockHeader}>
        <div className={styles.blockTitle}>{title}</div>
        {stats.length > 0 ? (
          <div className={styles.statsInline}>
            {stats.map((item) => (
              <MetricNumber key={item.label} label={item.label} value={item.value} color={item.color} />
            ))}
          </div>
        ) : null}
      </div>
      <div className={styles.chartCanvas}>
        <ResponsiveContainer width="100%" height={height}>
          <ChartComponent data={chartData} margin={{ top: 12, right: 8, left: 18, bottom: 18 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#dbe4f0" />
            <XAxis dataKey="name" />
            <YAxis domain={resolvedDomain} width={56} />
            <Tooltip />
            {referenceArea ? (
              <ReferenceArea
                x1={`${referenceArea.startIndex}`}
                x2={`${referenceArea.endIndex}`}
                fill={referenceArea.color || COLOR.time}
                fillOpacity={0.15}
                strokeOpacity={0}
              />
            ) : null}
            {series.map((item) => {
              const color = colorMap[item.name] || COLOR.neutral;
              if (chartType === 'area') {
                return (
                  <Area
                    key={item.name}
                    type="monotone"
                    dataKey={item.name}
                    stroke={color}
                    fill={color}
                    fillOpacity={showAreaFill.includes(item.name) ? 0.25 : 0.06}
                    strokeWidth={2.5}
                  />
                );
              }
              return (
                <Line
                  key={item.name}
                  type="monotone"
                  dataKey={item.name}
                  stroke={color}
                  strokeWidth={2.5}
                  dot={{ r: 3, fill: color }}
                  activeDot={{ r: 5 }}
                />
              );
            })}
            {referenceDots.map((item) => (
              <ReferenceDot
                key={`${item.label}-${item.x}-${item.y}`}
                x={`${item.x}`}
                y={item.y}
                r={5}
                fill={item.color}
                stroke="#fff"
                label={{ value: item.label, fill: item.color, fontSize: 11, position: 'top' }}
              />
            ))}
          </ChartComponent>
        </ResponsiveContainer>
        <div className={styles.axisCaption}>{yAxisLabel}</div>
      </div>
      {note ? <div className={styles.blockNote}>{note}</div> : null}
    </div>
  );
}

function MatrixTable({ headers, rows, footer = null }) {
  return (
    <div className={styles.matrixTable}>
      <table>
        <thead>
          <tr>
            {headers.map((header) => (
              <th key={header}>{header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={`${row[0]}-${index}`}>
              {row.map((cell, cellIndex) => (
                <td key={`${cellIndex}-${index}`}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {footer ? <div className={styles.tableFooter}>{footer}</div> : null}
    </div>
  );
}

function ThroughputPage({ values, data, text, formula }) {
  const seriesData = data?.chart?.series?.data || [0];
  const seriesName = data?.chart?.series?.name || 'TPS';
  const safeBaseline = values.baseline || 1;
  const safeActualTotal = values.actualTotal || 0;
  const safeDuration = values.duration || 1;

  const baselineSeries = {
    type: 'area',
    series: {
      name: 'Baseline',
      data: Array.from({ length: seriesData.length }, () => safeBaseline),
    },
  };
  const domain = getChartDomain({
    series: [
      baselineSeries.series,
      { name: seriesName, data: seriesData },
    ],
  });
  const denom = safeBaseline * safeDuration;
  const score = denom > 0 ? (100 * safeActualTotal) / denom : 0;

  return (
    <div className={styles.pageShell}>
      <FormulaStrip
        formula={formula}
        substituted={`S_{\\mathrm{Thr}} = 100 \\times \\frac{${latexColor('red', formatNumber(safeActualTotal))}}{${latexColor('blue', formatNumber(safeBaseline))} \\times ${latexColor('orange', formatNumber(safeDuration))}} = ${latexColor('green', formatNumber(score))}`}
      />
      <div className={styles.splitPage}>
        <ChartBlock
          title={text.actualChart}
          chart={data?.chart || { type: 'area', series: { name: 'TPS', data: [0] } }}
          chartType="area"
          yAxisLabel={text.throughputAxis}
          domain={domain}
          showAreaFill={['TPS']}
          colorMap={{ TPS: COLOR.actual }}
          stats={[
            { label: text.actualTotal, value: formatNumber(safeActualTotal), color: COLOR.actual },
          ]}
        />
        <ChartBlock
          title={text.baselineChart}
          chart={baselineSeries}
          chartType="area"
          yAxisLabel={text.throughputAxis}
          domain={domain}
          showAreaFill={['Baseline']}
          colorMap={{ Baseline: COLOR.baseline }}
          stats={[
            { label: 'P0', value: formatNumber(safeBaseline), color: COLOR.baseline },
            { label: 'Δt', value: formatNumber(safeDuration), color: COLOR.time },
            { label: text.expectedTotal, value: formatNumber(safeBaseline * safeDuration), color: COLOR.baseline },
          ]}
        />
      </div>
    </div>
  );
}

function LatencyPage({ values, data, text, formula }) {
  const seriesData = data?.chart?.series?.data || [0];
  const seriesName = data?.chart?.series?.name || 'Latency(ms)';
  const safeBaseline = values.baseline || 1;
  const safeExpected = values.expected || safeBaseline;
  const safeActual = values.actual || 1;

  const baselineSeries = {
    type: 'area',
    series: {
      name: 'Baseline',
      data: Array.from({ length: seriesData.length }, () => safeBaseline),
    },
  };
  const domain = getChartDomain({
    series: [
      baselineSeries.series,
      { name: seriesName, data: seriesData },
    ],
  });
  const score = safeActual > 0 ? (100 * safeExpected) / safeActual : 0;

  return (
    <div className={styles.pageShell}>
      <FormulaStrip
        formula={formula}
        substituted={`S_{\\mathrm{Lat}} = 100 \\times \\frac{${latexColor('blue', formatNumber(safeExpected))}}{${latexColor('red', formatNumber(safeActual))}} = ${latexColor('green', formatNumber(score))}`}
      />
      <div className={styles.splitPage}>
        <ChartBlock
          title={text.baselineChart}
          chart={baselineSeries}
          chartType="area"
          yAxisLabel={text.latencyAxis}
          domain={domain}
          showAreaFill={['Baseline']}
          colorMap={{ Baseline: COLOR.baseline }}
          stats={[
            { label: 'L', value: formatNumber(safeBaseline), color: COLOR.baseline },
            { label: text.expectedTotal, value: formatNumber(safeExpected), color: COLOR.baseline },
          ]}
        />
        <ChartBlock
          title={text.actualLatency}
          chart={data?.chart || { type: 'area', series: { name: 'Latency(ms)', data: [0] } }}
          chartType="area"
          yAxisLabel={text.latencyAxis}
          domain={domain}
          showAreaFill={['Latency(ms)']}
          colorMap={{ 'Latency(ms)': COLOR.actual }}
          stats={[
            { label: text.actualTotal, value: formatNumber(safeActual), color: COLOR.actual },
          ]}
        />
      </div>
    </div>
  );
}

function StabilityPage({ values, data, text, formula }) {
  const safeVar1 = values.var1 ?? 0;
  const safeVar2 = values.var2 ?? 0;
  const safeAlpha = values.alpha || 1;
  const averageVar = (safeVar1 + safeVar2) / 2;
  const rawScore = 100 * (1 - Math.pow(Math.min(averageVar / 0.25, 1), safeAlpha));
  const score = Number.isFinite(rawScore) ? Math.max(0, Math.min(100, rawScore)) : 0;

  return (
    <div className={styles.pageShell}>
      <FormulaStrip
        formula={formula}
        substituted={`S_{\\mathrm{Sta}} = 100 \\times \\left(1 - \\left(\\frac{\\frac{${latexColor('teal', formatNumber(safeVar1))} + ${latexColor('teal', formatNumber(safeVar2))}}{${latexColor('orange', '2')}}}{0.25}\\right)^{${latexColor('orange', formatNumber(safeAlpha))}}\\right) = ${latexColor('green', formatNumber(score))}`}
      />
      <div className={styles.compactPage}>
        <ChartBlock
          title={text.variance}
          chart={data?.chart || { type: 'line', series: { name: 'P_norm', data: [1] } }}
          chartType="line"
          yAxisLabel={text.varianceAxis}
          colorMap={{ P_norm: COLOR.variance }}
          stats={[
            { label: 'V1', value: formatNumber(safeVar1), color: COLOR.variance },
            { label: 'V2', value: formatNumber(safeVar2), color: COLOR.variance },
            { label: 'a', value: formatNumber(safeAlpha), color: COLOR.time },
          ]}
          height={220}
        />
      </div>
    </div>
  );
}

function ResistanceRecoveryPage({ metricKey, values, data, text, formula }) {
  const isRecovery = metricKey === 'recovery';

  const seriesArr = Array.isArray(data?.chart?.series) && data.chart.series.length >= 2
    ? data.chart.series
    : [{ name: 'Baseline', data: [1] }, { name: 'Actual', data: [1] }];

  const safeChart = { type: 'area', series: seriesArr };
  const baselineSeries = { type: 'area', series: seriesArr[0] };
  const actualSeries = { type: 'area', series: seriesArr[1] };
  const lineSeries = makeSeriesChart('Actual', seriesArr[1].data);
  const domain = getChartDomain(safeChart);

  const safeW1 = values.w1_time_recovery ?? 0.3;
  const safeW2 = values.w2_depth_retention ?? 0.4;
  const safeW3 = values.w3_total_loss ?? 0.3;
  const safeBaselineTps = values.baseline_tps || 1;
  const safeMinTps = values.min_tps ?? 0;
  const safeActualTotal = values.actual_total_tps ?? 0;
  const safeRecoveryDuration = values.recovery_duration || 1;

  const targetPoint = (isRecovery ? values.recovery_point : values.baselinepoint) || [0, safeBaselineTps];
  const minpoint = values.minpoint || [0, safeMinTps];

  const timeDecay = Math.exp(-safeRecoveryDuration);
  const ratio = safeBaselineTps > 0 ? safeMinTps / safeBaselineTps : 0;
  const denom = safeBaselineTps * safeRecoveryDuration;
  const integralRatio = denom > 0 ? safeActualTotal / denom : 0;
  const rawScore = 100 * (
    safeW1 * timeDecay +
    safeW2 * ratio +
    safeW3 * integralRatio
  );
  const score = Number.isFinite(rawScore) ? Math.max(0, Math.min(100, rawScore)) : 0;

  return (
    <div className={styles.pageShell}>
      <FormulaStrip
        formula={formula}
        substituted={`${isRecovery ? 'S_{\\mathrm{Rec}}' : 'S_{\\mathrm{Res}}'} = 100 \\times (${latexColor('purple', formatNumber(safeW1))} \\times ${latexColor('orange', formatNumber(timeDecay))} + ${latexColor('purple', formatNumber(safeW2))} \\times ${latexColor('red', formatNumber(ratio))} + ${latexColor('purple', formatNumber(safeW3))} \\times ${latexColor(isRecovery ? 'red' : 'blue', formatNumber(integralRatio))}) = ${latexColor('green', formatNumber(score))}`}
      />
      <InlineWeights values={{ w1_time_recovery: safeW1, w2_depth_retention: safeW2, w3_total_loss: safeW3 }} />
      <div className={styles.pageGridFour}>
        <ChartBlock
          title={text.baselineChart}
          chart={baselineSeries}
          chartType="area"
          yAxisLabel={text.throughputAxis}
          domain={domain}
          showAreaFill={['Baseline']}
          colorMap={{ Baseline: COLOR.baseline }}
          stats={[
            { label: 'P0', value: formatNumber(safeBaselineTps), color: COLOR.baseline },
          ]}
          height={156}
        />
        <ChartBlock
          title={text.actualChart}
          chart={actualSeries}
          chartType="area"
          yAxisLabel={text.throughputAxis}
          domain={domain}
          showAreaFill={['Actual']}
          colorMap={{ Actual: COLOR.actual }}
          stats={[
            { label: text.actualTotal, value: formatNumber(safeActualTotal), color: COLOR.actual },
          ]}
          height={156}
        />
        <ChartBlock
          title={isRecovery ? text.recoveryEffect : text.resistanceEffect}
          chart={lineSeries}
          chartType="line"
          yAxisLabel={text.throughputAxis}
          domain={domain}
          colorMap={{ Actual: COLOR.actual }}
          referenceDots={[
            { x: minpoint[0] ?? 0, y: minpoint[1] ?? 0, label: 'Pd', color: COLOR.actual },
            { x: targetPoint[0] ?? 0, y: targetPoint[1] ?? safeBaselineTps, label: isRecovery ? 'Pr' : 'P0', color: COLOR.baseline },
          ]}
          stats={[
            { label: 'Pd/P0', value: formatNumber(ratio), color: COLOR.actual },
          ]}
          height={156}
        />
        <ChartBlock
          title={isRecovery ? text.recoveryTime : text.resistanceTime}
          chart={lineSeries}
          chartType="line"
          yAxisLabel={text.throughputAxis}
          domain={domain}
          colorMap={{ Actual: COLOR.actual }}
          referenceArea={values.chart_segment || { startIndex: 0, endIndex: 1, color: '#E34E59', label: '0s' }}
          stats={[
            { label: 'Δt', value: formatNumber(safeRecoveryDuration), color: COLOR.time },
          ]}
          height={156}
        />
      </div>
    </div>
  );
}

function AdaptabilityPage({ values, data, text, formula }) {
  const safeAverages = Array.isArray(values.averages) && values.averages.length === 5
    ? values.averages : [0, 0, 0, 0, 0];
  const safeDeviations = Array.isArray(values.deviations) && values.deviations.length === 5
    ? values.deviations : [0, 0, 0, 0, 0];

  const rows = DIMENSION_NAMES.map((name, index) => [
    name,
    formatNumber(safeAverages[index] ?? 0),
    formatNumber(safeDeviations[index] ?? 0),
  ]);
  const total = safeDeviations.reduce((sum, item, index) => {
    const avg = safeAverages[index] || 1;
    return sum + 0.2 * (item / (5 * avg));
  }, 0);
  const rawScore = 100 * total;
  const score = Number.isFinite(rawScore) ? Math.max(0, Math.min(100, rawScore)) : 0;

  return (
    <div className={styles.pageShell}>
      <FormulaStrip
        formula={formula}
        substituted={`S_{\\mathrm{Adp}} = 100 \\times \\sum_{j=1}^{5} \\left(${latexColor('purple', '0.2')} \\times \\frac{\\mathrm{Deviation}_j}{5 \\times \\mathrm{Avg}_j}\\right) = ${latexColor('green', formatNumber(score))}`}
      />
      <div className={styles.splitPage}>
        <ChartBlock
          title={text.scoreTrend}
          chart={data.chart}
          chartType="line"
          yAxisLabel={text.scoreAxis}
          colorMap={{
            TPS: COLOR.baseline,
            Stability: COLOR.variance,
            Latency: COLOR.actual,
            Resistance: COLOR.time,
            Recovery: COLOR.weight,
          }}
          height={198}
        />
        <MatrixTable
          headers={[text.tableMetric, text.tableAverage, text.tableDeviation]}
          rows={rows}
          footer={`w1 = w2 = w3 = w4 = w5 = ${formatNumber(values.tps_w)}`}
        />
      </div>
    </div>
  );
}

function DisturbancePage({ values, data, text, formula }) {
  const safeDurations = Array.isArray(values.durations) && values.durations.length > 0
    ? values.durations : [1];
  const safeLambda = values.lambda || 0.1;

  const rawScore = 100 * (
    safeDurations.reduce((sum, item) => sum + Math.exp(-safeLambda * item), 0) / safeDurations.length
  );
  const score = Number.isFinite(rawScore) ? Math.max(0, Math.min(100, rawScore)) : 0;
  const durationText = safeDurations.map((item, index) => `${index + 1}:${formatNumber(item)}`).join('  ');

  return (
    <div className={styles.pageShell}>
      <FormulaStrip
        formula={formula}
        substituted={`S_{\\mathrm{Per}} = 100 \\times \\frac{${safeDurations.map((item) => `e^{-${latexColor('orange', formatNumber(safeLambda))} \\times ${latexColor('red', formatNumber(item))}}`).join(' + ')}}{${latexColor('orange', safeDurations.length)}} = ${latexColor('green', formatNumber(score))}`}
      />
      <div className={styles.splitPage}>
        <ChartBlock
          title={text.timeWindow}
          chart={data.chart}
          chartType="line"
          yAxisLabel={text.durationAxis}
          colorMap={{ 'Disturbance Duration': COLOR.actual }}
          stats={[
            { label: 'lambda', value: formatNumber(safeLambda), color: COLOR.time },
          ]}
          height={208}
        />
        <div className={styles.infoBlock}>
          <div className={styles.infoTitle}>{text.actualTotal}</div>
          <div className={styles.durationRow}>{durationText}</div>
        </div>
      </div>
    </div>
  );
}

function DeviationPage({ values, data, text, formula }) {
  const safeCompare = Array.isArray(values.compare2firsttime) && values.compare2firsttime.length === 5
    ? values.compare2firsttime : [0, 0, 0, 0, 0];

  const rows = DIMENSION_NAMES.map((name, index) => [
    name,
    `+${formatNumber(safeCompare[index] ?? 0)}`,
  ]);
  const total = safeCompare.reduce((sum, item) => sum + item, 0);
  const rawScore = 100 * ((0.2 * total) / (5 * safeCompare.length));
  const score = Number.isFinite(rawScore) ? Math.max(0, Math.min(100, rawScore)) : 0;

  return (
    <div className={styles.pageShell}>
      <FormulaStrip
        formula={formula}
        substituted={`S_{\\mathrm{Dev}} = 100 \\times \\sum_{j=1}^{5} \\left(${latexColor('purple', '0.2')} \\times \\frac{\\Delta_j}{5 \\times ${latexColor('orange', values.compare2firsttime.length)}}\\right) = ${latexColor('green', formatNumber(score))}`}
      />
      <div className={styles.splitPage}>
        <ChartBlock
          title={text.scoreTrend}
          chart={data.chart}
          chartType="line"
          yAxisLabel={text.scoreAxis}
          colorMap={{
            TPS: COLOR.baseline,
            Stability: COLOR.variance,
            Latency: COLOR.actual,
            Resistance: COLOR.time,
            Recovery: COLOR.weight,
          }}
          height={198}
        />
        <MatrixTable
          headers={[text.tableMetric, text.tableChange]}
          rows={rows}
          footer={`w1 = w2 = w3 = w4 = w5 = ${formatNumber(values.tps_w)}`}
        />
      </div>
    </div>
  );
}

function renderMetricPage(metricKey, values, data, text, formula) {
  if (metricKey === 'throughput') return <ThroughputPage values={values} data={data} text={text} formula={formula} />;
  if (metricKey === 'latency') return <LatencyPage values={values} data={data} text={text} formula={formula} />;
  if (metricKey === 'stability') return <StabilityPage values={values} data={data} text={text} formula={formula} />;
  if (metricKey === 'resistance') return <ResistanceRecoveryPage metricKey="resistance" values={values} data={data} text={text} formula={formula} />;
  if (metricKey === 'recovery') return <ResistanceRecoveryPage metricKey="recovery" values={values} data={data} text={text} formula={formula} />;
  if (metricKey === 'adaptability') return <AdaptabilityPage values={values} data={data} text={text} formula={formula} />;
  if (metricKey === 'disturbance') return <DisturbancePage values={values} data={data} text={text} formula={formula} />;
  if (metricKey === 'deviation') return <DeviationPage values={values} data={data} text={text} formula={formula} />;

  // fallback for unknown metric keys — show the score and a simple chart
  const fallbackChart = data?.chart || { type: 'line', series: { name: metricKey, data: [data?.score || 0] } };
  return (
    <div className={styles.pageShell}>
      <div className={styles.chartCanvas} style={{ minHeight: 200 }}>
        <ChartBlock
          title={metricKey}
          chart={fallbackChart}
          chartType="line"
          yAxisLabel="Score"
          colorMap={{ [metricKey]: COLOR.neutral }}
          stats={[
            { label: 'Score', value: formatNumber(data?.score ?? 0), color: COLOR.neutral },
          ]}
        />
      </div>
    </div>
  );
}

const MetricExplainModal = ({ metricKey, runContext, monitorData, scores, onClose }) => {
  const { lang, translate } = useLanguage();
  const bodyRef = useRef(null);
  const text = useMetricText(lang);

  const data = buildMetricExplanation(metricKey, { monitorData: monitorData || [], scores: scores || [] });

  useEffect(() => {
    if (data && bodyRef.current && typeof window !== 'undefined' && window.MathJax) {
      const timer = setTimeout(() => {
        if (window.MathJax.typesetPromise) {
          window.MathJax.typesetPromise([bodyRef.current]).catch(() => {});
        } else if (window.MathJax.typeset) {
          window.MathJax.typeset([bodyRef.current]);
        }
      }, 100);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [data]);

  useEffect(() => {
    const handleKeydown = (event) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeydown);
    return () => window.removeEventListener('keydown', handleKeydown);
  }, [onClose]);

  const meta = METRIC_META[metricKey];
  const valueMap = buildValueMap(data?.values);
  const metricName = meta ? translate(meta.nameKey) : metricKey;
  const runText = runContext?.runName
    ? `${text.runPrefix} #${runContext.runId} · ${runContext.runName}`
    : '';

  return (
    <div className={styles.modalOverlay} onClick={onClose}>
      <div className={styles.modalContainer} onClick={(event) => event.stopPropagation()}>
        <div className={styles.modalHeader}>
          <div className={styles.headerText}>
            <div className={styles.modalEyebrow}>{runText}</div>
            <h3>{metricName}</h3>
          </div>
          {data?.score != null ? <div className={styles.scoreBadge}>{formatNumber(data.score)}</div> : null}
          <button className={styles.closeButton} onClick={onClose}>×</button>
        </div>
        <div className={styles.modalBody} ref={bodyRef}>
          {data ? renderMetricPage(metricKey, valueMap, data, text, meta.formula) : (
            <div className={styles.loading}>{text.loadFailed}</div>
          )}
        </div>
      </div>
    </div>
  );
};

export default MetricExplainModal;
