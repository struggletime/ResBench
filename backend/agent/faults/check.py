# -*- coding:utf-8 -*-
"""Agent 自检桥接端点：POST /check。

与 /inject 同构：收到 {cmd, args} 后在本机执行命令，但语义是"故障自检"。
agent 不做任何故障语义判断，只做三件事：
  1. 执行命令（复用 utils.tools.exec_capture，30s 兜底超时）
  2. 校验 stdout 是否为契约 JSON（{"enforced":...,"checks":[...]}）
  3. 错误包装（returncode != 0 / stdout 非 JSON）
"""
import datetime
import json
import re

from fastapi import APIRouter, Request
from fastapi.concurrency import run_in_threadpool

from utils.tools import exec_capture

router = APIRouter(tags=["check"])

# 与后端 FaultVerifier.maxRequestTimeoutMs(15s) 对齐：agent 先返回超时错误，
# 避免被后端断连后仍占用线程池；且长 check 不再阻塞 FastAPI 事件循环（见 /check 注释）
CHECK_TIMEOUT_SECONDS = 12
INVALID_PREFIX = "invalid self-check output: "

# 剥离 ANSI 转义序列（DBChaos CLI 会输出 ^[[33m 等颜色码，污染日志与错误消息）
ANSI_RE = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")


def _strip_ansi(text):
    if not text:
        return text
    return ANSI_RE.sub("", text)


def _extract_json_object(text):
    """宽容解析：优先整体解析；失败时截取第一个 { 到最后一个 } 之间的内容。"""
    text = (text or "").strip()
    if not text:
        return None
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else None
    except (ValueError, TypeError):
        pass
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end == -1 or end <= start:
        return None
    try:
        parsed = json.loads(text[start:end + 1])
        return parsed if isinstance(parsed, dict) else None
    except (ValueError, TypeError):
        return None


@router.post("/check")
async def check(request: Request):
    request_body = await request.json()
    cmd = str(request_body.get("cmd", "")).strip()
    args = request_body.get("args", []) or []
    commands = [cmd] + [str(arg) for arg in args]

    print()
    print("[AGENT][/check][%s] received self-check request: cmd=%s, args=%s"
          % (datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), cmd, args))
    print("[AGENT][/check] full command: %s" % " ".join(commands))

    if not cmd:
        return {"error": "empty cmd"}

    # 阻塞子进程调用放线程池：单条长 check（如 fake-check timeout）不再卡住
    # FastAPI 事件循环，否则并发到达的其他故障 /check 会排队读到后端超时
    # （表现为后端 RestfulCaller 抛 "Access request failed. path /check"）。
    returncode, stdout, stderr, timed_out = await run_in_threadpool(
        exec_capture, commands, timeout=CHECK_TIMEOUT_SECONDS
    )

    if returncode != 0:
        reason = stderr.strip() if stderr and stderr.strip() else ("timed out" if timed_out else "unknown error")
        print("[AGENT][/check] self-check command failed: rc=%s reason=%s" % (returncode, reason))
        return {"error": reason, "returncode": returncode}

    parsed = _extract_json_object(stdout)
    if parsed is None:
        snippet = _strip_ansi(stdout or "").strip()[:200]
        print("[AGENT][/check] stdout is not a contract JSON: %s" % snippet)
        return {"error": INVALID_PREFIX + snippet}

    print("[AGENT][/check] self-check result: %s" % _strip_ansi(stdout or "").strip())
    # 原样透传契约 JSON（enforced/summary/checks 由 DBChaos 产生，resbench 消费）
    return parsed