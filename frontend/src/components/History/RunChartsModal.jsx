import React, { useEffect } from 'react';
import styles from './HistoryTable.module.css';
import RadarChart from '../ScoreChart/RadarChart';
import RadarTable from '../ScoreChart/RadarTable';
import ResilienceTriangleChart from '../RealTimeChart/ResilienceTriangleChart';
import VerificationBadges from './VerificationBadges';
import { useLanguage } from '../../i18n';

/**
 * 历史运行的图表二级页面：实时性能图（韧性三角）+ 韧性评分雷达图。
 * 从历史列表点击运行名打开，不再在列表底部展开。
 */
const RunChartsModal = ({
  run,
  scoresLoading,
  scoresError,
  scores,
  monitorData,
  faultInjectInfo,
  triangles,
  faultOptions,
  selectedFaultIdx,
  onSelectFaultIdx,
  onClose,
}) => {
  const { lang } = useLanguage();
  const isZh = lang !== 'en';
  const hasScores = Array.isArray(scores) && scores.length > 0;

  useEffect(() => {
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  if (!run) return null;

  return (
    <div className={styles.chartModalOverlay} onClick={onClose}>
      <div
        className={styles.chartModal}
        role="dialog"
        aria-modal="true"
        aria-label={isZh ? '运行图表' : 'Run charts'}
        data-tour="history-score-panel"
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles.chartModalHeader}>
          <div>
            <h2>{isZh ? '运行图表' : 'Run Charts'}</h2>
            <p className={styles.chartModalSub}>
              {run.runName} · {run.historyName}
            </p>
          </div>
          <button type="button" className={styles.chartModalClose} onClick={onClose}>
            {isZh ? '关闭' : 'Close'}
          </button>
        </div>

        <div className={styles.chartModalBody}>
          <div className={styles.chartModalGrid}>
            <div className={styles.chartCard}>
              <VerificationBadges faultInjectInfo={faultInjectInfo} />
              <ResilienceTriangleChart
                data={monitorData}
                faultInjectInfo={faultInjectInfo}
                triangles={triangles}
                faults={run.faults || []}
                height={520}
              />
            </div>

            <div className={styles.chartCard}>
              {hasScores ? (
                <>
                  {faultOptions.length > 0 && (
                    <div className={styles.faultSelectRow}>
                      <label htmlFor="history-score-view">
                        {isZh ? '评分视图：' : 'Score View:'}
                      </label>
                      <select
                        id="history-score-view"
                        value={selectedFaultIdx}
                        onChange={(event) => onSelectFaultIdx(Number(event.target.value))}
                      >
                        <option value={-1}>{isZh ? '平均分' : 'Average'}</option>
                        {faultOptions.map((option, index) => (
                          <option key={index} value={index}>
                            {option.faultName || `#${index + 1}`}
                          </option>
                        ))}
                      </select>
                    </div>
                  )}
                  <RadarChart
                    height={520}
                    data={scores}
                    monitorData={monitorData}
                    scores={scores}
                    runContext={{ runId: run.id, runName: run.runName }}
                  />
                  <RadarTable scores={scores} />
                </>
              ) : scoresError ? (
                <div className={styles.scoreError}>
                  <strong>{isZh ? '错误：' : 'Error: '}</strong>
                  {scoresError}
                </div>
              ) : (
                <div className={styles.noDataHint}>
                  {scoresLoading
                    ? (isZh ? '评分加载中...' : 'Loading scores...')
                    : (isZh
                      ? '暂无韧性评分数据，该测试的评分计算可能未成功完成。'
                      : 'No resilience score data available. The evaluation script may have failed for this test run.')}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default RunChartsModal;
