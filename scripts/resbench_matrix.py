#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""resbench 自动化测试矩阵（直连引擎 8080，fixed-time 模式）。

能力：
  * 测试矩阵：databases × workloads × orchestrations × repeat
  * 组合故障：同一 stage 内的故障同时触发（同 groupKey）；多个 stage 串行（级联）
  * 只跑固定时间模式（autoTimingMode=fixed-time），运行时长可预估、可校验
  * 每轮自动进入引擎的归档体系：传 testName/dbKey/orchestrationId/labels/source，
    跑完用 /web/bench/history/query 把这一轮的指标与产物完整性写进 index.csv
  * 产物：runs/<batch>/index.csv（可 --resume 续跑）、每轮 request.json / schedule.json / result.json
  * 数据库参数：databases[].params 指定了就按它应用（读现状/写库/重启/读回核对都由引擎负责），
    没指定就用数据库现有配置；跑完不还原。调引擎的 /web/bench/db/params（与前端「应用配置」同一份实现）。

用法：
  # 先看矩阵与预计耗时（默认就是 dry-run，不会真的跑）
  python3 scripts/resbench_matrix.py --config scripts/matrix-example.yaml

  # 真的跑（需要引擎已启动、数据库可连）
  python3 scripts/resbench_matrix.py --config scripts/matrix-example.yaml --execute

  # 断点续跑 / 只跑部分编排 / 覆盖重复次数
  python3 scripts/resbench_matrix.py -c cfg.yaml --execute --resume runs/matrix_20260921_120000
  python3 scripts/resbench_matrix.py -c cfg.yaml --execute --only 'comboA,comboB'

  # 只应用数据库参数（不跑矩阵）：先单独验证参数改没改对
  python3 scripts/resbench_matrix.py -c cfg.yaml --apply-db-params oglocal
  python3 scripts/resbench_matrix.py -c cfg.yaml --apply-db-params all
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("需要 PyYAML：pip install pyyaml")

# 与引擎 StartByFormService.buildScheduledFaults 保持一致：
# 第一组故障基准 60s；同组内 planning = 基准 + delay；组结束 = max(planning + duration)；
# 下一组基准 = 上一组结束 + 30s。
STAGE_BASE_SEC = 60
STAGE_GAP_SEC = 30
DEFAULT_TAIL_SEC = 60          # 恢复/核验尾巴（经验值，仅用于预估）
INDEX_FIELDS = [
    "status", "label", "dbKey", "container", "dbParams", "workloadKey", "orchestrationId", "repeat",
    "testName", "historyName", "plannedSeconds", "estimatedSeconds",
    "accepted", "success", "state", "startedAt", "endedAt", "elapsedSeconds",
    "faultCount", "stability", "defence", "recovery", "rto", "throughputRate",
    "complete", "note",
]

# --------------------------------------------------------------------------- #
# 文件类故障：引擎目录里查不到，但 nameKey 有效
# --------------------------------------------------------------------------- #
# 为什么查不到：引擎的 config/fault-cases.yaml 只收了 DBChaos 的 18 个"库内"故障，
# 下面这四个是 chaosblade 的宿主机文件操作，引擎用 switch 直接接
# （StartByFormService.buildScheduledFaults），所以目录接口里没有它们的条目。
# 脚本自带这张表：既让它们能通过 nameKey 校验，也补上引擎那边的默认时长 60s。
#
# auto：--auto-file-targets 打开时，各路径参数替换成的 @auto: 哨兵。
# 取值来自后端 FileFaultTargetService.suggestFor 给每个故障标的**高强度档**推荐项
# （就是前端故障表单点「推荐」会高亮的那一条），不是脚本自己编的：
#   filemove:high     → 整个库目录        @auto:db-dir
#   filemove:dst-dir  → 数据总目录        @auto:pgdata
#   filechmod:high    → 整个库目录        @auto:db-dir
#   fileappend:high   → 最热表的最后一段  @auto:hottest-last-segment
# 哨兵是"按库类型现场解析"的：同一个名字在 PG 上落到 PGDATA，在 MySQL 上落到 @@datadir。
# 数据总目录也可以写中性名 @auto:datadir，与 @auto:pgdata 是同一个值（这里沿用老写法）。
# 例外：fileremoved 后端**没有**推荐规则（suggestFor 里根本没有它的条目），
# 这里取最热文件的第 0 段 @auto:hottest-file，属脚本自定。
FILE_FAULTS: dict[str, dict] = {
    "faultinject.fault.disk.filemove": {
        "label": "File Move",
        "params": ("src-path", "dst-dir"),
        "auto": {"src-path": "@auto:db-dir", "dst-dir": "@auto:pgdata"},
    },
    "faultinject.fault.disk.filechmod": {
        "label": "File Chmod",
        "params": ("filepath", "mark"),
        "auto": {"filepath": "@auto:db-dir"},
    },
    "faultinject.fault.disk.fileappend": {
        "label": "File Append",
        "params": ("filepath", "content"),
        "auto": {"filepath": "@auto:hottest-last-segment"},
    },
    "faultinject.fault.disk.fileremoved": {
        "label": "File Delete",
        "params": ("file-path",),
        "auto": {"file-path": "@auto:hottest-file"},
    },
}
FILE_FAULT_DURATION = 60        # 引擎给这四个故障的默认时长（StartByFormService 里写死 60）


# --------------------------------------------------------------------------- #
# HTTP 小工具
# --------------------------------------------------------------------------- #
def http_json(method: str, url: str, payload: Any = None, params: dict | None = None,
              timeout: int = 30) -> Any:
    if params:
        url = url + ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"{method} {url} -> HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"{method} {url} 连接失败：{error.reason}") from error
    if not body.strip():
        return None
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return body


