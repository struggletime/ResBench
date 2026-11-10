# 后端批量压测（resbench_batch.py）

只用**引擎后端**接口跑批量压测，不需要启动前端。
脚本：`scripts/resbench_batch.py`，示例配置：本目录。

## 1. 它做什么

一次调用 = 一个批次：`databases × workloads × orchestrations` 的**笛卡尔积**，全部**串行**执行。

| 步骤 | 调的接口 |
|---|---|
| 应用数据库参数（可选，对应前端「应用配置」） | `POST /web/bench/db/params` |
| 提交一轮测试 | `POST /web/bench/startByForm` |
| 轮询到跑完 | `GET /web/bench/status?historyName=<runId>` |
| 取这一轮的指标与产物完整性 | `GET /web/bench/history/query?keyword=<runId>` |
| 打标签 / 写备注（让前端历史页也能检索到） | `POST /web/bench/history/label` |

`startByForm` 是**引擎自己的接口**（`BenchmarkController`），前端只是它的调用方之一；
脚本直接打引擎，不加载任何前端代码、不依赖前端构建或运行。走这个接口的好处是
**连接信息、故障注入命令、排程 XML 全部由后端生成**，脚本不用手写那几份 XML；
后端还会顺带写 `run-meta.json`，前端历史页因此能按标签/编排检索到这批结果。

跑起来之后 Ctrl+C（或 `kill`）是**安全**的：脚本会把**它自己刚提交的那一轮**停掉再退出，
不会让子 JVM 在引擎上多跑几小时、占着「同时只收一个任务」的闸门。引擎上若是别人的任务，
脚本认出来就不会碰。详见第 5 节末。

## 2. 参数口径：和前端一致，只多一个字段

脚本发给引擎的字段，逐项对应前端「数据库配置 / 工作负载 / 故障注入」三个面板。

- `autoTimingMode` 写死 `fixed-time`、`runOptions.monitorIntervalSec=1/create/load/execute` 写死 `true`
  —— 前端 `buildRequestBody()` 就是这么写死的；
- 不发送 `dbKey / orchestrationId / source / envInfo`，也不发送 `delaySeconds`
  —— 前端表单里没有这些；
- 运行名（`historyName`）由引擎按 `<负载名>_<testName>_<时间戳>` 生成，脚本不自己拼。

脚本比前端表单**多一个字段**：**`testName`** —— 它是 `StartByFormRequest` 的字段、
前端历史页认它做「测试名」，但前端压测表单不填。脚本补上组合名
`<库key>__<负载key>__<编排key>`，于是归档目录 `results/<运行名>/` 和
`config/generated/` 里的 XML 文件名都自带实验环境信息。
它**不改变引擎行为**（只影响命名，且引擎强制保留负载名前缀）。

`containerName`（前端「数据库配置」里的**容器名**输入框，脚本里是 `databases[].container`）
也是**顶层字段**（跟 `faults` 平级，不在 `database` 里）。非空时引擎把 chaosblade 类故障
套一层 `docker exec <容器> …` 在容器内注入 —— 详见 §3.1 与 `docs/batch-script-usage.md`。
留空（前端不填 / 脚本不写 `container`）则完全不发这个键，行为与以前逐字节一致。

`dbKey` 之类的 `key` 字段只存在于 YAML 里，是脚本内部用来互相引用的名字，**不会发给后端**。

## 3. 三份配置文件

### 3.1 `databases.yaml` —— 一个文件可含多个库

```yaml
databases:
  - key: og-local            # 脚本内部引用名 + 结果目录名的一部分，不发给后端
    dbType: opengauss        # opengauss | mysql | postgresql | oceanbase | gaussdb
    deploymentType: standalone
    standalone: { ip: YOUR_DB_HOST, port: "5434", username: YOUR_USER, password: <口令> }
    container: og-db         # 可选：库跑在 Docker 容器里时填容器名（见 §3.1 末）
    # 可选：对应前端「应用配置」按钮
    dbParams:
      bufferPool: 2GB
      maxConnections: "500"
      restartCommand: "nohup gs_ctl restart -D /gaussdb/data >/tmp/x.log 2>&1 &"
```

