import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Legend,
  ReferenceDot,
  ReferenceArea,
  ReferenceLine,
  Label,
  Customized,
} from 'recharts';
import "./ResilienceTriangleChart.css";
import { useLanguage } from '../../i18n';
import { resolveFaultDisplayName } from '../../config/faultConfig';
import { buildFaultDetails, effectiveGroupKey } from '../../utils/faultDetails';
import FaultDetailPopover from './FaultDetailPopover';

const RenderTriangle = ({ faultStart, performanceBottom, recoveryEnd, xAxis, yAxis }) => {
  if (!xAxis || !yAxis || !xAxis.scale || !yAxis.scale) {
    return null;
  }

  try {
    const xScale = xAxis.scale;
    const yScale = yAxis.scale;

    const points = [
      { x: xScale(faultStart.time), y: yScale(faultStart.throughput) },
      { x: xScale(performanceBottom.time), y: yScale(performanceBottom.throughput) },
      { x: xScale(recoveryEnd.time), y: yScale(recoveryEnd.throughput) },
    ];

    if (points.some((p) => Number.isNaN(p.x) || Number.isNaN(p.y))) {
      return null;
    }

    return (
      <polygon
        points={points.map((p) => `${p.x},${p.y}`).join(' ')}
        fill="rgba(255, 0, 0, 0.15)"
        stroke="red"
        strokeWidth={2}
      />
    );
  } catch {
    return null;
  }
};

