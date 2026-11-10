#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把一次运行归档导出为自包含的 HTML 实验报告（离线、无外网依赖）。

用法：
  python3 scripts/export_run_report.py <归档目录>              # 写 <归档目录>/report.html
  python3 scripts/export_run_report.py <归档目录> --out x.html
  python3 scripts/export_run_report.py <归档目录> --index      # 同时刷新上级目录的 index.html
  python3 scripts/export_run_report.py <归档目录> --metrics container.memory.usage
                                                             # 换监控指标（默认 CPU 两条）

归档目录可以只放一部分文件；缺哪份就在报告里标「未归档」，不会因为缺文件直接失败。
读取的文件（都在归档目录内）：
  job.json  run-meta.json  summary.json  metric.json  monitor.csv  faultInject.json
  monitoring/series.json
"""
import argparse
import csv
import html as html_mod
import json
import os
import sys
from datetime import datetime

# 默认展示的监控指标（CPU 故障）；其它故障用 --metrics 覆盖，如 --metrics container.memory.usage
DEFAULT_METRICS = [
    ("container.cpu.usage", "%", "容器 CPU 使用率"),
    ("container.cpu.cores", "cores", "容器 CPU 核数"),
]

# 常用指标的单位与中文名（--metrics 只给 metricId 时按这里补）
METRIC_LABELS = {
    "container.cpu.usage": ("%", "容器 CPU 使用率"),
    "container.cpu.cores": ("cores", "容器 CPU 核数"),
    "container.memory.usage": ("%", "容器内存使用率"),
    "container.disk.read": ("B/s", "容器磁盘读"),
    "container.disk.write": ("B/s", "容器磁盘写"),
    "host.cpu.usage": ("%", "宿主机 CPU 使用率"),
    "host.memory.usage": ("%", "宿主机内存使用率"),
    "host.disk.usage": ("%", "文件系统使用率"),
    "host.process.stopped": ("count", "被暂停进程数"),
    "host.load.ratio": ("1", "宿主机负载比"),
    "host.pressure.cpu": ("%", "宿主机 CPU 压力"),
    "host.pressure.io": ("%", "宿主机 IO 压力"),
    "net.ping.rtt": ("ms", "ICMP 时延（RTT）"),
    "net.ping.loss": ("%", "ICMP 丢包率"),
    "net.qdisc.drop.percent": ("%", "网卡丢包率"),
    "net.qdisc.reorder.rate": ("events/s", "网卡乱序率"),
    "db.tps.agent": ("tx/s", "数据库统计 TPS"),
    "db.connections.usage": ("%", "连接使用率"),
    "db.connections.open_rate": ("conn/s", "新建连接速率"),
    "db.sessions": ("count", "Session 数"),
    "db.rollback.rate": ("txn/s", "事务回滚速率"),
    "net.tcp.dsack.rate": ("events/s", "DSACK 重复包速率"),
    "net.tcp.rto.rate": ("events/s", "TCP RTO 超时速率"),
    "net.tcp.retrans.ratio": ("%", "TCP 重传率"),
    "net.tcp.lost_retrans.rate": ("events/s", "SACK 丢包重传速率"),
    "host.netstat.retrans": ("segs/s", "TCP 重传速率"),
}

# faultInject.json 的 faultName → 报告里的短名。未列出的保留原 faultName。
FAULT_LABELS = {
    "CPU Load": "CPU 负载",
    "Memory Load": "内存负载",
    "Network Packet Loss": "网络丢包",
    "Network Delay": "网络延迟",
    "Network Reorder": "网络乱序",
    "Network Duplicate": "网络重复包",
    "Disk Fill": "磁盘写满",
    "File Chmod": "文件权限 000",
    "Connection Storm": "连接风暴",
    "Connection Limit Exceeded": "连接数耗尽",
    "Massive Transaction Rollback": "大量事务回滚",
    "海量事务回滚": "大量事务回滚",
    "连接风暴": "连接风暴",
    "连接数耗尽": "连接数耗尽",
}


def load_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default
    except Exception as e:  # noqa: BLE001 - 坏文件也只要在报告里说明
        return {"__error__": f"{type(e).__name__}: {e}"} if default is None else default


def read_monitor_csv(path):
    rows = []
    try:
        with open(path, encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                try:
                    rows.append({
                        "t": int(float(r["timestamp"])),
                        "tps": float(r["tps"]),
                        "avg": float(r["avgLatencyMs"]),
                        "p95": float(r["latencyMs95th"]),
                        "max": float(r["maxLatencyMs"]),
                    })
                except (KeyError, TypeError, ValueError):
                    continue
    except FileNotFoundError:
        return []
    return rows


def pick_series(series_json, metric_id):
    """series.json 里同一 metricId 可能有多条（不同 target/labels）：取点数最多的；
    点数相同时取变化幅度最大的一条 —— host.disk.usage 有 7 个挂载点，盘满只体现在
    被填充的那个上，取第一条会把「/」的平线画出来。"""
    best = None
    best_span = -1.0
    for s in (series_json or {}).get("series", []) or []:
        if s.get("metricId") != metric_id:
            continue
        pts = [[int(p[0]), float(p[1])] for p in (s.get("points") or []) if p and len(p) >= 2]
        if not pts:
            continue
        span = max(p[1] for p in pts) - min(p[1] for p in pts)
        if (best is None or len(pts) > len(best["pts"])
                or (len(pts) == len(best["pts"]) and span > best_span)):
            best = {"unit": s.get("unit") or "", "labels": s.get("labels") or {}, "pts": pts}
            best_span = span
    return best


def ms2str(ms):
    if not ms:
        return ""
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


def build_payload(run_dir, metrics=None, clip_to_tps=False, title=None):
    metrics = metrics or DEFAULT_METRICS
    job = load_json(os.path.join(run_dir, "job.json"), {}) or {}
    meta = load_json(os.path.join(run_dir, "run-meta.json"), {}) or {}
    metric = load_json(os.path.join(run_dir, "metric.json"), []) or []
    faults = load_json(os.path.join(run_dir, "faultInject.json"), []) or []
    summary = load_json(os.path.join(run_dir, "summary.json"), {}) or {}
    series = load_json(os.path.join(run_dir, "monitoring", "series.json"), {}) or {}
    monitor = read_monitor_csv(os.path.join(run_dir, "monitor.csv"))

    # summary.json 不一定是固定文件名，按后缀兜底
    if not summary:
        for name in sorted(os.listdir(run_dir)):
            if name.endswith(".summary.json"):
                summary = load_json(os.path.join(run_dir, name), {}) or {}
                break

    start_ms = int(job.get("startTimeStamp") or 0) or None
    meta_start_ms = int(meta.get("createdAt") or 0) or None
    # 引擎偶尔会残留上一轮的 job.startTimeStamp，比本轮 run-meta.createdAt 早出数小时，
    # 直接把报告时长和起点画错；偏差超过 1 小时时以 run-meta 为准。
    if start_ms and meta_start_ms and start_ms < meta_start_ms - 3600_000:
        start_ms = meta_start_ms
    if not start_ms:
        start_ms = meta_start_ms
    end_ms = int(job.get("stopTimeStamp") or meta.get("updatedAt") or 0) or None

    shown = []
    for mid, unit, label in metrics:
        s = pick_series(series, mid)
        if s:
            labels = s["labels"] or {}
            suffix = (labels.get("mountpoint") or labels.get("device")
                      or labels.get("state") or "")
            shown.append({"metric": mid, "unit": s["unit"] or unit,
                          "label": label + ("（%s）" % suffix if suffix else ""),
                          "labels": labels, "pts": s["pts"]})

    # 监控会话在 create/load 之前就开了（reload 的批次会多出几十分钟灌数期），
    # 和 TPS 曲线不同起点。--clip-to-tps 把每条指标裁到 TPS 曲线的时间范围内。
    clip_window = None
    if clip_to_tps and monitor and shown:
        lo = min(r["t"] for r in monitor)
        hi = max(r["t"] for r in monitor)
        clip_window = [lo, hi]
        for c in shown:
            c["pts"] = [p for p in c["pts"] if lo <= p[0] <= hi]

    # 统一时间原点：所有时间序列里最早的点，便于横轴用「相对秒」
    stamps = [p["t"] for p in monitor]
    for c in shown:
        stamps.extend(p[0] for p in c["pts"])
    for f in faults:
        if f.get("startTime"):
            stamps.append(int(f["startTime"]))
    t0 = min(stamps) if stamps else (start_ms or 0)

    norm_faults = []
    for i, f in enumerate(faults):
        res = f.get("faultResults") or []
        norm_faults.append({
            "index": i + 1,
            "name": f.get("faultName") or "",
            "shortName": FAULT_LABELS.get(f.get("faultName") or "",
                                          f.get("faultName") or ""),
            "group": f.get("groupKey") or "",
            "delaySec": f.get("faultDelay"),
            "durationSec": f.get("faultDuration"),
            "startMs": f.get("startTime"),
            "endMs": f.get("endTime"),
            "startText": f.get("startTimeFormat") or ms2str(f.get("startTime")),
            "endText": f.get("endTimeFormat") or ms2str(f.get("endTime")),
            "injectCmd": f.get("injectionCMD") or "",
            "recoveryCmd": f.get("recoveryCMD") or "",
            "verification": f.get("verification") or {},
            "autoRecovery": bool(f.get("autoRecovery")),
            "results": [{
                "endPoint": r.get("endPoint"),
                "returncode": r.get("returncode"),
                "elapsedMs": r.get("elapsedMs"),
                "error": bool(r.get("error")),
                "replied": bool(r.get("replied")),
                "result": (r.get("result") or "")[:400],
            } for r in res],
        })

    return {
        "run": {
            "name": title or meta.get("historyName") or os.path.basename(run_dir.rstrip("/")),
            "dir": os.path.abspath(run_dir),
            "success": job.get("success"),
            "state": job.get("curState") or "",
            "startMs": start_ms,
            "endMs": end_ms,
            "startText": ms2str(start_ms),
            "endText": ms2str(end_ms),
            "durationSec": round((end_ms - start_ms) / 1000, 1) if start_ms and end_ms else None,
            "source": meta.get("source") or "",
            "note": meta.get("note") or "",
            "labels": meta.get("labels") or [],
            "db": {
                "type": (meta.get("database") or {}).get("dbType") or "",
                "deployment": (meta.get("database") or {}).get("deploymentType") or "",
                "container": (meta.get("database") or {}).get("containerName") or "",
                "dockerized": (meta.get("database") or {}).get("dockerized"),
                "params": (meta.get("database") or {}).get("params") or {},
                "resources": (meta.get("database") or {}).get("resources") or {},
            },
            "workload": meta.get("workload") or {},
            "runOptions": meta.get("runOptions") or {},
            "archivedFaults": meta.get("faults") or [],
        },
        "summary": summary,
        "triangles": metric if isinstance(metric, list) else [],
        "faults": norm_faults,
        "monitor": monitor,
        "monitorSeries": shown,
        "requestedMetrics": [m[0] for m in metrics],
        "t0": t0,
        "monitoring": {
            "available": bool((series or {}).get("series")),
            "seriesCount": len(((series or {}).get("series") or [])),
            "start": (series or {}).get("start"),
            "end": (series or {}).get("end"),
            "warnings": (series or {}).get("warnings") or [],
            "clipWindow": clip_window,
        },
    }


# ----------------------------------------------------------------------------
# HTML 模板：图表全部用原生 SVG 画，不引任何外部库（离线可看）
# ----------------------------------------------------------------------------
TEMPLATE = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<style>
  :root{
    --bg:#f6f7f9; --card:#fff; --ink:#1f2430; --muted:#6b7280; --line:#e5e7eb;
    --blue:#2f6fed; --green:#12a150; --amber:#d97706; --red:#d64545; --violet:#7c5cff;
  }
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);
       font:14px/1.6 -apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
  .wrap{max-width:1180px;margin:0 auto;padding:24px 20px 64px}
  header.top{display:flex;flex-wrap:wrap;gap:12px;align-items:flex-end;justify-content:space-between;
             border-bottom:2px solid var(--line);padding-bottom:14px;margin-bottom:20px}
  h1{font-size:20px;margin:0 0 4px}
  h2{font-size:16px;margin:28px 0 12px;padding-left:9px;border-left:4px solid var(--blue)}
  .sub{color:var(--muted);font-size:12.5px;word-break:break-all}
  .badge{display:inline-block;padding:2px 9px;border-radius:999px;font-size:12px;font-weight:600}
  .ok{background:#e7f7ee;color:var(--green)} .bad{background:#fdecec;color:var(--red)}
  .warn{background:#fff4e5;color:var(--amber)} .info{background:#eaf0ff;color:var(--blue)}
  .grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(168px,1fr))}
  .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
  .kpi .label{color:var(--muted);font-size:12px;margin-bottom:4px}
  .kpi .value{font-size:22px;font-weight:700;font-variant-numeric:tabular-nums}
  .kpi .hint{color:var(--muted);font-size:11.5px;margin-top:2px}
  .panel{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px 6px;margin-bottom:14px}
  .panel h3{margin:2px 0 8px;font-size:14px;font-weight:600}
  .legend{display:flex;flex-wrap:wrap;gap:14px;color:var(--muted);font-size:12px;margin:0 0 6px 4px}
  .legend i{display:inline-block;width:14px;height:3px;border-radius:2px;margin-right:5px;vertical-align:middle}
  svg{display:block;width:100%;height:auto;overflow:visible}
  .axis text{fill:var(--muted);font-size:11px}
  .gridline{stroke:#eef0f3;stroke-width:1}
  table{width:100%;border-collapse:collapse;background:var(--card);font-size:13px;margin-bottom:8px}
  th,td{border:1px solid var(--line);padding:7px 9px;text-align:left;vertical-align:top}
  th{background:#fafbfc;color:#374151;font-weight:600;white-space:nowrap}
  td.num{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
  details{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 14px;margin-bottom:12px}
  summary{cursor:pointer;font-weight:600}
  code,pre{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}
  pre{background:#0f172a;color:#e2e8f0;padding:10px 12px;border-radius:8px;overflow:auto;white-space:pre-wrap;word-break:break-all}
  .muted{color:var(--muted)}
  .empty{color:var(--muted);padding:10px 4px}
  .tip{position:fixed;pointer-events:none;background:#111827;color:#f9fafb;border-radius:6px;
       padding:7px 9px;font-size:12px;line-height:1.45;opacity:0;transition:opacity .1s;z-index:9;max-width:320px}
  .toolbar{display:flex;gap:10px;align-items:center;margin-bottom:10px}
  .toolbar input{padding:5px 8px;border:1px solid var(--line);border-radius:6px;font-size:12px}
  footer{margin-top:28px;color:var(--muted);font-size:12px;border-top:1px solid var(--line);padding-top:12px}
</style>
</head>
<body>
<div class="wrap">
  <header class="top">
    <div>
      <h1>__TITLE__</h1>
      <div class="sub" id="runSub"></div>
    </div>
    <div id="runBadges"></div>
  </header>

  <h2>一、测试档案</h2>
  <div class="grid" id="profile"></div>

  <h2>二、关键指标</h2>
  <div class="grid" id="kpis"></div>

  <h2>三、TPS 与韧性三角</h2>
  <div class="panel">
    <h3>TPS 曲线（逐秒）· 阴影为故障窗口 · ▲ 为韧性三角点</h3>
    <div class="legend" id="tpsLegend"></div>
    <div id="chartTps"></div>
  </div>
  <div class="panel">
    <h3>延迟曲线（平均 / P95，毫秒）</h3>
    <div class="legend" id="latLegend"></div>
    <div id="chartLat"></div>
  </div>

  <h2>四、韧性三角点（每轮）</h2>
  <div id="triangleTable"></div>

  <h2>五、韧性评分（每轮）</h2>
  <div id="scoreTable"></div>

  <h2 id="monitorTitle">六、监控数据</h2>
  <div id="cpuCharts"></div>
  <div id="cpuStats"></div>

  <h2>七、故障注入记录</h2>
  <div id="faultTable"></div>

  <details id="filesBox" open>
    <summary>原始归档文件（report.html 与它们同目录）</summary>
    <div id="files"></div>
  </details>

  <footer>
    由 <code>scripts/export_run_report.py</code> 生成 · <span id="genAt"></span> ·
    数据全部内嵌在本文件里，直接拷走即可离线查看
  </footer>
</div>
<div class="tip" id="tip"></div>

<script type="application/json" id="payload">__PAYLOAD__</script>
<script>
(function(){
  const S = JSON.parse(document.getElementById('payload').textContent);
  const T0 = S.t0 || (S.run.startMs || 0);
  const rel = ms => (ms - T0) / 1000;
  const fmt = (v, d=2) => (v === null || v === undefined || Number.isNaN(v))
      ? '-' : Number(v).toLocaleString('zh-CN', {maximumFractionDigits: d});
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => (
      {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const $ = id => document.getElementById(id);
  const tip = $('tip');

  // ---------- 头部 ----------
  document.title = S.run.name;
  $('runSub').textContent = S.run.name;
  $('genAt').textContent = new Date().toLocaleString();
  const badges = [];
  badges.push(S.run.success === true ? '<span class="badge ok">运行成功</span>'
            : S.run.success === false ? '<span class="badge bad">运行失败</span>'
            : '<span class="badge warn">状态未知</span>');
  if (S.run.state) badges.push('<span class="badge info">' + esc(S.run.state) + '</span>');
  badges.push('<span class="badge info">' + esc((S.run.db.type || 'database').toUpperCase()) + '</span>');
  badges.push('<span class="badge info">' + esc(S.run.workload.name || 'workload') + '</span>');
  $('runBadges').innerHTML = badges.join(' ');

  // ---------- 测试档案 ----------
  const db = S.run.db || {};
  const res = db.resources || {};
  const cont = res.container || {};
  const host = res.host || {};
  const wo = (S.run.workload && S.run.workload.params) || {};
  const profile = [
    ['开始时间', S.run.startText || '-'],
    ['结束时间', S.run.endText || '-'],
    ['运行时长', S.run.durationSec != null ? fmt(S.run.durationSec, 1) + ' s' : '-'],
    ['数据库', (db.type || '-') + (db.dockerized ? '（容器部署）' : '')],
    ['容器', db.container || '-'],
    ['镜像', cont.image || '-'],
    ['CPU 限额', cont.cpuLimit ? fmt(cont.cpuLimit, 0) + ' 核' : '未限制/未采集'],
    ['内存限额', cont.memoryLimitBytes ? fmt(cont.memoryLimitBytes / 1073741824, 1) + ' GiB' : '未限制/未采集'],
    ['宿主机', host.cpuCount ? host.cpuCount + ' 核 / ' + fmt(host.memoryBytes / 1073741824, 0) + ' GiB' : '-'],
    ['负载', S.run.workload.name || '-'],
    ['负载参数', Object.entries(wo).map(([k, v]) => k + '=' + v).join('，') || '-'],
    ['数据库参数', Object.entries(db.params || {}).map(([k, v]) => k + '=' + v).join('，') || '-'],
    ['异常来源', S.run.source || '-'],
    ['归档备注', S.run.note || '-'],
  ];
  $('profile').innerHTML = profile.map(([k, v]) =>
    '<div class="card kpi"><div class="label">' + esc(k) + '</div><div style="font-size:15px;font-weight:600">'
    + esc(v) + '</div></div>').join('');

  // ---------- 关键指标 ----------
  const sm = S.summary || {};
  const lat = sm['Latency Distribution'] || {};
  const kpis = [];
  if (sm['Throughput (requests/second)'] != null)
    kpis.push(['吞吐量 TPS', fmt(sm['Throughput (requests/second)'], 2), 'req/s']);
  if (sm['Goodput (requests/second)'] != null)
    kpis.push(['有效吞吐 Goodput', fmt(sm['Goodput (requests/second)'], 2), 'req/s']);
  if (lat['Average Latency (microseconds)'] != null)
    kpis.push(['平均延迟', fmt(lat['Average Latency (microseconds)'] / 1000, 2), 'ms']);
  if (lat['95th Percentile Latency (microseconds)'] != null)
    kpis.push(['P95 延迟', fmt(lat['95th Percentile Latency (microseconds)'] / 1000, 2), 'ms']);
  if (lat['99th Percentile Latency (microseconds)'] != null)
    kpis.push(['P99 延迟', fmt(lat['99th Percentile Latency (microseconds)'] / 1000, 2), 'ms']);
  if (sm['Measured Requests'] != null)
    kpis.push(['完成事务', fmt(sm['Measured Requests'], 0), '次']);
  const tri = S.triangles || [];
  const avgOf = key => {
    const vs = tri.map(t => Number(t[key])).filter(v => Number.isFinite(v));
    return vs.length ? vs.reduce((a, b) => a + b, 0) / vs.length : null;
  };
  [['rto', 'RTO 平均', 's'], ['rpo', 'RPO 平均', '%'], ['throughput', '吞吐评分', '分'],
   ['latency', '延迟评分', '分'], ['stability', '稳定性', '分'], ['defence', '防御性', '分'],
   ['recovery', '恢复性', '分']].forEach(([k, name, unit]) => {
    const v = avgOf(k);
    if (v !== null) kpis.push([name, fmt(v, 2), unit]);
  });
  $('kpis').innerHTML = kpis.map(([k, v, u]) =>
    '<div class="card kpi"><div class="label">' + esc(k) + '</div><div class="value">' + v +
    '</div><div class="hint">' + esc(u) + '</div></div>').join('') ||
    '<div class="card empty">归档里没有 summary/metric，关键指标不可算</div>';

  // ---------- 图表工具 ----------
  function drawChart(host, cfg) {
    host.innerHTML = '';
    const W = Math.max(host.clientWidth || 900, 520);
    const H = cfg.height || 300;
    const M = {l: 62, r: 26, t: 14, b: 34};
    const iw = W - M.l - M.r, ih = H - M.t - M.b;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    svg.setAttribute('height', H);
    const add = (tag, attrs, text) => {
      const e = document.createElementNS(ns, tag);
      for (const k in attrs) e.setAttribute(k, attrs[k]);
      if (text != null) e.textContent = text;
      svg.appendChild(e);
      return e;
    };

    let xs = [], ys = [];
    cfg.series.forEach(s => s.pts.forEach(p => { xs.push(p[0]); ys.push(p[1]); }));
    if (!xs.length) { host.innerHTML = '<div class="empty">没有数据</div>'; return; }
    let x0 = Math.min(...xs), x1 = Math.max(...xs);
    if (x1 === x0) x1 = x0 + 1;
    let y0 = cfg.yMin != null ? cfg.yMin : 0;
    let yMax = Math.max(...ys);
    if (cfg.yMax != null) yMax = cfg.yMax;
    if (yMax <= y0) yMax = y0 + 1;
    const pad = (yMax - y0) * 0.08;
    yMax += pad;
    const X = t => M.l + (t - x0) / (x1 - x0) * iw;
    const Y = v => M.t + ih - (v - y0) / (yMax - y0) * ih;

    // 故障窗口阴影
    (cfg.bands || []).forEach(b => {
      const bx = Math.max(M.l, X(b.from)), bw = Math.max(0, Math.min(M.l + iw, X(b.to)) - bx);
      if (bw <= 0) return;
      add('rect', {x: bx, y: M.t, width: bw, height: ih, fill: (b.color || '#f59e0b'), 'fill-opacity': 0.10});
      add('rect', {x: bx, y: M.t, width: bw, height: ih, fill: 'none',
                   stroke: (b.color || '#f59e0b'), 'stroke-opacity': 0.45, 'stroke-dasharray': '4 3'});
      if (b.label) add('text', {x: bx + 4, y: M.t + 12, fill: b.color || '#b45309',
                                'font-size': 10.5, 'font-weight': 600}, b.label);
    });

    // 网格与坐标轴
    const ticks = 5;
    for (let i = 0; i <= ticks; i++) {
      const v = y0 + (yMax - y0) * i / ticks, y = Y(v);
      add('line', {x1: M.l, x2: M.l + iw, y1: y, y2: y, class: 'gridline'});
      add('text', {x: M.l - 8, y: y + 4, 'text-anchor': 'end', class: 'axis'},
          fmt(v, v >= 100 ? 0 : 1));
    }
    add('line', {x1: M.l, x2: M.l + iw, y1: M.t + ih, y2: M.t + ih, stroke: '#cbd5e1'});
    add('line', {x1: M.l, x2: M.l, y1: M.t, y2: M.t + ih, stroke: '#cbd5e1'});
    const xt = 6;
    for (let i = 0; i <= xt; i++) {
      const t = x0 + (x1 - x0) * i / xt;
      add('text', {x: X(t), y: M.t + ih + 18, 'text-anchor': 'middle', class: 'axis'},
          fmt(rel(t), 0) + 's');
    }
    add('text', {x: M.l + iw / 2, y: H - 2, 'text-anchor': 'middle', class: 'axis'},
        '运行开始后秒数');
    // 纵轴标题放在绘图区左上角，避免压住最上面的刻度值
    if (cfg.yLabel) add('text', {x: M.l, y: 10, class: 'axis', 'font-weight': 600}, cfg.yLabel);

    // 折线
    cfg.series.forEach(s => {
      if (!s.pts.length) return;
      const d = s.pts.map((p, i) => (i ? 'L' : 'M') + X(p[0]).toFixed(2) + ' ' + Y(p[1]).toFixed(2)).join(' ');
      add('path', {d: d, fill: 'none', stroke: s.color, 'stroke-width': s.width || 1.8,
                   'stroke-dasharray': s.dash ? '5 4' : 'none', 'stroke-linejoin': 'round'});
    });

    // 三角点
    (cfg.markers || []).forEach(m => {
      const cx = X(m.t), cy = Y(m.v);
      add('path', {d: 'M' + cx + ' ' + (cy - 6) + ' L' + (cx + 5.5) + ' ' + (cy + 4) + ' L' + (cx - 5.5) + ' ' + (cy + 4) + ' Z',
                   fill: m.color || '#d64545', stroke: '#fff', 'stroke-width': 1.2});
      if (m.label) add('text', {x: cx, y: cy - 10, 'text-anchor': 'middle',
                                fill: m.color || '#d64545', 'font-size': 10.5, 'font-weight': 600}, m.label);
    });

    // 悬浮提示
    const hover = add('line', {x1: 0, x2: 0, y1: M.t, y2: M.t + ih, stroke: '#94a3b8',
                               'stroke-dasharray': '3 3', opacity: 0});
    const overlay = add('rect', {x: M.l, y: M.t, width: iw, height: ih, fill: 'transparent'});
    overlay.addEventListener('mousemove', ev => {
      const rect = svg.getBoundingClientRect();
      const mx = (ev.clientX - rect.left) * (W / rect.width);
      const t = x0 + (mx - M.l) / iw * (x1 - x0);
      hover.setAttribute('x1', mx); hover.setAttribute('x2', mx); hover.setAttribute('opacity', 1);
      const rows = cfg.series.map(s => {
        if (!s.pts.length) return null;
        let best = s.pts[0];
        for (const p of s.pts) if (Math.abs(p[0] - t) < Math.abs(best[0] - t)) best = p;
        return '<div><span style="color:' + s.color + '">■</span> ' + esc(s.name) + '：<b>'
             + fmt(best[1], 2) + '</b> ' + esc(s.unit || '') + '</div>';
      }).filter(Boolean).join('');
      tip.innerHTML = '<div class="muted">' + fmt(rel(t), 0) + ' s（' + new Date(t).toLocaleTimeString() + '）</div>' + rows;
      tip.style.left = Math.min(ev.clientX + 14, window.innerWidth - 330) + 'px';
      tip.style.top = Math.max(ev.clientY - 10, 8) + 'px';
      tip.style.opacity = 1;
    });
    overlay.addEventListener('mouseleave', () => { tip.style.opacity = 0; hover.setAttribute('opacity', 0); });
    host.appendChild(svg);
  }

  // ---------- TPS / 延迟 ----------
  const mon = S.monitor || [];
  const bands = (S.faults || []).filter(f => f.startMs && f.endMs).map((f, i, a) => ({
    from: f.startMs, to: f.endMs,
    label: (f.shortName || f.name || ('故障' + (i + 1))) + (f.durationSec ? '（' + f.durationSec + 's）' : ''),
    color: ['#f59e0b', '#ef4444', '#8b5cf6'][i % 3]
  }));
  const tpsPts = mon.map(r => [r.t, r.tps]);
  const avgPts = mon.map(r => [r.t, r.avg]);
  const p95Pts = mon.map(r => [r.t, r.p95]);
  const markers = [];
  (S.triangles || []).forEach((t, i) => {
    const useFault = t.fault_t_start_ms != null;
    const pts = useFault
      ? [[t.fault_t_start_ms, t.fault_t_start_tps, '起'], [t.fault_t_min_ms, t.fault_t_min_tps, '谷'], [t.fault_t_end_ms, t.fault_t_end_tps, '恢']]
      : [[t.t_start_ms, t.t_start_tps, '起'], [t.t_min_ms, t.t_min_tps, '谷'], [t.t_end_ms, t.t_end_tps, '恢']];
    pts.forEach(([ms, v, name]) => {
      if (ms == null || v == null) return;
      markers.push({t: Number(ms), v: Number(v), label: name, color: ['#d64545', '#ea580c', '#7c5cff'][i % 3]});
    });
  });
  if (tpsPts.length) {
    $('tpsLegend').innerHTML =
      '<span><i style="background:#2f6fed"></i>TPS</span>' +
      bands.map(b => '<span><i style="background:' + b.color + ';opacity:.5"></i>' + esc(b.label) + '</span>').join('') +
      '<span><i style="background:#d64545"></i>韧性三角点（▲ 起点/谷底/恢复）</span>';
    drawChart($('chartTps'), {series: [{name: 'TPS', unit: 'req/s', color: '#2f6fed', pts: tpsPts}],
                              bands, markers, yLabel: 'req/s', height: 320});
  } else {
    $('chartTps').innerHTML = '<div class="empty">未归档 monitor.csv（逐秒 TPS）</div>';
    $('tpsLegend').innerHTML = '';
  }
  if (avgPts.length) {
    $('latLegend').innerHTML = '<span><i style="background:#0ea5e9"></i>平均延迟</span>'
      + '<span><i style="background:#f97316"></i>P95 延迟</span>'
      + bands.map(b => '<span><i style="background:' + b.color + ';opacity:.5"></i>' + esc(b.label) + '</span>').join('');
    drawChart($('chartLat'), {series: [
      {name: '平均延迟', unit: 'ms', color: '#0ea5e9', pts: avgPts},
      {name: 'P95 延迟', unit: 'ms', color: '#f97316', pts: p95Pts, dash: true},
    ], bands, yLabel: 'ms', height: 260});
  } else {
    $('chartLat').innerHTML = '<div class="empty">未归档 monitor.csv（延迟曲线）</div>';
  }

  window.addEventListener('resize', () => { location.reload(); });

  // ---------- 韧性三角表 ----------
  function table(headers, rows) {
    return '<table><thead><tr>' + headers.map(h => '<th>' + esc(h) + '</th>').join('')
      + '</tr></thead><tbody>' + rows.map(r => '<tr>' + r.map((c, i) =>
        '<td' + (i ? ' class="num"' : '') + '>' + (c == null || c === '' ? '<span class="muted">-</span>' : esc(c)) + '</td>'
      ).join('') + '</tr>').join('') + '</tbody></table>';
  }
  const triRows = (S.triangles || []).map((t, i) => {
    const f = (S.faults || [])[i] || {};
    const useFault = t.fault_t_start_ms != null;
    const p = useFault
      ? [t.fault_t_start_ms, t.fault_t_min_ms, t.fault_t_end_ms]
      : [t.t_start_ms, t.t_min_ms, t.t_end_ms];
    const v = useFault
      ? [t.fault_t_start_tps, t.fault_t_min_tps, t.fault_t_end_tps]
      : [t.t_start_tps, t.t_min_tps, t.t_end_tps];
    const base = useFault ? t.fault_baseline_tps : t.baseline_tps;
    const drop = (v[0] && v[1] != null) ? (1 - v[1] / v[0]) * 100 : null;
    return [
      '第 ' + (i + 1) + ' 轮 · ' + (f.shortName || t['Fault Name'] || f.name || ''),
      (f.startText || '') + ' ~ ' + (f.endText || ''),
      f.durationSec != null ? f.durationSec + ' s' : (t.duration_milliseconds ? (t.duration_milliseconds / 1000) + ' s' : ''),
      useFault ? '注入窗口（fault_t_*）' : '算法检测（t_*）',
      fmt(p[0] != null ? rel(p[0]) : null, 0) + 's / ' + fmt(v[0], 1),
      fmt(p[1] != null ? rel(p[1]) : null, 0) + 's / ' + fmt(v[1], 1),
      fmt(p[2] != null ? rel(p[2]) : null, 0) + 's / ' + fmt(v[2], 1),
      fmt(base, 1),
      drop != null ? fmt(drop, 1) + ' %' : '-',
      fmt(t.rto, 1), fmt(t.rpo, 1),
      t.outage_seconds != null ? fmt(t.outage_seconds, 1) + ' s' : '-',
      t.data_loss != null ? fmt(t.data_loss, 0) : '-',
    ];
  });
  $('triangleTable').innerHTML = triRows.length
    ? table(['轮次 / 故障', '注入窗口', '计划时长', '三角来源', '起点（时刻 / TPS）',
             '谷底（时刻 / TPS）', '恢复（时刻 / TPS）', '基线 TPS', 'TPS 跌幅', 'RTO', 'RPO',
             '中断时长', '数据丢失'], triRows)
      + '<div class="sub">时刻 = 运行开始后的秒数；TPS 单位 req/s。' +
        '「起点 → 谷底 → 恢复」三个点就是前端韧性三角图上的 ▲。</div>'
    : '<div class="card empty">未归档 metric.json，无法计算韧性三角</div>';

  // ---------- 韧性评分表 ----------
  const scoreCols = [['throughput', '吞吐'], ['latency', '延迟'], ['stability', '稳定性'],
                     ['defence', '防御性'], ['recovery', '恢复性'], ['adaptability', '适应性'],
                     ['volatility', '波动性'], ['recovery_time_factor', '恢复时间因子']];
  const scoreRows = (S.triangles || []).map((t, i) => [
    '第 ' + (i + 1) + ' 轮',
  ].concat(scoreCols.map(([k]) => t[k] != null ? fmt(t[k], 2) : null)));
  if (scoreRows.length) {
    const avgRow = ['平均'].concat(scoreCols.map(([k]) => {
      const vs = S.triangles.map(t => Number(t[k])).filter(Number.isFinite);
      return vs.length ? fmt(vs.reduce((a, b) => a + b, 0) / vs.length, 2) : null;
    }));
    $('scoreTable').innerHTML = table(['轮次'].concat(scoreCols.map(([, n]) => n)), scoreRows.concat([avgRow]))
      + '<div class="sub">数值来自 metric.json（与前端雷达图同一份数据）。</div>';
  } else {
    $('scoreTable').innerHTML = '<div class="card empty">未归档 metric.json，无评分</div>';
  }

  // ---------- 监控指标 ----------
  const mSeries = S.monitorSeries || [];
  const mIds = mSeries.map(c => c.metric);
  const isCpuScope = mIds.length > 0 && mIds.every(m => m.indexOf('cpu') >= 0);
  const isMemScope = mIds.length > 0 && mIds.every(m => m.indexOf('memory') >= 0);
  $('monitorTitle').textContent = '六、监控数据'
    + (isCpuScope ? '（CPU 口径）' : isMemScope ? '（内存口径）' : '');
  const cpuBox = $('cpuCharts');
  cpuBox.innerHTML = '';
  if (!mSeries.length) {
    cpuBox.innerHTML = '<div class="card empty">未归档监控数据（monitoring/series.json），'
      + '或本次没采到 ' + esc((S.requestedMetrics || []).join(' / ') || '目标指标') + '</div>';
  } else {
    mSeries.forEach((c, idx) => {
      const panel = document.createElement('div');
      panel.className = 'panel';
      const lab = Object.entries(c.labels || {}).map(([k, v]) => k + '=' + v).join('，');
      panel.innerHTML = '<h3>' + esc(c.label) + '（' + esc(c.metric) + '，单位 ' + esc(c.unit || '-') + '）'
        + '</h3><div class="legend"><span><i style="background:' + (idx ? '#12a150' : '#7c5cff') + '"></i>'
        + esc(c.metric) + '，共 ' + c.pts.length + ' 个采样点</span>' + (lab ? '<span>' + esc(lab) + '</span>' : '')
        + '</div><div id="cpuChart' + idx + '"></div>';
      cpuBox.appendChild(panel);
      drawChart(panel.querySelector('#cpuChart' + idx), {
        series: [{name: c.metric, unit: c.unit, color: idx ? '#12a150' : '#7c5cff', pts: c.pts}],
        bands, yLabel: c.unit, height: 240});
    });
    const note = document.createElement('div');
    note.className = 'sub';
    note.style.margin = '0 4px 10px';
    note.textContent = isCpuScope
      ? 'cAdvisor 口径：container.cpu.usage 的 100% ≈ 1 核，32 核满载约 3200%；'
        + 'container.cpu.cores 是折算后的核数。故障窗口内的台阶即 CPU 满载注入。'
      : isMemScope
        ? '容器内存口径：container.memory.usage 是 cgroup memory limit 的使用百分比'
          + '（本例上限 40 GiB）。故障窗口内的台阶即内存占用注入。'
        : '阴影为故障注入窗口；曲线取自 monitoring/series.json 的原始采样点。';
    cpuBox.appendChild(note);
    const missing = (S.requestedMetrics || []).filter(m => !mIds.includes(m));
    if (missing.length) {
      const miss = document.createElement('div');
      miss.className = 'sub';
      miss.style.margin = '0 4px 10px';
      miss.textContent = '本次未采到（series.json 查询失败或无数据）：' + missing.join(' / ');
      cpuBox.appendChild(miss);
    }
  }
  const statRows = mSeries.map(c => {
    const vs = c.pts.map(p => p[1]);
    const avg = vs.reduce((a, b) => a + b, 0) / (vs.length || 1);
    const faultVs = [];
    (S.faults || []).forEach(f => {
      if (!f.startMs || !f.endMs) return;
      c.pts.forEach(([t, v]) => { if (t >= f.startMs && t <= f.endMs) faultVs.push(v); });
    });
    return [c.metric, c.unit, c.pts.length, fmt(Math.min(...vs), 2), fmt(Math.max(...vs), 2),
            fmt(avg, 2), faultVs.length ? fmt(Math.max(...faultVs), 2) : null,
            faultVs.length ? fmt(faultVs.reduce((a, b) => a + b, 0) / faultVs.length, 2) : null];
  });
  $('cpuStats').innerHTML = statRows.length
    ? table(['指标', '单位', '采样点', '最小', '最大', '平均', '故障窗口内最大', '故障窗口内平均'], statRows)
    : '';

  // ---------- 故障注入记录 ----------
  const fRows = (S.faults || []).map(f => {
    const r = (f.results || [])[0] || {};
    const v = f.verification || {};
    return [
      '第 ' + f.index + ' 轮',
      f.shortName || f.name,
      f.group,
      (f.delaySec != null ? f.delaySec + ' s' : '-'),
      (f.durationSec != null ? f.durationSec + ' s' : '-'),
      f.startText + ' ~ ' + f.endText,
      r.endPoint || '-',
      r.returncode != null ? String(r.returncode) : '-',
      r.elapsedMs != null ? fmt(r.elapsedMs, 0) + ' ms' : '-',
      r.error ? '是' : (r.replied === false ? '未回执' : '否'),
      v.status ? v.status + (v.error ? '（' + v.error + '）' : '') : '-',
      f.injectCmd,
    ];
  });
  $('faultTable').innerHTML = fRows.length
    ? table(['轮次', '故障', '组', '注入时刻', '持续', '实际窗口', '目标', '返回码', '耗时',
             '错误', '自检', '注入命令'], fRows)
    : '<div class="card empty">未归档 faultInject.json</div>';

  // ---------- 文件清单 ----------
  $('files').innerHTML = '<ul class="sub">' + [
    'job.json · 运行状态与起止时间',
    'run-meta.json · 库/负载/故障配置快照',
    'summary.json · 总吞吐与延迟分布',
    'metric.json · 韧性三角点与每轮评分',
    'monitor.csv · 逐秒 TPS / 延迟',
    'faultInject.json · 注入命令与回执',
    'monitoring/series.json · 监控指标序列（本报告只取 CPU 两条）',
  ].map(x => '<li>' + esc(x) + '</li>').join('') + '</ul>';
})();
</script>
</body>
</html>
"""


