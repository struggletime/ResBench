#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import csv
import datetime as dt
import json
import math
from pathlib import Path
from statistics import pstdev

import matplotlib.pyplot as plt


def find_latest_history_dir(results_root: Path):
  if not results_root.exists() or not results_root.is_dir():
    raise FileNotFoundError(f"results directory not found: {results_root}")

  candidates = [p for p in results_root.iterdir() if p.is_dir()]
  valid = [p for p in candidates if (p / "faultInject.json").exists() and (p / "monitor.csv").exists()]
  if not valid:
    raise FileNotFoundError(
      f"No history directory with both faultInject.json and monitor.csv under: {results_root}"
    )
  valid.sort(key=lambda p: p.stat().st_mtime, reverse=True)
  return valid[0]


def read_monitor_rows(monitor_csv: Path):
  rows = []
  with monitor_csv.open("r", encoding="utf-8") as f:
    reader = csv.DictReader(f)
    for row in reader:
      try:
        ts = int(float(row["timestamp"]))
        tps = float(row["tps"])
      except (KeyError, ValueError, TypeError):
        continue
      rows.append((ts, tps))
  return rows


def read_stability_rows(stability_csv: Path):
  rows = []
  if not stability_csv.exists():
    return rows
  with stability_csv.open("r", encoding="utf-8") as f:
    reader = csv.DictReader(f)
    for row in reader:
      try:
        ts = int(float(row["ts_ms"]))
        cv = float(row["cv"])
      except (KeyError, ValueError, TypeError):
        continue
      if math.isnan(cv) or math.isinf(cv):
        continue
      threshold = math.nan
      try:
        threshold = float(row.get("threshold", "nan"))
      except (ValueError, TypeError):
        threshold = math.nan
      rows.append((ts, cv, threshold))
  return rows


def normalize_fault_window(fault: dict):
  try:
    start = int(fault.get("startTime", 0) or 0)
  except (TypeError, ValueError):
    start = 0
  try:
    end = int(fault.get("endTime", 0) or 0)
  except (TypeError, ValueError):
    end = 0

  if end <= start:
    try:
      duration_sec = int(fault.get("faultDuration", 0) or 0)
    except (TypeError, ValueError):
      duration_sec = 0
    if start > 0 and duration_sec > 0:
      end = start + duration_sec * 1000

  return start, end


def calc_cv(values):
  if not values:
    return math.nan, math.nan, math.nan
  avg = sum(values) / len(values)
  if avg == 0:
    return avg, math.nan, math.nan
  std = pstdev(values) if len(values) > 1 else 0.0
  cv = std / avg
  return avg, std, cv


def summarize(faults, monitor_rows):
  out = []
  for fault in faults:
    start, end = normalize_fault_window(fault)
    if start <= 0 or end <= start:
      continue

    tps_values = [tps for ts, tps in monitor_rows if start <= ts <= end]
    avg, std, cv = calc_cv(tps_values)
    out.append(
      {
        "fault_id": str(fault.get("faultID", "")),
        "fault_name": str(fault.get("faultName", "unknown")),
        "start_time": start,
        "end_time": end,
        "sample_count": len(tps_values),
        "avg_tps": avg,
        "std_tps": std,
        "cv": cv,
      }
    )
  return out


def summarize_injection_cv_points(faults, stability_rows):
  out = []
  if not stability_rows:
    return out

  ts_only = [r[0] for r in stability_rows]
  for fault in faults:
    start, end = normalize_fault_window(fault)
    if start <= 0:
      continue
    nearest_idx = min(range(len(ts_only)), key=lambda i: abs(ts_only[i] - start))
    matched_ts, cv, _ = stability_rows[nearest_idx]
    out.append(
      {
        "fault_id": str(fault.get("faultID", "")),
        "fault_name": str(fault.get("faultName", "unknown")),
        "start_time": start,
        "end_time": end,
        "matched_ts": matched_ts,
        "delta_ms": abs(matched_ts - start),
        "cv_at_injection": cv,
      }
    )

  out.sort(key=lambda r: r["start_time"])
  return out


def write_csv(summary_rows, output_csv: Path):
  output_csv.parent.mkdir(parents=True, exist_ok=True)
  with output_csv.open("w", encoding="utf-8", newline="") as f:
    writer = csv.DictWriter(
      f,
      fieldnames=[
        "fault_id",
        "fault_name",
        "start_time",
        "end_time",
        "sample_count",
        "avg_tps",
        "std_tps",
        "cv",
      ],
    )
    writer.writeheader()
    for row in summary_rows:
      writer.writerow(row)


