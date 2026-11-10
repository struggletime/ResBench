#!/usr/bin/env python3
"""离线复算批量结果的监控判据。

旧版 evaluate_effect 用「提交时刻 + 计划偏移」定位注入窗口，而计划偏移是相对负载
开始的；二者相差约 70 秒，判据整体落在负载爬坡段。这里改用归档里的
<runId>.faults.json（真实 startTime/endTime）重算，不重新跑压测。

默认只打印差异，不改任何文件；确认后再加 --write 写回 CSV 与 .monitor.json。

用法：
    python3 scripts/recheck_monitor.py batch-results/<批次目录>
    python3 scripts/recheck_monitor.py batch-results/<批次目录> --write
"""
import argparse
import csv
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import resbench_batch as rb  # noqa: E402


def planned_faults(row):
    """CSV 的 faults 列 -> [nameKey, ...]，保持编排顺序。"""
    names = []
    for part in (row.get("faults") or "").split(";"):
        part = part.strip()
        if part:
            names.append(part.split("[", 1)[0].strip())
    return names


def build_stages(names, items):
    """按 faultDelay 把注入记录分批，并和编排里的故障名一一对应。

    引擎按计划顺序写 faultInject.json，所以记录下标与 CSV 里的故障顺序一致；
    数量不一致时返回 None，交给调用方跳过（不猜）。
    """
    if not names or len(names) != len(items):
        return None
    delays = {}
    for index, item in enumerate(items):
        delay = rb.parse_int(item.get("faultDelay"))
        if delay is None:
            return None
        delays.setdefault(delay, []).append(index)

    stages = []
    for delay in sorted(delays):
        slots = delays[delay]
        duration = [rb.parse_int(items[i].get("faultDuration")) or 0 for i in slots]
        stages.append({
            "base": delay,
            "end": delay + max(duration),
            "faults": [{"nameKey": names[i], "params": {}} for i in slots],
        })
    return stages


def main():
    parser = argparse.ArgumentParser(description="用真实注入时刻复算批量监控判据")
    parser.add_argument("batch_dir", help="批次目录（含 runs-*.csv 和每轮子目录）")
    parser.add_argument("--write", action="store_true",
                        help="写回 CSV 的 monitor_verify/monitor_evidence 和 .monitor.json")
    args = parser.parse_args()

    csv_files = sorted(glob.glob(os.path.join(args.batch_dir, "runs-*.csv")))
    if len(csv_files) != 1:
        sys.exit("批次目录里应当恰好有一份 runs-*.csv，实际：%s" % csv_files)
    csv_path = csv_files[0]
    with open(csv_path, encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames
        rows = list(reader)

    changed = 0
    skipped = 0
    for row in rows:
        run_id = row.get("history_name")
        if not run_id:
            continue
        monitor_paths = glob.glob(os.path.join(args.batch_dir, "*", run_id + ".monitor.json"))
        faults_paths = glob.glob(os.path.join(args.batch_dir, "*", run_id + ".faults.json"))
        if not monitor_paths or not faults_paths:
            skipped += 1
            continue
        with open(monitor_paths[0], encoding="utf-8") as handle:
            monitor = json.load(handle)
        with open(faults_paths[0], encoding="utf-8") as handle:
            items = json.load(handle)
        stages = monitor.get("stages") or build_stages(planned_faults(row), items)
        if not stages:
            print("! %s：注入记录与编排故障对不上，跳过" % row.get("orchestration", run_id))
            skipped += 1
            continue

        verdict, evidence, detail = rb.evaluate_effect(
            monitor.get("series"), stages, monitor["startMs"], items)
        old = monitor.get("verdict") or row.get("monitor_verify") or "-"
        if old == verdict and row.get("monitor_evidence") == evidence:
            continue

        changed += 1
        print("%-28s %-13s -> %-13s 窗口=%s" % (
            row.get("orchestration", run_id), old, verdict, detail.get("window_mode")))
        print("    %s" % evidence[:200])
        if args.write:
            row["monitor_verify"] = verdict
            row["monitor_evidence"] = evidence
            monitor.update({"verdict": verdict, "evidence": evidence,
                            "detail": detail, "stages": stages})
            with open(monitor_paths[0], "w", encoding="utf-8") as handle:
                json.dump(monitor, handle, ensure_ascii=False, indent=2)

    if args.write and changed:
        with open(csv_path, "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    print("共 %d 轮：判据变化 %d 轮，跳过 %d 轮%s" % (
        len(rows), changed, skipped, "" if args.write else "（只读，加 --write 才写回）"))


if __name__ == "__main__":
    main()