def render_html(payload):
    title = payload["run"]["name"] or "实验报告"
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    body = body.replace("</", "<\\/")  # 防止提前闭合 <script>
    return (TEMPLATE.replace("__TITLE__", title).replace("__PAYLOAD__", body))


def report_title(path):
    """取已生成报告里的 <title>，索引里用它当报告名（如「net corrupt 故障」）。"""
    try:
        with open(path, encoding="utf-8") as f:
            head = f.read(4096)
        i = head.find("<title>")
        j = head.find("</title>", i)
        if i >= 0 and j > i:
            return head[i + len("<title>"):j].strip()
    except Exception:  # noqa: BLE001 - 读不到就退回目录名
        pass
    return ""


def write_index(root):
    """在 root 下扫描子目录里的 report.html，生成 index.html。"""
    items = []
    for name in sorted(os.listdir(root)):
        p = os.path.join(root, name, "report.html")
        if os.path.isfile(p):
            items.append((name, os.path.join(name, "report.html"), report_title(p)))
    rows = "\n".join(
        f'<tr><td>{html_mod.escape(title)}</td><td><a href="{href}">{html_mod.escape(name)}</a></td></tr>'
        for name, href, title in items
    ) or '<tr><td colspan="2" class="muted">还没有报告</td></tr>'
    html = f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>{os.path.basename(os.path.abspath(root))} · 实验报告索引</title>