# --------------------------------------------------------------------------- #
# 配置解析与校验
# --------------------------------------------------------------------------- #
def load_config(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        config = yaml.safe_load(handle) or {}
    for section in ("databases", "workloads", "orchestrations"):
        if not config.get(section):
            raise SystemExit(f"配置缺少 {section} 段：{path}")
    config.setdefault("engine", {})
    config.setdefault("matrix", {})
    config.setdefault("archive", {})
    return config


def fetch_catalog(engine: str) -> dict[str, dict]:
    """从引擎拿故障目录（nameKey -> 定义），用于校验与取默认时长。"""
    items = http_json("GET", f"{engine}/web/faults/catalog") or []
    return {item["nameKey"]: item for item in items}


def fault_label(item: dict) -> str:
    """目录里的 name 可能是双语字典、双语 JSON 字符串，或普通字符串，统一取中文名。"""
    name = item.get("name")
    if isinstance(name, str) and name.strip().startswith("{"):
        try:
            name = json.loads(name)
        except json.JSONDecodeError:
            return name
    if isinstance(name, dict):
        return str(name.get("zh") or name.get("en") or "")
    return str(name or "")


def file_fault_definition(name_key: str) -> dict | None:
    """脚本自带的文件类故障定义，形状与引擎目录条目一致（下游代码不用为它分叉）。"""
    spec = FILE_FAULTS.get(name_key)
    if spec is None:
        return None
    return {
        "nameKey": name_key,
        "name": spec["label"],
        "defaultDuration": FILE_FAULT_DURATION,
        # required 一律 False：引擎对这四类都有 /tmp 下的兜底默认值，不写不会启动失败；
        # 打开 --auto-file-targets 时这些参数反正会被哨兵覆盖。
        "params": [{"paramName": name, "required": False} for name in spec["params"]],
        "scriptBuiltin": True,
    }


def with_file_faults(catalog: dict[str, dict]) -> dict[str, dict]:
    """把脚本自带的文件类故障并进引擎目录（引擎哪天自己收录了，以引擎为准）。"""
    merged = dict(catalog)
    for name_key in FILE_FAULTS:
        merged.setdefault(name_key, file_fault_definition(name_key))
    return merged


def list_faults(catalog: dict[str, dict]) -> None:
    """打印故障目录：nameKey / 名称 / 默认时长 / 必填参数。"""
    builtin_count = sum(1 for key in catalog if key in FILE_FAULTS)
    tail = f"，其中 {builtin_count} 个为脚本自带的文件类故障（标 *）" if builtin_count else ""
    print(f"故障目录：{len(catalog)} 个{tail}（nameKey 必须与此处完全一致，不能自己编）\n")
    print(f"{'nameKey':<52}{'名称':<12}{'默认时长':>8}  必填参数")
    print("-" * 104)
    for key in sorted(catalog):
        item = catalog[key]
        required = [p.get("paramName") for p in (item.get("params") or []) if p.get("required")]
        mark = "*" if key in FILE_FAULTS else " "
        print(f"{mark}{key:<51}{fault_label(item):<12}{str(item.get('defaultDuration') or ''):>8}  "
              f"{','.join(required) or '-'}")
    print("\n提示：必填参数必须写进编排的 params（不写会被引擎拒绝启动）；"
          "duration 不写则用上表默认时长。")
    if builtin_count:
        print("      * 文件类故障不带必填参数，路径不写就用 /tmp 下的兜底值；"
              "加 --auto-file-targets 改用后端推荐的真实路径（@auto: 哨兵）。")


def normalize_stages(orch: dict, catalog: dict, cat_missing_ok: bool = False,
                     auto_file_targets: bool = False) -> list[dict]:
    """把编排定义规整成 [{key, faults:[{nameKey, delay, params}]}]，并校验故障与参数。

    auto_file_targets 打开时，文件类故障的路径参数会被换成 @auto: 哨兵（引擎在开跑
    那一刻查库解析成真实路径）；关掉就按 YAML 里写的原样跑。两种情况下 params 都是
    逐字透传给引擎的，脚本不做解析。
    """
    orch_id = orch.get("id") or orch.get("key")
    if not orch_id:
        raise SystemExit("每个 orchestration 都需要 id")
    stages = orch.get("stages") or []
    if not stages:
        # 允许省略 stages：直接用 faults（全部同一组，同时触发）
        stages = [{"key": "s1", "faults": orch.get("faults") or []}]
    normalized = []
    for index, stage in enumerate(stages, start=1):
        key = str(stage.get("key") or stage.get("id") or f"s{index}")
        faults = stage.get("faults") or []
        if not faults:
            raise SystemExit(f"编排 {orch_id} 的 stage {key} 没有任何故障")
        built = []
        for fault in faults:
            name_key = fault.get("nameKey")
            if not name_key:
                raise SystemExit(f"编排 {orch_id}/{key} 有故障缺少 nameKey")
            definition = catalog.get(name_key)
            if definition is None and not cat_missing_ok:
                available = ", ".join(sorted(catalog))
                raise SystemExit(
                    f"故障 nameKey 不在引擎目录中：{name_key}（编排 {orch_id}/{key}）\n"
                    f"  引擎可用的 {len(catalog)} 个 nameKey：\n    {available}\n"
                    "  查看名称/默认时长/必填参数：python3 scripts/resbench_matrix.py --list-faults")
            params = {str(k): str(v) for k, v in (fault.get("params") or {}).items()}
            # --auto-file-targets：文件类故障的路径改由后端在开跑时推荐。
            # 只覆盖路径参数，其余（mark/content/duration）保持 YAML 里写的样子。
            auto_targets: dict[str, str] = {}
            if auto_file_targets:
                for param_name, sentinel in (FILE_FAULTS.get(name_key) or {}).get("auto", {}).items():
                    auto_targets[param_name] = sentinel
                    params[param_name] = sentinel
            definition = definition or {}
            for param in definition.get("params") or []:
                param_name = param.get("paramName")
                if param.get("required") and param_name not in params and not param.get("defaultValue"):
                    raise SystemExit(
                        f"故障 {name_key} 缺少必填参数 {param_name}（编排 {orch_id}/{key}）")
            duration = params.get("duration")
            if duration is None:
                duration = str(definition.get("defaultDuration") or 60)
            try:
                duration_int = max(1, int(float(duration)))
            except ValueError:
                raise SystemExit(f"故障 {name_key} 的 duration 不是数字：{duration}")
            params["duration"] = str(duration_int)
            built.append({
                "nameKey": name_key,
                "delay": max(0, int(fault.get("delay", 0) or 0)),
                "params": params,
                "duration": duration_int,
                "faultName": fault_label(definition),
                # 只在真的替换过时才有这个键，方便计划阶段原样打出来给人看
                **({"autoTargets": auto_targets} if auto_targets else {}),
            })
        normalized.append({"key": key, "faults": built})
    return normalized


def plan_stages(stages: list[dict]) -> tuple[list[dict], int]:
    """复刻引擎的分组排程，返回(逐故障排程, 最后一次故障结束的秒数)。"""
    schedule: list[dict] = []
    current = STAGE_BASE_SEC
    for stage in stages:
        group_end = current
        for fault in stage["faults"]:
            start = current + fault["delay"]
            schedule.append({
                "stage": stage["key"],
                "nameKey": fault["nameKey"],
                "faultName": fault.get("faultName"),
                "startSeconds": start,
                "endSeconds": start + fault["duration"],
                "durationSeconds": fault["duration"],
                "params": fault["params"],
            })
            group_end = max(group_end, start + fault["duration"])
        current = group_end + STAGE_GAP_SEC
    planned_seconds = max((item["endSeconds"] for item in schedule), default=0)
    return schedule, planned_seconds


def build_faults(stages: list[dict]) -> list[dict]:
    """转成引擎的 faults 数组：同 stage 同 groupKey = 同时触发；delaySeconds 为组内相对延迟。"""
    faults = []
    for stage in stages:
        for fault in stage["faults"]:
            faults.append({
                "nameKey": fault["nameKey"],
                "groupKey": stage["key"],
                "delaySeconds": fault["delay"],
                "params": fault["params"],
            })
    return faults


def redact_database(database: dict) -> dict:
    """run-request.json 里不落连接信息（与引擎 run-meta.json 的口径一致）。"""
    clean = json.loads(json.dumps(database))
    if isinstance(clean.get("standalone"), dict):
        clean["standalone"] = {k: ("******" if k in ("password", "username", "ip", "port") else v)
                               for k, v in clean["standalone"].items()}
    if isinstance(clean.get("primaryStandby"), dict) and isinstance(clean["primaryStandby"].get("nodes"), list):
        clean["primaryStandby"]["nodes"] = [
            {k: ("******" if k in ("password", "username", "ip", "port") else v) for k, v in node.items()}
            for node in clean["primaryStandby"]["nodes"]
        ]
    for key in ("distributed", "embedded"):
        if isinstance(clean.get(key), dict):
            clean[key] = {k: ("******" if "password" in k.lower() else v) for k, v in clean[key].items()}
    return clean


# --------------------------------------------------------------------------- #
# 开跑前预检
# --------------------------------------------------------------------------- #
# --------------------------------------------------------------------------- #
# 数据库参数：调引擎的 /web/bench/db/params 应用（引擎负责写库 + 重启 + 读回核对）
#
# 语义（与用户约定）：
#   * params 指定了 → 按指定值应用；og/pg 的这类参数是 postmaster 上下文，引擎负责重启
#   * params 没指定 → 什么都不做，直接用数据库现有配置
#   * 跑完不还原（保持改后的状态）
#
# 为什么改成调引擎：读现状 / 生成 SQL / 重启 / 轮询重连 / 读回核对这整套逻辑引擎里已经有一份
# （前端「数据库配置 → 应用配置」按钮走的就是它）。脚本再自己实现一遍就会出现两套行为各自演化
# —— 键名契约、取整容差、报错文案都会分叉。现在脚本只负责：把 YAML 里的**真实参数名**翻译成
# 引擎的**语义键**、发请求、把结果映射回原来的 action 词汇（index.csv 与报告都依赖它）。
#
# 引擎侧同一条链路的失败文案是给用户看的（中文），脚本直接把它写进 reason，两边说法一致。
# --------------------------------------------------------------------------- #
PG_BUFFER = "shared_buffers"
MYSQL_BUFFER = "innodb_buffer_pool_size"
CONN_PARAM = "max_connections"

# 只认这几个参数；params 里其他键会被忽略并提示
PARAM_SPECS: dict[str, tuple[str, ...]] = {
    "postgresql": (PG_BUFFER, CONN_PARAM),
    "opengauss": (PG_BUFFER, CONN_PARAM),
    "mysql": (MYSQL_BUFFER, CONN_PARAM),
}
# og/pg：shared_buffers / max_connections 都是 postmaster 上下文 → 必须重启；
# mysql：两个参数 SET GLOBAL 在线生效 → 不重启。
NEEDS_RESTART = {"postgresql": True, "opengauss": True, "mysql": False}

# YAML 里写的是**真实参数名**（归档也用它，便于跟库里对比）；引擎接口认的是**语义键**。
# 两个缓冲池参数映射到同一个语义键（按 dbType 互斥，不会同时出现）。
SEMANTIC_KEYS = {
    PG_BUFFER: "bufferPool",
    MYSQL_BUFFER: "bufferPool",
    CONN_PARAM: "maxConnections",
}

DEFAULT_WAIT_SEC = 180
MIN_WAIT_SEC = 10
MAX_WAIT_SEC = 600                # 与引擎侧的钳位一致（[10, 600]）
DB_PARAM_TIMEOUT_MARGIN_SEC = 120  # 引擎要等重启，HTTP 超时得比 waitSec 宽


def param_support(database: dict) -> tuple[tuple[str, ...], str]:
    """返回 (该库支持的参数名, 不支持的原因)。"""
    db_type = str(database.get("dbType") or "").lower()
    deploy = str(database.get("deploymentType") or "standalone").lower()
    spec = PARAM_SPECS.get(db_type)
    if spec is None or deploy != "standalone":
        return (), f"{db_type or '?'}/{deploy} 暂不支持（只做单机 og/pg/mysql）"
    return spec, ""


def semantics_to_real(actual_names: dict, values: dict | None) -> dict[str, Any]:
    """引擎返回的是语义键（bufferPool…）→ 换成真实参数名（shared_buffers…）。

    引擎的 actualNames 就是这份映射，所以脚本不用再抄第二份；拿不到映射时退回原键名。
    """
    return {str((actual_names or {}).get(key, key)): value
            for key, value in (values or {}).items()}


def apply_db_params(db_key: str, database: dict, engine: str,
                    wait_sec: int | None = None) -> dict:
    """按 databases[].params 调引擎接口应用参数（引擎负责重启并等它连上）。

    不还原。失败不抛异常，全部写进返回值的 action/reason，由调用方决定是否继续。
    action 词汇沿用原来的：applied / already / skipped / unsupported /
    pending-restart / mismatch / failed（后三个由 DB_PARAM_FAILED 判为失败）。
    """
    started = time.time()
    info: dict[str, Any] = {
        "dbKey": db_key, "dbType": database.get("dbType"),
        "deploymentType": database.get("deploymentType", "standalone"),
        "agentPort": int(database.get("agentPort") or 0) or None,
        "action": "skipped", "reason": "", "targets": {}, "before": {}, "after": {},
        "statements": [], "mismatches": [], "unsupported": [],
        "restarted": False, "waitedSec": 0, "applied": False,
        "alreadyEffective": False, "requiresRestart": False,
        "restartCommand": str(database.get("restartCmd") or "").strip(),
        # 库里实际生效的值（真实参数名）→ 归档用；拿不到就空着，由调用方决定退回请求值
        "resolved": {},
    }
    try:
        params = database.get("params") or {}
        spec, why = param_support(database)
        if not params:
            info["reason"] = "未指定 params → 用数据库现有配置"
            return info
        if not spec:
            info.update({"action": "unsupported", "reason": why})
            return info
        targets = {p: params[p] for p in spec if p in params}
        info["unsupported"] = [k for k in params if k not in spec]
        if not targets:
            info["reason"] = f"params 里没有可应用的参数（只认 {'/'.join(spec)}）"
            return info
        info["targets"] = {p: str(v) for p, v in targets.items()}

        missing = [p for p in targets if p not in SEMANTIC_KEYS]
        if missing:
            raise RuntimeError(f"脚本没给这些参数配语义键映射：{', '.join(missing)}")
        wait = int(wait_sec or database.get("waitSec") or DEFAULT_WAIT_SEC)
        payload: dict[str, Any] = {
            "envInfo": str(database.get("envInfo") or ""),
            "dbType": database.get("dbType"),
            "deploymentType": database.get("deploymentType", "standalone"),
            "standalone": database.get("standalone") or {},
            "params": {SEMANTIC_KEYS[p]: str(v) for p, v in targets.items()},
            # 需要重启的库必须显式给命令（引擎不猜 systemd/docker/gs_ctl）；
            # 一律写成 detach（nohup ... &）—— agent 等单条命令的上限是 30s
            "restart": {"command": info["restartCommand"], "waitSec": wait},
        }
        if database.get("agentPort"):
            payload["agentPort"] = int(database["agentPort"])

        engine = str(engine or "").rstrip("/")
        if not engine:
            raise RuntimeError("没有引擎地址：用 --engine 或配置 engine.baseUrl 指定")
        # 引擎最多会占住 waitSec + 一点余量才返回（重启 + 轮询重连）
        timeout = min(max(wait, MIN_WAIT_SEC), MAX_WAIT_SEC) + DB_PARAM_TIMEOUT_MARGIN_SEC
        try:
            response = http_json("POST", f"{engine}/web/bench/db/params",
                                 payload=payload, timeout=timeout) or {}
        except RuntimeError as error:
            if "HTTP 404" in str(error):
                raise RuntimeError(
                    f"引擎没有数据库参数接口（{engine} 是旧构建）：重建并重启引擎后再跑，"
                    "或先把 params 从配置里去掉") from error
            raise

        actual_names = response.get("actualNames") or {}
        info["before"] = semantics_to_real(actual_names, response.get("before"))
        info["after"] = semantics_to_real(actual_names, response.get("after"))
        info["targets"] = semantics_to_real(actual_names, response.get("targets")) or info["targets"]
        info["statements"] = list(response.get("statements") or [])
        info["mismatches"] = list(response.get("mismatches") or [])
        info["applied"] = bool(response.get("applied"))
        info["alreadyEffective"] = bool(response.get("alreadyEffective"))
        info["requiresRestart"] = bool(response.get("requiresRestart"))
        info["restarted"] = bool(response.get("restarted"))
        info["waitedSec"] = int(response.get("waitedSec") or 0)
        info["reason"] = str(response.get("message") or "")
        if response.get("restartCommand"):
            info["restartCommand"] = str(response["restartCommand"])
        ignored = list(response.get("ignored") or [])
        if ignored:
            info["unsupported"] = sorted(set(info["unsupported"]) | set(ignored))
        # 归档用：库里实际生效的值（引擎读回来的）
        info["resolved"] = info["after"] or info["before"]

        if response.get("success"):
            info["action"] = "already" if info["alreadyEffective"] else "applied"
        elif info["mismatches"]:
            info["action"] = "mismatch"
        elif info["applied"] and info["requiresRestart"] and not info["restarted"]:
            # 已写入但没重启成功：没配命令 / agent 下发不了 / 重连超时 —— 原因都在 reason 里
            info["action"] = "pending-restart"
        else:
            info["action"] = "failed"
        return info
    except (RuntimeError, ValueError) as error:
        info.update({"action": "failed", "reason": str(error)[:500]})
        return info
    finally:
        info["elapsedMs"] = int((time.time() - started) * 1000)


DB_PARAM_TAGS = {"applied": "OK", "already": "OK", "skipped": "----",
                 "unsupported": "WARN", "pending-restart": "FAIL",
                 "mismatch": "FAIL", "failed": "FAIL"}


def db_params_summary(info: dict) -> str:
    """给 index.csv 用的一行摘要。"""
    action = info.get("action") or ""
    targets = info.get("targets") or {}
    if action == "applied":
        head = "+".join(f"{k}={v}" for k, v in targets.items())
        return head + ("+restart" if info.get("restarted") else "")
    if action == "already":
        return "already:" + "+".join(f"{k}={v}" for k, v in targets.items())
    return action


def report_db_params(info: dict) -> None:
    key = info["dbKey"]
    action = info.get("action") or ""
    targets = info.get("targets") or {}
    head = ", ".join(f"{k}={v}" for k, v in targets.items())
    line = f"  {DB_PARAM_TAGS.get(action, '?'):>4}  {key}（{info.get('dbType')}）："
    if action == "applied":
        tail = (f"已重启，等 {info['waitedSec']}s 后重连成功"
                if info.get("restarted") else "在线生效，未重启")
        line += f"{head} 已生效（{tail}）"
    elif action == "already":
        line += f"{head} 已是目标值，未重启"
    elif action == "skipped":
        line += info.get("reason") or ""
    else:
        line += info.get("reason") or ""
        if action == "failed" and info.get("applied"):
            line += "（注意：参数可能已写入数据库，本次不还原）"
    print(line)
    if info.get("unsupported"):
        print(f"        （已忽略不认识的参数：{', '.join(info['unsupported'])}）")
    for item in info.get("mismatches") or []:
        print(f"        ! {item}")


DB_PARAM_FAILED = ("failed", "pending-restart", "mismatch")


def db_params_intent(cells: list[dict]) -> list[str]:
    """预检用：打印每个库将要对参数做什么（不连库、不执行）。"""
    lines: list[str] = []
    seen: set[str] = set()
    for cell in cells:
        key = cell["dbKey"]
        if key in seen:
            continue
        seen.add(key)
        database = cell["database"]
        params = database.get("params") or {}
        db_type = str(database.get("dbType") or "").lower()
        # sqlRunner 是"脚本自己连库执行 SQL"时代的产物：参数改由引擎接口应用后它不再被读，
        # 留着会让人以为还在生效，所以提示一句。
        if str(database.get("sqlRunner") or "").strip():
            lines.append(f"        （提示：{key} 的 sqlRunner 已废弃（参数由引擎接口应用），"
                         "可以从配置里删掉）")
        if not params:
            lines.append(f"  ----  {key}：未指定 params，用数据库现有配置")
            continue
        spec, why = param_support(database)
        if not spec:
            lines.append(f"  WARN  {key}：{why}，将跳过")
            continue
        usable = {p: params[p] for p in spec if p in params}
        extra = [k for k in params if k not in spec]
        if not usable:
            lines.append(f"  WARN  {key}：params 里没有可应用的参数（只认 {'/'.join(spec)}），将跳过")
        elif NEEDS_RESTART.get(db_type) and not str(database.get("restartCmd") or "").strip():
            lines.append(f"  FAIL  {key}：要应用 {', '.join(f'{k}={v}' for k, v in usable.items())}，"
                         "但没配 restartCmd（postmaster 类参数必须重启才生效）；"
                         "库跑在容器里就写 nohup docker restart <容器名> >/tmp/resbench_restart.log 2>&1 &"
                         "（必须 detach，agent 等单条命令的上限是 30 秒）")
        else:
            tail = "并重启" if NEEDS_RESTART.get(db_type) else "（在线生效，不重启）"
            lines.append(f"  ----  {key}：将应用 {', '.join(f'{k}={v}' for k, v in usable.items())} {tail}")
        if extra:
            lines.append(f"        （忽略不认识的参数：{', '.join(extra)}）")
    return lines


def probe_db_param_api(cells: list[dict], engine: str, env: str, timeout: int) -> tuple[str, bool]:
    """探一下引擎有没有参数应用接口（旧构建没有 /web/bench/db/params）。

    用**空 params** 探测：引擎在碰数据库之前就会以"请先填写要修改的参数"拒掉，
    所以这个探测不连库、不改任何东西，只证明接口在、且脚本的报文形状能被接受。
    返回 (输出行, 是否可用)。
    """
    if not any((cell["database"].get("params") or {}) for cell in cells):
        return "", True  # 没有库要改参数，引擎新旧都无所谓
    probe = next(cell["database"] for cell in cells if (cell["database"].get("params") or {}))
    payload = {
        "envInfo": env,
        "dbType": probe.get("dbType"),
        "deploymentType": probe.get("deploymentType", "standalone"),
        "standalone": probe.get("standalone") or {},
        "params": {},
    }
    try:
        response = http_json("POST", f"{engine}/web/bench/db/params",
                             payload=payload, timeout=timeout) or {}
    except RuntimeError as error:
        if "HTTP 404" in str(error):
            return ("  FAIL  引擎没有参数应用接口（旧构建）：params 无法生效，"
                    "重建并重启引擎后再跑（或先把 params 从配置里去掉）"), False
        return f"  FAIL  参数应用接口异常：{str(error)[:160]}", False
    if response.get("success"):
        return ("  WARN  参数应用接口自检返回了成功（空 params 不该成功）：请确认引擎版本", True)
    return "  PASS  参数应用接口可用（引擎负责写库 + 重启 + 读回核对）", True


def preflight(cells: list[dict], config: dict, engine: str | None = None) -> tuple[list[str], bool]:
    """逐档数据库做连接预检（引擎 /web/bench/connection/test），并检查归档检索接口是否可用。

    返回 (输出行, 数据库是否全部可用)。旧构建可能没有这两个接口，此时给出 SKIP/WARN 而不算失败。
    engine 由调用方传入（好让 --engine 生效）；不传则退回配置里的 baseUrl。
    """
    engine_conf = config.get("engine", {})
    engine = str(engine or engine_conf.get("baseUrl", "http://127.0.0.1:8080")).rstrip("/")
    env = engine_conf.get("env", "openGauss")
    lines: list[str] = []
    ok = True
    checked: dict[str, bool] = {}
    for cell in cells:
        key = cell["dbKey"]
        if key in checked:
            continue
        db = cell["database"]
        payload = {
            "envInfo": env,
            "dbType": db.get("dbType"),
            "deploymentType": db.get("deploymentType", "standalone"),
            "standalone": db.get("standalone") or {},
            "distributed": db.get("distributed") or {},
        }
        try:
            resp = http_json("POST", f"{engine}/web/bench/connection/test", payload=payload,
                             timeout=int(engine_conf.get("preflightTimeoutSec", 60) or 60)) or {}
        except RuntimeError as error:
            if "HTTP 404" in str(error):
                checked[key] = True
                lines.append(f"  SKIP  {key}（{db.get('dbType')}）：引擎没有连接测试接口，"
                             "当前是旧构建，重建重启后可预检")
            else:
                checked[key] = False
                ok = False
                lines.append(f"  FAIL  {key}（{db.get('dbType')}）：{str(error)[:160]}")
            continue
        if resp.get("success"):
            checked[key] = True
            lines.append(f"  PASS  {key}（{db.get('dbType')}）连接成功：{resp.get('message') or ''}")
        else:
            checked[key] = False
            ok = False
            lines.append(f"  FAIL  {key}（{db.get('dbType')}）：{resp.get('message') or '连接失败'}")

    # 配了 container 的库：提前讲清楚故障打到哪儿，避免"以为注入到容器里、其实打在宿主机"。
    for key, container in dict(
            (cell["dbKey"], str(cell["database"].get("container") or "").strip()) for cell in cells).items():
        if container:
            lines.append(f"  ----  {key}：chaosblade 类故障将在容器 {container} 内注入"
                         "（docker exec；网络类故障仍走宿主机）")

    try:
        http_json("GET", f"{engine}/web/bench/history/query", params={"size": "1"}, timeout=30)
        lines.append("  PASS  归档检索接口可用：index.csv 会带上指标与产物完整性")
    except RuntimeError as error:
        if "HTTP 404" in str(error):
            lines.append("  WARN  归档检索接口不可用（旧构建）：矩阵照常跑，但 index.csv 不填指标列")
        else:
            lines.append(f"  WARN  归档检索接口异常：{str(error)[:160]}")

    lines.append("")
    lines.append("数据库参数（params 指定了就按它应用、需要重启的由引擎重启；没指定就用现有配置）：")
    probe_line, probe_ok = probe_db_param_api(
        cells, engine, env, int(engine_conf.get("preflightTimeoutSec", 60) or 60))
    if probe_line:
        lines.append(probe_line)
    if not probe_ok:
        ok = False
    param_lines = db_params_intent(cells)
    lines.extend(param_lines)
    if any(item.startswith("  FAIL") for item in param_lines):
        ok = False
    return lines, ok


# --------------------------------------------------------------------------- #
# 矩阵展开
# --------------------------------------------------------------------------- #
def slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.+-]", "-", str(value))


