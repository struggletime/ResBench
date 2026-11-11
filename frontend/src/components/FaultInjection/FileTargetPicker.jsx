import React, { useState } from "react";
import { useSelector } from "react-redux";
import { fetchFileFaultTargets } from "../../api/suiteApi";
import { useLanguage } from "../../i18n";
import styles from "./FileTargetPicker.module.css";

// 只有路径类参数才挂这个按钮。按 paramName 判断，不按 nameKey 硬编码 ——
// 这样以后新增 file/disk 故障自动就有；引擎侧接了别的数据库（加一个探针）这里也不用改。
const PATH_PARAMS = new Set(["filepath", "src-path", "file-path", "dst-dir", "path"]);

function humanBytes(bytes) {
  if (bytes === null || bytes === undefined) {
    return "";
  }
  if (bytes >= 1 << 30) return `${(bytes / (1 << 30)).toFixed(1)} GB`;
  if (bytes >= 1 << 20) return `${(bytes / (1 << 20)).toFixed(1)} MB`;
  if (bytes >= 1 << 10) return `${(bytes / (1 << 10)).toFixed(1)} KB`;
  return `${bytes} B`;
}

function itemMeta(item, translate) {
  const parts = [];
  const size = humanBytes(item.bytes);
  if (size) parts.push(size);
  if (item.segments) parts.push(translate('filePicker.segments', { count: item.segments }));
  if (item.scans !== null && item.scans !== undefined) {
    parts.push(translate('filePicker.scans', { count: item.scans.toLocaleString() }));
  }
  return parts.join(" · ");
}

/**
 * 参数框旁边的「选择文件」按钮：连上当前配置的目标库，列出引擎检测到的候选文件，
 * 标明是目录还是文件、属于哪张表/哪个索引，点一条填进输入框。输入框本身仍可手填任意路径。
 *
 * props:
 *   nameKey   这条故障的 nameKey（用来让引擎把匹配当前档位的那一项标成推荐）
 *   paramName 当前参数名（filepath / src-path / file-path / dst-dir / path 才显示按钮）
 *   level     可选，"high" / "low" —— 与 nameKey 一起决定推荐哪一项
 *   onPick(path) 用户选中一条时回调，由调用方写进自己的 state
 */
const FileTargetPicker = ({ nameKey, paramName, level, onPick }) => {
  const { translate } = useLanguage();
  const database = useSelector((state) => state.database);
  const workload = useSelector((state) => state.workload);

  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [data, setData] = useState(null);
  const [loadedKey, setLoadedKey] = useState("");
  const [expanded, setExpanded] = useState(() => new Set());

  if (!PATH_PARAMS.has(paramName)) {
    return null;
  }

  const currentKey = `${database.type}|${workload.workload}|${nameKey}|${level || ""}`;

  const load = async () => {
    // 同一个故障+档位只查一次；换了库/负载/故障就重查（清单是"此刻"的快照）
    if (data && loadedKey === currentKey) {
      return;
    }
    if (!workload.workload) {
      setError(translate('filePicker.noWorkload'));
      setData(null);
      return;
    }
    setLoading(true);
    setError("");
    try {
      const body = await fetchFileFaultTargets({
        dbType: database.type,
        deploymentType: database.deploymentType,
        standalone: database.standalone,
        distributed: database.distributed,
        params: { workload: workload.workload, nameKey, level: level || "" },
      });
      if (!body || body.success === false) {
        setData(null);
        setError(body?.message || translate('filePicker.unavailable'));
      } else {
        setData(body);
        // 默认展开"含推荐项"的那一组，没有推荐就展开第一张表
        const groups = body.groups || [];
        const recommended = groups.find(
          (g) => (g.items || []).some((it) => it.recommended)
            || (g.children || []).some((c) => (c.items || []).some((it) => it.recommended))
        );
        setExpanded(new Set([(recommended || groups[1] || groups[0])?.title]));
      }
      setLoadedKey(currentKey);
    } catch (e) {
      setData(null);
      setError(e?.message || translate('filePicker.unavailable'));
    } finally {
      setLoading(false);
    }
  };

  const toggle = (title) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(title)) {
        next.delete(title);
      } else {
        next.add(title);
      }
      return next;
    });
  };

  const pick = (path) => {
    onPick(path);
    setOpen(false);
  };

  const renderItem = (item, key) => (
    <button
      key={key}
      type="button"
      className={`${styles.item} ${item.recommended ? styles.itemRecommended : ""}`}
      onClick={() => pick(item.path)}
      title={translate('filePicker.pickHint')}
    >
      <div className={styles.itemHead}>
        <span className={`${styles.kindTag} ${item.kind === "directory" ? styles.kindDirectory : styles.kindFile}`}>
          {item.kind === "directory" ? translate('filePicker.directory') : translate('filePicker.file')}
        </span>
        <span className={styles.label}>{item.label}</span>
        {item.recommended && <span className={styles.recommended}>{translate('filePicker.recommended')}</span>}
      </div>
      <div className={styles.path}>{item.path}</div>
      <div className={styles.meta}>
        {[item.belongsTo, itemMeta(item, translate)].filter(Boolean).join(" · ")}
      </div>
      {item.note && <div className={styles.note}>{item.note}</div>}
    </button>
  );

  const renderGroup = (group, isChild) => {
    const isOpen = expanded.has(group.title);
    return (
      <div key={`${group.kind}-${group.title}`} className={isChild ? styles.childGroup : ""}>
        <div className={styles.groupTitle} onClick={() => toggle(group.title)}>
          <span>{isOpen ? "▾" : "▸"}</span>
          <span>{group.title}</span>
          {group.subtitle && <span className={styles.subtitle}>{group.subtitle}</span>}
        </div>
        {isOpen && (
          <>
            {(group.items || []).map((item, idx) => renderItem(item, `${group.title}-i${idx}`))}
            {(group.children || []).map((child) => renderGroup(child, true))}
          </>
        )}
      </div>
    );
  };

  return (
    <span className={styles.wrapper}>
      <button
        type="button"
        className={styles.trigger}
        onClick={() => {
          setOpen((prev) => !prev);
          if (!open) {
            load();
          }
        }}
      >
        {translate('filePicker.trigger')}
      </button>

      {open && (
        <div className={styles.panel}>
          <div className={styles.header}>
            <span>
              {translate('filePicker.title')}
              {data?.dataDirectory ? translate('filePicker.dataDir', { dir: data.dataDirectory }) : ""}
            </span>
            <button type="button" className={styles.close} onClick={() => setOpen(false)}>
              ✕
            </button>
          </div>

          {loading && <div className={styles.message}>{translate('filePicker.loading')}</div>}
          {!loading && error && <div className={`${styles.message} ${styles.error}`}>{error}</div>}

          {!loading && !error && data && (
            <>
              {(data.notes || []).map((note, idx) => (
                <div key={idx} className={styles.message}>
                  {note}
                </div>
              ))}
              {(data.groups || []).map((group) => renderGroup(group, false))}
            </>
          )}
        </div>
      )}
    </span>
  );
};

export default FileTargetPicker;
