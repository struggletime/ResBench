import os.path
import csv
import math
import json
import re
import datetime
try:
    import pymser
except ImportError:
    pymser = None
from scipy.signal import savgol_filter
import pandas as pd

from evaluation_manage import integrated_change_point_detection
from evaluation_manage import identify_key_events
from evaluation_manage import plot_original_tps
from evaluation_manage import plot_smooth_tps
from evaluation_manage import calculate_score
from evaluation_manage import calculate_multi_score
from evaluation_manage import plot_resilience_triangle
from evaluation_manage import plot_resilience_radar

SCORE_KEYS = [
    "rto",
    "throughput",
    "latency",
    "stability",
    "defence",
    "recovery",
    "adaptability",
    "volatility",
]


def clamp_score(value, default=0.0):
    try:
        score = float(value)
    except (TypeError, ValueError):
        return default
    if not math.isfinite(score):
        return default
    return max(0.0, min(100.0, score))


def average(values, default=0.0):
    filtered = []
    for value in values:
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            continue
        if math.isfinite(parsed):
            filtered.append(parsed)
    if not filtered:
        return default
    return sum(filtered) / len(filtered)


def mark_fault_unmeasured(fault, reason):
    fault["metric_status"] = "NOT_EVALUATED"
    fault["metric_message"] = reason
    fault["outage_seconds"] = 0
    fault["recovery_time_factor"] = 0
    fault["throughput_rate"] = 1.0
    fault["latency_rate"] = 1.0
    for key in SCORE_KEYS:
        fault[key] = 100.0 if key != "adaptability" else 0.0


def sanitize_metric_value(value):
    if isinstance(value, float) and not math.isfinite(value):
        return 0.0
    if isinstance(value, dict):
        return {k: sanitize_metric_value(v) for k, v in value.items()}
    if isinstance(value, list):
        return [sanitize_metric_value(v) for v in value]
    return value