- 只校验**实际会用到**的那份连接信息：单机不看 `distributed`，分布式不看 `standalone`。
- `dbParams` 的规则与前端一致：只有单机 `opengauss/postgresql/mysql` 能改；
  og/pg 的这两个参数必须重启才生效，所以 `restartCommand` 必填。
  命令怎么写、报「读回不一致」时怎么查，见本节末。
- `dbParams` 在**这个库的第一个轮次之前应用一次**，之后所有轮次沿用（避免每轮都重启库）；
  应用结果按真实参数名（`shared_buffers` / `max_connections`）记进 `database.params` 归档。

**`container`（库跑在 Docker 容器里）** —— 填了容器名，该库的 chaosblade 类故障就套
`docker exec <容器> …` 在**容器内**执行，注入 / 恢复 / 自检三条命令一起套：

- 只作用于 chaosblade 类（`cpu` / `memory` / `disk` / `file` / `process`）。
  目录里的 DBChaos 故障（`connection.*` / `transaction.*` …）走 JDBC，**永远不进容器**。
- **网络类故障（`faultinject.fault.network.*`）是例外，仍打到宿主机网卡**
  （容器网卡名与宿主机不同、网络命名空间隔离），引擎会打一条 WARN。这是有意为之。
- 磁盘类故障的路径（`path` / `src-path` / `file-path` / `filepath`）按**容器内**路径解析
  —— 要填 `SHOW data_directory` 读到的容器内路径，不是宿主机的挂载路径。
- 前置条件：容器内已装 chaosblade（`/opt/chaosblade/blade`）、agent 的 OS 用户在 docker 组、
  容器名在 agent 主机上能解析。容器名写错不会是 4xx，会体现为 agent 侧 `docker exec` 失败。
- 容器名会记进 `run-meta.json` 的 `database.containerName`，CSV 里也有一列，事后能分辨打在哪。

**容器库的「应用配置」重启命令要自己写 docker 形式**（后端不会替你合成 systemd/docker/gs_ctl）：

```yaml
    container: pg-docker
    dbParams:
      bufferPool: 2GB
      maxConnections: "500"
      # 命令由 agent 在宿主机上执行，**不套** docker exec，所以直接 docker restart；
      # su - <用户> -c 只是借那个用户的 docker 组权限（agent 已在 docker 组就不需要）
      restartCommand: "su - YOUR_USER -c \"docker restart pg-docker\""
```

写法有取舍，理由都在引擎的实现里：

- **前台（不加 `&`）**：`docker restart` 返回时容器已经起来了，引擎随后那句「重连成功」才有意义。
  代价是命令必须快 —— agent 等单条命令的上限是 30 秒；容器停得慢就先把停机时间调小。
- **detach（`nohup … &`）**：不会撞 30 秒上限，但引擎是在**命令返回之后**立刻去连的，
  库这时很可能还没停，于是「重连成功」和 `restarted=true` 都会是假的。用 detach 就必须
  自己到库上核 `SELECT pg_postmaster_start_time();`。
- **不要重定向输出**：agent 的 stdout/stderr 会被引擎收进 `restartOutput`（前端能看到），
  `> /tmp/x.log 2>&1` 等于把唯一的报错藏进数据库主机的文件里。

**参数写进去了、也重启了、读回却还是旧值** —— 最常见的原因是**容器是带命令行参数启动的**
（镜像 CMD 里的 `-c shared_buffers=12GB` 之类）：命令行优先级**高于** `ALTER SYSTEM` 写的
`postgresql.auto.conf`，那就只能改容器启动参数，`dbParams` 怎么改都压不过它。
脚本在应用失败时会把证据摆出来（`restarted` / `waitedSec` / 下发的重启命令 / 具体差在哪两项），
不阻断压测；`waitedSec` 不是判据（引擎是命令返回后才开始连的）。

