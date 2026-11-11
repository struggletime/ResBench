import React, { useEffect, useState } from 'react';
import styles from './HistoryTable.module.css';
import { HISTORY_EXPORT_CATEGORIES, HISTORY_EXPORT_DEFAULT } from '../../api/suiteApi';
import { useLanguage } from '../../i18n';

// 导出类别展示文案（raw.csv 单独强调体积）
const CATEGORY_LABEL_KEYS = {
  summary: 'archive.category.summary',
  monitor: 'archive.category.monitor',
  metric: 'archive.category.metric',
  fault: 'archive.category.fault',
  config: 'archive.category.config',
  charts: 'archive.category.charts',
  detail: 'archive.category.detail',
  other: 'archive.category.other',
  raw: 'archive.category.raw',
};

function formatBytes(bytes) {
  const numeric = Number(bytes);
  if (!Number.isFinite(numeric) || numeric <= 0) return '--';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = numeric;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(value >= 10 || index === 0 ? 0 : 1)} ${units[index]}`;
}

/** 通用对话框外壳：遮罩点击 / Esc 关闭，沿用 ReportPreview 的自绘弹窗风格 */
function DialogShell({ title, subtitle, onClose, children, footer }) {
  const { translate } = useLanguage();
  useEffect(() => {
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') onClose?.();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return (
    <div
      className={styles.dialogOverlay}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose?.();
      }}
    >
      <div className={styles.dialogContent}>
        <div className={styles.dialogHeader}>
          <div>
            <h3>{title}</h3>
            {subtitle ? <div className={styles.dialogSubtitle}>{subtitle}</div> : null}
          </div>
          <button type="button" className={styles.dialogCloseBtn} onClick={onClose} aria-label={translate('archive.close')}>
            ×
          </button>
        </div>
        <div className={styles.dialogBody}>{children}</div>
        {footer ? <div className={styles.dialogFooter}>{footer}</div> : null}
      </div>
    </div>
  );
}

/**
 * 打标签 / 写备注对话框。
 * 标签支持逗号（中英文）或回车分隔，重复标签自动去重。
 */
export function LabelDialog({ run, saving, error, onCancel, onSubmit }) {
  const { translate } = useLanguage();
  const [labels, setLabels] = useState(() => (Array.isArray(run?.labels) ? [...run.labels] : []));
  const [input, setInput] = useState('');
  const [note, setNote] = useState(run?.note || '');

  const addLabels = (raw) => {
    const parts = String(raw || '')
      .split(/[,，\n]/)
      .map((item) => item.trim())
      .filter(Boolean);
    if (parts.length === 0) return;
    setLabels((prev) => {
      const next = [...prev];
      parts.forEach((part) => {
        if (!next.some((item) => item.toLowerCase() === part.toLowerCase())) {
          next.push(part);
        }
      });
      return next;
    });
    setInput('');
  };

  const handleInputChange = (event) => {
    const value = event.target.value;
    // 输入逗号时立即落成一个标签
    if (/[,，]/.test(value)) {
      addLabels(value);
      return;
    }
    setInput(value);
  };

  const handleInputKeyDown = (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      addLabels(input);
    } else if (event.key === 'Backspace' && !input && labels.length > 0) {
      setLabels((prev) => prev.slice(0, -1));
    }
  };

  const removeLabel = (target) => {
    setLabels((prev) => prev.filter((item) => item !== target));
  };

  return (
    <DialogShell
      title={translate('archive.labelTitle')}
      subtitle={run?.historyName}
      onClose={saving ? undefined : onCancel}
      footer={
        <>
          <button type="button" className={styles.secondaryBtn} onClick={onCancel} disabled={saving}>
            {translate('common.cancel')}
          </button>
          <button
            type="button"
            className={styles.primaryBtn}
            onClick={() => onSubmit({ labels, note })}
            disabled={saving}
          >
            {saving ? translate('common.saving') : translate('common.save')}
          </button>
        </>
      }
    >
      <div className={styles.dialogField}>
        <label>{translate('archive.labels')}</label>
        {labels.length > 0 ? (
          <div className={styles.tagList}>
            {labels.map((label) => (
              <span key={label} className={styles.tagItem}>
                {label}
                <button
                  type="button"
                  className={styles.tagRemove}
                  onClick={() => removeLabel(label)}
                  aria-label={translate('archive.removeLabel', { label })}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        ) : null}
        <div className={styles.tagEditorRow}>
          <input
            type="text"
            className={styles.dialogInput}
            placeholder={translate('archive.labelPlaceholder')}
            value={input}
            onChange={handleInputChange}
            onKeyDown={handleInputKeyDown}
            onBlur={() => addLabels(input)}
          />
        </div>
        <div className={styles.dialogHint}>{translate('archive.labelHint')}</div>
      </div>

      <div className={styles.dialogField}>
        <label>{translate('archive.note')}</label>
        <textarea
          className={styles.dialogTextarea}
          placeholder={translate('archive.notePlaceholder')}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </div>

      {error ? <div className={styles.dialogError}>{error}</div> : null}
    </DialogShell>
  );
}

/**
 * 导出归档 zip 对话框。默认勾选「除 raw 外全部」，raw.csv 需显式勾选。
 */
export function ExportDialog({ run, exporting, error, onCancel, onSubmit }) {
  const { translate } = useLanguage();
  const [selected, setSelected] = useState(() => new Set(HISTORY_EXPORT_DEFAULT));

  const toggle = (category) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  };

  const artifacts = run?.artifacts || null;
  const selectedList = HISTORY_EXPORT_CATEGORIES.filter((category) => selected.has(category));
  const includesRaw = selected.has('raw');

  return (
    <DialogShell
      title={translate('archive.exportTitle')}
      subtitle={run?.historyName}
      onClose={exporting ? undefined : onCancel}
      footer={
        <>
          <button type="button" className={styles.secondaryBtn} onClick={onCancel} disabled={exporting}>
            {translate('common.cancel')}
          </button>
          <button
            type="button"
            className={styles.primaryBtn}
            onClick={() => onSubmit(selectedList)}
            disabled={exporting || selectedList.length === 0}
          >
            {exporting ? translate('common.exporting') : translate('archive.export')}
          </button>
        </>
      }
    >
      <div className={styles.dialogField}>
        <label>{translate('archive.include')}</label>
        <div className={styles.categoryList}>
          {HISTORY_EXPORT_CATEGORIES.map((category) => (
            <label
              key={category}
              className={`${styles.categoryItem} ${category === 'raw' ? styles.categoryDanger : ''}`}
            >
              <input
                type="checkbox"
                checked={selected.has(category)}
                onChange={() => toggle(category)}
              />
              {translate(CATEGORY_LABEL_KEYS[category] || category)}
            </label>
          ))}
        </div>
        <div className={styles.dialogHint}>
          {translate('archive.rawHintDefault')}
          {artifacts?.sizeBytes
            ? translate('archive.rawHintSize', {
              total: formatBytes(artifacts.sizeBytes),
              raw: formatBytes(artifacts.rawBytes),
            })
            : translate('archive.rawHintNoSize')}
        </div>
      </div>

      {includesRaw ? (
        <div className={styles.dialogError}>
          {translate('archive.rawWarning')}
        </div>
      ) : null}

      {error ? <div className={styles.dialogError}>{error}</div> : null}
    </DialogShell>
  );
}