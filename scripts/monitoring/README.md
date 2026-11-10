# 「乱序率」指标上线说明（qdisc_exporter）

目标：监控页上出现一条 **网卡乱序率**，乱序故障注入期间它必须抬起来。

数据来自 `tc -s qdisc show dev ens17f0` 里的 `requeues`——netem 判定「需要重排」的
报文数。它不是内核的通用计数，只有 netem 会把它记成重排命中数：没挂 reorder 规则时
恒为 0，注入期间随流量增长。

> ⚠ 判读必须成对看：`net.qdisc.reorder.rate` 高 **不代表** 真的发生了乱序。
> percent ≥ 90 时 netem 会把几乎所有包标成「立即发送」，delay 被打塌到 0ms，
> 命中率 100% 而对端一个乱序都没有（本机实测 2000/2000 命中、delay 0.06ms、乱序 0；
> 30–50% 才有真乱序）。所以同屏要带上 `net.qdisc.delay`。

## 一、先确认目标机走哪条路（在 YOUR_DB_HOST 上执行）

```bash
ss -ltnp | grep 19091                      # 找到 Agent 的 PID
tr '\0' '\n' < /proc/<PID>/cmdline | grep -i textfile
```

- 输出里有 `--collector.textfile.directory=...` → 走 **A. textfile**（不用改任何配置）
- 没有 → 走 **B. 独立端点**（改一次 prometheus.yml 的抓取任务，reload 即可，不用重启）

## 二、把脚本放到目标机

```bash
install -m 0755 qdisc_exporter.py /usr/local/bin/qdisc_exporter.py
```

脚本只用标准库，要求以 root 运行（`tc` 需要 CAP_NET_ADMIN），且网卡名与引擎
网络故障的 `interface` 一致（默认 `ens17f0`，可用 `--dev` 改）。

### A. textfile 模式

```bash
# 前台验证，先看输出对不对
python3 /usr/local/bin/qdisc_exporter.py --textfile /var/lib/node_exporter/textfile/qdisc.prom --interval 3 &
sleep 3 && cat /var/lib/node_exporter/textfile/qdisc.prom
curl -s http://127.0.0.1:19091/metrics | grep netem_requeues_total   # 应该能看到

# 长期跑：写成 systemd 服务
cat >/etc/systemd/system/qdisc-exporter.service <<'EOF'
[Unit]
Description=netem qdisc metrics exporter
[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/qdisc_exporter.py --textfile /var/lib/node_exporter/textfile/qdisc.prom --interval 3
Restart=always
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now qdisc-exporter
```

### B. 独立端点模式

```bash
cat >/etc/systemd/system/qdisc-exporter.service <<'EOF'
[Unit]
Description=netem qdisc metrics exporter
[Service]
ExecStart=/usr/bin/python3 /usr/local/bin/qdisc_exporter.py --serve 0.0.0.0:19200
Restart=always
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now qdisc-exporter
curl -s http://127.0.0.1:19200/metrics | head
```

然后给 `prometheus.yml` 加一个抓取任务，**instance 必须钉成 19091**，否则 catalog
里的 `instance="{{instance}}"` 模板匹配不到：

```yaml
  - job_name: resbench-qdisc
    scrape_interval: 3s
    static_configs:
      - targets: ["127.0.0.1:19200"]
        labels:
          instance: "127.0.0.1:19091"
```

重载（不丢数据、不重启）：

```bash
curl -X POST http://127.0.0.1:9090/-/reload     # 需要 --web.enable-lifecycle
# 没开 lifecycle 就发 SIGHUP：kill -HUP $(pidof prometheus)
```

## 三、验证

```bash
# Prometheus 里应该能立刻查到（注入前是 0）
curl -s 'http://127.0.0.1:9090/api/v1/query' --data-urlencode 'query=max(netem_present)' | head -c 300
```

手工挂一条规则就能看到它涨，不用等整轮压测：

```bash
tc qdisc add dev ens17f0 root netem delay 10ms reorder 30% 80%
ping -f -c 2000 <对端IP> >/dev/null 2>&1 || true
curl -s 'http://127.0.0.1:9090/api/v1/query' \
  --data-urlencode 'query=rate(netem_requeues_total[1m])' | head -c 300
tc qdisc del dev ens17f0 root
```

> 生产环境不要手工 `tc qdisc del`：那会覆盖 chaosblade 正在管理的规则。
> 上面这条只在确认没有压测在跑时用来冒烟。

## 四、打开指标

`MonitoringCatalog.java` 已注册三条（`net.qdisc.reorder.rate` 默认勾选）：

| metricId | 名称 | 用途 |
|---|---|---|
| `net.qdisc.reorder.rate` | 网卡乱序率 | `rate(netem_requeues_total[1m])`，注入期间抬升 |
| `net.qdisc.delay` | netem 配置时延 | 防「命中率高但 delay 塌成 0」的空操作误判 |
| `net.qdisc.reorder.percent` | netem 重排比例 | 确认实际下发的档位 |

它们没有 `collectorId`：序列由本脚本直接暴露，不在 agent 的采集器清单里。
`MonitoringService.supports()` 对这类指标放行，真没部署时会显示成空曲线/QUERY_FAILED。

res-manager 需要重启才会加载新 catalog（8090 上那个进程）。

## 五、两个采样限制

1. **抓取间隔不能比导出器的刷新间隔快**。`openinsight-agent` job 是 3s；`cadvisor`
   job 2026-10-25 先调到 3s，当天因为「30s 的 CPU 故障要 20 秒才在曲线上打满」又调到
   **1s**，同时把 cadvisor 容器从 `--housekeeping_interval=10s` 改成 `1s`（实测源端时间戳
   从 16.3s 一跳变成 1.2~1.7s 一跳，Prometheus 每分钟落点 4 → 40 个）。qdisc job 也写 3s，
   `--interval` 用 3。
   只要抓取比源端刷新快，Prometheus 就只会把同一个值重复存，曲线看着密、实际没变 ——
   cadvisor 那个 3s 抓取 + 16s 源刷新就是典型：每分钟只有 4 个真样本。
2. **故障时长建议 120s**。宿主机 netstat 类计数器是 60s 刷新的，30s 的故障本来就
   欠采样；乱序这类窗口短的故障尤其要拉长才能被判据稳定识别。