### 3.2 `workloads.yaml` —— 一个文件可含多个负载

```yaml
workloads:
  - key: sm-600
    name: smallbank          # smallbank | tpcc（引擎只接受这两个）
    params:
      accountsNum: "1000"
      terminals: "20"
      duration: "600"        # 可选：只对没有故障的轮次生效，见 §4
      amalgamateWeight: "15"
      balanceWeight: "15"
      depositCheckingWeight: "15"
      sendPaymentWeight: "25"
      transactSavingsWeight: "15"
      writeCheckWeight: "15"
```

tpcc 的 `params` 是 `warehouses / concurrency / duration / reload`。
`reload: true` 表示建表 + 加载数据。建仓很贵（本环境 1000 仓实测 **4h08m**），而引擎建不建仓
只看 `runOptions.reload`、**从不问库里已经有多少仓** —— 所以脚本会在每个（库 + 这个负载）的
**第一轮之前**先查一次库里实际仓数（`POST /web/bench/tpcc/warehouse/verify`）：

| 查到的 | 脚本怎么做 |
|---|---|
| 仓数 = 配置 | 直接当普通测试跑，不建仓；后面几轮也不用建 —— 省掉这一次建仓 |
| 仓数 ≠ 配置（没建 / 建多 / 建少） | **先把建仓单独跑一轮**（预热轮：不带故障、`reload=true`），**等它跑完**再跑正式轮（等待上限用 `--build-timeout`）；这一对后面几轮一律 `reload=false` |
| 查不出来（连不上库 / 没权限 / 超时 / 引擎是旧构建） | **不猜**，按这里配的 `reload` 执行 |

两点要留意：

- 判据**只看仓数，不看 district**。所以「仓数对得上、但 district 行数不足」（上次加载中断、
  数据不全）算**通过**、不重建 —— 脚本会打一条醒目的 `!` 警告把 district 实际/期望值打出来，
  这一轮的指标要按「数据可能不全」来读。
- 「查不出来」不等于「仓数不符」：仓数是**未知**而不是**不等**，所以这时不会凭空触发一次
  几小时的建仓，而是退回 `reload` 配置。示例里 tpcc 都写着 `reload: true`，也就是这种情况下
  **仍然会建仓**，只是脚本会明说"没查成，按配置执行"。

每个（库 + 负载）**只在第一轮查一次**，后面几轮不再查、也不再重读 YAML。
OceanBase 不支持 tpcc，脚本会在启动时报错。

### 3.3 `orch/*.yaml` —— 一个文件可以放**多个**编排

一个文件里能写多个编排，每个编排有自己的 `key`（用来区分各轮结果）：

```yaml
name: combo-demo                        # 可选：这个文件的总编排 key，只给批次目录命名用

orchestrations:
  - key: combo-A                        # 这个编排的名字，参与结果目录命名
    batches:
      - duration: "60"                  # 可选：把这一批所有故障的 duration 统一覆盖成 60
        faults:
          - nameKey: faultinject.fault.connection.surge    # 同一批内 → 同时注入
            params: { concurrent-connections: "200" }
          - nameKey: faultinject.fault.sql.plan_flip
            params: { threads: "16" }

      - at: 93                          # 可选：这一批的绝对注入时刻（秒）
        faults:                         # 写了 at 就不再套「上一批结束 +30s」
          - nameKey: faultinject.fault.transaction.massive.rollback
            params: { threads: "16", rollback-rate: "80" }

  - key: combo-B                        # 同一批调用里接着跑的第二个编排
    batches:
      - faults:
          - nameKey: faultinject.fault.cpu.load
            params: { duration: "30" }
```

顶层还支持另外两种写法，效果一样，**旧文件不用改**：

| 顶层写法 | 含义 |
|---|---|
| `orchestrations: [...]` | 一个文件多个编排，**每项必须写 `key`** |
| `key:` + `batches:` | 单编排文件（就是上面只写一项） |
| 直接写批次列表 | 单编排文件，`key` 取文件名 |