class MetricReport:
    def __init__(self, resdir):
        self.resdir = resdir
        self.summary = self.load_summary(resdir)
        self.measurement_end_ts_milliseconds = self.infer_measurement_end_ts_milliseconds(resdir)

        # load fault infos
        # find csv file end with faults.csv,
        # read "Start Time (microseconds)" and "Duration (microseconds)" for each row using dictreader
        # and store into fault infos array
        self.fault_infos = []
        for file in os.listdir(resdir):
            if file.endswith("faults.csv"):
                with open(os.path.join(resdir, file), 'r') as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        self.fault_infos.append({
                            "Fault Name": row["Fault Name"],
                            "start_ts_milliseconds": int(row["Start Time (milliseconds)"]),
                            "duration_milliseconds": int(row["Duration (milliseconds)"])
                        })
                break

        # sort fault infos by startTsMicroseconds
        self.fault_infos.sort(key=lambda x: x["start_ts_milliseconds"])
        # calculate the stage of each fault, stage end is the start of next fault or end of test
        # find the stage end timestamp for each fault
        for i, fault in enumerate(self.fault_infos):
            if i < len(self.fault_infos) - 1:
                fault["stage_end_ts_milliseconds"] = self.fault_infos[i +
                                                                      1]["start_ts_milliseconds"]
            else:
                fault["stage_end_ts_milliseconds"] = self.measurement_end_ts_milliseconds
            fault["stage_end_ts_milliseconds"] = min(
                fault["stage_end_ts_milliseconds"],
                self.measurement_end_ts_milliseconds,
            )

        for i, fault in enumerate(self.fault_infos):
            if fault["duration_milliseconds"] == -1:
                fault["fault_end_ts_milliseconds"] = fault["stage_end_ts_milliseconds"]
            else:
                fault["fault_end_ts_milliseconds"] = fault["start_ts_milliseconds"] + \
                    fault["duration_milliseconds"]
            if i == 0:
                fault["stage_start_ts_milliseconds"] = self.summary["Start timestamp (milliseconds)"]
            else:
                fault["stage_start_ts_milliseconds"] = self.fault_infos[i - 1]["fault_end_ts_milliseconds"]

        self.throughputs_per_seconds = self.calc_throughput_per_seconds(resdir)
        self.latency_per_seconds = self.calc_latency_per_seconds(resdir)

        self.df = pd.DataFrame({
            'time': range(1, len(self.throughputs_per_seconds) + 1),
            'throughput': self.throughputs_per_seconds
        })

    def infer_measurement_end_ts_milliseconds(self, resdir):
        start_ts = self.summary.get("Start timestamp (milliseconds)", 0)
        elapsed_end = start_ts + math.floor(
            self.summary.get("Elapsed Time (nanoseconds)", 0) / 1000_000.0)
        candidates = [elapsed_end]

        for file in os.listdir(resdir):
            if file.endswith("raw.csv"):
                with open(os.path.join(resdir, file), 'r') as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        try:
                            end_ts = math.floor(float(row["Start Time (microseconds)"]) * 1000
                                                + float(row["Latency (microseconds)"]) / 1000.0)
                        except (KeyError, TypeError, ValueError):
                            continue
                        candidates.append(end_ts)
                break

        for file in os.listdir(resdir):
            if file == "monitor.csv":
                with open(os.path.join(resdir, file), 'r') as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        try:
                            candidates.append(int(float(row["timestamp"])))
                        except (KeyError, TypeError, ValueError):
                            continue
                break

        return max(candidates)

    def load_summary(self, resdir):

        # load summary infos
        # find json file end with summary.json and read it
        summary = {}
        summary_file = None
        for file in os.listdir(resdir):
            if file.endswith("summary.json"):
                summary_file = os.path.join(resdir, file)
                with open(summary_file, 'r') as f:
                    summary = json.load(f)
                break
        if not summary:
            raise RuntimeError(
                f"No summary.json found or empty summary under {resdir}. "
                "This directory may be incomplete (only monitor/fault files)."
            )
        required = ["Start timestamp (milliseconds)", "Elapsed Time (nanoseconds)"]
        missing = [k for k in required if k not in summary]
        if missing:
            raise RuntimeError(
                f"Invalid summary schema in {summary_file}. Missing keys: {missing}. "
                "Please point -r to a complete benchmark result directory."
            )
        return summary

    def calc_throughput_per_seconds(self, resdir):

        test_start_ts = self.summary["Start timestamp (milliseconds)"]
        test_duration_seconds = max(
            1,
            math.ceil((self.measurement_end_ts_milliseconds - test_start_ts) / 1000.0) + 1,
        )
        print("test_duration_seconds", test_duration_seconds)
        # load raw csv
        # load transactions trace from raw.csv
        # each contains "Start Time (microseconds)", "Latency (microseconds)"
        # collect all successful transaction end in every second
        throughputs_milliseconds_per_seconds = [0 for i in range(test_duration_seconds)]
        for file in os.listdir(resdir):
            if file.endswith("raw.csv"):
                with open(os.path.join(resdir, file), 'r') as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        if row["isSuccess"] == "true":
                            endTs = math.floor(float(
                                row["Start Time (microseconds)"])*1000_000) + math.floor(float(row["Latency (microseconds)"]))
                            # print("endTs",endTs)
                            endTs = endTs/1000.0 - test_start_ts
                            # print("endTs",endTs)
                            endTs = math.floor(endTs/1000.0)
                            # print("endTs",endTs)
                            if 0 <= endTs < test_duration_seconds:
                                throughputs_milliseconds_per_seconds[endTs] += 1
                break

        return throughputs_milliseconds_per_seconds

    def calc_latency_per_seconds(self, resdir):
        # calc average latency for each second
        test_start_ts = self.summary["Start timestamp (milliseconds)"]
        test_duration_seconds = max(
            1,
            math.ceil((self.measurement_end_ts_milliseconds - test_start_ts) / 1000.0) + 1,
        )

        # load raw csv
        # load transactions trace from raw.csv
        # each contains "Start Time (microseconds)", "Latency (microseconds)"
        # collect all successful transaction end in every second
        latencies_milliseconds = [0 for i in range(test_duration_seconds)]
        counts = [0 for i in range(test_duration_seconds)]
        for file in os.listdir(resdir):
            if file.endswith("raw.csv"):
                with open(os.path.join(resdir, file), 'r') as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        if row["isSuccess"] == "true":
                            endTs = math.floor(float(
                                row["Start Time (microseconds)"])*1000_000) + math.floor(float(row["Latency (microseconds)"]))
                            endTs = endTs/1000.0 - test_start_ts
                            endTs = math.floor(endTs/1000.0)
                            if 0 <= endTs < test_duration_seconds:
                                latencies_milliseconds[endTs] += float(
                                    row["Latency (microseconds)"]) / 1000.0
                                counts[endTs] += 1
                break
        latencies_milliseconds_per_seconds = [-1 if counts[i] ==
                                              0 else latencies_milliseconds[i] / counts[i] for i in range(test_duration_seconds)]
        return latencies_milliseconds_per_seconds

    def calc_avg_data_loss(self, resdir):

        data_loss_check_history = []
        # load data loss check history from file end with data_loss.csv
        # read "TimeStamp (milliseconds)" as ts_milliseconds and read "AvgDataLoss (seconds)" as avg_data_loss
        for file in os.listdir(resdir):
            if file.endswith("data_loss.csv"):
                with open(os.path.join(resdir, file), 'r') as f:
                    reader = csv.DictReader(f)
                    for row in reader:
                        data_loss_check_history.append({
                            "ts_milliseconds": int(row["TimeStamp (milliseconds)"]),
                            "avg_data_loss": float(row["AvgDataLoss (seconds)"])
                        })
                break

        if len(data_loss_check_history) == 0:
            for fault in self.fault_infos:
                fault["data_loss"] = 0
                fault["rpo"] = math.exp(-0.1 * fault["data_loss"])
                fault["rpo"] = round(100 * fault["rpo"], 2)
            return

        # sort data_loss_check_history by ts_milliseconds
        data_loss_check_history.sort(key=lambda x: x["ts_milliseconds"])
        if data_loss_check_history[-1]["avg_data_loss"] == 0.0:
            # fast path
            for i, fault in enumerate(self.fault_infos):
                fault["data_loss"] = 0
                fault["rpo"] = math.exp(-0.1 * fault["data_loss"])
                fault["rpo"] = round(100 * fault["rpo"], 2)
            return
        # print(len(data_loss_check_history))
        checks_before_faults = []
        # find nearest check result before each fault
        for fault in self.fault_infos:
            # find nearest check result before fault
            nearest_check = None
            for check in data_loss_check_history:
                # print(check)
                # print(fault)
                if check["ts_milliseconds"] < fault["start_ts_milliseconds"]:
                    nearest_check = check
                else:
                    break
            checks_before_faults.append(nearest_check)

        # append last check result
        checks_before_faults.append(data_loss_check_history[-1])

        # the check history is the sum of data loss result of each fault
        # calculate the data loss of each fault using check_{i+1} - check_i
        for i, fault in enumerate(self.fault_infos):
            fault["data_loss"] = checks_before_faults[i +
                                                      1]["avg_data_loss"] - checks_before_faults[i]["avg_data_loss"]
            fault["rpo"] = math.exp(-0.1 * fault["data_loss"])
            fault["rpo"] = round(100 * fault["rpo"], 2)

    def calc_outage(self, resdir):

        # 1、compute base throughput using average throughput before first fault
        stage0_seconds = math.floor(
            (self.fault_infos[0]["start_ts_milliseconds"] - self.summary["Start timestamp (milliseconds)"])/1000.0)
        stage0_seconds = max(1, min(stage0_seconds, len(self.throughputs_per_seconds)))
        base_throughputs = average(self.throughputs_per_seconds[:stage0_seconds], 0.0)
        if base_throughputs <= 0:
            base_throughputs = average([tp for tp in self.throughputs_per_seconds if tp > 0], 1.0)

        # 2、if throughput is less than 15% base throughput, it is outage
        # 3、compute average outage duration during each fault stage
        for fault in self.fault_infos:
            stage_start_second = math.floor(
                (fault["start_ts_milliseconds"] - self.summary["Start timestamp (milliseconds)"])/1000.0)
            stage_end_second = math.floor(
                (min(fault["fault_end_ts_milliseconds"], self.measurement_end_ts_milliseconds)
                 - self.summary["Start timestamp (milliseconds)"])/1000.0)
            stage_start_second = max(0, stage_start_second)
            stage_end_second = min(len(self.throughputs_per_seconds), stage_end_second)
            stage_duration_seconds = stage_end_second - stage_start_second
            if stage_duration_seconds <= 0:
                fault["outage_seconds"] = 0
                fault["rto"] = 100.0
                fault["metric_status"] = "NOT_EVALUATED"
                fault["metric_message"] = "故障注入时间未落入有效性能采样区间"
                continue
            # print(f"debug: fault {fault}, duration {stage_duration_seconds}")
            outage_seconds = 0
            stage_throughputs = self.throughputs_per_seconds[stage_start_second:stage_end_second]
            per_outage_seconds = sum([1 if tp < 0.15 * base_throughputs
                                  else 0 for tp in stage_throughputs])
            outage_seconds += per_outage_seconds
            # print(f"debug: throughputs: {stage_throughputs}, outages: {per_outage_seconds}
            fault["outage_seconds"] = outage_seconds
            fault["rto"] = math.exp(-0.1 * outage_seconds / 60.0)
            fault["rto"] = round(100 * fault["rto"], 2)

    def steady_metric(self, fault_start: int, stage_end: int, txn_stat, performance_desired):
        steady_metric = {
            'start_time': fault_start,
            'end_time': stage_end,
        }

        original_txn_stat = txn_stat

        # ----
        # Use pymser to find the steady state
        # ----
        stage_data = txn_stat[fault_start:stage_end]
        if len(stage_data) < 2:
            steady_metric['recovery_time_factor'] = 0
            steady_metric['recovery_factor'] = 0
            steady_metric['total_performance_factor'] = 0
            steady_metric['absorption_factor'] = 0
            return steady_metric

        if pymser is None:
            recovery_threshold = 0.9 * performance_desired
            recovery_time = 0
            for i, value in enumerate(stage_data):
                if value >= recovery_threshold:
                    recovery_time = i
                    break
            else:
                recovery_time = len(stage_data) - 1
            steady_metric['recovery_time_factor'] = recovery_time
            performance_recovery = average(
                original_txn_stat[fault_start + recovery_time: min(fault_start + recovery_time + 60, stage_end)],
                0.0,
            )
            recovery_factor = performance_recovery / performance_desired if performance_desired > 0 else 0
            steady_metric['recovery_factor'] = min(recovery_factor, 1.0)
            total_performance = average(original_txn_stat[fault_start:stage_end], 0.0)
            steady_metric['total_performance_factor'] = min(
                total_performance / performance_desired, 1.0) if performance_desired > 0 else 0
            steady_metric['absorption_factor'] = min(
                min(stage_data) / performance_desired, 1.0) if performance_desired > 0 else 0
            return steady_metric

        steady_result = pymser.equilibrate(
            stage_data, LLM=False, batch_size=1, ADF_test=False, uncertainty='uSD', print_results=False)

        mse = steady_result['MSE']

        # compute average performance in later 1min or all time if recovery time is less than 1min

        performance_recovery = sum(original_txn_stat[fault_start+steady_result['t0']: min(
            fault_start+steady_result['t0']+60, stage_end)]) / min(60, stage_end - fault_start - steady_result['t0'])

        # find min mse
        minn1 = steady_result['t0']

        # allow 10% difference between local min mse and the recovery mse
        for i in range(0, minn1):
            if mse[i] < 1.05*mse[minn1] or abs(mse[i] - mse[minn1]) < 1:
                minn1 = i
                break

        # ----
        # Use pymser to find the steady state for filtered txn_stat
        # ----
        txn_stat = savgol_filter(txn_stat, 11, 3)
        sd = txn_stat[fault_start:stage_end]
        steady_result = pymser.equilibrate(
            txn_stat[fault_start:stage_end], LLM=False, batch_size=1, ADF_test=False, uncertainty='uSD', print_results=False)

        mse = steady_result['MSE']

        # find min mse
        minn2 = steady_result['t0']

        # allow 10% difference between local min mse and the recovery mse
        for i in range(0, minn2):
            if mse[i] < 1.05*mse[minn2] or abs(mse[i] - mse[minn2]) < 1:
                minn2 = i
                break

        minn = int(min(minn1, minn2))

        while txn_stat[fault_start+minn] < 0.9*performance_recovery and (fault_start+minn) < stage_end:
            minn += 1

        # print("recovery end {}", minn, "s" "recovery end under non-filtered txn_stat: ",
        #       minn1, "s" "recovery end under filtered txn_stat: ", minn2, "s")

        recovery_time = minn
        steady_metric['recovery_time_factor'] = minn

        # average performance in later 1min or all time if recovery time is less than 1min
        performance_recovery = sum(original_txn_stat[fault_start+recovery_time: min(
            fault_start+recovery_time+60, stage_end)]) / min(60, stage_end - fault_start - recovery_time)
        recovery_factor = performance_recovery / performance_desired
        steady_metric['recovery_factor'] = int(min(recovery_factor, 1.0))

        if recovery_time != 0:
            total_performance = sum(
                original_txn_stat[fault_start:stage_end]) / (stage_end - fault_start)
            total_performance_factor = total_performance / performance_desired
            steady_metric['total_performance_factor'] = min(
                total_performance_factor, 1)
            absorption_factor = min(
                original_txn_stat[fault_start:fault_start+recovery_time]) / performance_desired
            steady_metric['absorption_factor'] = min(absorption_factor, 1)
        else:
            steady_metric['total_performance_factor'] = min(recovery_factor, 1)
            steady_metric['absorption_factor'] = min(recovery_factor, 1)

        # print(f"Steady state metrics: {steady_metric}")
        return steady_metric

    def calc_steady(self, resdir):
        # calc steady_metric
        # calc normal throughput and latency using trace before faults
        normal_stage = math.floor(
            (self.fault_infos[0]["start_ts_milliseconds"] - self.summary["Start timestamp (milliseconds)"])/1000.0)
        normal_stage = max(1, min(normal_stage, len(self.throughputs_per_seconds)))
        desired_throughput = average(self.throughputs_per_seconds[:normal_stage], 0.0)
        if desired_throughput <= 0:
            desired_throughput = average([tp for tp in self.throughputs_per_seconds if tp > 0], 1.0)
        desired_latency = average(
            [lat for lat in self.latency_per_seconds[:normal_stage] if lat != -1],
            1.0,
        )
        if desired_latency <= 0:
            desired_latency = 1.0

        for fault in self.fault_infos:
            fault_stage_start = int(
                (fault["start_ts_milliseconds"] - self.summary["Start timestamp (milliseconds)"]) / 1000)
            fault_stage_end = int(
                (min(fault["fault_end_ts_milliseconds"], self.measurement_end_ts_milliseconds)
                 - self.summary["Start timestamp (milliseconds)"]) / 1000)
            fault_stage_start = max(0, fault_stage_start)
            fault_stage_end = min(len(self.throughputs_per_seconds), fault_stage_end)
            if fault_stage_end <= fault_stage_start:
                mark_fault_unmeasured(fault, "故障注入时间未落入有效性能采样区间")
                continue
            # get recovery_time_factor for each fault
            fault["recovery_time_factor"] = self.steady_metric(
                fault_stage_start, fault_stage_end, self.throughputs_per_seconds, desired_throughput)["recovery_time_factor"]
            throughput_after_recovery = average(
                self.throughputs_per_seconds[fault_stage_start: fault_stage_end],
                0.0,
            )
            fault["throughput_rate"] = min(
                throughput_after_recovery/desired_throughput if desired_throughput > 0 else 0, 1)
            fault["throughput"] = round(clamp_score(100 * fault["throughput_rate"]), 2)
            fault_range = self.latency_per_seconds[fault_stage_start: fault_stage_end]
            filtered_values = [x for x in fault_range if x != -1]
            if len(filtered_values) == 0:
                fault["latency_rate"] = 1.0
                fault["latency"] = 100.0
            else:
                latency_after_recover = average(filtered_values, desired_latency)
                fault["latency_rate"] = max(latency_after_recover/desired_latency, 0)
                fault["latency"] = math.exp(-0.1 * (latency_after_recover/desired_latency))
                fault["latency"] = round(clamp_score(100 * fault["latency"]), 2)


    def save_metric(self, resdir):
        # save fault infos to metric.csv of result dir
        with open(os.path.join(resdir, "metric.csv"), 'w', newline='') as f:
            writer = csv.DictWriter(f, fieldnames=["Fault Name", "start_ts_milliseconds",
                                    "duration_milliseconds", "fault_end_ts_milliseconds", "stage_start_ts_milliseconds",
                                    "stage_end_ts_milliseconds", "data_loss", "outage_seconds", "latency_rate", "recovery_time_factor",
                                    "throughput_rate", "rpo", "rto", "throughput", "latency", "stability", "defence", "recovery", "adaptability", "volatility",
                                    "metric_status", "metric_message"], extrasaction="ignore")
            writer.writeheader()
            for fault in self.fault_infos:
                writer.writerow(fault)

        with open(os.path.join(resdir, "metric.json"), 'w') as f:
                json.dump(sanitize_metric_value(self.fault_infos), f, indent=4)

        # store fault_infos into sqlite
        import sqlite3
        conn = sqlite3.connect("metric.db")
        c = conn.cursor()
        # create table DBType(string), Suite(string), TimeStampMS(INTEGER), Fault(string), Metric(json) if not exits
        c.execute(
            "CREATE TABLE IF NOT EXISTS Metrics (DBType TEXT, Suite TEXT, TimeStampMS INTEGER, Fault TEXT, Metric TEXT)")
        db_type = self.summary["DBMS Type"]
        suite = self.summary["Test Suite"]
        time_stamp_ms = self.summary["Start timestamp (milliseconds)"]
        for fault in self.fault_infos:
            fault_name = fault["Fault Name"]
            # convert fault into json string
            fault_json = json.dumps(fault, default=str)
            # store in sqlite
            c.execute("INSERT INTO Metrics (DBType, Suite, TimeStampMS, Fault, Metric) VALUES (?, ?, ?, ?, ?)",
                      (db_type, suite, time_stamp_ms, fault_name, fault_json))
        conn.commit()

