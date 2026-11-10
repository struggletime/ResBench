import React, { useMemo, useState } from 'react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  ReferenceLine,
  ReferenceArea,
  Legend,
} from 'recharts';
import { useLanguage } from '../../i18n';
import { useSelector } from 'react-redux';
import { CHANNEL_STATE, runChannelState } from '../../utils/channelState';
import { buildFaultDetails } from '../../utils/faultDetails';
import FaultDetailPopover from './FaultDetailPopover';

function formatTimestamp(timestamp) {
  if (!timestamp) return '--:--:--';
  const date = new Date(Number(timestamp));
  if (Number.isNaN(date.getTime())) return '--:--:--';
  return date.toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

function translateStatus(statusValue, translate) {
  const key = String(statusValue || '').toLowerCase();
  const map = {
    running: 'realtimeChart.status.running',
    stopped: 'realtimeChart.status.stopped',
    end: 'realtimeChart.status.end',
    success: 'realtimeChart.status.success',
    error: 'realtimeChart.status.error',
    idle: 'realtimeChart.status.idle',
  };
  const i18nKey = map[key];
  if (i18nKey) {
    const translated = translate(i18nKey);
    if (translated !== i18nKey) return translated;
  }
  return statusValue || 'Unknown';
}

const RealTimeThroughputChart = () => {
  const { translate, lang } = useLanguage();
  const [faultPopover, setFaultPopover] = useState(null);
  const reduxData = useSelector((state) => state.monitor?.data || []);
  const suiteState = useSelector((state) => state.monitor?.suiteState || '');
  const faultRegions = useSelector((state) => state.monitor?.faultRegions || []);
  const faultInjectInfo = useSelector((state) => state.monitor?.faultInjectInfo || []);

  const data = useMemo(() => {
    if (!Array.isArray(reduxData) || reduxData.length === 0) return [];
    const formatted = reduxData.map((item, index) => {
      if (item.time) return item;
      const timestamp = item?.timestamp ?? index;
      const throughput = Number.parseFloat(item?.tps ?? item?.throughput ?? 0);
      const latency = Number.parseFloat(item?.avgLatencyMs ?? 0);
      return {
        id: item.id || `${timestamp}-${index}`,
        timestamp: Number(timestamp),
        time: formatTimestamp(timestamp),
        throughput: Number.isFinite(throughput) ? Number(throughput.toFixed(2)) : 0,
        avgLatencyMs: Number.isFinite(latency) ? Number(latency.toFixed(2)) : 0,
      };
    }).filter((item) => Number.isFinite(item.throughput));
    return formatted;
  }, [reduxData]);

  const faultAreas = useMemo(() => {
    if (!faultRegions || faultRegions.length === 0 || data.length === 0) return [];
    const lastDataTs = data[data.length - 1].timestamp;
    const firstDataTs = data[0].timestamp;
    const tsScale = lastDataTs > 1e12 ? 1 : 1000;

    // 1. 收集原始区间
    const rawAreas = faultRegions
      .map((fault) => {
        const startTs = (fault.startTime || 0) * tsScale;
        const endTs = (fault.endTime > 0 ? fault.endTime : (lastDataTs / tsScale)) * tsScale;
        if (startTs <= 0) return null;

        let firstIdx = -1;
        let lastIdx = -1;
        for (let i = 0; i < data.length; i++) {
          if (data[i].timestamp >= startTs && firstIdx === -1) firstIdx = i;
          if (data[i].timestamp <= endTs) lastIdx = i;
        }
        if (firstIdx !== -1 && lastIdx === -1) lastIdx = data.length - 1;
        if (firstIdx === -1) {
          if (startTs <= firstDataTs && endTs >= firstDataTs) {
            firstIdx = 0;
            for (let i = 0; i < data.length; i++) {
              if (data[i].timestamp <= endTs) lastIdx = i;
            }
          } else {
            return null;
          }
        }
        if (lastIdx === -1) return null;
        if (firstIdx === 0 && lastIdx === data.length - 1 && data.length > 5) return null;

        return {
          x1: data[firstIdx].time,
          x2: data[lastIdx].time,
          firstIdx,
          lastIdx,
          label: fault.faultName || '',
          id: fault.faultID,
          groupKey: fault.groupKey || '',
          // 保留原始 faultRegion，点击红带时用它去关联完整注入信息
          fault,
        };
      })
      .filter(Boolean);

    // 2. 按 groupKey 合并
    const merged = {};
    for (const area of rawAreas) {
      const key = area.groupKey || area.id || area.x1;
      if (!merged[key]) {
        merged[key] = {
          x1: area.x1,
          x2: area.x2,
          firstIdx: area.firstIdx,
          lastIdx: area.lastIdx,
          labels: [area.label],
          faults: [area.fault],
        };
      } else {
        merged[key].firstIdx = Math.min(merged[key].firstIdx, area.firstIdx);
        merged[key].lastIdx = Math.max(merged[key].lastIdx, area.lastIdx);
        merged[key].faults.push(area.fault);
        if (area.label && !merged[key].labels.includes(area.label)) {
          merged[key].labels.push(area.label);
        }
      }
    }

    // 3. 转为渲染数组
    const result = Object.entries(merged).map(([key, m]) => ({
      x1: m.firstIdx !== undefined ? data[m.firstIdx].time : m.x1,
      x2: m.lastIdx !== undefined ? data[m.lastIdx].time : m.x2,
      label: m.labels.join(' + '),
      id: key,
      faults: m.faults,
    }));
    return result;
  }, [faultRegions, data]);

  /**
   * 长连接中途断开时，在图上打一条竖线 —— 落点是**第一次观测到回退**的那个采样点。
   *
   * <p>只在「先骑上过通道、之后又退回 HTTP」时才画：整轮都没建立（`never`）的时候画在哪
   * 都是误导，那种情况交给上方的横幅说。断掉的准确时刻无从得知（两次调用之间没有东西在
   * 采样通道），所以贴到 ≥ 该时刻的第一个采样点上，标签也照实写「回退」而不是「断开」。
   */
  const channelFallbackX = useMemo(() => {
    if (data.length === 0) return null;
    const state = runChannelState(faultInjectInfo);
    if (state.state !== CHANNEL_STATE.BROKEN || !state.firstFallbackAt) return null;
    const target = Number(state.firstFallbackAt);
    const point = data.find((item) => item.timestamp >= target) || data[data.length - 1];
    return point?.time || null;
  }, [data, faultInjectInfo]);

  const averageThroughput = data.length > 0
    ? Math.round(data.reduce((sum, item) => sum + item.throughput, 0) / data.length)
    : 0;
  const latestLatency = data.length > 0 ? data[data.length - 1].avgLatencyMs : 0;

  const maxThroughput = data.length > 0 ? Math.max(...data.map(d => d.throughput)) : 0;
  const yMax = Math.max(100, maxThroughput, averageThroughput) * 1.2;

  const displayStatus = translateStatus(suiteState, translate);

  const handleFaultAreaClick = (event, area) => {
    event.stopPropagation();
    // faultRegions 只有区间；到 faultInjectInfo 里取完整记录（命令、参数、验证结论）
    const infos = (area.faults || []).map((region) => (
      faultInjectInfo.find((info) => (
        (region.faultID != null && info.faultID != null && String(info.faultID) === String(region.faultID))
        || (region.groupKey && info.groupKey === region.groupKey)
        || (region.faultName && info.faultName === region.faultName)
      )) || region
    ));
    const details = buildFaultDetails({
      archivedFaults: [],
      faultInjectInfo: infos,
      translate,
      lang,
    });
    setFaultPopover({
      details,
      anchor: { x: event.clientX, y: event.clientY },
    });
  };

  const CustomTooltip = ({ active, payload, label }) => {
    if (active && payload && payload.length) {
      return (
        <div style={{
          backgroundColor: '#fff',
          padding: '12px',
          border: '1px solid #e0e0e0',
          borderRadius: '6px',
          boxShadow: '0 4px 12px rgba(0,0,0,0.1)'
        }}>
          <p style={{ margin: '0 0 8px 0', fontWeight: 'bold', color: '#333' }}>{label || payload[0]?.payload?.time}</p>
          {payload.map((entry, index) => (
            <p key={index} style={{ margin: '4px 0', color: entry.color, fontSize: '14px', fontWeight: '500' }}>
              {entry.name}:
              <span style={{ marginLeft: '8px' }}>{entry.value}</span>
              {entry.dataKey === 'throughput' ? ' tpmC' : ' ms'}
            </p>
          ))}
        </div>
      );
    }
    return null;
  };

  if (data.length === 0) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', width: '100%', height: '500px', padding: '16px', backgroundColor: '#fff', borderRadius: '8px', boxShadow: '0 2px 8px rgba(0,0,0,0.05)', justifyContent: 'center', alignItems: 'center' }}>
        <p style={{ color: '#999', fontSize: '14px' }}>{translate('realtimeChart.noData') || '暂无性能数据，等待测试开始...'}</p>
      </div>
    );
  }

  return (
    <div
      style={{ display: 'flex', flexDirection: 'column', width: '100%', height: '500px', padding: '16px', backgroundColor: '#fff', borderRadius: '8px', boxShadow: '0 2px 8px rgba(0,0,0,0.05)' }}
      onClick={() => setFaultPopover(null)}
    >

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <h3 style={{ margin: 0, color: '#333', fontSize: '18px' }}>{translate('realtimeChart.title') || '实时性能图'}</h3>

        <div style={{ display: 'flex', gap: '16px', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: '12px', alignItems: 'center', backgroundColor: '#f9f9f9', padding: '6px 12px', borderRadius: '6px' }}>
            <span style={{ fontSize: '13px', fontWeight: '500', color: '#555' }}>
              {translate('realtimeChart.state') || 'State'}: <span style={{ color: '#1890ff' }}>{displayStatus}</span>
            </span>
            <span style={{ fontSize: '13px', fontWeight: '500', color: '#555' }}>
              {translate('realtimeChart.avgTps') || 'Avg TPS'}: <span style={{ color: '#4caf50' }}>{averageThroughput}</span>
            </span>
            <span style={{ fontSize: '13px', fontWeight: '500', color: '#555' }}>
              {translate('realtimeChart.avgLatency') || 'Avg Latency'}: <span style={{ color: '#fa8c16' }}>{latestLatency} ms</span>
            </span>
          </div>

          {faultRegions.length > 0 && (
            <div style={{ display: 'flex', gap: '6px', alignItems: 'center', backgroundColor: '#fff2f0', padding: '4px 10px', borderRadius: '4px', border: '1px solid #ffccc7' }}>
              <span style={{ width: 10, height: 10, borderRadius: 2, backgroundColor: 'rgba(255, 77, 79, 0.2)', border: '1px solid #ff4d4f' }} />
              <span style={{ fontSize: '12px', color: '#cf1322' }}>
                {translate('realtimeChart.faultActive') || '不利因素注入中'} ({faultRegions.length} adverse event{faultRegions.length > 1 ? 's' : ''}{faultAreas.length !== faultRegions.length ? `, ${faultAreas.length} shown` : ''})
              </span>
            </div>
          )}
          {faultRegions.length === 0 && data.length > 0 && (
            <div style={{ fontSize: '11px', color: '#bbb' }}>no adverse event regions</div>
          )}
        </div>
      </div>

      <div style={{ flex: 1, minHeight: 0, width: '100%' }}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 10, right: 10, left: 10, bottom: 10 }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#eaeaea" />
            <XAxis
              dataKey="time"
              interval="preserveStartEnd"
              tick={{ fontSize: 11, fill: '#888' }}
              minTickGap={30}
              axisLine={{ stroke: '#ccc' }}
              tickLine={{ stroke: '#ccc' }}
            />
            <YAxis
              yAxisId="left"
              domain={[0, yMax]}
              tick={{ fontSize: 12, fill: '#888' }}
              axisLine={{ stroke: '#ccc' }}
              tickLine={false}
              width={60}
              tickFormatter={(val) => val >= 1000 ? `${(val/1000).toFixed(1)}k` : val}
              tick={(props) => {
                const { x, y, payload, index, ticks } = props;
                if (ticks && index === ticks.length - 1) return null;
                return (
                  <text x={x} y={y} textAnchor="end" fill="#888" fontSize="12" dy={4}>
                    {payload.value >= 1000 ? `${(payload.value/1000).toFixed(1)}k` : payload.value}
                  </text>
                );
              }}
            />
            <YAxis
              yAxisId="right"
              orientation="right"
              domain={[0, 'auto']}
              tick={{ fontSize: 12, fill: '#888' }}
              axisLine={{ stroke: '#ccc' }}
              tickLine={false}
              width={40}
            />
            <Tooltip content={<CustomTooltip />} />
            <Legend wrapperStyle={{ paddingTop: '10px' }} />

            <ReferenceLine yAxisId="left" y={averageThroughput || 0} stroke="#ff9800" strokeDasharray="3 3" label={{ position: 'insideTopLeft', value: translate('realtimeChart.avgTps') || 'Avg TPS', fill: '#ff9800', fontSize: 12 }} />

            {/* Fault injection red shaded regions */}
            {faultAreas.map((area) => (
              <React.Fragment key={area.id}>
                <ReferenceArea
                  x1={area.x1}
                  x2={area.x2}
                  y1={0}
                  y2={yMax}
                  yAxisId="left"
                  fill="#ff4d4f"
                  fillOpacity={0.15}
                  stroke="#ff4d4f"
                  strokeWidth={1}
                  strokeOpacity={0.3}
                  style={{ cursor: 'pointer' }}
                  onClick={(event) => handleFaultAreaClick(event, area)}
                  label={area.label ? { value: area.label, position: 'insideTop', fill: '#cf1322', fontSize: 11, style: { pointerEvents: 'none' } } : undefined}
                />
                <ReferenceLine
                  key={`${area.id}-start`}
                  x={area.x1}
                  stroke="#cf1322"
                  strokeWidth={1.5}
                  strokeDasharray="4 3"
                  label={{ value: area.x1, position: 'insideBottom', fill: '#cf1322', fontSize: 10, offset: 5 }}
                />
                <ReferenceLine
                  key={`${area.id}-end`}
                  x={area.x2}
                  stroke="#cf1322"
                  strokeWidth={1.5}
                  strokeDasharray="4 3"
                  label={{ value: area.x2, position: 'insideBottom', fill: '#cf1322', fontSize: 10, offset: 5 }}
                />
              </React.Fragment>
            ))}

            {/* 长连接中途断开：第一次观测到回退 HTTP 的竖线 */}
            {channelFallbackX && (
              <ReferenceLine
                x={channelFallbackX}
                stroke="#b45309"
                strokeWidth={1.5}
                strokeDasharray="2 4"
                label={{
                  value: translate('channel.lineLabel'),
                  position: 'insideTopRight',
                  fill: '#b45309',
                  fontSize: 11,
                }}
              />
            )}

            <Line
              yAxisId="left"
              name={translate('realtimeChart.tooltip.throughput') || 'Throughput'}
              type="monotone"
              dataKey="throughput"
              stroke="#52c41a"
              strokeWidth={2.5}
              dot={false}
              activeDot={{ r: 6, fill: "#52c41a", stroke: '#fff', strokeWidth: 2 }}
              isAnimationActive={false}
            />
            <Line
              yAxisId="right"
              name={translate('realtimeChart.tooltip.latency') || 'Avg Latency'}
              type="monotone"
              dataKey="avgLatencyMs"
              stroke="#1890ff"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, fill: "#1890ff", stroke: '#fff', strokeWidth: 2 }}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>

      {faultPopover ? (
        <FaultDetailPopover
          details={faultPopover.details}
          anchor={faultPopover.anchor}
          onClose={() => setFaultPopover(null)}
        />
      ) : null}
    </div>
  );
};

export default RealTimeThroughputChart;