**顶层 `name:`（可选，三种写法都能加）** 是这个**文件**的总编排 key，只用来给
本次调用的批次目录命名（见 §6）：`batch-results/<时间戳>_<库key>_<负载key>_<name>/`。
不写就退回文件名（`e1-orch.yaml` → `e1-orch`）；一次传多个编排文件时用 `+` 连起来
（`e1+combo-demo`）。**它和每个编排自己的 `key` 是两回事，互不影响**
—— 每轮的结果目录名和 `--only-orchestration` 仍然只看 `key`。

- `key` 在**一次调用的所有文件、所有编排**之间不能重复
  —— 结果目录名和 `--only-orchestration` 都靠它区分。
- **`batches:` 写成空列表 = 这一轮不注入任何故障**，只跑负载。
  可以当基线/对照组用；注意这时负载时长完全靠 `workloads.yaml` 里写的 `duration` 决定
  （没有故障计划给它托底，见 §4），要和别的轮次可比就得显式写够。
- **同一个 `faults:` 列表里的故障同时注入；不同批之间串行。**
- **批次可以写 `at: 93` 指定绝对注入时刻（秒，相对故障运行器启动）。** 写了就
  直接作为引擎的 `<planning>`，不再套「首批 t=60s / 批间 +30s」；没写 at 的批次保持原规则。
  同一批内的故障仍共用一个时刻。`at` 必须写在批次层，不能写在单个故障里。
- 想逐个串行，就每批只放一个故障；想把同一个故障串行注入 N 次，就写 N 个批。
- `nameKey` 取自引擎故障目录：`curl -s http://127.0.0.1:8080/web/faults/catalog | jq '.[].nameKey'`
- 目录里的故障（DBChaos 那 18 个）**不带 `duration` 也能跑** —— 用目录里的 `defaultDuration`；
  **OS 级 chaosblade 故障**（`cpu.load` / `network.package` / `disk.fill` / `process.*` …）
  不在目录里，**必须显式写 `params.duration`**，否则脚本启动时直接报错，不会拿猜来的数字算时长。

## 4. 负载时长是自动算出来的

故障时刻由引擎排：**第一批 t=60s，每批结束 +30s 开下一批**。脚本按同一套规则先算一遍：

```
批 1 基准 b₁ = 60s
批 i 结束 eᵢ = bᵢ + max(批 i 内每个故障的 duration)
批 i+1 基准 bᵢ₊₁ = eᵢ + 30s
计划终点 E = e_最后一批
负载时长 = E + tail        （tail 默认 20s，--tail 可调）
```

批次写了 `at:` 时，这一批的 `bᵢ = at`，不再由上一批推算；没写 at 的批次仍按上面的
规则接在**已有最晚终点**之后。计划终点取所有批次的 `max(bᵢ + duration)`。

有故障的轮次，负载时长**完全按计划算**：`计划终点 + tail`（tail 默认 20s，`--tail` 可调）。
`workloads.yaml` 里写的 `duration` 不参与 —— 它以前被当「下限」取 `max()`，改了故障时长
之后负载还是旧计划的长度，计划与实际对不上。想拉长稳态尾巴，调 `--tail`。
`duration` 只对**没有故障**的编排（`batches: []`）生效：那种轮次的计划终点是 60s，
不写就会只跑 80s。
dry-run 会把每批的时刻表和最终时长打印出来。

## 5. 运行

```bash
# 先看计划（不会提交任何任务）
python3 scripts/resbench_batch.py \
  --engine http://127.0.0.1:8080 \
  --databases scripts/batch-example/databases.yaml \
  --workloads scripts/batch-example/workloads.yaml \
  --orchestration scripts/batch-example/orch/combo-conn-planflip.yaml \
  --orchestration scripts/batch-example/orch/serial-os-faults.yaml

# 确认无误后真正开跑
python3 scripts/resbench_batch.py ... --execute
```

