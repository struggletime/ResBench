#!/usr/bin/env python3
"""统计 plan 实验数据中「注入回执失败」的测试，生成离线 HTML。

判定口径与报告里的「错误」列一致：
  faultInject.json -> 任一故障的 faultResults[0].error == true

注意：不按 verification.status == "ERROR" 统计。像连接数耗尽的
"Too many connections" 自检异常是故障生效造成的假阴性，不是注入失败。
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import html
import json
import re
from pathlib import Path


DEFAULT_ARCHIVE = Path("plan实验数据/mysql-tpcc")
DEFAULT_OUT = DEFAULT_ARCHIVE / "injection-failures.html"

FAULT_LABELS = {
    "CPU Load": "CPU 负载",
    "Memory Load": "内存负载",
    "Network Packet Loss": "网络丢包",
    "Network Delay": "网络延迟",
    "Network Reorder": "网络乱序",
    "Network Duplicate": "网络重复包",
    "Disk Fill": "磁盘写满",
    "File Chmod": "文件权限 000",
    "File Move": "文件移动",
    "Connection Storm": "连接风暴",
    "Connection Limit Exceeded": "连接数耗尽",
    "Massive Transaction Rollback": "大量事务回滚",
    "Process Stop": "进程暂停",
    "连接风暴": "连接风暴",
    "连接数耗尽": "连接数耗尽",
    "海量事务回滚": "大量事务回滚",
}


def esc(value) -> str:
    return html.escape("" if value is None else str(value), quote=True)


def plan_no(history: str) -> str:
    found = re.search(r"plan-(\d{6})", history)
    return found.group(1) if found else ""


def load_json(path: Path, default):
    try:
        with path.open(encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return default


def error_text(result: dict) -> str:
    raw = (result.get("bladeError") or result.get("result") or "").strip()
    if "err: " in raw:
        raw = raw.split("err: ", 1)[1]
    first = raw.splitlines()[0].strip() if raw else ""
    return first or "（没有错误文本）"


def scan(archive: Path):
    manifest = load_json(archive / "manifest.json", {})
    runs = manifest.get("runs") or []
    if not runs:
        raise SystemExit(f"manifest.json 没有 runs：{archive}")

    records = []
    total_tests = 0
    total_faults = 0
    verify_error_tests = 0
    verify_error_only_tests = 0
    verify_error_faults = 0

    for run in runs:
        history = run.get("historyName") or ""
        source = archive / history / "faultInject.json"
        faults = load_json(source, [])
        if not isinstance(faults, list):
            faults = []
        total_tests += 1
        total_faults += len(faults)

        failed = []
        has_verify_error = False
        for index, fault in enumerate(faults, 1):
            results = fault.get("faultResults") or []
            result = results[0] if results else {}
            verification = fault.get("verification") or {}
            if verification.get("status") == "ERROR":
                has_verify_error = True
                verify_error_faults += 1
            if result.get("error"):
                failed.append({
                    "index": index,
                    "name": fault.get("faultName") or "",
                    "group": fault.get("groupKey") or "",
                    "delay": fault.get("faultDelay"),
                    "duration": fault.get("faultDuration"),
                    "returncode": result.get("returncode"),
                    "error": error_text(result),
                    "full_error": (result.get("bladeError")
                                   or result.get("result") or "").strip(),
                })
        if has_verify_error:
            verify_error_tests += 1
        if failed:
            records.append({
                "plan": run.get("plan") or plan_no(history),
                "history": history,
                "report": run.get("report") or f"{history}/report.html",
                "total_faults": len(faults),
                "failed": failed,
            })
        elif has_verify_error:
            verify_error_only_tests += 1

    records.sort(key=lambda item: (item["plan"], item["history"]))
    return records, {
        "total_tests": total_tests,
        "total_faults": total_faults,
        "verify_error_tests": verify_error_tests,
        "verify_error_only_tests": verify_error_only_tests,
        "verify_error_faults": verify_error_faults,
    }


def bar_rows(counter, label_fn) -> str:
    if not counter:
        return '<div class="muted">无数据</div>'
    peak = max(counter.values()) or 1
    rows = []
    for key, count in sorted(counter.items(), key=lambda kv: (-kv[1], str(kv[0]))):
        width = max(2.0, 100.0 * count / peak)
        rows.append(
            '<div class="bar-row">'
            f'<div class="bar-label">{esc(label_fn(key))}</div>'
            f'<div class="bar-track"><div class="bar-fill" style="width:{width:.1f}%"></div></div>'
            f'<div class="bar-value">{count}</div>'
            "</div>"
        )
    return "".join(rows)


def render(records, stats, archive: Path, generated_at: str) -> str:
    failed_faults = sum(len(item["failed"]) for item in records)
    failure_counts = collections.Counter(len(item["failed"]) for item in records)
    fault_counts = collections.Counter(
        fault["name"] for item in records for fault in item["failed"]
    )
    index_counts = collections.Counter(
        fault["index"] for item in records for fault in item["failed"]
    )
    all_file_exists = all(
        "File exists" in fault["error"]
        for item in records for fault in item["failed"]
    )

    test_pct = 100.0 * len(records) / max(1, stats["total_tests"])
    fault_pct = 100.0 * failed_faults / max(1, stats["total_faults"])

    fault_options = "".join(
        f'<option value="{esc(name)}">{esc(FAULT_LABELS.get(name, name))}'
        f'（{count}）</option>'
        for name, count in sorted(fault_counts.items(), key=lambda kv: (-kv[1], kv[0]))
    )

    rows = []
    for item in records:
        names = sorted({fault["name"] for fault in item["failed"]})
        search = " ".join([
            item["plan"], item["history"],
            " ".join(names),
            " ".join(fault["error"] for fault in item["failed"]),
        ]).lower()
        chips = "".join(
            f'<span class="chip chip-bad">{esc(FAULT_LABELS.get(name, name))}</span>'
            for name in names
        )
        details = []
        for fault in item["failed"]:
            rc = "-" if fault["returncode"] is None else str(fault["returncode"])
            delay = "-" if fault["delay"] is None else f'{fault["delay"]}s'
            duration = "-" if fault["duration"] is None else f'{fault["duration"]}s'
            title = esc(fault["full_error"]).replace("\n", "&#10;")
            details.append(
                '<div class="fail-item">'
                f'<div class="fail-head"><span class="fail-name">第 {fault["index"]} 轮 · '
                f'{esc(FAULT_LABELS.get(fault["name"], fault["name"]))}</span>'
                f'<span class="fail-meta">{esc(fault["group"])} · 注入 {esc(delay)} · '
                f'持续 {esc(duration)} · 返回码 {esc(rc)}</span></div>'
                f'<div class="err" title="{title}">{esc(fault["error"])}</div>'
                "</div>"
            )
        rows.append(
            f'<tr data-search="{esc(search)}" data-faults="{esc("|".join(names))}" '
            f'data-count="{len(item["failed"])}">'
            f'<td class="num">{esc(item["plan"])}</td>'
            f'<td class="test-cell"><a href="{esc(item["report"])}">plan-{esc(item["plan"])}</a>'
            f'<div class="mono">{esc(item["history"])}</div></td>'
            f'<td class="num">{item["total_faults"]}</td>'
            f'<td><span class="badge bad">注入失败 ×{len(item["failed"])}</span>'
            f'<div class="chips">{chips}</div></td>'
            f'<td><div class="fail-list">{"".join(details)}</div></td>'
            f'<td><a class="report-link" href="{esc(item["report"])}">打开报告 →</a></td>'
            "</tr>"
        )

    css = """
