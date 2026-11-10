# -*- coding:utf-8 -*-
import sys
import datetime
import subprocess
import threading

from fastapi import FastAPI, Request, WebSocket, Depends
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
import uvicorn
import asyncio
import ast
import pandas as pd
import copy
import json
from faults import process
from faults import network
from faults import hardware
from faults import command
from faults import check
from faults import channel
from utils.tools import exec

from starlette.websockets import WebSocketState

from fastapi.openapi.docs import (
    get_redoc_html,
    get_swagger_ui_html,
    get_swagger_ui_oauth2_redirect_html,
)

app = FastAPI(docs_url=None, redoc_url=None)  # 创建 api 对象

templates = Jinja2Templates(directory="templates")  # 模板目录

app.include_router(process.router)
app.include_router(network.router)
app.include_router(hardware.router)
app.include_router(command.router)
app.include_router(check.router)
# 常驻命令通道（注入/恢复骑在 t+0 建好的那条 WS 上，见 faults/channel.py）
app.include_router(channel.router)


@app.get("/docs", include_in_schema=False)
async def custom_swagger_ui_html():
    return get_swagger_ui_html(
        openapi_url=app.openapi_url,
        title=app.title + " - DB R Swagger UI",
        oauth2_redirect_url=app.swagger_ui_oauth2_redirect_url,
        swagger_js_url="/static/swagger-ui-bundle.js",
        swagger_css_url="/static/swagger-ui.css",
    )


@app.get(app.swagger_ui_oauth2_redirect_url, include_in_schema=False)
async def swagger_ui_redirect():
    return get_swagger_ui_oauth2_redirect_html()


@app.get("/redoc", include_in_schema=False)
async def redoc_html():
    return get_redoc_html(
        openapi_url=app.openapi_url,
        title=app.title + " - ReDoc",
        redoc_js_url="/static/redoc.standalone.js",
    )


class UserWorkStats:
    def __init__(self, ts, tps, fault_id, stats_pd):
        self.ts = ts
        self.tps = tps
        self.fault_id = fault_id
        self.stats_pd = stats_pd
        self.expected_txn = self.stats_pd.sum(axis=1)
        self.succ_txn = self.stats_pd[0]
        self.abort_txn = self.stats_pd[1]
        self.failed_txn = self.stats_pd.iloc[:, 2:].sum(axis=1)

    def __str__(self):
        return ("ts:%s, fault:%s, tps:%s" %
                (self.ts, self.fault_id, self.tps))


@app.get("/")  # 根路由
def root():
    return {"Hello": "DataBase Reliability benchmark",
            "/docs": "the ready APIs",
            "/redoc": "the ready APIs",
            "/work_stats": "Monitoring the work statistics while fault occurs",
            "/process/instance/{os}/{dbtype}/{inst}/{op}": "start on stop the database instance, e.g: /process/instance/Win/postgres/1/start"}


txn_stats = []
keep_size = 16
pre_req_stats = None
fault_msg = []


@app.get("/work_stats")
async def sync_req_stats(request: Request):
    global txn_stats, keep_size, fault_msg
    return templates.TemplateResponse(
        "index.html",
        {
            "request": request,
            "txn_stats": txn_stats[:keep_size],
            "fault_msg": fault_msg
        }
    )

@app.post("/test")
async def test(request: Request):
    data = await request.json()
    printf(data)


def _dispose_inject(proc):
    """后台收尸线程：等待注入进程结束，完整打印 stdout/stderr 到 agent 日志。

    不保存任何结果到内存/文件——进程最终成败由框架的 /check 验证阶段判定
    （dispatch = 已下发；启动级失败在 Popen 阶段即返回错误）。
    """
    stdout, stderr = proc.communicate()
    print("[AGENT][/inject][%s] finished pid=%s rc=%s" % (
        datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), proc.pid, proc.returncode))
    if stdout:
        print("[AGENT][/inject][STDOUT]\n%s" % stdout)
    if stderr:
        print("[AGENT][/inject][STDERR]\n%s" % stderr)


