#!/usr/bin/env python3
"""netem qdisc 指标导出器 —— 让「乱序率」出现在 Prometheus / 监控页上。

## 数据来源

    tc -s qdisc show dev <dev>

    qdisc netem 8001: root refcnt 2 limit 1000 delay 10.0ms reorder 90% 80% gap 1
     Sent 5077220 bytes 3798 pkt (dropped 0, overlimits 0 requeues 3399)
     backlog 0b 0p requeues 3399

括号里的 **`requeues` 就是 netem 判定「需要重排」的报文数**：没挂 reorder 规则时
恒为 0，percent=90 时它等于全部报文。注意它不是通用 qdisc 语义里的「设备忙重新
入队」，那是同名不同义 —— 只有 netem 会把它记成重排命中数。

它单独不能当作「乱序生效」的完整证据：percent 太高时 netem 会把几乎所有包都标成
「立即发送」，delay 被打塌成 0ms，requeues 涨到 100% 而对端一个乱序都没有
（本机实测 percent=90 → 时延 0.06ms、对端乱序 0；percent=30~50 才有真乱序）。
所以这里同时导出 delay / reorder percent / gap，判据必须两者一起看。

## 两种用法（输出完全相同）

A. 自带 HTTP 端点（推荐：值永远等于抓取那一刻的 tc 输出，不会被 10s 缓存拉平）
     python3 qdisc_exporter.py --serve 0.0.0.0:19200
   Prometheus 增加一个抓取任务指向 <host>:19200/metrics

B. 写 textfile（仅当目标机的 node_exporter 已开 --collector.textfile.directory）
     python3 qdisc_exporter.py --textfile /var/lib/node_exporter/textfile/qdisc.prom --interval 3

   --dev ens17f0   目标网卡，默认与引擎网络故障的 interface 默认值一致
   --once          打印一次到 stdout 后退出（排障用）

## 导出的序列

    netem_present{dev}                1 = 网卡上挂着 netem qdisc；0 = 没挂（故障未生效/已恢复）
    netem_requeues_total{dev}         counter，重排命中报文数 —— 「乱序率」= rate(...[1m])
    netem_sent_packets_total{dev}     counter，经过该 qdisc 的报文数（判断流量有没有真的走这张网卡）
    netem_dropped_total{dev}          counter，qdisc 丢包数
    netem_overlimits_total{dev}       counter
    netem_reorder_percent{dev}        gauge，规则里配置的 percent
    netem_delay_seconds{dev}          gauge，规则里配置的 delay（0 或缺失 = 重排不会真的发生）
    netem_gap{dev}                    gauge
    netem_loss_percent{dev}           gauge，规则里配置的丢包档位（测量值看 dropped 计数）
    netem_duplicate_percent{dev}      gauge，规则里配置的重复档位（tc 没有 duplicate 计数）

qdisc 不存在时 present=0、计数全为 0：曲线是一条贴地的零线，故障期间尖起来，
这样「注入期间升高」在页面上一眼能看出来。
"""

import argparse
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PARAM_RE = re.compile(r"qdisc netem\s+\S+:\s+(.*)$")
DELAY_RE = re.compile(r"\bdelay\s+([0-9.]+)(us|ms|s)\b")
REORDER_RE = re.compile(r"\breorder\s+([0-9.]+)%(?:\s+([0-9.]+)%)?")
GAP_RE = re.compile(r"\bgap\s+(\d+)")
# loss 可能是 "loss 80%"（旧版 "loss random 80%"）；gemodel 形式没有紧跟的百分比，不匹配。
LOSS_RE = re.compile(r"\bloss\s+(?:random\s+)?([0-9.]+)%")
DUPLICATE_RE = re.compile(r"\bduplicate\s+([0-9.]+)%")
SENT_RE = re.compile(
    r"Sent (\d+) bytes (\d+) pkt \(dropped (\d+), overlimits (\d+) requeues (\d+)\)")

UNIT_SECONDS = {"us": 1e-6, "ms": 1e-3, "s": 1.0}


