# ResBench 监控模块部署指南（CentOS 7.6 数据库服务器）

> 目标：在数据库服务器上部署监控 Agent 和 Prometheus，让你 Windows 电脑上的 ResBench 前端"监控"页面显示真实曲线。
>
> 总共 7 步，每一步都有验证方法，照着复制粘贴即可。
>
> 所有文件统一放在服务器上 `/home/YOUR_USER/` 目录，程序直接从该目录运行。
>
> **最近更新（2026-10-27）**：补齐监控模块新增采集项的部署说明 —— 主机网络探针
> （`net.ping.*` / `net.tcp.*` / `host.netstat.*`）、netem qdisc 指标（`net.qdisc.*`，
> 默认关闭）、容器指标（`container.*`，需要 cAdvisor）。这些是**增量能力**：按旧流程
> 装完，原有指标照常工作；新增指标按需开启，没开时表现为空曲线或整类不可选，不会让
> 监控页报错。

---

## 部署架构（先看懂这张图）

```
你的 Windows 电脑                          数据库服务器（CentOS 7.6）
┌─────────────────────────┐               ┌──────────────────────────────┐
│  ResBench 前端 (:5173)   │               │  openGauss / PostgreSQL /    │
│  ResBench 后端 (:8090)   │ ───────────→  │  OceanBase                   │
│                         │   访问        │                              │
│                         │  19091/9090   │  OpenInsight Agent (:19091)  │←─ 采集数据库和主机指标
│                         │               │        ↑ 抓取                │
│                         │ ───────────→  │  Prometheus (:9090)          │←─ 存时序数据
└─────────────────────────┘               └──────────────────────────────┘
```

**开始前必须知道的 3 个事实：**

| # | 事实 | 影响 |
|---|------|------|
| 1 | Agent 有两条数据库采集通道：`og`（openGauss / PostgreSQL，PG 协议）和 `mysql`（MySQL，依赖 `performance_schema`） | openGauss / PostgreSQL / MySQL ✅；**OceanBase 未经现场验证**，建议只把它当主机指标看 |
| 2 | CentOS 7.6 内核 3.10 太老，不支持 eBPF | 配置里已关闭 eBPF。主机 + 数据库指标不受影响 |
| 3 | Linux 二进制已帮你编译好 | 见下表，**服务器上不需要装 Go，不需要编译** |
| 4 | 新增指标按需开启，缺少采集端时是「空曲线 / 整类不可选」 | `net.qdisc.*` 默认关闭；`container.*` 需要 cAdvisor；老二进制没有 `node_ping` |

**需要上传到服务器的文件（都在 `桌面\monitor_module\` 目录）：**

| 文件 | 说明 |
|------|------|
| `openinsight-agent-linux` | 编译好的 Agent 程序（Linux x86_64）；需包含 `node_ping` / `node_network_stack` / `node_qdisc` 的新版本 |
| `config.yaml` | Agent 配置（含 openGauss / PostgreSQL / MySQL 连接信息） |
| `prometheus.yml` | Prometheus 配置 |
| `qdisc_exporter.py`（可选） | 只在**不用** agent 原生 `node_qdisc`、又想看 `net.qdisc.*` 时才需要，见 `scripts/monitoring/README.md` |

> 如果服务器是 ARM CPU（用 `uname -m` 检查，输出 `aarch64`），上面的二进制不能用，联系我再编一个 ARM 版。

---

## 第 1 步：上传必需文件到 /home/YOUR_USER/

最简单的方式用 **WinSCP**（图形界面拖拽）：

1. 下载安装 WinSCP（https://winscp.net）
2. 新建会话：填服务器 IP、YOUR_USER 账号密码，登录
3. 进入右侧 `/home/YOUR_USER/` 目录
4. 把上面 3 个文件从 `桌面\monitor_module\` 拖进去

或者用 PowerShell 命令（把 `<服务器IP>` 换成实际 IP）：

```powershell
scp C:\Users\YOUR_USER\Desktop\monitor_module\openinsight-agent-linux YOUR_USER@<服务器IP>:/home/YOUR_USER/
scp C:\Users\YOUR_USER\Desktop\monitor_module\config.yaml YOUR_USER@<服务器IP>:/home/YOUR_USER/
scp C:\Users\YOUR_USER\Desktop\monitor_module\prometheus.yml YOUR_USER@<服务器IP>:/home/YOUR_USER/
```

**✅ 验证**：服务器上执行 `ls /home/YOUR_USER/` 能看到上面 3 个必需文件（用到 qdisc_exporter 时再加一个）。

---

## 第 2 步：确认数据库密码

Agent 用 `config.yaml` 里的账号连接 openGauss 采集指标。当前配置：

```yaml
dsn: "host=127.0.0.1 port=5434 user=test1230 password=YOUR_PASSWORD dbname=postgres sslmode=disable"
```

在服务器上检查并修改（用户名/密码换成你实际用的）：

```bash
vi /home/YOUR_USER/config.yaml
```

- `port=5434` 是 openGauss 单机默认端口
- `dbname=postgres` 是 openGauss 自带的库，监控查询走它
- 密码含有 `@` `#` 等特殊字符时不需要转义，直接写