常用参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--engine` | `http://127.0.0.1:8080` | 引擎地址；不需要 `envInfo` |
| `--manager` | `http://127.0.0.1:8090` | res-manager 地址；压测期间由它建监控会话、归档曲线，并做「故障是否在资源上生效」的曲线判据（见 §6） |
| `--no-monitor` | 关 | 不建监控会话、不做曲线判据，只跑引擎。manager 或 Prometheus 不可用时也会自动按这个模式降级，不影响压测 |
| `--monitor-metrics` | 10 个默认指标 | 采集的指标 ID，逗号分隔。默认 `container.cpu.cores,container.cpu.usage,container.memory.usage,db.connections.usage,db.tps.agent,db.rollback.rate,host.netstat.retrans,host.netstat.reorder,host.disk.usage,host.process.stopped` |
| `--execute` | 关 | 不加就是 dry-run，只打印计划 |
| `--out` | `batch-results/` | 结果**根**目录：每次调用在它下面建一个批次目录，见 §6 |
| `--tail` | `20` | 负载在故障计划结束之后再跑多少秒 |
| `--settle` | `15` | 跑完等归档落盘再检索的秒数 |
| `--cooldown` | `0` | 轮与轮之间的额外冷却 |
| `--end-margin` | `600` | 超过「负载时长 + 本值」仍未结束就报超时（**普通**轮次） |
| `--build-timeout` | `21600` | **建仓预热轮**的等结束上限（6 小时）。1000 仓实测约 4h08m，仓数更多就往大调；它超时会**停下整批**并提示（不会带着"数据可能没就位"往下跑，也不会留下一串被「执行中」挡掉的空轮） |
| `--busy-wait` | `600` | 提交时撞上「执行中」（引擎上已有别的任务）最多等多少秒。等不到会打印**引擎自己说的状态**和清理办法（见下），然后**停下整批** —— 接着跑只会得到一串被同一道门挡掉的空轮 |
| `--keep-run-on-interrupt` | 关 | Ctrl+C / `kill` 时**不**停引擎上本轮的压测任务（默认会停，见下） |
| `--only-db` / `--only-workload` / `--only-orchestration` | 无 | 笛卡尔积的子集筛选（脚本的运行控制，不发给后端），可重复 |

**Ctrl+C / `kill` 中断时**：默认把脚本**自己刚提交的那一轮**停掉（`POST /web/bench/stop`，
body `[]`，与前端「停止」按钮同一条路），而不是留它在引擎上跑几小时。同时本轮会如实补一行
进批次 CSV（`note` 写明被中断、归档不完整），退出码 `130`，并按 `startTimestamp` 认领 ——
**引擎上若是别人的任务，脚本认出来就不会碰**（这点在共用引擎上很重要）。三点代价要知道：

- **已注入的故障不会自动恢复**：OS 级 chaosblade 故障带 duration 会自己到期，DBChaos 类
  随被杀掉的进程中止；恢复命令全文在归档的 `<负载>_<日期>.config.xml` 里，要清就按它来。
- 这一轮的归档不完整（没有 `metric.json` 等）。
- `kill -9` / 直接关终端覆盖不到（脚本没机会收尾），手工：
  `curl -X POST http://127.0.0.1:8080/web/bench/stop -H 'Content-Type: application/json' -d '[]'`

**引擎卡在「执行中」，但确实没有任务在跑？** 那是上一次被中断留下的残骸：引擎放行新任务
只看工作目录下的临时状态文件 `backend/resbenchmark/tmp/job.json`，里面若还写着
`curState: RUNNING`，每次轮询状态都会把这个「锁」重新装上。把它改成终止态即可放行、**不用重启引擎**：

