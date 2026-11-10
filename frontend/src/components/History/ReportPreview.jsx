import React, { useMemo, useRef, useState } from 'react';
import html2canvas from 'html2canvas';
import jsPDF from 'jspdf';
import styles from './ReportPreview.module.css';
import { useLanguage } from '../../i18n';
import ResilienceTriangleChart from '../RealTimeChart/ResilienceTriangleChart';
import RadarChart from '../ScoreChart/RadarChart';
import { buildFaultDetails } from '../../utils/faultDetails';

const VERIFICATION_META = {
  ENFORCED: { color: '#15803d', bg: '#dcfce7', border: '#86efac' },
  NOT_ENFORCED: { color: '#b91c1c', bg: '#fee2e2', border: '#fca5a5' },
  ERROR: { color: '#c2410c', bg: '#ffedd5', border: '#fdba74' },
  UNKNOWN: { color: '#525252', bg: '#f5f5f5', border: '#d4d4d4' },
  VERIFYING: { color: '#1d4ed8', bg: '#dbeafe', border: '#93c5fd' },
  PENDING: { color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb' },
};

function formatBytes(bytes) {
  const value = Number(bytes);
  if (!Number.isFinite(value) || value <= 0) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[index]}`;
}

const ReportPreview = ({
  run,
  radarScores,
  scoresLoading,
  reportResult,
  reportMonitorData,
  reportTriangles,
  reportFaultInjectInfo,
  onClose,
}) => {
  const { translate, lang } = useLanguage();
  const reportRef = useRef(null);
  const [isGenerating, setIsGenerating] = useState(false);

  const tr = (key, fallback) => {
    const value = translate(key);
    return value === key ? fallback : value;
  };

  const safeRun = run || {};
  const safeScores = useMemo(
    () => (Array.isArray(radarScores) ? radarScores : []),
    [radarScores]
  );
  const resultPayload = reportResult?.data && typeof reportResult.data === 'object'
    ? reportResult.data
    : (reportResult && typeof reportResult === 'object' ? reportResult : {});

  const pickFirst = (...values) => {
    for (const value of values) {
      if (value !== undefined && value !== null && value !== '') {
        return value;
      }
    }
    return undefined;
  };

  const formatConfigValue = (value) => {
    if (value === undefined || value === null || value === '') return '-';
    if (Array.isArray(value)) return value.length ? value.join(', ') : '-';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  };

  const isZh = lang !== 'en';

  // ---- 全部配置来自这一轮的历史归档，不用当前表单的 Redux 状态 ----
  const dbParams = safeRun.dbParams && typeof safeRun.dbParams === 'object' ? safeRun.dbParams : {};
  const workloadParams = safeRun.workloadParams && typeof safeRun.workloadParams === 'object'
    ? safeRun.workloadParams
    : {};
  const runOptions = safeRun.runOptions && typeof safeRun.runOptions === 'object' ? safeRun.runOptions : null;
  const resources = safeRun.resources && typeof safeRun.resources === 'object' ? safeRun.resources : null;
  const archivedFaults = Array.isArray(safeRun.faults) ? safeRun.faults : [];
  const dockerizedKnown = typeof safeRun.dockerized === 'boolean';
  const dockerized = dockerizedKnown ? safeRun.dockerized : Boolean(safeRun.containerName);
  const resourcesUnavailable = resources ? resources.available === false : false;
  const containerAvailable = resources?.container ? resources.container.available !== false : false;

  const t = {
    title: isZh ? '数据库韧性评估报告' : 'Database Resilience Assessment Report',
    testProfile: isZh ? '测试档案' : 'Test Profile',
    keyMetrics: isZh ? '关键韧性指标' : 'Key Resilience Metrics',
    visualAnalysis: isZh ? '可视化分析' : 'Visual Analysis',
    metricBreakdown: isZh ? '维度评分明细' : 'Metric Breakdown',
    conclusion: isZh ? '结论摘要' : 'Executive Conclusion',
    runName: isZh ? '测试名称' : 'Run Name',
    database: isZh ? '数据库' : 'Database',
    dbVersion: isZh ? '数据库版本' : 'Database Version',
    startTime: isZh ? '开始时间' : 'Start Time',
    endTime: isZh ? '结束时间' : 'End Time',
    overallScore: isZh ? '综合评分' : 'Overall Score',
    grade: isZh ? '等级' : 'Grade',
    strongest: isZh ? '最高单项' : 'Strongest Dimension',
    spread: isZh ? '分数离散度' : 'Score Spread',
    averageHint: isZh ? '所有维度分数平均值' : 'Average of all resilience dimensions',
    gradeHint: isZh ? '依据阈值自动评级' : 'Auto-rated from score threshold',
    strongestHint: isZh ? '单维度最高分' : 'Highest single-dimension score',
    spreadHint: isZh ? '最高分与最低分差值' : 'Difference between max and min',
    radarView: isZh ? '韧性雷达总览' : 'Resilience Radar Overview',
    triangleView: isZh ? '韧性三角（TPS影响）' : 'Resilience Triangle (TPS Impact)',
    dimension: isZh ? '维度' : 'Dimension',
    score: isZh ? '分数' : 'Score',
    level: isZh ? '评价' : 'Level',
    reportId: isZh ? '报告编号' : 'Report ID',
    generated: isZh ? '生成时间' : 'Generated',
    executiveSummary: isZh ? '执行摘要' : 'Executive Summary',
    summaryLine1: isZh
      ? '本报告基于当前测试记录自动生成，集中呈现系统在故障扰动下的韧性表现。'
      : 'This report is automatically generated from the selected test run and highlights system resilience under adverse event disturbances.',
    summaryLine2: isZh
      ? '建议将本次结果作为后续回归测试基线，持续对比关键维度变化趋势。'
      : 'Use this result as a baseline for future regression runs and compare trend shifts across key dimensions.',
    tagStatus: isZh ? '状态' : 'Status',
    tagTop: isZh ? '最佳维度' : 'Top Dimension',
    tagWeak: isZh ? '薄弱维度' : 'Weak Dimension',
    testConfig: isZh ? '测试配置（归档快照）' : 'Test Configuration (Archived Snapshot)',
    dbConfig: isZh ? '数据库配置' : 'Database Configuration',
    workloadConfig: isZh ? '负载配置' : 'Workload Configuration',
    faultConfig: isZh ? '故障注入配置' : 'Fault Injection Configuration',
    deploymentType: isZh ? '部署类型' : 'Deployment Type',
    dbType: isZh ? '数据库类型' : 'Database Type',
    docker: isZh ? '是否使用 Docker' : 'Docker',
    containerName: isZh ? '容器名称' : 'Container Name',
    containerStatus: isZh ? '容器状态' : 'Container Status',
    containerImage: isZh ? '容器镜像' : 'Container Image',
    containerCpu: isZh ? '容器 CPU 限额' : 'Container CPU Limit',
    containerMemory: isZh ? '容器内存限额' : 'Container Memory Limit',
    hostCpu: isZh ? '宿主机 CPU' : 'Host CPU',
    hostMemory: isZh ? '宿主机内存' : 'Host Memory',
    dbParams: isZh ? '数据库参数' : 'Database Parameters',
    workloadType: isZh ? '负载类型' : 'Workload Type',
    workloadParams: isZh ? '负载参数' : 'Workload Parameters',
    runOptions: isZh ? '运行选项' : 'Run Options',
    monitorInterval: isZh ? '监控采样间隔' : 'Monitor Interval',
    runCreate: isZh ? '建库' : 'Create',
    runLoad: isZh ? '装载' : 'Load',
    runExecute: isZh ? '执行' : 'Execute',
    runReload: isZh ? '重新装载' : 'Reload',
    faultCount: isZh ? '故障数量' : 'Fault Count',
    faultGroup: isZh ? '故障组' : 'Group',
    faultDelay: isZh ? '注入时刻' : 'Injection time',
    faultDuration: isZh ? '计划持续' : 'Duration',
    faultStart: isZh ? '开始' : 'Start',
    faultEnd: isZh ? '结束' : 'End',
    faultParams: isZh ? '参数' : 'Parameters',
    faultNoParams: isZh ? '未记录参数' : 'No parameters recorded',
    faultCommands: isZh ? '注入 / 恢复命令' : 'Injection / recovery commands',
    faultEmpty: isZh ? '本轮没有注入故障' : 'No faults injected in this run',
    standaloneFault: isZh ? '独立故障' : 'Standalone',
    fromCommand: isZh ? '由注入命令解析' : 'Parsed from command',
    fromArchive: isZh ? '归档配置' : 'Archived config',
    none: isZh ? '无' : 'None',
    yes: isZh ? '是' : 'Yes',
    no: isZh ? '否' : 'No',
    notLimited: isZh ? '未限制' : 'Not limited',
    notCollected: isZh ? '未采集' : 'Not collected',
    notArchived: isZh ? '未归档' : 'Not archived',
    connectionNotArchived: isZh
      ? '连接信息（IP / 端口 / 用户名 / 密码）按设计不写入归档'
      : 'Connection details (IP / port / username / password) are not archived by design',
    seconds: isZh ? '秒' : 's',
    accountsNum: tr('Workload.smallbank.accountsNum', isZh ? '账户数量' : 'Accounts Count'),
    terminals: tr('Workload.smallbank.terminals', isZh ? '并发终端数' : 'Concurrent Terminals'),
    duration: isZh ? '持续时长' : 'Duration',
    warehouses: isZh ? '仓库数' : 'Warehouses',
    concurrency: isZh ? '并发数' : 'Concurrency',
    amalgamateWeight: tr('Workload.smallbank.amalgamateWeight', 'Amalgamate'),
    balanceWeight: tr('Workload.smallbank.balanceWeight', 'Balance'),
    depositCheckingWeight: tr('Workload.smallbank.depositCheckingWeight', 'DepositChecking'),
    sendPaymentWeight: tr('Workload.smallbank.sendPaymentWeight', 'SendPayment'),
    transactSavingsWeight: tr('Workload.smallbank.transactSavingsWeight', 'TransactSavings'),
    writeCheckWeight: tr('Workload.smallbank.writeCheckWeight', 'WriteCheck'),
    weightSuffix: tr('history.report.weightSuffix', '(%)'),
    missingDataPrefix: tr('history.report.missingDataPrefix', isZh ? '归档数据缺失字段：' : 'Missing archived fields:'),
    missingDatabase: tr('history.report.missing.databaseType', isZh ? '数据库配置: 类型/版本' : 'Database config: type/version'),
    missingWorkload: tr('history.report.missing.workloadMode', isZh ? '负载配置: workload' : 'Workload config: workload'),
    missingScores: tr('history.report.missing.scores', isZh ? '评分数据: radar scores' : 'Score data: radar scores'),
    missingMonitor: tr('history.report.missing.monitor', isZh ? '图表数据: monitorData/tpsData' : 'Chart data: monitorData/tpsData'),
    missingResources: isZh ? '资源信息: CPU / 内存' : 'Resource info: CPU / memory',
  };

  const reportTitle = tr('history.report.title', t.title);
  const generatedAt = new Date().toLocaleString();
  const reportId = `${safeRun.id || 'NA'}-${new Date().toISOString().slice(0, 10)}`;
  const runDisplayName = safeRun.historyName || safeRun.runId || safeRun.runName || '-';
  const dbType = safeRun.dbType || '';
  const dbTypeLabel = safeRun.dbTypeRaw || dbType;
  const dbVersion = safeRun.dbVersion || safeRun.DBVersion || '';
  const dbDisplay = [dbTypeLabel, dbVersion].filter(Boolean).join(' ') || t.notArchived;

  const dockerText = dockerizedKnown || safeRun.containerName
    ? (dockerized ? t.yes : t.no)
    : t.notArchived;

  const containerInfo = resources?.container || null;
  const containerCpuLimit = containerInfo?.cpuLimit;
  const containerCpuText = !dockerized
    ? '—'
    : containerCpuLimit != null
      ? `${containerCpuLimit} ${isZh ? '核' : 'cores'}`
      : containerAvailable
        ? t.notLimited
        : (containerInfo || resources ? t.notCollected : t.notArchived);

  const containerMemoryBytes = containerInfo?.memoryLimitBytes;
  const containerMemoryText = !dockerized
    ? '—'
    : containerMemoryBytes
      ? formatBytes(containerMemoryBytes)
      : containerAvailable
        ? t.notLimited
        : (containerInfo || resources ? t.notCollected : t.notArchived);

  const hostCpu = resources?.host?.cpuCount;
  const hostCpuText = hostCpu != null
    ? `${hostCpu} ${isZh ? '核' : 'cores'}`
    : (resources ? t.notCollected : t.notArchived);

  const hostMemoryText = resources?.host?.memoryBytes
    ? formatBytes(resources.host.memoryBytes)
    : (resources ? t.notCollected : t.notArchived);

  const workloadParamLabels = {
    accountsNum: t.accountsNum,
    terminals: t.terminals,
    duration: t.duration,
    warehouses: t.warehouses,
    concurrency: t.concurrency,
    amalgamateWeight: `${t.amalgamateWeight} ${t.weightSuffix}`,
    balanceWeight: `${t.balanceWeight} ${t.weightSuffix}`,
    depositCheckingWeight: `${t.depositCheckingWeight} ${t.weightSuffix}`,
    sendPaymentWeight: `${t.sendPaymentWeight} ${t.weightSuffix}`,
    transactSavingsWeight: `${t.transactSavingsWeight} ${t.weightSuffix}`,
    writeCheckWeight: `${t.writeCheckWeight} ${t.weightSuffix}`,
  };
  const workloadItems = Object.entries(workloadParams).map(([key, value]) => ({
    key,
    label: workloadParamLabels[key] || key,
    value,
  }));
  const runOptionItems = runOptions
    ? [
        { key: 'monitorIntervalSec', label: t.monitorInterval, value: runOptions.monitorIntervalSec != null ? `${runOptions.monitorIntervalSec} ${t.seconds}` : null },
        { key: 'create', label: t.runCreate, value: typeof runOptions.create === 'boolean' ? (runOptions.create ? t.yes : t.no) : null },
        { key: 'load', label: t.runLoad, value: typeof runOptions.load === 'boolean' ? (runOptions.load ? t.yes : t.no) : null },
        { key: 'execute', label: t.runExecute, value: typeof runOptions.execute === 'boolean' ? (runOptions.execute ? t.yes : t.no) : null },
        { key: 'reload', label: t.runReload, value: typeof runOptions.reload === 'boolean' ? (runOptions.reload ? t.yes : t.no) : null },
      ].filter((item) => item.value !== null && item.value !== undefined)
    : [];

  const faultDetails = buildFaultDetails({
    archivedFaults,
    faultInjectInfo: reportFaultInjectInfo,
    translate,
    lang,
  });

  const computedSummary = useMemo(() => {
    const values = safeScores
      .map((item) => Number(item.value))
      .filter((value) => Number.isFinite(value));

    const average = values.length
      ? Math.round(values.reduce((acc, value) => acc + value, 0) / values.length)
      : 0;

    const maxScore = values.length ? Math.max(...values) : 0;
    const minScore = values.length ? Math.min(...values) : 0;
    const grade = average >= 85 ? 'A' : average >= 70 ? 'B' : average >= 55 ? 'C' : 'D';

    return {
      average,
      maxScore,
      minScore,
      grade,
      range: maxScore - minScore,
    };
  }, [safeScores]);

  const metricHighlights = useMemo(() => {
    if (!safeScores.length) {
      return { best: null, weakest: null };
    }
    const sorted = [...safeScores].sort((a, b) => Number(b.value) - Number(a.value));
    return {
      best: sorted[0],
      weakest: sorted[sorted.length - 1],
    };
  }, [safeScores]);

  const triangleSource = Array.isArray(reportMonitorData) && reportMonitorData.length > 0
    ? reportMonitorData
    : (Array.isArray(resultPayload?.tpsData)
      ? resultPayload.tpsData
      : (Array.isArray(resultPayload?.monitorData) ? resultPayload.monitorData : []));
  const triangleData = triangleSource
    .map((item, index) => {
      const rawTime = Number(item?.time ?? item?.timestamp ?? index);
      const throughput = Number(item?.throughput ?? item?.tps ?? item?.value);
      return {
        time: Number.isFinite(rawTime) ? rawTime : index,
        throughput: Number.isFinite(throughput) ? throughput : 0,
      };
    })
    .filter((item) => Number.isFinite(item.time) && Number.isFinite(item.throughput));
  const triangleBaseline = Number(
    pickFirst(resultPayload?.baseline, resultPayload?.baseLine, resultPayload?.baselineTps)
  );

  const missingFields = useMemo(() => {
    const missing = [];
    if (!dbType || !dbVersion) {
      missing.push(t.missingDatabase);
    }
    if (!safeRun.workload) {
      missing.push(t.missingWorkload);
    }
    if (!safeScores.length) {
      missing.push(t.missingScores);
    }
    if (triangleData.length === 0) {
      missing.push(t.missingMonitor);
    }
    if (!resources || resourcesUnavailable) {
      missing.push(t.missingResources);
    }
    return missing;
  }, [dbType, dbVersion, resources, resourcesUnavailable, safeRun.workload, safeScores.length, t.missingDatabase, t.missingMonitor, t.missingResources, t.missingScores, t.missingWorkload, triangleData.length]);

  const handleDownloadPdf = async () => {
    if (!reportRef.current) return;

    try {
      setIsGenerating(true);

      const canvas = await html2canvas(reportRef.current, {
        scale: 2,
        useCORS: true,
        logging: false,
        backgroundColor: '#f5f7fb',
      });

      const imgData = canvas.toDataURL('image/jpeg', 0.98);
      const pdf = new jsPDF({
        orientation: 'portrait',
        unit: 'mm',
        format: 'a4',
      });

      const pdfWidth = pdf.internal.pageSize.getWidth();
      const pageHeight = pdf.internal.pageSize.getHeight();
      const imgHeight = (canvas.height * pdfWidth) / canvas.width;

      let renderedHeight = 0;
      while (renderedHeight < imgHeight) {
        const position = -renderedHeight;
        pdf.addImage(imgData, 'JPEG', 0, position, pdfWidth, imgHeight);
        renderedHeight += pageHeight;
        if (renderedHeight < imgHeight) {
          pdf.addPage();
        }
      }

      const safeName = `${runDisplayName || 'test-report'}`.replace(/[\\/:*?"<>|]/g, '_');
      pdf.save(`Report_${safeName}_${new Date().toISOString().split('T')[0]}.pdf`);
    } catch (error) {
      console.error('Error generating PDF:', error);
      alert(
        tr('history.report.generateFailed',
          'Failed to generate report. Please try again.'
        )
      );
    } finally {
      setIsGenerating(false);
    }
  };

  const statusClass = styles[safeRun.status?.toLowerCase()] || styles.normal;
  const statusLabel = (() => {
    const statusKey = String(safeRun.status || '').toLowerCase();
    if (lang === 'en') {
      if (statusKey === 'normal') return 'Normal';
      if (statusKey === 'error') return 'Error';
      if (statusKey === 'running') return 'Running';
      if (statusKey === 'stopped') return 'Stopped';
      return safeRun.status;
    }
    if (statusKey === 'normal') return '正常';
    if (statusKey === 'error') return '异常';
    if (statusKey === 'running') return '运行中';
    if (statusKey === 'stopped') return '已停止';
    return safeRun.status;
  })();

  if (!run) return null;

  return (
    <div className={styles.modalOverlay} onClick={onClose}>
      <div className={styles.modalContent} onClick={(event) => event.stopPropagation()}>
        <div className={styles.modalHeader}>
          <h2>{tr('history.report.preview', isZh ? '测试报告预览' : 'Report Preview')}</h2>
          <div className={styles.headerActions}>
            <button className={styles.closeBtn} onClick={onClose} disabled={isGenerating}>
              {tr('history.report.cancel', isZh ? '关闭' : 'Close')}
            </button>
            <button
              className={styles.downloadBtn}
              onClick={handleDownloadPdf}
              disabled={isGenerating}
            >
              {isGenerating
                ? tr('history.report.generating', isZh ? '正在生成...' : 'Generating...')
                : tr('history.report.exportPdf', isZh ? '导出 PDF' : 'Export PDF')}
            </button>
          </div>
        </div>

        <div className={styles.modalBody}>
          <div className={styles.reportContainer} ref={reportRef}>
            <section className={styles.coverSection}>
              <div className={styles.coverContent}>
                <div className={styles.coverTop}>
                  <div className={styles.coverTitleGroup}>
                    <h1>{reportTitle}</h1>
                    <p className={styles.coverRunName}>
                      {t.runName}: {runDisplayName}
                    </p>
                  </div>
                  <div className={styles.coverMetaPanel}>
                    <div className={styles.metaItem}>
                      <span className={styles.metaLabel}>{t.reportId}</span>
                      <span className={styles.metaValue}>{reportId}</span>
                    </div>
                    <div className={styles.metaItem}>
                      <span className={styles.metaLabel}>{t.generated}</span>
                      <span className={styles.metaValue}>{generatedAt}</span>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <section className={styles.overviewSection}>
              <div className={styles.sectionHead}>
                <h3>{t.executiveSummary}</h3>
              </div>
              <p className={styles.overviewText}>{t.summaryLine1}</p>
              <p className={styles.overviewText}>{t.summaryLine2}</p>
              <div className={styles.summaryTags}>
                <span className={styles.summaryTag}>{t.tagStatus}: {statusLabel}</span>
                <span className={styles.summaryTag}>
                  {t.tagTop}: {metricHighlights.best?.name || (isZh ? '暂无' : 'N/A')}
                </span>
                <span className={styles.summaryTag}>
                  {t.tagWeak}: {metricHighlights.weakest?.name || (isZh ? '暂无' : 'N/A')}
                </span>
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles.sectionHead}>
                <h3>{tr('history.report.testProfile', t.testProfile)}</h3>
                <span className={`${styles.statusBadge} ${statusClass}`}>{statusLabel}</span>
              </div>

              <div className={styles.infoGrid}>
                <div className={styles.infoItem}>
                  <span className={styles.infoLabel}>{t.runName}</span>
                  <span className={styles.infoValue}>{runDisplayName}</span>
                </div>
                <div className={styles.infoItem}>
                  <span className={styles.infoLabel}>{t.database}</span>
                  <span className={styles.infoValue}>{dbDisplay}</span>
                </div>
                <div className={styles.infoItem}>
                  <span className={styles.infoLabel}>{t.startTime}</span>
                  <span className={styles.infoValue}>{safeRun.startTime || '-'}</span>
                </div>
                <div className={styles.infoItem}>
                  <span className={styles.infoLabel}>{t.endTime}</span>
                  <span className={styles.infoValue}>{safeRun.endTime || '-'}</span>
                </div>
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles.sectionHead}>
                <h3>{t.testConfig}</h3>
              </div>

              <div className={styles.configBlocks}>
                <div className={styles.configBlock}>
                  <div className={styles.configTitle}>{t.dbConfig}</div>
                  <div className={styles.configList}>
                    <div className={styles.configItem}><span>{t.dbType}</span><strong>{formatConfigValue(dbTypeLabel || null)}</strong></div>
                    <div className={styles.configItem}><span>{t.dbVersion}</span><strong>{formatConfigValue(dbVersion || null)}</strong></div>
                    <div className={styles.configItem}><span>{t.deploymentType}</span><strong>{formatConfigValue(safeRun.deploymentType || null)}</strong></div>
                    <div className={styles.configItem}><span>{t.docker}</span><strong>{dockerText}</strong></div>
                    {dockerized ? (
                      <>
                        <div className={styles.configItem}><span>{t.containerName}</span><strong>{formatConfigValue(safeRun.containerName || null)}</strong></div>
                        {resources?.container?.status ? (
                          <div className={styles.configItem}><span>{t.containerStatus}</span><strong>{resources.container.status}</strong></div>
                        ) : null}
                        {resources?.container?.image ? (
                          <div className={styles.configItem}><span>{t.containerImage}</span><strong>{resources.container.image}</strong></div>
                        ) : null}
                        <div className={styles.configItem}><span>{t.containerCpu}</span><strong>{containerCpuText}</strong></div>
                        <div className={styles.configItem}><span>{t.containerMemory}</span><strong>{containerMemoryText}</strong></div>
                      </>
                    ) : null}
                    <div className={styles.configItem}><span>{t.hostCpu}</span><strong>{hostCpuText}</strong></div>
                    <div className={styles.configItem}><span>{t.hostMemory}</span><strong>{hostMemoryText}</strong></div>
                    <div className={styles.configItemMulti}>
                      <span>{t.dbParams}</span>
                      {Object.keys(dbParams).length ? (
                        <div className={styles.paramGrid}>
                          {Object.entries(dbParams).map(([key, value]) => (
                            <div className={styles.paramPair} key={key}>
                              <span>{key}</span>
                              <strong>{formatConfigValue(value)}</strong>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <strong>{t.notArchived}</strong>
                      )}
                    </div>
                  </div>
                  <div className={styles.blockNote}>{t.connectionNotArchived}</div>
                </div>

                <div className={styles.configBlock}>
                  <div className={styles.configTitle}>{t.workloadConfig}</div>
                  <div className={styles.configList}>
                    <div className={styles.configItem}>
                      <span>{t.workloadType}</span>
                      <strong>{formatConfigValue(safeRun.workload || null)}</strong>
                    </div>
                    {workloadItems.length ? (
                      workloadItems.map((item) => (
                        <div key={item.key} className={styles.configItem}>
                          <span>{item.label}</span>
                          <strong>{formatConfigValue(item.value)}</strong>
                        </div>
                      ))
                    ) : (
                      <div className={styles.configItem}>
                        <span>{t.workloadParams}</span>
                        <strong>{t.notArchived}</strong>
                      </div>
                    )}
                    {runOptionItems.length ? (
                      <>
                        <div className={styles.subTitle}>{t.runOptions}</div>
                        {runOptionItems.map((item) => (
                          <div key={item.key} className={styles.configItem}>
                            <span>{item.label}</span>
                            <strong>{item.value}</strong>
                          </div>
                        ))}
                      </>
                    ) : null}
                  </div>
                </div>

                <div className={`${styles.configBlock} ${styles.faultBlock}`}>
                  <div className={styles.configTitle}>{t.faultConfig}</div>
                  <div className={styles.configList}>
                    <div className={styles.configItem}>
                      <span>{t.faultCount}</span>
                      <strong>{faultDetails.length}</strong>
                    </div>
                  </div>
                  {faultDetails.length ? (
                    <div className={styles.faultList}>
                      {faultDetails.map((fault, index) => {
                        const verification = fault.verification;
                        const status = verification?.status
                          ? String(verification.status).toUpperCase()
                          : null;
                        const statusMeta = status ? (VERIFICATION_META[status] || VERIFICATION_META.UNKNOWN) : null;
                        const statusKey = status ? `verification.status.${status}` : '';
                        const statusText = status
                          ? (translate(statusKey) !== statusKey ? translate(statusKey) : status)
                          : '';
                        const parsedFromCommand = fault.params.some((param) => param.fromCommand);
                        return (
                          <div className={styles.faultCard} key={`${fault.key || fault.name}-${index}`}>
                            <div className={styles.faultHead}>
                              <strong>{index + 1}. {fault.name}</strong>
                              {statusMeta ? (
                                <span
                                  className={styles.verifyChip}
                                  style={{
                                    color: statusMeta.color,
                                    background: statusMeta.bg,
                                    borderColor: statusMeta.border,
                                  }}
                                  title={verification?.error || undefined}
                                >
                                  {statusText}
                                </span>
                              ) : null}
                            </div>
                            <div className={styles.faultMetaGrid}>
                              <div><span>{t.faultGroup}</span><strong>{fault.groupKey || t.standaloneFault}</strong></div>
                              <div><span>{t.faultDelay}</span><strong>{fault.delaySeconds != null ? `${fault.delaySeconds} ${t.seconds}` : '—'}</strong></div>
                              <div><span>{t.faultDuration}</span><strong>{fault.durationSeconds != null ? `${fault.durationSeconds} ${t.seconds}` : '—'}</strong></div>
                              <div><span>{t.faultStart}</span><strong>{fault.startTimeText || '—'}</strong></div>
                              <div><span>{t.faultEnd}</span><strong>{fault.endTimeText || '—'}</strong></div>
                            </div>
                            <div className={styles.faultParamHead}>
                              <span>{t.faultParams}</span>
                              {fault.params.length ? (
                                <em>{parsedFromCommand ? t.fromCommand : t.fromArchive}</em>
                              ) : null}
                            </div>
                            {fault.params.length ? (
                              <table className={styles.faultParamTable}>
                                <tbody>
                                  {fault.params.map((param) => (
                                    <tr key={param.key}>
                                      <th>{param.label}</th>
                                      <td>
                                        {param.value}
                                        {param.unit ? <span className={styles.unit}>{param.unit}</span> : null}
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            ) : (
                              <div className={styles.faultEmptyLine}>{t.faultNoParams}</div>
                            )}
                            {verification?.error ? (
                              <div className={styles.verifyError}>
                                {isZh ? '验证说明：' : 'Verification: '}{verification.error}
                              </div>
                            ) : null}
                            {(fault.injectionCMD || fault.recoveryCMD) ? (
                              <details className={styles.faultCommands}>
                                <summary>{t.faultCommands}</summary>
                                {fault.injectionCMD ? <code>{fault.injectionCMD}</code> : null}
                                {fault.recoveryCMD ? <code>{fault.recoveryCMD}</code> : null}
                              </details>
                            ) : null}
                          </div>
                        );
                      })}
                    </div>
                  ) : (
                    <div className={styles.faultEmptyLine}>{t.faultEmpty}</div>
                  )}
                </div>
              </div>
              {missingFields.length > 0 ? (
                <div className={styles.missingDataNotice}>
                  {t.missingDataPrefix} {missingFields.join(' ; ')}
                </div>
              ) : null}
            </section>

            <section className={styles.section}>
              <div className={styles.sectionHead}>
                <h3>{tr('history.report.keyMetrics', t.keyMetrics)}</h3>
              </div>

              <div className={styles.kpiGrid}>
                <div className={styles.kpiCard}>
                  <p className={styles.kpiLabel}>{t.overallScore}</p>
                  <p className={styles.kpiValue}>{computedSummary.average}</p>
                  <p className={styles.kpiHint}>{t.averageHint}</p>
                </div>
                <div className={styles.kpiCard}>
                  <p className={styles.kpiLabel}>{t.grade}</p>
                  <p className={styles.kpiValue}>{computedSummary.grade}</p>
                  <p className={styles.kpiHint}>{t.gradeHint}</p>
                </div>
                <div className={styles.kpiCard}>
                  <p className={styles.kpiLabel}>{t.strongest}</p>
                  <p className={styles.kpiValue}>{computedSummary.maxScore}</p>
                  <p className={styles.kpiHint}>{t.strongestHint}</p>
                </div>
                <div className={styles.kpiCard}>
                  <p className={styles.kpiLabel}>{t.spread}</p>
                  <p className={styles.kpiValue}>{computedSummary.range}</p>
                  <p className={styles.kpiHint}>{t.spreadHint}</p>
                </div>
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles.sectionHead}>
                <h3>{tr('history.report.visualAnalysis', t.visualAnalysis)}</h3>
              </div>

              <div className={styles.chartsGrid}>
                <div className={styles.chartBox}>
                  <p className={styles.chartTitle}>{t.radarView}</p>
                  <div className={styles.chartContent}>
                    {scoresLoading ? (
                      <p className={styles.chartPlaceholder}>
                        {isZh ? '评分加载中...' : 'Loading scores...'}
                      </p>
                    ) : safeScores.length > 0 ? (
                      <RadarChart
                        title=""
                        height={480}
                        data={safeScores}
                        monitorData={reportMonitorData}
                        scores={safeScores}
                        runContext={{ runId: safeRun.id, runName: runDisplayName }}
                      />
                    ) : (
                      <p className={styles.chartPlaceholder}>
                        {isZh ? '暂无评分数据' : 'No score data available'}
                      </p>
                    )}
                  </div>
                </div>

                <div className={styles.chartBox}>
                  <p className={styles.chartTitle}>{t.triangleView}</p>
                  <div className={styles.chartContent}>
                    <ResilienceTriangleChart
                      data={triangleData}
                      baseline={Number.isFinite(triangleBaseline) ? triangleBaseline : undefined}
                      triangles={reportTriangles}
                      faultInjectInfo={reportFaultInjectInfo}
                      faults={archivedFaults}
                      height={430}
                    />
                  </div>
                </div>
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles.sectionHead}>
                <h3>{tr('history.report.metricBreakdown', t.metricBreakdown)}</h3>
              </div>

              <div className={styles.metricTableWrap}>
                <table className={styles.metricTable}>
                  <thead>
                    <tr>
                      <th>{t.dimension}</th>
                      <th>{t.score}</th>
                      <th>{t.level}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {safeScores.map((metric) => {
                      const score = Number(metric.value) || 0;
                      const level =
                        score >= 85
                          ? (isZh ? '优秀' : 'Excellent')
                          : score >= 70
                            ? (isZh ? '良好' : 'Good')
                            : score >= 55
                              ? (isZh ? '一般' : 'Fair')
                              : (isZh ? '待优化' : 'Needs Improvement');
                      return (
                        <tr key={metric.metricKey || metric.name}>
                          <td>{metric.name}</td>
                          <td>{score}</td>
                          <td>{level}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </section>

            <section className={styles.section}>
              <div className={styles.sectionHead}>
                <h3>{tr('history.report.conclusion', t.conclusion)}</h3>
              </div>

              <div className={styles.conclusionBox}>
                {isZh ? (
                  <>
                    <p>
                      本次测试中，<strong>{dbDisplay}</strong> 的综合韧性评分为
                      <strong> {computedSummary.average}</strong>（等级 {computedSummary.grade}）。
                      从分数离散度看，系统在不同维度上
                      {computedSummary.range <= 20 ? '表现较均衡' : '仍存在明显短板'}。
                    </p>
                    <p>
                      优势维度：
                      <strong>{metricHighlights.best?.name || '暂无'}</strong>
                      （{metricHighlights.best?.value ?? '-'}）；
                      待改进维度：
                      <strong>{metricHighlights.weakest?.name || '暂无'}</strong>
                      （{metricHighlights.weakest?.value ?? '-'}）。
                    </p>
                    <p>
                      建议优先优化低分维度，并复用同一不利因素注入序列再次回归测试，对比恢复趋势和分数提升幅度。
                    </p>
                  </>
                ) : (
                  <>
                    <p>
                      In this test run, <strong>{dbDisplay}</strong> achieved an overall
                      resilience score of <strong>{computedSummary.average}</strong> ({computedSummary.grade}
                      grade). The score distribution indicates{' '}
                      {computedSummary.range <= 20
                        ? 'balanced resilience across dimensions'
                        : 'observable gaps between dimensions'}.
                    </p>
                    <p>
                      Strongest dimension: <strong>{metricHighlights.best?.name || 'N/A'}</strong>
                      ({metricHighlights.best?.value ?? '-'}). Weakest dimension: <strong>{metricHighlights.weakest?.name || 'N/A'}</strong>
                      ({metricHighlights.weakest?.value ?? '-'}).
                    </p>
                    <p>
                      Recommendation: prioritize optimization in low-score dimensions first, then
                      re-run the same adverse event sequence to compare recovery trend and score uplift.
                    </p>
                  </>
                )}
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ReportPreview;