**✅ 验证**（可选，用 psql 测连接）：

```bash
gsql -d postgres -U test1230 -W YOUR_PASSWORD -p 5434 -c "select 1"
```

---

## 第 2 步（续）：按需打开新增指标的采集开关

新增指标都靠 `config.yaml` 里的开关，**不配也能部署，只是没有对应曲线**。把下面几段并进现有 `config.yaml`（键名层级必须一致）：

```yaml
node:
  enabled: true
  # 空列表 = 不注册 node_qdisc，net.qdisc.* 全部为空（新装默认就是空）
  qdisc_devices: ["ens17f0"]
  # 空列表 = 打默认路由下一跳；需要指定目标时写 agent 能 ping 通的地址
  ping_targets: []

og:                      # openGauss / PostgreSQL
  enabled: true
  dsn: "host=127.0.0.1 port=5434 user=<账号> password=<口令> dbname=postgres sslmode=disable"

mysql:                   # 需要 MySQL 指标时才开，可与 og 同时启用
  enabled: false
  dsn: "<账号>:<口令>@tcp(127.0.0.1:3306)/?timeout=2s&readTimeout=5s"
```

| 指标 | 依赖 | 什么都不改时 |
|---|---|---|
| `net.ping.loss/rtt/up` | `node_ping`（`node.enabled=true` 即注册） | 有数据（默认探测下一跳） |
| `net.tcp.*`、`host.netstat.*` | `node_network_stack`（同样自动注册） | 有数据 |
| `host.process.stopped` | `node_process` | 有数据 |
| `net.qdisc.*` | `node.qdisc_devices` 非空，或外部 qdisc_exporter | **空曲线** |
| `container.*` | Prometheus 里有 `job="cadvisor"` 且 `up==1` | 整类不可选，提示「cAdvisor 未就绪」 |
| `db.*`（`og_*`） | `og.enabled=true` + DSN 指向**被测库** | 有数据，但只覆盖 DSN 那一个实例 |
| `db.*`（`mysql_*`） | `mysql.enabled=true` + `performance_schema` 开启 | 无 |

**三个必须知道的细节：**

1. **主机采集器默认按会话激活**：前端勾选指标后 res-manager 才会创建采集会话。想让主机曲线一直连续（故障前的基线也要），在 `node:` 下加 `always_on: true`；代价是 1 秒一次的主机采集常驻。
2. **`net.qdisc.*` 的 PromQL 带 `instance="127.0.0.1:19091"` 过滤**。用 agent 原生 `node_qdisc` 时标签天然对齐；用外部 `qdisc_exporter.py` 时必须走 **textfile 目录**（跟着 agent 的 `:19091/metrics` 一起暴露），否则得给它单独 relabel 成同一个 instance，否则监控页查不到。
3. **`net.qdisc.*` 默认关闭**：每条 `qdisc_devices` 每秒执行一次 `tc -s qdisc show dev <dev>`，只填真正注入故障的那张网卡（本环境是 `ens17f0`）。

**✅ 验证**（建临时会话跑新增采集器；会话 60 秒后自动过期）：