def main():
    # get resdir from resdir/r parameter
    import argparse
    import traceback as _traceback
    parser = argparse.ArgumentParser()
    parser.add_argument("--resdir", "-r", required=True,
                        help="results directory")
    args = parser.parse_args()
    print(args.resdir)
    resdir = args.resdir
    report = MetricReport(resdir)
    if len(report.fault_infos) > 0:
        try:
            _run_metric_pipeline(report, resdir)
        except Exception as e:
            print(f"[FATAL] Resilience metric pipeline failed: {e}")
            _traceback.print_exc()
            # 即使计算失败也尝试保存已有数据，避免前端完全看不到结果
            try:
                report.save_metric(resdir)
            except Exception:
                pass

def _run_metric_pipeline(report, resdir):
    """执行韧性评分全流程，封装为独立函数便于异常隔离"""
    report.calc_avg_data_loss(resdir)
    report.calc_outage(resdir)
    report.calc_steady(resdir)

    test_start_ts = report.summary["Start timestamp (milliseconds)"]
    for fault in report.fault_infos:
        try:
            _process_single_fault(report, fault, resdir, test_start_ts)
        except Exception as e:
            print(f"[FAULT_ERROR] fault={fault.get('Fault Name', '?')} failed: {e}")
            import traceback as _tb
            _tb.print_exc()
            mark_fault_unmeasured(fault, f"评分计算异常: {e}")
            fault_dir = os.path.join(resdir, fault["Fault Name"].replace("/", "").replace(" ", "_"))
            if not os.path.exists(fault_dir):
                os.makedirs(fault_dir)
            with open(os.path.join(fault_dir, "result.txt"), "w", encoding='utf-8') as fp:
                print(f"评分计算异常: {e}", file=fp)

    # 跨故障评分（适应力、波动率、雷达图）——仅在所有单故障处理完成后执行一次
    duplicates = {}
    for fault in report.fault_infos:
        name = fault["Fault Name"]
        if name in duplicates:
            duplicates[name].append(fault)
        else:
            duplicates[name] = [fault]

    for fault in report.fault_infos:
        fault_dir = os.path.join(resdir, fault["Fault Name"].replace("/", "").replace(" ", "_"))
        if not os.path.exists(fault_dir):
            os.makedirs(fault_dir)
        fp = open(os.path.join(fault_dir, "result.txt"), "a", encoding='utf-8')
        calculate_multi_score(fault, duplicates, fp)
        plot_resilience_radar(fault, fault_dir)
        fp.close()

    report.save_metric(resdir)


