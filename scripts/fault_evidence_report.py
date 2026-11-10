#!/usr/bin/env python3
"""按故障家族汇总「监控指标能否证明注入」的离线证据。

数据来源（只读，不改任何文件）：
    backend/resbenchmark/results/<runId>/faultInject.json   注入窗口 + 是否成功
    backend/res-manager/data/monitoring/<runId>/series.json 归档指标
    backend/resbenchmark/results/<runId>/monitor.csv        TPS / p95

判定窗口：
    基线 [start - 180s, start - 10s)
    故障 [start + 20s, end)

用法：
    python3 scripts/fault_evidence_report.py                     # 全部历史 run
    python3 scripts/fault_evidence_report.py --date 20261023     # 只统计某天
    python3 scripts/fault_evidence_report.py --impact            # 同时打印业务影响
"""
import argparse
import csv
import json
import os
import re
import statistics as st
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "backend", "resbenchmark", "results")
SER = os.path.join(ROOT, "backend", "res-manager", "data", "monitoring")

FAMILY = re.compile(r"__(e\d\d[a-z]*-[a-z0-9-]+?)_\d{8}_")

METRICS = {
    "container.cpu.cores": "ctcores",
    "container.cpu.usage": "ctcpu%",
    "container.memory.usage": "ctmem%",
    "container.memory.working_set": "ctWSet",
    "host.netstat.retrans": "retrans",
    "host.netstat.reorder": "reorder",
    "host.process.stopped": "procT",
    "host.disk.usage": "diskUse",
    "host.disk.write": "hostWr",
    "container.disk.write": "ctWr",
    "db.connections.usage": "conn%",
    "db.rollback.rate": "rollback",
    "db.tps.agent": "dbTPS",
}

# 与 MonitoringCatalog 的 host.disk.usage 过滤保持一致：伪文件系统不是真实磁盘占用。
PSEUDO_FSTYPES = {
    "tmpfs", "devtmpfs", "overlay", "proc", "sysfs", "cgroup", "cgroup2", "debugfs",
    "tracefs", "securityfs", "pstore", "configfs", "fusectl", "mqueue", "hugetlbfs",
    "devpts", "rpc_pipefs", "binfmt_misc", "autofs", "squashfs", "ramfs", "nsfs",
}

BASE_LO, BASE_HI = 180_000, 10_000
WIN_LO = 20_000


def load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def window_mean(points, lo, hi):
    xs = [v for ts, v in points if lo <= ts < hi]
    return sum(xs) / len(xs) if xs else None


def series_map(run_id):
    data = load_json(os.path.join(SER, run_id, "series.json"))
    if not data:
        return None
    out = {}
    for s in data.get("series", []):
        mid = s.get("metricId")
        if mid not in METRICS:
            continue
        labels = s.get("labels") or {}
        if mid == "host.disk.usage" and labels.get("fstype") in PSEUDO_FSTYPES:
            continue
        mount = labels.get("mountpoint")
        key = mid if not mount else "%s[%s]" % (mid, mount)
        out.setdefault(key, []).extend(s.get("points", []))
    return out


def monitor_points(run_id):
    path = os.path.join(RES, run_id, "monitor.csv")
    out = []
    try:
        with open(path) as f:
            for row in csv.DictReader(f):
                try:
                    out.append((int(row["timestamp"]), float(row["tps"]),
                                float(row["latencyMs95th"])))
                except (KeyError, ValueError):
                    pass
    except OSError:
        return None
    return out


def successful_faults(run_id):
    data = load_json(os.path.join(RES, run_id, "faultInject.json"))
    if not isinstance(data, list):
        return []
    out = []
    for f in data:
        res = (f.get("faultResults") or [{}])[0]
        start, end = f.get("startTime"), f.get("endTime")
        if res.get("error") is not False or not start or not end:
            continue
        out.append((start, end))
    return out


def median_delta(pairs):
    rels = [((w / b) - 1) * 100 for b, w in pairs if b not in (0, None)]
    return (st.median([b for b, w in pairs]),
            st.median([w for b, w in pairs]),
            st.median(rels) if rels else None,
            (min(rels), max(rels)) if rels else None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", help="只统计 runId 里包含该日期的 run，例如 20261023")
    ap.add_argument("--impact", action="store_true", help="同时打印 TPS/p95 变化")
    args = ap.parse_args()

    if not os.path.isdir(RES):
        sys.exit("找不到结果目录: %s" % RES)

    metric_agg = {}
    impact_agg = {}
    family_runs = {}
    for run_id in sorted(os.listdir(RES)):
        if args.date and args.date not in run_id:
            continue
        m = FAMILY.search(run_id + "_")
        if not m:
            continue
        faults = successful_faults(run_id)
        if not faults:
            continue
        family = m.group(1)
        series = series_map(run_id)
        points = monitor_points(run_id) if args.impact else None
        for start, end in faults:
            family_runs.setdefault(family, set()).add(run_id)
            if series:
                for key, pts in series.items():
                    b = window_mean(pts, start - BASE_LO, start - BASE_HI)
                    w = window_mean(pts, start + WIN_LO, end)
                    if b is not None and w is not None:
                        metric_agg.setdefault(family, {}).setdefault(key, []).append((b, w))
            if points:
                bt = window_mean([(t, v) for t, v, _ in points], start - BASE_LO, start - BASE_HI)
                wt = window_mean([(t, v) for t, v, _ in points], start + WIN_LO, end)
                bl = window_mean([(t, v) for t, _, v in points], start - BASE_LO, start - BASE_HI)
                wl = window_mean([(t, v) for t, _, v in points], start + WIN_LO, end)
                if None not in (bt, wt, bl, wl):
                    impact_agg.setdefault(family, []).append((bt, wt, bl, wl))

    for family in sorted(metric_agg):
        print("\n## %s  runs=%d" % (family, len(family_runs[family])))
        for key, pairs in sorted(metric_agg[family].items()):
            b, w, med, rng = median_delta(pairs)
            name = METRICS.get(key, key)
            if med is None:
                print("   %-44s base=%11.4g fault=%11.4g (base 0) n=%d" % (name, b, w, len(pairs)))
            else:
                print("   %-44s base=%11.4g fault=%11.4g med=%+9.1f%% [%+.0f,%+.0f] n=%d"
                      % (name, b, w, med, rng[0], rng[1], len(pairs)))
        if args.impact and family in impact_agg:
            rows = impact_agg[family]
            bt = st.median([r[0] for r in rows])
            wt = st.median([r[1] for r in rows])
            bl = st.median([r[2] for r in rows])
            wl = st.median([r[3] for r in rows])
            print("   TPS %8.1f -> %8.1f (%+.0f%%)   p95 %8.1f -> %8.1f ms (%+.0f%%)  n=%d"
                  % (bt, wt, (wt / bt - 1) * 100 if bt else 0,
                     bl, wl, (wl / bl - 1) * 100 if bl else 0, len(rows)))


if __name__ == "__main__":
    main()
