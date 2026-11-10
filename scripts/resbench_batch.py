#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""resbench 批量压测脚本 —— 只用后端（引擎）接口，不依赖前端。

一次调用 = 一个批次：databases × workloads × orchestrations 的笛卡尔积，全部**串行**执行。

  提交  POST /web/bench/startByForm        引擎自己的接口；无需 envInfo，无需起前端
  预检  POST /web/bench/tpcc/warehouse/verify  每个 (库, tpcc负载) 第一轮查一次库里实际仓数
  等待  GET  /web/bench/status             轮询到 end=true
  记录  GET  /web/bench/history/query      取这一轮的指标与产物完整性
  归档  POST /web/bench/history/label      打标签，让前端历史页也能检索到这批结果

tpcc 建仓很贵（1000 仓实测 4h08m），而引擎建不建仓只看 runOptions.reload、从不问库里
已经有多少仓。所以每个 (库, tpcc负载) 开跑前先照一次镜子：仓数对得上就直接当普通测试跑
（省掉这次建仓），对不上就先跑一轮**预热轮**（不带任何故障、reload=true）把仓建好，
之后这一对的正式轮一律 reload=false。查不出来时（连不上库 / 没权限 / 引擎是旧构建）不猜，
按 YAML 里配的 reload 执行。

建仓单独占一轮，不只是为了好看：reload 会 DROP + CREATE 再灌数据，文件号是那之后才生成的，
而文件类故障的 @auto: 哨兵要在提交那一刻解析成真实路径 —— 两件事放进同一次请求，解析出来
的路径建完仓就过期了。分开之后，正式轮提交时解析到的就是预热轮刚建好的那份数据。

本次调用的全部产物落在一个批次目录里（<--out>/<时间戳>_<库key>_<负载key>_<总编排key>/），
不再平铺进 --out 跟别的批次混在一起。总编排key 取编排文件的顶层 name:，不写就用文件名。

发给引擎的字段与前端表单一一对应，只有一个是脚本主动补的（前端压测表单里没有这个输入口）：
  testName       组合名 <库key>__<负载key>__<编排key>，只影响运行/归档命名，不改变引擎行为。
containerName（来自 databases[].container）不是脚本专有的 —— 前端「数据库配置」里就有
「容器名」这个输入框，两边是同一个顶层字段：非空时引擎把 chaosblade 类故障套 docker exec
在容器内注入（网络类故障与 DBChaos 类不受影响，仍走宿主机 / JDBC）。
其余一律写死成前端同款值或干脆不发：
  autoTimingMode / runOptions(create/load/execute/monitorIntervalSec) 写死；
  dbKey / orchestrationId / source / delaySeconds 不发；
  编排里某一批写了 at:（绝对秒）时，该批故障改发 planningSeconds，
  引擎直接把它当 <planning>，不再套首批 +60s / 批间 +30s 的固定推算。

配置由三份 YAML 描述（见 scripts/batch-example/）：
  --databases   一个文件可含多个库
  --workloads   一个文件可含多个负载
  --orchestration  故障编排文件，一个文件可以放多个编排（顶层 orchestrations: 列表），
                   也可以一个文件只放一个（key: + batches:，或直接写批次列表）；
                   顶层可选 name: 给这个文件的总编排命名（只用于批次目录名）

用法：
  python3 scripts/resbench_batch.py --databases dbs.yaml --workloads wls.yaml \\
      --orchestration orch/a.yaml --orchestration orch/b.yaml --execute