def build_cells(config: dict, catalog: dict, repeats_override: int | None,
                only: str | None, cat_missing_ok: bool = False,
                auto_file_targets: bool = False) -> list[dict]:
    matrix = config.get("matrix") or {}
    repeats = repeats_override or int(matrix.get("repeat", 1) or 1)
    exclude = {str(item) for item in (matrix.get("exclude") or [])}
    only_set = {item.strip() for item in only.split(",")} if only else set()

    orchestrations = []
    for orch in config["orchestrations"]:
        stages = normalize_stages(orch, catalog, cat_missing_ok=cat_missing_ok,
                                  auto_file_targets=auto_file_targets)
        schedule, planned_seconds = plan_stages(stages)
        orchestrations.append({
            "id": str(orch.get("id")),
            "note": orch.get("note"),
            "stages": stages,
            "schedule": schedule,
            "plannedSeconds": planned_seconds,
        })

    cells = []
    for db_key, database in config["databases"].items():
        for workload_key, workload in config["workloads"].items():
            for orch in orchestrations:
                if only_set and orch["id"] not in only_set and str(orch.get("id")) not in only_set:
                    continue
                for repeat in range(1, repeats + 1):
                    label = f"{slug(db_key)}_{slug(workload_key)}_{slug(orch['id'])}_r{repeat}"
                    if label in exclude or f"{db_key}@{workload_key}" in exclude:
                        continue
                    cells.append({
                        "label": label,
                        "dbKey": str(db_key),
                        "workloadKey": str(workload_key),
                        "database": database,
                        "workload": workload,
                        "orchestrationId": orch["id"],
                        "orchestrationNote": orch.get("note"),
                        "stages": orch["stages"],
                        "schedule": orch["schedule"],
                        "plannedSeconds": orch["plannedSeconds"],
                        "repeat": repeat,
                    })
    return cells


