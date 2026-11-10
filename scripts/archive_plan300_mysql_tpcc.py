#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 plan_300 · MySQL × TPC-C 主批次归档到 plan实验数据/mysql-tpcc/。

规则（与用户要求一致）：
  1. 只归档批次 CSV 里不含 process.stop 的测试 —— Process Stop 在本轮没有真正注入；
  2. 每个归档目录 = 引擎完整产物 + monitoring/series.json(+session.json) + report.html；
  3. 报告只画「该测试所含故障」对应的监控指标（多个故障取并集），并额外写
     fault-metrics.json 记录每个故障窗口的基线/窗口统计；
  4. 组目录写 index.html（统计卡片 + 故障分布 + 判定分布 + 可筛选明细表）。

用法：
  python3 scripts/archive_plan300_mysql_tpcc.py --dry-run
  python3 scripts/archive_plan300_mysql_tpcc.py
"""
from __future__ import annotations

import argparse
import csv
import html as html_mod
import importlib.util
import json
import os
import re
import shutil
import statistics
import sys
from collections import Counter, OrderedDict
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPORT_SCRIPT = ROOT / "scripts" / "export_run_report.py"
DEFAULT_BATCH_CSV = (ROOT / "batch-results" / "4groups" /
                     "20261103_001535_mysql-2gbSharedBuffer-1000_tpcc-10w_plan300-mysql-tpcc-main" /
                     "runs-20261103_001535.csv")
RESULTS = ROOT / "backend" / "resbenchmark" / "results"
MONITORING = ROOT / "backend" / "res-manager" / "data" / "monitoring"
DEFAULT_TARGET = ROOT / "plan实验数据" / "mysql-tpcc"

# 报告里的指标顺序固定，保证不同测试的报告横向可比。
METRIC_ORDER = [
    "container.cpu.usage",
    "container.memory.usage",
    "net.qdisc.drop.percent",
    "net.ping.loss",
    "net.ping.rtt",
    "net.qdisc.reorder.rate",
    "net.tcp.dsack.rate",
    "net.tcp.rto.rate",
    "net.tcp.lost_retrans.rate",
    "host.disk.usage",
    "host.process.stopped",
    "db.connections.usage",
    "db.connections.open_rate",
    "db.sessions",
    "db.rollback.rate",
]

FAULT_SHORT = {
    "faultinject.fault.cpu.load": "CPU 负载",
    "faultinject.fault.memory.load": "内存负载",
    "faultinject.fault.network.package": "网络丢包",
    "faultinject.fault.network.delay": "网络延迟",
    "faultinject.fault.network.unorder": "网络乱序",
    "faultinject.fault.network.duplicate": "网络重复包",
    "faultinject.fault.disk.fill": "磁盘写满",
    "faultinject.fault.disk.filechmod": "文件权限 000",
    "faultinject.fault.disk.filemove": "文件移动",
    "faultinject.fault.process.stop": "进程暂停",
    "faultinject.fault.connection.surge": "连接风暴",
    "faultinject.fault.connection.limit.exceeded": "连接数耗尽",
    "faultinject.fault.transaction.massive.rollback": "大量事务回滚",
}

# faultInject.json 里的 faultName 是中文/英文展示名，run-meta.json 里是 nameKey；
# 两者顺序不一定一致（同一批故障按实际注入完成时间落盘），必须按名字配对。
FAULT_NAME_TO_KEY = {
    "CPU Load": "faultinject.fault.cpu.load",
    "Memory Load": "faultinject.fault.memory.load",
    "Network Packet Loss": "faultinject.fault.network.package",
    "Network Delay": "faultinject.fault.network.delay",
    "Network Reorder": "faultinject.fault.network.unorder",
    "Network Duplicate": "faultinject.fault.network.duplicate",
    "Disk Fill": "faultinject.fault.disk.fill",
    "File Chmod": "faultinject.fault.disk.filechmod",
    "File Move": "faultinject.fault.disk.filemove",
    "Process Stop": "faultinject.fault.process.stop",
    "Connection Storm": "faultinject.fault.connection.surge",
    "Connection Limit Exceeded": "faultinject.fault.connection.limit.exceeded",
    "Massive Transaction Rollback": "faultinject.fault.transaction.massive.rollback",
    "连接风暴": "faultinject.fault.connection.surge",
    "连接数耗尽": "faultinject.fault.connection.limit.exceeded",
    "海量事务回滚": "faultinject.fault.transaction.massive.rollback",
}

# 故障 → 该故障的判据指标。主判据在前，辅证在后；顺序与 METRIC_ORDER 无关。
FAULT_METRICS = {
    "faultinject.fault.cpu.load": ["container.cpu.usage"],
    "faultinject.fault.memory.load": ["container.memory.usage"],
    "faultinject.fault.network.package": [
        "net.qdisc.drop.percent", "net.ping.loss",
        "net.tcp.rto.rate", "net.tcp.lost_retrans.rate",
    ],
    "faultinject.fault.network.delay": ["net.ping.rtt", "net.tcp.rto.rate"],
    "faultinject.fault.network.unorder": ["net.qdisc.reorder.rate", "net.tcp.rto.rate"],
    "faultinject.fault.network.duplicate": ["net.tcp.dsack.rate", "net.tcp.rto.rate"],
    "faultinject.fault.disk.fill": ["host.disk.usage"],
    # 文件权限没有专用计数器；host.disk.usage 只作为资源背景，主证据是注入回执 + TPS/延迟。
    "faultinject.fault.disk.filechmod": ["host.disk.usage"],
    # 文件移动同样没有专用计数器，用磁盘使用率作资源背景（文件不在原位）。
    "faultinject.fault.disk.filemove": ["host.disk.usage"],
    "faultinject.fault.process.stop": ["host.process.stopped"],
    "faultinject.fault.connection.surge": [
        "db.connections.usage", "db.connections.open_rate",
        "db.sessions", "container.cpu.usage",
    ],
    "faultinject.fault.connection.limit.exceeded": [
        "db.connections.usage", "db.connections.open_rate", "db.sessions",
    ],
    "faultinject.fault.transaction.massive.rollback": ["db.rollback.rate"],
}

FAULT_NOTE = {
    "faultinject.fault.disk.filechmod":
        "无专用监控计数器；host.disk.usage 仅作资源背景，主证据是注入回执 + TPS/延迟。",
    "faultinject.fault.disk.filemove":
        "无专用监控计数器；host.disk.usage 仅作资源背景，主证据是注入回执 + TPS/延迟。",
    "faultinject.fault.process.stop":
        "主判据是宿主机上处于 T 状态的进程数（host.process.stopped）；MySQL 场景匹配 mysqld。",
    "faultinject.fault.disk.fill":
        "主判据是数据目录所在挂载点的文件系统使用率；报告自动选变化幅度最大的挂载点。",
    "faultinject.fault.connection.surge":
        "db.sessions 报告里画单条状态曲线（按点数选），fault-metrics.json 里按时间戳对所有状态求和。",
    "faultinject.fault.connection.limit.exceeded":
        "db.sessions 报告里画单条状态曲线（按点数选），fault-metrics.json 里按时间戳对所有状态求和。",
}

SHORT_METRIC = {
    "container.cpu.usage": "CPU",
    "container.memory.usage": "内存",
    "net.qdisc.drop.percent": "网卡丢包",
    "net.ping.loss": "ICMP 丢包",
    "net.ping.rtt": "RTT",
    "net.qdisc.reorder.rate": "乱序",
    "net.tcp.dsack.rate": "DSACK",
    "net.tcp.rto.rate": "RTO",
    "net.tcp.lost_retrans.rate": "丢包重传",
    "host.disk.usage": "磁盘使用率",
    "host.process.stopped": "暂停进程数",
    "db.connections.usage": "连接使用率",
    "db.connections.open_rate": "新建连接速率",
    "db.sessions": "Session",
    "db.rollback.rate": "回滚速率",
}

STATUS_ORDER = ["ENFORCED", "PARTIAL", "NO_DATA", "NOT_ENFORCED", "ERROR", "UNKNOWN"]


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------
def load_json(path: Path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default
    except Exception:
        return default


def load_report_module():
    spec = importlib.util.spec_from_file_location("export_run_report", EXPORT_SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def plan_no(history_name: str) -> str:
    m = re.search(r"plan-(\d{6})", history_name)
    return m.group(1) if m else history_name


def fault_metric_union(name_keys):
    keys = set(name_keys)
    return [m for m in METRIC_ORDER
            if any(m in FAULT_METRICS.get(k, []) for k in keys)]


def metric_label(mod, mid):
    return mod.METRIC_LABELS.get(mid, ("", mid))[1]


def pct(part, whole):
    return round(100.0 * part / whole, 1) if whole else 0.0


def percentile(vals, q):
    if not vals:
        return None
    vals = sorted(vals)
    if len(vals) == 1:
        return vals[0]
    pos = (len(vals) - 1) * q
    lo = int(pos)
    hi = min(lo + 1, len(vals) - 1)
    return vals[lo] + (vals[hi] - vals[lo]) * (pos - lo)


def window_stats(points, lo_ms, hi_ms):
    """points: [(ms, value)]；左闭右开。"""
    vals = [v for t, v in points if lo_ms <= t < hi_ms and v is not None]
    if not vals:
        return None
    return {
        "n": len(vals),
        "mean": round(statistics.fmean(vals), 4),
        "min": round(min(vals), 4),
        "max": round(max(vals), 4),
        "p95": round(percentile(vals, 0.95), 4),
    }


def series_points(mod, series_json, mid):
    """取一条指标用于统计。db.sessions 有多个状态，按时间戳求和；其余用 pick_series。"""
    if mid == "db.sessions":
        by_ts = {}
        for s in (series_json or {}).get("series", []) or []:
            if s.get("metricId") != mid:
                continue
            for p in s.get("points") or []:
                if p and len(p) >= 2:
                    t = int(p[0])
                    by_ts[t] = by_ts.get(t, 0.0) + float(p[1])
        return sorted(by_ts.items())
    s = mod.pick_series(series_json, mid)
    return [(int(t), float(v)) for t, v in (s or {}).get("pts", [])]


def series_labels(mod, series_json, mid):
    if mid == "db.sessions":
        return {"aggregate": "sum(states)"}
    s = mod.pick_series(series_json, mid)
    if not s:
        return {}
    labels = dict(s.get("labels") or {})
    labels["_seriesUnit"] = s.get("unit") or ""
    return labels


def make_title(plan: str, name_keys) -> str:
    names = []
    for k in name_keys:
        n = FAULT_SHORT.get(k, k)
        if n not in names:
            names.append(n)
    return f"plan-{plan} · " + " + ".join(names)


def match_meta_fault(fault, meta_faults, index):
    """按 faultName 找 run-meta 里对应的 nameKey；找不到再退回同下标。"""
    key = FAULT_NAME_TO_KEY.get(fault.get("faultName") or "", "")
    candidates = [x for x in meta_faults if x.get("nameKey") == key]
    if candidates:
        group = fault.get("groupKey")
        same_group = [x for x in candidates if x.get("groupKey") == group]
        return (same_group or candidates)[0], key
    mf = meta_faults[index] if index < len(meta_faults) else {}
    return mf, mf.get("nameKey") or ""


def build_fault_metrics(mod, history_name, meta_faults, fault_inject, series_json):
    faults = []
    for i, f in enumerate(fault_inject):
        mf, key = match_meta_fault(f, meta_faults, i)
        start = f.get("startTime")
        end = f.get("endTime")
        entry = {
            "index": i + 1,
            "faultName": f.get("faultName") or FAULT_SHORT.get(key, key),
            "nameKey": key,
            "planningSec": mf.get("planningSeconds"),
            "plannedDelaySec": f.get("faultDelay"),
            "durationSec": f.get("faultDuration"),
            "startMs": start,
            "endMs": end,
            "params": mf.get("params") or {},
            "injectionStatus": (f.get("verification") or {}).get("status") or "",
            "injectionError": (f.get("verification") or {}).get("error") or "",
            "metrics": [],
            "note": FAULT_NOTE.get(key, ""),
        }
        if start and end:
            start, end = int(start), int(end)
            for mid in FAULT_METRICS.get(key, []):
                pts = series_points(mod, series_json, mid)
                base_lo, base_hi = start - 60_000, start
                base = window_stats(pts, base_lo, base_hi)
                if not base or base["n"] < 3:
                    base = window_stats(pts, start - 180_000, start)
                win = window_stats(pts, start, end + 1)
                unit, label = mod.METRIC_LABELS.get(mid, ("", mid))
                item = {
                    "metric": mid,
                    "label": label,
                    "unit": unit,
                    "seriesLabels": series_labels(mod, series_json, mid),
                    "points": len(pts),
                    "available": bool(pts),
                    "baseline": base,
                    "window": win,
                    "deltaMean": (round(win["mean"] - base["mean"], 4)
                                  if base and win else None),
                    "ratioMean": (round(win["mean"] / base["mean"], 4)
                                  if base and win and abs(base["mean"]) > 1e-9 else None),
                }
                entry["metrics"].append(item)
        if not series_json.get("series"):
            entry["note"] = (entry["note"] + " " if entry["note"] else "") + \
                "本轮监控未采集（Prometheus 未就绪）。"
        faults.append(entry)
    return faults


# ---------------------------------------------------------------------------
# HTML 索引
# ---------------------------------------------------------------------------
def esc(x):
    return html_mod.escape(str(x if x is not None else ""))


def status_class(s):
    return {
        "ENFORCED": "good", "PARTIAL": "warn", "NO_DATA": "muted",
        "NOT_ENFORCED": "bad", "ERROR": "bad", "UNKNOWN": "muted",
    }.get(s, "muted")


def status_dot(s):
    return {"good": "●", "warn": "▲", "bad": "■", "muted": "○"}.get(status_class(s), "○")


def parse_fault_verify(text):
    out = Counter()
    for tok in (text or "").split():
        if ":" in tok:
            k, v = tok.split(":", 1)
            try:
                out[k] += int(v)
            except ValueError:
                pass
    return out


def render_index(target: Path, records, excluded, fault_counts, inject_counts,
                 monitor_counts, totals, metric_mapping, metric_label_by_id,
                 generated_at):
    n_arch = totals["archived"]
    max_fault = max(fault_counts.values()) if fault_counts else 1

    # 故障分布：单色排序条形图（不是 11 种分类色）。
    fault_rows = []
    for key, cnt in sorted(fault_counts.items(), key=lambda kv: (-kv[1], kv[0])):
        short = FAULT_SHORT.get(key, key)
        width = max(2.0, 100.0 * cnt / max_fault)
        fault_rows.append(
            f'<div class="bar-row" title="{esc(short)}：{cnt} 个测试">'
            f'<div class="bar-label">{esc(short)}</div>'
            f'<div class="bar-track"><div class="bar-fill" style="width:{width:.1f}%"></div></div>'
            f'<div class="bar-value">{cnt}</div></div>'
        )

    def dist_rows(counter, order, total):
        rows = []
        for k in order:
            if k not in counter:
                continue
            v = counter[k]
            rows.append(
                f'<div class="dist-row" title="{esc(k)}：{v}">'
                f'<span class="dot {status_class(k)}">{status_dot(k)}</span>'
                f'<span class="dist-label">{esc(k)}</span>'
                f'<span class="dist-track"><span class="dist-fill {status_class(k)}" '
                f'style="width:{max(2.0, 100.0 * v / max(1, total)):.1f}%"></span></span>'
                f'<span class="dist-value">{v}</span></div>'
            )
        return "".join(rows) or '<div class="muted">无数据</div>'

    # 明细表
    table_rows = []
    for r in records:
        fault_html = "".join(
            f'<span class="chip">{esc(FAULT_SHORT.get(k, k))}'
            + (f' ×{c}' if c > 1 else "") + "</span>"
            for k, c in r["fault_counts"]
        )
        verify_html = "".join(
            f'<span class="badge {status_class(k)}">{status_dot(k)} {esc(k)} ×{v}</span>'
            for k, v in r["inject_counts"].most_common()
        ) or '<span class="muted">—</span>'
        mon_label = r["monitor_verify"] or "未采集"
        mon_badge = (f'<span class="badge {status_class(mon_label)}">'
                     f'{status_dot(mon_label)} {esc(mon_label)}</span>')
        metrics = "、".join(SHORT_METRIC.get(m, m) for m in r["available_metrics"]) or "—"
        if r["missing_metrics"]:
            metrics += ('<div class="tiny missing">缺：'
                        + esc("、".join(SHORT_METRIC.get(m, m) for m in r["missing_metrics"]))
                        + "</div>")
        tri = f'{r["stability"]} / {r["defence"]} / {r["recovery"]}'
        state_cls = "good" if r["state"] == "GRACEFUL" else "warn"
        table_rows.append(
            f'<tr data-faults="{" ".join(r["fault_keys"])}" '
            f'data-verify="{esc(mon_label)}" '
            f'data-search="{esc((r["plan"] + " " + r["history"] + " " + " ".join(r["fault_names"])).lower())}">'
            f'<td class="num">{esc(r["plan"])}</td>'
            f'<td class="test-cell"><a href="{esc(r["href"])}">{esc(r["title"])}</a>'
            f'<div class="mono">{esc(r["history"])}</div></td>'
            f'<td>{fault_html}</td>'
            f'<td>{verify_html}</td>'
            f'<td>{mon_badge}<div class="tiny">{esc(r["monitor_evidence"])}</div></td>'
            f'<td class="tiny">{metrics}</td>'
            f'<td class="num tiny">{esc(tri)}</td>'
            f'<td class="num tiny">{esc(r["rto"])}s / {esc(r["outage"])}s</td>'
            f'<td><span class="badge {state_cls}">{esc(r["state"])}</span></td>'
            f'<td><a class="report-link" href="{esc(r["href"])}">打开 →</a></td></tr>'
        )

    fault_select = "".join(
        f'<option value="{esc(k)}">{esc(FAULT_SHORT.get(k, k))}（{c}）</option>'
        for k, c in sorted(fault_counts.items(), key=lambda kv: (-kv[1], kv[0]))
    )
    verify_select = "".join(
        f'<option value="{esc(k)}">{esc(k)}（{v}）</option>'
        for k, v in sorted(monitor_counts.items(), key=lambda kv: (-kv[1], kv[0]))
    )

    excluded_rows = "".join(
        f'<tr><td class="num">{esc(plan_no(x["history_name"]))}</td>'
        f'<td class="mono">{esc(x["history_name"])}</td>'
        f'<td>{esc(x["faults"])}</td></tr>'
        for x in excluded
    )

    mapping_rows = "".join(
        f'<tr><td>{esc(FAULT_SHORT.get(k, k))}</td>'
        f'<td class="mono">{esc(", ".join(ms))}</td>'
        f'<td>{esc("、".join(metric_label_by_id.get(m, m) for m in ms))}</td></tr>'
        for k, ms in metric_mapping
    )

    include_stop = bool(totals.get("include_process_stop"))
    page_label = totals.get("label") or "plan_300 · MySQL × TPC-C 归档"
    excluded_link = "" if include_stop or not excluded else (
        ' · <a href="excluded-process-stop.csv">排除清单</a>')
    excluded_block = "" if not excluded else f"""