def _process_single_fault(report, fault, resdir, test_start_ts):
    """处理单个故障的韧性三角与评分计算"""
    # 1 预处理
    stage_start_ts = fault["stage_start_ts_milliseconds"]
    stage_end_ts = fault["stage_end_ts_milliseconds"]
    start_index = math.floor(stage_start_ts / 1000.0) - math.floor(test_start_ts / 1000.0) + 15
    end_index = math.floor(stage_end_ts / 1000.0) - math.floor(test_start_ts / 1000.0) - 15
    fault_dir = os.path.join(resdir, fault["Fault Name"].replace("/", "").replace(" ", "_"))
    if not os.path.exists(fault_dir):
        os.makedirs(fault_dir)

    if fault.get("metric_status") == "NOT_EVALUATED" or end_index <= start_index:
        print(f"[TRIANGLE_DEBUG] fault={fault['Fault Name']} SKIPPED - {fault.get('metric_message', 'end_index <= start_index')}")
        mark_fault_unmeasured(fault, fault.get("metric_message", "故障注入时间未落入有效性能采样区间"))
        with open(os.path.join(fault_dir, "result.txt"), "w", encoding='utf-8') as fp:
            print(fault["metric_message"], file=fp)
        return

    filter_df = report.df[(report.df['time'] >= start_index) & (report.df['time'] <= end_index)]
    if len(filter_df) < 5:
        print(f"[TRIANGLE_DEBUG] fault={fault['Fault Name']} SKIPPED - insufficient data points ({len(filter_df)})")
        mark_fault_unmeasured(fault, "有效性能采样点不足，无法计算韧性三角")
        with open(os.path.join(fault_dir, "result.txt"), "w", encoding='utf-8') as fp:
            print(fault["metric_message"], file=fp)
        return

    df = filter_df.reset_index(drop=True)
    df['time'] = df['time'] - df['time'].iloc[0] + 1

    # 2 绘制原始的时间序列
    plot_original_tps(df, fault_dir)

    # 3 对原始数据进行平滑处理
    # S-G滤波进行平滑，其中window_size和polyorder根据需要进行调整
    df = plot_smooth_tps(df, fault_dir)

    # 4 判断性能曲线是否平稳，如果平稳，韧性得分返回100
    # 计算 throughput 的均值
    mean_value = df['throughput'].mean()
    # 计算上下阈值
    lower_bound = mean_value * (1 - 0.1)
    upper_bound = mean_value * (1 + 0.1)
    # 判断每个数据是否在上下阈值范围内
    within_range = (df['throughput'] >= lower_bound) & (df['throughput'] <= upper_bound)
    # 计算满足条件的数据比例
    ratio = within_range.mean()
    # 判断比例是否大于等于指定百分比
    if ratio >= 0.99:
        fault["stability"] = 100.0
        fault["defence"] = 100.0
        fault["recovery"] = 100.0
        print(f"[TRIANGLE_DEBUG] fault={fault['Fault Name']} SKIPPED - stable performance curve (ratio=0.99)")
        with open(os.path.join(fault_dir, "result.txt"), "w", encoding='utf-8') as fp:
            print("性能曲线平稳，韧性三角相关维度按满分计。", file=fp)
        return

    # 5 突变点检测
    # 这里采用共识的方法，分别按照不同参数运行pelt_rbf,pelt_l1和binseg等算法，寻找至少一半算法都能找到的突变点，以提高准确度
    change_points = integrated_change_point_detection(df['throughput'].values)

    # 6 识别关键点，包括韧性三角的起始时间点，最低时间点，恢复结束时间点，最低点的tpmc值，恢复后基线的tpmc值
    t_start, t_min, t_end, d_max, baseline = identify_key_events(df, change_points)

    # 保存韧性三角关键点到 fault 字典，供 MetricUtils 读取并暴露到前端
    test_start_ms = int(report.summary["Start timestamp (milliseconds)"])
    fault["t_start_ms"] = int(test_start_ms + (start_index + int(t_start)) * 1000)
    fault["t_min_ms"] = int(test_start_ms + (start_index + int(t_min)) * 1000)
    fault["t_end_ms"] = int(test_start_ms + (start_index + int(t_end)) * 1000)
    fault["t_start_tps"] = float(df.iloc[int(t_start)]['throughput']) if int(t_start) < len(df) else 0.0
    fault["t_min_tps"] = float(d_max)
    fault["t_end_tps"] = float(df.iloc[int(t_end)]['throughput']) if int(t_end) < len(df) else 0.0
    fault["baseline_tps"] = float(baseline)
    print(f"[TRIANGLE_DEBUG] fault={fault['Fault Name']} t_start_ms={fault['t_start_ms']} t_min_ms={fault['t_min_ms']} t_end_ms={fault['t_end_ms']} baseline_tps={fault['baseline_tps']}")

    # ---- 使用 fault 注入区间计算三角形（仅前端展示，不参与评分） ----
    f_s = max(
        int(math.floor(fault["start_ts_milliseconds"] / 1000.0) - math.floor(test_start_ts / 1000.0)) - 2,
        0
    )
    max_e = int(math.floor(report.measurement_end_ts_milliseconds / 1000.0) - math.floor(test_start_ts / 1000.0))
    f_e = min(
        int(math.floor(fault["fault_end_ts_milliseconds"] / 1000.0) - math.floor(test_start_ts / 1000.0)) + 5,
        max_e
    )
    print(f"[FAULT_TRI_DEBUG] fault={fault['Fault Name']} f_s={f_s} f_e={f_e} start_ms={fault['start_ts_milliseconds']} end_ms={fault['fault_end_ts_milliseconds']} test_start_ms={test_start_ms}")
    raw_fault_df = report.df[(report.df['time'] >= f_s + 1) & (report.df['time'] <= f_e)].reset_index(drop=True)
    print(f"[FAULT_TRI_DEBUG] raw_fault_df len={len(raw_fault_df)} time_range=[{raw_fault_df['time'].min() if len(raw_fault_df) > 0 else 'N/A'},{raw_fault_df['time'].max() if len(raw_fault_df) > 0 else 'N/A'}]")
    if len(raw_fault_df) >= 5:
        # 与 stage 计算保持一致：time 列归一化到 1,2,3,...
        fault_df = raw_fault_df.copy()
        fault_df['time'] = fault_df['time'] - fault_df['time'].iloc[0] + 1
        print(f"[FAULT_TRI_DEBUG] after normalization: time_range=[{fault_df['time'].min()},{fault_df['time'].max()}]")
        raw_tps = fault_df['throughput'].values
        print(f"[FAULT_TRI_DEBUG] raw throughput range=[{raw_tps.min()},{raw_tps.max()}]")
        # 与 stage 计算保持一致：SG 滤波平滑后检测突变点
        smooth = savgol_filter(raw_tps, 5, 2)
        print(f"[FAULT_TRI_DEBUG] after smooth: range=[{smooth.min()},{smooth.max()}]")
        f_change_points = integrated_change_point_detection(smooth)
        print(f"[FAULT_TRI_DEBUG] change_points={f_change_points}")
        # 构造临时 df（平滑后的 throughput）传给 identify_key_events
        import pandas as _pd
        temp_fault_df = _pd.DataFrame({'time': fault_df['time'].values, 'throughput': smooth})
        ft_start, ft_min, ft_end, fd_max, f_baseline = identify_key_events(temp_fault_df, f_change_points)
        print(f"[FAULT_TRI_DEBUG] identify_key_events returned: ft_start={ft_start} ft_min={ft_min} ft_end={ft_end} fd_max={fd_max} f_baseline={f_baseline}")
        # ft_start/ft_min/ft_end 是 time 列的值（1-based），转为 0-based index
        ft_start_idx = int(ft_start) - 1
        ft_min_idx = int(ft_min) - 1
        ft_end_idx = int(ft_end) - 1
        print(f"[FAULT_TRI_DEBUG] 0-based indices: start={ft_start_idx} min={ft_min_idx} end={ft_end_idx} (smooth len={len(smooth)})")
        def safe_tps(arr, idx):
            if 0 <= idx < len(arr) and math.isfinite(arr[idx]):
                return float(arr[idx])
            return 0.0
        smooth_start = safe_tps(smooth, ft_start_idx)
        smooth_min = safe_tps(smooth, ft_min_idx)
        smooth_end = safe_tps(smooth, ft_end_idx)
        raw_start = safe_tps(raw_tps, ft_start_idx)
        raw_min = safe_tps(raw_tps, ft_min_idx)
        raw_end = safe_tps(raw_tps, ft_end_idx)
        print(f"[FAULT_TRI_DEBUG] smooth[{ft_start_idx}]={smooth_start} raw[{ft_start_idx}]={raw_start}")
        print(f"[FAULT_TRI_DEBUG] smooth[{ft_min_idx}]={smooth_min} raw[{ft_min_idx}]={raw_min}")
        print(f"[FAULT_TRI_DEBUG] smooth[{ft_end_idx}]={smooth_end} raw[{ft_end_idx}]={raw_end}")
        # 优先使用原始值（让点在曲线上），原始值为 0 时回退到平滑值
        fault["fault_t_start_ms"] = int(test_start_ms + (f_s + ft_start_idx) * 1000)
        fault["fault_t_min_ms"] = int(test_start_ms + (f_s + ft_min_idx) * 1000)
        fault["fault_t_end_ms"] = int(test_start_ms + (f_s + ft_end_idx) * 1000)
        fault["fault_t_start_tps"] = raw_start if raw_start > 0 else smooth_start
        fault["fault_t_min_tps"] = raw_min if raw_min > 0 else smooth_min
        fault["fault_t_end_tps"] = raw_end if raw_end > 0 else smooth_end
        fault["fault_baseline_tps"] = float(f_baseline) if math.isfinite(float(f_baseline)) else 0.0
        print(f"[FAULT_TRI_DEBUG] FINAL saved: start=({fault['fault_t_start_ms']},{fault['fault_t_start_tps']}) min=({fault['fault_t_min_ms']},{fault['fault_t_min_tps']}) end=({fault['fault_t_end_ms']},{fault['fault_t_end_tps']}) baseline={fault['fault_baseline_tps']}")
    else:
        print(f"[FAULT_TRI_DEBUG] fault={fault['Fault Name']} SKIPPED - insufficient fault window data ({len(raw_fault_df)})")

    # 7 计算韧性得分
    calculate_score(df, fault, t_start, t_min, t_end, d_max, baseline, fault_dir)

    # 8 绘制韧性三角
    plot_resilience_triangle(df, t_start, t_min, t_end, d_max, baseline, fault_dir)
    for fault in report.fault_infos:
        print(f"[TRIANGLE_DEBUG] SAVED fault={fault['Fault Name']} t_start_ms={fault.get('t_start_ms')} t_min_ms={fault.get('t_min_ms')} t_end_ms={fault.get('t_end_ms')}")
        print(f"[SCORE_DUMP] fault={fault['Fault Name']} "
              f"throughput={fault.get('throughput')} latency={fault.get('latency')} "
              f"stability={fault.get('stability')} defence={fault.get('defence')} "
              f"recovery={fault.get('recovery')} rto={fault.get('rto')} "
              f"adaptability={fault.get('adaptability')} volatility={fault.get('volatility')} "
              f"metric_status={fault.get('metric_status')}")

if __name__ == "__main__":
    main()