def estimated_seconds(cell: dict, config: dict) -> int:
    workload_seconds = int(float((cell["workload"].get("params") or {}).get("duration", 120)))
    overhead = int((cell["workload"].get("estimatedOverheadSec")
                    or config.get("engine", {}).get("estimatedOverheadSec", 120)) or 0)
    tail = int(config.get("engine", {}).get("tailSec", DEFAULT_TAIL_SEC) or 0)
    return max(workload_seconds, cell["plannedSeconds"]) + overhead + tail


def validate_cells(cells: list[dict], config: dict) -> list[str]:
    problems = []
    for cell in cells:
        workload_seconds = int(float((cell["workload"].get("params") or {}).get("duration", 120)))
        needed = cell["plannedSeconds"] + int(config.get("engine", {}).get("minSlackSec", 30) or 0)
        if workload_seconds < cell["plannedSeconds"]:
            problems.append(
                f"{cell['label']}：workload duration={workload_seconds}s 小于故障计划 "
                f"{cell['plannedSeconds']}s，最后一组故障会被截断")
        elif workload_seconds < needed:
            problems.append(
                f"{cell['label']}：workload duration={workload_seconds}s 仅比故障计划 "
                f"{cell['plannedSeconds']}s 多 {workload_seconds - cell['plannedSeconds']}s，建议留 ≥30s")
        db_type = str(cell["database"].get("dbType", "")).lower()
        deployment = str(cell["database"].get("deploymentType", "")).lower()
        if deployment == "distributed" and db_type not in ("oceanbase", "gaussdb"):
            problems.append(f"{cell['label']}：分布式部署只支持 oceanbase/gaussdb（当前 {db_type}）")
        workload_name = str(cell["workload"].get("name", "")).lower()
        if db_type == "oceanbase" and workload_name == "tpcc":
            problems.append(f"{cell['label']}：oceanbase 目前不支持 tpcc")
    return problems