```bash
# 先确认引擎自己怎么说：startTimestamp 是上次起跑时刻，message 为空说明它认为自己还在跑
curl -s http://127.0.0.1:8080/web/bench/status

# 再看那个文件（引擎每次轮询都重读它）
cat backend/resbenchmark/tmp/job.json
# {"curState":"RUNNING","startTimeStamp":"1790938926097","stopTimeStamp":"","success":false}
#        ↑ 改成 "MANUAL"（或 "GRACEFUL"），补一个 stopTimeStamp，success 保持 false

curl -s http://127.0.0.1:8080/web/bench/status   # end 应变回 true
```

注意 `POST /web/bench/stop` **清不掉它**（它只改引擎内存），重启引擎也清不掉（启动时不做对账）。

## 6. 结果记录

**一次调用 = 一个批次目录**，本次的全部产物都落在里面，不再平铺进 `--out` 跟别的批次混：

```
batch-results/
└── 20261001_122713_pg-docker_tpcc-360_e1/          ← 批次目录：<时间戳>_<库key>_<负载key>_<总编排key>
    ├── runs-20261001_122713.csv                    ← 每轮一行，整个批次一个文件
    └── <库key>__<负载key>__<编排key>/
        └── <historyName>.json                      ← 这一轮在归档检索里的整行原始数据
```

- 目录名里的三段 key 都来自**这次真正要跑的**组合（过完 `--only-*` 筛选），
  多个库/负载/编排文件时用 `+` 连起来，例如
  `20261001_122713_db-a+db-b_w1_e1+e2`；
- 总编排 key 就是编排文件顶层的 `name:`（见 §3.3），不写则用文件名；
- `runs-<批次时间戳>.csv` —— 含 `history_name`（引擎生成的运行名）、库/负载/编排、`suite`、
  计划终点、实际负载时长、成功与否、耗时、产物是否完整、指标
  （`stability` / `defence` / `recovery` / `rto` / `outage_seconds`）等；
- `<historyName>.json` —— 这一轮在归档检索里的整行原始数据。同一组合反复跑就在同一目录里累积；
- `<historyName>.faults.json` —— 这一轮每个故障的注入/恢复结果与自检状态
  （`GET /web/bench/inject/info` 的原始返回），用来回答"故障到底生没生效"；
- **`fault_inject`** 列 —— `ok` / `failed:2/3` / `unknown` / 空（`batches: []` 的基线轮）。
  它回答的是**故障有没有真的生效**：注入命令失败、或自检是 `NOT_ENFORCED`，都算 `failed`。
  `failed` 的轮次，同一行的 `defence` / `recovery` / `rto` **不能读作"数据库扛住了这个故障"**
  —— 故障根本没打上去，指标只是负载的基线波动。收尾时脚本会把这些轮次统一列出来；
- **`fault_inject_errors`** 列 —— 失败项的紧凑明细（如 `Disk Fill(rc=1)`），
  注入没失败时为空。查不到注入记录、或引擎是旧构建（没有 inject/info 接口）时为 `unknown`；
- **`fault_verify`** 列 —— 自检状态汇总（如 `ENFORCED:3` / `UNKNOWN:3`）。
  `UNKNOWN` 表示该故障**没有配置自检命令**（OS 级 chaosblade 故障都是这样），
  此时"生效与否"只能靠 `fault_inject` 的命令返回码判断，脚本不会把它当成失败；
- `<historyName>.monitor.json` —— 这一轮的曲线判据全文：判定、证据、每个注入窗口的
  基线/窗口区间、增量、命中的曲线标签，以及归档下来的整份曲线；
- CSV 里的 `json_file` 是**从当前目录出发**的路径，`cat` 它就能看，跟以前一样
  （只是现在前面多了批次目录这一层）。整个批次目录搬走的话这个路径会失效，整个 `--out` 一起搬没这问题。

### 6.1 监控曲线判据（`monitor_*` 三列）

`fault_verify` 只看引擎的自检命令，OS 级 chaosblade 故障（网络、磁盘、进程）没有自检，
只能靠返回码。**`monitor_*` 三列补的就是这一段**：从 manager 归档的曲线里，看注入窗口内
资源上有没有出现预期变化。压测开始时脚本用 `--monitor-metrics` 建采集会话
（`queryStepSec=5`，manager 的下限已从 10 秒放宽到 3 秒，所以按 5 秒归档，60 秒注入窗口约 12 个点），
正常结束/超时后归档，再按下面的窗口对齐：

