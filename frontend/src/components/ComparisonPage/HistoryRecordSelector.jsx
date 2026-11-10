import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Pagination } from 'antd';
import styles from './HistoryRecordSelector.module.css';
import { useLanguage } from '../../i18n';
import { fetchHistoryList } from '../../api/suiteApi';

const ITEMS_PER_PAGE = 10;

function parseFaults(faults) {
  if (!faults || faults === '-') return [];
  if (Array.isArray(faults)) return faults;
  if (typeof faults === 'string') {
    try {
      const parsed = JSON.parse(faults);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }
  return [];
}

function getFaultNames(faults) {
  return parseFaults(faults).map((f) => (typeof f === 'string' ? f : f.nameKey) || '').sort();
}

function hasSameFaultNames(f1, f2) {
  if (!f1 || f1 === '-' || !f2 || f2 === '-') return false;
  const names1 = getFaultNames(f1);
  const names2 = getFaultNames(f2);
  if (names1.length === 0 && names2.length === 0) return false;
  if (names1.length !== names2.length) return false;
  return names1.every((n, i) => n === names2[i]);
}

function hasDifferentFaults(f1, f2) {
  if (!f1 || f1 === '-' || !f2 || f2 === '-') return false;
  return !hasSameFaultNames(f1, f2);
}

function parseHistoryStartTime(historyName) {
  if (!historyName) return { timestamp: Number.NaN, text: '--' };
  const match = String(historyName).match(/_(\d{8})_(\d{6})$/);
  if (!match) return { timestamp: Number.NaN, text: '--' };

  const [, datePart, timePart] = match;
  const year = Number(datePart.slice(0, 4));
  const month = Number(datePart.slice(4, 6)) - 1;
  const day = Number(datePart.slice(6, 8));
  const hour = Number(timePart.slice(0, 2));
  const minute = Number(timePart.slice(2, 4));
  const second = Number(timePart.slice(4, 6));
  const parsedDate = new Date(year, month, day, hour, minute, second);
  if (Number.isNaN(parsedDate.getTime())) {
    return { timestamp: Number.NaN, text: '--' };
  }
  const text = `${datePart.slice(0, 4)}-${datePart.slice(4, 6)}-${datePart.slice(6, 8)} ${timePart.slice(0, 2)}:${timePart.slice(2, 4)}:${timePart.slice(4, 6)}`;
  return { timestamp: parsedDate.getTime(), text };
}

function normalizeRecords(data) {
  const historyMap = data?.historyMap || {};
  const statusMap = data?.statusMap || {};
  const records = [];

  Object.entries(historyMap).forEach(([suiteName, historyNames]) => {
    if (!Array.isArray(historyNames)) return;
    historyNames.forEach((historyName) => {
      const statusPayload = statusMap[historyName];
      const isNormal = statusPayload
        && typeof statusPayload === 'object'
        && statusPayload.end === true
        && statusPayload.success !== false;
      if (!isNormal) return;

      const parsedStart = parseHistoryStartTime(historyName);
      const startTs = Number(statusPayload?.startTimestamp);
      const endTs = Number(statusPayload?.endTimestamp);

      records.push({
        id: historyName,
        historyName,
        suiteName: suiteName || historyName.split('_')[0] || '',
        testName: parsedStart.text !== '--' ? parsedStart.text : (suiteName || historyName),
        dbTypeVersion: statusPayload?.dbType || '-',
        startTime: parsedStart.text,
        endTime: endTs > 0 ? new Date(endTs).toLocaleString('zh-CN', { hour12: false }) : '--',
        startTimestamp: Number.isFinite(startTs) ? startTs : parsedStart.timestamp,
        workloadType: statusPayload?.workloadType || '-',
        workloadConfig: statusPayload?.workloadConfig || '',
        warehouses: statusPayload?.warehouses || '-',
        concurrency: statusPayload?.concurrency || '-',
        duration: statusPayload?.duration || '-',
        threads: statusPayload?.threads || '-',
        readWriteRatio: statusPayload?.readWriteRatio || '-',
        runMode: statusPayload?.runMode || '-',
        faults: statusPayload?.faults || '-',
      });
    });
  });

  return records.sort((a, b) => {
    const aTime = Number.isFinite(a.startTimestamp) ? a.startTimestamp : -Infinity;
    const bTime = Number.isFinite(b.startTimestamp) ? b.startTimestamp : -Infinity;
    return bTime - aTime;
  });
}

const HistoryRecordSelector = ({
  dimension,
  selectedRecords,
  onAddRecord,
  onRemoveRecord,
}) => {
  const { translate } = useLanguage();
  const [searchParams, setSearchParams] = useState({
    id: '',
    dbTypeVersion: 'ALL',
  });
  const [currentPage, setCurrentPage] = useState(1);
  const [allRecords, setAllRecords] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [filterMode, setFilterMode] = useState(null);

  const loadRecords = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await fetchHistoryList({ dbType: undefined });
      setAllRecords(normalizeRecords(data));
    } catch (err) {
      setError(err.message || 'Failed to load records');
      setAllRecords([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRecords();
  }, [loadRecords]);

  useEffect(() => {
    if (selectedRecords.length === 0) {
      setFilterMode(null);
    }
  }, [selectedRecords]);

  const filtered = useMemo(() => {
    let result = allRecords;
    if (searchParams.id.trim()) {
      const kw = searchParams.id.trim().toLowerCase();
      result = result.filter((r) => String(r.id).toLowerCase().includes(kw));
    }
    if (searchParams.dbTypeVersion !== 'ALL') {
      const kw = searchParams.dbTypeVersion.trim().toLowerCase();
      result = result.filter((r) => String(r.dbTypeVersion).toLowerCase().includes(kw));
    }

    if (selectedRecords.length >= 1 && filterMode) {
      const first = selectedRecords[0];
      if (filterMode === 'sameFaults') {
        // 相同不利不同数据库：故障名称+数量一致，数据库不同
        const hasFaultData = first.faults && first.faults !== '-';
        result = result.filter((r) => {
          if (selectedRecords.some(s => s.id === r.id)) return true;
          if (first.dbTypeVersion === '-' || r.dbTypeVersion === '-') return false;
          if (r.dbTypeVersion === first.dbTypeVersion) return false;
          if (hasFaultData && r.faults && r.faults !== '-') {
            return hasSameFaultNames(r.faults, first.faults);
          }
          // faults 数据不可用时，同 suiteName 视为同不利配置
          return r.suiteName === first.suiteName;
        });
      } else if (filterMode === 'sameDb') {
        // 相同数据库不同不利：数据库一致，故障名称或数量不同
        const hasFaultData = first.faults && first.faults !== '-';
        result = result.filter((r) => {
          if (selectedRecords.some(s => s.id === r.id)) return true;
          if (first.dbTypeVersion === '-' || r.dbTypeVersion === '-') return false;
          if (r.dbTypeVersion !== first.dbTypeVersion) return false;
          if (hasFaultData && r.faults && r.faults !== '-') {
            return hasDifferentFaults(r.faults, first.faults);
          }
          // faults 数据不可用时，不同 suiteName 视为不同不利配置
          return r.suiteName !== first.suiteName;
        });
      }
    } else if (selectedRecords.length > 1) {
      const first = selectedRecords[0];
      if (dimension === 'database') {
        result = result.filter((r) => selectedRecords.some(s => s.id === r.id) || r.suiteName === first.suiteName);
      } else if (dimension === 'workload') {
        result = result.filter((r) => selectedRecords.some(s => s.id === r.id) || r.dbTypeVersion === first.dbTypeVersion);
      } else if (dimension === 'fault') {
        result = result.filter((r) => selectedRecords.some(s => s.id === r.id) || (r.dbTypeVersion === first.dbTypeVersion && r.suiteName === first.suiteName));
      }
    }

    return result;
  }, [allRecords, searchParams, selectedRecords, dimension, filterMode]);

  const total = filtered.length;
  const paginated = useMemo(() => {
    const start = (currentPage - 1) * ITEMS_PER_PAGE;
    return filtered.slice(start, start + ITEMS_PER_PAGE);
  }, [filtered, currentPage]);

  const dbVersionOptions = useMemo(() => {
    const types = [...new Set(allRecords.map((r) => r.dbTypeVersion).filter(Boolean))]
      .filter((t) => t !== '-')
      .sort();
    return [
      { value: 'ALL', label: 'ALL' },
      ...types.map((t) => ({ value: t, label: t })),
    ];
  }, [allRecords]);

  const handleSearch = () => setCurrentPage(1);

  const handleAdd = (record) => onAddRecord(record);

  if (!dimension) return null;

  const isAdded = (id) => selectedRecords.some((r) => r.id === id);

  return (
    <div className={styles.container}>
      <div className={styles.searchBar} data-tour="compare-search">
        <div className={styles.searchItem}>
          <span className={styles.searchLabel}>
            {translate('comparison.history.selector.search.id')}
          </span>
          <input
            type="text"
            placeholder={translate('comparison.history.selector.placeholder.id')}
            className={styles.searchInput}
            value={searchParams.id}
            onChange={(e) => setSearchParams((prev) => ({ ...prev, id: e.target.value }))}
          />
        </div>
        <div className={styles.searchItem}>
          <span className={styles.searchLabel}>
            {translate('comparison.history.selector.search.dbVersion')}
          </span>
          <select
            className={styles.searchInput}
            value={searchParams.dbTypeVersion}
            onChange={(e) => setSearchParams((prev) => ({ ...prev, dbTypeVersion: e.target.value }))}
          >
            {dbVersionOptions.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </div>
        <button className={styles.searchButton} onClick={handleSearch}>
          {translate('comparison.history.selector.search.button')}
        </button>
      </div>

      {selectedRecords.length >= 1 && (
        <div className={styles.filterButtons}>
          <button
            className={`${styles.filterButton} ${filterMode === 'sameFaults' ? styles.filterButtonActive : ''}`}
            onClick={() => setFilterMode(filterMode === 'sameFaults' ? null : 'sameFaults')}
          >
            {translate('HistoryRecordSelector.sameFaultDiffDb')}
          </button>
          <button
            className={`${styles.filterButton} ${filterMode === 'sameDb' ? styles.filterButtonActive : ''}`}
            onClick={() => setFilterMode(filterMode === 'sameDb' ? null : 'sameDb')}
          >
            {translate('HistoryRecordSelector.sameDbDiffFault')}
          </button>
        </div>
      )}

      {loading && (
        <div className={styles.loadingBanner}>
          {translate('HistoryRecordSelector.loading')}
        </div>
      )}
      {error && <div className={styles.errorBanner}>{error}</div>}

      <table className={styles.table}>
        <thead>
          <tr>
            <th>{translate('comparison.history.selector.table.id')}</th>
            <th>{translate('comparison.history.selector.table.dbVersion')}</th>
            <th>{translate('comparison.history.selector.table.startTime')}</th>
            <th>{translate('comparison.history.selector.table.endTime')}</th>
            <th>{translate('comparison.history.selector.table.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {paginated.length === 0 && !loading ? (
            <tr>
              <td colSpan={5} style={{ textAlign: 'center', padding: '16px' }}>
                {translate('HistoryRecordSelector.noRecords')}
              </td>
            </tr>
          ) : (
            paginated.map((record, idx) => (
              <tr key={record.id}>
                <td title={record.id} style={{ wordBreak: 'break-all', whiteSpace: 'normal', maxWidth: '180px' }}>
                  {record.id}
                </td>
                <td>{record.dbTypeVersion}</td>
                <td>{record.startTime}</td>
                <td>{record.endTime}</td>
                <td data-tour={idx === 0 ? 'compare-add-btn' : undefined}>
                  {isAdded(record.id) ? (
                    <button
                      className={styles.removeButton}
                      onClick={() => onRemoveRecord(record.id)}
                    >
                      {translate('HistoryRecordSelector.cancelAdd')}
                    </button>
                  ) : (
                    <button
                      className={styles.addButton}
                      onClick={() => handleAdd(record)}
                    >
                      {translate('comparison.history.selector.action.add')}
                    </button>
                  )}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>

      {total > 0 && (
        <div className={styles.paginationContainer}>
          <span className={styles.totalText}>
            {translate('comparison.history.selector.pagination.totalPrefix')}
            {' '}{total}{' '}
            {translate('comparison.history.selector.pagination.totalSuffix')}
          </span>
          <Pagination
            current={currentPage}
            pageSize={ITEMS_PER_PAGE}
            total={total}
            onChange={(page) => setCurrentPage(page)}
            showSizeChanger={false}
            showQuickJumper
          />
        </div>
      )}
    </div>
  );
};

export default HistoryRecordSelector;
