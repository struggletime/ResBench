import React from 'react';
import { useLanguage } from "../../i18n";
import { FAULT_DEFINITIONS, displayFaultName } from '../../config/faultConfig';

const s = {
  overlay: {
    position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.45)',
    display: 'flex', justifyContent: 'center', alignItems: 'center',
    zIndex: 1000,
  },
  modal: {
    backgroundColor: '#fff', borderRadius: 16, padding: '24px 28px',
    maxWidth: 800, width: '90%', maxHeight: '85vh', overflowY: 'auto',
    boxShadow: '0 20px 60px rgba(0,0,0,0.2)',
  },
  header: {
    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    marginBottom: 16, paddingBottom: 12, borderBottom: '1px solid #eaeaea',
  },
  title: { fontSize: 17, fontWeight: 600, color: '#1f2937', margin: 0 },
  closeBtn: {
    width: 30, height: 30, border: 'none', borderRadius: 8,
    backgroundColor: '#f3f4f6', cursor: 'pointer', fontSize: 16,
    display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#666',
  },
  recordCard: {
    border: '1px solid #e5e7eb', borderRadius: 12, padding: '16px 20px',
    marginBottom: 14, backgroundColor: '#f9fafb',
  },
  recordTitle: {
    fontSize: 14, fontWeight: 600, color: '#0369a1',
    marginBottom: 12, paddingBottom: 8,
    borderBottom: '1px solid #e5e7eb', wordBreak: 'break-all',
  },
  infoGrid: {
    display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))',
    gap: '8px 16px', marginBottom: 14,
  },
  infoItem: { display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 },
  infoLabel: {
    display: 'inline-block', padding: '2px 8px', fontSize: 11, fontWeight: 600,
    color: '#0369a1', backgroundColor: '#f0f9ff', borderRadius: 6,
    border: '1px solid rgba(14,165,233,0.2)', whiteSpace: 'nowrap',
  },
  infoValue: { color: '#333', fontWeight: 500, fontSize: 13 },
  tag: {
    display: 'inline-block', padding: '4px 12px', fontSize: 12, fontWeight: 600,
    color: '#555', backgroundColor: '#f0f9ff', borderRadius: 6,
    marginBottom: 10,
  },
  faultItem: {
    display: 'flex', alignItems: 'flex-start', gap: 10,
    padding: '10px 14px', backgroundColor: '#fff',
    border: '1px solid #e5e7eb', borderRadius: 8, marginBottom: 6,
  },
  faultIdx: {
    width: 22, height: 22, borderRadius: '50%', backgroundColor: '#1890ff',
    color: '#fff', fontSize: 11, fontWeight: 700, flexShrink: 0,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
  },
  faultBody: { flex: 1, minWidth: 0 },
  faultNameText: { fontSize: 13, fontWeight: 600, color: '#1f2937', marginBottom: 2 },
  faultParamRow: { fontSize: 12, color: '#6b7280', lineHeight: 1.6 },
  faultParamLabel: { color: '#9ca3af', marginRight: 4 },
  faultParamValue: { color: '#374151', fontWeight: 500 },
  noFault: { fontSize: 12, color: '#9ca3af', fontStyle: 'italic', padding: '10px 0' },
  label: { display: 'inline-block', padding: '2px 8px', fontSize: 11, fontWeight: 600, color: '#0369a1', backgroundColor: '#f0f9ff', borderRadius: 6, border: '1px solid rgba(14,165,233,0.2)', whiteSpace: 'nowrap', marginRight: 6 },
};

function buildFaultDetail(fault) {
  if (!fault) return [];
  const def = Object.values(FAULT_DEFINITIONS).find(d => d.nameKey === fault.nameKey);
  if (!def) return [{ label: 'key', value: fault.nameKey || '-' }];
  return def.params.map(p => ({
    label: p.paramName,
    value: (fault.params?.[p.paramName] ?? p.defaultValue) || '-',
    unit: p.unit || '',
  }));
}

function parseFaults(record) {
  const raw = record.faults;
  if (!raw || raw === '-') return [];
  if (Array.isArray(raw)) return raw;
  if (typeof raw === 'string') {
    try { return JSON.parse(raw); } catch { return []; }
  }
  return [];
}

const ConfigDetailModal = ({ title, selectedRecords, onClose }) => {
  const { translate, lang } = useLanguage();
  if (!selectedRecords || selectedRecords.length === 0) return null;

  return (
    <div style={s.overlay} onClick={onClose}>
      <div style={s.modal} onClick={e => e.stopPropagation()}>
        <div style={s.header}>
          <h3 style={s.title}>{title}</h3>
          <button style={s.closeBtn} onClick={onClose}>×</button>
        </div>

        {selectedRecords.map((record, idx) => {
          const faults = parseFaults(record);

          let wc = {};
          try {
            if (typeof record.workloadConfig === 'string') wc = JSON.parse(record.workloadConfig);
            else if (typeof record.workloadConfig === 'object') wc = record.workloadConfig;
          // eslint-disable-next-line no-empty
          } catch {
          }
          const wcItems = Object.entries(wc).filter(([k]) => k !== 'isolation').map(([k, v]) => ({ k, v }));

          return (
            <div key={record.id} style={s.recordCard}>
              <div style={s.recordTitle}>记录 {idx + 1}: {record.id}</div>

              <div style={s.infoGrid}>
                <div style={s.infoItem}>
                  <span style={s.infoLabel}>{translate('comparison.workload')}</span>
                  <span style={s.infoValue}>{record.workloadType || '-'}</span>
                </div>
                {wcItems.map(item => (
                  <div key={item.k} style={s.infoItem}>
                    <span style={s.infoLabel}>{item.k}</span>
                    <span style={s.infoValue}>{String(item.v)}</span>
                  </div>
                ))}
                <div style={s.infoItem}>
                  <span style={s.infoLabel}>{translate('comparison.duration')}</span>
                  <span style={s.infoValue}>{record.duration || '-'}s</span>
                </div>
              </div>

              <div style={s.tag}>{translate('comparison.faultConfig')}</div>
              {faults.length === 0 ? (
                <div style={s.noFault}>{translate('comparison.noFault')}</div>
              ) : (
                faults.map((f, i) => {
                  const fo = typeof f === 'string' ? { nameKey: f } : f;
                  const def = Object.values(FAULT_DEFINITIONS).find(d => d.nameKey === fo.nameKey);
                  const params = buildFaultDetail(fo);
                  return (
                    <div key={i} style={s.faultItem}>
                      <span style={s.faultIdx}>{i + 1}</span>
                      <div style={s.faultBody}>
                        <div style={s.faultNameText}>
                          {def ? displayFaultName(def, translate, lang) : (translate(fo.nameKey) || fo.nameKey || '-')}
                        </div>
                        <div style={s.faultParamRow}>
                          {params.map((p, pi) => (
                            <span key={pi}>
                              <span style={s.faultParamLabel}>{p.label}:</span>
                              <span style={s.faultParamValue}>{p.value}{p.unit}</span>
                              {pi < params.length - 1 && <span style={{ margin: '0 6px', color: '#d1d5db' }}>|</span>}
                            </span>
                          ))}
                        </div>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default ConfigDetailModal;
