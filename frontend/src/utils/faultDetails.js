// 故障详情：把归档元信息（run-meta.json 的 faults / 历史检索行）与
// faultInject.json（实际注入结果）合成一份可直接展示的结构。
//
// 两条数据来源的字段互补：
//   · 归档 faults：nameKey / groupKey / delaySeconds / params（用户配置的参数）
//   · faultInject：faultName / startTime / endTime / faultDuration / 命令 / 验证结论
// 线上“实时性能图”没有归档 faults，只能从 injectionCMD 里解析参数；
// 历史报告优先用归档参数，缺失时才退回命令解析，并在界面上注明来源。

import {
  FAULT_DEFINITIONS,
  NAME_KEY_TO_TYPE,
  displayFaultName,
  pickI18n,
} from '../config/faultConfig';

/** 命令里的 flag 与故障目录 paramName 的别名 */
const PARAM_ALIASES = {
  timeout: 'duration',
  'mem-percent': 'memory-percent',
  'memory-percent': 'memory-percent',
  'src-path': 'src-path',
  'dst-dir': 'dst-dir',
  'destination-ip': 'destination-ip',
  'process-cmd': 'process-cmd',
  'file-path': 'file-path',
  'target-service': 'target-service',
  'error-type': 'error-type',
  'class-method': 'class-method',
  'http-status': 'http-status',
  'delay-ms': 'delay-ms',
};

/** 目录里没有对应定义时，命令 flag 的中文兜底名 */
const RAW_FLAG_LABELS = {
  timeout: '持续时间',
  percent: '比例',
  interface: '网卡',
  'destination-ip': '目标 IP',
  'mem-percent': '内存占用比例',
  'memory-percent': '内存占用比例',
  path: '路径',
  'src-path': '源文件',
  'dst-dir': '目标目录',
  'file-path': '文件路径',
  filepath: '文件路径',
  mark: '权限标记',
  'process-cmd': '进程命令',
  signal: '信号',
  count: '数量',
  interval: '间隔',
  threads: '线程数',
  duration: '持续时间',
};

export function findFaultDefinition(nameKey) {
  if (!nameKey) return null;
  const type = NAME_KEY_TO_TYPE[nameKey];
  return (type && FAULT_DEFINITIONS[type]) || FAULT_DEFINITIONS[nameKey] || null;
}

function firstDefined(...values) {
  for (const value of values) {
    if (value !== undefined && value !== null && value !== '') return value;
  }
  return undefined;
}

function firstNumber(...values) {
  for (const value of values) {
    if (value === undefined || value === null || value === '') continue;
    const number = Number(value);
    if (Number.isFinite(number)) return number;
  }
  return null;
}

