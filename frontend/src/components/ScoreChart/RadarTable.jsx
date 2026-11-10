import { useState } from 'react';
import { useLanguage } from '../../i18n';
import style from './RadarScoreTable.module.css';

const METRIC_KEYS = [
  'throughput',
  'latency',
  'stability',
  'resistance',
  'recovery',
  'adaptability',
  'deviation',
  'disturbance',
];

function getTier(value) {
  if (value >= 70) return 'high';
  if (value >= 40) return 'mid';
  return 'low';
}

const RadarScoreTable = ({ scores }) => {
  const { translate } = useLanguage();
  const [tooltip, setTooltip] = useState({
    visible: false,
    text: '',
    x: 0,
    y: 0,
    placement: 'right',
  });

  const showTooltip = (event, text) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const tooltipMaxWidth = 300;
    const gap = 10;
    const canPlaceRight = rect.right + gap + tooltipMaxWidth < window.innerWidth;
    const placement = canPlaceRight ? 'right' : 'left';
    const x = canPlaceRight ? rect.right + gap : rect.left - gap;
    const y = rect.top + rect.height / 2;
    setTooltip({ visible: true, text, x, y, placement });
  };

  const hideTooltip = () => {
    setTooltip((prev) => ({ ...prev, visible: false }));
  };

  const headers = METRIC_KEYS.map((key) => ({
    labelKey: `RadarTable.${key}.label`,
    descKey: `RadarTable.${key}.desc`,
  }));

  const values = METRIC_KEYS.map((key) => {
    const scoreItem = (scores || []).find((s) => s.metricKey === key);
    return scoreItem ? scoreItem.value : 0;
  });

  return (
    <div className={style['radar-score-table-container']}>
      <div className={style['table-title']}>
        {translate('RadarTable.title')}
      </div>

      <div className={style['table-scroll']}>
        <table className={style['score-table']}>
          <thead>
            <tr>
              {headers.map((header, index) => {
                const label = translate(header.labelKey);
                const description = translate(header.descKey);
                return (
                  <th
                    key={index}
                    onMouseEnter={(e) => showTooltip(e, description)}
                    onMouseLeave={hideTooltip}
                  >
                    {label}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            <tr>
              {values.map((value, index) => (
                <td key={index} data-tier={getTier(value)}>
                  {value}
                </td>
              ))}
            </tr>
          </tbody>
        </table>
      </div>

      {tooltip.visible && (
        <div
          className={style.tooltip}
          style={{
            left: tooltip.x,
            top: tooltip.y,
            transform:
              tooltip.placement === 'right'
                ? 'translate(0, -50%)'
                : 'translate(-100%, -50%)',
          }}
        >
          {tooltip.text}
        </div>
      )}
    </div>
  );
};

export default RadarScoreTable;