def fmt_duration(seconds: float) -> str:
    seconds = int(seconds)
    hours, remainder = divmod(seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}h{minutes:02d}m"
    if minutes:
        return f"{minutes}m{secs:02d}s"
    return f"{secs}s"


def cell_reload(cell: dict) -> bool:
    """这一轮引擎会不会先 reload（DROP + CREATE 再灌数据）。

    与 build_request 里写进 runOptions 的判定必须一致：显式配的 reload，
    加上 tpcc 每组第一轮强制 reload。
    """
    workload = cell["workload"]
    reload = bool((workload.get("runOptions") or {}).get("reload"))
    if str(workload.get("name", "")).lower() == "tpcc" and cell.get("firstOfGroup"):
        reload = True
    return reload


def auto_target_notes(cells: list[dict]) -> tuple[list[str], list[str]]:
    """--auto-file-targets 的计划期回执：返回(替换清单, 会在建仓之后才解析的轮次)。

    哨兵要在库里查表才能定位真实文件。reload=true 会先 DROP + CREATE 再灌数据，
    那时候文件号才生成 —— 所以引擎在提交时**故意不解析**，等 job 建完再解析，
    跑的正是「先建仓完成 → 再确认文件故障的参数 → 再从第一个测试开始跑」。
    脚本事先能算出哪些轮属于这种情况，先说一声，免得看到日志里没解析就以为没生效。
    """
    notes: list[str] = []
    deferred: list[str] = []
    for cell in cells:
        substituted: list[str] = []
        for stage in cell["stages"]:
            for fault in stage["faults"]:
                if not fault.get("autoTargets"):
                    continue
                pairs = "、".join(f"{k} → {v}" for k, v in fault["autoTargets"].items())
                substituted.append(f"{fault['faultName']}（{pairs}）")
        if not substituted:
            continue
        notes.append(f"{cell['label']}：{len(substituted)} 个文件类故障改用推荐参数 —— "
                     + "；".join(substituted))
        if cell_reload(cell):
            deferred.append(
                f"{cell['label']}：这一轮带建仓（reload=true）→ 哨兵在**建仓完成之后**才解析，"
                "不用先跑一轮预热。")
    return notes, deferred


