import React, { useCallback, useEffect, useRef, useState } from 'react';
import HistoryRecordSelector from './HistoryRecordSelector.jsx';
import SelectedRecordsList from './SelectedRecordsList.jsx';
import ComparisonLineCharts from './ComparisonLineCharts.jsx';
import ComparisonBarChart from './ComparisonBarChart.jsx';
import ComparisonRadarChart from './ComparisonRadarChart.jsx';
import ConfigDetailModal from './ConfigDetailModal';
import { fetchSuiteResult, fetchSuiteMonitor } from '../../api/suiteApi';
import { getEnvInfo } from '../../api/client';
import { mapBackendScores, mapMonitorToTps } from '../../utils/comparisonHelpers';
import { useLanguage } from '../../i18n';

const ComparisonPage = () => {
  const { translate } = useLanguage();
  const [dimension] = useState('database');
  const [selectedRecords, setSelectedRecords] = useState([]);
  const [recordsData, setRecordsData] = useState({});
  const fetchedIdsRef = useRef(new Set());

  const handleAddRecord = useCallback((record) => {
    setSelectedRecords((prev) => {
      if (prev.some((r) => r.id === record.id)) return prev;
      return [...prev, record];
    });
  }, []);

  const handleRemoveRecord = useCallback((recordId) => {
    setSelectedRecords((prev) => prev.filter((r) => r.id !== recordId));
    setRecordsData((prev) => {
      const next = { ...prev };
      delete next[recordId];
      return next;
    });
    fetchedIdsRef.current.delete(recordId);
  }, []);

  useEffect(() => {
    if (selectedRecords.length < 2) {
      fetchedIdsRef.current.clear();
      return;
    }

    let cancelled = false;
    const recordsToFetch = selectedRecords.filter(
      (r) => !fetchedIdsRef.current.has(r.id)
    );

    if (recordsToFetch.length === 0) return;

    recordsToFetch.forEach((r) => fetchedIdsRef.current.add(r.id));

    async function loadComparisonData() {
      const results = await Promise.allSettled(
        recordsToFetch.map(async (record) => {
          if (cancelled) return;
          setRecordsData((prev) => ({
            ...prev,
            [record.id]: { ...prev[record.id], loading: true },
          }));

          try {
            const [scoreResult, monitorData] = await Promise.all([
              fetchSuiteResult(getEnvInfo(), record.suiteName || record.testName, record.historyName || record.id),
              fetchSuiteMonitor(record.historyName || record.id),
            ]);

            if (cancelled) return;

            const scores = mapBackendScores(scoreResult, translate);
            const tpsData = mapMonitorToTps(monitorData);

            setRecordsData((prev) => ({
              ...prev,
              [record.id]: { scores: scores || undefined, tpsData, loading: false },
            }));
          } catch {
            if (cancelled) return;
            setRecordsData((prev) => ({
              ...prev,
              [record.id]: { ...prev[record.id], loading: false, error: true },
            }));
          }
        })
      );

      // Log any rejected fetches for debugging
      results.forEach((r, i) => {
        if (r.status === 'rejected' && !cancelled) {
          const record = recordsToFetch[i];
          console.error(`Failed to load data for record ${record?.id}:`, r.reason);
        }
      });
    }

    loadComparisonData();

    return () => { cancelled = true; };
  }, [selectedRecords]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="page-shell comparison-page">
      {dimension && (
        <div className="page-card">
          <HistoryRecordSelector
            dimension={dimension}
            selectedRecords={selectedRecords}
            onAddRecord={handleAddRecord}
            onRemoveRecord={handleRemoveRecord}
          />
        </div>
      )}

      {selectedRecords.length > 0 && (
        <div className="page-card" data-tour="compare-selected-list">
          <SelectedRecordsList
            selectedRecords={selectedRecords}
            onRemove={handleRemoveRecord}
          />
        </div>
      )}

      <ComparisonConfigSection
        selectedRecords={selectedRecords}
        dimension={dimension}
      />

      {selectedRecords.length >= 2 && (
        <div className="page-card" data-tour="compare-charts">
          <ComparisonLineCharts
            selectedRecords={selectedRecords}
            recordsData={recordsData}
            dimension={dimension}
          />
        </div>
      )}

      {selectedRecords.length >= 2 && (
        <div className="page-card">
          <ComparisonBarChart
            selectedRecords={selectedRecords}
            recordsData={recordsData}
            dimension={dimension}
          />
        </div>
      )}

      {selectedRecords.length >= 2 && (
        <div className="page-card">
          <ComparisonRadarChart
            selectedRecords={selectedRecords}
            recordsData={recordsData}
            dimension={dimension}
          />
        </div>
      )}
    </div>
  );
};

function ComparisonConfigSection({ selectedRecords, dimension }) {
  const { lang } = useLanguage();
  const [modalData, setModalData] = useState(null);

  if (selectedRecords.length < 2) return null;

  const isZh = lang === 'zh';

  return (
    <>
      <div className="page-card" style={{ padding: '16px' }}>
        <h3 style={{ marginBottom: '12px' }}>
          {isZh ? '对比配置概览' : 'Comparison Configuration Overview'}
        </h3>
        <div style={{ display: 'flex', gap: '24px', flexWrap: 'wrap' }}>
          {selectedRecords.map((record) => (
            <div key={record.id} style={{
              border: '1px solid #e5e7eb',
              borderRadius: '8px',
              padding: '12px',
              minWidth: '200px',
              cursor: 'pointer',
              transition: 'box-shadow 0.2s',
            }}
              onClick={() => setModalData(record)}
              onMouseOver={e => e.currentTarget.style.boxShadow = '0 4px 12px rgba(0,0,0,0.1)'}
              onMouseOut={e => e.currentTarget.style.boxShadow = 'none'}
            >
              <strong style={{ fontSize: '14px' }}>{record.id}</strong>
              <div style={{ fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>
                <div>{isZh ? '测试名' : 'Test'}: {record.testName}</div>
                <div>{isZh ? '数据库' : 'DB'}: {record.dbTypeVersion}</div>
                <div>{isZh ? '开始时间' : 'Start'}: {record.startTime}</div>
                {dimension === 'database' && (
                  <div style={{ marginTop: '4px', fontSize: '11px', color: '#9ca3af' }}>
                    {isZh ? `相同负载 + 不利因素 (${record.testName})` : `Fixed: workload + adverse event (${record.testName})`}
                  </div>
                )}
                {dimension === 'workload' && (
                  <div style={{ marginTop: '4px', fontSize: '11px', color: '#9ca3af' }}>
                    {isZh ? `相同数据库 + 不利因素 (${record.dbTypeVersion})` : `Fixed: database + adverse event (${record.dbTypeVersion})`}
                  </div>
                )}
                {dimension === 'fault' && (
                  <div style={{ marginTop: '4px', fontSize: '11px', color: '#9ca3af' }}>
                    {isZh ? `相同数据库 + 负载 (${record.dbTypeVersion})` : `Fixed: database + workload (${record.dbTypeVersion})`}
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {modalData && (
        <ConfigDetailModal
          title={isZh ? `配置详情 - ${modalData.id}` : `Config Detail - ${modalData.id}`}
          selectedRecords={[modalData]}
          onClose={() => setModalData(null)}
        />
      )}
    </>
  );
}

export default ComparisonPage;