```bash
# 1) 主机网络探针（与 qdisc 分开验证 —— 没配 qdisc_devices 时 node_qdisc 没注册，
#    把它放进 collectors 会让接口直接返回 400 unsupported collector）
curl -s -X POST http://127.0.0.1:19091/api/v1/collection-sessions \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"selftest-net","ttlSec":60,"collectors":["node_ping","node_network_stack"]}'

sleep 12
curl -s http://127.0.0.1:19091/metrics | grep -E '^(net_ping_up|node_netstat_tcp_retranssegs)' | head
# 两个都应有输出，net_ping_up 的值应为 1
# 返回 400 unsupported collector → node.enabled=false，或二进制太旧（换新版本）

# 2) qdisc（只有配了 node.qdisc_devices 才做这一步）
curl -s -X POST http://127.0.0.1:19091/api/v1/collection-sessions \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"selftest-qdisc","ttlSec":60,"collectors":["node_qdisc"]}'

sleep 12
curl -s http://127.0.0.1:19091/metrics | grep '^netem_present' | head
# 没有输出：网卡名写错 / 该网卡不存在 / 二进制没有 node_qdisc
```

---

## 第 3 步：安装并启动 Agent

程序直接从 `/home/YOUR_USER/` 运行，不需要复制到别处。依次执行（整段复制）：

```bash
cd /home/YOUR_USER

# 1. 重命名（去掉 -linux 后缀）并加执行权限
mv openinsight-agent-linux openinsight-agent
chmod +x /home/YOUR_USER/openinsight-agent

# 2. 创建系统服务（开机自启、崩溃自动拉起）
sudo tee /etc/systemd/system/openinsight-agent.service > /dev/null <<'EOF'
[Unit]
Description=OpenInsight Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/home/YOUR_USER/openinsight-agent --config.file=/home/YOUR_USER/config.yaml
Restart=always
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

# 3. 启动
sudo systemctl daemon-reload
sudo systemctl enable --now openinsight-agent
systemctl status openinsight-agent --no-pager
```

> 说明：不用仓库自带的 `deploy/openinsight-agent.service`，因为它声明的 `CAP_BPF` 等权限 CentOS 7.6 的旧版 systemd 不认识，会启动报错。上面这个精简版专门适配 CentOS 7.6。

**✅ 验证**（2 条都执行）：

```bash
curl -s http://127.0.0.1:19091/healthz
# 应输出：ok

curl -s http://127.0.0.1:19091/api/v1/capabilities | head -c 200
# 应输出一段 JSON
```

**（可选）验证数据库连接**：Agent 按需采集，没有压测会话时不会连接数据库，`og_collector_up` 指标不存在是正常的。想立即确认 openGauss 连接没问题，创建一个 60 秒的临时会话：

```bash
curl -s -X POST http://127.0.0.1:19091/api/v1/collection-sessions \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"selftest","ttlSec":60,"collectors":["og_tps"]}'

sleep 12

curl -s http://127.0.0.1:19091/metrics | grep og_collector_up
# 期望输出 og_collector_up 1（1 = 数据库连接正常；0 = 连不上，见常见问题第 1 条）
# 会话 60 秒后自动过期，无需清理
```

---

## 第 4 步：安装并启动 Prometheus

Prometheus 程序下载解压到 `/home/YOUR_USER/prometheus/`：

```bash
cd /home/YOUR_USER

# 1. 下载 Prometheus（国内服务器推荐华为云镜像，速度快）
wget https://mirrors.huaweicloud.com/prometheus/prometheus-2.53.3.linux-amd64.tar.gz
# 下载失败就用官方地址：
# wget https://github.com/prometheus/prometheus/releases/download/v2.53.3/prometheus-2.53.3.linux-amd64.tar.gz

# 2. 解压，并建一个软链接方便升级
tar xzf prometheus-2.53.3.linux-amd64.tar.gz
ln -s /home/YOUR_USER/prometheus-2.53.3.linux-amd64 /home/YOUR_USER/prometheus

# 3. 创建系统服务（配置文件直接用 /home/YOUR_USER/prometheus.yml，不用复制）
sudo tee /etc/systemd/system/prometheus.service > /dev/null <<'EOF'
[Unit]
Description=Prometheus
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=root
ExecStart=/home/YOUR_USER/prometheus/prometheus --config.file=/home/YOUR_USER/prometheus.yml --storage.tsdb.path=/home/YOUR_USER/prometheus/data --storage.tsdb.retention.time=15d --web.listen-address=:9090
Restart=always
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now prometheus
systemctl status prometheus --no-pager
```