def print_matrix(cells: list[dict], config: dict, problems: list[str]) -> None:
    print(f"\n矩阵共 {len(cells)} 轮：")
    print(f"{'label':<52}{'编排':<12}{'计划(s)':>9}{'预计单轮':>10}")
    for cell in cells:
        print(f"{cell['label']:<52}{str(cell['orchestrationId']):<12}"
              f"{cell['plannedSeconds']:>9}{fmt_duration(estimated_seconds(cell, config)):>10}")
    total = sum(estimated_seconds(cell, config) for cell in cells)
    cooldown = int(config.get("engine", {}).get("cooldownSec", 30) or 0)
    total += cooldown * max(0, len(cells) - 1)
    print(f"\n预计总时长 ≈ {fmt_duration(total)}（含每轮冷却 {cooldown}s；装载/建表开销为估算值）")
    if problems:
        print("\n校验提醒：")
        for problem in problems:
            print(f"  ! {problem}")
    else:
        print("\n校验通过：故障目录、必填参数、负载时长与部署类型组合都没问题。")


# --------------------------------------------------------------------------- #
# 执行
# --------------------------------------------------------------------------- #
def build_request(cell: dict, config: dict, applied: dict | None = None) -> dict:
    engine_conf = config.get("engine", {})
    archive = config.get("archive", {})
    workload = cell["workload"]
    database = cell["database"]
    run_options = {
        "monitorIntervalSec": int(engine_conf.get("monitorIntervalSec", 1) or 1),
        "create": True, "load": True, "execute": True,
        # 判定统一走 cell_reload，别再在这里重写一遍（计划期的 reload 冲突检查要用同一个）
        "reload": cell_reload(cell),
    }
    labels = list(archive.get("labels") or [])
    labels.extend(workload.get("labels") or [])
    request = {
        "envInfo": engine_conf.get("env", "openGauss"),
        # testName 会被引擎拼成 <workload>_<dbKey>_<workloadKey>_<编排>_r<n>_<时间戳>
        "testName": f"{cell['dbKey']}_{cell['workloadKey']}_{cell['orchestrationId']}_r{cell['repeat']}",
        "dbType": database.get("dbType"),
        "workload": {"name": workload.get("name"), "params": workload.get("params") or {}},
        "database": {
            "deploymentType": database.get("deploymentType", "standalone"),
            "standalone": database.get("standalone") or {},
            "primaryStandby": database.get("primaryStandby") or {},
            "distributed": database.get("distributed") or {},
            "embedded": database.get("embedded") or {},
            # 数据库配置参数（非连接信息）会进入归档，可检索可对比。
            # applied 是引擎读回来的**实际生效值**（真实参数名，例如 MySQL 把 100M 取整成 128M
            # 时给的就是 128M）；引擎没能报告时退回配置里写的请求值，至少不丢信息。
            "params": dict(applied or database.get("params") or {}),
        },
        "faults": build_faults(cell["stages"]),
        "autoTimingMode": "fixed-time",
        "runOptions": run_options,
        "orchestrationId": str(cell["orchestrationId"]),
        "dbKey": cell["dbKey"],
        "source": "script",
        "labels": labels,
        "note": cell.get("orchestrationNote") or archive.get("note") or "",
    }
    # 容器名是数据库部署的属性（一次运行只有一个），配在 databases.<key>.container 下。
    # 非空时引擎把 chaosblade 类故障套 docker exec 在容器内注入；网络类故障仍走宿主机。
    container = str(database.get("container") or "").strip()
    if container:
        request["containerName"] = container
    return request


def wait_for_run(engine: str, history_name: str, budget_seconds: int, poll: int,
                 start_timeout: int) -> dict:
    """两段式等待：先等任务真正跑起来，再等结束。返回最后一次 status。"""
    deadline = time.time() + start_timeout
    status: dict = {}
    while time.time() < deadline:
        status = http_json("GET", f"{engine}/web/bench/status", params={"historyName": history_name}) or {}
        if status.get("startTimestamp") or status.get("endTimestamp"):
            break
        time.sleep(poll)
    else:
        raise RuntimeError(f"等待启动超时（{start_timeout}s）：{history_name}")

    if not status.get("end"):
        end_deadline = time.time() + max(60, budget_seconds)
        while time.time() < end_deadline:
            time.sleep(poll)
            status = http_json("GET", f"{engine}/web/bench/status", params={"historyName": history_name}) or {}
            if status.get("end"):
                break
        else:
            raise RuntimeError(f"等待结束超时（{fmt_duration(budget_seconds)}）：{history_name}")
    return status


