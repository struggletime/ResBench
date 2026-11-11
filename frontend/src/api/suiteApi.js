import { getEnvInfo, request } from "./client";
import { translateText } from "../i18n";

export async function fetchDatabaseTypes(deploymentType) {
  return request("/suite/dbtype", {
    params: {
      envInfo: getEnvInfo(),
      deploymentType,
    },
  });
}

export async function fetchWorkloads(dbType) {
  return request("/suite/workload", {
    params: {
      envInfo: getEnvInfo(),
      dbType,
    },
  });
}

export async function startTestByForm(body) {
  return request("/suite/startByForm", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      ...body,
    },
  });
}

// 故障编排合理性校验：按 startByForm 同样的表单生成两份 XML，
// 交给引擎调用 kg_orchestration.py 查询 Neo4j 知识图谱。
export async function checkFaultOrchestration(body) {
  return request("/suite/fault/orchestration/check", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      ...body,
    },
  });
}

export async function stopTestSuite() {
  return request("/suite/stop", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      args: [],
    },
  });
}

export async function testDatabaseConnection(body) {
  return request("/suite/connection/test", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      ...body,
    },
  });
}

/**
 * 应用数据库参数（缓冲池大小 / 最大连接数上限）。
 * 只支持单机 og / pg / mysql；需要重启的库（og/pg）必须在 body.restart.command 里带重启命令。
 * 注意：引擎那边失败也返回 200，失败信息在返回值的 success=false + message 里。
 */
export async function applyDatabaseParams(body) {
  return request("/suite/db/params", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      ...body,
    },
  });
}

/**
 * 检查目标库实际有多少个 tpcc 仓，与 body.params.warehouses 比对。
 * 只做 tpcc。引擎那边失败也返回 200，结论在返回值的 success / conclusion / message 里：
 *   success=false → 无法判定（连不上 / 没权限 / 超时），不代表库是空的；
 *   conclusion=ABSENT 才是尚未建仓。
 */
export async function verifyTpccWarehouses(body) {
  return request("/suite/tpcc/warehouse/verify", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      ...body,
    },
  });
}

/**
 * 列出 file 类故障可以打的目标文件，按「目录 → 每张表（表下挂它的堆文件和它的各个索引）」分组。
 * body.params.workload 必填（引擎靠它定位 benchmark 的 DDL，才知道哪些表算数）；
 * 可选带 nameKey + level，命中的那一项会被标成 recommended。
 * 引擎那边失败也返回 200，结论在返回值的 success / message / groups 里。
 */
export async function fetchFileFaultTargets(body) {
  return request("/suite/faults/file-targets", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      ...body,
    },
  });
}

export async function fetchSuiteStatus(historyName) {
  return request("/suite/status", {
    params: {
      envInfo: getEnvInfo(),
      historyName,
    },
  });
}

export async function fetchSuiteMonitor(historyName, startTimestamp = "0", endTimestamp) {
  return request("/suite/monitor", {
    params: {
      envInfo: getEnvInfo(),
      historyName,
      startTimestamp,
      endTimestamp,
    },
  });
}

export async function fetchHistoryList(params = {}) {
  return request("/suite/history/list", {
    params: {
      envInfo: getEnvInfo(),
      ...params,
    },
  });
}

export async function fetchFaultInjectInfo(historyName) {
  return request("/suite/inject/info", {
    params: {
      envInfo: getEnvInfo(),
      historyName,
    },
  });
}

// 故障目录（yaml 配置驱动，v2.1）：返回 nameKey + 展示文本 + check 支持情况
export async function fetchFaultCatalog() {
  return request("/suite/faults/catalog", {
    params: {
      envInfo: getEnvInfo(),
    },
  });
}

// 手动补验：即时执行一次指定故障的 check 命令
export async function verifyFault(body) {
  return request("/suite/fault/verify", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      ...body,
    },
  });
}

// ---------------------------------------------------------------------------
// 测试历史「归档检索」相关接口（服务端过滤 / 分页 / 标签备注 / 导出 zip）
// ---------------------------------------------------------------------------

/**
 * 历史检索（服务端过滤 + 分页）。
 * 查询参数全部可选、空值忽略：keyword, since, until, dbType, workload,
 * orchestration, dbKey, label, fault, source, success(true|false), complete(true),
 * page(默认1), size(默认20, 上限200), sort(默认开始时间倒序, 可传 start_asc)。
 * 返回 data: { total, page, size, pageCount, rows: [...] }。
 */
