import React, { useEffect, useState, useRef } from 'react';
import {
  RadarChart,
  Radar,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  ResponsiveContainer,
} from 'recharts';
import style from './RadarChartComponent.module.css';
import { useLanguage } from '../../i18n';
// import MetricExplainModal from './MetricExplainModal';

const RadarChartComponent = ({ data, title, height }) => {
  const { translate } = useLanguage();
  const [isFormulaOpen, setIsFormulaOpen] = useState(false);
  // const [selectedMetric, setSelectedMetric] = useState(null);
  const formulaModalRef = useRef(null);

  const chartTitle = title || translate('RadarChart.defaultTitle');

  useEffect(() => {
    if (isFormulaOpen && formulaModalRef.current && typeof window !== 'undefined' && window.MathJax) {
      if (window.MathJax.typesetPromise) {
        window.MathJax.typesetPromise([formulaModalRef.current]).catch(() => {});
      } else if (window.MathJax.typeset) {
        window.MathJax.typeset([formulaModalRef.current]);
      }
    }
  }, [isFormulaOpen]);

  // const handleMetricClick = useCallback((metricKey) => {
  //   setSelectedMetric(metricKey);
  // }, []);

  // const handleCloseModal = useCallback(() => {
  //   setSelectedMetric(null);
  // }, []);

  const formulaData = [
    { nameKey: 'RadarChart.throughput', explanationKey: 'RadarChart.throughput.explanation', formula: '\\[  S_{\\mathrm{Thr}} = 100 \\times \\int_{t_0}^{t_r} P(t) dt / (P_0 \\times (t_r - t_0))  \\]' },
    { nameKey: 'RadarChart.latency', explanationKey: 'RadarChart.latency.explanation', formula: '\\[S_{\\mathrm{Lat}} = 100 \\times \\frac{\\int_{t_0}^{t_r} TL(t) dt}{\\int_{t_0}^{t_r} L(t) dt} \\]' },
    { nameKey: 'RadarChart.stability', explanationKey: 'RadarChart.stability.explanation', formula: '\\[S_{\\mathrm{Sta}} = 100 \\times \\left(1 - \\left(\\frac{1}{K} \\times \\sum_{j=1}^{K} \\mathrm{Var}(P_{\\mathrm{norm}}(t))_{t \\in I_j} / 0.25\\right)^a\\right) \\]' },
    { nameKey: 'RadarChart.resistance', explanationKey: 'RadarChart.resistance.explanation', formula: '\\[ S_{\\mathrm{Res}} = 100 \\times \\left( \\omega_1 \\times e^{-\\lambda (t_d - t_0)} + \\omega_2 \\times P_d / P_0 + \\omega_3 \\times \\int_{t_0}^{t_d} P(t) dt / (P_0 \\times (t_d - t_0)) \\right) \\]' },
    { nameKey: 'RadarChart.recovery', explanationKey: 'RadarChart.recovery.explanation', formula: '\\[ S_{\\mathrm{Rec}} = 100 \\times \\left( \\omega_1 \\times e^{-\\lambda (t_r - t_d)} + \\omega_2 \\times P_r / P_0 + \\omega_3 \\times \\int_{t_d}^{t_r} P(t) dt / (P_0 \\times (t_r - t_d)) \\right) \\]' },
    { nameKey: 'RadarChart.adaptability', explanationKey: 'RadarChart.adaptability.explanation', formula: '\\[S_{\\mathrm{Adp}} = 100 \\times \\sum_{j=1}^{5} \\left( \\omega_j \\times \\sum_{i=1}^{n} |S_j^i - \\bar{S}_j| / (n \\times \\bar{S}_j) \\right) \\]' },

    { nameKey: 'RadarChart.disturbance', explanationKey: 'RadarChart.disturbance.explanation', formula: '\\[S_{\\mathrm{Per}} = 100 \\times \\sum_{i=1}^{n} e^{-\\lambda \\times (t_d^i - t_0^i)} / n  \\]' },
    { nameKey: 'RadarChart.deviation', explanationKey: 'RadarChart.deviation.explanation', formula: '\\[ S_{\\mathrm{Dev}} = 100 \\times \\sum_{i=1}^{5} \\left( \\omega_j \\times \\sum_{i=1}^{n} (S_j^i - S_j^1) / (5 \\times n) \\right) \\]' },
    // { nameKey: 'RadarChart.volatility', explanationKey: 'RadarChart.volatility.explanation', formula: '\\[  \\]' },
    // { nameKey: 'RadarChart.rto', explanationKey: 'RadarChart.rto.explanation', formula: '\\[  \\]' },
  ];

  return (
    <div className={style.container}>
      <div className={style.chartWrapper} style={height ? { height } : undefined}>
        <h3>{chartTitle}</h3>
        <ResponsiveContainer width="100%" height="75%">
          <RadarChart
            data={data}
            cx="50%"
            cy="50%"
            outerRadius="80%"
            startAngle={90}
            endAngle={-270}
          >
            <PolarGrid
              gridType="circle"
              radialLines={{ stroke: '#ddd' }}
              concentricGrids={{ stroke: '#eee' }}
            />
            <PolarAngleAxis
              dataKey="name"
              tick={{ fill: '#666', fontSize: 11 }}
              axisLine={false}
            />
            <PolarRadiusAxis
              angle={90}
              domain={[0, 100]}
              tick={{ fill: '#999', fontSize: 10 }}
              tickCount={5}
            />
            <Radar
              name={translate('RadarChart.radarLegend')}
              dataKey="value"
              stroke="#007acc"
              strokeWidth={2}
              fill="#e6f7ff"
              fillOpacity={0.5}
              dot={{ r: 4, fill: '#007acc' }}
            />
          </RadarChart>
        </ResponsiveContainer>

        {/* 可点击的指标标签行 */}
        {/* <div className={style.metricTags}>
          {data && data.map((item) => (
            <button
              key={item.metricKey}
              className={style.metricTag}
              onClick={() => handleMetricClick(item.metricKey)}
            >
              {item.name}
            </button>
          ))}
        </div> */}

        <button
          className={style.questionButton}
          onClick={() => setIsFormulaOpen(true)}
          aria-label={translate('RadarChart.viewFormulas')}
        >
          ?
        </button>
      </div>

      {isFormulaOpen && (
        <div className={style.modalOverlay} onClick={() => setIsFormulaOpen(false)}>
          <div className={style.modalContent} ref={formulaModalRef} onClick={(e) => e.stopPropagation()}>
            <div className={style.modalHeader}>
              <h3>{translate('RadarChart.formulaModalTitle')}</h3>
              <button
                className={style.closeButton}
                onClick={() => setIsFormulaOpen(false)}
              >
                ×
              </button>
            </div>
            <div className={style.formulaList}>
              {formulaData.map((item, index) => (
                <div key={index} className={style.formulaItem}>
                  <strong>{translate(item.nameKey)}</strong>
                  <div className={style.formula}>{item.formula}</div>
                  <p className={style.explanation}>{translate(item.explanationKey)}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* {selectedMetric && (
        <MetricExplainModal
          metricKey={selectedMetric}
          runContext={runContext}
          monitorData={monitorData}
          scores={scores}
          onClose={handleCloseModal}
        />
      )} */}
    </div>
  );
};

export default RadarChartComponent;