**✅ 验证**：

```bash
curl -s http://127.0.0.1:9090/-/ready
# 应输出：Prometheus Server is Ready.

curl -s http://127.0.0.1:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
# 应输出："health":"up"
```

> ⚠️ `prometheus.yml` 里的 `target_id: "db-primary-01"` **绝对不要改**。ResBench 后端按这个固定标签查询曲线，改了前端就没数据。

---

## 第 4 步（可选）：容器指标（cAdvisor）

`container.*` 整类指标只认 Prometheus 里 `job="cadvisor"` 且 `up==1` 的目标 —— res-manager 就是用这条查询判断容器指标是否可选的。数据库跑在 Docker 里才需要这一步。

```yaml
# /home/YOUR_USER/prometheus.yml 的 scrape_configs 下追加
  - job_name: "cadvisor"
    static_configs:
      - targets: ["127.0.0.1:8088"]     # 换成 cAdvisor 实际监听的地址
```

cAdvisor 的启动方式按现场（Docker / 裸机都行），只要它的 `/metrics` 能被 Prometheus 以 `job=cadvisor` 抓到即可。

**✅ 验证**：

```bash
curl -s 'http://127.0.0.1:9090/api/v1/query?query=up{job="cadvisor"}' | grep -o '"value":\[[^]]*\]'
# 应看到值为 1；值为 0 或结果为空表示抓取失败
```

> 容器名要和 `docker ps` 里的名字一致（res-manager 会校验后原样填进 PromQL 的 `name=` 标签）。

---

## 第 5 步：开放防火墙端口（19091 和 9090）

Windows 上的 ResBench 后端要访问这两个端口：

```bash
sudo firewall-cmd --permanent --add-port=19091/tcp --add-port=9090/tcp
sudo firewall-cmd --reload
```

> 如果提示 `FirewallD is not running`，说明服务器没开防火墙，跳过即可。
>
> 安全提示：这两个端口没有登录认证，只建议在内网环境开放。

**✅ 验证**：在你 Windows 电脑的浏览器打开：

- `http://<服务器IP>:19091/healthz` → 应显示 `ok`
- `http://<服务器IP>:9090/-/ready` → 应显示 `Prometheus Server is Ready.`

---

## 第 6 步：修改 Windows 上的 ResBench 配置并重启后端

编辑 `C:\Users\YOUR_USER\Desktop\Resbench\backend\res-manager\src\main\resources\application.properties`，把 `agent-url` 和 `prometheus-url` 的 `127.0.0.1` 改成服务器 IP（`prometheus-instance` 保持不变，见下方警告）：

```properties
# 修改前
res.monitoring.agent-url=http://127.0.0.1:19091
res.monitoring.prometheus-url=http://127.0.0.1:9090
res.monitoring.prometheus-instance=127.0.0.1:19091

# 修改后（示例，换成你的服务器 IP）
res.monitoring.agent-url=http://YOUR_DB_HOST:19091
res.monitoring.prometheus-url=http://YOUR_DB_HOST:9090
res.monitoring.prometheus-instance=127.0.0.1:19091

# 可选：一次最多勾选多少个指标（当前默认 32；旧版本是 20）
res.monitoring.max-metrics=32
```

> ⚠️ **三个值含义不同，这是最容易踩的坑：**
>
> | 配置 | 作用 | 应填的值 |
> |---|---|---|
> | `agent-url` | res-manager 访问 Agent 的地址 | 服务器 IP |
> | `prometheus-url` | res-manager 查询 Prometheus 的地址 | 服务器 IP |
> | `prometheus-instance` | **查询曲线时的标签过滤条件** | 必须与服务器上 `prometheus.yml` 里 `targets` 的地址字符串**完全一致** |
>
> 本指南中 `prometheus.yml` 的 targets 写的是 `127.0.0.1:19091`（Prometheus 和 Agent 同机），
> 所以 `prometheus-instance` 保持 `127.0.0.1:19091` 不变。如果把它也改成服务器 IP，
> 所有查询都会因标签不匹配而查不到数据（前端表现为"有图表但无曲线"）。
>
> 副作用：前端"目标节点"下拉会显示 `127.0.0.1:19091`（仅展示瑕疵）。
> 若希望展示服务器 IP：把 `prometheus.yml` 的 `targets` 改成 `["YOUR_DB_HOST:19091"]`
> 并重启 Prometheus，`prometheus-instance` 同步改为相同值即可——**两边一致是唯一原则**。