def read_tc(dev, timeout=3.0):
    """返回 tc -s qdisc show dev <dev> 的原始输出；命令失败返回空串。"""
    try:
        proc = subprocess.run(["tc", "-s", "qdisc", "show", "dev", dev],
                              capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return ""
    if proc.returncode != 0:
        return ""
    return proc.stdout


def parse(text, dev):
    """把 tc 输出解析成 {metric: (type, value)}；没有 netem qdisc 时 present=0。"""
    present = 0
    params = {}
    counters = {}
    for line in text.splitlines():
        match = PARAM_RE.search(line)
        if match:
            body = match.group(1)
            present = 1
            delay = DELAY_RE.search(body)
            if delay:
                params["delay_seconds"] = float(delay.group(1)) * UNIT_SECONDS[delay.group(2)]
            reorder = REORDER_RE.search(body)
            if reorder:
                params["reorder_percent"] = float(reorder.group(1))
            gap = GAP_RE.search(body)
            if gap:
                params["gap"] = float(gap.group(1))
            loss = LOSS_RE.search(body)
            if loss:
                params["loss_percent"] = float(loss.group(1))
            duplicate = DUPLICATE_RE.search(body)
            if duplicate:
                params["duplicate_percent"] = float(duplicate.group(1))
        match = SENT_RE.search(line)
        if match:
            counters = {
                "sent_packets_total": float(match.group(2)),
                "dropped_total": float(match.group(3)),
                "overlimits_total": float(match.group(4)),
                "requeues_total": float(match.group(5)),
            }
    values = {
        "present": ("gauge", float(present)),
        "requeues_total": ("counter", counters.get("requeues_total", 0.0)),
        "sent_packets_total": ("counter", counters.get("sent_packets_total", 0.0)),
        "dropped_total": ("counter", counters.get("dropped_total", 0.0)),
        "overlimits_total": ("counter", counters.get("overlimits_total", 0.0)),
        "reorder_percent": ("gauge", params.get("reorder_percent", 0.0)),
        "delay_seconds": ("gauge", params.get("delay_seconds", 0.0)),
        "gap": ("gauge", params.get("gap", 0.0)),
        "loss_percent": ("gauge", params.get("loss_percent", 0.0)),
        "duplicate_percent": ("gauge", params.get("duplicate_percent", 0.0)),
    }
    return values


HELP = {
    "netem_present": "网卡上是否挂着 netem qdisc（1=是）",
    "netem_requeues_total": "netem 判定需要重排的报文数：乱序率 = rate(...[1m])",
    "netem_sent_packets_total": "经过该 qdisc 的报文数（判断流量是否真的走这张网卡）",
    "netem_dropped_total": "该 qdisc 丢弃的报文数",
    "netem_overlimits_total": "该 qdisc 的 overlimits 计数",
    "netem_reorder_percent": "netem 规则里配置的 reorder percent",
    "netem_delay_seconds": "netem 规则里配置的 delay（0 = 重排不会真的发生）",
    "netem_gap": "netem 规则里配置的 gap",
    "netem_loss_percent": "netem 规则里配置的丢包档位；实测丢包率看 dropped/(dropped+sent)",
    "netem_duplicate_percent": "netem 规则里配置的重复档位；tc 不提供 duplicate 计数",
}


def render(dev):
    values = parse(read_tc(dev), dev)
    out = []
    for name, (kind, value) in values.items():
        full = "netem_" + name
        out.append("# HELP %s %s" % (full, HELP[full]))
        out.append("# TYPE %s %s" % (full, kind))
        out.append('%s{dev="%s"} %s' % (full, dev, int(value) if value == int(value) else value))
    return "\n".join(out) + "\n"


def write_atomic(path, content):
    directory = os.path.dirname(path) or "."
    fd, tmp = tempfile.mkstemp(dir=directory, prefix=".qdisc-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as handle:
            handle.write(content)
        os.replace(tmp, path)
    except Exception:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


class Handler(BaseHTTPRequestHandler):
    dev = "ens17f0"

    def do_GET(self):
        if self.path.split("?")[0] not in ("/metrics", "/"):
            self.send_error(404)
            return
        body = render(self.dev).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # 别把每次抓取写进日志
        pass


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--dev", default="ens17f0")
    parser.add_argument("--serve", metavar="ADDR:PORT")
    parser.add_argument("--textfile", metavar="PATH")
    parser.add_argument("--interval", type=float, default=3.0)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()

    if args.once:
        sys.stdout.write(render(args.dev))
        return 0

    if args.serve:
        host, _, port = args.serve.rpartition(":")
        Handler.dev = args.dev
        server = ThreadingHTTPServer((host or "0.0.0.0", int(port)), Handler)
        print("qdisc exporter listening on %s:%s dev=%s" % (host or "0.0.0.0", port, args.dev))
        server.serve_forever()
        return 0

    if args.textfile:
        running = True

        def stop(_signum, _frame):
            nonlocal running
            running = False

        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        while running:
            write_atomic(args.textfile, render(args.dev))
            deadline = time.time() + args.interval
            while running and time.time() < deadline:
                time.sleep(min(0.5, args.interval))
        return 0

    parser.error("要么 --serve ADDR:PORT，要么 --textfile PATH，要么 --once")


if __name__ == "__main__":
    sys.exit(main())