def write_timeline_csv(point_rows, output_csv: Path):
  output_csv.parent.mkdir(parents=True, exist_ok=True)
  with output_csv.open("w", encoding="utf-8", newline="") as f:
    writer = csv.DictWriter(
      f,
      fieldnames=[
        "fault_id",
        "fault_name",
        "start_time",
        "end_time",
        "matched_ts",
        "delta_ms",
        "cv_at_injection",
      ],
    )
    writer.writeheader()
    for row in point_rows:
      writer.writerow(row)


def draw_bar(summary_rows, output_png: Path, title: str):
  output_png.parent.mkdir(parents=True, exist_ok=True)
  valid_rows = [r for r in summary_rows if not math.isnan(r["cv"])]

  if not valid_rows:
    plt.figure(figsize=(10, 4))
    plt.title(title)
    plt.text(0.5, 0.5, "No valid injection interval data", ha="center", va="center")
    plt.axis("off")
    plt.tight_layout()
    plt.savefig(output_png, dpi=150)
    plt.close()
    return

  names = [r["fault_name"] for r in valid_rows]
  cvs = [r["cv"] for r in valid_rows]

  plt.figure(figsize=(12, 5))
  bars = plt.bar(range(len(names)), cvs)
  plt.xticks(range(len(names)), names, rotation=20, ha="right")
  plt.ylabel("CV during injection")
  plt.xlabel("Fault name")
  plt.title(title)
  plt.grid(axis="y", alpha=0.3)

  for bar, cv in zip(bars, cvs):
    plt.text(
      bar.get_x() + bar.get_width() / 2,
      bar.get_height(),
      f"{cv:.4f}",
      ha="center",
      va="bottom",
      fontsize=9,
    )

  plt.tight_layout()
  plt.savefig(output_png, dpi=150)
  plt.close()


def resolve_dynamic_threshold(stability_rows):
  for _, _, threshold in stability_rows:
    if not math.isnan(threshold) and not math.isinf(threshold):
      return threshold
  return math.nan


def draw_timeline(point_rows, output_png: Path, title: str, threshold=math.nan):
  output_png.parent.mkdir(parents=True, exist_ok=True)
  valid = [r for r in point_rows if not math.isnan(r["cv_at_injection"])]

  if not valid:
    plt.figure(figsize=(12, 4))
    plt.title(title)
    plt.text(0.5, 0.5, "No valid injection timestamp-CV pairs", ha="center", va="center")
    plt.axis("off")
    plt.tight_layout()
    plt.savefig(output_png, dpi=150)
    plt.close()
    return

  x = [dt.datetime.fromtimestamp(r["start_time"] / 1000.0) for r in valid]
  y = [r["cv_at_injection"] for r in valid]

  plt.figure(figsize=(13, 5))
  plt.plot(x, y, marker="o", linestyle="-", linewidth=1.2)
  plt.scatter(x, y, s=30)

  for xi, yi, row in zip(x, y, valid):
    inject_time = dt.datetime.fromtimestamp(row["start_time"] / 1000.0).strftime("%H:%M:%S")
    label = f"{row['fault_name']}\n{inject_time}, cv={row['cv_at_injection']:.4f}"
    plt.annotate(
      label,
      (xi, yi),
      textcoords="offset points",
      xytext=(0, 8),
      ha="center",
      fontsize=8,
    )

  if not math.isnan(threshold):
    plt.axhline(y=threshold, color="red", linestyle="--", linewidth=1.2,
                label=f"dynamic threshold={threshold:.4f}")
    plt.legend(loc="best")

  plt.ylabel("CV at injection timestamp")
  plt.xlabel("Injection time")
  plt.title(title)
  plt.grid(axis="y", alpha=0.3)
  plt.xticks(rotation=20, ha="right")
  plt.tight_layout()
  plt.savefig(output_png, dpi=150)
  plt.close()


def derive_prefix_from_history_dir(history_dir: Path):
  # Use exact folder name so timestamp aligns with input directory.
  return f"{history_dir.name}_injection_cv"


