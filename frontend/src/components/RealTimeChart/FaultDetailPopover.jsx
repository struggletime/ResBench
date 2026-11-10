import React, { useEffect, useMemo, useRef } from 'react';
import { useLanguage } from '../../i18n';
import styles from './FaultDetailPopover.module.css';

const STATUS_STYLE = {
  ENFORCED: { color: '#15803d', bg: '#dcfce7', border: '#86efac' },
  NOT_ENFORCED: { color: '#b91c1c', bg: '#fee2e2', border: '#fca5a5' },
  ERROR: { color: '#c2410c', bg: '#ffedd5', border: '#fdba74' },
  UNKNOWN: { color: '#525252', bg: '#f5f5f5', border: '#d4d4d4' },
  VERIFYING: { color: '#1d4ed8', bg: '#dbeafe', border: '#93c5fd' },
  PENDING: { color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb' },
};

function VerificationChip({ verification, translate, prefix }) {
  if (!verification || !verification.status) return null;
  const status = String(verification.status).toUpperCase();
  const meta = STATUS_STYLE[status] || STATUS_STYLE.UNKNOWN;
  const key = `verification.status.${status}`;
  const translated = translate(key);
  const text = translated && translated !== key ? translated : status;
  return (
    <span
      className={styles.chip}
      style={{ color: meta.color, background: meta.bg, borderColor: meta.border }}
      title={verification.error || verification.summary || undefined}
    >
      {prefix ? `${prefix}: ` : ''}
      {text}
      {verification.error ? ` · ${verification.error}` : ''}
    </span>
  );
}

const FaultDetailPopover = ({ details = [], anchor = {}, onClose }) => {
  const { translate, lang } = useLanguage();
  const isZh = lang !== 'en';
  const popoverRef = useRef(null);

  useEffect(() => {
    const handlePointerDown = (event) => {
      if (popoverRef.current && !popoverRef.current.contains(event.target)) {
        onClose?.();
      }
    };
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') onClose?.();
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [onClose]);

  const position = useMemo(() => {
    const margin = 12;
    const width = 400;
    const viewportWidth = typeof window === 'undefined' ? 1280 : window.innerWidth;
    const viewportHeight = typeof window === 'undefined' ? 800 : window.innerHeight;
    const anchorX = Number(anchor.x) || viewportWidth / 2;
    const anchorY = Number(anchor.y) || viewportHeight / 2;
    const left = Math.min(Math.max(margin, anchorX - 40), viewportWidth - width - margin);
    const top = anchorY + 16 + 360 > viewportHeight
      ? Math.max(margin, anchorY - 380)
      : anchorY + 16;
    return { left: Math.max(margin, left), top };
  }, [anchor.x, anchor.y]);

  if (!details.length) return null;

  return (
    <div
      ref={popoverRef}
      className={styles.popover}
      style={{ left: position.left, top: position.top }}
      onClick={(event) => event.stopPropagation()}
      role="dialog"
      aria-label={isZh ? '故障参数配置' : 'Fault configuration'}
    >
      <div className={styles.header}>
        <span className={styles.title}>{isZh ? '故障参数配置' : 'Fault Configuration'}</span>
        <span className={styles.count}>{details.length}</span>
        <button type="button" className={styles.close} onClick={onClose} aria-label={isZh ? '关闭' : 'Close'}>
          ×
        </button>
      </div>

      <div className={styles.body}>
        {details.map((detail, index) => {
          const parsedFromCommand = detail.params.some((param) => param.fromCommand);
          return (
            <div className={styles.card} key={`${detail.key || detail.name}-${index}`}>
              <div className={styles.cardHead}>
                <strong className={styles.faultName}>{detail.name}</strong>
                <div className={styles.chips}>
                  <VerificationChip
                    verification={detail.verification}
                    translate={translate}
                  />
                  <VerificationChip
                    verification={detail.recoveryVerification}
                    translate={translate}
                    prefix={isZh ? '恢复' : 'Recovery'}
                  />
                </div>
              </div>

              <div className={styles.metaGrid}>
                <div className={styles.metaItem}>
                  <span>{isZh ? '故障组' : 'Group'}</span>
                  <strong>{detail.groupKey || (isZh ? '独立故障' : 'Standalone')}</strong>
                </div>
                <div className={styles.metaItem}>
                  <span>{isZh ? '注入时刻' : 'Injection time'}</span>
                  <strong>{detail.delaySeconds != null ? `${detail.delaySeconds} s` : '—'}</strong>
                </div>
                <div className={styles.metaItem}>
                  <span>{isZh ? '计划持续' : 'Duration'}</span>
                  <strong>{detail.durationSeconds != null ? `${detail.durationSeconds} s` : '—'}</strong>
                </div>
                <div className={styles.metaItem}>
                  <span>{isZh ? '开始' : 'Start'}</span>
                  <strong>{detail.startTimeText || '—'}</strong>
                </div>
                <div className={styles.metaItem}>
                  <span>{isZh ? '结束' : 'End'}</span>
                  <strong>{detail.endTimeText || '—'}</strong>
                </div>
              </div>

              <div className={styles.sectionTitle}>
                <span>{isZh ? '参数配置' : 'Parameters'}</span>
                {detail.params.length > 0 ? (
                  <span className={styles.source}>
                    {parsedFromCommand
                      ? (isZh ? '由注入命令解析' : 'Parsed from command')
                      : (isZh ? '归档配置' : 'Archived config')}
                  </span>
                ) : null}
              </div>

              {detail.params.length > 0 ? (
                <table className={styles.paramTable}>
                  <tbody>
                    {detail.params.map((param) => (
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
                <div className={styles.empty}>{isZh ? '未记录参数（命令见下方）' : 'No parameters recorded'}</div>
              )}

              {detail.verification?.error ? (
                <div className={styles.verifyError}>
                  <strong>{isZh ? '验证说明：' : 'Verification: '}</strong>
                  {detail.verification.error}
                </div>
              ) : null}

              {(detail.injectionCMD || detail.recoveryCMD) ? (
                <details className={styles.commands}>
                  <summary>{isZh ? '注入 / 恢复命令' : 'Injection / recovery commands'}</summary>
                  {detail.injectionCMD ? (
                    <div className={styles.commandRow}>
                      <span>{isZh ? '注入' : 'Inject'}</span>
                      <code>{detail.injectionCMD}</code>
                    </div>
                  ) : null}
                  {detail.recoveryCMD ? (
                    <div className={styles.commandRow}>
                      <span>{isZh ? '恢复' : 'Recover'}</span>
                      <code>{detail.recoveryCMD}</code>
                    </div>
                  ) : null}
                </details>
              ) : null}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default FaultDetailPopover;
