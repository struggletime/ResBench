import math
import csv
import os
from sklearn.cluster import DBSCAN
from scipy.stats import zscore, norm
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from statsmodels.tsa.seasonal import STL
from statsmodels.tsa.stattools import adfuller
from ruptures import Pelt
from pylab import mpl
from collections import Counter
from ruptures import Binseg, Window, Dynp
from scipy.signal import savgol_filter
from statsmodels.nonparametric.smoothers_lowess import lowess
mpl.rcParams["font.sans-serif"] = ["Droid Sans Fallback", "SimHei"]
mpl.rcParams["axes.unicode_minus"] = False


def integrated_change_point_detection(data, methods=None):
    """
    突变点检测函数，集成多种突变点检测算法，提高准确性

    参数:
    data: 时间序列数据 (1D数组或Series)
    methods: 使用的检测方法列表

    返回:
    最可靠的突变点列表
    """
    if methods is None:
        methods = [
            ("pelt_rbf", Pelt(model="rbf", min_size=1, jump=1)),
            # ("pelt_l1", Pelt(model="l1", min_size=2)),
            # ("pelt_l2", Pelt(model="l2", min_size=2)),
            ("binseg", Binseg(model="l2", min_size=2)),
            ("window", Window(model="l2", width=20, jump=1)),
            ("dynp", Dynp(model="l2", min_size=1, jump=1))
        ]

    all_change_points = []

    # 1. 运行所有检测器
    for name, detector in methods:
        try:
            detector.fit(data)
            # 自适应惩罚项
            n_samples = len(data)
            penalty = 2 * np.log(n_samples)  # 基于BIC的惩罚项
            if name == "dynp" or name == "binseg":
                bkps = detector.predict(n_bkps=20)
            else:
                bkps = detector.predict(pen=penalty)
            valid_bkps = [b for b in bkps if b < len(data) - 5]
            all_change_points.extend(valid_bkps)
            # print(f"{name} 检测到 {len(valid_bkps)} 个突变点: {valid_bkps}")
        except Exception as e:
            print(f"{name} 检测失败: {e}")

    if not all_change_points:
        return []

    # 2. 投票机制：选择被多个检测器共同识别的点
    point_counts = Counter(all_change_points)
    consensus_points = [point for point, count in point_counts.items()
                        if count >= max(1, len(methods) // 3)]  # 至少一半检测器同意

    final_points = sorted(consensus_points)

    # print(f"最终突变点: {final_points}")
    return final_points


def map_to_score(x, n=0.4):
    """
    将x从[0, 0.25]映射到[100, 0]分数区间
    x: 输入值，范围[0, 0.25]
    n: 幂指数，默认0.1，小于1

    参数:
    x: 待映射的分数
    n: 控制范围

    返回:
    [100, 0]区间内的分数值
    """
    if x < 0 or x > 0.25:
        raise ValueError("x must be between 0 and 0.25")
    return 100 * (1 - (x / 0.25) ** n)


def identify_recovery_end(df, min_idx, baseline, std_dev, change_points=None):
    """
    寻找恢复结束点

    参数:
    df : 原始数据
    min_idx : 故障最低点位置
    baseline : 基线值
    std_dev : 标准差


    返回:
    恢复结束点
    """
    # 获取故障后的数据（从最低点之后开始）
    # 若最后一个点与最低点的吞吐量差距极小或相同，则可以认为最后一个点就是恢复结束点
    if abs(df.loc[len(df) - 1, 'throughput'] -df.loc[min_idx, 'throughput']) <= 1:
        return len(df) - 1
    recovery_df = df.iloc[min_idx + 1:].copy()
    if recovery_df.empty:
        return min_idx

    # 过滤出在最低点之后的突变点，并按时间顺序排序
    candidate_points = sorted([cp for cp in change_points if cp > min_idx])

    for cp in candidate_points:
        # 对于每个点，评估稳定性和恢复程度
        stability = evaluate_stability(
            df, cp, baseline, std_dev)
        # 检查是否满足基本条件
        # 设置稳定性阈值和恢复程度阈值，可以根据需要调整
        if stability >= 0.59 and df.loc[cp, 'throughput'] >= baseline * 0.9:
            return cp

    # 如果没有找到符合条件的突变点，使用传统方法
    recovery_end= fallback_method(df, min_idx, baseline, std_dev)
    return recovery_end


def evaluate_stability(df, point, baseline, std_dev, check_window=180):
    """
    计算point后check_window个点的稳定性

    参数:
    df : 原始数据
    point : 待计算的可能结束点
    baseline : 基线值
    std_dev : 标准差
    check_window : 检查的窗口大小,可以根据需要调整

    返回:
    稳定性评分
    """
    # 获取突变点后的数据
    post_data = df[df.index >= point]
    if len(post_data) < 10:  # 数据不足
        return 0.0

    # 使用自适应窗口（最多100个点）
    window = min(check_window, len(post_data))
    values = post_data['throughput'].iloc[:window]

    # 计算稳定性指标
    # 1. 波动范围（相对于基线标准差）
    value_range = values.max() - values.min()
    range_score = max(0, 1 - value_range / (3 * std_dev))

    # 2. 标准差（相对于基线标准差）
    value_std = values.std()
    std_score = max(0, 1 - value_std / (0.7 * std_dev))

    # 3. 趋势稳定性（检测最后部分是否更稳定）
    last_quarter = values.iloc[-len(values) //
                               4:] if len(values) >= 20 else values
    last_std = last_quarter.std()
    trend_score = max(0, 1 - last_std / (0.5 * std_dev)) if len(last_quarter) > 5 else 0.5

    # 计算稳定性综合评分
    stability_score = 0.4 * range_score + 0.4 * std_score + 0.2 * trend_score

    return min(1.0, stability_score)


def fallback_method(df, min_idx, baseline, std_dev):
    """
    针对恢复失败型的恢复结束点定位

    参数:
    df : 原始数据
    min_idx : 最低点的位置
    baseline : 基线值
    std_dev : 标准差

    返回:
    恢复结束点
    """
    recovery_df = df.iloc[min_idx + 1:].copy()
    if recovery_df.empty:
        return min_idx, baseline

    throughput = recovery_df['throughput']

    # 方法1: 寻找稳定区域（优先选择最早的点）
    stable_point = find_stable_region(recovery_df, std_dev)
    if stable_point and abs(df.loc[stable_point, 'throughput'] - df.loc[min_idx, 'throughput']) / df.loc[min_idx, 'throughput'] >= 0.1:
        return stable_point, throughput.loc[stable_point:].mean()

    # 方法2: 返回恢复程度最高的点
    max_idx = throughput.idxmax()
    if abs(df.loc[max_idx, 'throughput'] - df.loc[min_idx,'throughput']) / df.loc[min_idx, 'throughput'] < 0.1:
        max_idx = len(df) - 1
    return max_idx


def find_stable_region(df, std_dev,
                       min_duration=90, max_deviation=1.0):
    """
    寻找满足稳定性条件的连续区域，返回最早的点

    参数:
    df : 原始数据
    std_dev : 标准差
    min_duration : 滚动窗口
    max_deviation : 偏离度阈值

    返回:
    满足条件的最早点
    """
    throughput = df['throughput']
    tolerance = max_deviation * std_dev

    # 计算滚动波动范围
    rolling_range = throughput.rolling(min_duration, min_periods=min_duration).apply(
        lambda x: x.max() - x.min(), raw=True
    )
    # 寻找满足稳定性条件的区域
    stable_regions = rolling_range[rolling_range < tolerance]
    if not stable_regions.empty:
        # 返回第一个稳定区域的起始点
        return stable_regions.index[0]

    return None

# 5 识别关键事件点


def identify_fault_start(df, min_idx, change_points):
    """
    鲁棒的故障起始点识别

    参数:
    df : 原始数据
    min_idx : 最低点的位置
    change_points : 突变点集合

    返回:
    故障起始点
    """
    pre_fault_points = [cp for cp in change_points if cp < min_idx]
    if not pre_fault_points:
        # 没有检测到下降前的突变点，用第一个数据点作为故障起始
        return df.loc[0, 'time']
    pre_fault_points = np.flip(pre_fault_points)
    max_score = 0
    best_candidate = 0
    for cp in pre_fault_points:
        score = 0

        # 持续时间得分（避免瞬时波动）
        tmp = cp
        while tmp + \
                1 < len(df) and (df.loc[tmp + 1, 'throughput'] * 0.85) - df.loc[tmp, 'throughput'] < 0:
            tmp = tmp + 1
        duration = tmp - cp

        score += duration

        # 性能下降幅度得分
        trend_score = (df.loc[cp, 'throughput'] -
                       df.loc[tmp, 'throughput'])

        score += 10 * trend_score
        if (score > max_score * 1.2):
            max_score = score
            best_candidate = cp

    if best_candidate == 0:
        best_candidate = pre_fault_points[len(pre_fault_points) - 1]
    return df.loc[best_candidate, 'time']


def calculate_baseline(df):
    """
    计算基线并返回

    参数:
    df : 原始数据

    返回:
    基线值
    """
    try:

        # 假设测试初期是稳定的，尝试使用测试开始阶段的稳定段
        initial_window = df[(df['time'] >= 0) & (df['time'] <= min(3, df['time'].max()))]
        return initial_window['throughput'].quantile(
            0.75)  # 使用75%分位数避免初期波动

    except Exception as e:
        print(f"基线计算错误: {e}, 使用备用方法")
        return df['throughput'].max() * 0.9


def identify_key_events(df, change_points):
    """
    起始点，最低点和结束点三个关键点的识别

    参数:
    df : 原始数据
    change_points : 突变点集合

    返回:
    三个关键点，以及基线值和最低点对应的值
    """
    min_idx = df['throughput'].idxmin()
    candidate_min_points = [cp for cp in change_points]
    for cp in candidate_min_points:
        if abs((df.loc[cp - 1, 'throughput'] - df.loc[min_idx,'throughput']) / df.loc[min_idx, 'throughput']) <= 0.001:
            min_idx = cp - 1
            break
    t_min = df.loc[min_idx, 'time']
    d_max = df.loc[min_idx, 'throughput']
    std_dev = df['throughput'].std()

    baseline = calculate_baseline(df)

    t_start = identify_fault_start(df, min_idx, change_points)

    recovery_idx = identify_recovery_end(df, min_idx, baseline, std_dev, change_points)

    t_end = df.loc[recovery_idx, 'time']

    return t_start, t_min, t_end, d_max, baseline


def plot_original_tps(df, save_dir):
    """
    绘制原始吞吐量时间序列图

    参数:
    df : 包含 'time' 和 'throughput' 列的 DataFrame
    save_dir : 图片保存目录
    """
    plt.figure(figsize=(14, 6))
    plt.plot(df['time'], df['throughput'], label='原始吞吐量')
    plt.title('Original TPMC')
    plt.xlabel('time (min)')
    plt.ylabel('throughput (tpmC)')
    ymax = df['throughput'].max() * 1.1
    plt.ylim(0, ymax)
    plt.grid(True)
    plt.legend()
    plt.savefig(os.path.join(save_dir, "Original TPMC.png"))
    plt.close()


def plot_smooth_tps(df, save_dir):
    """
    S-G滤波平滑处理并绘制平滑后的时间序列图

    参数:
    df : 包含 'time' 和 'throughput' 列的 DataFrame
    save_dir : 图片保存目录

    返回:
    平滑处理后的 DataFrame（'time' 和 'throughput' 列）
    """
    df['smooth.5'] = savgol_filter(df['throughput'], 5, 2)
    df = df[['time', 'smooth.5']]
    df['throughput'] = df['smooth.5']
    plt.figure(figsize=(14, 8))
    plt.plot(df['time'], df['throughput'], label='吞吐量', alpha=0.7)
    plt.title('Smooth TPMC')
    plt.xlabel('time (min)')
    plt.ylabel('throughput (tpmC)')
    ymax = df['throughput'].max() * 1.1
    plt.ylim(0, ymax)
    plt.legend()
    plt.grid(True)
    plt.savefig(os.path.join(save_dir, "Smooth TPMC.png"))
    plt.close()
    return df


def _get_time_factors(df):
    """
    推断数据的时间单位，返回 (dx, time_to_minutes)

    dx : 梯形积分步长（与 df['time'] 单位一致）
    time_to_minutes : 将 df['time'] 差值转换为分钟的因子
    """
    if len(df) < 2:
        return 1.0, 1.0 / 60.0
    diffs = np.diff(df['time'])
    if len(diffs) == 0:
        return 1.0, 1.0 / 60.0
    dt = np.mean(diffs)
    if not np.isfinite(dt) or dt <= 0:
        return 1.0, 1.0 / 60.0
    if dt > 0.5:
        # 秒为单位（步长 ≈ 1 秒）
        return dt, 1.0 / 60.0
    else:
        # 分钟为单位
        return dt, 1.0


def _safe_trapezoid(y, dx=1.0):
    """安全梯形积分，空数组返回 0.0"""
    if y is None or len(y) == 0:
        return 0.0
    result = np.trapezoid(y, dx=dx)
    if isinstance(result, np.ndarray) and result.size == 0:
        return 0.0
    return float(result)


def _safe_df_lookup(df, time_val, column='throughput'):
    """安全从 DataFrame 按时间查找值，匹配不到时用最近邻"""
    sub = df[df['time'] == time_val]
    if len(sub) > 0:
        return float(sub[column].values[0])
    # 浮点匹配失败时回退到最近邻
    idx = (df['time'] - time_val).abs().idxmin()
    return float(df.loc[idx, column])


def calculate_score(df, fault, t_start, t_min, t_end, d_max, baseline, fault_dir):
    """
    计算单个故障阶段的各项评分，结果写入 fault 字典和 result.txt

    参数:
    df : 平滑后的 DataFrame
    fault : 故障信息字典（评分结果会写入此字典）
    t_start : 故障起始时间（df['time'] 单位）
    t_min : 性能最低点时间
    t_end : 恢复结束点时间
    d_max : 最低点吞吐量
    baseline : 基线吞吐量
    fault_dir : 结果保存目录
    """
    from sklearn.preprocessing import MinMaxScaler

    dx, time_to_min = _get_time_factors(df)
    print(f"[CALC_SCORE] fault={fault.get('Fault Name', '?')} t_start={t_start:.1f} t_min={t_min:.1f} t_end={t_end:.1f} d_max={d_max:.1f} baseline={baseline:.1f}")

    # 安全基线
    safe_baseline = baseline if baseline and baseline > 0 else 1.0

    # 提取故障阶段数据
    fault_period = df[(df['time'] >= t_start) & (df['time'] <= t_end)]
    y_def = []
    y_rec = []
    for p in range(1, len(fault_period)):
        if fault_period['time'].values[p] >= t_start and fault_period['time'].values[p] < t_min:
            y_def.append(fault_period['throughput'].values[p])
        elif fault_period['time'].values[p] >= t_min and fault_period['time'].values[p] <= t_end:
            y_rec.append(fault_period['throughput'].values[p])

    # 计算防御与恢复阶段时长（避免除零）
    t_def_delta = max(t_min - t_start, 1)
    t_rec_delta = max(t_end - t_min, 1)
    t_def = t_def_delta * time_to_min
    t_rec = t_rec_delta * time_to_min

    fp = open(os.path.join(fault_dir, "result.txt"), "w", encoding='utf-8')

    # TPS评分 (throughput)
    fp_mean = fault_period['throughput'].mean()
    print(f"故障期间平均吞吐量: {fp_mean:.1f} tpmC", file=fp)
    print(f"基线为: {baseline:.1f} tpmC", file=fp)
    tps_score = 100 * fp_mean / safe_baseline
    print(f"TPS评分为: {tps_score:.1f}", file=fp)
    fault["throughput"] = round(max(0.0, min(100.0, tps_score)), 2)

    # 稳定性评分（抖动率）
    scaler = MinMaxScaler()
    normalized_data = scaler.fit_transform(fault_period[['throughput']])
    if normalized_data.ndim > 1:
        normalized_data = normalized_data.flatten()
    split_data = [normalized_data[i:i + 30]
                  for i in range(0, len(normalized_data), 30)]
    sum3 = 0
    for sub_data in split_data:
        sum3 += np.var(sub_data)
    sum3 = sum3 / max(math.ceil(len(normalized_data) / 30), 1)
    r = min(sum3, 0.249)  # 钳制到 [0, 0.25) 防止 map_to_score 抛异常
    stability_score = map_to_score(r)
    print(f"稳定性评分为: {stability_score:.1f}", file=fp)
    fault["stability"] = round(max(0.0, min(100.0, stability_score)), 2)

    # 延迟评分（已在 calc_steady 中预计算，此处直接使用）
    latency_score = fault.get("latency", 0)
    print(f"延迟评分为: {latency_score}", file=fp)

    # 抵抗力评分
    # 三部分：归一化时间 + 最低点与baseline比值 + 抵抗阶段与baseline的积分比值，权重 4:4:2
    print(f"抵抗阶段耗时: {t_min - t_start:.1f}", file=fp)
    print(f"最低点性能: {d_max:.1f} tpmC", file=fp)
    def_integral = _safe_trapezoid(y_def, dx=dx)
    def_parts = (
        0.4 * math.exp(-0.1 * t_def) +
        0.4 * d_max / safe_baseline +
        0.2 * def_integral / (t_def_delta * safe_baseline)
    )
    defence_score = 100 * def_parts
    print(f"抵抗力评分: {defence_score:.1f}", file=fp)
    fault["defence"] = round(max(0.0, min(100.0, defence_score)), 2)
    print("抵抗力三部分", file=fp)
    print(math.exp(-0.1 * t_def), file=fp)
    print(d_max / safe_baseline, file=fp)
    print(def_integral / (t_def_delta * safe_baseline), file=fp)

    # 恢复力评分
    # 三部分：归一化时间 + 恢复结束点与baseline比值 + 恢复阶段与baseline的积分比值，权重 4:4:2
    print(f"恢复阶段耗时: {t_end - t_min:.1f}", file=fp)
    rec_end_tp = _safe_df_lookup(df, t_end, 'throughput')
    rec_integral = _safe_trapezoid(y_rec, dx=dx)
    rec_parts = (
        0.4 * math.exp(-0.1 * t_rec) +
        0.4 * rec_end_tp / safe_baseline +
        0.2 * rec_integral / (t_rec_delta * safe_baseline)
    )
    recovery_score = 100 * rec_parts
    print(f"恢复力评分: {recovery_score:.1f}", file=fp)
    fault["recovery"] = round(max(0.0, min(100.0, recovery_score)), 2)
    print("恢复力三部分", file=fp)
    print(math.exp(-0.1 * t_rec), file=fp)
    print(rec_end_tp / safe_baseline, file=fp)
    print(rec_integral / (t_rec_delta * safe_baseline), file=fp)

    # RTO评分（单次值，后续 calculate_multi_score 会按多轮累积更新）
    t_rto = (t_end - t_start) * time_to_min
    rto_score = 100 * math.exp(-0.1 * t_rto)
    print(f"RTO评分: {rto_score:.1f}", file=fp)
    fault["rto"] = round(max(0.0, min(100.0, rto_score)), 2)
    fault["_single_rto"] = fault["rto"]  # 保存单次RTO，供 calculate_multi_score 使用

    print(f"[CALC_SCORE_DONE] fault={fault.get('Fault Name', '?')} "
          f"throughput={fault.get('throughput')} stability={fault.get('stability')} "
          f"defence={fault.get('defence')} recovery={fault.get('recovery')} rto={fault.get('rto')}")
    fp.close()


def calculate_multi_score(fault, duplicates, fp):
    """
    计算多轮测试的适应力和波动率评分

    参数:
    fault : 当前故障信息字典（结果会写入此字典）
    duplicates : {fault_name: [fault_dict, ...]} 同名故障列表
    fp : 结果文件句柄
    """
    same_faults = sorted(
        duplicates.get(fault["Fault Name"], [fault]),
        key=lambda x: x.get("start_ts_milliseconds", 0)
    )
    n = len(same_faults)

    if n <= 1:
        fault["adaptability"] = 0.0
        fault["volatility"] = 0.0
        print("适应力评分: 0.0", file=fp)
        print("波动率评分: 0.0", file=fp)
        return

    # 收集各维度分数（按时间排序）
    tps_list = [f.get("throughput", 0) for f in same_faults]
    lat_list = [f.get("latency", 0) for f in same_faults]
    stab_list = [f.get("stability", 0) for f in same_faults]
    def_list = [f.get("defence", 0) for f in same_faults]
    rec_list = [f.get("recovery", 0) for f in same_faults]

    # 第一个故障是基准线，适应力和波动率均为 0
    is_first = fault is same_faults[0]

    if is_first:
        fault["adaptability"] = 0.0
        fault["volatility"] = 0.0
        print(f"适应力评分: 0.0 (首个故障，无基准可比较)", file=fp)
        print(f"波动率评分: 0.0 (首个故障，无偏离可计算)", file=fp)
    else:
        # 找到当前故障在排序列表中的索引
        cur_idx = same_faults.index(fault)

        # 适应力 = 当前故障各维度相对于第一次的改进的平均值
        ada_score = (tps_list[cur_idx] - tps_list[0]
                     + stab_list[cur_idx] - stab_list[0]
                     + rec_list[cur_idx] - rec_list[0]
                     + def_list[cur_idx] - def_list[0]
                     + lat_list[cur_idx] - lat_list[0]) / 5
        fault["adaptability"] = round(max(0.0, min(100.0, ada_score)), 2)
        print(f"适应力评分: {fault['adaptability']} (第{cur_idx+1}次相对于第1次)", file=fp)

        # 波动率 = 当前故障各维度相对于基准均值的偏离程度
        dims = [tps_list, lat_list, stab_list, def_list, rec_list]
        vol_score = 0
        for dim_list in dims:
            base_val = dim_list[0]
            if base_val > 0:
                vol_score += 0.2 * abs(dim_list[cur_idx] - base_val) / base_val
        vol_score = (1 - vol_score) * 100
        fault["volatility"] = round(max(0.0, min(100.0, vol_score)), 2)
        print(f"波动率评分: {fault['volatility']} (第{cur_idx+1}次相对于第1次)", file=fp)

    # 更新 RTO 为累积平均值
    # 使用 _single_rto 确保多次调用幂等（避免 rto 被覆盖后重复累积）
    for i, f in enumerate(same_faults):
        single_rto = f.get("_single_rto", f.get("rto", 0))
        if i == 0:
            cumulative_rto = single_rto
        else:
            prev_rto = same_faults[i - 1].get("rto", 0)
            cumulative_rto = (prev_rto * i + single_rto) / (i + 1)
        f["rto"] = round(cumulative_rto, 2)


def plot_resilience_triangle(df, t_start, t_min, t_end, d_max, baseline, save_dir):
    """
    标记关键点并绘制韧性三角

    参数:
    df : 原始（平滑前）DataFrame
    t_start : 故障起始时间
    t_min : 性能最低点时间
    t_end : 恢复结束点时间
    d_max : 最低点吞吐量
    baseline : 基线吞吐量
    save_dir : 图片保存目录
    """
    plt.figure(figsize=(10, 4))
    plt.plot(df['time'], df['throughput'], label='Throughput')
    plt.axhline(y=baseline, color='g', linestyle='--', label='Baseline')

    # 标记关键点
    plt.scatter([t_start, t_min, t_end],
                [df[df['time'] == t_start]['throughput'].values[0],
                 d_max,
                 df[df['time'] == t_end]['throughput'].values[0]],
                c='red', s=100, zorder=5)

    # 绘制韧性三角
    tri_x = [t_start, t_min, t_end]
    tri_y = [baseline, d_max, baseline]
    plt.fill(tri_x, tri_y, 'r', alpha=0.2, label='Resilience Triangle')

    plt.annotate(f'Fault Start\n{t_start:.1f}min',
                 (t_start, baseline * 1.05),
                 ha='center')
    plt.annotate(f'Minimum Point\n{d_max:.1f} tpmC\n({t_min:.1f}min)',
                 (t_min, d_max * 0.9),
                 ha='center')
    plt.annotate(f'Recovery End\n{t_end:.1f}min',
                 (t_end, baseline * 1.05),
                 ha='center')

    plt.xlabel('Time (min)')
    plt.ylabel('Throughput (tpmC)')
    ymax = df['throughput'].max() * 1.1
    plt.ylim(0, ymax)
    plt.legend()
    plt.grid(True)
    plt.savefig(os.path.join(save_dir, "Resilience Triangle.png"))
    plt.close()


def plot_resilience_radar(arg1, arg2=None):
    """
    画雷达图

    两种调用方式:
    - 旧接口: plot_resilience_radar(scores) —— scores 为 8 维评分列表，使用全局 folder_path
    - 新接口: plot_resilience_radar(fault, save_dir) —— 从 fault 字典读取 8 维评分

    8 维: Performance / Latency / Stability / Defence / Recovery / Adaptability / Period / Deviation
    """
    categories = [
        'Performance',
        'Latency',
        'Stability',
        'Defence',
        'Recovery',
        'Adaptability',
        'Period',
        'Deviation']

    if arg2 is not None:
        # 新接口: (fault, save_dir)
        fault = arg1
        save_dir = arg2
        scores = [
            fault.get("throughput", 0),
            fault.get("latency", 0),
            fault.get("stability", 0),
            fault.get("defence", 0),
            fault.get("recovery", 0),
            fault.get("adaptability", 0),
            fault.get("rto", 0),
            fault.get("volatility", 0),
        ]
    else:
        # 旧接口: (scores) —— 兼容 demo 代码
        scores = arg1
        save_dir = folder_path  # demo 中定义的全局路径

    fig = plt.figure(figsize=(8, 8))
    ax = fig.add_subplot(111, polar=True)

    # 计算每个角度的位置
    angles = np.linspace(
        0,
        2 * np.pi,
        len(categories),
        endpoint=False).tolist()
    scores += scores[:1]
    angles += angles[:1]

    ax.plot(angles, scores, linewidth=3, linestyle='solid')
    ax.fill(angles, scores, alpha=0.25)
    ax.set_theta_offset(np.pi / 2)
    ax.set_theta_direction(-1)
    ax.set_thetagrids(np.degrees(angles[:-1]), categories, fontsize=18)

    ax.set_rlabel_position(0)
    plt.yticks([20, 40, 60, 80, 100], ["20", "40", "60",
               "80", "100"], color="grey", size=14)
    plt.ylim(0, 100)
    plt.savefig(os.path.join(save_dir, "radar.png"),
                dpi=300,
                bbox_inches='tight',
                facecolor='white',
                edgecolor='none')
    plt.close()



if __name__ == "__main__":
    # 以下是demo主体部分
    # 1.加载数据
    base_dir = r"C:\Users\HPY\Desktop\时间序列分析\demo突变点识别测试"  # 路径根据需要替换
    tps_scores = []  # tps评分列表
    jit_scores = []  # 抖动率评分列表
    lat_scores = []  # 延迟评分列表
    ada_scores = []  # 适应力评分列表
    rec_scores = []  # 恢复力评分列表
    def_scores = []  # 抵抗力评分列表
    rto_scores = []  # RTO韧性评分列表
    vol_scores = []  # 波动率评分列表
    num = -1

    # 暂未引入延迟数据，假设5份延迟数据的评分如下
    lat_scores.append(10)
    lat_scores.append(30)
    lat_scores.append(50)
    lat_scores.append(70)
    lat_scores.append(90)
    # 假设在路径下面已经有5份数据，文件夹名分别为1，2，3，4，5，每份数据下面有一个tpmc随时间变化数据的csv文件
    for folder in ["5", "4", "3", "2", "1"]:
        num = num + 1
        folder_path = os.path.join(base_dir, folder)
        csv_files = [f for f in os.listdir(folder_path) if f.endswith('.csv')]
        if not csv_files:
            print(f"文件夹 {folder} 中没有CSV文件")
            continue

        csv_path = os.path.join(folder_path, csv_files[0])
        df = pd.read_csv(csv_path)
        df = df[1:]
        df = df[:-1]
        # 1 预处理

        df = df[['time', 'throughput']]  # 保留关键列
        df.dropna(inplace=True)  # 删除缺失值

        # 2 绘制原始的时间序列
        plt.figure(figsize=(14, 6))
        plt.plot(df['time'], df['throughput'], label='原始吞吐量')
        plt.title('Original TPMC')
        plt.xlabel('time (min)')
        plt.ylabel('throughput (tpmC)')
        ymax = df['throughput'].max() * 1.1
        plt.ylim(0, ymax)
        plt.grid(True)
        plt.legend()
        plt.savefig(os.path.join(folder_path, "Original TPMC.png"))
        # plt.show()

        # 3 对原始数据进行平滑处理

        # S-G滤波进行平滑，其中window_size和polyorder根据需要进行调整
        df['smooth.5'] = savgol_filter(df['throughput'], 5, 2)
        df = df[['time', 'smooth.5']]
        df['throughput'] = df['smooth.5']
        plt.figure(figsize=(14, 8))
        plt.plot(df['time'], df['throughput'], label='吞吐量', alpha=0.7)
        plt.title('Smooth TPMC')
        plt.xlabel('time (min)')
        plt.ylabel('throughput (tpmC)')
        plt.ylim(0, ymax)
        plt.legend()
        plt.grid(True)
        plt.savefig(os.path.join(folder_path, "Smooth TPMC.png"))
        # plt.show()

        # 4 突变点检测
        # 这里采用共识的方法，分别按照不同参数运行pelt_rbf,pelt_l1和binseg等算法，寻找至少一半算法都能找到的突变点，以提高准确度

        change_points = integrated_change_point_detection(df['throughput'].values)

        # 5 识别关键点，包括韧性三角的起始时间点，最低时间点，恢复结束时间点，最低点的tpmc值，恢复后基线的tpmc值
        t_start, t_min, t_end, d_max, baseline = identify_key_events(df, change_points)

        # 6 输出韧性三角关键信息
        delta_t = t_end - t_start
        fp = open(os.path.join(folder_path, "result.txt"), "w")
        print("\n韧性特征分析:", file=fp)
        print(f"故障起始时间: {t_start:.2f}min", file=fp)
        print(f"性能最低点: {t_min:.2f}min (吞吐量: {d_max:.1f})", file=fp)
        print(f"恢复结束时间: {t_end:.2f}min", file=fp)
        print(f"总影响时长: {delta_t:.2f}min", file=fp)

        # 7 开始计算评分
        fault_period = df[(df['time'] >= t_start) & (
            df['time'] <= t_end)]  # 提取故障阶段的吞吐量变化
        y_def = []
        y_rec = []
        for p in range(1, len(fault_period)):
            if (fault_period['time'].values[p] >= t_start and fault_period['time'].values[p] < t_min):
                y_def.append(fault_period['throughput'].values[p])
            elif (fault_period['time'].values[p] >= t_min and fault_period['time'].values[p] <= t_end):
                y_rec.append(fault_period['throughput'].values[p])

        # 7.1 计算TPS评分
        print(f"故障期间平均吞吐量: {fault_period['throughput'].mean():.1f} tpmC", file=fp)
        print(f"基线为: {baseline:.1f} tpmC", file=fp)
        tps_score = 100 * fault_period['throughput'].mean() / baseline
        print(f"TPS评分为: {tps_score:.1f}", file=fp)
        tps_scores.append(tps_score)

        # 7.2 计算稳定性评分
        from sklearn.preprocessing import MinMaxScaler
        scaler = MinMaxScaler()
        normalized_data = scaler.fit_transform(fault_period[['throughput']])
        if normalized_data.ndim > 1:
            normalized_data = normalized_data.flatten()
        split_data = [normalized_data[i:i + 30]
                      for i in range(0, len(normalized_data), 30)]
        sum3 = 0
        for sub_data in split_data:
            sum3 += np.var(sub_data)
        sum3 = sum3 / math.ceil(len(normalized_data) / 30)
        r = sum3
        print(f"稳定性评分为: {map_to_score(r):.1f} ",
              file=fp)  # 暂时未引入延迟数据，所以只考虑tpmc的抖动率
        jit_scores.append(map_to_score(r))

        # 7.3 计算延迟评分
        print(f"延迟评分为: {lat_scores[num]}", file=fp)

        # 7.4 计算抵抗力评分
        print(f"抵抗阶段耗时: {t_min - t_start:.1f}min", file=fp)
        print(f"最低点性能: {d_max:.1f} tpmC", file=fp)

        # 抵抗力评分三部分：归一化时间+最低点与baseline比值+抵抗阶段与baseline的积分比值，目前暂时认为三者权重为4:4:2，后续可以调整
        # 这里有一个问题是，负指数归一化时比例因子的取值如何覆盖不同数据范围？
        print(f"抵抗力评分: {100 * (0.4 * math.exp(-0.1 * (t_min - t_start)) + 0.4 * d_max / baseline + 0.2 * np.trapezoid(y_def , dx=0.033) / ((t_min - t_start) * baseline)):.1f}",file=fp)
        def_scores.append(100 * (0.4 * math.exp(-0.1 * (t_min - t_start)) + 0.4 * d_max / baseline + 0.2 * np.trapezoid(y_def, dx=0.033) / ((t_min - t_start) * baseline)))
        print("抵抗力三部分", file=fp)
        print(math.exp(-0.01 * (t_min - t_start)), file=fp)
        print(d_max / baseline, file=fp)
        print(np.trapezoid(y_def, dx=0.033) /
              ((t_min - t_start) * baseline), file=fp)

        # 7.5 计算恢复力评分
        print(f"恢复阶段耗时: {t_end - t_min:.1f}min", file=fp)

        # 恢复力评分三部分：归一化时间+恢复结束点与baseline比值+恢复阶段与baseline的积分比值，目前暂时认为三者权重为4:4:2，后续可以调整

        print(
            f"恢复力评分: {100 * ((0.4 * math.exp(-0.1 * (t_end - t_min))) + 0.4 * df[df['time'] == t_end]['throughput'].values[0] / baseline + 0.2 * np.trapezoid(y_rec, dx=0.033) / ((t_end - t_min) * baseline)):.1f}", file=fp)
        rec_scores.append(100 * ((0.4 * math.exp(-0.1 * (t_end - t_min))) + 0.4 *
                          df[df['time'] == t_end]['throughput'].values[0] / baseline + 0.2 * np.trapezoid(y_rec, dx=0.033) / ((t_end - t_min) * baseline)))
        print("恢复力三部分", file=fp)
        print(math.exp(-0.1 * (t_end - t_min)), file=fp)
        print(df[df['time'] == t_end]['throughput'].values[0] / baseline, file=fp)
        print(np.trapezoid(y_rec, dx=0.033) /
              ((t_end - t_min) * baseline), file=fp)

        # 7.6 计算RTO评分
        if (len(rec_scores) > 1):
            print(
                f"RTO评分: {(rec_scores[num - 1] * num + 100 * (math.exp(-0.1 * (t_end - t_start)))) / (num + 1):.1f}", file=fp)
            rto_scores.append(
                (rec_scores[num - 1] * num + 100 * (math.exp(-0.1 * (t_end - t_start)))) / (num + 1))
        else:
            print(
                f"RTO评分: {100 * (math.exp(-0.1 * (t_end - t_start))):.1f}", file=fp)
            rto_scores.append(100 * (math.exp(-0.1 * (t_end - t_start))))

        # 7.7 计算适应力评分
        ada_score = 0
        if (len(rec_scores) > 1):
            for i in range(1, len(rec_scores)):
                ada_score += tps_scores[i] - tps_scores[0]

            for i in range(1, len(rec_scores)):
                ada_score += jit_scores[i] - jit_scores[0]

            for i in range(1, len(rec_scores)):
                ada_score += rec_scores[i] - rec_scores[0]

            for i in range(1, len(rec_scores)):
                ada_score += def_scores[i] - def_scores[0]

            for i in range(1, len(rec_scores)):
                ada_score += lat_scores[i] - lat_scores[0]
            ada_score = max(0, ada_score)
            # 适应力评分=五个维度与原始差值的算术平均值，这里暂未加权
            ada_score = ada_score / (5 * (len(rec_scores) - 1))
        else:
            ada_score = 0
        print(f"适应力评分:{ada_score}", file=fp)
        ada_scores.append(ada_score)

        # 7.8 计算波动率评分
        vol_score = 0
        ave_tps_score = 0
        ave_jit_score = 0
        ave_rec_score = 0
        ave_def_score = 0
        ave_lac_score = 0

        if (len(rec_scores) > 1):
            # 波动率评分=五个维度的抖动率加权和
            for i in range(0, len(rec_scores)):
                ave_tps_score = ave_tps_score + tps_scores[i]
            ave_tps_score = ave_tps_score / (num + 1)
            sum = 0
            for i in range(0, len(rec_scores)):
                sum = sum + abs(tps_scores[i] - ave_tps_score)
            vol_score = vol_score + 0.2 * sum / (num + 1) / ave_tps_score

            for i in range(0, len(rec_scores)):
                ave_jit_score = ave_jit_score + jit_scores[i]
            ave_jit_score = ave_jit_score / (num + 1)
            sum = 0
            for i in range(0, len(rec_scores)):
                sum = sum + abs(jit_scores[i] - ave_jit_score)
            vol_score = vol_score + 0.2 * sum / (num + 1) / ave_jit_score

            for i in range(0, len(rec_scores)):
                ave_def_score = ave_def_score + def_scores[i]
            ave_def_score = ave_def_score / (num + 1)
            sum = 0
            for i in range(0, len(rec_scores)):
                sum = sum + abs(def_scores[i] - ave_def_score)
            vol_score = vol_score + 0.2 * sum / (num + 1) / ave_def_score

            for i in range(0, len(rec_scores)):
                ave_rec_score = ave_rec_score + rec_scores[i]
            ave_rec_score = ave_rec_score / (num + 1)
            sum = 0

            for i in range(0, len(rec_scores)):
                ave_lac_score = ave_lac_score + lat_scores[i]
            ave_lac_score = ave_lac_score / (num + 1)
            sum = 0

            for i in range(0, len(rec_scores)):
                sum = sum + abs(lat_scores[i] - ave_lac_score)
            vol_score = vol_score + 0.2 * sum / (num + 1) / ave_lac_score
            vol_score = (1 - vol_score) * 100
        else:
            vol_score = 0
        print(f"波动率评分:{vol_score}", file=fp)
        vol_scores.append(vol_score)

        # 8 标记关键点并绘制韧性三角
        plt.figure(figsize=(10, 4))
        plt.plot(df['time'], df['throughput'], label='Throughput')
        plt.axhline(y=baseline, color='g', linestyle='--', label='Baseline')

        # 标记关键点
        plt.scatter([t_start, t_min, t_end],
                    [df[df['time'] == t_start]['throughput'].values[0],
                    d_max,
                    df[df['time'] == t_end]['throughput'].values[0]],
                    c='red', s=100, zorder=5)

        # 绘制韧性三角
        tri_x = [t_start, t_min, t_end]
        tri_y = [baseline, d_max, baseline]
        plt.fill(tri_x, tri_y, 'r', alpha=0.2, label='Resilience Triangle')

        plt.annotate(f'Fault Start\n{t_start:.1f}min',
                     (t_start, baseline * 1.05),
                     ha='center')
        plt.annotate(f'Minimum Point\n{d_max:.1f} tpmC\n({t_min:.1f}min)',
                     (t_min, d_max * 0.9),
                     ha='center')
        plt.annotate(f'Recovery End\n{t_end:.1f}min',
                     (t_end, baseline * 1.05),
                     ha='center')

        # plt.title('Resilience Triangle Analysis')
        plt.xlabel('Time (min)')
        plt.ylabel('Throughput (tpmC)')
        plt.ylim(0, ymax)
        plt.legend()
        plt.grid(True)
        plt.savefig(os.path.join(folder_path, "Resilience Triangle.png"))

        # 9 绘制雷达图
        scores = []
        scores.append(tps_scores[num])
        scores.append(lat_scores[num])
        scores.append(jit_scores[num])
        scores.append(def_scores[num])
        scores.append(rec_scores[num])
        scores.append(ada_scores[num])
        scores.append(rto_scores[num])
        scores.append(vol_scores[num])
        plot_resilience_radar(scores, folder_path)  # 绘制雷达图函数
        # plt.show()
        # print(tps_scores)
        # print(jit_scores)
        # print(lat_scores)
        # print(def_scores)
        # print(rec_scores)
        # print(ada_scores)
        # print(rto_scores)
        # print(vol_scores)