const ResilienceTriangleChart = ({
  data = [],
  baseline,
  triangles = [],
  faultInjectInfo = [],
  // 历史报告传入归档的故障配置（run.faults），点击红带时可展示当时的参数
  faults = [],
  height = 300,
}) => {
  const { translate, lang } = useLanguage();
  const [faultPopover, setFaultPopover] = useState(null);
  const prepared = useMemo(() => {
    if (!Array.isArray(data) || data.length === 0) {
      return { series: [], baselineValue: 0, autoTriangles: [], faultZones: [], recoveryZones: [], convertedBackendTriangles: [] };
    }

    const normalized = data
      .map((item, index) => {
        const rawX = Number(item?.time ?? item?.timestamp ?? index);
        const y = Number(item?.throughput ?? item?.tps ?? item?.value);
        return {
          rawX: Number.isFinite(rawX) ? rawX : index,
          throughput: Number.isFinite(y) ? y : 0,
        };
      })
      .filter((item) => Number.isFinite(item.rawX) && Number.isFinite(item.throughput))
      .sort((a, b) => a.rawX - b.rawX);

    if (!normalized.length) {
      return { series: [], baselineValue: 0, autoTriangles: [], faultZones: [], recoveryZones: [], convertedBackendTriangles: [] };
    }

    const firstX = normalized[0].rawX;
    const useEpoch = firstX > 1e10;
    const series = normalized.map((point, idx) => ({
      time: useEpoch ? Math.round((point.rawX - firstX) / 1000) : point.rawX,
      throughput: point.throughput,
      _index: idx,
    }));

    const baselineValue = Number.isFinite(Number(baseline))
      ? Number(baseline)
      : (() => {
          const seed = series.slice(0, Math.min(8, series.length));
          if (!seed.length) return 0;
          return seed.reduce((sum, item) => sum + item.throughput, 0) / seed.length;
        })();

    const autoTriangles = [];
    if (series.length >= 3 && baselineValue > 0) {
      const dropThreshold = baselineValue * 0.9;
      const recoverThreshold = baselineValue * 0.95;
      let cursor = 1;
      while (cursor < series.length) {
        const prev = series[cursor - 1];
        const curr = series[cursor];
        if (prev.throughput >= dropThreshold && curr.throughput < dropThreshold) {
          const faultStart = curr;
          let bottom = curr;
          let j = cursor + 1;
          while (j < series.length) {
            if (series[j].throughput < bottom.throughput) {
              bottom = series[j];
            }
            if (j > bottom._index && series[j].throughput >= recoverThreshold) {
              autoTriangles.push([faultStart, bottom, series[j]]);
              cursor = j + 1;
              break;
            }
            j += 1;
          }
          if (j >= series.length) {
            break;
          }
        } else {
          cursor += 1;
        }
      }
    }

    const faultZones = [];
    const recoveryZones = [];
    const RECOVERY_EXTRA_SEC = 5;
    const hasFaultTriangles = Array.isArray(triangles) && triangles.length > 0 && triangles[0] != null && 'faultTStartMs' in triangles[0];
    // 优先使用实际注入时间（faultInjectInfo），不受算法检测偏移影响
    if (useEpoch && Array.isArray(faultInjectInfo) && faultInjectInfo.length > 0) {
      // 先收集所有单个故障区间
      const rawZones = [];
      for (const f of faultInjectInfo) {
        if (!f.startTime || f.startTime <= 0) continue;
        const zoneStart = Math.max(0, Math.round((f.startTime - firstX) / 1000));
        const zoneEnd = f.endTime > 0
          ? Math.round((f.endTime - firstX) / 1000)
          : series.length > 0 ? series[series.length - 1].time : zoneStart + 60;
        if (zoneStart < zoneEnd) {
          rawZones.push({
            zoneStart,
            zoneEnd,
            faultName: resolveFaultDisplayName(f, translate, lang),
            groupKey: f.groupKey || '',
            info: f,
          });
        }
      }
      // 按 groupKey 合并同一组的区间
      const merged = {};
      for (const z of rawZones) {
        const key = z.groupKey || z.faultName + z.zoneStart;
        if (!merged[key]) {
          merged[key] = { ...z, faultNames: [z.faultName], infos: [z.info] };
        } else {
          merged[key].zoneStart = Math.min(merged[key].zoneStart, z.zoneStart);
          merged[key].zoneEnd = Math.max(merged[key].zoneEnd, z.zoneEnd);
          merged[key].infos.push(z.info);
          if (!merged[key].faultNames.includes(z.faultName)) {
            merged[key].faultNames.push(z.faultName);
          }
        }
      }
      for (const z of Object.values(merged)) {
        const displayName = z.faultNames.length > 1 && z.groupKey
          ? z.faultNames.join(' + ')
          : z.faultName;
        const extendedEnd = Math.min(z.zoneEnd + RECOVERY_EXTRA_SEC, series.length > 0 ? series[series.length - 1].time : z.zoneEnd + RECOVERY_EXTRA_SEC);
        faultZones.push({
          zoneStart: z.zoneStart,
          zoneEnd: extendedEnd,
          faultName: displayName,
          groupKey: z.groupKey,
          infos: z.infos,
        });
      }
    } else if (useEpoch && hasFaultTriangles) {
      for (const t of triangles) {
        const zoneStart = Math.max(0, Math.round((Number(t.faultTStartMs) - firstX) / 1000));
        const zoneEnd = series.length > 0
          ? Math.min(Math.round((Number(t.faultTEndMs) - firstX) / 1000), series[series.length - 1].time)
          : zoneStart + 60;
        if (zoneStart < zoneEnd) {
          const extendedEnd = Math.min(zoneEnd + RECOVERY_EXTRA_SEC, series.length > 0 ? series[series.length - 1].time : zoneEnd + RECOVERY_EXTRA_SEC);
          faultZones.push({
            zoneStart,
            zoneEnd: extendedEnd,
            faultName: resolveFaultDisplayName(t.faultName || '', translate, lang),
            groupKey: '',
            infos: [],
          });
        }
      }
    }

    // 尝试将后端传入的三角形数据（epoch ms 格式）转换为图表使用的相对秒格式
    let convertedBackendTriangles = [];
    if (Array.isArray(triangles) && triangles.length > 0 && triangles[0] != null && typeof triangles[0] === 'object') {
      if ('tStartMs' in triangles[0]) {
        // 从 series 中查找最接近的时间点的 TPS，保证点落在曲线上
        const snapTps = (time) => {
          if (!series.length) return 0;
          const nearest = series.reduce((best, p) =>
            Math.abs(p.time - time) < Math.abs(best.time - time) ? p : best
          );
          return nearest.throughput;
        };
        convertedBackendTriangles = triangles.map(t => {
          const hasFault = 'faultTStartMs' in t;
          const startMs = hasFault ? Number(t.faultTStartMs) : Number(t.tStartMs);
          const minMs = hasFault ? Number(t.faultTMinMs) : Number(t.tMinMs);
          const endMs = hasFault ? Number(t.faultTEndMs) : Number(t.tEndMs);
          const startTime = useEpoch ? Math.round((startMs - firstX) / 1000) : startMs;
          const minTime = useEpoch ? Math.round((minMs - firstX) / 1000) : minMs;
          const endTime = useEpoch ? Math.round((endMs - firstX) / 1000) : endMs;
          return [
            { time: startTime, throughput: snapTps(startTime) },
            { time: minTime, throughput: snapTps(minTime) },
            { time: endTime, throughput: snapTps(endTime) },
          ];
        });
      }
    }

    return { series, baselineValue, autoTriangles, faultZones, recoveryZones, convertedBackendTriangles };
  }, [baseline, data, faultInjectInfo, triangles, translate, lang]);

  const trianglesToRender = prepared.convertedBackendTriangles.length > 0
    ? prepared.convertedBackendTriangles
    : (Array.isArray(triangles) && triangles.length > 0 && !('tStartMs' in (triangles[0] || {}))
        ? triangles
        : prepared.autoTriangles);

  const yDomain = useMemo(() => {
    const seriesMax = prepared.series.reduce((max, p) => Math.max(max, p.throughput), 0);
    const triMax = trianglesToRender.reduce((max, tri) => Math.max(max, ...tri.map(p => p.throughput)), 0);
    const maxVal = Math.max(seriesMax, triMax, 1);
    return [0, Math.ceil(maxVal * 1.1)];
  }, [prepared.series, trianglesToRender]);

  const containerRef = useRef(null);
  const [containerWidth, setContainerWidth] = useState(0);
  useEffect(() => {
    const element = containerRef.current;
    if (!element) return undefined;
    const update = () => setContainerWidth(element.clientWidth || 0);
    update();
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(update);
      observer.observe(element);
      return () => observer.disconnect();
    }
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, [prepared.series.length]);

  const PIXEL_PER_POINT = 4;
  const chartWidth = Math.max(
    (prepared.series.length || 0) * PIXEL_PER_POINT,
    containerWidth || 800
  );

  const handleZoneClick = (event, zone) => {
    event.stopPropagation();
    const zoneGroup = zone.groupKey || '';
    const archived = zoneGroup
      ? (Array.isArray(faults) ? faults : []).filter(
          (fault, index) => effectiveGroupKey(fault, index) === zoneGroup
        )
      : [];
    const infos = Array.isArray(zone.infos) && zone.infos.length > 0
      ? zone.infos
      : (Array.isArray(faultInjectInfo)
          ? faultInjectInfo.filter((info) => !zoneGroup || info.groupKey === zoneGroup)
          : []);
    const details = buildFaultDetails({
      archivedFaults: archived,
      faultInjectInfo: infos,
      translate,
      lang,
    });
    setFaultPopover({
      details,
      anchor: { x: event.clientX, y: event.clientY },
    });
  };

  if (prepared.series.length === 0) {
    return (
      <div style={{ width: '100%', padding: '20px', textAlign: 'center', color: '#94a3b8' }}>
        {translate('history.report.noRealMonitorData')}
      </div>
    );
  }

  return (
    <div
      style={{ width: '100%', padding: '20px', display: 'flex', flexDirection: 'column' }}
      onClick={() => setFaultPopover(null)}
    >
      <h3 style={{ textAlign: 'center', marginBottom: '10px' }}>
        {translate('RealTime.title')}
      </h3>
      <div ref={containerRef} style={{ overflowX: 'auto', width: '100%' }}>
        <div style={{ width: chartWidth }}>
          <LineChart
            data={prepared.series}
            width={chartWidth}
            height={height}
            margin={{ top: 20, right: 30, left: 60, bottom: 50 }}
          >
          <CartesianGrid strokeDasharray="3 3" />

            <XAxis
              dataKey="time"
              type="number"
              domain={['auto', 'auto']}
              label={{ value: translate('RealTime.time'), position: 'bottom', offset: 10 }}
              tick={{ fontSize: 12 }}
            />

            <YAxis
              dataKey="throughput"
              domain={yDomain}
              label={{ value: translate('RealTime.throughput'), angle: -90, position: 'insideLeft', offset: 10 }}
              tick={{ fontSize: 12 }}
            />

          <Line
            type="monotone"
            dataKey="throughput"
            stroke="#007acc"
            strokeWidth={2}
            dot={false}
            name={translate('RealTime.throughput')}
          />

          {prepared.baselineValue > 0 && (
            <Line
              type="linear"
              dataKey={() => prepared.baselineValue}
              stroke="#00aa00"
              strokeWidth={2}
              strokeDasharray="5 5"
              dot={false}
              name={translate('RealTime.baseline')}
            />
          )}

          {trianglesToRender.length > 0 && (
            <Customized
              component={({ xAxis, yAxis }) => {
                return (
                  <g>
                    {trianglesToRender.map((tri, triIdx) => {
                      const [faultStart, performanceBottom, recoveryEnd] = tri;
                      return (
                        <RenderTriangle
                          key={`tri-poly-${triIdx}`}
                          faultStart={faultStart}
                          performanceBottom={performanceBottom}
                          recoveryEnd={recoveryEnd}
                          xAxis={xAxis}
                          yAxis={yAxis}
                        />
                      );
                    })}
                  </g>
                );
              }}
            />
          )}
          {trianglesToRender.map((tri, triIdx) => {
            const [faultStart, performanceBottom, recoveryEnd] = tri;
            return (
              <React.Fragment key={`triangle-${triIdx}`}>
                <ReferenceDot
                  x={faultStart.time}
                  y={faultStart.throughput}
                  r={6}
                  fill="red"
                  stroke="#fff"
                  strokeWidth={2}
                />
                <ReferenceDot
                  x={performanceBottom.time}
                  y={performanceBottom.throughput}
                  r={6}
                  fill="red"
                  stroke="#fff"
                  strokeWidth={2}
                />
                <ReferenceDot
                  x={recoveryEnd.time}
                  y={recoveryEnd.throughput}
                  r={6}
                  fill="red"
                  stroke="#fff"
                  strokeWidth={2}
                />
              </React.Fragment>
            );
          })}

          <Legend
            layout="horizontal"
            align="left"
            verticalAlign="bottom"
            height={40}
          />

          {prepared.faultZones.map((zone, i) => (
            <React.Fragment key={`fault-${i}`}>
              <ReferenceLine
                x={zone.zoneStart}
                stroke="#ff4d4f"
                strokeDasharray="3 3"
                strokeOpacity={0.4}
                label={{
                  value: `${zone.zoneStart}s`,
                  position: 'bottom',
                  fill: '#ff4d4f',
                  fontSize: 10,
                  offset: 5,
                }}
              />
              <ReferenceLine
                x={zone.zoneEnd}
                stroke="#ff4d4f"
                strokeDasharray="3 3"
                strokeOpacity={0.25}
                label={{
                  value: `${zone.zoneEnd}s`,
                  position: 'bottom',
                  fill: '#ff4d4f',
                  fontSize: 9,
                  offset: 15,
                }}
              />
              <ReferenceArea
                x1={zone.zoneStart}
                x2={zone.zoneEnd}
                fill="#ff4d4f"
                fillOpacity={0.15}
                stroke="#ff4d4f"
                strokeOpacity={0.4}
                strokeWidth={1}
                style={{ cursor: 'pointer' }}
                onClick={(event) => handleZoneClick(event, zone)}
              >
                {zone.faultName && (
                  <Label
                    value={zone.faultName}
                    position="insideTop"
                    fill="#ff4d4f"
                    fontSize={10}
                    offset={5}
                    style={{ pointerEvents: 'none' }}
                  />
                )}
              </ReferenceArea>
            </React.Fragment>
          ))}
        </LineChart>
      </div>
      </div>

      {faultPopover ? (
        <FaultDetailPopover
          details={faultPopover.details}
          anchor={faultPopover.anchor}
          onClose={() => setFaultPopover(null)}
        />
      ) : null}

      {/* trianglesToRender文字显示暂时注释
      {trianglesToRender.length > 0 && (
        <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#475569' }}>
          {trianglesToRender.map((tri, i) => (
            <div key={`tri-text-${i}`} style={{ display: 'flex', gap: 12, justifyContent: 'center', flexWrap: 'wrap' }}>
              <span style={{ fontWeight: 'bold' }}>#{i + 1}:</span>
              <span>{translate('RealTime.dot.start')}: t={tri[0].time}s, TPS={Math.round(tri[0].throughput)}</span>
              <span>{translate('RealTime.dot.buttom')}: t={tri[1].time}s, TPS={Math.round(tri[1].throughput)}</span>
              <span>{translate('RealTime.dot.recovery')}: t={tri[2].time}s, TPS={Math.round(tri[2].throughput)}</span>
            </div>
          ))}
        </div>
      )}
      */}
    </div>
  );
};

export default ResilienceTriangleChart;