然后重启 res-manager（先停掉现在跑的，再用原来的方式启动）。

**✅ 验证**：浏览器打开

```
http://127.0.0.1:8090/monitoring/capabilities
```

返回的 JSON 里应有：`"agentAvailable": true`、`"prometheusAvailable": true`

再确认新增采集器有没有被 agent 上报（没有就说明二进制太旧）：

```bash
curl -s http://127.0.0.1:8090/monitoring/capabilities | grep -o 'node_ping\|node_qdisc\|node_network_stack'
```

也可以直接看指标目录里每个指标是否可选、不可选的原因：

```bash
curl -s http://127.0.0.1:8090/monitoring/catalog | grep -o '"metricId":"net.qdisc[^}]*'
# available=false 时 reason 会写「collector 未启用」/「cAdvisor 未就绪」
```

---

## 第 7 步：前端使用

1. 打开 ResBench 前端 → 侧边栏「监控」
2. 打开「启用监控」开关 → 点「选择指标」→ 按需勾选（主机 / 数据库分类）→ 保存
3. 回到测试页启动压测
4. 再进「监控」页，压测期间实时刷新曲线，结束后自动归档
5. 「测试记录」下拉框可查看历史曲线

> 指标选得越少，对压测性能的影响越小。

---

## 进阶（可选）

### 监控 PostgreSQL

`og` 采集通道一个 Agent 只能连一个数据库。要同时监控 PostgreSQL，在服务器上再部署第二个 Agent（或者直接把现有实例的 `og.dsn` 指向被测 PostgreSQL）：

1. 复制一份 `/home/YOUR_USER/config.yaml` 改名为 `config-pg.yaml`，修改：`og.dsn` 指向 PostgreSQL（`port=5432 user=postgres ...`）
2. 复制 service 文件，`--config.file` 改为 `/home/YOUR_USER/config-pg.yaml`，加参数 `--web.listen-address=:19092`（.service 里 ExecStart 末尾追加）
3. `/home/YOUR_USER/prometheus.yml` 的 `scrape_configs` 下追加第二个 job，`targets: ["127.0.0.1:19092"]`，`target_id` 用 `db-pg-01`

> ResBench 前端固定查询 `prometheus-instance` 配置的那一个实例（默认 `127.0.0.1:19091`），所以只有**主 Agent** 的曲线能进监控页；第二个 Agent（`:19092`）的曲线需要在 Prometheus 自带页面（`http://<服务器IP>:9090`）或 Grafana 看。

### 监控 MySQL

MySQL 不需要第二个 Agent：在同一个 `config.yaml` 里打开 `mysql` 段即可（可与 `og` 同时启用，指标统一用 `mysql_` 前缀）。

```yaml
mysql:
  enabled: true
  dsn: "<账号>:<口令>@tcp(127.0.0.1:3306)/?timeout=2s&readTimeout=5s"
```

权限与前提：`performance_schema=ON`；监控账号至少要有 `PROCESS`（看全部会话）和 `REPLICATION CLIENT`（复制状态）。完整指标清单见 `monitor_module/METRICS.md` 第 5 节。

### 数据库指标只覆盖 DSN 那一个实例（排查前必读）

- `db.*` 指标只覆盖 `og.dsn` / `mysql.dsn` 指向的**那一个实例**。给 openGauss 配的 DSN 不会产生被测 PostgreSQL 的 `db.*` 曲线 —— `og_*` 底层查的是 `pg_stat_activity` / `pg_locks`，把 DSN 指向 PostgreSQL 就能采集 PostgreSQL。
- 所以 `db.lock.wait.max`（最大锁等待）反映哪个库，取决于它的 DSN 指哪儿；`db.blocked.sessions` 目前只接了 MySQL 的 `mysql_blocked_sessions`（agent 侧已经产出 `og_blocked_sessions`，但指标目录尚未接进来）。
- 遇到「注入了但监控没反应」，先确认两件事：① 采集器指的就是被测库；② 该指标在目录里不是「不可选」。

### 关于 OceanBase