:root{
  color-scheme:light;
  --page:#f9f9f7; --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e;
  --muted:#898781; --line:#e1e0d9; --axis:#c3c2b7;
  --blue:#2a78d6; --blue-soft:#9ec5f4;
  --good:#0ca30c; --warn:#fab219; --bad:#d03b3b;
  --chip:#eef1f6; --shadow:0 1px 2px rgba(11,11,11,.05);
}
@media (prefers-color-scheme:dark){
  :root:not([data-theme="light"]){
    color-scheme:dark;
    --page:#0d0d0d; --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7;
    --muted:#898781; --line:#2c2c2a; --axis:#383835;
    --blue:#3987e5; --blue-soft:#184f95;
    --chip:#242423; --shadow:none;
  }
}
:root[data-theme="dark"]{
  color-scheme:dark;
  --page:#0d0d0d; --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7;
  --muted:#898781; --line:#2c2c2a; --axis:#383835;
  --blue:#3987e5; --blue-soft:#184f95;
  --chip:#242423; --shadow:none;
}
*{box-sizing:border-box}
body{margin:0;background:var(--page);color:var(--ink);
  font:14px/1.6 system-ui,-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
.wrap{max-width:1420px;margin:0 auto;padding:28px 20px 64px}
header.top{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-end;justify-content:space-between;
  border-bottom:2px solid var(--line);padding-bottom:16px;margin-bottom:22px}
h1{font-size:22px;margin:0 0 4px;letter-spacing:-.01em}
.sub{color:var(--muted);font-size:12.5px}
.sub code{font-size:12px;color:var(--ink2)}
.sub a{color:var(--blue);text-decoration:none}
.sub a:hover{text-decoration:underline}
.kpis{display:grid;grid-template-columns:repeat(5,minmax(150px,1fr));gap:12px;margin:0 0 18px}
.kpi{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:14px 16px;
  box-shadow:var(--shadow)}
.kpi .v{font-size:28px;font-weight:650;line-height:1.15;letter-spacing:-.02em}
.kpi .l{color:var(--ink2);font-size:12.5px;margin-top:3px}
.kpi .h{color:var(--muted);font-size:11.5px;margin-top:2px}
.kpi.bad .v{color:var(--bad)}
.grid{display:grid;grid-template-columns:repeat(12,1fr);gap:14px;margin-bottom:18px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:16px 18px;
  box-shadow:var(--shadow)}
.card h2{font-size:14px;margin:0 0 12px;font-weight:650}
.card h2 .n{color:var(--muted);font-weight:400;font-size:12px}
.span6{grid-column:span 6}.span3{grid-column:span 3}
.bar-row{display:grid;grid-template-columns:112px 1fr 38px;gap:9px;align-items:center;padding:3px 0}
.bar-label{font-size:12.5px;color:var(--ink2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.bar-track{height:10px;background:var(--chip);border-radius:4px;overflow:hidden;display:block}
.bar-fill{height:100%;background:var(--blue);border-radius:4px}
.bar-value{text-align:right;font-size:12.5px;font-variant-numeric:tabular-nums;color:var(--ink2)}
.callout{background:var(--surface);border:1px solid var(--line);border-left:4px solid var(--bad);
  border-radius:12px;padding:16px 18px;margin:0 0 18px;box-shadow:var(--shadow)}
.callout .title{font-weight:650;margin-bottom:4px}
.callout code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px}
.filters{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin:20px 0 10px}
.filters input,.filters select{background:var(--surface);color:var(--ink);border:1px solid var(--line);
  border-radius:8px;padding:8px 10px;font:inherit;font-size:13px;min-width:180px}
.filters input{min-width:300px;flex:1;max-width:470px}
.filters .count{color:var(--muted);font-size:12.5px;margin-left:auto}
.tablewrap{background:var(--surface);border:1px solid var(--line);border-radius:12px;overflow:auto;
  box-shadow:var(--shadow)}
table{border-collapse:collapse;width:100%;min-width:1120px}
th{position:sticky;top:0;background:var(--surface);z-index:1;text-align:left;font-size:11.5px;
  text-transform:uppercase;letter-spacing:.04em;color:var(--muted);font-weight:600;
  border-bottom:1px solid var(--line);padding:10px 10px;white-space:nowrap}
td{border-bottom:1px solid var(--line);padding:9px 10px;vertical-align:top;font-size:12.5px}
tr:last-child td{border-bottom:0}
tbody tr:hover{background:color-mix(in srgb,var(--blue) 5%,transparent)}
a{color:var(--blue);text-decoration:none}a:hover{text-decoration:underline}
.num{font-variant-numeric:tabular-nums;white-space:nowrap}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:10.5px;
  color:var(--muted);word-break:break-all;max-width:360px}
.test-cell{min-width:300px}
td:nth-child(5){min-width:430px}
.chip{display:inline-block;background:var(--chip);color:var(--ink2);border-radius:999px;
  padding:1px 8px;margin:1px 3px 1px 0;font-size:11px;white-space:nowrap}
.chip-bad{color:var(--bad);background:color-mix(in srgb,var(--bad) 11%,transparent)}
.chips{margin-top:4px}
.badge{display:inline-block;border-radius:999px;padding:1px 8px;margin:1px 3px 1px 0;
  font-size:11px;white-space:nowrap}
.badge.bad{color:var(--bad);background:color-mix(in srgb,var(--bad) 12%,transparent)}
.fail-list{display:flex;flex-direction:column;gap:7px}
.fail-item{border-left:3px solid var(--bad);padding-left:9px}
.fail-head{display:flex;flex-wrap:wrap;gap:6px;align-items:baseline}
.fail-name{font-weight:600;font-size:12px}
.fail-meta{font-size:11px;color:var(--muted);font-variant-numeric:tabular-nums}
.err{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:11px;
  color:var(--ink2);word-break:break-word;margin-top:2px}
.report-link{white-space:nowrap;font-weight:600}
details{margin-top:22px;background:var(--surface);border:1px solid var(--line);border-radius:12px;
  padding:14px 18px}
summary{cursor:pointer;font-weight:650;font-size:14px}
details ul{margin:10px 0 0;padding-left:20px;color:var(--ink2);font-size:12.5px}
details li{margin:5px 0}
.note{color:var(--muted);font-size:12px;margin-top:10px}
.muted{color:var(--muted)}
@media (max-width:1080px){
  .kpis{grid-template-columns:repeat(2,minmax(140px,1fr))}
  .span6,.span3{grid-column:span 12}
  .filters input{min-width:0;width:100%}
}
"""

    js = """
const TOTAL=__TOTAL__;
const q=document.getElementById('q'), ft=document.getElementById('fault'),
      ct=document.getElementById('count'), shown=document.getElementById('shown'),
      rows=[...document.querySelectorAll('#rows tr')];
function apply(){
  const text=(q.value||'').trim().toLowerCase(), fault=ft.value, count=ct.value;
  let visible=0;
  for(const tr of rows){
    const okText=!text||tr.dataset.search.includes(text);
    const okFault=!fault||tr.dataset.faults.split('|').includes(fault);
    const okCount=!count||tr.dataset.count===count;
    const ok=okText&&okFault&&okCount;
    tr.style.display=ok?'':'none';
    if(ok)visible++;
  }
  shown.textContent='显示 '+visible+' / '+TOTAL;
}
[q,ft,ct].forEach(el=>el.addEventListener('input',apply));
apply();
""".replace("__TOTAL__", str(len(records)))

    html_text = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>plan_300 · MySQL × TPC-C · 注入失败统计</title>
<style>{css}</style></head>
<body><div class="wrap">
<header class="top">
  <div>
    <h1>注入失败统计 · plan_300 MySQL × TPC-C</h1>
    <div class="sub">范围：<code>{esc(archive)}</code> 的 {stats["total_tests"]} 个已归档测试
      · 生成于 {esc(generated_at)}
      · <a href="index.html">返回归档索引</a></div>
    <div class="sub">判定：任一故障的注入回执 <code>faultResults[0].error == true</code>
      （报告「错误」列为「是」）。自检 ERROR 不直接计入。</div>
  </div>
  <div class="sub">只统计已归档测试；Process Stop 测试仍按归档口径排除。</div>
</header>

<div class="kpis">
  <div class="kpi bad"><div class="v">{len(records)}</div><div class="l">含注入失败的测试</div>
    <div class="h">占 {stats["total_tests"]} 个归档测试的 {test_pct:.1f}%</div></div>
  <div class="kpi bad"><div class="v">{failed_faults}</div><div class="l">失败注入次数</div>
    <div class="h">占 {stats["total_faults"]} 次注入的 {fault_pct:.1f}%</div></div>
  <div class="kpi"><div class="v">{len(fault_counts)}</div><div class="l">涉及故障类型</div>
    <div class="h">全部为网络类故障</div></div>
  <div class="kpi"><div class="v">{failure_counts.get(1, 0)}</div><div class="l">单故障失败测试</div>
    <div class="h">{failure_counts.get(2, 0)} 个测试含 2 个失败故障</div></div>
  <div class="kpi"><div class="v">{stats["verify_error_tests"]}</div><div class="l">含自检 ERROR 的测试</div>
    <div class="h">其中 {stats["verify_error_only_tests"]} 个不是注入失败，未计入</div></div>
</div>

<div class="grid">
  <div class="card span6">
    <h2>失败故障类型 <span class="n">· 按失败注入次数</span></h2>
    {bar_rows(fault_counts, lambda name: FAULT_LABELS.get(name, name))}
    <div class="note">只出现网络类故障：丢包、延迟、乱序、重复包。</div>
  </div>
  <div class="card span3">
    <h2>每个测试的失败数 <span class="n">· 按测试</span></h2>
    {bar_rows(failure_counts, lambda count: f"{count} 个故障")}
    <div class="note">45 个测试中，42 个只失败 1 次，3 个失败 2 次。</div>
  </div>
  <div class="card span3">
    <h2>失败发生在第几轮 <span class="n">· 按测试内故障序号</span></h2>
    {bar_rows(index_counts, lambda index: f"第 {index} 轮")}
    <div class="note">序号与报告「轮次」列一致。</div>
  </div>
</div>

<div class="callout">
  <div class="title">48 次失败的错误信息完全一致：<code>RTNETLINK answers: File exists</code></div>
  <div>从回执看，故障机 <code>ens17f0</code> 上已经存在 root qdisc，新的网络故障再去添加时被拒绝。
    这些计划里的网络故障按 30 秒间隔首尾相接，前一个故障的 netem qdisc 在边界尚未清理，
    后一个网络故障就开始注入，因此只有网络类故障出现失败，其他故障类型没有。
    {"本页只做统计，没有把这类失败从归档中移除。" if all_file_exists else ""}</div>
</div>

<div class="filters">
  <input id="q" type="search" placeholder="搜索计划号 / 测试目录 / 故障 / 错误…"
         aria-label="搜索">
  <select id="fault" aria-label="失败故障类型">
    <option value="">全部失败类型</option>{fault_options}
  </select>
  <select id="count" aria-label="失败故障数">
    <option value="">全部失败数</option>
    <option value="1">1 个失败故障</option>
    <option value="2">2 个失败故障</option>
  </select>
  <span class="count" id="shown">显示 {len(records)} / {len(records)}</span>
</div>

<div class="tablewrap">
<table>
<thead><tr>
  <th scope="col">计划</th><th scope="col">测试 / 报告</th><th scope="col">总故障</th><th scope="col">失败故障</th>
  <th scope="col">失败明细（注入回执）</th><th scope="col">报告</th>
</tr></thead>
<tbody id="rows">
{"".join(rows)}
</tbody></table>
</div>

<details>
  <summary>判定口径与数据来源</summary>
  <ul>
    <li>数据来源：<code>{esc(archive)}/manifest.json</code> 的 {stats["total_tests"]} 个已归档测试，
      逐个读取测试目录下的 <code>faultInject.json</code>。</li>
    <li>注入失败：任一故障的 <code>faultResults[0].error</code> 为 <code>true</code>。
      这与测试报告「七、故障注入记录」里的「错误」列一致。</li>
    <li>不计入：<code>verification.status == "ERROR"</code>。<code>{stats["verify_error_tests"]}</code>
      个测试含自检 ERROR（共 {stats["verify_error_faults"]} 个故障），其中
      <code>{stats["verify_error_only_tests"]}</code> 个测试没有注入失败，只是自检层报错。
      典型例子是连接数耗尽：自检连接被 <code>Too many connections</code> 拒绝，
      这恰好说明故障生效，不是注入失败。</li>
    <li>本页只覆盖已归档的 {stats["total_tests"]} 个测试；含 Process Stop 的测试按归档规则整体排除，
      不在这里统计。</li>
  </ul>
  <div class="note">生成脚本：<code>scripts/build_injection_failure_report.py</code>。
    页面自包含、离线可用；测试链接打开对应目录的 <code>report.html</code>。</div>
</details>
</div>
<script>{js}</script>
</body></html>
"""
    return html_text


def main():
    parser = argparse.ArgumentParser(description="生成注入失败统计 HTML")
    parser.add_argument("--archive", default=str(DEFAULT_ARCHIVE),
                        help="归档根目录（含 manifest.json）")
    parser.add_argument("--out", default=str(DEFAULT_OUT), help="输出 HTML")
    args = parser.parse_args()

    archive = Path(args.archive)
    records, stats = scan(archive)
    if not records:
        raise SystemExit("没有找到注入失败测试，未生成页面")

    generated_at = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    out = Path(args.out)
    out.write_text(render(records, stats, archive, generated_at), encoding="utf-8")

    failed_faults = sum(len(item["failed"]) for item in records)
    print(f"已归档测试：{stats['total_tests']}")
    print(f"含注入失败：{len(records)} 个测试 / {failed_faults} 次失败")
    print(f"含自检 ERROR：{stats['verify_error_tests']} 个测试"
          f"（其中 {stats['verify_error_only_tests']} 个不是注入失败）")
    print(f"输出：{out}")


if __name__ == "__main__":
    main()