<details>
  <summary>未归档的 {len(excluded)} 个测试（含 Process Stop）</summary>
  <p class="note">这些测试按你的要求没有归档：只要一个测试里出现 Process Stop，整条测试都不收录。下面列出的是被排除的计划与故障组合。</p>
  <div class="tablewrap"><table><thead><tr><th>计划</th><th>目录</th><th>故障</th></tr></thead>
  <tbody>{excluded_rows}</tbody></table></div>
</details>"""
    stop_note = ("Process Stop 已包含在归档中：本批 mysqld 真实进入 T 状态，判据是 host.process.stopped。"
                 if include_stop else "Process Stop 已按要求整体排除，因此不在映射表中。")

    html = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(page_label)} · 索引</title>
<style>
:root{{
  color-scheme:light;
  --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e;
  --muted:#898781; --line:#e1e0d9; --axis:#c3c2b7;
  --blue:#2a78d6; --blue-soft:#9ec5f4;
  --good:#0ca30c; --warn:#fab219; --serious:#ec835a; --bad:#d03b3b;
  --chip:#eef1f6; --shadow:0 1px 2px rgba(11,11,11,.05);
}}
@media (prefers-color-scheme:dark){{
  :root:not([data-theme="light"]){{
    color-scheme:dark;
    --page:#0d0d0d; --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7;
    --muted:#898781; --line:#2c2c2a; --axis:#383835;
    --blue:#3987e5; --blue-soft:#184f95;
    --chip:#242423; --shadow:none;
  }}
}}
:root[data-theme="dark"]{{
  color-scheme:dark;
  --page:#0d0d0d; --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7;
  --muted:#898781; --line:#2c2c2a; --axis:#383835;
  --blue:#3987e5; --blue-soft:#184f95;
  --chip:#242423; --shadow:none;
}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--page);color:var(--ink);
  font:14px/1.6 system-ui,-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}}
.wrap{{max-width:1420px;margin:0 auto;padding:28px 20px 64px}}
header.top{{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;justify-content:space-between;
  border-bottom:2px solid var(--line);padding-bottom:16px;margin-bottom:22px}}
h1{{font-size:22px;margin:0 0 4px;letter-spacing:-.01em}}
.sub{{color:var(--muted);font-size:12.5px}}
.sub code{{font-size:12px;color:var(--ink2)}}
.kpis{{display:grid;grid-template-columns:repeat(5,minmax(150px,1fr));gap:12px;margin:0 0 18px}}
.kpi{{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:14px 16px;box-shadow:var(--shadow)}}
.kpi .v{{font-size:28px;font-weight:650;line-height:1.15;letter-spacing:-.02em}}
.kpi .l{{color:var(--ink2);font-size:12.5px;margin-top:3px}}
.kpi .h{{color:var(--muted);font-size:11.5px;margin-top:2px}}
.grid{{display:grid;grid-template-columns:repeat(12,1fr);gap:14px;margin-bottom:18px}}
.card{{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:16px 18px;box-shadow:var(--shadow)}}
.card h2{{font-size:14px;margin:0 0 12px;font-weight:650}}
.card h2 .n{{color:var(--muted);font-weight:400;font-size:12px}}
.span6{{grid-column:span 6}} .span3{{grid-column:span 3}}
.bar-row,.dist-row{{display:grid;grid-template-columns:112px 1fr 38px;gap:9px;align-items:center;padding:3px 0}}
.bar-label,.dist-label{{font-size:12.5px;color:var(--ink2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
.bar-track,.dist-track{{height:10px;background:var(--chip);border-radius:4px;overflow:hidden;display:block}}
.bar-fill{{height:100%;background:var(--blue);border-radius:4px}}
.bar-value,.dist-value{{text-align:right;font-size:12.5px;font-variant-numeric:tabular-nums;color:var(--ink2)}}
.dist-row{{grid-template-columns:18px 106px 1fr 38px}}
.dist-fill{{display:block;height:100%;border-radius:4px;background:var(--axis)}}
.dist-fill.good{{background:var(--good)}} .dist-fill.warn{{background:var(--warn)}}
.dist-fill.bad{{background:var(--bad)}} .dist-fill.muted{{background:var(--axis)}}
.dot.good{{color:var(--good)}} .dot.warn{{color:var(--warn)}} .dot.bad{{color:var(--bad)}} .dot.muted{{color:var(--muted)}}
.dot{{font-size:11px}}
.filters{{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin:20px 0 10px}}
.filters input,.filters select{{background:var(--surface);color:var(--ink);border:1px solid var(--line);
  border-radius:8px;padding:8px 10px;font:inherit;font-size:13px;min-width:180px}}
.filters input{{min-width:300px;flex:1;max-width:430px}}
.filters .count{{color:var(--muted);font-size:12.5px;margin-left:auto}}
.tablewrap{{background:var(--surface);border:1px solid var(--line);border-radius:12px;overflow:auto;box-shadow:var(--shadow)}}
table{{border-collapse:collapse;width:100%;min-width:1280px}}
th{{position:sticky;top:0;background:var(--surface);z-index:1;text-align:left;font-size:11.5px;
  text-transform:uppercase;letter-spacing:.04em;color:var(--muted);font-weight:600;
  border-bottom:1px solid var(--line);padding:10px 10px;white-space:nowrap}}
td{{border-bottom:1px solid var(--line);padding:9px 10px;vertical-align:top;font-size:12.5px}}
tr:last-child td{{border-bottom:0}}
tbody tr:hover{{background:color-mix(in srgb,var(--blue) 5%,transparent)}}
a{{color:var(--blue);text-decoration:none}} a:hover{{text-decoration:underline}}
.num{{font-variant-numeric:tabular-nums;white-space:nowrap}}
.mono{{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:10.5px;color:var(--muted);word-break:break-all;max-width:360px}}
.tiny{{font-size:11px;color:var(--ink2);margin-top:3px;line-height:1.45}}
.tiny.missing{{color:var(--bad)}}
.chip{{display:inline-block;background:var(--chip);color:var(--ink2);border-radius:999px;
  padding:1px 8px;margin:1px 3px 1px 0;font-size:11px;white-space:nowrap}}
.badge{{display:inline-block;border-radius:999px;padding:1px 8px;margin:1px 3px 1px 0;font-size:11px;white-space:nowrap}}
.badge.good{{color:var(--good);background:color-mix(in srgb,var(--good) 12%,transparent)}}
.badge.warn{{color:var(--ink);background:color-mix(in srgb,var(--warn) 26%,transparent)}}
.badge.bad{{color:var(--bad);background:color-mix(in srgb,var(--bad) 12%,transparent)}}
.badge.muted{{color:var(--muted);background:var(--chip)}}
.report-link{{white-space:nowrap;font-weight:600}}
details{{margin-top:22px;background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:14px 18px}}
summary{{cursor:pointer;font-weight:650;font-size:14px}}
details table{{margin-top:12px;min-width:900px}}
.note{{color:var(--muted);font-size:12px;margin-top:10px}}
@media (max-width:1080px){{
  .kpis{{grid-template-columns:repeat(2,minmax(140px,1fr))}}
  .span6,.span3{{grid-column:span 12}}
  .filters input{{min-width:0;width:100%}}
}}
</style></head>
<body><div class="wrap">
<header class="top">
  <div>
    <h1>{esc(page_label)}</h1>
    <div class="sub">{'补测批次' if include_stop else '主批次'} <code>{esc(totals["source_batch"])}</code> · 生成于 {esc(generated_at)}
      · <a href="source-{esc(totals["source_batch"])}">原始批次 CSV</a>
      · <a href="manifest.json">manifest.json</a>{excluded_link}</div>
  </div>
  <div class="sub">自包含离线页面 · 每个测试的报告在对应目录的 <code>report.html</code></div>
</header>

<div class="kpis">
  <div class="kpi"><div class="v">{totals["main"]}</div><div class="l">批次测试总数</div><div class="h">{'全部归档' if include_stop else '不含 File Move 的 225 个计划'}</div></div>
  <div class="kpi"><div class="v">{n_arch}</div><div class="l">已归档测试</div><div class="h">{'未排除任何测试' if include_stop else '排除含 Process Stop 的 ' + str(totals["excluded_process_stop"]) + ' 个'}</div></div>
  {('<div class="kpi"><div class="v">%d</div><div class="l">含 Process Stop 的测试</div><div class="h">mysqld 真实进入 T 状态</div></div>' % totals["process_stop_tests"]) if include_stop else ('<div class="kpi"><div class="v">%d</div><div class="l">未归档 · Process Stop</div><div class="h">该故障本轮未真正注入</div></div>' % totals["excluded_process_stop"])}
  <div class="kpi"><div class="v">{totals["monitoring_available"]}</div><div class="l">有监控曲线的测试</div><div class="h">{totals["monitoring_missing"]} 个监控缺失</div></div>
  <div class="kpi"><div class="v">{totals["faults_archived"]}</div><div class="l">归档故障注入次数</div><div class="h">按测试内故障数累计</div></div>
</div>

<div class="grid">
  <div class="card span6">
    <h2>故障类型分布 <span class="n">· {n_arch} 个测试 / {totals["faults_archived"]} 次注入</span></h2>
    {''.join(fault_rows)}
    <div class="note">按测试计数；一个测试可含多个故障，因此各条之和大于 {n_arch}。</div>
  </div>
  <div class="card span3">
    <h2>监控自动判定 <span class="n">· 按测试</span></h2>
    {dist_rows(monitor_counts, ["ENFORCED", "PARTIAL", "NO_DATA", "NOT_ENFORCED", "未采集"], n_arch)}
    <div class="note">PARTIAL = 部分故障命中监控判据；NO_DATA = 判据指标缺数据。</div>
  </div>
  <div class="card span3">
    <h2>注入自检 <span class="n">· 按故障次数</span></h2>
    {dist_rows(inject_counts, STATUS_ORDER, sum(inject_counts.values()) or 1)}
    <div class="note">UNKNOWN 多为该故障没有配置 check 模板，不等于注入失败。</div>
  </div>
</div>

<div class="filters">
  <input id="q" type="search" placeholder="搜索计划号 / 目录名 / 故障…">
  <select id="fault"><option value="">全部故障类型</option>{fault_select}</select>
  <select id="verify"><option value="">全部监控判定</option>{verify_select}</select>
  <span class="count" id="count">显示 {n_arch} / {n_arch}</span>
</div>
<div class="tablewrap">
<table>
<thead><tr>
  <th>计划</th><th>测试 / 报告</th><th>故障</th><th>注入自检</th><th>监控判定</th>
  <th>报告指标</th><th>韧性 S / D / R</th><th>RTO / 中断</th><th>状态</th><th></th>
</tr></thead>
<tbody id="rows">
{''.join(table_rows)}
</tbody></table>
</div>

{excluded_block}

<details>
  <summary>故障 → 监控指标映射</summary>
  <p class="note">每个测试的 report.html 画的是该测试所含故障对应指标的并集；fault-metrics.json 里还记录了每个故障窗口相对基线的统计。</p>
  <div class="tablewrap"><table><thead><tr><th>故障</th><th>指标 ID</th><th>中文名</th></tr></thead>
  <tbody>{mapping_rows}</tbody></table></div>
  <p class="note">文件权限 000 与文件移动没有专用计数器：报告里的磁盘使用率只作资源背景，主证据是注入回执 + TPS/延迟。
  {esc(stop_note)}</p>
</details>

<div class="note">监控指标为本轮采集的 15 项：容器 CPU/内存、网卡丢包/乱序、ICMP 丢包/RTT、TCP DSACK/RTO/丢包重传、
文件系统使用率、进程暂停数、连接使用率/新建连接速率/Session 数、事务回滚速率。
「报告指标」列只列实际有数据的指标；某几项查询失败时在下方用红色标「缺」。</div>
</div>
<script>
const q=document.getElementById('q'), fs=document.getElementById('fault'),
      vs=document.getElementById('verify'), rows=[...document.querySelectorAll('#rows tr')],
      count=document.getElementById('count');
function apply(){{
  const text=(q.value||'').trim().toLowerCase(), f=fs.value, v=vs.value;
  let shown=0;
  for(const tr of rows){{
    const okText=!text||tr.dataset.search.includes(text);
    const okFault=!f||tr.dataset.faults.split(' ').includes(f);
    const okVerify=!v||tr.dataset.verify===v;
    const ok=okText&&okFault&&okVerify;
    tr.style.display=ok?'':'none';
    if(ok) shown++;
  }}
  count.textContent='显示 '+shown+' / {n_arch}';
}}
[q,fs,vs].forEach(el=>el.addEventListener('input',apply));
apply();
</script>
</body></html>"""
    return html


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="归档 plan300 MySQL TPC-C 主批次")
    ap.add_argument("--batch-csv", default=str(DEFAULT_BATCH_CSV))
    ap.add_argument("--target", default=str(DEFAULT_TARGET))
    ap.add_argument("--dry-run", action="store_true", help="只打印统计，不复制/不写文件")
    ap.add_argument("--skip-copy", action="store_true",
                    help="归档目录已存在时跳过复制，只重建 report.html / fault-metrics.json")
    ap.add_argument("--limit", type=int, help="只处理前 N 个归档测试（调试）")
    ap.add_argument("--include-process-stop", action="store_true",
                    help="包含 Process Stop 的测试（补测批次用；默认仍整体排除）")
    ap.add_argument("--label", default="plan_300 · MySQL × TPC-C 归档",
                    help="页面/README 标题")
    args = ap.parse_args()

    batch_csv = Path(args.batch_csv)
    target = Path(args.target)
    if not batch_csv.is_file():
        sys.exit(f"批次 CSV 不存在：{batch_csv}")

    mod = load_report_module()
    metric_label_by_id = {m: mod.METRIC_LABELS.get(m, ("", m))[1] for m in METRIC_ORDER}

    with open(batch_csv, encoding="utf-8", newline="") as f:
        all_rows = list(csv.DictReader(f))

    kept, excluded = [], []
    for row in all_rows:
        if args.include_process_stop:
            kept.append(row)
            continue
        history = row["history_name"]
        meta = load_json(RESULTS / history / "run-meta.json", {})
        meta_faults = meta.get("faults") or []
        keys = [f.get("nameKey") or "" for f in meta_faults]
        has_stop = ("process.stop" in (row.get("faults") or "")
                    or any("process.stop" in k for k in keys))
        if has_stop:
            excluded.append(row)
        else:
            kept.append(row)

    if args.limit:
        kept = kept[:args.limit]

    print(f"批次总数 {len(all_rows)}，归档 {len(kept)}，排除 process.stop {len(excluded)}")
    if not args.dry_run:
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(batch_csv, target / f"source-{batch_csv.name}")

    records = []
    fault_csv_rows = []
    fault_counts = Counter()
    monitor_counts = Counter()
    inject_counts = Counter()
    total_faults = 0
    monitoring_missing = []

    for idx, row in enumerate(kept, 1):
        history = row["history_name"]
        src = RESULTS / history
        if not src.is_dir():
            print(f"  ✘ [{idx}/{len(kept)}] 缺少引擎目录：{src}")
            continue
        meta = load_json(src / "run-meta.json", {})
        meta_faults = meta.get("faults") or []
        key_order = [f.get("nameKey") or "" for f in meta_faults]
        fault_inject = load_json(src / "faultInject.json", [])
        mon_src = MONITORING / history
        series_json = load_json(mon_src / "series.json", {})
        has_series = bool(series_json.get("series"))
        metrics = fault_metric_union(key_order) or ["container.cpu.usage"]
        available_metrics = [m for m in metrics if series_points(mod, series_json, m)]
        missing_metrics = [m for m in metrics if m not in available_metrics]
        title = make_title(plan_no(history), key_order)

        dst = target / history
        if not args.dry_run and not args.skip_copy:
            dst.mkdir(parents=True, exist_ok=True)
            shutil.copytree(src, dst, dirs_exist_ok=True)
            if mon_src.is_dir():
                shutil.copytree(mon_src, dst / "monitoring", dirs_exist_ok=True)
        if not args.dry_run and args.skip_copy and not dst.is_dir():
            print(f"  ✘ [{idx}/{len(kept)}] 归档目录不存在（--skip-copy）：{dst}")
            continue

        if not args.dry_run:
            payload = mod.build_payload(
                str(dst),
                [(m, mod.METRIC_LABELS.get(m, ("", m))[0],
                  mod.METRIC_LABELS.get(m, ("", m))[1]) for m in metrics],
                clip_to_tps=True,
                title=title,
            )
            (dst / "report.html").write_text(mod.render_html(payload), encoding="utf-8")

            fault_stats = build_fault_metrics(mod, history, meta_faults, fault_inject,
                                              series_json)
            fm = {
                "historyName": history,
                "plan": plan_no(history),
                "sourceBatch": batch_csv.name,
                "generatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "monitoringAvailable": has_series,
                "monitoringSeriesCount": len(series_json.get("series") or []),
                "monitoringWarnings": series_json.get("warnings") or [],
                "metrics": metrics,
                "faults": fault_stats,
            }
            (dst / "fault-metrics.json").write_text(
                json.dumps(fm, ensure_ascii=False, indent=2), encoding="utf-8")

            for f in fault_stats:
                for x in f["metrics"]:
                    fault_csv_rows.append({
                        "plan": plan_no(history),
                        "history_name": history,
                        "fault_index": f["index"],
                        "fault_name": f["faultName"],
                        "name_key": f["nameKey"],
                        "planning_sec": f["planningSec"],
                        "duration_sec": f["durationSec"],
                        "start_ms": f["startMs"],
                        "end_ms": f["endMs"],
                        "injection_status": f["injectionStatus"],
                        "metric": x["metric"],
                        "unit": x["unit"],
                        "points": x["points"],
                        "available": x["available"],
                        "baseline_n": (x["baseline"] or {}).get("n"),
                        "baseline_mean": (x["baseline"] or {}).get("mean"),
                        "window_n": (x["window"] or {}).get("n"),
                        "window_mean": (x["window"] or {}).get("mean"),
                        "delta_mean": x["deltaMean"],
                        "ratio_mean": x["ratioMean"],
                        "note": f.get("note") or "",
                    })

        if not has_series:
            monitoring_missing.append(history)

        counts = Counter(m for m in key_order if m)
        fcl = [(k, v) for k, v in counts.most_common()]
        inject = parse_fault_verify(row.get("fault_verify"))
        monitor_verify = row.get("monitor_verify") or ""
        records.append({
            "plan": plan_no(history),
            "history": history,
            "title": title,
            "href": f"{history}/report.html",
            "fault_keys": sorted(counts),
            "fault_counts": fcl,
            "fault_names": [FAULT_SHORT.get(k, k) for k in sorted(counts)],
            "inject_counts": inject,
            "monitor_verify": monitor_verify,
            "monitor_evidence": row.get("monitor_evidence") or "",
            "metrics": metrics,
            "available_metrics": available_metrics,
            "missing_metrics": missing_metrics,
            "monitor_warnings": series_json.get("warnings") or [],
            "stability": row.get("stability") or "",
            "defence": row.get("defence") or "",
            "recovery": row.get("recovery") or "",
            "rto": row.get("rto") or "",
            "outage": row.get("outage_seconds") or "",
            "state": row.get("state") or "",
        })
        fault_counts.update(counts)
        monitor_counts[monitor_verify or "未采集"] += 1
        inject_counts.update(inject)
        total_faults += len(key_order)
        print(f"  [{idx}/{len(kept)}] {plan_no(history)}  "
              f"{'监控 OK' if has_series else '监控缺失'}  "
              f"{' + '.join(FAULT_SHORT.get(k, k) for k in key_order)}")

    records.sort(key=lambda r: int(r["plan"]) if r["plan"].isdigit() else 0)
    stop_tests = sum(1 for r in records
                     if "faultinject.fault.process.stop" in r["fault_keys"])
    move_tests = sum(1 for r in records
                     if "faultinject.fault.disk.filemove" in r["fault_keys"])
    if records and not args.dry_run:
        totals = {
            "process_stop_tests": stop_tests,
            "file_move_tests": move_tests,
            "main": len(all_rows),
            "archived": len(records),
            "excluded_process_stop": len(excluded),
            "monitoring_available": len(records) - len(monitoring_missing),
            "monitoring_missing": len(monitoring_missing),
            "faults_archived": total_faults,
            "source_batch": batch_csv.name,
            "include_process_stop": bool(args.include_process_stop),
            "label": args.label,
        }
        generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        metric_mapping = [(k, v) for k, v in FAULT_METRICS.items()]
        index_html = render_index(target, records, excluded, fault_counts,
                                  inject_counts, monitor_counts, totals,
                                  metric_mapping, metric_label_by_id, generated_at)
        (target / "index.html").write_text(index_html, encoding="utf-8")

        manifest = {
            "generatedAt": generated_at,
            "sourceBatchCsv": str(batch_csv),
            "totals": totals,
            "excludedProcessStop": [
                {"plan": plan_no(r["history_name"]), "historyName": r["history_name"],
                 "faults": r.get("faults") or ""}
                for r in excluded
            ],
            "faultMetricMapping": FAULT_METRICS,
            "runs": [{
                "plan": r["plan"], "historyName": r["history"],
                "faults": r["fault_counts"], "metrics": r["metrics"],
                "availableMetrics": r["available_metrics"],
                "missingMetrics": r["missing_metrics"],
                "monitorWarnings": r["monitor_warnings"],
                "monitorVerify": r["monitor_verify"],
                "report": r["href"],
            } for r in records],
        }
        (target / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

        with open(target / "excluded-process-stop.csv", "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f)
            w.writerow(["plan", "history_name", "faults"])
            for r in excluded:
                w.writerow([plan_no(r["history_name"]), r["history_name"],
                            r.get("faults") or ""])

        if fault_csv_rows:
            with open(target / "fault-metrics.csv", "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, fieldnames=list(fault_csv_rows[0].keys()))
                w.writeheader()
                w.writerows(fault_csv_rows)

        mapping_lines = "\n".join(
            f"- `{k}` → {FAULT_SHORT.get(k, k)}：{', '.join(v)}"
            for k, v in FAULT_METRICS.items()
        )
        excluded_csv_line = (
            f"`excluded-process-stop.csv` 是 {len(excluded)} 个被排除的测试；\n"
            if excluded else "")
        scope_line = (
            f"- 批次测试：{len(all_rows)} 个（补测批次，全部归档；含 Process Stop "
            f"{stop_tests} 个、File Move {move_tests} 个）"
            if args.include_process_stop else
            f"- 主批次测试：{len(all_rows)} 个（不含 File Move 的 225 个计划）")
        excluded_line = (
            f"- 未归档：{len(excluded)} 个（含 Process Stop；本轮的 Process Stop 没有真正注入）"
            if excluded else "- 未归档：0 个")
        missing_line = (
            f"- 有 {len(monitoring_missing)} 个测试缺少监控曲线（`series.json` 不存在或为空），"
            f"报告里标「未采集」。\n" if monitoring_missing else "")
        stop_line = ("Process Stop 已包含在归档中，判据是 `host.process.stopped`（宿主机 T 状态进程数）。"
                     if args.include_process_stop else "Process Stop 已按要求整体排除。")

        readme = f"""# {args.label}

- 来源批次：`{batch_csv}`
{scope_line}
- 已归档：{len(records)} 个
{excluded_line}
- 有监控曲线：{len(records) - len(monitoring_missing)} 个；监控缺失：{len(monitoring_missing)} 个
- 归档故障注入：{total_faults} 次

## 目录结构

每个测试目录 = 引擎完整产物 + `monitoring/series.json` + `monitoring/session.json`
+ `report.html`（该测试所含故障对应指标的并集）+ `fault-metrics.json`
（每个故障窗口的基线/窗口统计）。

`index.html` 是组级可视化索引；`manifest.json` 是机器可读清单；
{excluded_csv_line}`fault-metrics.csv` 把每个故障 × 指标的基线/窗口统计摊平成一张表。

## 数据质量

{missing_line}- 部分测试的 `container.cpu.usage` / `container.memory.usage` / 连接类指标查询失败
  （`series.json` 里有指标定义但采样点为空）。报告只画有数据的曲线，
  索引「报告指标」列用红色「缺」标出未采到的指标。
- 文件权限 000 与文件移动没有专用计数器（见下），不要把磁盘使用率当成它们的判据。

## 监控指标

{mapping_lines}

> 文件权限 000 与文件移动没有专用计数器：报告里的磁盘使用率只作资源背景，主证据是注入回执 + TPS/延迟。
> {stop_line}
"""
        (target / "README.md").write_text(readme, encoding="utf-8")

    print()
    print(f"完成：归档 {len(records)} 个测试；排除 process.stop {len(excluded)} 个；"
          f"监控缺失 {len(monitoring_missing)} 个")
    if not args.dry_run:
        print(f"索引：{target / 'index.html'}")
        print(f"清单：{target / 'manifest.json'}")


if __name__ == "__main__":
    main()