<style>body{{font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
margin:32px;color:#1f2430;background:#f6f7f9}}a{{color:#2f6fed;text-decoration:none}}
h1{{font-size:18px}}table{{border-collapse:collapse;background:#fff;width:100%}}
td{{border:1px solid #e5e7eb;padding:8px 10px}}
th{{border:1px solid #e5e7eb;padding:8px 10px;background:#eef1f6;text-align:left}}
.muted{{color:#6b7280}}</style></head>
<body><h1>{os.path.basename(os.path.abspath(root))} · 实验报告索引</h1>
<p class="muted">共 {len(items)} 份报告</p>
<table><tr><th>报告</th><th>目录</th></tr>{rows}</table></body></html>"""
    out = os.path.join(root, "index.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    return out, len(items)


def main():
    ap = argparse.ArgumentParser(description="把运行归档导出为自包含 HTML 报告")
    ap.add_argument("run_dir", help="归档目录（含 job.json / metric.json / monitor.csv 等）")
    ap.add_argument("--out", help="输出文件，默认 <run_dir>/report.html")
    ap.add_argument("--index", action="store_true", help="同时刷新上级目录的 index.html")
    ap.add_argument("--metrics", help="逗号分隔的监控指标，默认 CPU 两条。"
                                     "例：container.memory.usage")
    ap.add_argument("--title", help="报告标题（默认取 run-meta 的 historyName）")
    ap.add_argument("--clip-to-tps", action="store_true",
                    help="把监控曲线裁剪到 TPS 曲线的时间范围内（reload/建仓的批次用）")
    args = ap.parse_args()

    if not os.path.isdir(args.run_dir):
        sys.exit(f"不是目录：{args.run_dir}")

    metrics = None
    if args.metrics:
        metrics = []
        for mid in args.metrics.split(","):
            mid = mid.strip()
            if not mid:
                continue
            unit, label = METRIC_LABELS.get(mid, ("", mid))
            metrics.append((mid, unit, label))

    payload = build_payload(args.run_dir, metrics,
                            clip_to_tps=args.clip_to_tps, title=args.title)
    html = render_html(payload)
    out = args.out or os.path.join(args.run_dir, "report.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"✔ {out}  ({os.path.getsize(out) / 1024:.1f} KB)")
    if args.index:
        # 索引放在「运行目录的上一级」（即 实验数据/pg-tpcc/ 这类分组目录）
        group = os.path.dirname(os.path.dirname(os.path.abspath(out)))
        idx, n = write_index(group)
        print(f"✔ {idx}  ({n} 份报告)")


if __name__ == "__main__":
    main()