@app.post("/inject")
async def inject(request: Request):
    # 解析 JSON 请求体

    request_body = await request.json()
    cmd = request_body.get("cmd", "").strip()
    args = request_body.get("args", [])
    # mode: wait（默认，同步执行并等待进程结束，返回最终结果）/ dispatch（启动即返回，后台收尸打印）
    mode = request_body.get("mode", "wait")
    # timeout（秒）：执行硬上限，到点 SIGKILL 子进程并返回 TimeoutExpired。
    # `blade destroy` 没有 --timeout 旗标，撤销本身卡住只能由调用方兜底；
    # 引擎会按 uid 撤销时显式传这个值。老引擎不传 → 保持 60s 的旧行为。
    try:
        timeout = int(request_body.get("timeout", 60))
    except (TypeError, ValueError):
        timeout = 60
    if timeout <= 0:
        timeout = 60
    commands = [cmd] + [str(arg) for arg in args]
    # 空行分隔不同的注入命令，便于阅读日志
    print()
    print()
    print("[AGENT][/inject][%s] received injection request: cmd=%s, args=%s, mode=%s, timeout=%ss"
          % (datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), cmd, args, mode, timeout))
    print("[AGENT][/inject] full command: %s" % " ".join(commands))
    if mode == "dispatch":
        # DBChaos 持续型注入：Popen 启动即返回，进程在后台跑满 duration；
        # 后台线程负责收尸（防僵尸）并把 stdout/stderr 完整打印到 agent 日志，不长期存储。
        try:
            proc = subprocess.Popen(
                commands,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                universal_newlines=True)
            threading.Thread(target=_dispose_inject, args=(proc,), daemon=True).start()
            result = "Executed: Dispatched: pid=%s" % proc.pid
        except FileNotFoundError as e:
            result = "CommandNotFound: %s" % e
        except Exception as e:
            result = "Error: %s" % e
        print("[AGENT][/inject] execution result: %s" % result)
        return {"result": result}
    result = exec(commands, timeout=timeout, capture=bool(request_body.get("capture", False)))
    print("[AGENT][/inject] execution result: %s" % result)
    return {
        "result": result
    }

@app.websocket("/java_side_workload")
async def websocket_endpoint(websocket: WebSocket):
    global txn_stats, keep_size, pre_req_stats, fault_msg
    await websocket.accept()
    while True:
        try:
            if websocket.client_state != WebSocketState.CONNECTED:
                print("Not CONNECTED")
                break

            trxs = await websocket.receive_text()

            msg_secs = trxs.split("||")
#             print(msg_secs)

            succ_ = ast.literal_eval(msg_secs[2].replace("=", ":"))
            err_ = ast.literal_eval(msg_secs[3].replace("=", ":"))
            abort_ = ast.literal_eval(msg_secs[4].replace("=", ":"))
            retry_ = ast.literal_eval(msg_secs[5].replace("=", ":"))
            retryClient_ = ast.literal_eval(msg_secs[6].replace("=", ":"))
            unknown_ = ast.literal_eval(msg_secs[7].replace("=", ":"))

            tps = msg_secs[8]

            fault_msg = msg_secs[9].split("\n")



            stats_by_user = pd.DataFrame([succ_, err_, abort_, retry_, retryClient_, unknown_]).T

            if pre_req_stats is None:
                pre_req_stats = copy.deepcopy(stats_by_user)
                stats_by_user_diff = stats_by_user
            else:
                stats_by_user_diff = stats_by_user - pre_req_stats

            req_stats = UserWorkStats(ts=msg_secs[0],
                                      fault_id=float(msg_secs[1]),
                                      tps=tps,
                                      stats_pd=stats_by_user_diff)

            txn_stats.append(req_stats)

            pre_req_stats = copy.deepcopy(stats_by_user)

            # pre_succ_req = succ_
            # pre_err_req = err_
            # pre_abort_req = abort_
            # pre_retry_req = retry_
            # pre_unknown_req = unknown_

            if len(txn_stats) > keep_size:
                txn_stats.pop(0)

            # print("work_stats: ", req_stats)

            # await asyncio.sleep(1)

        except BaseException as e:
            print(e)
            # raise e
            break


if __name__ == '__main__':
    # export PYTHONPATH="${PYTHONPATH}:/your/agent/root"
    uvicorn.run(app, host="0.0.0.0", port=8000)