export async function fetchHistoryQuery(params = {}) {
  return request("/suite/history/query", {
    params: {
      envInfo: getEnvInfo(),
      ...params,
    },
  });
}

/**
 * 归档动作：给一轮历史测试打标签 / 写备注（旧运行无 run-meta.json 时后端自动补建）。
 * 返回 data: { historyName, labels: [], note }。
 */
export async function saveHistoryLabel({ historyName, labels = [], note = "" }) {
  return request("/suite/history/label", {
    method: "POST",
    body: {
      envInfo: getEnvInfo(),
      historyName,
      labels,
      note,
    },
  });
}

// 导出 zip 的产物类别（后端 include 取值）；raw.csv 单轮可达 200MB+，默认排除。
export const HISTORY_EXPORT_CATEGORIES = [
  "summary",
  "monitor",
  "metric",
  "fault",
  "config",
  "charts",
  "detail",
  "other",
  "raw",
];
export const HISTORY_EXPORT_DEFAULT = [
  "summary",
  "monitor",
  "metric",
  "fault",
  "config",
  "charts",
  "detail",
  "other",
];

/**
 * 归档动作：导出历史运行的 zip（二进制）。默认 include 不含 raw。
 * 返回 { blob, filename }，失败时抛出可读错误（404 / 空响应 / JSON 错误体都会转成提示）。
 */
export async function exportHistoryArchive(historyName, include = HISTORY_EXPORT_DEFAULT) {
  const params = new URLSearchParams();
  params.set("envInfo", getEnvInfo());
  params.set("historyName", historyName);
  if (Array.isArray(include) && include.length > 0) {
    params.set("include", include.join(","));
  }

  // 与 client.js 的 baseUrl 口径保持一致（这里需要原生 fetch 拿二进制）
  const base =
    typeof window !== "undefined" && window.__APP_CONFIG__?.apiBaseUrl
      ? window.__APP_CONFIG__.apiBaseUrl.replace(/\/+$/, "")
      : "";
  const url = new URL("/suite/history/export", base || window.location.origin);
  url.search = params.toString();

  const response = await fetch(url.toString());

  if (!response.ok) {
    // 后端失败可能是空响应（404）也可能是 GlobalResponse JSON，尽量转成可读提示
    let detail = "";
    try {
      const text = await response.text();
      if (text) {
        try {
          const json = JSON.parse(text);
          detail = json?.msg || json?.message || json?.error || "";
        } catch {
          detail = text.slice(0, 200);
        }
      }
    } catch {
      // 读取失败时忽略，用状态码兜底
    }
    throw new Error(detail || translateText('history.exportHttpError', { status: response.status }));
  }

  const contentType = response.headers.get("Content-Type") || "";

  // 合并网关/旧后端常见的「HTTP 200 + JSON 错误体」以及「未映射路由返回 JSON」：
  // 只有明确的 zip / 二进制类型才当作成功，其余一律转成可读提示。
  if (!/zip|octet-stream/i.test(contentType)) {
    let detail = "";
    try {
      const text = await response.text();
      if (text) {
        try {
          const json = JSON.parse(text);
          detail = json?.msg || json?.message || json?.error || "";
        } catch {
          detail = text.slice(0, 200);
        }
      }
    } catch {
      // 读取失败时用 Content-Type 兜底
    }
    throw new Error(detail || translateText('history.exportNotZip', { contentType: contentType || 'unknown' }));
  }

  const blob = await response.blob();

  // 文件名优先取 Content-Disposition，取不到则回退 <historyName>.zip
  let filename = `${historyName}.zip`;
  const disposition = response.headers.get("Content-Disposition") || "";
  const match = disposition.match(/filename\*?=(?:UTF-8''|")?([^";]+)/i);
  if (match && match[1]) {
    try {
      filename = decodeURIComponent(match[1].trim().replace(/^"|"$/g, ""));
    } catch {
      // 解码失败保留默认文件名
    }
  }

  return { blob, filename };
}

export async function fetchSuiteResult(envInfo, testSuiteName, historyName) {
  return request("/suite/result", {
    params: {
      envInfo: envInfo || getEnvInfo(),
      testSuiteName,
      historyName,
    },
  });
}
