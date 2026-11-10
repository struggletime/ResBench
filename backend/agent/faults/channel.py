# -*- coding:utf-8 -*-
"""常驻命令通道：WS /fault_channel。

引擎在注入之前（网络还健康时）就建好这条 WebSocket，之后注入与恢复都骑在它上面发。
存在的唯一理由：filemove 恢复失败的根因是"在 t+30 那一刻新建 TCP 连接"建不起来
（Connect timed out，connect 与 read 共用 10s 预算，整整 10 秒没完成握手），
而同期引擎到本机的**已有连接**完全健康。复用旧连接，问题就不存在了。

与 POST /inject 的关系：语义完全相同（执行 {cmd, args}，按 mode 决定等不等），
但本轮刻意**不去重构 /inject 共用这十行分发逻辑**——热路径零改动换少量重复。
下一轮可以合并。

协议：
    收 {"id": "...", "path": "/inject", "param": {"cmd":..., "args":[...], "mode":"wait"}}
    回 {"id": "...", "ok": true, "result": "Executed: Successful: None: None", "elapsedMs": 37}

ok 只在**协议层**出问题（请求解析不了 / 不认识的 path）时为 false；
命令本身跑失败仍然 ok=true、把失败串放进 result ——与 HTTP 那条路返回
200 + {"result":"Error: ..."} 完全同形，引擎侧 markResult 才不用分叉。
"""
import asyncio
import datetime
import json
import subprocess
import threading
from functools import partial

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool

from utils.tools import exec

router = APIRouter(tags=["channel"])

# 支持的 path（与 HTTP 端点一一对应）；本轮只放 /inject
SUPPORTED_PATHS = ("/inject",)


def _now():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _log(msg):
    # flush=True：这条通道的日志是"连接在故障窗口里还活着"的唯一证据，
    # 不能让它在缓冲区里过夜。
    print("[AGENT][channel][%s] %s" % (_now(), msg), flush=True)


def _dispose(proc):
    """dispatch 模式的后台收尸线程（与 main.py 的 _dispose_inject 同职责）。"""
    stdout, stderr = proc.communicate()
    _log("dispatch finished pid=%s rc=%s" % (proc.pid, proc.returncode))
    if stdout:
        _log("dispatch STDOUT\n%s" % stdout)
    if stderr:
        _log("dispatch STDERR\n%s" % stderr)


def _run_dispatch(commands):
    """mode=dispatch：Popen 启动即返回，进程在后台跑满 duration，后台线程收尸。"""
    try:
        proc = subprocess.Popen(
            commands,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True)
        threading.Thread(target=_dispose, args=(proc,), daemon=True).start()
        return "Executed: Dispatched: pid=%s" % proc.pid
    except FileNotFoundError as e:
        return "CommandNotFound: %s" % e
    except Exception as e:
        return "Error: %s" % e


async def _run_inject(param):
    """执行一条 /inject 语义的命令，返回结果串（与 POST /inject 逐字一致）。"""
    cmd = str(param.get("cmd", "")).strip()
    args = param.get("args", []) or []
    mode = param.get("mode", "wait")
    commands = [cmd] + [str(arg) for arg in args]

    _log("received injection request: cmd=%s, args=%s, mode=%s" % (cmd, args, mode))
    _log("full command: %s" % " ".join(commands))

    if mode == "dispatch":
        result = _run_dispatch(commands)
        _log("execution result: %s" % result)
        return result

    # 阻塞子进程放线程池。exec() 最长会跑满它自己的 60s 超时，直接在这里 await
    # 会把 FastAPI 的事件循环卡死——卡死的后果不只是慢：loop 不动，
    # 这条 WS 连 ping 都读不了，会看起来像"通道没用"。
    # capture=True（恢复命令）：把子进程 stdout/stderr 回传，引擎才能区分
    # "chaosblade 找不到 chaos_os（已超时自愈）" 与真正的恢复失败。
    result = await run_in_threadpool(
        partial(exec, commands, capture=bool(param.get("capture", False)))
    )
    _log("execution result: %s" % result)
    return result


@router.websocket("/fault_channel")
async def fault_channel(websocket: WebSocket):
    await websocket.accept()
    # 这一行在 agent.log 里必须**每个 run 只出现一次**：出现两次就说明引擎在故障窗口里
    # 又新建了连接，那正是本次要消除的操作。
    _log("client connected: %s" % (websocket.client,))

    try:
        while True:
            text = await websocket.receive_text()
            started = datetime.datetime.now()
            try:
                message = json.loads(text)
            except (ValueError, TypeError) as e:
                _log("could not parse a frame: %s — %s" % (e, text[:200]))
                await websocket.send_json({"id": None, "ok": False, "error": "invalid json: %s" % e})
                continue

            req_id = message.get("id")
            path = message.get("path")
            param = message.get("param") or {}
            try:
                if path not in SUPPORTED_PATHS:
                    raise ValueError("unsupported path: %r" % (path,))
                result = await _run_inject(param)
            except Exception as e:
                # 协议层/分发层出错才 ok=false；命令自己的失败在 result 里，ok 仍为 true
                _log("command failed (id=%s): %s" % (req_id, e))
                await websocket.send_json({"id": req_id, "ok": False, "error": str(e)})
                continue

            elapsed_ms = int((datetime.datetime.now() - started).total_seconds() * 1000)
            _log("replying id=%s elapsedMs=%s" % (req_id, elapsed_ms))
            await websocket.send_json(
                {"id": req_id, "ok": True, "result": result, "elapsedMs": elapsed_ms})
    except WebSocketDisconnect:
        _log("client disconnected")
    except asyncio.CancelledError:
        # 服务端关停：正常路径，别当成异常刷栈
        _log("connection cancelled (server shutting down)")
        raise
    except Exception as e:
        _log("connection error: %s" % e)
        raise