Agent 没有专门的 OceanBase 采集器。可以用 `mysql` 通道试着连（OceanBase 兼容 MySQL 协议），但它依赖的 `performance_schema` / `information_schema` 视图兼容度未做现场验证；至少可以保证**主机指标**（CPU、内存、磁盘、网络）可用。

---

## 常见问题

### 1. Agent 起不来或 `og_collector_up 0`

```bash
systemctl status openinsight-agent --no-pager
journalctl -u openinsight-agent -n 50 --no-pager
```

- 日志提示连接拒绝/密码错误 → 检查 `/home/YOUR_USER/config.yaml` 的 DSN
- openGauss 默认只允许本机连接，Agent 也在本机所以没问题；如果改过 `pg_hba.conf`，确认包含 `host all all 127.0.0.1/32 md5`
- 端口被占用 → `ss -tlnp | grep 19091`

### 2. Windows 能 ping 通服务器，但 19091/9090 打不开

云服务器还需在控制台「安全组」里放行 19091、9090 端口（TCP）。

### 3. capabilities 返回 true，但前端没有曲线

按顺序检查：

1. 压测**开始前**是否已打开「启用监控」并选择了指标
2. `/home/YOUR_USER/prometheus.yml` 里 `target_id` 是否还是 `db-primary-01`
3. `curl -s http://127.0.0.1:9090/api/v1/targets | grep -o '"health":"[a-z]*"'` 是否为 up
4. 只能看结束后的曲线？等测试结束归档后再看历史

### 4. 想看 eBPF 指标

需要把内核升级到 5.x+（如 openEuler、Ubuntu 22.04）才有 BTF 支持，CentOS 7.6 无法支持。

### 5. 日志报 `AuthenticationSASL body is invalid`（openGauss 专属坑，已完整验证）

`journalctl` 里出现 `og_collector_up=0` 且有
`failed to receive message: AuthenticationSASL body is invalid unterminated string`：
openGauss 默认用**私有 sha256** 密码认证，Agent 的标准 PostgreSQL 驱动（pgx）不兼容
（**gsql 能连不代表第三方驱动能连**，gsql 是 openGauss 自带客户端）。

修复 = 创建一个 **md5 认证的专用监控账号**（不动压测账号）。完整步骤（本机已验证）：

**① 打开 md5 加密开关（重启级参数，需改配置文件 + 重启数据库）：**

```bash
cat >> <数据目录>/postgresql.conf <<'EOF'
password_encryption_type = 0     # 0=md5；此参数不能会话内 SET，必须重启生效
password_reuse_time = 0          # 关闭密码复用限制（否则无法重设历史密码）
EOF
~/openGauss-server-5.0.0/dest/bin/gs_ctl restart -D <数据目录>
```

**② 创建监控账号（用户名/密码可自定义）：**

```bash
~/openGauss-server-5.0.0/dest/bin/gsql -d postgres -p 5434 <<'EOF'
CREATE USER monitor WITH PASSWORD 'Monitor@123';
ALTER USER monitor SYSADMIN;      -- 采集 wait_events、slow_sql 等系统视图需要
SELECT pg_reload_conf();
EOF
```

**③ pg_hba.conf 顶部加 md5 规则并 reload：**

```
host  all  monitor  127.0.0.1/32  md5
```

```bash
~/openGauss-server-5.0.0/dest/bin/gsql -d postgres -p 5434 -c "SELECT pg_reload_conf();"
```

**④ 验证并切换 Agent 配置：**

```bash
gsql -d postgres -U monitor -p 5434 -W 'Monitor@123' -c "select 1"   # 应成功
# config.yaml 的 dsn 改为 user=monitor password=Monitor@123，然后：
sudo systemctl restart openinsight-agent
```

**本坑相关的 4 个小知识（都实测踩过）：**

- `SET password_encryption_type = 0` 会报 `cannot be changed now`——它是重启级参数，必须改配置文件重启
- 修改密码时报 `New password should not equal to the old ones`——密码复用策略，靠上面第 ① 步的 `password_reuse_time = 0` 解除；**"新密码与当前密码相同"这条基本规则无法绕过**，所以要么换新密码，要么新建用户
- `GRANT sysadmin TO xxx` 在 5.0 会报 `role "sysadmin" does not exist`——正确写法是 `ALTER USER xxx SYSADMIN`
- `SHOW hba_file` 只有超级用户能执行；普通用户查 `pg_roles` 看不到别人——找超级用户的办法：
  `ps -ef | grep [g]aussdb`，**进程属主就是初始超级用户**，`-D` 后面就是数据目录