"""

import argparse
import csv
import json
import os
import re
import shlex
import signal
import sys
import time
from datetime import datetime

import requests
import yaml

# ---------------------------------------------------------------------------
# 引擎的排程常量（与 StartByFormService.buildScheduledFaults 一致）
# ---------------------------------------------------------------------------
FIRST_BATCH_BASE_SEC = 60  # 第一批故障的注入时刻
BATCH_GAP_SEC = 30         # 批与批之间引擎自动留的间隔

# ---------------------------------------------------------------------------
# 文件类故障的路径参数 → 后端推荐目标（--auto-file-targets）
# ---------------------------------------------------------------------------
# 引擎的 /web/faults/catalog 里没有文件类故障（目录只收 DBChaos 的库内故障，文件类是
# chaosblade 的宿主机操作，引擎用 switch 直接接），所以编排里得自己写路径；而路径是
# OID / 目录名，重灌一次数据就全变了（file-orch.yaml 头部那句"重灌过数据就要重取 OID"）。
# 打开 --auto-file-targets 后，下面这些参数会被换成 @auto: 哨兵，引擎在**开跑那一刻**
# 查库解析成真实路径，随灌随对。
#
# 取值来自后端 FileFaultTargetService.suggestFor 给每个故障标的**高强度档**推荐项
# （前端故障表单点「推荐」会高亮的那一条），不是脚本自己编的：
#   filemove:high     → 整个库目录        db-dir
#   filemove:dst-dir  → 数据总目录        datadir（老名字 pgdata 是同一个值，也认）
#   filechmod:high    → 整个库目录        db-dir
#   fileappend:high   → 最热表的最后一段  hottest-last-segment
# 例外：fileremoved 后端**没有**推荐规则（suggestFor 里没它的条目），
# 这里取最热文件的第 0 段 hottest-file，属脚本自定。
#
# 注意（MySQL / 单段表）：InnoDB 一个表一个 .ibd、不分段，后端在这些布局上**不给 append 标推荐**
# —— 每一档都会写在表的主数据文件末尾，而引擎的 append 命令不传 --enable-backup。脚本这里仍按
# @auto:hottest-last-segment 替换（在 MySQL 上就等于整张 .ibd），它会进下面的"不可恢复"提醒。
#
# 哨兵是"按库类型现场解析"的，同一个名字在不同库上落到不同文件：
#   PG    PGDATA/base/<oid>/<relfilenode>（1 GiB 一段，有第 0 段/最后一段/中间段之分）
#   MySQL @@datadir/<库>/<表>.ibd（InnoDB 一表一个文件、不分段：last-segment 就是整张 .ibd，
#         而没有"非最后一段"，hottest-mid-segment 在 MySQL 上会明确报错并让你换别的）
#
# ⚠ 两个后果，打开前必须知道：
#   1. append 换成 last-segment 是**不可恢复**的那一档（引擎渲染的 append 命令从不传
#      --enable-backup，多出来的字节不会被撤销，故障窗口结束后库还是坏的）——
#      正是 file-orch.yaml 里把 file-append-high 默认关掉的原因。要安全的低档就手写
#      @auto:hottest-mid-segment（不分段的库没有中间段，那边得直接写具体文件），
#      或这一个编排别开开关。
#   2. chmod 只映射到 db-dir（high）：编排里原先打在单个文件上的 low 档会被提到 high。
FILE_FAULT_AUTO_TARGETS = {
    "faultinject.fault.disk.filemove": {"src-path": "@auto:db-dir", "dst-dir": "@auto:pgdata"},
    "faultinject.fault.disk.filechmod": {"filepath": "@auto:db-dir"},
    "faultinject.fault.disk.fileappend": {"filepath": "@auto:hottest-last-segment"},
    "faultinject.fault.disk.fileremoved": {"file-path": "@auto:hottest-file"},
}
# 只有这几个哨兵名引擎认（FileFaultTargetService.SENTINEL_NAMES），写错引擎会报错并列出可选
AUTO_SENTINEL_NAMES = ("db-dir", "hottest-file", "hottest-last-segment",
                       "hottest-mid-segment", "datadir", "pgdata")
# 会在计划里点名提醒"这一档跑完库不会自己好"的参数值
IRREVERSIBLE_AUTO_TARGETS = ("@auto:hottest-last-segment",)

DB_TYPES = ("opengauss", "mysql", "postgresql", "oceanbase", "gaussdb")
DEPLOYMENT_TYPES = ("standalone", "distributed")
WORKLOAD_NAMES = ("smallbank", "tpcc")

# 「应用配置」能改参数的库（与前端 PARAM_SUPPORTED_TYPES 一致）
PARAM_SUPPORTED_TYPES = ("opengauss", "postgresql", "mysql")
# 这两个库的缓冲池/连接数是 postmaster 上下文，必须重启才生效（前端 NEEDS_RESTART_TYPES）
NEEDS_RESTART_TYPES = ("opengauss", "postgresql")

CSV_FIELDS = [
    "batch_no", "history_name", "db_key", "db_type", "deployment_type", "container",
    "workload_key", "workload", "orchestration", "suite", "faults",
    "plan_end_sec", "workload_duration_sec",
    "accepted", "success", "state", "start_time", "end_time", "elapsed_sec",
    "artifacts_complete", "size_bytes",
    "stability", "defence", "recovery", "rto", "outage_seconds",
    "enforced_fault_count",
    # 故障到底有没有真的生效（enforced_fault_count 是引擎给的，实测恒为 0，别用它判断）
    "fault_inject", "fault_inject_errors", "fault_verify",
    # 监控曲线判据：注入窗口内资源曲线是否出现预期方向的变化（manager 开会话 + 归档）
    "monitor_run", "monitor_verify", "monitor_evidence",
    "json_file", "note",
]

# 压测期间由 manager 开会话采集的指标。前 10 个覆盖 e1 的两个档位：
# 容器 CPU/内存、连接、回滚，以及宿主机的重传/重排、文件系统使用率、被暂停进程数。
# 后 10 个是网络故障的专用信号（2026-10-24 在 MonitoringCatalog 新增，已在现场
# Prometheus 上逐条验证有数据）：丢包看重传率/RTO/丢包重传/SYN 重传，乱序看 oo 队列，
# 重复包看 DSACK，建连被打崩看 conn.fail；packet rx/tx 只作流量对照。
# 注意：这一组 20 个指标要小于 res.monitoring.max-metrics（当前 32）；再加指标前先确认上限还有余量。
DEFAULT_MONITOR_METRICS = [
    "container.cpu.cores",
    "container.cpu.usage",
    "container.memory.usage",
    "db.connections.usage",
    "db.tps.agent",
    "db.rollback.rate",
    "host.netstat.retrans",
    "host.netstat.reorder",
    "host.disk.usage",
    "host.process.stopped",
    # 网络专用信号
    "net.tcp.retrans.ratio",
    "net.tcp.rto.rate",
    "net.tcp.lost_retrans.rate",
    "net.tcp.synretrans.rate",
    "net.tcp.oo.rate",
    "net.tcp.reorder.rate",
    "net.tcp.dsack.rate",
    "net.tcp.conn.fail.rate",
    "net.packet.rx",
    "net.packet.tx",
    # netem 自身证据：规则是否挂上、实际丢包率、配置档位、下发时延
    # 丢包率 = dropped / (dropped + sent)，是 net loss 唯一的直接测量值
    "net.qdisc.present",
    "net.qdisc.drop.percent",
    "net.qdisc.delay",
    "net.qdisc.duplicate.percent",
    # ICMP 主动探测：由 agent 从数据库主机 ping 默认网关，直接量出口链路
    # 丢包率/时延，不依赖被测流量是否还活着（丢包打到 agent 回传通道时，
    # net.tcp.* 会随连接一起塌缩，net.qdisc.* 又只在有流量时才动）
    "net.ping.loss",
    "net.ping.rtt",
]

# 每个故障在注入窗口里应该把哪条曲线往哪个方向推。
# min_abs = 窗口均值相对基线的绝对增量下限；min_ratio = 窗口均值相对基线的倍数下限。
# positive = 只看"窗口里出现过正数、基线里没有"（被暂停进程数）。
EFFECT_RULES = {
    # 主判据是容器口径；MySQL 组没有容器（3306 是宿主机 mysqld.service），
    # 整机口径的曲线只在主判据完全没采到时才用（_window_signal 返回 None）。
    "cpu.load": {
        "metric": "container.cpu.cores", "min_abs": 2.0,
        "fallback": {"metric": "host.cpu.usage", "min_abs": 20.0},
    },
    "memory.load": {
        "metric": "container.memory.usage", "min_abs": 5.0,
        "fallback": {"metric": "host.memory.usage", "min_abs": 5.0},
    },
    "connection.surge": {"metric": "db.connections.usage", "min_abs": 5.0},
    # 连接线程饱和：注入会话各占一条连接、在库内 SLEEP(60)/pg_sleep(60) 常驻 60 秒。
    # MySQL 侧 PROCESSLIST 里这些线程的状态是 Query 而不是 Sleep → db.threads.running
    # 直接抬升（溢出 300 时增量在数百量级，20 的门槛只用来排除工作负载自身抖动）。
    # PG 侧没有对应曲线（db.* 全是 og_* 口径，DSN 又不在被测实例上），这里**故意不给
    # fallback**：宁可判 NO_DATA，也不要拿 openGauss 的连接率冒充 PG 的证据。
    "connection.thread_saturation": {"metric": "db.threads.running", "min_abs": 20.0},
    "transaction.massive.rollback": {
        "metric": "db.rollback.rate", "min_abs": 1.0,
        # og_database_stats 没被采时退到 TPS：回滚也计入 xact_commit + xact_rollback
        "fallback": {"metric": "db.tps.agent", "min_abs": 0.0, "min_ratio": 1.3},
    },
    # 网络：主判据改用 2026-10-24 新增的专用信号（阈值依据见
    # docs/fault-monitoring-evidence.md §7.1）。旧的 host.netstat.* 只留在 fallback 里，
    # 仅当主指标这条曲线完全没采到时才会轮到它——它们分不清是哪种网络故障。
    "network.package": {
        "metric": "net.tcp.retrans.ratio", "min_abs": 1.0, "min_ratio": 2.0,
        "fallback": {"metric": "host.netstat.retrans", "min_abs": 0.5, "min_ratio": 2.0},
    },
    "network.unorder": {
        "metric": "net.tcp.oo.rate", "min_abs": 0.5, "min_ratio": 3.0,
        "fallback": {"metric": "host.netstat.reorder", "min_abs": 0.2, "min_ratio": 1.5},
    },
    "network.duplicate": {
        "metric": "net.tcp.dsack.rate", "min_abs": 0.2, "min_ratio": 3.0,
        "fallback": {"metric": "host.netstat.reorder", "min_abs": 0.2, "min_ratio": 1.5},
    },
    # 包损坏：比特被翻转后由接收端校验失败丢弃，现象和丢包同源（重传/RTO 上升），
    # 但 netem 不统计 corrupt、qdisc 的 dropped 恒为 0，判据只能看 TCP 后果。
    # 主判据用 RTO 绝对速率：2026-10-25 归档里同一档丢包下 rto 涨 5.7~95 倍，
    # 而 retrans.ratio 只有 1.7 倍且会被 0 值窗口摊薄，只留作 fallback。
    "network.corrupt": {
        "metric": "net.tcp.rto.rate", "min_abs": 1.0, "min_ratio": 2.0,
        "fallback": {"metric": "net.tcp.retrans.ratio", "min_abs": 1.0, "min_ratio": 2.0},
    },
    # 延迟没有宿主机 RTT 计数器（tcprtt 探针 loaded=false），只能拿吞吐下降做间接参考：
    # 窗口均值比基线低 30% 以上算命中。这是 L3 业务信号，不是资源证据，仅供对照。
    "network.delay": {
        "metric": "db.tps.agent", "min_drop": 0.3, "decrease": True, "indirect": True,
    },
    "disk.fill": {"metric": "host.disk.usage", "min_abs": 3.0},
    "process.stop": {"metric": "host.process.stopped", "positive": True},
}

EFFECT_BASELINE_SEC = 30   # 每个注入窗口往前取 30 秒做基线

# 监控能力探测失败后隔多久重试（秒）。2026-10-25 12:48 那批就是启动时探测抖了一下，
# 旧代码把失败永久缓存，整批 40 分钟没有任何曲线；现在改成冷却后重试。
MONITOR_PROBE_RETRY_SEC = 60
# 同一轮内的探测重试：第一次失败后隔 1 秒再试，最多 3 次。
# 第一个故障通常排在工作负载开始后 30 秒，几秒的重试不会影响注入时刻。
MONITOR_PROBE_ATTEMPTS = 3
MONITOR_PROBE_RETRY_DELAY_SEC = 1


class ConfigError(Exception):
    """配置/命令行错误 —— 启动阶段就要报出来，不要跑到一半才炸。"""


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
class Engine(object):
    def __init__(self, base_url):
        self.base = base_url.rstrip("/")
        self.session = requests.Session()

    def url(self, path):
        return self.base + path

    def get_json(self, path, params=None, timeout=60):
        resp = self.session.get(self.url(path), params=params, timeout=timeout)
        resp.raise_for_status()
        if not resp.content:
            return None
        return resp.json()

    def post_json(self, path, body, timeout=120):
        resp = self.session.post(self.url(path), json=body, timeout=timeout)
        resp.raise_for_status()
        if not resp.content:
            return None
        return resp.json()


# ---------------------------------------------------------------------------
# 进程挂起故障：自动推荐该挂哪个进程（--auto-process-target）
#
# 为什么需要它：process.stop 的 --process-cmd 是**命令行子串匹配**（chaosblade 拿
# ps/grep 找进程），手写一个裸进程名很容易命中错误实例 —— 现场就有两个 PG
# （宿主 /usr/bin/postgres -D /mnt/nvme4n1/pg_data -p 5432 与容器 pg_tpcc32 的 5433）、
# 两个 mysqld（宿主 /usr/sbin/mysqld :3306 与容器 mysql8 的裸 mysqld :33060）。
#
# 推荐规则（只读，不写文件、不发信号）：
#   1. 用 ss 按**数据库端口**找到监听进程（这是「被测库」的唯一硬锚点）；
#   2. 取它的 argv[0] 做匹配串 —— 与 chaosblade 的匹配方式同为子串匹配；
#   3. 核对命中范围：
#        · 库在容器里：命中集合必须落在该容器内（并用宿主机监听进程的 cgroup
#          核对它确实属于这个容器），容器 PID 命名空间天然隔离其它实例；
#        · 库在宿主机：所有命中进程必须与监听进程同 cgroup，否则说明这个裸名字
#          会打到别的实例上 —— 直接报错，让人显式写 process-cmd，绝不猜。
#
# 命中多个进程是正常的：PG 的 postgres 会命中 postmaster + 每个 backend + logger/
# checkpointer 等辅助进程，整簇挂起才是「数据库进程挂起」该有的效果；MySQL 只有
# 一个 mysqld 进程（线程不是进程）。
# ---------------------------------------------------------------------------
PROCESS_STOP_NAME_KEY = "faultinject.fault.process.stop"
PROCESS_CMD_PARAM = "process-cmd"
DEFAULT_AGENT_PORT = "8000"

# 探针脚本：输出 "RB\t<类型>\t<字段>…"，用 \t 分隔（命令行里基本不会出现制表符）。
# 按前缀捞行，不依赖 agent 把 stdout 包成什么外壳。
_PROCESS_PROBE_HOST = r"""
PID=$(ss -lntpH "sport = :__PORT__" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
if [ -z "$PID" ]; then printf 'RB\tLISTENER\t0\t-\t-\n'; exit 0; fi
printf 'RB\tLISTENER\t%s\t%s\t%s\n' "$PID" "$(awk -F: '{q=$3; if (length(q)>length(b)) b=q} END {print b}' /proc/$PID/cgroup 2>/dev/null)" "$(tr '\0' ' ' < /proc/$PID/cmdline 2>/dev/null)"
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  [ -n "$c" ] || continue
  g=$(awk -F: '{q=$3; if (length(q)>length(b)) b=q} END {print b}' "$d/cgroup" 2>/dev/null)
  printf 'RB\tP\t%s\t%s\t%s\n' "$p" "$g" "$c"
done
"""

# 容器里常常没装 iproute2/procps（pg_tpcc32 里 ps/ss/pgrep 全没有），只能读 /proc。
_PROCESS_LIST_CONTAINER = r"""
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  [ -n "$c" ] || continue
  printf 'RB\tP\t%s\t-\t%s\n' "$p" "$c"
done
"""

PORT_RE = re.compile(r"^\d+$")
CONTAINER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")


AGENT_ATTEMPTS = 4                      # agent 偶尔会断联，一次连不上不代表它挂了
AGENT_RETRY_DELAYS = (5, 15, 30)        # 秒；比 10s 的命令超时宽一档，避开瞬时抖动


def agent_exec(agent_base, script, timeout=30, attempts=AGENT_ATTEMPTS):
    """在数据库主机的 agent 上跑一段只读 shell，返回原始 stdout 文本。

    连不上要重试：agent 会偶尔断联（进程重启、连接被对端 RST、宿主机瞬时不可达），
    一次连不上就抛 ConfigError 会把整组测试直接停掉 —— 2026-11-01 11:35 的 pg×tpcc 组
    就是这么整组没跑的（`连不上数据库主机的 agent … connect timeout=35`），
    十几分钟后同一台机器上的 pg×smallbank 组一切正常。

    这里的命令全是只读探针（ps / docker inspect / docker exec 查询），重放没有副作用，
    所以可以放心重试；真断了的 agent 会在用尽重试后照旧抛 ConfigError。
    """
    body = {"cmd": "sh", "args": ["-c", script], "mode": "wait",
            "timeout": int(timeout), "capture": True}
    attempts = max(1, attempts)
    last_exc = None
    for attempt in range(attempts):
        try:
            resp = requests.post(agent_base.rstrip("/") + "/inject", json=body,
                                 timeout=timeout + 10)
            resp.raise_for_status()
            payload = resp.json() or {}
            return as_text(payload.get("result"))
        except Exception as exc:
            last_exc = exc
            if attempt + 1 >= attempts:
                break
            delay = AGENT_RETRY_DELAYS[min(attempt, len(AGENT_RETRY_DELAYS) - 1)]
            print("  ! agent %s 第 %d 次调用失败（%s），%d 秒后重试…"
                  % (agent_base, attempt + 1, exc, delay), file=sys.stderr)
            time.sleep(delay)
    raise ConfigError("连不上数据库主机的 agent %s（重试 %d 次仍失败，进程目标探测要用它）：%s"
                      % (agent_base, attempts, last_exc))


def _probe_rows(text):
    """把探针输出解析成行：{kind, pid, cgroup, cmdline}。"""
    rows = []
    for line in as_text(text).splitlines():
        # agent 会把第一行 stdout 顶在 "Executed: Successful: " 后面，按标记定位而不是按行首
        mark = line.find("RB\t")
        if mark < 0:
            continue
        parts = line[mark:].split("\t")
        if len(parts) < 5:
            continue
        rows.append({"kind": parts[1], "pid": parts[2],
                     "cgroup": parts[3], "cmdline": "\t".join(parts[4:]).strip()})
    return rows


def discover_db_process(db):
    """按端口找到被测库的进程，返回自动推荐的 process-cmd 与核对信息。

    失败一律抛 ConfigError（在提交前就炸，不带着一个错的 process-cmd 跑 20 分钟）。
    """
    endpoint = db["standalone"] if db["deploymentType"] == "standalone" else db["distributed"]
    if not endpoint:
        raise ConfigError("库 %s 没有可用的连接信息，无法自动推荐 process-cmd" % db["key"])
    ip = as_text(endpoint.get("ip"))
    port = as_text(endpoint.get("port"))
    if not PORT_RE.match(port):
        raise ConfigError("库 %s 的端口不是数字（%r），无法探测进程" % (db["key"], port))
    agent = "http://%s:%s" % (ip, as_text(db.get("agentPort")) or DEFAULT_AGENT_PORT)

    host_rows = _probe_rows(agent_exec(
        agent, _PROCESS_PROBE_HOST.replace("__PORT__", port), timeout=25))
    listener = next((r for r in host_rows if r["kind"] == "LISTENER"), None)
    if listener is None:
        raise ConfigError("在 %s 上执行进程探测没有任何输出（agent 版本不支持？）" % agent)
    if listener["pid"] in ("", "0"):
        raise ConfigError(
            "在 %s 上找不到监听 %s:%s 的进程（ss 没结果）。"
            "确认库确实在这个地址上监听；或不用 --auto-process-target，在编排里显式写 process-cmd"
            % (ip, ip, port))
    arg0 = listener["cmdline"].split()[0] if listener["cmdline"].split() else ""
    if not arg0:
        raise ConfigError("监听 %s:%s 的进程（pid=%s）命令行是空的，无法推荐"
                          % (ip, port, listener["pid"]))

    container = as_text(db.get("container")).strip()
    if container:
        if not CONTAINER_RE.match(container):
            raise ConfigError("库 %s 的 container 名不合法：%r" % (db["key"], container))
        cid = as_text(agent_exec(agent, "docker inspect -f '{{.Id}}' %s 2>/dev/null"
                                 % shlex.quote(container), timeout=20)).strip()
        cid = next((tok for tok in cid.split() if re.match(r"^[0-9a-f]{12,64}$", tok)), "")
        if not cid:
            raise ConfigError("库 %s 配了容器 %s，但在宿主机上查不到它的 Id"
                              % (db["key"], container))
        if cid not in listener["cgroup"]:
            raise ConfigError(
                "库 %s 的端口 %s 由 pid=%s 监听，但它的 cgroup 是 %r，不属于容器 %s；"
                "说明端口不是这个容器在监听（host 网络之外的部署方式），"
                "自动推荐不适用，请在编排里显式写 process-cmd"
                % (db["key"], port, listener["pid"], listener["cgroup"], container))
        rows = _probe_rows(agent_exec(
            agent, "docker exec %s sh -c %s" % (shlex.quote(container),
                                                shlex.quote(_PROCESS_LIST_CONTAINER)),
            timeout=30))
        matches = [r for r in rows if r["kind"] == "P" and arg0 in r["cmdline"]]
        if not matches:
            raise ConfigError("在容器 %s 里用 %r 匹配不到任何进程（监听进程 pid=%s 在宿主机侧）"
                              % (container, arg0, listener["pid"]))
        return {
            "cmd": arg0,
            "count": len(matches),
            "context": "容器 %s 内" % container,
            "instance": listener["cgroup"],
            "sample": matches[0]["cmdline"][:90],
        }

    matches = [r for r in host_rows if r["kind"] == "P" and arg0 in r["cmdline"]]
    if not matches:
        raise ConfigError("在宿主机上用 %r 匹配不到任何进程（监听进程 pid=%s）"
                          % (arg0, listener["pid"]))
    groups = {}
    for row in matches:
        groups.setdefault(row["cgroup"], []).append(row)
    if len(groups) > 1:
        detail = "；".join("%s（%d 个，如 pid=%s %s）"
                           % (cg or "(空)", len(rows), rows[0]["pid"], rows[0]["cmdline"][:60])
                           for cg, rows in groups.items())
        raise ConfigError(
            "库 %s 在宿主机上，进程名 %r 会同时命中多个实例：%s。"
            "直接挂起会误伤别的库 —— 请在编排里显式写一个只属于本实例的 process-cmd"
            "（例如带上绝对路径），或给这个库配 container"
            % (db["key"], arg0, detail))
    if listener["pid"] not in [r["pid"] for r in matches]:
        raise ConfigError("匹配集合里没有监听进程 pid=%s，探测结果自相矛盾，放弃自动推荐"
                          % listener["pid"])
    return {
        "cmd": arg0,
        "count": len(matches),
        "context": "宿主机",
        "instance": matches[0]["cgroup"],
        "sample": matches[0]["cmdline"][:90],
    }


def apply_process_target(db, stages, cache, log):
    """把编排里 process.stop 的 process-cmd 换成现场探测结果（每个库探一次）。"""
    targets = [fault for stage in stages for fault in stage["faults"]
               if fault["nameKey"] == PROCESS_STOP_NAME_KEY]
    if not targets:
        return
    found = cache.get(db["key"])
    if found is None:
        found = discover_db_process(db)
        cache[db["key"]] = found
        log("  ----  %s：process.stop 自动目标 = %r（%s，探测时命中 %d 个进程，实例 %s）"
            % (db["key"], found["cmd"], found["context"], found["count"],
               found["instance"] or "-"))
        # 命中数只是提交那一刻的进程表快照：PG 的 backend 随压测连接数增长，
        # chaosblade 是注入那一刻按子串重新匹配的，所以真正挂起的是当时全部匹配进程。
        log("      样例：%s" % found["sample"])
    for fault in targets:
        fault["params"][PROCESS_CMD_PARAM] = found["cmd"]


# ---------------------------------------------------------------------------
# 监控（res-manager）
#
# 引擎只负责压测和注入，曲线在 manager 那边：压测开始时建一个采集会话，结束后归档，
# 再用归档曲线回答「这个故障到底有没有在资源上留下痕迹」。manager 或 Prometheus 不可用
# 时整条链路自动降级成"只跑引擎"，不阻塞压测。
# ---------------------------------------------------------------------------
def unwrap_manager(payload):
    """manager 的接口统一包了一层 {code,msg,data}；失败时 data 为空。"""
    if isinstance(payload, dict) and "code" in payload and "data" in payload:
        return payload.get("data")
    return payload


class Monitor(object):
    def __init__(self, base_url, metric_ids, log, enabled=True):
        self.api = Engine(base_url)
        self.metric_ids = list(metric_ids)
        self.log = log
        self.enabled = enabled
        self.available = False
        self.targets = []
        self.reason = ""
        self.attempts = 0
        self.next_probe_at = 0.0

    def _probe(self):
        """探测 manager 的监控能力。

        同一轮内先隔 MONITOR_PROBE_RETRY_DELAY_SEC 重试，最多
        MONITOR_PROBE_ATTEMPTS 次；仍失败才冷却 MONITOR_PROBE_RETRY_SEC，
        到下一轮再试。第一个故障一般在负载开始后 30 秒，几秒重试不影响注入。
        失败原因留在 self.reason 里，由 finish_monitor 写进 CSV 的
        monitor_evidence 列。
        """
        if not self.enabled:
            self.reason = "命令行 --no-monitor"
            return False
        if self.available:
            return True
        now = time.monotonic()
        if now < self.next_probe_at:
            return False
        self.attempts += 1
        last_reason = ""
        for try_no in range(1, MONITOR_PROBE_ATTEMPTS + 1):
            try:
                cap = unwrap_manager(
                    self.api.get_json("/monitoring/capabilities", timeout=10)) or {}
            except Exception as exc:
                last_reason = "连不上 manager %s：%s" % (self.api.base, exc)
            else:
                self.targets = [t.get("targetId") for t in cap.get("targets") or []
                                if t.get("targetId")]
                if not cap.get("monitoringEnabled"):
                    # 配置层面关闭了监控，重试没有意义，直接进入下一轮冷却。
                    self.reason = "manager 的监控功能未启用"
                    self.available = False
                    self._defer_probe(time.monotonic())
                    return False
                if not cap.get("prometheusAvailable"):
                    last_reason = "Prometheus 未就绪"
                else:
                    last_reason = ""

            if not last_reason:
                self.reason = ""
                self.available = True
                if self.attempts > 1:
                    self.log("  ! 监控已恢复（第 %d 次探测成功），从这一轮起开始采集曲线。"
                             % self.attempts)
                elif try_no > 1:
                    self.log("  · 监控探测第 %d 次尝试成功，本轮开始采集。" % try_no)
                return True

            if try_no < MONITOR_PROBE_ATTEMPTS:
                self.log("  ! 监控探测失败（%s），%d 秒后重试（%d/%d）…"
                         % (last_reason, MONITOR_PROBE_RETRY_DELAY_SEC,
                            try_no + 1, MONITOR_PROBE_ATTEMPTS))
                time.sleep(MONITOR_PROBE_RETRY_DELAY_SEC)

        self.reason = last_reason or "探测失败（原因未记录）"
        self.available = False
        self._defer_probe(time.monotonic())
        return False

    def _defer_probe(self, now):
        """失败后进入冷却，下一个用例开始时再试；冷却期间的用例直接按不可用处理。"""
        self.next_probe_at = now + MONITOR_PROBE_RETRY_SEC
        self.log("  ! 监控不可用（%s）。%d 秒后重试；本轮不做曲线判据，压测照跑。"
                 % (self.reason, MONITOR_PROBE_RETRY_SEC))

    def start(self, run_id, container, db_type=None):
        if not self._probe():
            return "disabled"
        body = {
            "runId": run_id,
            "targets": self.targets,
            "metricIds": self.metric_ids,
            "uiRefreshSec": 5,
            "queryStepSec": 1,
            "archiveEnabled": True,
        }
        if container:
            body["containerName"] = container
        # db.* 指标同时有 og_* 与 mysql_* 两套序列，manager 靠 dbType 选数据源；
        # 不传的话会沿用旧行为（or 优先命中 og_*，MySQL 的故障看不到）。
        if db_type:
            body["dbType"] = db_type
        try:
            session = unwrap_manager(
                self.api.post_json("/monitoring/sessions", body, timeout=30)) or {}
        except Exception as exc:
            self.log("  ! 监控会话没建起来（%s），这一轮没有曲线判据，压测继续。" % exc)
            self.available = False
            self.reason = "会话创建失败：%s" % exc
            self.next_probe_at = time.monotonic() + MONITOR_PROBE_RETRY_SEC
            return "failed"
        if not session.get("runId"):
            self.log("  ! 监控会话没建起来（manager 返回空），这一轮没有曲线判据，压测继续。")
            self.available = False
            self.reason = "会话创建失败：manager 返回空会话"
            self.next_probe_at = time.monotonic() + MONITOR_PROBE_RETRY_SEC
            return "failed"
        if session.get("status") == "DEGRADED":
            self.log("  ! 监控会话建了但 Agent 没接上（DEGRADED），曲线可能不完整。")
        return "ok"

    def keepalive(self, run_id):
        """压测期间定期问一次会话，顺便把 session TTL 续上（默认 900s）。"""
        if not self.available:
            return
        try:
            self.api.get_json("/monitoring/sessions/%s" % run_id, timeout=10)
        except Exception:
            pass

    def complete(self, run_id):
        if not self.available:
            return
        try:
            unwrap_manager(
                self.api.post_json("/monitoring/sessions/%s/complete" % run_id, {}, timeout=120))
        except Exception as exc:
            self.log("  ! 监控归档失败（%s）；Prometheus 保留期内还能补取。" % exc)

    def series(self, run_id):
        """取这次运行的曲线：优先读归档，归档没写成再问 Prometheus。"""
        for path in ("/monitoring/history/%s/series" % run_id,
                     "/monitoring/sessions/%s/series" % run_id):
            try:
                data = unwrap_manager(self.api.get_json(path, timeout=60))
            except Exception:
                continue
            if data and data.get("series"):
                return data
        return None


def fetch_catalog(engine):
    """拉故障目录：nameKey / defaultDuration / 参数默认值。OS 级故障不在里面。"""
    items = engine.get_json("/web/faults/catalog")
    if not isinstance(items, list):
        raise ConfigError("引擎返回的故障目录格式不对：%r" % (items,))
    catalog = {}
    for item in items:
        key = item.get("nameKey")
        if key:
            catalog[key] = item
    return catalog


# ---------------------------------------------------------------------------
# 读 YAML
# ---------------------------------------------------------------------------
def read_yaml(path):
    if not os.path.isfile(path):
        raise ConfigError("找不到文件：%s" % path)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return yaml.safe_load(handle)
    except yaml.YAMLError as exc:
        raise ConfigError("解析 YAML 失败：%s\n%s" % (path, exc))


def as_text(value):
    """前端表单发出去的都是字符串（端口、参数值），这里统一成同款。"""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value).strip()


def require_str(value, where):
    text = as_text(value)
    if not text:
        raise ConfigError("%s 不能为空" % where)
    return text


def path_key(value, where):
    """key 会被拼进结果目录名（<库key>__<负载key>__<编排key>），必须能安全当路径片段。"""
    text = require_str(value, where)
    if text in (".", "..") or "/" in text or "\\" in text:
        raise ConfigError("%s 不能是 %r：会拼进结果目录名，不能含路径分隔符" % (where, text))
    return text


# 引擎给运行名做净化时保留的字符集（见 StartByFormService.buildRunId）
RUN_ID_UNSAFE = re.compile(r"[^A-Za-z0-9_.+\-一-龥]")


def combo_name(db, workload, orchestration):
    """一轮测试的组合名：数据库 key × 负载 key × 编排 key。

    这个名字同时用作两处：
      - 发给引擎的 testName → 引擎的运行名变成 <负载名>_<组合名>_<时间戳>，
        于是归档目录 results/<运行名>/ 自带实验环境信息；
      - 脚本自己的结果目录名。
    引擎会把认不出的字符换成 '-'（StartByFormService.buildRunId），
    这里按同一套规则先洗一遍，保证两边看到的目录名一模一样。
    """
    raw = "%s__%s__%s" % (db["key"], workload["key"], orchestration["key"])
    return RUN_ID_UNSAFE.sub("-", raw)


def batch_dir_name(plan, stamp):
    """本次调用的批次目录名：<时间戳>_<库key>_<负载key>_<总编排key>。

    一次调用只有一个批次目录，所以三段都能是多个值（用 + 连起来），
    比如一次选了 2 个库 1 个负载 2 个编排文件 → 20261001_122713_db-a+db-b_w1_e1+e2。

    取值来源是 **plan**（已经过 --only-db / --only-workload / --only-orchestration
    筛选），所以目录名只反映这一批真正要跑的，而不是配置文件里全写了什么。
    """
    def uniq(pick):
        seen = []
        for item in plan:
            value = pick(item)
            if value not in seen:
                seen.append(value)
        return "+".join(seen)

    return RUN_ID_UNSAFE.sub("-", "%s_%s_%s_%s" % (
        stamp,
        uniq(lambda item: item["db"]["key"]),
        uniq(lambda item: item["workload"]["key"]),
        uniq(lambda item: item["orchestration"]["suite"])))


# ---------------------------------------------------------------------------
# 1) 数据库配置
# ---------------------------------------------------------------------------
def load_databases(path):
    data = read_yaml(path) or {}
    items = data.get("databases")
    if not isinstance(items, list) or not items:
        raise ConfigError("%s：顶层需要一个非空的 databases: 列表" % path)

    result = []
    seen = set()
    for index, raw in enumerate(items):
        where = "%s 第 %d 个数据库" % (path, index + 1)
        if not isinstance(raw, dict):
            raise ConfigError("%s：应该是一个映射" % where)

        key = path_key(raw.get("key"), where + " 的 key")
        if key in seen:
            raise ConfigError("%s：key 重复：%s" % (path, key))
        seen.add(key)

        db_type = require_str(raw.get("dbType"), "%s(%s) 的 dbType" % (where, key)).lower()
        if db_type not in DB_TYPES:
            raise ConfigError("%s(%s)：dbType 只能是 %s" % (where, key, "/".join(DB_TYPES)))

        deployment = as_text(raw.get("deploymentType") or "standalone").lower()
        if deployment not in DEPLOYMENT_TYPES:
            raise ConfigError("%s(%s)：deploymentType 只能是 %s" % (where, key, "/".join(DEPLOYMENT_TYPES)))
        if deployment == "distributed" and db_type not in ("oceanbase", "gaussdb"):
            raise ConfigError("%s(%s)：分布式只支持 oceanbase / gaussdb" % (where, key))

        def endpoint(field):
            block = raw.get(field) or {}
            if not isinstance(block, dict):
                raise ConfigError("%s(%s)：%s 应该是一个映射" % (where, key, field))
            return {
                "ip": require_str(block.get("ip"), "%s(%s).%s.ip" % (where, key, field)),
                "port": require_str(block.get("port"), "%s(%s).%s.port" % (where, key, field)),
                "username": require_str(block.get("username"), "%s(%s).%s.username" % (where, key, field)),
                "password": as_text(block.get("password")),
            }

        # 只校验真正会用到的那份连接信息：单机不看 distributed，分布式不看 standalone
        db = {
            "key": key,
            "dbType": db_type,
            "deploymentType": deployment,
            "standalone": endpoint("standalone") if deployment == "standalone" else {},
            "distributed": endpoint("distributed") if deployment == "distributed" else {},
            "dbParams": raw.get("dbParams") or {},
            # 可选：chaosblade 类故障通过 docker exec 在该容器内注入（网络类故障仍走宿主机）
            "container": as_text(raw.get("container")).strip(),
            # 可选：--auto-process-target 探针走这台机器的 agent（默认 8000，与引擎一致）
            "agentPort": as_text(raw.get("agentPort")).strip() or DEFAULT_AGENT_PORT,
        }
        validate_db_params(db, where)
        result.append(db)
    return result


def validate_db_params(db, where):
    """校验「应用配置」那部分，规则与前端 DatabaseConfig 一致。"""
    params = db["dbParams"]
    if not params:
        return
    if not isinstance(params, dict):
        raise ConfigError("%s(%s)：dbParams 应该是一个映射" % (where, db["key"]))

    buffer_pool = as_text(params.get("bufferPool"))
    max_conn = as_text(params.get("maxConnections"))
    if not buffer_pool and not max_conn:
        raise ConfigError("%s(%s)：dbParams 两个参数都空，没必要应用" % (where, db["key"]))
    if db["deploymentType"] != "standalone":
        raise ConfigError("%s(%s)：只有单机库能改参数（前端同样限制）" % (where, db["key"]))
    if db["dbType"] not in PARAM_SUPPORTED_TYPES:
        raise ConfigError("%s(%s)：只有单机 %s 能改参数"
                          % (where, db["key"], "/".join(PARAM_SUPPORTED_TYPES)))
    if db["dbType"] in NEEDS_RESTART_TYPES and not as_text(params.get("restartCommand")):
        raise ConfigError("%s(%s)：这个库的这两个参数必须重启才生效，请在 dbParams.restartCommand 给出重启命令；"
                          "库跑在容器里就写 nohup docker restart <容器名> >/tmp/resbench_restart.log 2>&1 &"
                          "（必须 detach，agent 等单条命令的上限是 30 秒）"
                          % (where, db["key"]))


# ---------------------------------------------------------------------------
# 2) 背景负载配置
# ---------------------------------------------------------------------------
def load_workloads(path):
    data = read_yaml(path) or {}
    items = data.get("workloads")
    if not isinstance(items, list) or not items:
        raise ConfigError("%s：顶层需要一个非空的 workloads: 列表" % path)

    result = []
    seen = set()
    for index, raw in enumerate(items):
        where = "%s 第 %d 个负载" % (path, index + 1)
        if not isinstance(raw, dict):
            raise ConfigError("%s：应该是一个映射" % where)

        key = path_key(raw.get("key"), where + " 的 key")
        if key in seen:
            raise ConfigError("%s：key 重复：%s" % (path, key))
        seen.add(key)

        name = require_str(raw.get("name"), "%s(%s) 的 name" % (where, key)).lower()
        if name not in WORKLOAD_NAMES:
            raise ConfigError("%s(%s)：name 只能是 %s"
                              % (where, key, "/".join(WORKLOAD_NAMES)))

        params = raw.get("params") or {}
        if not isinstance(params, dict):
            raise ConfigError("%s(%s)：params 应该是一个映射" % (where, key))

        result.append({"key": key, "name": name, "params": params})
    return result


# ---------------------------------------------------------------------------
# 3) 故障编排配置（一个文件 = 一个或多个编排）
# ---------------------------------------------------------------------------
def suite_key_of(data, path):
    """编排文件的**总编排名**（一个文件一个）：顶层 name:，不写就用文件名。

    它只用来给本次调用的批次目录命名（<时间戳>_<库key>_<负载key>_<总编排key>），
    跟编排自己的 key 是两回事 —— key 仍然单独区分每一轮，不受它影响。
    """
    raw = data.get("name") if isinstance(data, dict) else None
    if raw is None or as_text(raw) == "":
        raw = os.path.splitext(os.path.basename(path))[0]
    return RUN_ID_UNSAFE.sub("-", path_key(raw, "%s 的顶层 name" % path))


def parse_orchestration(raw, where, path, default_key=None, suite=None):
    """解析一个编排：key + batches。每一批同时注入，批与批之间串行。

    批次可以写 ``at: 32`` 指定这一批的绝对注入时刻（秒，相对故障运行器启动）。
    写了 at 的批次不再参与“首批 +60 / 批间 +30”的固定推算；没写的批次保持原行为。

    suite 是这个编排所属文件的总编排名（见 suite_key_of），只用于批次目录命名。
    """
    if not isinstance(raw, dict):
        raise ConfigError("%s：应该是一个映射，形如 - key: … / batches: […]" % where)

    key_raw = raw.get("key")
    if (key_raw is None or key_raw == "") and default_key is not None:
        key_raw = default_key
    if key_raw is None or as_text(key_raw) == "":
        raise ConfigError("%s：必须有 key（结果目录名和 --only-orchestration 都靠它区分）" % where)
    key = path_key(key_raw, "%s 的 key" % where)

    batches_raw = raw.get("batches")
    if batches_raw is None:
        batches_raw = []
    if not isinstance(batches_raw, list):
        raise ConfigError("%s：batches: 应该是一个列表（可以为空，表示这一轮不注入故障）" % where)

    batches = []
    for index, raw_batch in enumerate(batches_raw):
        bwhere = "%s 第 %d 批" % (where, index + 1)
        if not isinstance(raw_batch, dict):
            raise ConfigError("%s：应该是一个映射，形如 - faults: [...]" % bwhere)
        faults = raw_batch.get("faults")
        if not isinstance(faults, list) or not faults:
            raise ConfigError("%s：需要一个非空的 faults: 列表" % bwhere)

        parsed_faults = []
        for findex, fault in enumerate(faults):
            fwhere = "%s 第 %d 个故障" % (bwhere, findex + 1)
            if not isinstance(fault, dict):
                raise ConfigError("%s：应该是一个映射" % fwhere)
            if "at" in fault:
                raise ConfigError(
                    "%s：at 写在这一批的批次层（- at: 30），不要写在单个故障里" % fwhere)
            name_key = require_str(fault.get("nameKey"), fwhere + " 的 nameKey")
            params = fault.get("params") or {}
            if not isinstance(params, dict):
                raise ConfigError("%s(%s)：params 应该是一个映射" % (fwhere, name_key))
            parsed_faults.append({"nameKey": name_key, "params": params})

        override = raw_batch.get("duration")
        at = None
        at_raw = raw_batch.get("at")
        if at_raw not in (None, ""):
            try:
                at = int(float(at_raw))
            except (TypeError, ValueError):
                raise ConfigError("%s：at 必须是秒数，收到 %r" % (bwhere, at_raw))
            if at < 0:
                raise ConfigError("%s：at 不能为负数，收到 %s" % (bwhere, at))
        batches.append({
            "faults": parsed_faults,
            "duration": None if override in (None, "") else int(float(override)),
            "at": at,
        })
    return {"key": key, "name": key, "suite": suite, "path": path, "batches": batches}


def load_orchestrations(path):
    """编排文件 → 编排列表。一个文件可以放多个编排。

    三种顶层写法：
        name: e1              # 可选：这个文件的**总编排名**，只给批次目录命名用
        orchestrations:       # 一个文件多个编排，每项必须写 key（结果目录名靠它区分）
          - key: combo-A
            batches:
              - at: 32        # 可选：这一批的绝对注入时刻（秒）
                faults: [...] # 同一批内同时注入
              - faults: [...] # 不写 at：沿用下一批（上一批结束 +30s）
          - key: combo-B
            batches:
              - faults: [...] # 不同批之间串行（上一批结束 +30s）

        name: e1              # 可选，同上
        key: combo-A          # 单编排文件，等价于上面只写一项
        batches:
          - faults: [...]

        - faults: [...]       # 直接写批次列表，key 取文件名（旧写法）

    顶层 name: 只影响**本次调用的批次目录名**（<时间戳>_<库key>_<负载key>_<name>），
    不写就用文件名；它和每个编排自己的 key 无关，--only-orchestration 仍按 key 匹配。
    """
    data = read_yaml(path)
    if data is None:
        raise ConfigError("%s：文件是空的" % path)

    suite = suite_key_of(data, path)

    if isinstance(data, dict) and "orchestrations" in data:
        items = data.get("orchestrations")
        if not isinstance(items, list) or not items:
            raise ConfigError("%s：orchestrations: 需要一个非空的列表" % path)
        return [parse_orchestration(item, "%s 第 %d 个编排" % (path, index + 1), path,
                                    suite=suite)
                for index, item in enumerate(items)]

    if isinstance(data, dict):
        if data.get("batches") is None:
            raise ConfigError(
                "%s：顶层应该是 orchestrations: 列表（一个文件多个编排），"
                "或者 key: + batches:（单编排），或者直接写一个批次列表" % path)
        return [parse_orchestration(data, path, path, suite=suite)]

    if isinstance(data, list):
        name = os.path.splitext(os.path.basename(path))[0]
        return [parse_orchestration({"batches": data}, path, path, default_key=name,
                                    suite=suite)]

    raise ConfigError("%s：顶层应该是 orchestrations: 列表、key/batches 映射，或者一个批次列表" % path)


# ---------------------------------------------------------------------------
# 故障参数的落地（补默认值 / 校验必填 / 定 duration）
# ---------------------------------------------------------------------------
def materialize_fault(fault, catalog, batch_duration, auto_file_targets=False):
    """把一个故障变成发给引擎的 params。规则与前端表单一致：缺的用目录默认值填。

    auto_file_targets 打开时，文件类故障的路径参数改写成 @auto: 哨兵（见
    FILE_FAULT_AUTO_TARGETS）——写在编排里的路径会被顶掉，mark / content / duration 不动。
    返回 (duration, params, 被替换的参数)。
    """
    name_key = fault["nameKey"]
    definition = catalog.get(name_key)
    params = {as_text(k): as_text(v) for k, v in fault["params"].items()}

    substituted = {}
    if auto_file_targets:
        for param_name, sentinel in (FILE_FAULT_AUTO_TARGETS.get(name_key) or {}).items():
            substituted[param_name] = sentinel
            params[param_name] = sentinel

    if definition:
        declare = definition.get("params") or []
        for spec in declare:
            param_name = as_text(spec.get("paramName"))
            if not param_name or param_name in params:
                continue
            default = spec.get("defaultValue")
            if default is not None and as_text(default) != "":
                params[param_name] = as_text(default)
            elif spec.get("required"):
                raise ConfigError(
                    "故障 %s 的必填参数 %s 没有给值，目录里也没有默认值，请在编排文件里显式写"
                    % (name_key, param_name))

    # duration 优先级：批级覆盖 > 故障自带 > 目录默认时长
    duration = None
    if batch_duration is not None:
        duration = batch_duration
    elif params.get("duration"):
        duration = int(float(params["duration"]))
    elif definition and definition.get("defaultDuration") is not None:
        duration = int(definition["defaultDuration"])

    if duration is None or duration <= 0:
        raise ConfigError(
            "故障 %s 不知道要持续多久：它不在引擎故障目录里（OS 级 chaosblade 故障就是这种），"
            "请在编排文件里显式写 params.duration" % name_key)

    params["duration"] = str(duration)
    return duration, params, substituted


def resolve_plan(orchestration, catalog, auto_file_targets=False):
    """算出这个编排的时刻表。返回 (批次列表, 计划终点秒)。

    stage["base"]/["end"] 是相对故障运行器启动（负载预热完成）的秒数。
    批次写了 at: 时 base 就是 at（绝对时刻），否则继续用首批 +60s / 批间 +30s 推算。
    """
    base = FIRST_BATCH_BASE_SEC
    plan_end = 0
    stages = []
    for batch in orchestration["batches"]:
        durations = []
        faults = []
        for fault in batch["faults"]:
            duration, params, substituted = materialize_fault(
                fault, catalog, batch["duration"], auto_file_targets)
            durations.append(duration)
            item = {"nameKey": fault["nameKey"], "params": params}
            if substituted:
                item["autoTargets"] = substituted
            faults.append(item)
        at = batch.get("at")
        stage_base = at if at is not None else base
        stage_end = stage_base + max(durations)
        stages.append({
            "base": stage_base,
            "end": stage_end,
            "absolute": at is not None,
            "faults": faults,
        })
        plan_end = max(plan_end, stage_end)
        # 绝对时刻批次可能写在后面（例如恢复 plan 里的 67/74/77 错峰），
        # 下一个相对批次仍应从已有的最晚终点继续，不能倒退。
        base = max(base, stage_end + BATCH_GAP_SEC)
    if not stages:
        plan_end = FIRST_BATCH_BASE_SEC
    return stages, plan_end


def compute_workload_duration(workload, plan_end, tail_sec, has_faults=True):
    """负载时长 = 故障计划终点 + 尾巴。只有**没有故障**的编排才用配置里写死的 duration。

    以前取 max(配置里写的, 算出来的)，等于把配置值当下限：把故障时长从 60s 改成 30s 后，
    计划终点已经降到 210s，负载却还是照旧跑 320s（旧计划留下的值），dry-run 里也看不出
    原因。现在有故障就完全按计划算；想拉长稳态尾巴请调 --tail，别再写 duration。
    """
    planned = plan_end + tail_sec
    declared = workload["params"].get("duration")
    declared_value = None
    if declared not in (None, ""):
        try:
            declared_value = int(float(as_text(declared)))
        except ValueError:
            raise ConfigError("负载 %s 的 duration 不是数字：%r" % (workload["key"], declared))
    if declared_value is not None and declared_value <= 0:
        raise ConfigError("负载 %s 的 duration 必须大于 0" % workload["key"])
    if has_faults or declared_value is None:
        return planned
    return declared_value


# ---------------------------------------------------------------------------
# 组装 startByForm 请求体
# ---------------------------------------------------------------------------
def build_request(db, workload, stages, workload_duration, reload_flag, applied_db_params,
                  test_name):
    fault_items = []
    for group_index, stage in enumerate(stages, start=1):
        group_key = "group-%d" % group_index
        for fault in stage["faults"]:
            item = {
                "nameKey": fault["nameKey"],
                "groupKey": group_key,
                "params": dict(fault["params"]),
            }
            # 只有写了 at 的批次才发绝对时刻；其余保持旧请求体，引擎走原有的组内偏移。
            if stage.get("absolute"):
                item["planningSeconds"] = int(stage["base"])
            fault_items.append(item)

    workload_params = {as_text(k): as_text(v) for k, v in workload["params"].items()}
    workload_params["duration"] = str(workload_duration)
    is_tpcc = workload["name"] == "tpcc"
    if is_tpcc:
        workload_params["reload"] = "true" if reload_flag else "false"

    database = {
        "deploymentType": db["deploymentType"],
        "standalone": dict(db["standalone"]),
        "primaryStandby": {"nodes": []},
        "distributed": dict(db["distributed"]),
        # 引擎不拿它做执行，只用于归档：run-meta.json 靠它记录这轮压测时库上的真实配置
        "params": dict(applied_db_params),
    }

    request = {
        "dbType": db["dbType"],
        "workload": {"name": workload["name"], "params": workload_params},
        "database": database,
        "faults": fault_items,
        # 组合名 → 引擎运行名带 <负载名>_<组合名>_<时间戳>，归档目录一眼看出实验环境。
        # 前端表单目前不填这个字段（DTO 里有，历史页认它做「测试名」），是脚本主动补的。
        "testName": test_name,
        # 下面两项前端是写死的（buildRequestBody），脚本照搬，不开放配置
        "autoTimingMode": "fixed-time",
        "runOptions": {
            "monitorIntervalSec": 1,
            "create": True,
            "load": True,
            "execute": True,
            "reload": reload_flag if is_tpcc else False,
        },
    }
    # 容器名配在 databases[].container 下（一次运行只有一个）：
    # 非空时引擎把 chaosblade 类故障套 docker exec 在容器内注入；网络类故障仍走宿主机。
    container = str(db.get("container") or "").strip()
    if container:
        request["containerName"] = container
    return request


# ---------------------------------------------------------------------------
# tpcc 仓数预检：POST /web/bench/tpcc/warehouse/verify
#
# 引擎建不建仓只看 runOptions.reload（StartByFormService 里 reload 不为真就直接
# create=false/load=false），**从不问库里已经有多少仓**。而 1000 仓重建一次实测 4h08m。
# 于是"库里躺着上一轮建好的仓、脚本又闷头重建一遍"是必然发生的。
# 所以每个 (库, tpcc负载) 的**第一轮**先照一次镜子：仓数对得上就直接跑，省下这几小时。
# ---------------------------------------------------------------------------
VERIFY_PATH = "/web/bench/tpcc/warehouse/verify"
WAREHOUSE_NUMBER_RE = re.compile(r"^\d+$")   # 与前端 /^\d+$/ 同口径

VERDICT_READY = "ready"            # 仓数一致：数据已就绪，首轮不必建仓
VERDICT_INCOMPLETE = "incomplete"  # 仓数一致但 district 缺：行为同 ready，额外警告
VERDICT_REBUILD = "rebuild"        # 仓数不符 / 尚未建仓
VERDICT_UNKNOWN = "unknown"        # 判不了：按配置的 reload 走


def configured_warehouses(workload):
    """读负载配的 warehouses（正整数）。取不到返回 None —— 绝不替它默认 1。"""
    raw = as_text(workload["params"].get("warehouses"))
    if not WAREHOUSE_NUMBER_RE.match(raw):
        return None
    value = int(raw)
    return value if value > 0 else None


def verify_warehouses(engine, db, workload, log, timeout=120):
    """查库里实际的 tpcc 仓数，与配置比对，返回 (verdict, detail)。

    所有"看不到"的情况一律归 UNKNOWN —— 「看不到」不等于「仓数不符」，
    更不能因为连不上库就把库里已有的数据当没建仓、去重建一遍把它冲掉。
    """
    configured = configured_warehouses(workload)
    if configured is None:
        return VERDICT_UNKNOWN, (
            "负载 %s 没有可用的 warehouses（要正整数），无法比对；"
            "引擎真跑时按默认 1 仓处理，这里不替它默认（那样只会报出一个假的不一致）"
            % workload["key"])

    body = {
        "dbType": db["dbType"],
        "deploymentType": db["deploymentType"],
        "standalone": dict(db["standalone"]),     # 分布式时是 {}
        "distributed": dict(db["distributed"]),   # 单机时是 {}
        "params": {"warehouses": str(configured)},
    }
    try:
        resp = engine.post_json(VERIFY_PATH, body, timeout=timeout) or {}
    except requests.HTTPError as exc:
        code = getattr(getattr(exc, "response", None), "status_code", None)
        if code == 404:
            return VERDICT_UNKNOWN, "引擎没有仓数检查接口（旧构建？），跳过预检"
        return VERDICT_UNKNOWN, "仓数预检请求失败（HTTP %s）：%s" % (code, exc)
    except Exception as exc:
        return VERDICT_UNKNOWN, "仓数预检请求失败：%s" % exc

    # 引擎上有任务在跑时计数是流动的，这一眼不可信
    if resp.get("runInProgress"):
        return VERDICT_UNKNOWN, ("预检时引擎上有任务在跑，仓数可能正在变化、这一眼不可信：%s"
                                 % as_text(resp.get("message")))
    if not resp.get("success"):
        return VERDICT_UNKNOWN, "仓数预检无法判定：%s" % as_text(resp.get("message"))

    # 只看仓数（不看 district）：实际值直接与配置比，不拿 conclusion 当判据
    actual = resp.get("actualWarehouses")
    conclusion = as_text(resp.get("conclusion"))
    if actual is not None and int(actual) == configured:
        if conclusion == "INCOMPLETE":
            # 仓数相等只证明这些仓**开始装了**（warehouse 行是每个仓最先写入的），
            # 不证明装完了。按既定口径不重建，但必须把残缺数字亮出来。
            return VERDICT_INCOMPLETE, (
                "仓数与配置一致（%d），但 district 实际 %s / 期望 %s —— 疑似上次加载中断、数据不全"
                % (configured, resp.get("actualDistricts"), resp.get("expectedDistricts")))
        return VERDICT_READY, "库里 %d 仓，与配置一致（%s）" % (configured, conclusion)
    return VERDICT_REBUILD, ("库里实际 %s 仓、配置 %d 仓（%s）；%s"
                             % (actual, configured, conclusion, as_text(resp.get("message"))))


def decide_reload(yaml_reload, verdict, detail):
    """预检结论 → 要不要先把建仓单独跑一轮（预热轮），外加该打的日志。只对 tpcc 调用。"""
    if verdict == VERDICT_READY:
        return False, "  · 仓数预检：%s → 数据已就绪，首轮不建仓" % detail
    if verdict == VERDICT_INCOMPLETE:
        return False, ("  ! 仓数预检：%s\n"
                       "    仓数一致但不完整；按既定口径不重建，这一轮的指标要按「数据可能不全」来读"
                       % detail)
    if verdict == VERDICT_REBUILD:
        lines = ("  ! 仓数预检：%s\n"
                 "  ! 先跑一轮建仓预热轮（不带故障、load；建仓会清掉现有数据重建），"
                 "跑完再开始正式轮" % detail)
        if not yaml_reload:
            lines += ("\n  ! 注意：本负载配的是 reload: false，但仓数不符 —— "
                      "脚本按「不符就建仓」处理。若你是有意复用现有数据，请把这一对排除后再跑")
        return True, lines
    return yaml_reload, ("  ! 仓数预检无法判定：%s\n"
                         "    按配置里的 reload=%s 执行（不因为「看不到」就去重建）"
                         % (detail, yaml_reload))


def log_db_params_diagnosis(resp, log):
    """参数没生效时，把响应里本来就有的证据摆出来 —— 「重启到底发生没有」是看得出来的。

    读回不一致 + 引擎报 restarted=true，说明 ALTER SYSTEM 写下的值没有在这个实例上生效
    （shared_buffers / max_connections 是 postmaster 参数，只有重启才生效）。
    注意别拿 waitedSec 当判据：引擎是**命令返回之后**才开始连的，库若在命令返回前
    就已经起来，waitedSec 同样是 0（DbParamService.restartAndWait）。
    """
    mismatches = resp.get("mismatches") or []
    restarted = resp.get("restarted")
    waited = resp.get("waitedSec")
    command = as_text(resp.get("restartCommand"))

    if mismatches:
        log("    · 读回不一致：%s" % "；".join(as_text(m) for m in mismatches))
    if command:
        log("    · 下发的重启命令：%s" % command)
    if restarted is None and not mismatches:
        return
    log("    · 重启：restarted=%s，waitedSec=%s" % (restarted, waited))

    if mismatches and restarted:
        log("    ! 引擎说重启过了，但读回还是旧值 —— 这个实例并没有带着新配置重新起来：")
        log("      · 重启命令打的可能不是引擎连的这个实例/容器（容器名、端口对不上）；")
        log("      · 目标实例若是用命令行 -c 参数启动的（docker 镜像的 CMD 里很常见，"
            "如 -c shared_buffers=…），命令行优先级**高于** ALTER SYSTEM 写的 "
            "postgresql.auto.conf；")
        log("      · detach 写法（nohup … &）下 agent 报的 returncode=0 只代表命令已交出，"
            "不代表重启成功；命令若还把输出重定向进了文件（> /tmp/x.log），"
            "引擎的 restartOutput 就是空的，报错只剩数据库主机上那个文件里才有。")
        log("      想知道重启到底发生过没有，别只看 waitedSec：到库上比 "
            "pg_postmaster_start_time() 或 pg_settings 里该参数的 source 更准。")
    elif mismatches and restarted is False:
        log("    ! 参数写入了但没生效：这两个是 postmaster 参数，必须重启数据库才生效，"
            "而引擎这一次的重启没成功（restarted=false；失败原因见上面的 message，"
            "agent 的原始输出在引擎日志和 restartOutput 里）。")
    elif mismatches:
        log("    ! 参数写入了但没生效：这两个是 postmaster 参数，必须重启数据库才生效，"
            "而引擎这次没有报 restarted —— 到库上确认它到底重启过没有"
            "（比 pg_postmaster_start_time() 最直接），再看上面的 message。")


# ---------------------------------------------------------------------------
# 「应用配置」：单机数据库参数（对应前端 DatabaseConfig 的应用按钮）
# ---------------------------------------------------------------------------
def apply_db_params(engine, db, log):
    params = db["dbParams"]
    if not params:
        return {}

    body = {
        "dbType": db["dbType"],
        "deploymentType": "standalone",
        "standalone": dict(db["standalone"]),
        "params": {
            "bufferPool": as_text(params.get("bufferPool")),
            "maxConnections": as_text(params.get("maxConnections")),
        },
        "restart": {
            "command": as_text(params.get("restartCommand")),
            "waitSec": 180,
        },
        "agentPort": 8000,
    }
    try:
        resp = engine.post_json("/web/bench/db/params", body, timeout=600) or {}
    except Exception as exc:
        log("  ! 应用数据库参数时请求失败（不阻断压测）：%s" % exc)
        return {}

    if not resp.get("success"):
        log("  ! 应用数据库参数失败（不阻断压测）：%s" % resp.get("message"))
        log_db_params_diagnosis(resp, log)
        return {}

    actual_names = resp.get("actualNames") or {}
    targets = resp.get("targets") or {}
    after = resp.get("after") or {}
    applied = {}
    for semantic, actual in actual_names.items():
        value = after.get(semantic)
        if not actual or not targets.get(semantic):
            continue
        # 引擎读不到时会回 "读不到" 这个占位串，truthy 但没意义，别归档进去
        if value and str(value) != "读不到":
            applied[actual] = str(value)

    detail = ", ".join("%s=%s" % (k, v) for k, v in applied.items()) or "（无变化）"
    log("  · 数据库参数已应用：%s%s" % (detail, "，已重启" if resp.get("restarted") else ""))
    return applied


# ---------------------------------------------------------------------------
# 等一轮跑完
# ---------------------------------------------------------------------------
def parse_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def fmt_secs(seconds):
    """把秒数说成人话：371 -> "6 分 11 秒"。"""
    try:
        seconds = int(round(float(seconds)))
    except (TypeError, ValueError):
        return "?"
    if seconds < 60:
        return "%d 秒" % seconds
    if seconds < 3600:
        return "%d 分 %d 秒" % (seconds // 60, seconds % 60)
    return "%d 小时 %d 分" % (seconds // 3600, (seconds % 3600) // 60)


def archive_of(engine, run_id):
    """这一轮在归档里的那一行；还没落盘就返回 None。"""
    return fetch_history_row(engine, run_id, attempts=1)


def archive_finished(row):
    """这一轮真的跑完了没有。

    只看归档：`artifacts.complete` = summary + monitor + metric 三样都落盘
    （HistoryQueryService.java:135），负载真的跑完、指标也算完了才会齐。

    为什么不看 /web/bench/status：那个接口读的是引擎工作目录下的 tmp/job.json，
    所有任务共用这一个格子 —— 上一轮结束时留下的 end=true 会被下一轮读到，
    于是"刚提交 30 秒就报结束"。2026-10-04 那一批就是这么乱掉的（第 6 轮起
    脚本以为跑完了、其实负载还在跑，后面几轮还叠到了一起）。
    """
    if not row:
        return False
    artifacts = row.get("artifacts") or {}
    return bool(artifacts.get("complete"))


def wait_for_start(engine, run_id, submitted_at_ms, timeout_sec, poll_sec):
    """等这一轮真的开始跑：归档里出现它，且开始时刻不早于本次提交。

    startTimestamp 来自运行名里的时刻，不受 tmp/job.json 被谁覆盖的影响。
    返回 (状态, 归档行)，状态是 "started" 或 "timeout"。
    """
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        row = archive_of(engine, run_id)
        started_at = parse_int((row or {}).get("startTimestamp"))
        if row and started_at is not None and started_at >= submitted_at_ms - 10000:
            return "started", row
        time.sleep(poll_sec)
    return "timeout", None


ARCHIVE_GRACE_SEC = 180     # 归档说"已结束"但产物迟迟不齐时，再等这么久就如实报


def wait_for_end(engine, run_id, timeout_sec, poll_sec, grace_sec=ARCHIVE_GRACE_SEC, on_tick=None):
    """等这一轮跑完 —— 判据是归档齐了，不是引擎那个全局状态。

    万一归档写了"已结束"而产物一直不齐（跑崩了、指标没算出来），再等 grace_sec
    就返回，由调用方按"没跑完/产物不齐"如实报，而不是白等满整个预算。
    on_tick 每分钟回调一次（监控会话靠它续 TTL）。
    返回 (归档行, 判定)，判定是 "complete" / "incomplete" / "timeout"。
    """
    deadline = time.time() + timeout_sec
    incomplete_since = None
    last = None
    next_tick = 0
    while time.time() < deadline:
        if on_tick is not None and time.time() >= next_tick:
            on_tick()
            next_tick = time.time() + 60
        row = archive_of(engine, run_id) or last
        last = row
        if archive_finished(row):
            return row, "complete"
        if row and row.get("endTimestamp"):
            if incomplete_since is None:
                incomplete_since = time.time()
            elif time.time() - incomplete_since >= grace_sec:
                return row, "incomplete"
        else:
            incomplete_since = None
        time.sleep(poll_sec)
    return last, "timeout"


def live_workloads():
    """引擎当前有没有压测子进程在跑：[(pid, 已运行秒数), ...]。

    引擎起压测的方式是 `java @<工作目录>/target/job-launch.args`，本机 /proc 里就能看到。
    比问引擎可靠 —— 引擎那两个接口读的是同一个全局状态，上一轮的结论会被下一轮读到，
    出现"两个负载同时打一个库"时接口那边还是看不出来。
    """
    found = []
    try:
        pids = [name for name in os.listdir("/proc") if name.isdigit()]
    except OSError:
        return found
    for pid in pids:
        try:
            with open("/proc/%s/cmdline" % pid, "rb") as handle:
                cmdline = handle.read().decode("utf-8", "replace")
        except OSError:
            continue
        if "job-launch.args" in cmdline:
            found.append((int(pid), process_age_sec(pid)))
    return sorted(found)


def process_age_sec(pid):
    """进程已经跑了多久（秒）。读不到就返回 0。"""
    try:
        with open("/proc/%s/stat" % pid) as handle:
            fields = handle.read().rsplit(")", 1)[1].split()
        start_ticks = int(fields[19])          # stat 第 22 个字段：starttime
        with open("/proc/uptime") as handle:
            uptime = float(handle.read().split()[0])
        return max(0.0, uptime - start_ticks / os.sysconf("SC_CLK_TCK"))
    except (OSError, ValueError, IndexError):
        return 0.0


def engine_gate_open(engine):
    """引擎放行新任务了吗 —— 只看它自报的 end（它把终止态写进 tmp/job.json 才算放行）。

    只用于两处：stop 之后确认真的停掉了；以及被拒后等它空闲。
    判定"这一轮跑完了没有"**不要**用它，见 archive_finished。
    """
    try:
        status = engine.get_json("/web/bench/status") or {}
    except Exception:
        return False
    return bool(status.get("end"))


def engine_is_idle(engine):
    """引擎现在空闲吗：本机没有压测进程，且引擎自报放行了。

    两个条件都要 —— 只看引擎自报会被上一轮的结论骗（见 archive_finished 的注释）。
    """
    if live_workloads():
        return False
    return engine_gate_open(engine)


def describe_live_workloads(workloads):
    """"pid 74561（已跑 6 分 11 秒）、pid 74808（已跑 5 分 40 秒）"。"""
    return "、".join("pid %d（已跑 %s）" % (pid, fmt_secs(age)) for pid, age in workloads)


def describe_engine_status(engine, log):
    """把引擎自报的状态说成人话（原始字段另起一行，留给排障用）。"""
    try:
        status = engine.get_json("/web/bench/status") or {}
    except Exception as exc:
        log("    读不到引擎状态：%s" % exc)
        return None
    started = parse_int(status.get("startTimestamp"))
    reason = as_text(status.get("message"))
    if status.get("end"):
        log("    引擎自报：它现在没有在跑的任务。")
    elif started:
        log("    引擎自报：手上那个任务还没结束；它记的开始时间是 %s，已经占了 %d 分钟。"
            % (fmt_ts(started), int((time.time() * 1000 - started) / 60000)))
    else:
        log("    引擎自报：手上有个任务在跑，但没给出开始时间。")
    log("    它给的原因：%s" % (reason if reason else "（没给）"))
    log("    （引擎原始回答：end=%s，startTimestamp=%s，message=%s）"
        % (status.get("end"), as_text(status.get("startTimestamp")) or "空", reason or "空"))
    return status


def log_engine_busy_hint(log):
    """引擎被占着时的解释与解法（引擎同一时刻只收一个任务）。"""
    log("    （若你确认没有任务在跑，那多半是上次跑一半被打断留下的残骸：引擎放行新任务只看")
    log("      工作目录下那个临时状态文件 tmp/job.json，里面还写着\"curState\":\"RUNNING\"。")
    log("      引擎每次轮询状态都会重读它，所以把它改成 \"MANUAL\" 就能放行、不用重启引擎：")
    log("        backend/resbenchmark/tmp/job.json")
    log("        {\"curState\":\"MANUAL\",\"startTimeStamp\":\"<原来的>\",\"stopTimeStamp\":\"<现在>\","
        "\"success\":false}")
    log("      注意：POST /web/bench/stop 在这种「残骸」情形下清不掉它 —— 它只负责杀掉当前那个")
    log("      子进程；只有子进程真的存在并被杀，引擎 async 的收尾才会把终止态写进这个文件。")
    log("      残骸意味着已经没有子进程了，所以 stop 会返回成功、文件却照旧。）")


# ---------------------------------------------------------------------------
# 提交一轮：先确认引擎空着，再发请求
# ---------------------------------------------------------------------------
def ensure_engine_free(engine, args, log):
    """提交前先确认引擎上真的没有负载在跑。返回 True = 可以提交。

    只看引擎的接口不够：它的状态是全局一份，读到的可能是上一轮的结论。2026-10-04 那一批
    就是因此叠出了"两个负载同时打一个库"（数据的解释力当场就没了）。本机 /proc 里的
    压测子进程不会骗人，所以提交前先看它。
    """
    running = live_workloads()
    if not running:
        return True
    log("  引擎上还有压测在跑（%s），先不提交这一轮 —— 两个负载同时打一个库，数据会互相污染。"
        % describe_live_workloads(running))
    idle_deadline = time.time() + args.busy_wait
    while time.time() < idle_deadline and live_workloads():
        time.sleep(args.poll)
    running = live_workloads()
    if running:
        log("  × 等了 %s 它还在跑（%s）。再往下交只会越叠越多，这一批停在这里。"
            % (fmt_secs(args.busy_wait), describe_live_workloads(running)))
        log("    这些是引擎起的压测子进程：等它自己跑完，或者先把引擎上那个任务停掉再重跑这一批。")
        return False
    log("  · 它已经退出，继续提交这一轮。")
    return True


def submit_run(engine, body, args, log, active):
    """提交一轮，撞上「执行中」就等引擎空下来再试。返回 (响应, 提交时刻ms, 引擎被占着)。

    active 在 POST 之前就要填好：一发出引擎可能已经受理并 spawn 了子进程，而脚本这时
    还不知道 runId —— 中断正好落在这个窗口时，收尾得靠里面那几项认领这一轮。
    """
    response = None
    submitted_at_ms = 0
    engine_busy = False
    for attempt in range(3):
        submitted_at_ms = int(time.time() * 1000)
        active["submitted_at_ms"] = submitted_at_ms
        try:
            response = engine.post_json("/web/bench/startByForm", body) or {}
        except Exception as exc:
            log("  ! 提交失败：%s" % exc)
            response = None
            break
        if response.get("accepted"):
            break
        message = str(response.get("message") or "")
        if "执行中" in message:
            # 等待预算只花一次：等过一整轮还是被挡着就收摊，不再叠加、
            # 也不盲等 —— 引擎同一时刻只收一个任务，接着跑只会得到一串被挡掉的空轮。
            if attempt > 0:
                engine_busy = True
                break
            log("  引擎说它手上还有一个任务，暂时不收新的（同一时刻只收一个）。它的原话：%s" % message)
            describe_engine_status(engine, log)
            log("    等它跑完再交这一轮（最多等 %s）…" % fmt_secs(args.busy_wait))
            idle_deadline = time.time() + args.busy_wait
            while time.time() < idle_deadline and not engine_is_idle(engine):
                time.sleep(args.poll)
            if not engine_is_idle(engine):
                engine_busy = True
                break
            continue
        break
    return response, submitted_at_ms, engine_busy


# ---------------------------------------------------------------------------
# 建仓预热轮：把「重新 load」从正式测试里分出来，单独当一个阶段
#
# reload 会先 DROP + CREATE 再灌数据，文件号是那之后才生成的。而 @auto: 哨兵要在**提交
# 那一刻**解析成真实路径 —— 提交时算出来的文件号，建完仓就过期了，故障会打在已经不存在的
# 文件上（"注入成功但指标毫无变化"，最难查的那种）。所以这两件事不能出现在同一次请求里。
#
# 办法不是在引擎里延后解析，而是把建仓挪到前面去，单独占一轮：
#   ① 先查仓数（引擎的仓数预检接口），对得上就什么都不做，直接进正式轮；
#   ② 对不上就先跑这一轮预热轮 —— 不带任何故障，因此没有哨兵，reload=true 也不会被挡；
#   ③ 预热轮跑完，数据就位；正式轮 reload=false，哨兵在提交时解析出来的就是刚建好的那份文件。
# 代价是每对「库 × 负载」多一轮，好处是建仓和故障注入再也不会互相干扰，正式轮的指标
# 也不必再背着一次建仓。
# ---------------------------------------------------------------------------
def prime_test_name(db, workload, orchestration):
    """预热轮的测试名：后缀 -prime，归档里一眼看出这不是一次正式测试。"""
    return "%s-prime" % combo_name(db, workload, orchestration)


def run_prime_round(engine, db, workload, orchestration, duration, applied_db_params, args, log):
    """跑一轮「只建仓、不带故障」的预热轮。返回 True = 数据已就位。

    请求体与正式轮的差别只有一处：faults 为空、reload=true（tpcc 靠它才真的建仓）。
    负载照跑，不改成 execute=false —— 那样不产出 summary/monitor/metric，归档永远不算
    「齐」，脚本只能一直等到超时；跑满一轮是实跑验证过的形态。
    """
    test_name = prime_test_name(db, workload, orchestration)
    body = build_request(db, workload, [], duration, True, applied_db_params, test_name)
    timeout = duration + args.build_timeout
    log("  · 这一对要先建仓：单独跑一轮预热轮（不带故障、reload=true），跑完再开始第一个故障的"
        "注入。等它的上限 = 负载 %s + --build-timeout=%s = %s（仓数多就把 --build-timeout 调大）"
        % (fmt_secs(duration), fmt_secs(args.build_timeout), fmt_secs(timeout)))

    if not ensure_engine_free(engine, args, log):
        return False

    active = {"phase": "submitting", "submitted_at_ms": 0, "run_id": "",
              "label": "预热轮 %s × %s" % (db["key"], workload["key"]),
              "row": None, "row_written": False}
    RUN_STATE["active"] = active
    response, submitted_at_ms, engine_busy = submit_run(engine, body, args, log, active)

    if engine_busy:
        log("  × 引擎一直被别的任务占着，预热轮提交不上去。")
        active["phase"] = "idle"
        return False
    run_id = (response or {}).get("runId") or (response or {}).get("historyName") or ""
    if not (response or {}).get("accepted"):
        log("  × 预热轮提交未接受：%s" % ((response or {}).get("message") or "提交被拒绝"))
        active["phase"] = "idle"
        return False
    if not run_id:
        log("  × 引擎接受了预热轮但没返回 runId，无法跟踪它")
        active["phase"] = "idle"
        return False

    active["phase"] = "running"
    active["run_id"] = run_id
    log("  → 预热轮已提交：%s" % run_id)

    state, _ = wait_for_start(engine, run_id, submitted_at_ms, args.start_timeout, args.poll)
    if state == "timeout":
        log("  × 预热轮提交上去了，但 %s 内没见它开始跑（归档里一直没出现）"
            % fmt_secs(args.start_timeout))
        active["phase"] = "unknown"
        return False

    archive_row, verdict = wait_for_end(engine, run_id, timeout, args.poll)
    if verdict == "timeout":
        log("  × 预热轮等了 %s 也没跑完（建仓可能还没做完）。" % fmt_secs(timeout))
        log("    它可能还在引擎上跑。这一对的数据是否就位无法确认，本批次停在这里；"
            "把 --build-timeout 调大后重跑。")
        active["phase"] = "unknown"
        return False

    active["phase"] = "ended"
    # 预热轮看的是「仓建好了没有」，不是「产物齐不齐」：metric.json 是引擎跑完之后补算的
    # 历史指标，缺它不影响数据已经落库。2026-11-01 13:18 那轮 100 仓建仓
    # （scalefactor=100、job.json = {"curState":"GRACEFUL","success":true}）就因为差一个
    # metric.json 被判失败，把 mysql×tpcc 整组停掉 —— 建仓其实是成功的。
    success, state_text, source = resolve_final_state(engine, run_id, archive_row)
    if success is False:
        log("  × 预热轮失败了（状态 %s，判据来源 %s），数据没有建好 —— "
            "带着这个疑问往下跑，只会得到一轮打在空处的故障。"
            % (state_text or "未知", source))
        return False
    if success is None:
        # 三条路都查不到就不猜「成功」，改用仓数预检做正向确认；查不出来也不停整组。
        log("  ! 预热轮结束了，但读不到 job.json 的 success / summary 的 Final State"
            "（归档状态 %s）—— 用仓数预检确认数据是否就位。" % (state_text or "未知"))
        verify_verdict, verify_detail = verify_warehouses(engine, db, workload, log)
        if verify_verdict == VERDICT_REBUILD:
            log("  × 仓数预检：%s → 数据没建好，这一对停在这里。" % verify_detail)
            return False
        log("  · 仓数预检：%s → 按数据已就位继续（不为一个读不到的字段停掉整组测试）。"
            % verify_detail)
    elif verdict != "complete":
        log("  ! 预热轮归档不齐（缺 summary/monitor/metric 中的某几样），但它本身是正常结束的"
            "（%s = %s）—— 建仓数据已就位，继续往下跑；只是历史页上这一轮不会有完整指标。"
            % (source, state_text or "成功"))

    elapsed = (archive_row or {}).get("elapsedMs")
    log("  √ 预热轮跑完了：数据已就位（用时 %s）。下面这一轮起 reload=false，"
        "哨兵在提交时解析出来的就是这份数据。" % (fmt_secs(elapsed / 1000.0) if elapsed else "没记到"))
    label_run(engine, run_id, build_labels(db, workload, orchestration, args.labels),
              "resbench_batch.py: 建仓预热轮（不带故障）", log)
    return True


# ---------------------------------------------------------------------------
# 中断（Ctrl+C / SIGTERM）收尾
# ---------------------------------------------------------------------------
# 收尾要用的现场。循环里只往这里放收尾需要的那几样，不把 main 的局部变量全搬出来。
RUN_STATE = {
    "engine": None, "log": None, "handles": None, "writer": None,
    "active": None, "plan_len": 0, "attempted": 0, "keep_run": False, "batch_dir": "",
}

STOP_SLACK_MS = 1000       # 认领时允许的时钟偏差
STOP_CONFIRM_TRIES = 6     # 受理到子进程真正 spawn 之间有 ~9 秒空窗，第一发 stop 可能空转
STOP_CONFIRM_WAIT = 5

INTERRUPTED_RUNNING = "被中断（Ctrl+C/SIGTERM），本轮已停止，归档不完整"
INTERRUPTED_ENDED = "被中断（Ctrl+C/SIGTERM），本轮在引擎侧已结束，归档明细没来得及取回"


def stop_active_run(engine, active, log):
    """停掉**脚本自己**那一轮在引擎上的任务；分不清是不是自己的，就绝不动手。

    引擎的 currentProcess 是单例全局槽，stop 杀的就是"当前那一个" —— 而这台引擎是共用的
    （别人可能正在建仓），所以动手前必须用 startTimestamp 认领：它是引擎受理那一刻
    写进 tmp/job.json 的，比本次提交还早就说明跑着的是别人。
    """
    phase = (active or {}).get("phase")
    if phase not in ("submitting", "running", "unknown"):
        log("  本轮不在飞行中（%s），引擎上没有属于本脚本的任务要停。"
            % (phase or "无记录"))
        return True

    submitted = (active or {}).get("submitted_at_ms") or 0
    try:
        status = engine.get_json("/web/bench/status") or {}
    except Exception as exc:
        log("  ! 读不到引擎状态：%s" % exc)
        status = {}

    if status.get("end"):
        log("  引擎上已经没有任务在跑（end=true），无需停止。")
        return True

    started = parse_int(status.get("startTimestamp"))
    if started is not None and submitted and started + STOP_SLACK_MS < submitted:
        log("  ! 引擎上跑着的任务不是这一轮：startTimestamp=%s 早于本次提交 %s，**没有**动它。"
            % (started, submitted))
        return False

    if not (active or {}).get("run_id"):
        log("  · 这一轮还没拿到 runId（中断正好落在提交那一瞬），只能按 startTimestamp 认领。")
    log("  停止本轮任务：POST /web/bench/stop（body []）…")
    for attempt in range(1, STOP_CONFIRM_TRIES + 1):
        try:
            engine.post_json("/web/bench/stop", [], timeout=60)
        except Exception as exc:
            log("  ! 第 %d 次 stop 请求失败：%s" % (attempt, exc))
        stop_deadline = time.time() + STOP_CONFIRM_WAIT
        while time.time() < stop_deadline and not engine_gate_open(engine):
            time.sleep(2)
        if engine_gate_open(engine):
            log("  √ 子进程已杀掉，引擎已放行（end=true），归档里也会留下这一轮的 job.json。")
            return True
        log("  · 第 %d 次没停掉（子进程可能还没起来、这一发空转了一下），再试…" % attempt)

    log("  ! 几次都没确认停掉，手工确认：")
    log("      %s" % manual_stop_command(engine))
    return False


def manual_stop_command(engine):
    return ("curl -X POST %s/web/bench/stop -H 'Content-Type: application/json' -d '[]'"
            % getattr(engine, "base", "<引擎地址>"))


def cleanup_after_interrupt(state):
    """中断收尾。顺序是有意的：先把本地记录落盘，再去停引擎上的任务。"""
    try:
        _cleanup(state)
    except KeyboardInterrupt:
        # 第二次 Ctrl+C：不再等，把手工兜底交出去就退出
        print("\n再按一次 Ctrl+C：不再等待，立刻退出。引擎上若还有任务，手工停：\n  %s"
              % manual_stop_command(state.get("engine")), file=sys.stderr)


def _cleanup(state):
    engine = state.get("engine")
    log = state.get("log") or (lambda text="": print(text))
    active = state.get("active") or {}
    phase = active.get("phase")

    log("")
    log("!" * 78)
    log("被中断：正在收尾（再按一次 Ctrl+C，可以立刻退出、不再等待）")

    # ① 本地先落盘：本轮如实写一行，别让这一轮在批次记录里凭空消失
    row = active.get("row")
    if row is not None and not active.get("row_written"):
        row["note"] = INTERRUPTED_ENDED if phase == "ended" else INTERRUPTED_RUNNING
        row["success"] = "false"
        try:
            write_row(state.get("writer"), state["handles"], row)
            active["row_written"] = True
            log("  · 本轮已如实写进批次 CSV（note：%s）。" % row["note"])
        except Exception as exc:
            log("  ! 补写本轮 CSV 行失败：%s" % exc)
    elif row is None:
        log("  · 这一轮还没提交、也没有半截记录，CSV 不用补。")

    if state.get("handles") is not None:
        try:
            state["handles"].close()
            state["handles"] = None
        except Exception as exc:
            log("  ! 关 CSV 失败：%s" % exc)

    # ② 再停引擎上属于我们的那一轮
    if state.get("keep_run"):
        log("  按 --keep-run-on-interrupt：**保留**引擎上这一轮任务，没有停它。要停就手工：")
        log("      %s" % manual_stop_command(engine))
    elif engine is None:
        log("  （没连过引擎，没有要停的任务。）")
    else:
        stop_active_run(engine, active, log)

    # ③ 把这次中断的代价说全，不粉饰
    if phase in ("submitting", "running", "unknown"):
        log("  这一轮：引擎侧的「恢复故障」步骤不会执行 —— OS 级 chaosblade 故障本身带 duration")
        log("  会自己到期，DBChaos 类由被杀掉的进程驱动、随之中止；恢复命令全文在归档的")
        log("  <负载>_<日期>.config.xml 里，真不放心就到库上按它清一遍。归档也不完整（没有")
        log("  metric.json 等），批次 CSV 里这一行不代表一次完整运行。")
    log("  （这套收尾覆盖不到 kill -9 / 直接关终端 —— 脚本没机会跑；那种情况手工停：")
    log("   %s）" % manual_stop_command(engine))
    log("=" * 78)
    if state.get("plan_len"):
        log("本批次提前停止：计划 %d 轮，只跑到第 %d 轮。结果：%s"
            % (state["plan_len"], state.get("attempted") or 0, state.get("batch_dir") or "（未建）"))
    log("=" * 78)


def fetch_history_row(engine, run_id, attempts=6, interval_sec=5):
    """跑完去归档里把这一行的指标捞回来。刚结束可能还没落盘，重试几次。"""
    for attempt in range(attempts):
        try:
            payload = engine.get_json("/web/bench/history/query",
                                      params={"keyword": run_id, "size": 5})
            rows = (payload or {}).get("rows") or []
            for row in rows:
                if row.get("historyName") == run_id:
                    return row
        except Exception:
            pass
        if attempt < attempts - 1:
            time.sleep(interval_sec)
    return None


# ---------------------------------------------------------------------------
# 「这一轮到底成功没有」—— 归档行的 success 会短暂读成 null
#
# 引擎的写盘顺序是 summary/metric 先落、job.json 最后落（BenchmarkController.writeFinalJobState
# 在压测子进程退出之后才把 tmp/job.json 拷进运行目录），而 wait_for_end 是 5 秒一次的轮询，
# 一看到 artifacts.complete 就立刻读这一行 —— 正好卡在这条缝里（实测相差约 0.6 秒），
# 于是一轮成功的测试被记成 success=null、判失败。2026-11-01 白天就是这样：
# pg×smallbank 13/13、mysql×smallbank 13/13 产物齐全却全部记成失败。
#
# 这里按三条路收敛，都查不到才承认「不知道」，绝不替它编一个结论。
# ---------------------------------------------------------------------------
RESULTS_DIR_ENV = "RESBENCH_RESULTS_DIR"
LOCAL_RESULTS_CANDIDATES = ("backend/resbenchmark/results", "results")
SUMMARY_OK_STATES = ("EXIT", "COMPLETED", "GRACEFUL")


def run_dir_roots():
    """引擎运行目录在本机时的候选位置：环境变量优先，其次是仓库里的两个默认位置。"""
    roots = []
    env = as_text(os.environ.get(RESULTS_DIR_ENV)).strip()
    if env:
        roots.append(env)
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    roots.extend(os.path.join(repo, rel) for rel in LOCAL_RESULTS_CANDIDATES)
    return roots


def read_run_json(run_id, name):
    """直接读运行目录里的某个 json（脚本和引擎不同机时读不到，返回 None）。"""
    for root in run_dir_roots():
        try:
            with open(os.path.join(root, run_id, name), "r", encoding="utf-8") as handle:
                return json.load(handle)
        except (OSError, ValueError):
            continue
    return None


def read_run_final_state(run_id):
    """summary.json 里的 BenchmarkSQL 终态（EXIT / COMPLETED …）。取不到返回 None。"""
    for root in run_dir_roots():
        directory = os.path.join(root, run_id)
        try:
            names = os.listdir(directory)
        except OSError:
            continue
        for name in names:
            if not name.endswith(".summary.json"):
                continue
            try:
                with open(os.path.join(directory, name), "r", encoding="utf-8") as handle:
                    data = json.load(handle)
            except (OSError, ValueError):
                continue
            state = as_text((data or {}).get("Final State")).strip()
            if state:
                return state
    return None


def resolve_final_state(engine, run_id, archive_row):
    """把这一轮的成败收敛成 (success, state, 判据来源)。

    success 为 True/False 是结论，None = 三条路都查不到（调用方按「未确认」处理）。
    """
    row = archive_row or {}
    if row.get("success") is not None:
        return bool(row.get("success")), as_text(row.get("state")), "归档"
    # ① 重查归档：引擎每次处理查询都会重新读运行目录里的 job.json，
    #    等价于回读那个文件，而且脚本和引擎不同机时也能用。
    fresh = fetch_history_row(engine, run_id, attempts=6, interval_sec=2)
    if fresh and fresh.get("success") is not None:
        return bool(fresh.get("success")), as_text(fresh.get("state")), "归档(重查)"
    # ② 直接读运行目录的 job.json（脚本和引擎同机时可用）
    job = read_run_json(run_id, "job.json")
    if isinstance(job, dict) and job.get("success") is not None:
        return bool(job.get("success")), as_text(job.get("curState")), "运行目录 job.json"
    # ③ 退到 summary.json 的 Final State（产物已经落盘时才有）
    final_state = read_run_final_state(run_id)
    if final_state:
        return final_state.upper() in SUMMARY_OK_STATES, final_state, "运行目录 summary"
    return None, as_text(row.get("state")), "查不到"


def fetch_fault_inject(engine, run_id):
    """取这一轮每个故障的注入/恢复结果与自检状态（前端实时页用的是同一个接口）。

    读取的是这一轮归档里的 faultInject.json，所以跑完仍然取得到。
    """
    payload = engine.get_json("/web/bench/inject/info", params={"historyName": run_id})
    return payload if isinstance(payload, list) else []


def inner_result(value):
    """agent 把命令输出包在 {"result": "..."} 这层 JSON 里，剥一层再判断。

    引擎自己没剥（FaultInjector.markResult 拿到的就是这层外壳，所以 error 恒为 false），
    脚本这边先剥开，才能看见 "Executed: Error 1" 这种真话。
    """
    text = as_text(value)
    try:
        parsed = json.loads(text)
    except (ValueError, TypeError):
        return text
    if isinstance(parsed, dict) and "result" in parsed:
        return as_text(parsed["result"])
    return text


RC_HINTS = {
    "1": "工具自己报错，细节看原始记录",
    "2": "命令的参数写得不对",
    "126": "命令在容器里跑不起来，多半是没有执行权限",
    "127": "容器里没有这个命令",
}


def explain_fault_errors(text):
    """把 CSV 里的 "Memory Load(rc=126); Memory Load(rc=126)" 说成人话，并去掉重复。

    人话只打在日志里；CSV 仍保留原始写法（rc=… 便于检索、便于和归档对账）。
    """
    said = []
    for part in [piece.strip() for piece in as_text(text).split(";") if piece.strip()]:
        match = re.match(r"^(.*?)\(rc=(\d+)\)$", part)
        if match:
            name, code = match.group(1), match.group(2)
            plain = "%s —— 错误码 %s，%s" % (name, code, RC_HINTS.get(code, "命令返回了错误"))
        else:
            plain = part
        if plain not in said:
            said.append(plain)
    return "；".join(said)


def classify_injection(item):
    """单个故障 → (ok | failed | unknown, 说明文本)。

    UNKNOWN 不算失败：chaosblade 类故障都没有 check 模板，e01 cpu 这种真生效的也是 UNKNOWN，
    它只表示"无法确证"，单独记进 fault_verify 列。
    """
    name = as_text(item.get("faultName")) or as_text(item.get("faultID")) or "?"
    results = item.get("faultResults") or []
    status = as_text((item.get("verification") or {}).get("status"))

    # 自检说"注入了没效果"/"命令本身失败"，比命令返回码更可信
    if status == "NOT_ENFORCED":
        return "failed", "%s(注入了没效果)" % name
    if status == "ERROR":
        return "failed", "%s(注入命令失败)" % name

    if not results:
        return "unknown", "%s(没有注入结果)" % name

    for result in results:
        if result.get("error") or result.get("timedOut"):
            return "failed", "%s(agent 调用失败)" % name
        text = inner_result(result.get("result"))
        if text.startswith("Executed: Error"):
            code = re.search(r"Executed: Error (\d+)", text)
            return "failed", "%s(rc=%s)" % (name, code.group(1) if code else "?")
        if text.startswith("TimeoutExpired"):
            return "failed", "%s(超时)" % name
        if text.startswith("CommandNotFound"):
            return "failed", "%s(命令不存在)" % name
        if text.startswith("Error:"):
            return "failed", "%s(下发失败)" % name

    # 走到这里说明命令层面都通了，异步派发的成败只能看自检
    if status == "ENFORCED":
        return "ok", ""
    for result in results:
        text = inner_result(result.get("result"))
        if text.startswith("Executed: Successful") or text.startswith("Executed: Dispatched"):
            return "ok", ""
    return "unknown", "%s(结果无法判定)" % name


def summarize_fault_inject(items):
    """整轮的故障注入判定 → (fault_inject, fault_inject_errors, fault_verify)。

    任一条失败 → failed；否则有无法确证的 → unknown；否则 ok。
    """
    if not items:
        return "", "", ""

    verdicts = [classify_injection(item) for item in items]
    failed = [why for verdict, why in verdicts if verdict == "failed"]
    if failed:
        state = "failed:%d/%d" % (len(failed), len(verdicts))
    elif any(verdict == "unknown" for verdict, _ in verdicts):
        state = "unknown"
    else:
        state = "ok"

    statuses = {}
    for item in items:
        status = as_text((item.get("verification") or {}).get("status")) or "无自检"
        statuses[status] = statuses.get(status, 0) + 1
    verify = " ".join("%s:%d" % (key, statuses[key]) for key in sorted(statuses))

    detail = "; ".join(failed)
    if len(detail) > 300:
        detail = detail[:297] + "..."
    return state, detail, verify


def _mean(points, lo, hi):
    # 半开区间 [lo, hi)：注入窗口和它前面的基线不能共用边界上的那一个采样点
    values = [value for ts, value in points if lo <= ts < hi]
    return sum(values) / len(values) if values else None


def _extreme(points, lo, hi, want_max):
    values = [value for ts, value in points if lo <= ts < hi]
    if not values:
        return None
    return max(values) if want_max else min(values)


def _series_index(response):
    """把归档曲线展开成 [(metricId, labels, [(ts_ms, value)])]。"""
    index = []
    for item in (response or {}).get("series") or []:
        points = []
        for point in item.get("points") or []:
            if not isinstance(point, (list, tuple)) or len(point) < 2:
                continue
            try:
                points.append((int(float(point[0])), float(point[1])))
            except (TypeError, ValueError):
                continue
        if points:
            index.append((item.get("metricId"), item.get("labels") or {}, points))
    return index


def _window_signal(index, rule, lo, hi, baseline_lo):
    """在窗口里找偏离基线最明显的序列。返回 (是否命中, 增量, 标签) 或 None（没数据）。"""
    best = None
    for metric_id, labels, points in index:
        if metric_id != rule["metric"]:
            continue
        base = _mean(points, baseline_lo, lo)
        win = _mean(points, lo, hi)
        if base is None or win is None:
            continue
        if rule.get("positive"):
            base_peak = _extreme(points, baseline_lo, lo, True)
            win_peak = _extreme(points, lo, hi, True)
            if base_peak is None or win_peak is None:
                continue
            hit = win_peak >= 0.5 and win_peak > base_peak
            delta = win_peak - base_peak
        elif rule.get("decrease"):
            # 反向判据：窗口均值比基线低 min_drop（0.3 = 下降 30%）以上才算命中
            delta = win - base
            hit = base > 0 and win <= base * (1.0 - rule.get("min_drop", 0.0))
        else:
            delta = win - base
            hit = delta >= rule.get("min_abs", 0.0)
            ratio = rule.get("min_ratio")
            if hit and ratio is not None and win < max(base, 1e-9) * ratio:
                hit = False
        if best is None:
            best = (delta, hit, labels)
        elif (delta < best[0]) if rule.get("decrease") else (delta > best[0]):
            best = (delta, hit, labels)
    return best


def _stage_windows(stages, run_start_ms, fault_items):
    """给每个批次定位真实的注入窗口。

    stage["base"]/["end"] 是相对**负载开始**的秒数，而 run_start_ms 是提交/监控开始的
    时刻；引擎要先建仓、再起负载，两者相差几十秒（本机实测约 70 秒）。直接用
    run_start_ms + base 会把窗口整体前移，落在负载爬坡段，判据因此失真。

    优先用注入记录里的真实时刻：同一个 faultDelay 的故障属于同一批，窗口取它们
    startTime 的最小值到 endTime 的最大值。某个批次没有记录时，用其它记录的
    「startTime − faultDelay」反推负载起点，再按计划偏移补齐。

    返回 (windows, mode)；mode 是 actual / mixed / offset / planned，写进明细备查。
    """
    planned = [(run_start_ms + int(stage["base"]) * 1000,
                run_start_ms + int(stage["end"]) * 1000) for stage in stages]
    if not stages:
        return [], "actual"
    if not fault_items:
        return planned, "planned"

    by_delay = {}
    workload_offsets = []
    for item in fault_items:
        start = parse_int(item.get("startTime"))
        delay = parse_int(item.get("faultDelay"))
        if not start or delay is None:
            continue
        end = parse_int(item.get("endTime"))
        by_delay.setdefault(delay, []).append((start, end))
        workload_offsets.append(start - delay * 1000 - run_start_ms)

    workload_start = None
    if workload_offsets:
        workload_offsets.sort()
        workload_start = run_start_ms + workload_offsets[len(workload_offsets) // 2]

    windows = []
    matched = 0
    for stage, (fallback_lo, fallback_hi) in zip(stages, planned):
        group = by_delay.get(int(stage["base"]))
        if group:
            matched += 1
            lo = min(start for start, _ in group)
            ends = [end for _, end in group if end]
            hi = max(ends) if ends else lo + (fallback_hi - fallback_lo)
            windows.append((lo, hi))
        elif workload_start is not None:
            windows.append((workload_start + int(stage["base"]) * 1000,
                            workload_start + int(stage["end"]) * 1000))
        else:
            windows.append((fallback_lo, fallback_hi))

    if matched == len(stages):
        mode = "actual"
    elif matched:
        mode = "mixed"
    elif workload_start is not None:
        mode = "offset"
    else:
        mode = "planned"
    return windows, mode


def evaluate_effect(series, stages, run_start_ms, fault_items=None):
    """按注入窗口判断曲线有没有出现预期变化。

    窗口优先取 fault_items 里的真实 startTime/endTime（见 _stage_windows）；没有注入
    记录时退回计划时刻，并在证据里标注窗口可能偏移。每个窗口往前 30 秒做基线，三个
    批次各自判断：全部命中 ENFORCED，命中一部分 PARTIAL，一个都没有 NOT_ENFORCED。
    返回 (判定, 一行证据, 明细)。
    """
    index = _series_index(series)
    windows, window_mode = _stage_windows(stages, run_start_ms, fault_items)
    evidence = []
    details = []
    total = 0
    hit_count = 0
    known = 0
    for stage_no, stage in enumerate(stages, start=1):
        lo, hi = windows[stage_no - 1]
        baseline_lo = max(run_start_ms, lo - EFFECT_BASELINE_SEC * 1000)
        for fault in stage.get("faults") or []:
            suffix = fault["nameKey"].split("faultinject.fault.")[-1]
            rule = EFFECT_RULES.get(suffix)
            if not rule:
                continue
            total += 1
            candidates = [rule] + ([rule["fallback"]] if rule.get("fallback") else [])
            chosen = None
            for candidate in candidates:
                signal = _window_signal(index, candidate, lo, hi, baseline_lo)
                if signal is not None:
                    chosen = (candidate, signal)
                    break
            if chosen is None:
                evidence.append("批%d %s 无数据" % (stage_no, suffix))
                details.append({"stage": stage_no, "fault": suffix, "status": "NO_DATA"})
                continue
            candidate, (delta, hit, labels) = chosen
            known += 1
            if hit:
                hit_count += 1
            evidence.append("批%d %s %s=%+.2f %s%s" % (
                stage_no, suffix, candidate["metric"], delta,
                "命中" if hit else "未命中",
                "（间接信号）" if candidate.get("indirect") else ""))
            details.append({
                "stage": stage_no, "fault": suffix, "metric": candidate["metric"],
                "delta": round(delta, 4), "hit": bool(hit), "labels": labels,
                "window": [lo, hi], "baseline": [baseline_lo, lo],
            })
    if known == 0:
        verdict = "NO_DATA"
    elif hit_count == total:
        verdict = "ENFORCED"
    elif hit_count > 0:
        verdict = "PARTIAL"
    else:
        verdict = "NOT_ENFORCED"
    if window_mode != "actual":
        evidence.append({
            "mixed": "部分批次没有注入记录，窗口用偏移推算",
            "offset": "没有逐批注入记录，窗口按负载起点偏移推算",
            "planned": "没有注入记录，窗口按提交时刻估算，可能整体偏移",
        }.get(window_mode, "窗口来源 %s" % window_mode))
    return verdict, "; ".join(evidence), {
        "windows": total, "hit": hit_count, "window_mode": window_mode, "details": details}


def finish_monitor(monitor, row, run_id, stages, start_ms, run_dir, log, fault_items=None):
    """归档监控会话，并把「曲线是否证明故障生效」写回 CSV。"""
    if not monitor.available:
        row["monitor_run"] = row.get("monitor_run") or "disabled"
        reason = (getattr(monitor, "reason", "") or "").strip() or "原因未记录"
        row["monitor_evidence"] = "监控未采集：%s" % reason
        return
    monitor.complete(run_id)
    row["monitor_run"] = "archived" if row.get("monitor_run") == "ok" else (row.get("monitor_run") or "archived")
    data = monitor.series(run_id)
    if not data:
        row["monitor_verify"] = "NO_DATA"
        row["monitor_evidence"] = "归档里没有曲线（Prometheus 无数据或会话没采到）"
        log("  · 监控判据：归档里没有曲线，无法判断故障是否在资源上生效")
        return
    if not start_ms:
        row["monitor_verify"] = "NO_DATA"
        row["monitor_evidence"] = "归档里没有运行开始时刻，无法对齐注入窗口"
        log("  · 监控判据：归档里没有开始时刻，无法对齐注入窗口")
        return
    if fault_items is None:
        row["monitor_verify"] = "NO_DATA"
        row["monitor_evidence"] = "没有故障注入记录，无法对齐真实注入窗口"
        log("  · 监控判据：没有故障注入记录，无法对齐真实注入窗口")
        return
    verdict, evidence, detail = evaluate_effect(data, stages, start_ms, fault_items)
    row["monitor_verify"] = verdict
    row["monitor_evidence"] = evidence
    log("  · 监控判据：%s（%s）" % (verdict, evidence))
    if run_dir:
        payload = {"runId": run_id, "verdict": verdict, "evidence": evidence,
                   "startMs": start_ms, "detail": detail,
                   # 留下批次结构，之后离线复算判据不必再从编排文件倒推
                   "stages": stages, "series": data}
        path = os.path.join(run_dir, "%s.monitor.json" % run_id)
        try:
            os.makedirs(run_dir, exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, ensure_ascii=False, indent=2)
        except OSError as exc:
            log("  ! 写监控判据失败：%s" % exc)


def record_fault_inject(engine, row, run_id, run_dir, log, state):
    """把这一轮的故障注入结果记进 CSV，并留一份原始数据在批次目录里。

    state 是个可变 dict，用来记住"引擎没有这个接口"，避免每轮都刷一遍同样的提示。
    """
    planned = bool(as_text(row.get("faults")))
    if state.get("unavailable"):
        row["fault_inject"] = "unknown"
        return None

    try:
        items = fetch_fault_inject(engine, run_id)
    except Exception as exc:
        state["unavailable"] = True
        log("  ! 引擎没有 /web/bench/inject/info 这个接口（旧版本？），所以这一轮"
            "「故障到底加进去了没有」问不出来：%s" % exc)
        row["fault_inject"] = "unknown"
        return None

    if not items:
        if planned:
            # 编排里排了故障，归档里却没有注入记录（例如 faultInject.json 是空的）
            row["fault_inject"] = "unknown"
            row["fault_inject_errors"] = "归档里没有注入记录"
            log("  ! 这一轮归档里没有故障注入记录，所以「故障到底加进去了没有」无从判断")
        return []

    row["fault_inject"], row["fault_inject_errors"], row["fault_verify"] = \
        summarize_fault_inject(items)

    try:
        os.makedirs(run_dir, exist_ok=True)
        with open(os.path.join(run_dir, "%s.faults.json" % run_id), "w", encoding="utf-8") as handle:
            json.dump(items, handle, ensure_ascii=False, indent=2)
    except OSError as exc:
        log("  ! 写这一轮的故障注入结果失败：%s" % exc)

    if row["fault_inject"].startswith("failed"):
        total = row["fault_inject"].split(":", 1)[1]
        log("  ! 这一轮安排的故障没有真的加进去（%s 次都没成功）：%s"
            % (total, explain_fault_errors(row["fault_inject_errors"])))
        log("    也就是说：这一轮数据库是在「没有故障」的状态下跑的，上面的分数只反映它平时的样子，")
        log("    不能用来回答「加上这个故障会怎样」。原始记录：%s.faults.json" % run_id)
    return items


def label_run(engine, run_id, labels, note, log):
    """打标签：前端历史页的「标签/备注」就是这个接口，跑完补上便于检索。"""
    try:
        engine.post_json("/web/bench/history/label",
                         {"historyName": run_id, "labels": labels, "note": note})
    except Exception as exc:
        log("  ! 写归档标签失败（不影响测试结果）：%s" % exc)


# ---------------------------------------------------------------------------
# 打印
# ---------------------------------------------------------------------------
class Printer(object):
    def __init__(self, quiet):
        self.quiet = quiet

    def __call__(self, message=""):
        if not self.quiet:
            print(message, flush=True)


def fmt_ts(millis):
    if not millis:
        return ""
    try:
        return datetime.fromtimestamp(int(millis) / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
    except (ValueError, OSError, OverflowError):
        return ""


def build_labels(db, workload, orchestration, extra_labels):
    labels = ["batch", db["key"], workload["key"], orchestration["name"]]
    for label in extra_labels or []:
        if label and label not in labels:
            labels.append(label)
    return labels


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="resbench 批量压测（只用后端引擎接口）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("用法：")[-1])
    parser.add_argument("--engine", default="http://127.0.0.1:8080",
                        help="引擎地址，默认 http://127.0.0.1:8080（不需要 envInfo）")
    parser.add_argument("--manager", default="http://127.0.0.1:8090",
                        help="res-manager 地址，默认 %(default)s；压测期间由它建监控会话并归档曲线")
    parser.add_argument("--no-monitor", action="store_true", dest="no_monitor",
                        help="不建监控会话、不做曲线判据（只跑引擎）")
    parser.add_argument("--monitor-metrics", dest="monitor_metrics",
                        type=lambda text: [item.strip() for item in text.split(",") if item.strip()],
                        default=None,
                        help="监控指标 ID，逗号分隔；默认 %s" % ",".join(DEFAULT_MONITOR_METRICS))
    parser.add_argument("--databases", required=True, help="数据库配置文件（可含多个库）")
    parser.add_argument("--workloads", required=True, help="背景负载配置文件（可含多个负载）")
    parser.add_argument("--orchestration", action="append", required=True, dest="orchestrations",
                        help="故障编排文件，可以重复传；一个文件里可以放多个编排")
    parser.add_argument("--execute", action="store_true",
                        help="真的提交任务。不加本参数只打印计划（dry-run）")
    parser.add_argument("--out", default="batch-results",
                        help="结果根目录：每次调用会在它下面建一个批次目录"
                             "（<时间戳>_<库key>_<负载key>_<总编排key>），默认 batch-results/")
    parser.add_argument("--tail", type=int, default=20,
                        help="负载要在故障计划结束之后再跑多少秒，默认 20")
    parser.add_argument("--settle", type=int, default=15,
                        help="跑完后等归档落盘再检索的秒数，默认 15")
    parser.add_argument("--cooldown", type=int, default=0,
                        help="轮与轮之间的额外冷却秒数，默认 0")
    parser.add_argument("--poll", type=int, default=5, help="轮询间隔秒数，默认 5")
    parser.add_argument("--start-timeout", type=int, default=180,
                        help="等任务真正跑起来的超时，默认 180 秒")
    parser.add_argument("--end-margin", type=int, default=600,
                        help="等任务结束的余量：超过 负载时长+本值 仍未结束就报超时，默认 600 秒")
    parser.add_argument("--build-timeout", type=int, default=21600,
                        help="**建仓预热轮**等任务结束的余量，默认 21600 秒（6 小时）；"
                             "正式轮仍用 --end-margin。1000 仓在本环境实测约 4h08m，"
                             "仓数更多就往大调")
    parser.add_argument("--busy-wait", type=int, default=600,
                        help="提交时撞上「执行中」最多等多少秒，默认 600；"
                             "这个预算只用于「引擎上已经有别人的任务」。等不到会打印引擎自己说的"
                             "状态和清理办法，然后**停止本批次**（后面每一轮都会撞同一道门，"
                             "接着跑只会得到一串被挡掉的空轮）")
    parser.add_argument("--label", action="append", default=[], dest="labels",
                        help="额外归档标签，可重复")
    parser.add_argument("--keep-run-on-interrupt", action="store_true", dest="keep_run",
                        help="Ctrl+C / SIGTERM 时**不**停引擎上本轮的压测任务（默认会停）。"
                             "默认行为只停脚本自己刚提交的那一轮；引擎上若是别人的任务，"
                             "脚本认出来就不会碰（靠引擎写的 startTimestamp 认领）")
    parser.add_argument("--only-db", action="append", default=[], dest="only_dbs",
                        help="只跑指定 key 的库（脚本的运行控制，不发给后端），可重复")
    parser.add_argument("--only-workload", action="append", default=[], dest="only_workloads",
                        help="只跑指定 key 的负载，可重复")
    parser.add_argument("--only-orchestration", action="append", default=[],
                        dest="only_orchestrations",
                        help="只跑指定文件名的编排（不含扩展名），可重复")
    parser.add_argument("--auto-file-targets", action="store_true", dest="auto_file_targets",
                        help="文件类故障的路径改用后端推荐的真实路径（@auto: 哨兵，"
                             "引擎开跑时查库解析）；不加则按编排文件里写的路径跑")
    parser.add_argument("--auto-process-target", action="store_true", dest="auto_process_target",
                        help="进程挂起故障的 process-cmd 改成现场探测结果：按数据库端口找到监听"
                             "进程，取它的 argv[0] 做匹配串，并核对命中范围（宿主机上必须只命中"
                             "同一实例，容器内按容器进程表匹配）；探不到或会误伤别的实例就报错")
    parser.add_argument("--quiet", action="store_true", help="少打印")
    return parser.parse_args(argv)


def select(items, wanted, field, what):
    if not wanted:
        return items
    picked = [item for item in items if str(item[field]) in wanted]
    missing = set(wanted) - set(str(item[field]) for item in picked)
    if missing:
        raise ConfigError("--only-%s 指定的 %s 不存在：%s" % (what, what, ", ".join(sorted(missing))))
    return picked


def main(argv=None):
    args = parse_args(argv if argv is not None else sys.argv[1:])
    args.monitor_metrics = args.monitor_metrics or list(DEFAULT_MONITOR_METRICS)
    log = Printer(args.quiet)

    # ---- 读配置 ----
    databases = select(load_databases(args.databases), args.only_dbs, "key", "db")
    workloads = select(load_workloads(args.workloads), args.only_workloads, "key", "workload")
    orchestrations = []
    seen_orch = set()
    for path in args.orchestrations:
        for orchestration in load_orchestrations(path):
            if orchestration["name"] in seen_orch:
                raise ConfigError("编排 key 重复（结果目录名和 --only-orchestration 都靠它区分）：%s"
                                  % orchestration["name"])
            seen_orch.add(orchestration["name"])
            orchestrations.append(orchestration)
    orchestrations = select(orchestrations, args.only_orchestrations, "name", "orchestration")

    # ---- 连引擎、拉目录 ----
    engine = Engine(args.engine)
    try:
        engine.get_json("/web/bench/status")
    except Exception as exc:
        raise ConfigError("连不上引擎 %s：%s" % (args.engine, exc))
    catalog = fetch_catalog(engine)

    # ---- 展开笛卡尔积 + 算时长 ----
    process_targets = {}   # db key → 探测结果（每个库只探一次，多个编排共用）
    plan = []
    for db in databases:
        for workload in workloads:
            if workload["name"] == "tpcc" and db["dbType"] == "oceanbase":
                raise ConfigError("库 %s 是 OceanBase，不支持 tpcc 负载（引擎限制）" % db["key"])
            for orchestration in orchestrations:
                stages, plan_end = resolve_plan(orchestration, catalog, args.auto_file_targets)
                # 进程挂起的目标必须在提交前定死：引擎只做子串匹配，写错会挂到别的实例上，
                # 而 process.stop 一旦挂错，整轮后面批次的数据库故障就没库可打了。
                if args.auto_process_target:
                    apply_process_target(db, stages, process_targets, log)
                duration = compute_workload_duration(workload, plan_end, args.tail, bool(stages))
                plan.append({
                    "db": db, "workload": workload, "orchestration": orchestration,
                    "stages": stages, "plan_end": plan_end, "duration": duration,
                })

    # ---- 打印计划 ----
    log("=" * 78)
    log("引擎            %s" % args.engine)
    log("库 / 负载 / 编排  %d × %d × %d = %d 轮，全部串行"
        % (len(databases), len(workloads), len(orchestrations), len(plan)))
    # 配了 container 的库先把话说清楚：只有 chaosblade 类故障进容器，网络类与 DBChaos 类不进。
    # 一次性打印，免得每轮都重复一遍。
    for db in databases:
        if db["container"]:
            log("  ----  %s：chaosblade 类故障（cpu/memory/disk/file/process）在容器 %s 内注入；"
                "网络类故障与 DBChaos 类（connection/transaction 等）仍走宿主机"
                % (db["key"], db["container"]))
    # --auto-file-targets：替换表 + 两个后果，一次性说完，免得每轮重复。
    if args.auto_file_targets:
        mapped = {}          # nameKey -> {参数: 哨兵}，去重后打一张表
        combos = []          # 受影响的轮次
        irreversible = set()
        needs_build = []     # tpcc 且配了 reload：仓数预检判定要建仓时会先跑一轮建仓预热轮
        for item in plan:
            hits = [(f, f["autoTargets"]) for stage in item["stages"]
                    for f in stage["faults"] if f.get("autoTargets")]
            if not hits:
                continue
            for fault, targets in hits:
                mapped.setdefault(fault["nameKey"], {}).update(targets)
                irreversible.update(s for s in targets.values()
                                    if s in IRREVERSIBLE_AUTO_TARGETS)
            combo = "%s × %s × %s" % (item["db"]["key"], item["workload"]["key"],
                                      item["orchestration"]["name"])
            combos.append(combo)
            if item["workload"]["name"] == "tpcc" and item["workload"]["params"].get("reload"):
                needs_build.append(combo)
        if mapped:
            log("--auto-file-targets：文件类故障的路径改用后端推荐目标")
            for name_key in sorted(mapped):
                pairs = "  ".join("%s→%s" % (k, v) for k, v in sorted(mapped[name_key].items()))
                log("  %-10s %s" % (name_key.replace("faultinject.fault.disk.", ""), pairs))
            log("  涉及 %d 轮：%s" % (len(combos), "；".join(combos)))
            if needs_build:
                log("  建仓与故障不放在同一轮里：仓数预检判定某对「库 × tpcc 负载」要建仓时，"
                    "会**先单独跑一轮预热轮**（不带故障、reload=true）把仓建好，之后该对的正式轮"
                    "一律 reload=false —— 哨兵在正式轮提交时才解析，拿到的是刚建好的那份文件"
                    "（建仓会重写文件号，提交那一刻算出来的路径建完就过期）。")
            for sentinel in sorted(irreversible):
                log("  ! %s 打在表文件的**最后一段**上：追加进去的字节引擎恢复不了"
                    "（渲染的 append 命令从来不传 --enable-backup），故障窗口结束后这个表还是坏的，"
                    "要自己 TRUNCATE / 重灌才会好。要安全的低档就在编排里手写 "
                    "@auto:hottest-mid-segment（不分段的库没有中间段，那边得直接写具体文件），"
                    "或这个编排别开开关。" % sentinel)
        else:
            log("--auto-file-targets：矩阵里没有文件类故障，本次不生效。")
    log("=" * 78)
    for index, item in enumerate(plan, start=1):
        container_tag = " [容器 %s]" % item["db"]["container"] if item["db"]["container"] else ""
        log("[%02d] %s(%s)%s × %s × %s" % (
            index, item["db"]["key"], item["db"]["dbType"], container_tag,
            item["workload"]["key"], item["orchestration"]["name"]))
        for stage_index, stage in enumerate(item["stages"], start=1):
            names = ", ".join(f["nameKey"].replace("faultinject.fault.", "")
                              for f in stage["faults"])
            marker = "  [指定 at]" if stage.get("absolute") else ""
            log("      批 %d  t=%ss → %ss%s  %s"
                % (stage_index, stage["base"], stage["end"], marker, names))
        declared = item["workload"]["params"].get("duration")
        note = ""
        if (item["stages"] and declared not in (None, "")
                and int(float(as_text(declared))) != item["duration"]):
            note = "（不采用配置里写的 %s：有故障时按计划算，稳态尾巴用 --tail 调）" \
                % as_text(declared)
        log("      故障计划终点 %ss → 负载时长 %ss%s"
            % (item["plan_end"], item["duration"], note))

    # tpcc 仓数预检：每组 (库 × 负载) 只在第一轮查一次。dry-run 不查库（查库要连数据库），
    # 只把脚本打算怎么做说清楚。
    tpcc_pairs = []
    for item in plan:
        if item["workload"]["name"] == "tpcc":
            pair = (item["db"]["key"], item["workload"]["key"])
            if pair not in tpcc_pairs:
                tpcc_pairs.append(pair)
    if tpcc_pairs:
        workload_by_key = {w["key"]: w for w in workloads}
        log("tpcc 仓数预检（每对「库 × 负载」只在第一轮查一次）：")
        for db_key, workload_key in tpcc_pairs:
            workload = workload_by_key[workload_key]
            count = configured_warehouses(workload)
            log("  %s × %s：配置 %s 仓，reload=%s → 这一对开跑前先查库里实际仓数，"
                "对得上就直接跑（不建仓），对不上就先单独跑一轮预热轮把仓建好、再开始正式轮"
                % (db_key, workload_key, count if count is not None else "未配置",
                   "true" if workload["params"].get("reload") else "false"))
            if count is None:
                log("      ! 没有可用的 warehouses（要正整数）：预检会跳过、按配置的 reload 执行；"
                    "引擎真跑时按默认 1 仓处理")
        log("  预热轮的等结束上限 = 负载时长 + --build-timeout=%ss（1000 仓实测约 4h08m）；"
            "普通轮次仍用 --end-margin=%ss" % (args.build_timeout, args.end_margin))
    log("")

    # ---- 本次调用的结果目录 ----
    # 一次调用只建一个批次目录，本次全部产物（批次 CSV + 每轮的归档 JSON）都放里面，
    # 不再平铺进 --out 跟别的批次混在一起。时间戳按真正开跑的时刻生成。
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    batch_dir = os.path.join(args.out, batch_dir_name(plan, stamp))
    log("结果目录  %s" % batch_dir)
    log("  runs-%s.csv                                 ← 每轮一行%s"
        % (stamp, "" if args.execute else "（下面这棵树的根目录，dry-run 没有建）"))
    log("  <库key>__<负载key>__<编排key>/<运行名>.json   ← 每轮的归档原始数据")
    log("")

    if not args.execute:
        log("这是 dry-run，没有提交任何任务，也没有建任何目录。确认无误后加 --execute 真正开跑。")
        return 0

    # ---- 落盘目录 ----
    os.makedirs(batch_dir, exist_ok=True)
    csv_path = os.path.join(batch_dir, "runs-%s.csv" % stamp)
    handles = open(csv_path, "w", encoding="utf-8", newline="")
    writer = csv.DictWriter(handles, fieldnames=CSV_FIELDS)
    writer.writeheader()
    handles.flush()
    log("结果写在 %s\n" % batch_dir)

    ready_pairs = set()    # 数据已就绪、无需建仓的 (库, tpcc负载)：库里本来就有，或本脚本建成功过
    pair_reload = {}       # (库, tpcc负载) -> 是否要先把建仓单独跑一轮（预检定的，正式轮一律不建仓）
    applied_params = {}    # 每个库应用过的参数，按库 key 缓存（同一个库只应用一次）
    applied_done = set()
    failures = 0
    attempted = 0         # 真正开始过的轮次数（提前停止时用它，而不是计划总数）
    aborted = False       # 遇到没法继续的情况 → 整批停下
    abort_reason = ""     # 停下来的原因（收尾时如实打出来，不替用户猜）
    fault_inject_state = {}   # 记住"引擎没有 inject/info 接口"，只提示一次
    ineffective = []          # 故障没真正生效的轮次，收尾统一列出来
    unverifiable = 0          # 故障生效与否无法判定的轮次
    monitor = Monitor(args.manager, args.monitor_metrics, log, enabled=not args.no_monitor)
    if not args.no_monitor:
        log("监控：压测开始前在 %s 建采集会话，结束后按注入窗口判定曲线（失败不影响压测）\n"
            % args.manager)

    # 中断收尾要用的现场（见 cleanup_after_interrupt）。只放收尾真正需要的几样。
    RUN_STATE.update(engine=engine, log=log, handles=handles, writer=writer,
                     active=None, plan_len=len(plan), attempted=0,
                     keep_run=args.keep_run, batch_dir=batch_dir)

    for index, item in enumerate(plan, start=1):
        db = item["db"]
        workload = item["workload"]
        orchestration = item["orchestration"]
        pair = (db["key"], workload["key"])
        is_tpcc = workload["name"] == "tpcc"
        attempted = index
        RUN_STATE["attempted"] = index

        log("-" * 78)
        log("[%02d/%02d] %s × %s × %s" % (index, len(plan), db["key"], workload["key"],
                                          orchestration["name"]))

        # 应用数据库参数：一个库只做一次（后面的轮次沿用，避免反复重启）。
        # 排在仓数预检**之前**：应用参数会重启库，别在库正要重启的时间窗里去数仓。
        if db["key"] not in applied_done:
            applied_done.add(db["key"])
            applied_params[db["key"]] = apply_db_params(engine, db, log)

        # tpcc 建不建仓：每个 (库, 负载) 的**第一轮**先查一次库里实际仓数，之后不再重读 YAML。
        #   仓数对得上 → 直接跑，省掉一次建仓（1000 仓要 4h08m）
        #   仓数对不上 → 先跑一轮预热轮把仓建好（不带故障），之后这一对的正式轮都 reload=false
        #   查不出来   → 不动 reload，按 YAML 配置走（「看不到」≠「没建仓」）
        reload_flag = bool(workload["params"].get("reload")) if is_tpcc else False
        if is_tpcc:
            if pair in ready_pairs:
                reload_flag = False
            elif pair in pair_reload:
                # 沿用预检定的结论：建仓还没成功就继续让它重试，不会退回 YAML 而悄悄放弃
                reload_flag = pair_reload[pair]
            else:
                verdict, detail = verify_warehouses(engine, db, workload, log)
                reload_flag, message = decide_reload(reload_flag, verdict, detail)
                for line in message.splitlines():
                    log(line)
                pair_reload[pair] = reload_flag
                if verdict in (VERDICT_READY, VERDICT_INCOMPLETE):
                    # 当场记账：否则下一轮会重新按 YAML 算出 reload=true，又建一遍
                    ready_pairs.add(pair)

        # 建仓不跟正式测试挤在一轮里：单独先跑一轮预热轮，跑完数据就位，再开始第一个故障的注入。
        # 这样正式轮永远是 reload=false，@auto: 哨兵在提交那一刻解析出来的路径就是刚建好的那份。
        if reload_flag:
            if not run_prime_round(engine, db, workload, orchestration, item["duration"],
                                   applied_params.get(db["key"], {}), args, log):
                log("  × 预热轮没跑成。正式轮一提交就会撞上同一件事（要么还在建仓、要么数据没就位），"
                    "所以这一对停在这里，不再往下跑。")
                aborted = True
                abort_reason = "建仓预热轮没跑成（原因见上面那一轮的报告）"
                active = {"phase": "idle", "submitted_at_ms": 0, "run_id": "", "label": "",
                          "row": None, "row_written": False}
                RUN_STATE["active"] = active
                break
            ready_pairs.add(pair)
            pair_reload[pair] = False
            reload_flag = False

        body = build_request(db, workload, item["stages"], item["duration"],
                             reload_flag, applied_params.get(db["key"], {}),
                             combo_name(db, workload, orchestration))

        # ---- 提交前先确认引擎上真的没有负载在跑 ----
        if not ensure_engine_free(engine, args, log):
            aborted = True
            abort_reason = "引擎上一直有别的压测在跑，等不到空闲"
            active = {"phase": "idle", "submitted_at_ms": 0, "run_id": "", "label": "",
                      "row": None, "row_written": False}
            RUN_STATE["active"] = active
            break

        # ---- 提交（引擎同一时刻只允许一个任务，被挡就等）----
        active = {"phase": "submitting", "submitted_at_ms": 0, "run_id": "",
                  "label": "%s × %s × %s" % (db["key"], workload["key"], orchestration["name"]),
                  "row": None, "row_written": False}
        RUN_STATE["active"] = active
        response, submitted_at_ms, engine_busy = submit_run(engine, body, args, log, active)

        if engine_busy:
            log("  × 等了 %s 引擎还是被占着。再往下跑，每一轮都会被同一道门挡住，这一批就停在这里。"
                % fmt_secs(args.busy_wait))
            log_engine_busy_hint(log)
            aborted = True
            abort_reason = "引擎一直被别的任务占着，等不到空闲"
            active["phase"] = "idle"   # 我们什么都没提交上去，引擎上那个不是我们的
            break

        run_id = (response or {}).get("runId") or (response or {}).get("historyName") or ""
        if response and response.get("accepted") and not run_id:
            log("  × 引擎接受了任务但没返回 runId，无法跟踪这一轮")
            response = dict(response, accepted=False, message="引擎未返回 runId")
        if response and response.get("accepted"):
            active["phase"] = "running"
            active["run_id"] = run_id
        row = {field: "" for field in CSV_FIELDS}
        row["faults"] = "; ".join(
            "%s[%s]" % (f["nameKey"], ",".join("%s=%s" % (k, v) for k, v in sorted(f["params"].items())))
            for stage in item["stages"] for f in stage["faults"])
        row["batch_no"] = index
        row["history_name"] = run_id
        row["db_key"] = db["key"]
        row["db_type"] = db["dbType"]
        row["deployment_type"] = db["deploymentType"]
        row["container"] = db["container"]
        row["workload_key"] = workload["key"]
        row["workload"] = workload["name"]
        row["orchestration"] = orchestration["name"]
        row["suite"] = orchestration["suite"]
        row["plan_end_sec"] = item["plan_end"]
        row["workload_duration_sec"] = item["duration"]
        active["row"] = row

        if not response or not response.get("accepted"):
            message = (response or {}).get("message") or "提交被拒绝"
            log("  × 提交未接受：%s" % message)
            row["accepted"] = "false"
            row["note"] = message
            failures += 1
            write_row(writer, handles, row)
            active["phase"] = "idle"      # 没有属于我们的任务在跑
            active["row_written"] = True
            continue

        row["accepted"] = "true"
        log("  → 已提交：%s；这一轮负载计划跑 %s，等它跑完…"
            % (run_id, fmt_secs(item["duration"])))
        row["monitor_run"] = monitor.start(run_id, db["container"], db.get("dbType"))

        state, archive_row = wait_for_start(engine, run_id, submitted_at_ms,
                                           args.start_timeout, args.poll)
        if state == "timeout":
            log("  × 提交上去了，但 %s 内没见它开始跑（归档里一直没出现这一轮）"
                % fmt_secs(args.start_timeout))
            row["note"] = "任务没跑起来（等启动超时）"
            failures += 1
            finish_monitor(monitor, row, run_id, item["stages"], None, None, log)
            write_row(writer, handles, row)
            # 受理了但没见它跑起来：任务可能还活着（也可能只是起得慢），交给认领判据决定要不要停
            active["phase"] = "unknown"
            active["row_written"] = True
            continue

        # 正式轮一律不建仓（建仓已经由前面的预热轮做掉了），等待余量固定用 --end-margin。
        timeout = item["duration"] + args.end_margin
        archive_row, verdict = wait_for_end(engine, run_id, timeout, args.poll,
                                           on_tick=lambda: monitor.keepalive(run_id))
        if verdict == "timeout":
            log("  × 等了 %s 也没等到这一轮跑完（负载计划 %s）。"
                % (fmt_secs(timeout), fmt_secs(item["duration"])))
            log("    它可能还在引擎上跑。晚点归档补齐后这一轮的数据仍然算数 —— "
                "可以按运行编号 %s 到历史页把结果取回来。" % run_id)
            row["note"] = "没等到跑完（等结束超时）；这一轮可能还在引擎上跑"
            failures += 1
            finish_monitor(monitor, row, run_id, item["stages"],
                           parse_int((archive_row or {}).get("startTimestamp")), None, log)
            write_row(writer, handles, row)
            active["phase"] = "unknown"   # 可能还在跑，同样交给认领判据
            active["row_written"] = True
            continue

        # 这一轮在引擎侧已经结束（不管成功失败）—— 从此之后任何中断都不该再杀任务，
        # 顶多是这一行的归档明细没来得及取回。
        active["phase"] = "ended"
        finished = verdict == "complete"
        success = bool((archive_row or {}).get("success")) if finished else False
        state_text = as_text((archive_row or {}).get("state"))
        resolved = None
        if finished and (archive_row or {}).get("success") is None:
            # 产物齐了、job.json 却还没落盘：success 读成 null 不是失败（见 resolve_final_state）
            resolved, resolved_state, source = resolve_final_state(engine, run_id, archive_row)
            if resolved is not None:
                success = resolved
                state_text = resolved_state or state_text
                if archive_row is not None:
                    archive_row["success"] = resolved
                    archive_row["state"] = state_text
                if source != "归档":
                    log("  · success 字段当时还没落盘，回读 %s 得到 success=%s（状态 %s）"
                        % (source, "true" if resolved else "false", state_text or "未知"))
        elapsed = (archive_row or {}).get("elapsedMs")
        used = fmt_secs(elapsed / 1000.0) if elapsed else "没记到（归档里缺结束时刻）"
        # 归档里的用时可能不可信：引擎那个状态文件是所有任务共用的一份，被后一轮覆盖过的话，
        # 这一轮就会留下别人的开始/结束时刻（2026-10-04 第 8 轮：真实跑了 360 秒，记成 22 秒）。
        too_short = bool(elapsed) and elapsed < item["duration"] * 1000 * 0.5
        if finished and success:
            log("  √ 跑完了：用时 %s（负载计划 %s）" % (used, fmt_secs(item["duration"])))
            row["note"] = "跑完了"
            if too_short:
                log("  ! 可这个用时比负载计划短太多（%s 对 %s），多半是引擎那个共用状态文件被后一轮"
                    "覆盖过 —— 别用时用这一列；下面的分数是从原始 samples.csv 算的，仍然有效。"
                    % (used, fmt_secs(item["duration"])))
                row["note"] = "跑完了（用时字段不可信：%s，计划 %s）" % (used, fmt_secs(item["duration"]))
        elif finished:
            if resolved is None and (archive_row or {}).get("success") is None:
                log("  × 跑完了、产物也齐了，但 success 查不到（job.json 没落盘、"
                    "summary 的 Final State 也读不到）—— 记成「未确认」，不是「失败」")
                row["note"] = "跑完了，但 success 字段读不到（job.json / summary 都没落）"
            else:
                log("  × 跑完了，但引擎判定这一轮失败（状态 %s）" % (state_text or "未知"))
                row["note"] = "跑完了，但引擎判定失败"
        else:
            log("  × 这一轮没跑成：归档说它结束了，产物却一直没齐"
                "（可能跑崩了，或者指标没算出来）")
            row["note"] = "没跑成：归档不齐（缺 summary/monitor/metric）"

        # 这一轮跑成功了，该对的数据就是好的：后面几轮都不用再掂量建仓的事
        if is_tpcc and success:
            ready_pairs.add(pair)

        time.sleep(max(0, args.settle))
        row["success"] = "true" if success else "false"
        run_dir = os.path.join(batch_dir, combo_name(db, workload, orchestration))
        fill_row_from_history(engine, row, run_id, run_dir, log, history=archive_row)
        fault_items = record_fault_inject(engine, row, run_id, run_dir, log, fault_inject_state)
        finish_monitor(monitor, row, run_id, item["stages"],
                       parse_int((archive_row or {}).get("startTimestamp")), run_dir, log,
                       fault_items=fault_items)
        if row["fault_inject"].startswith("failed"):
            ineffective.append((index, orchestration["name"], row["fault_inject"],
                                row["fault_inject_errors"]))
        elif row["fault_inject"] == "unknown":
            unverifiable += 1

        labels = build_labels(db, workload, orchestration, args.labels)
        label_run(engine, run_id, labels, "resbench_batch.py: %s" % orchestration["path"], log)

        if not success:
            failures += 1
        write_row(writer, handles, row)
        active["phase"] = "idle"
        active["row_written"] = True

        if args.cooldown:
            time.sleep(args.cooldown)

    handles.close()
    RUN_STATE["handles"] = None
    log("")
    log("=" * 78)
    if aborted:
        log("本批次提前停止：计划 %d 轮，只跑到第 %d 轮。原因：%s。"
            % (len(plan), attempted, abort_reason or "见上面最后一条 × 开头的行"))
    log("跑完 %d 轮，失败 %d 轮。结果都在：%s" % (attempted, failures, batch_dir))
    if ineffective:
        log("")
        log("故障没有真的加进去的轮次（这些行的稳定性/恢复/RTO 只反映「没有故障」时的样子）：")
        for index, name, verdict, detail in ineffective:
            log("  [%2d] %-22s %-10s %s" % (index, name, verdict, explain_fault_errors(detail)))
        log("  共 %d 轮。注入的原始结果都留在批次目录的 <运行名>.faults.json 里。" % len(ineffective))
    if unverifiable:
        log("另有 %d 轮无法判断故障到底加进去了没有（CSV 里 fault_inject=unknown，"
            "fault_inject_errors 写了原因）。" % unverifiable)
    log("=" * 78)
    return 1 if (failures or aborted) else 0


def write_row(writer, handles, row):
    writer.writerow(row)
    handles.flush()
    os.fsync(handles.fileno())


def log_result(row, log):
    """把这一轮的成绩说成人话（不是给机器读的字段）。"""
    if as_text(row.get("artifacts_complete")).lower() != "true":
        log("  结果：这一轮的产物不齐（summary / monitor / metric 缺了某几样），分数算不出来 ——"
            " 这不是 0 分，而是没有分数")
        return
    log("  结果：稳定性 %s 分、恢复 %s 分、RTO %s 分（都是 0–100 分，越高越好）"
        % (as_text(row.get("stability")) or "?", as_text(row.get("recovery")) or "?",
           as_text(row.get("rto")) or "?"))


def fill_row_from_history(engine, row, run_id, run_dir, log, history=None):
    """从归档检索里补齐指标与产物信息，并把整行存成 <运行名>.json。

    history 已经在手上就直接用（轮次循环判定"跑完没有"用的就是那一份），
    不再另取一次 —— 免得判据和取值来自归档的两个不同时刻。
    """
    if history is None:
        history = fetch_history_row(engine, run_id)
    if history is None:
        log("  ! 归档里暂时查不到这一轮（可能还没落盘），CSV 这一行只有状态、没有结果")
        row["note"] = (row.get("note") or "") + " | 归档里查不到"
        return

    artifacts = history.get("artifacts") or {}
    metrics = history.get("metrics") or {}

    def put(field, value):
        """归档里读不到就别覆盖已经填好的值（比如 status 里已经拿到的 success）。"""
        if value is not None:
            row[field] = as_text(value)

    put("success", history.get("success"))
    put("state", history.get("state"))
    row["start_time"] = fmt_ts(history.get("startTimestamp"))
    row["end_time"] = fmt_ts(history.get("endTimestamp"))
    elapsed = history.get("elapsedMs")
    put("elapsed_sec", round(elapsed / 1000.0, 1) if elapsed is not None else None)
    put("artifacts_complete", artifacts.get("complete"))
    put("size_bytes", artifacts.get("sizeBytes"))
    put("stability", metrics.get("stability"))
    put("defence", metrics.get("defence"))
    put("recovery", metrics.get("recovery"))
    put("rto", metrics.get("rto"))
    put("outage_seconds", metrics.get("outageSeconds"))
    put("enforced_fault_count", metrics.get("enforcedFaultCount"))

    json_path = os.path.join(run_dir, "%s.json" % run_id)
    try:
        os.makedirs(run_dir, exist_ok=True)
        with open(json_path, "w", encoding="utf-8") as handle:
            json.dump(history, handle, ensure_ascii=False, indent=2)
        row["json_file"] = json_path
    except OSError as exc:
        log("  ! 写 %s 失败：%s" % (json_path, exc))

    log_result(row, log)


def _on_sigterm(signum, frame):
    """SIGTERM（`kill <pid>`）默认是立刻静默死掉，把它接到和 Ctrl+C 同一条收尾路径上。"""
    raise KeyboardInterrupt("SIGTERM")


if __name__ == "__main__":
    # SIGINT 本来就会变成 KeyboardInterrupt，SIGTERM 不会 —— 只补后者。
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, _on_sigterm)
    try:
        sys.exit(main())
    except ConfigError as error:
        print("配置有问题：\n  %s" % error, file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        # 收尾：停掉脚本自己刚提交的那一轮（引擎上若是别人的任务，脚本认得出、不会碰），
        # 并把这一轮如实写进批次 CSV。见 cleanup_after_interrupt。
        cleanup_after_interrupt(RUN_STATE)
        sys.exit(130)