def fetch_archive_row(engine: str, history_name: str) -> dict:
    try:
        response = http_json("GET", f"{engine}/web/bench/history/query",
                             params={"keyword": history_name, "size": 5}) or {}
        for row in response.get("rows") or []:
            if row.get("historyName") == history_name:
                return row
    except RuntimeError as error:
        print(f"    ! 归档检索失败（不影响测试本身）：{error}")
    return {}


def write_index(index_path: Path, rows: list[dict]) -> None:
    with index_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=INDEX_FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field, "") for field in INDEX_FIELDS})


def run_cells(cells: list[dict], config: dict, out_dir: Path, resume: bool,
              force: bool = False) -> int:
    engine_conf = config["engine"]
    engine = str(engine_conf.get("baseUrl", "http://127.0.0.1:8080")).rstrip("/")
    poll = int(engine_conf.get("pollIntervalSec", 5) or 5)
    start_timeout = int(engine_conf.get("startTimeoutSec", 180) or 180)
    cooldown = int(engine_conf.get("cooldownSec", 30) or 0)
    margin = int(engine_conf.get("endMarginSec", 300) or 0)
    settle = int(engine_conf.get("settleSec", 20) or 0)

    index_path = out_dir / "index.csv"
    rows: list[dict] = []
    done_labels: set[str] = set()
    if resume and index_path.exists():
        with index_path.open(encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                rows.append(row)
                if row.get("status") == "done" and str(row.get("success")).lower() == "true":
                    done_labels.add(row.get("label"))
        print(f"续跑：已有 {len(rows)} 条记录，其中 {len(done_labels)} 轮已成功，将跳过")

    # 每个 (库, 负载) 组的第一轮开 reload（tpcc 建表加载需要）
    seen_groups: set[tuple] = set()
    for cell in cells:
        group = (cell["dbKey"], cell["workloadKey"])
        cell["firstOfGroup"] = group not in seen_groups
        seen_groups.add(group)

    failures = 0
    executed = 0
    db_state: dict[str, dict] = {}
    for order, cell in enumerate(cells, start=1):
        if cell["label"] in done_labels:
            print(f"[{order}/{len(cells)}] 跳过（已完成）：{cell['label']}")
            continue

        # 换库了才动手：params 指定了就应用（需要重启的重启），没指定就什么都不做。
        # 不还原 —— 与用户约定：跑完保持改后的状态。
        if cell["dbKey"] not in db_state:
            print(f"[{order}/{len(cells)}] 数据库 {cell['dbKey']} 参数检查")
            info = apply_db_params(cell["dbKey"], cell["database"], engine)
            report_db_params(info)
            db_state[cell["dbKey"]] = info
            if info["action"] in DB_PARAM_FAILED and not force:
                print("\n数据库参数没到位：为避免把这一批的数据按错误的配置记录，中止执行。")
                print("修好配置后重跑，或加 --force 强行继续。")
                break

        cell_dir = out_dir / "cells" / cell["label"]
        cell_dir.mkdir(parents=True, exist_ok=True)
        request = build_request(cell, config, applied=db_state[cell["dbKey"]].get("resolved"))
        (cell_dir / "request.json").write_text(
            json.dumps({**request, "database": redact_database(request["database"])},
                       ensure_ascii=False, indent=2), encoding="utf-8")
        (cell_dir / "schedule.json").write_text(
            json.dumps({"label": cell["label"], "plannedSeconds": cell["plannedSeconds"],
                        "stages": [s["key"] for s in cell["stages"]],
                        "schedule": cell["schedule"]}, ensure_ascii=False, indent=2),
            encoding="utf-8")

        estimate = estimated_seconds(cell, config)
        print(f"[{order}/{len(cells)}] 开始 {cell['label']}"
              f"（编排={cell['orchestrationId']}，故障={len(request['faults'])} 个，"
              f"计划 {cell['plannedSeconds']}s，预计 {fmt_duration(estimate)}）")
        row = {field: "" for field in INDEX_FIELDS}
        row.update({"label": cell["label"], "dbKey": cell["dbKey"],
                    "container": str(cell["database"].get("container") or "").strip(),
                    "dbParams": db_params_summary(db_state[cell["dbKey"]]),
                    "workloadKey": cell["workloadKey"],
                    "orchestrationId": cell["orchestrationId"], "repeat": cell["repeat"],
                    "testName": request["testName"], "plannedSeconds": cell["plannedSeconds"],
                    "estimatedSeconds": estimate, "faultCount": len(request["faults"]),
                    "note": request["note"]})
        started = datetime.now()
        row["startedAt"] = started.strftime("%Y-%m-%d %H:%M:%S")
        try:
            response = http_json("POST", f"{engine}/web/bench/startByForm", payload=request,
                                 timeout=120) or {}
        except RuntimeError as error:
            row.update({"status": "failed", "accepted": False, "success": False, "note": str(error)[:200]})
            print(f"    ! 提交失败：{error}")
            rows.append(row)
            write_index(index_path, rows)
            failures += 1
            continue

        row["accepted"] = bool(response.get("accepted"))
        row["historyName"] = response.get("historyName") or ""
        if not response.get("accepted"):
            row.update({"status": "failed", "success": False,
                        "note": (response.get("message") or "引擎拒绝启动")[:200]})
            print(f"    ! 引擎拒绝：{response.get('message')}")
            rows.append(row)
            write_index(index_path, rows)
            failures += 1
            continue

        executed += 1
        print(f"    historyName={row['historyName']}")
        try:
            status = wait_for_run(engine, row["historyName"],
                                  budget_seconds=estimate + margin, poll=poll,
                                  start_timeout=start_timeout)
            row["success"] = bool(status.get("success"))
            row["state"] = status.get("message") or ""
        except RuntimeError as error:
            row.update({"status": "failed", "success": False, "note": str(error)[:200]})
            print(f"    ! {error}")
            failures += 1

        if settle:
            time.sleep(settle)
        archive_row = fetch_archive_row(engine, row["historyName"])
        if archive_row:
            metrics = archive_row.get("metrics") or {}
            artifacts = archive_row.get("artifacts") or {}
            row["stability"] = metrics.get("stability", "")
            row["defence"] = metrics.get("defence", "")
            row["recovery"] = metrics.get("recovery", "")
            row["rto"] = metrics.get("rto", "")
            row["throughputRate"] = metrics.get("throughputRate", "")
            row["complete"] = artifacts.get("complete", "")
            (cell_dir / "result.json").write_text(
                json.dumps(archive_row, ensure_ascii=False, indent=2), encoding="utf-8")
        if not row["status"]:
            row["status"] = "done" if row["success"] is True else "failed"
        ended = datetime.now()
        row["endedAt"] = ended.strftime("%Y-%m-%d %H:%M:%S")
        row["elapsedSeconds"] = int((ended - started).total_seconds())
        rows.append(row)
        write_index(index_path, rows)
        print(f"    结果：success={row['success']} 用时={fmt_duration(row['elapsedSeconds'])}"
              f" complete={row['complete']}")
        if cooldown and order < len(cells):
            time.sleep(cooldown)

    if db_state:
        (out_dir / "db-params.json").write_text(
            json.dumps(db_state, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"数据库参数记录：{out_dir / 'db-params.json'}")

    succeeded = sum(1 for row in rows if str(row.get("success")).lower() == "true")
    print(f"\n完成：成功 {succeeded} 轮，失败/未执行 {len(cells) - succeeded} 轮；"
          f"索引 = {index_path}")
    return 0 if failures == 0 else 1


# --------------------------------------------------------------------------- #
def main() -> int:
    parser = argparse.ArgumentParser(description="resbench 自动化测试矩阵（fixed-time，多编排，组合故障）")
    parser.add_argument("-c", "--config", type=Path, help="矩阵配置 YAML")
    parser.add_argument("--engine", help="引擎地址（默认取配置里的 engine.baseUrl，或 http://127.0.0.1:8080）")
    parser.add_argument("--list-faults", action="store_true",
                        help="只打印引擎故障目录（nameKey/名称/默认时长/必填参数），不跑矩阵")
    parser.add_argument("--execute", action="store_true", help="真的执行（默认只做 dry-run）")
    parser.add_argument("--resume", metavar="BATCH_DIR", type=Path,
                        help="在已有批次目录上续跑（跳过已成功的轮次）")
    parser.add_argument("--out", type=Path, help="批次输出目录（默认 runs/matrix_<时间戳>）")
    parser.add_argument("--only", help="只跑这些编排 id，逗号分隔")
    parser.add_argument("--repeat", type=int, help="覆盖配置里的 repeat")
    parser.add_argument("--skip-catalog-check", action="store_true",
                        help="不连引擎校验故障目录（离线 dry-run 用）")
    parser.add_argument("--auto-file-targets", action="store_true",
                        help="文件类故障用后端推荐的真实路径（@auto: 哨兵）跑；"
                             "不加则按 YAML 里写的参数跑")
    parser.add_argument("--preflight-only", action="store_true",
                        help="只做环境预检（数据库连通性 + 归档接口），不跑矩阵")
    parser.add_argument("--skip-preflight", action="store_true",
                        help="执行时跳过预检（--execute 默认会先预检）")
    parser.add_argument("--force", action="store_true",
                        help="预检失败 / 数据库参数没到位也继续执行")
    parser.add_argument("--apply-db-params", metavar="DBKEY[,DBKEY]|all",
                        help="只应用这些库的 params（需要重启的负责重启并等它连上）后退出，不跑矩阵")
    args = parser.parse_args()

    # --list-faults：不需要配置文件，直接问引擎要目录
    if args.list_faults:
        engine = (args.engine or "http://127.0.0.1:8080").rstrip("/")
        try:
            list_faults(with_file_faults(fetch_catalog(engine)))
            return 0
        except RuntimeError as error:
            print(f"读取故障目录失败：{error}")
            return 2

    if not args.config:
        parser.error("需要 --config <矩阵配置 YAML>（或先用 --list-faults 查看引擎故障目录）")
    config = load_config(args.config)
    # 引擎地址（--list-faults 分支已单独处理）：参数应用也要经过引擎，所以先解析出来
    engine = (args.engine or str(config.get("engine", {}).get("baseUrl", "http://127.0.0.1:8080"))).rstrip("/")

    # --apply-db-params：只做参数应用（不跑矩阵；参数由引擎接口应用，所以需要引擎在跑）
    if args.apply_db_params:
        databases = config.get("databases") or {}
        wanted = [k.strip() for k in args.apply_db_params.split(",") if k.strip()]
        if len(wanted) == 1 and wanted[0].lower() == "all":
            wanted = list(databases)
        if not wanted:
            print("--apply-db-params 没给出库名")
            return 2
        unknown = [k for k in wanted if k not in databases]
        if unknown:
            print(f"配置里没有这些库：{', '.join(unknown)}；可选：{', '.join(databases)}")
            return 2
        print(f"数据库参数应用：{', '.join(wanted)}（params 没指定的库会跳过）")
        state: dict[str, dict] = {}
        bad = False
        for key in wanted:
            info = apply_db_params(key, databases[key], engine)
            report_db_params(info)
            state[key] = info
            bad = bad or info["action"] in DB_PARAM_FAILED
        target = args.out or (Path("runs") / f"db-params_{datetime.now():%Y%m%d_%H%M%S}")
        target.mkdir(parents=True, exist_ok=True)
        (target / "db-params.json").write_text(
            json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n记录：{target / 'db-params.json'}")
        if bad:
            print("有库的参数没到位（见上）。")
            return 1
        print("完成。")
        return 0

    catalog: dict[str, dict] = {}
    if not args.skip_catalog_check:
        try:
            catalog = fetch_catalog(engine)
            print(f"已从引擎读取故障目录：{len(catalog)} 个 nameKey")
        except RuntimeError as error:
            print(f"读取故障目录失败：{error}")
            print("提示：离线预览可加 --skip-catalog-check（会跳过 nameKey 校验）")
            return 2
    else:
        print("已跳过故障目录校验（离线模式）")
    # 文件类故障不在引擎目录里（引擎用 switch 直接接），这里补上，别让它们卡在 nameKey 校验上
    catalog = with_file_faults(catalog)

    cells = build_cells(config, catalog, args.repeat, args.only,
                        cat_missing_ok=args.skip_catalog_check,
                        auto_file_targets=args.auto_file_targets)
    if not cells:
        print("矩阵为空：检查 exclude / only / repeat 设置")
        return 2
    problems = validate_cells(cells, config)
    if args.auto_file_targets:
        auto_notes, auto_deferred = auto_target_notes(cells)
        if auto_notes:
            print(f"\n--auto-file-targets：{len(auto_notes)} 轮的文件类故障改用后端推荐参数")
            for note in auto_notes:
                print(f"  → {note}")
        else:
            print("\n--auto-file-targets：矩阵里没有文件类故障，本次不生效。")
        # 建仓轮只是解析得晚一点，不是问题，不该挡着不让跑
        for note in auto_deferred:
            print(f"  ! {note}")
    print_matrix(cells, config, problems)

    if args.preflight_only or (args.execute and not args.skip_preflight):
        print("\n预检中（数据库连接 + 归档接口）...")
        lines, preflight_ok = preflight(cells, config, engine)
        print("\n".join(lines))
        if args.preflight_only:
            print("\n预检完成。" if preflight_ok else "\n预检发现问题（见上）。")
            return 0 if preflight_ok else 3
        if not preflight_ok and not args.force:
            print("\n预检失败：有数据库连不上。修好配置后重跑，或加 --force 强行执行。")
            return 3

    out_dir = args.resume or args.out
    if not args.execute:
        print("\n这是 dry-run（未提交任何任务）。加 --execute 才会真的跑。")
        return 0
    if not out_dir:
        out_dir = Path("runs") / f"matrix_{datetime.now():%Y%m%d_%H%M%S}"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "matrix.json").write_text(
        json.dumps({"config": str(args.config), "cells": [
            {k: v for k, v in cell.items() if k not in ("database", "stages")} for cell in cells
        ]}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n批次目录：{out_dir}")
    return run_cells(cells, config, out_dir, resume=bool(args.resume), force=args.force)


if __name__ == "__main__":
    sys.exit(main())