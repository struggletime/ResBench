#!/usr/bin/env python3
"""从 实验数据/<组>/<runId>/ 的归档里复算故障窗口统计。

输入：一个 run 目录（里面有 faultInject.json / metric.json / monitoring/series.json）
输出：每条注入的窗口、可用指标、窗口均值/峰值，以及全轮基线。

用法：
  python3 -I scripts/fault_table_stats.py 实验数据/mysql-tpcc/<runId>
"""
import json
import os
import statistics
import sys

CLEAN_BASELINE_SEC = 180

# 需要重点看的曲线；db.sessions 按 state label 拆开
WANTED = [
    "container.cpu.usage",
    "container.memory.usage",
    "net.qdisc.drop.percent",
    "net.ping.loss",
    "net.ping.rtt",
    "net.qdisc.reorder.rate",
    "net.tcp.dsack.rate",
    "net.tcp.rto.rate",
    "net.tcp.lost_retrans.rate",
    "db.connections.usage",
    "db.sessions",
    "db.rollback.rate",
    "host.process.stopped",
    "host.disk.usage",
]


def load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def window_stats(points, start_sec, end_sec):
    vals = [p[1] for p in points if p[1] is not None and start_sec <= p[0] / 1000.0 < end_sec]
    if not vals:
        return None
    return {
        "n": len(vals),
        "mean": statistics.fmean(vals),
        "max": max(vals),
        "min": min(vals),
    }


def fmt(v):
    if v is None:
        return "-"
    a = abs(v)
    if a >= 1000:
        return f"{v:.0f}"
    if a >= 100:
        return f"{v:.1f}"
    if a >= 10:
        return f"{v:.2f}"
    return f"{v:.3f}"


def main(run_dir):
    inj = load_json(os.path.join(run_dir, "faultInject.json"))
    stages_path = os.path.join(run_dir, "metric.json")
    stages = load_json(stages_path) if os.path.exists(stages_path) else []

    print("run:", os.path.basename(run_dir))
    print("fault:", inj[0].get("faultName"), "| injections:", len(inj))
    for i, x in enumerate(inj, 1):
        print(f"  batch{i}: {x['startTimeFormat']} +{x['faultDuration']}s cmd={x['injectionCMD'][:120]}")
        v = x.get("verification") or {}
        print(f"           verify={v.get('status')} err={str(v.get('error'))[:100]}")

    series_path = os.path.join(run_dir, "monitoring", "series.json")
    if not os.path.exists(series_path):
        print("no monitoring/series.json")
        return
    series = load_json(series_path).get("series", [])

    first_start = inj[0]["startTime"] / 1000.0
    base_lo = first_start - CLEAN_BASELINE_SEC

    windows = []
    for x in inj:
        s = x["startTime"] / 1000.0
        e = s + x["faultDuration"]
        windows.append((s, e))

    print()
    print(f"基线: 首次注入前 {CLEAN_BASELINE_SEC}s / 60s / 30s")
    print(f"{'metric':28s} {'labels':22s} {'b180':>9s} {'b60':>9s} {'b30':>9s} " +
          " ".join(f"{'w%d mean' % (i+1):>10s} {'w%d max' % (i+1):>9s}" for i in range(len(windows))))
    for s in series:
        mid = s.get("metricId")
        if mid not in WANTED:
            continue
        pts = s.get("points") or []
        if not pts:
            print(f"{mid:28s} {str(s.get('labels'))[:22]:22s}  (no points, status={s.get('dataStatus')})")
            continue
        b180 = window_stats(pts, base_lo, first_start)
        b60 = window_stats(pts, first_start - 60, first_start)
        b30 = window_stats(pts, first_start - 30, first_start)
        row = (f"{mid:28s} {str(s.get('labels'))[:22]:22s} "
               f"{fmt(b180['mean']) if b180 else '-':>9s} "
               f"{fmt(b60['mean']) if b60 else '-':>9s} "
               f"{fmt(b30['mean']) if b30 else '-':>9s} ")
        for (ws, we) in windows:
            st = window_stats(pts, ws, we)
            row += f"{fmt(st['mean']) if st else '-':>10s} {fmt(st['max']) if st else '-':>9s} "
        print(row)

    if stages:
        print()
        print("metric.json（引擎口径，逐批）:")
        for i, st in enumerate(stages, 1):
            print(f"  batch{i}: outage={st.get('outage_seconds')}s "
                  f"t_start_tps={fmt(st.get('t_start_tps'))} t_min_tps={fmt(st.get('t_min_tps'))} "
                  f"thr_rate={fmt(st.get('throughput_rate'))} latency={fmt(st.get('latency'))}ms")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    main(sys.argv[1])