- 每个注入批次取 `[注入开始, 注入结束]`，再往前取 **30 秒**做基线；
- 区间是**半开** `[lo, hi)`，基线不包含窗口起点那个采样点；
- 三个批次（默认 t=60/150/240s）各自判断，**全部可判定的窗口都命中**才算 `ENFORCED`。

| `monitor_verify` | 含义 |
|---|---|
| `ENFORCED` | 所有可判定的注入窗口里，预期曲线都出现了预期方向的变化 |
| `PARTIAL` | 一部分窗口命中（例如第 2 次注入时采集断了） |
| `NOT_ENFORCED` | 曲线有数据，但注入窗口里没有预期变化 —— **故障大概率没打上去** |
| `NO_DATA` | 归档里没有曲线，或没有运行开始时刻，无法对齐窗口 |

判据（`nameKey` 后缀 → 曲线 / 门槛，见脚本里的 `EFFECT_RULES`）：

| 故障 | 曲线 | 命中条件 |
|---|---|---|
| `cpu.load` | `container.cpu.cores` | 窗口均值比基线高 ≥ 2 核 |
| `memory.load` | `container.memory.usage` | 高 ≥ 5 个百分点 |
| `connection.surge` | `db.connections.usage` | 高 ≥ 5 个百分点 |
| `transaction.massive.rollback` | `db.rollback.rate` | 高 ≥ 1 tx/s；没有该曲线时退到 `db.tps.agent` 涨 ≥ 1.3 倍 |
| `network.package` | `host.netstat.retrans` | 高 ≥ 0.5 segs/s 且 ≥ 2 倍 |
| `network.unorder` / `network.duplicate` | `host.netstat.reorder` | 高 ≥ 0.2 events/s 且 ≥ 1.5 倍 |
| `disk.fill` | `host.disk.usage` | 目标挂载点高 ≥ 3 个百分点 |
| `process.stop` | `host.process.stopped` | 窗口里出现 T 状态进程、基线里没有 |

`monitor_run` 列记监控链路本身：`archived`（会话跑完并归档）、`ok`（建了会话但没走到归档）、
`failed`（会话没建起来）、`disabled`（`--no-monitor`、manager 或 Prometheus 不可用）、空（旧记录）。

`monitor_evidence` 是紧凑证据，形如
`批1 network.package host.netstat.retrans=+3.20 命中; 批2 … 未命中`；
窗口边界、曲线标签、原始点位都在 `<historyName>.monitor.json` 的 `detail` / `series` 里。

**注意**：没有配判据的故障（如 `filemove` / `fileremoved` / `filechmod`）不参与统计，
`ENFORCED` 只表示"所有**可判定**的故障都在曲线上留下了痕迹"，不是"所有故障都生效了"。

> 默认指标里的 `host.disk.usage` / `host.netstat.retrans` / `host.netstat.reorder` /
> `host.process.stopped` / `db.rollback.rate` 是监控目录里新增的指标。**manager 必须重启过**
> 才会在 `/monitoring/catalog` 里出现；没重启时建会话会因「未知指标」失败，
> 那一轮的 `monitor_run` 是 `failed`。`curl -s http://127.0.0.1:8090/monitoring/catalog`
> 能确认这 5 个 ID 在不在。

CSV 每行写完就 flush + fsync，中途中断也不会丢已完成的记录。
每轮还会调 `history/label` 打上 `batch / <库key> / <负载key> / <编排key>` 标签，
前端历史页可以用这些标签检索到这批结果。

组合目录名和引擎运行名里那段（`<负载名>_<组合名>_<时间戳>`）**是同一个字符串**，
所以 `results/` 下的归档目录也能一眼看出跑在什么环境上。