def main():
  parser = argparse.ArgumentParser(
    description=(
      "Compute CV from all TPS points within each injection interval, "
      "then plot fault-name vs CV"
    )
  )
  parser.add_argument(
    "--history-dir",
    default="",
    help="Path to a single results history directory (contains faultInject.json and monitor.csv)",
  )
  parser.add_argument(
    "--results-root",
    default="",
    help="Root directory that contains history folders",
  )
  parser.add_argument(
    "--fault-json",
    default="",
    help="Path to faultInject.json (optional; overrides history-dir auto discovery)",
  )
  parser.add_argument(
    "--monitor-csv",
    default="",
    help="Path to monitor.csv (optional; overrides history-dir auto discovery)",
  )
  parser.add_argument(
    "--stability-csv",
    default="",
    help="Path to stability-metrics.csv (optional; default: <history>/auto-timing/stability-metrics.csv)",
  )
  parser.add_argument(
    "--out-dir",
    default="",
    help="Output directory (default: same as selected history dir)",
  )
  parser.add_argument(
    "--prefix",
    default="",
    help="Output file prefix (default: <history-folder>_injection_cv)",
  )
  args = parser.parse_args()

  selected_history_dir = None
  fault_json = None
  monitor_csv = None
  stability_csv = None

  if args.fault_json and args.monitor_csv:
    fault_json = Path(args.fault_json).expanduser().resolve()
    monitor_csv = Path(args.monitor_csv).expanduser().resolve()
    selected_history_dir = fault_json.parent
  else:
    if args.history_dir:
      selected_history_dir = Path(args.history_dir).expanduser().resolve()
    else:
      if args.results_root:
        results_root = Path(args.results_root).expanduser().resolve()
      else:
        script_dir = Path(__file__).resolve().parent
        guess_roots = [
          script_dir / "results",
          script_dir / "resbenchmark" / "results",
          script_dir / "resbenchmark" / "resbenchmark-opengauss" / "results",
        ]
        existing = [p for p in guess_roots if p.exists() and p.is_dir()]
        if not existing:
          raise FileNotFoundError(
            "Cannot auto-detect results root. Please pass --results-root or --history-dir."
          )
        results_root = existing[0]
      selected_history_dir = find_latest_history_dir(results_root)

    fault_json = selected_history_dir / "faultInject.json"
    monitor_csv = selected_history_dir / "monitor.csv"

  if args.stability_csv:
    stability_csv = Path(args.stability_csv).expanduser().resolve()
  else:
    stability_csv = selected_history_dir / "auto-timing" / "stability-metrics.csv"

  out_dir = Path(args.out_dir).expanduser().resolve() if args.out_dir else selected_history_dir

  if not fault_json.exists():
    raise FileNotFoundError(f"fault json not found: {fault_json}")
  if not monitor_csv.exists():
    raise FileNotFoundError(f"monitor csv not found: {monitor_csv}")

  faults = json.loads(fault_json.read_text(encoding="utf-8"))
  monitor_rows = read_monitor_rows(monitor_csv)
  summary_rows = summarize(faults, monitor_rows)
  stability_rows = read_stability_rows(stability_csv)
  point_rows = summarize_injection_cv_points(faults, stability_rows)
  dynamic_threshold = resolve_dynamic_threshold(stability_rows)

  prefix = args.prefix.strip() if args.prefix else derive_prefix_from_history_dir(selected_history_dir)
  output_csv = out_dir / f"{prefix}_summary.csv"
  output_png = out_dir / f"{prefix}_bar.png"
  timeline_csv = out_dir / f"{prefix}_timeline.csv"
  timeline_png = out_dir / f"{prefix}_timeline.png"

  write_csv(summary_rows, output_csv)
  draw_bar(summary_rows, output_png, "CV During Injection by Fault")
  write_timeline_csv(point_rows, timeline_csv)
  draw_timeline(point_rows, timeline_png, "CV at Fault Injection Timestamps", dynamic_threshold)

  print(f"History dir: {selected_history_dir}")
  print(f"fault json: {fault_json}")
  print(f"monitor csv: {monitor_csv}")
  print(f"stability csv: {stability_csv}")
  print(f"Summary CSV: {output_csv}")
  print(f"Bar chart: {output_png}")
  print(f"Timeline CSV: {timeline_csv}")
  print(f"Timeline chart: {timeline_png}")


if __name__ == "__main__":
  main()