### 6. 部分指标有数据、部分没有

按采集器逐个排查：

```bash
# 看哪些采集器在运行、哪些在报错（errors_total > 0 就是失败的）
curl -s http://127.0.0.1:19091/metrics | grep openinsight_collector
```

- 会话按需激活采集器：前端勾了哪些指标，Agent 才跑对应采集器；没勾的指标无数据是正常的
- 某采集器 `runs_total > 0` 但 `errors_total` 等于它 → 该采集器每次都失败（多为 SQL 权限或语法问题），
  Agent 日志会输出 `[og] stats sub-collect <函数名> failed: <原因>`，按原因处理
- **已修复案例**：`og_connections`（数据库连接使用率）曾因 SQL 使用了
  `current_setting('参数名', true)` 双参数形式而全部失败——**openGauss 不支持该 PG 专属语法**，
  必须用单参数 `current_setting('参数名')`。遇到同类 `SQLSTATE 42883 function ... does not exist`
  报错时优先怀疑 PG 专属语法差异
- 会话 TTL 过期（默认 15 分钟）或 Agent 重启后，采集会话由 res-manager 心跳自动重建；
  若长时间无数据，重启一次 res-manager 可强制重建

### 7. 前端点进监控页弹「404 monitoring session not found」

选择的历史压测 runId 是**部署监控之前**的测试，本来就没有监控归档，属预期现象
（前端已修复为静默处理）。只有部署监控之后的压测才有曲线和历史。

### 8. 新增指标一直是空的

先看指标目录里的 `reason`（`curl -s http://127.0.0.1:8090/monitoring/catalog`），再按下表核对：

| 现象 | 原因 | 处理 |
|---|---|---|
| `net.qdisc.*` 空曲线 | `node.qdisc_devices` 为空（默认关闭） | `config.yaml` 填 `qdisc_devices: ["ens17f0"]` 后重启 agent |
| `net.qdisc.present` 为 0 或无序列 | 网卡名写错，或该网卡没有流量 | `ip -br link` 核对网卡名；netem 规则只在故障注入期间存在 |
| `net.ping.*` 不可选 | agent 二进制太旧，没有 `node_ping` | 换新二进制 |
| `net.ping.loss` 恒 100% / `net.ping.up=0` | 默认下一跳禁 ICMP，或 ping 目标不可达 | 配 `ping_targets` 为可达地址 |
| `net.tcp.*` / `host.netstat.*` 无数据 | agent 太旧（无 `node_network_stack`） | 换新二进制 |
| `container.*` 整类不可选 | Prometheus 没有 `up{job="cadvisor"}==1` | 按第 4 步接 cAdvisor |
| `db.*` 有数据但和被测库对不上 | DSN 指向了别的实例 | 见「数据库指标只覆盖 DSN 那一个实例」 |

再看采集器自身有没有报错：

```bash
curl -s http://127.0.0.1:19091/metrics | grep openinsight_collector
# 某采集器 runs_total 在涨但 errors_total 等于 runs_total → 每次都失败，查 agent 日志
```

---

## 日常运维命令速查

```bash
# Agent
systemctl status openinsight-agent --no-pager
sudo systemctl restart openinsight-agent
journalctl -u openinsight-agent -f          # 实时日志

# Prometheus
systemctl status prometheus --no-pager
sudo systemctl restart prometheus

# 可选：qdisc exporter（只用 agent 原生 node_qdisc 时没有这个服务）
systemctl status qdisc-exporter --no-pager

# 文件位置
# Agent 程序：/home/YOUR_USER/openinsight-agent
# Agent 配置：/home/YOUR_USER/config.yaml
# Prometheus 程序：/home/YOUR_USER/prometheus/（软链接）
# Prometheus 配置：/home/YOUR_USER/prometheus.yml
# Prometheus 数据：/home/YOUR_USER/prometheus/data

# 数据留存
# Prometheus 原始数据保留 15 天（可在启动参数改 --storage.tsdb.retention.time）
# ResBench 曲线归档保留 180 天，可在监控页面按 runId 删除
```