export function formatDateTime(epochMs) {
  const value = Number(epochMs);
  if (!Number.isFinite(value) || value <= 0) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString('zh-CN', {
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatDuration(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value < 0) return '';
  return `${value} 秒`;
}

/** 归档故障在调度时的真实 groupKey：StartByFormService 会给空 groupKey 补 fault-<index>。 */
export function effectiveGroupKey(fault, index) {
  const declared = fault?.groupKey;
  if (declared !== undefined && declared !== null && String(declared).trim() !== '') {
    return String(declared);
  }
  return `fault-${index}`;
}

function isLegacyInjectEcho(fault) {
  return !fault?.nameKey && (fault?.faultName || fault?.startTime || fault?.verification);
}

/**
 * 旧版 HistoryQueryService 会把「faultInject 里有、meta 里按 groupKey 对不上」的记录
 * 追加成第二条，导致同一故障出现两次。这里做一次只针对该形态的合并：
 * 同组、其中一条没有 nameKey 且带 faultName/startTime → 合并进有 nameKey 的那条。
 */
function dedupeArchivedFaults(archived) {
  const entries = [];
  const byGroup = new Map();
  archived.forEach((fault, index) => {
    if (!fault || typeof fault !== 'object') return;
    const key = effectiveGroupKey(fault, index);
    const existing = byGroup.get(key);
    if (!existing) {
      const entry = { fault: { ...fault }, index, effectiveGroupKey: key };
      entries.push(entry);
      byGroup.set(key, entry);
      return;
    }
    const a = existing.fault;
    const b = fault;
    const mergeable =
      (a.nameKey && isLegacyInjectEcho(b)) || (b.nameKey && isLegacyInjectEcho(a));
    if (!mergeable) {
      // 同组但确实是两个不同的故障：保留为独立条目，不按 groupKey 去重
      const entry = { fault: { ...fault }, index, effectiveGroupKey: `${key}#${index}` };
      entries.push(entry);
      return;
    }
    const merged = { ...a };
    Object.entries(b).forEach(([field, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        merged[field] = merged[field] === undefined || merged[field] === null || merged[field] === ''
          ? value
          : merged[field];
      }
    });
    existing.fault = merged;
  });
  return entries;
}

/**
 * 归档故障与注入记录配对。
 * 返回 [{ archived, info }]：归档条目各出现一次，多余的注入记录 archived=null。
 */
export function pairFaults(archivedFaults, faultInjectInfo) {
  const archived = Array.isArray(archivedFaults) ? archivedFaults : [];
  const infos = (Array.isArray(faultInjectInfo) ? faultInjectInfo : []).filter(
    (item) => item && typeof item === 'object'
  );
  const archivedEntries = dedupeArchivedFaults(archived);
  const infoEntries = infos.map((info, index) => ({
    info,
    index,
    effectiveGroupKey: effectiveGroupKey(info, index),
    used: false,
  }));

  const pairs = new Map();

  // 1) 真实 groupKey（含合成 fault-<index>）精确匹配
  for (const entry of archivedEntries) {
    const hit = infoEntries.find(
      (candidate) => !candidate.used && candidate.effectiveGroupKey === entry.effectiveGroupKey
    );
    if (hit) {
      hit.used = true;
      pairs.set(entry, hit.info);
    }
  }

  // 2) 按展示名兜底（老归档 groupKey 缺失或与注入记录不一致）
  for (const entry of archivedEntries) {
    if (pairs.has(entry)) continue;
    const nameKey = entry.fault?.nameKey;
    const hit = infoEntries.find((candidate) => {
      if (candidate.used || !candidate.info.faultName) return false;
      if (!nameKey) return false;
      const translated = nameKey === candidate.info.faultName;
      const normalized = String(nameKey).split('.').pop();
      return translated || candidate.info.faultName === normalized;
    });
    if (hit) {
      hit.used = true;
      pairs.set(entry, hit.info);
    }
  }

  // 3) 按位置兜底（数量一致、顺序一致的最常见情况）
  for (const entry of archivedEntries) {
    if (pairs.has(entry)) continue;
    const hit = infoEntries.find((candidate) => !candidate.used && candidate.index === entry.index);
    if (hit) {
      hit.used = true;
      pairs.set(entry, hit.info);
    }
  }

  const result = archivedEntries.map((entry) => ({
    archived: entry.fault,
    info: pairs.get(entry) || null,
  }));
  for (const candidate of infoEntries) {
    if (!candidate.used) {
      result.push({ archived: null, info: candidate.info });
    }
  }
  return result;
}

/** 极简 shell 分词：只处理引号与空白，够解析故障注入命令。 */
function splitCommand(command) {
  const tokens = [];
  let current = '';
  let quote = null;
  const text = String(command || '');
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (quote) {
      if (ch === quote) {
        quote = null;
      } else {
        current += ch;
      }
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (/\s/.test(ch)) {
      if (current) {
        tokens.push(current);
        current = '';
      }
      continue;
    }
    current += ch;
  }
  if (current) tokens.push(current);
  return tokens;
}

/**
 * 从注入命令里解析 --flag value / --flag=value。
 * 返回 [{ flag, value }]，忽略 docker exec 前缀与子命令。
 */
export function parseCommandParams(command) {
  const tokens = splitCommand(command);
  const params = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    if (!token.startsWith('-')) continue;
    if (token === '--' || token === '-') continue;
    let flag;
    let value;
    const eq = token.indexOf('=');
    if (eq > 0) {
      flag = token.slice(0, eq);
      value = token.slice(eq + 1);
    } else {
      flag = token;
      const next = tokens[i + 1];
      if (next !== undefined && !next.startsWith('-')) {
        value = next;
        i += 1;
      } else {
        value = true;
      }
    }
    const name = String(flag).replace(/^--?/, '');
    if (!name || name === 'help') continue;
    params.push({ flag: name, value });
  }
  return params;
}

function paramSpec(def, paramName) {
  if (!def || !Array.isArray(def.params)) return null;
  return (
    def.params.find((item) => item.paramName === paramName) ||
    def.params.find((item) => item.paramName === PARAM_ALIASES[paramName]) ||
    null
  );
}

function paramLabel(def, rawName, translate, lang) {
  const spec = paramSpec(def, rawName);
  if (spec) {
    const descriptionKey = spec.descriptionKey;
    if (descriptionKey && !String(descriptionKey).trim().startsWith('{')) {
      const translated = translate(descriptionKey);
      if (translated && translated !== descriptionKey) return translated;
    }
    const yamlLabel = pickI18n(spec.label, lang);
    if (yamlLabel && yamlLabel !== rawName) return yamlLabel;
    const yamlDescription = pickI18n(spec.description, lang);
    if (yamlDescription) return yamlDescription;
  }
  const alias = PARAM_ALIASES[rawName];
  if (alias && alias !== rawName) {
    const aliasLabel = paramLabel(def, alias, translate, lang);
    if (aliasLabel && aliasLabel !== alias) return aliasLabel;
  }
  return RAW_FLAG_LABELS[rawName] || rawName;
}

function formatValue(value) {
  if (value === true) return '是';
  if (value === false) return '否';
  if (value === undefined || value === null || value === '') return '—';
  if (Array.isArray(value)) return value.join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/**
 * 引擎按部署形态自动附加、不属于用户配置参数的 flag（例如容器内 mem load 会自动带上
 * --channel nsexec --ns_target 1），详情面板里不展示，否则会看起来像多出了参数。
 */
const INTERNAL_FLAGS = new Set([
  'channel',
  'ns_target',
  'ns_pid',
  'ns_mnt',
  'ns_net',
  'cgroup-root',
  'uid',
]);

function buildParams(archivedParams, info, def, translate, lang) {
  const params = archivedParams && typeof archivedParams === 'object' ? archivedParams : {};
  const archivedKeys = Object.keys(params);
  if (archivedKeys.length > 0) {
    return archivedKeys.map((key) => {
      const spec = paramSpec(def, key);
      return {
        key,
        label: paramLabel(def, key, translate, lang),
        value: formatValue(params[key]),
        unit: spec?.unit || '',
        fromCommand: false,
      };
    });
  }
  return parseCommandParams(info?.injectionCMD)
    .filter(({ flag }) => !INTERNAL_FLAGS.has(flag))
    .map(({ flag, value }) => {
      const spec = paramSpec(def, flag);
      return {
        key: flag,
        label: paramLabel(def, flag, translate, lang),
        value: formatValue(value),
        unit: spec?.unit || '',
        fromCommand: true,
      };
    });
}

/** 兼容两种历史数据：完整对象，或只有一个状态字符串。 */
function normalizeVerification(value) {
  if (!value) return null;
  if (typeof value === 'string') return { status: value };
  return value;
}

/** 单条故障的展示结构。archived / info 至少有一个非空。 */
export function buildFaultDetail(archived, info, translate, lang) {
  const nameKey = firstDefined(
    archived?.nameKey,
    info?.verification?.nameKey && info.verification.nameKey !== info.faultName
      ? info.verification.nameKey
      : undefined
  );
  const def = findFaultDefinition(nameKey);
  const catalogName = def ? displayFaultName(def, translate, lang) : '';
  const name = firstDefined(
    catalogName && catalogName !== nameKey ? catalogName : undefined,
    info?.faultName,
    archived?.faultName,
    nameKey,
    '—'
  );

  const groupKey = firstDefined(archived?.groupKey, info?.groupKey);
  const delaySeconds = firstNumber(archived?.delaySeconds, info?.faultDelay);
  const durationSeconds = firstNumber(
    info?.faultDuration,
    archived?.faultDuration,
    archived?.params?.duration
  );
  const startTime = firstNumber(info?.startTime);
  const endTime = firstNumber(info?.endTime);
  const verification = normalizeVerification(info?.verification || archived?.verification);
  const recoveryVerification = normalizeVerification(
    info?.recoveryVerification || archived?.recoveryVerification
  );

  return {
    key: firstDefined(info?.faultID, nameKey, name, groupKey),
    nameKey: nameKey || null,
    name,
    groupKey: groupKey || null,
    delaySeconds,
    durationSeconds,
    startTime,
    endTime,
    startTimeText: firstDefined(info?.startTimeFormat, formatDateTime(startTime)),
    endTimeText: firstDefined(info?.endTimeFormat, formatDateTime(endTime)),
    verification,
    recoveryVerification,
    params: buildParams(archived?.params, info, def, translate, lang),
    injectionCMD: firstDefined(info?.injectionCMD, archived?.injectionCMD),
    recoveryCMD: firstDefined(info?.recoveryCMD, archived?.recoveryCMD),
    faultResults: firstDefined(info?.faultResults, archived?.faultResults),
    recoveryResult: firstDefined(info?.recoveryResult, archived?.recoveryResult),
    autoRecovery: firstDefined(info?.autoRecovery, info?.isAutoRecovery, archived?.autoRecovery),
    faultID: firstDefined(info?.faultID, archived?.faultID),
  };
}

/** 归档 + 注入记录 → 展示列表 */
export function buildFaultDetails({ archivedFaults, faultInjectInfo, translate, lang }) {
  const tr = typeof translate === 'function' ? translate : (key) => key;
  return pairFaults(archivedFaults, faultInjectInfo).map(({ archived, info }) =>
    buildFaultDetail(archived, info, tr, lang || 'zh')
  );
}

export default buildFaultDetails;
