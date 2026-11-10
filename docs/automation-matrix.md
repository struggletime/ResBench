# 自动化测试矩阵（fixed-time / 多编排 / 组合故障）

脚本：`scripts/resbench_matrix.py`，示例配置：`scripts/matrix-example.yaml`

## 1. 它解决什么

- **测试矩阵**：`databases × workloads × orchestrations × repeat`，一次配置、串行跑完；
- **组合故障**：同一 `stage` 内多个故障**同时**触发（映射到引擎 `groupKey` 相同）；
  多个 `stage` = **串行级联**（引擎自动在组间留 30s）；
- **固定时间模式**：只发 `autoTimingMode=fixed-time`，触发时刻可精确预估；
- **自动进入归档**：每轮传 `testName / dbKey / orchestrationId / labels / source=script`，
  跑完用 `/web/bench/history/query` 把指标与产物完整性写进 `index.csv`，
  前端历史页可以直接检索到这批矩阵结果；
- **断点续跑**：`--resume <批次目录>` 跳过已成功的轮次；
- **默认 dry-run**：不加 `--execute` 不会提交任何任务。

## 2. 运行名规则

脚本传 `testName = <dbKey>_<workloadKey>_<编排id>_r<n>`，引擎按其规则拼成

```
<workload>_<dbKey>_<workloadKey>_<编排id>_r<n>_<yyyyMMdd_HHmmss>
例：smallbank_oglocal_std_combo-cpu-net_r1_20260921_120000
```

前缀一定是负载名，满足 `/bench/result`、`/bench/event`、`history/list` 的
`historyName.startsWith(testSuiteName)` 过滤要求。

## 3. 故障排程（与引擎完全一致）

引擎 `StartByFormService.buildScheduledFaults` 的规则，脚本 1:1 复刻用于预估与校验：

```
组基准 = 60s（第一组）
组内某故障触发时刻 = 组基准 + delaySeconds
组结束 = max(触发时刻 + duration)
下一组基准 = 组结束 + 30s
```

`duration` 取 `fault.params.duration`（秒），缺省用故障目录里的 `defaultDuration`；
脚本会显式把 `duration` 补进请求参数，保证"预估"与"实跑"一致。

> 已验证：对真实运行 `results/smallbank_20260827_013845`（5 组故障，引擎记录
> `faultDelay = 60/120/180/240/300`、`faultDuration = 30/30/30/30/60`），
> 脚本预测的起始时刻与引擎实际记录**逐个一致**，计划终点同为 360s。

单轮预计耗时 = `max(workload.params.duration, 计划终点) + 装载开销 + 恢复尾巴`，
`装载开销` 可用 `engine.estimatedOverheadSec` 或 `workloads.<key>.estimatedOverheadSec` 覆盖（TPCC 建议调大）。

## 4. 配置结构

```yaml
engine:
  baseUrl: http://127.0.0.1:8080   # 唯一需要知道的：引擎在哪（也可用 --engine 顶掉）
  # 其余键都有默认值，需要时再写（括号里是默认值）：
  #   env (openGauss)            环境名 → 请求里的 envInfo；引擎不看它，manager 用它选引擎
  #   monitorIntervalSec (1)     监控采样间隔 → 真正下发给引擎（-im）
  #   pollIntervalSec (5)        脚本多久查一次 /web/bench/status
  #   startTimeoutSec (180)      等任务真正跑起来的超时
  #   endMarginSec (300)         等结束的余量（恢复/核验尾巴）
  #   settleSec (20)             结束后等归档落盘再检索
  #   cooldownSec (30)           轮间冷却
  #   tailSec (60)               预估用：恢复/核验尾巴
  #   minSlackSec (30)           负载时长至少比故障计划多这么多
  #   estimatedOverheadSec (120) 建表/装载预估开销

archive:                # 归档元信息
  labels: [matrix, nightly]
  note: "自动化矩阵"

databases:
  <dbKey>:
    dbType: opengauss | mysql | postgresql | oceanbase | gaussdb
    deploymentType: standalone | distributed
    standalone: {ip, port, username, password}
    distributed: {...}          # oceanbase/gaussdb
    params:                     # 数据库配置参数（非连接信息）→ 进归档、可检索对比
      shared_buffers: 2GB
      max_connections: "500"
    container: opengauss-db     # 可选：见 §9 容器内注入

workloads:
  <workloadKey>:
    name: smallbank | tpcc
    params: {duration: "600", rate: "200", ...}
    runOptions: {reload: true}   # tpcc 每组第一轮脚本会自动开 reload
    estimatedOverheadSec: 600

orchestrations:
  # nameKey 必须来自引擎真实目录：python3 scripts/resbench_matrix.py --list-faults
  - id: combo-conn-planflip
    note: "连接风暴 + 执行计划翻转 同时发生"
    stages:
      - key: s1                  # 同 key 的故障同时触发
        faults:
          - {nameKey: faultinject.fault.connection.surge, delay: 0,
             params: {concurrent-connections: "200", duration: "60"}}
          - {nameKey: faultinject.fault.sql.plan_flip, delay: 0,
             params: {threads: "16", duration: "60"}}
      - key: s2                  # 下一阶段串行（引擎自动间隔 30s）
        faults:
          - {nameKey: faultinject.fault.transaction.massive.rollback, delay: 30,
             params: {threads: "16", duration: "60"}}

matrix:
  repeat: 1
  exclude: ["mysqllocal@w32"]    # 也可写整个 label
```

> **`engine` 段为什么只有 `baseUrl`？** 因为其余键都不是"引擎的配置项"，而是脚本作为
> **外部驱动器**的节奏参数（轮询/等待/冷却/预估）——引擎 API 里没有这些概念，前端自然也没有
> 对应输入框。前端的角色是"提交一次测试"，提交完就结束（引擎异步跑），它不等在引擎外面。
> 反过来：脚本必须知道引擎在哪，而前端不需要（同源）。
> 另外 `env` 的消费方是 **manager**（`benchMarkAddr.getBenchMarkAddr(envInfo)` 用它选哪台引擎），
> 引擎本身完全不看 `envInfo`；`monitorIntervalSec` 才是真的下发给引擎的（`-im`）。

## 5. 命令行

```bash
# 先看引擎里真实有哪些故障（nameKey/名称/默认时长/必填参数）——写编排前必做
python3 scripts/resbench_matrix.py --list-faults

# 预览矩阵、逐轮预计耗时与总时长（默认 dry-run，不提交任务）
python3 scripts/resbench_matrix.py --config scripts/matrix-example.yaml

# 只做环境预检：逐档数据库连通性（引擎 /web/bench/connection/test）+ 归档检索接口是否可用
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --preflight-only

# 离线预览（不连引擎校验故障目录）
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --skip-catalog-check

# 真正执行
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute

# 文件类故障的路径交给后端推荐（@auto: 哨兵），见第 10 节；不加就按 YAML 里写的参数跑
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute --auto-file-targets

# 只跑部分编排 / 覆盖重复次数 / 指定输出目录
python3 scripts/resbench_matrix.py -c cfg.yaml --execute --only combo-cpu-net,single-disk --repeat 3
python3 scripts/resbench_matrix.py -c cfg.yaml --execute --out runs/batch-20260921

# 断点续跑（跳过已成功的轮次）
python3 scripts/resbench_matrix.py -c cfg.yaml --execute --resume runs/batch-20260921

# --execute 默认会先跑预检，有库连不上就中止（避免长矩阵跑一半才发现）
#   --skip-preflight  跳过预检
#   --force           预检失败也继续
```

预检输出示例：

```
预检中（数据库连接 + 归档接口）...
  PASS  oglocal（opengauss）连接成功：连接成功
  FAIL  mysqllocal（mysql）：无法连接到数据库服务器，请检查 IP、端口是否正确……
  PASS  归档检索接口可用：index.csv 会带上指标与产物完整性
```

旧构建（没有新接口）不会被判失败，只给 `SKIP`/`WARN`，矩阵照常可跑。

## 6. 产物与检索

```
runs/<批次>/
  matrix.json            # 本次矩阵展开与配置路径
  index.csv              # 每轮一行：label/historyName/计划与实跑时长/成功与关键指标/完整性
  cells/<label>/
    request.json         # 实际提交的请求（连接信息已脱敏）
    schedule.json        # 故障排程（绝对触发时刻）
    result.json          # 跑完从 history/query 取回的归档行（指标+产物完整性）
```

`index.csv` 可直接做透视表（db × workload × 编排），也可以直接在前端历史页用
标签（`archive.labels`）、编排 id、`dbKey`、来源 `script` 检索到同一批运行。

## 7. 已知约束（脚本已校验或规避）

- 引擎同一时刻只跑一轮 → 脚本串行执行，轮间 `cooldownSec` 冷却；
- `smallbank`/`tpcc` 之外的负载名会被引擎拒绝；`oceanbase + tpcc` 组合非法；
- 分布式部署只支持 `oceanbase`/`gaussdb`；
- TPCC 必须 `reload=true` 建表加载，脚本对每个 `(dbKey, workloadKey)` 组的第一轮自动开启；
- `workload.params.duration` 必须 ≥ 故障计划终点（不足会被截断），脚本在 dry-run 阶段就会提示；
- **`nameKey` 必须与引擎目录逐字一致**（自己编的名字会被 dry-run 拦下），必填参数不写也会被拦；
- 部分故障与负载的表名耦合（如 `txn.deadlock_storm` 的 `table` 默认是 TPCC 的 `bmsql_stock`、
  `txn.uncommitted_txn` 的 `table` 为必填），跑 smallbank 时要么改 `table`，要么用 `matrix.exclude` 排除。

## 8. 数据库参数真的应用到库上

`databases[].params` 以前只被引擎读进 `run-meta.json` 归档，跑出来的结果跟写的参数无关。
现在语义是：

| 情况 | 行为 |
|---|---|
| `params` 里指定了 `shared_buffers` / `max_connections` | 按指定值应用；og/pg 需要重启，**引擎负责重启并等它连上** |
| `params` 没指定（或空） | **什么都不做**，直接用数据库现有配置 |
| 跑完之后 | **不还原**，保持改后的状态 |

MySQL 认 `innodb_buffer_pool_size` / `max_connections`，用 `SET GLOBAL` **在线生效，不重启**。
其他 `params` 键（如 `work_mem`）会被忽略并提示。

### 调引擎接口，不再自己连库

脚本把参数交给引擎的 `POST /web/bench/db/params`（前端「数据库配置 → 应用配置」按钮走的是同一份实现），
读现状 / 生成 SQL / 重启 / 轮询重连 / 读回核对全部由引擎完成：

```
脚本 → POST <引擎>/web/bench/db/params
  { dbType, deploymentType, standalone{ip,port,username,password},
    params{bufferPool, maxConnections},          ← 语义键；脚本负责从真实参数名翻译过来
    restart{command, waitSec}, agentPort }
   ← {success, message, actualNames, targets, before, after, statements,
      alreadyEffective, requiresRestart, applied, restarted, waitedSec, mismatches}
```

引擎内部的执行顺序（每档库只做一次）：

```
读现状（pg_settings / SHOW VARIABLES）
  └ 目标值已生效 → 跳过，不白重启一次
应用（ALTER SYSTEM SET / SET GLOBAL）
重启（仅 og/pg；把 restartCmd 经 agent /command/exec 下发到数据库主机）
  → 之后每 2 秒 JDBC 试连一次，最多 waitSec（默认 180，服务端钳在 [10,600]）
读回核对
  └ 核对不一致 → success=false + mismatches（没有回滚，所以"没生效"绝不能算成功）
```

**为什么不再让脚本自己连库执行 SQL**：那套逻辑引擎里已经有一份，两套实现会各自演化 ——
键名契约、取整容差（MySQL 会把 `100M` 取整成 `128M`）、报错文案都会分叉。
现在脚本只负责参数名翻译与结果映射，失败文案直接用引擎给的，跟界面上看到的一致。

> 需要引擎是**带该接口的构建**：旧构建会在预检阶段直接判 FAIL（"引擎没有参数应用接口（旧构建）"），
> 不会等到长矩阵跑到一半才发现。

### 配置

```yaml
databases:
  oglocal:
    params: {shared_buffers: 2GB, max_connections: "500"}
    agentPort: 8000          # 引擎把重启命令下发到 库IP:agentPort（默认 8000）
    # 需要重启时执行的命令，务必 detach。引擎不猜部署方式（systemd/docker/gs_ctl），必须显式给。
    # agent 的用户身份 = 启动 agent 的用户；openGauss 不能用 root 起，通常要 su 到数据库的 OS 用户；
    # 单机模式还要带上你自己安装的 -Z 参数（例如 -Z single_node）。
    restartCmd: 'nohup su - YOUR_USER -c "gs_ctl restart -D /home/YOUR_USER/gaussdb-data/data -Z single_node" >/tmp/rb_restart.log 2>&1 &'
    waitSec: 180
  mysqllocal:
    params: {innodb_buffer_pool_size: 2G, max_connections: "500"}
    # MySQL 在线生效 → 不用配 restartCmd
```

`sqlRunner` 已废弃（脚本不再自己连库执行 SQL）。配置里留着不会报错，预检会提示可以删掉。

单独验证（不跑矩阵）：

```bash
python3 scripts/resbench_matrix.py -c cfg.yaml --apply-db-params oglocal
python3 scripts/resbench_matrix.py -c cfg.yaml --apply-db-params all
```

`--preflight-only` / `--execute` 的预检会：探一下引擎有没有参数应用接口（用空 `params` 探，**不会碰数据库**）、
逐档打印"将要应用什么、要不要重启"。**缺 `restartCmd` 会在预检阶段直接判失败**，不会等到长矩阵跑到一半才发现。

### 三个坑

| 坑 | 依据 | 后果 | 对策 |
|---|---|---|---|
| agent 等待单条命令的上限是 **30 秒** | `agent/faults/command.py` 里 `asyncio.wait_for(..., timeout=30)` | 不 detach 的重启命令会被 agent kill 掉等待并回 `returncode=-1` | `restartCmd` 一律 `nohup ... >log 2>&1 &`；成功判据是引擎"能不能重连上"，不是命令返回 0 |
| **agent 以什么用户运行**决定 `su` 能不能用 | `/command/exec` 用 `create_subprocess_shell`，身份 = 启动 agent 的用户 | 非 root 时 `su - omm` 会从 stdin 读密码 → 认证失败（会明确报错，不会静默） | agent 以 root 跑（可以 `su` 到任何用户），或 `sudo -u <user> ...` 配 NOPASSWD |
| 改 bufferpool 是**全局副作用** | 且按约定不还原 | 跑完这台库一直保持改后的配置，其他使用者受影响 | 测试期间确认这台库没有别的业务 |

### 产物

- 每批 `runs/<批次>/db-params.json`：逐库记录 `action` / `targets` / `before` / `after` / `statements` /
  `restarted` / `waitedSec` / `mismatches` / `reason`（引擎给的中文说明，失败时直接看它）；
- `index.csv` 增加 `dbParams` 列（如 `shared_buffers=2GB+max_connections=500+restart`），
  方便区分"真的跑在 2GB 上"和"只登记了 2GB"；
- 归档里的 `database.params` 现在记的是**引擎读回的实际生效值**（真实参数名）：MySQL 把 `100M`
  取整成 `128M` 时记的就是 `128MB`。引擎没能报告（旧构建 / 请求就失败了）时退回配置里写的请求值。
- 参数值仍会进引擎的 `run-meta.json` 归档，历史页可按配置检索/对比。
## 9. 容器内注入（数据库跑在 Docker 里）

数据库跑在容器里时，宿主机视角的 CPU / 内存 / 磁盘 / 进程类故障打不到容器内部。
在库配置下加一个 `container`，引擎就会把这些 chaosblade 故障套一层 `docker exec` 在容器内执行。
（**前端「数据库配置」里有同一个输入框「容器名」**，填了走同一条路；脚本这边是 `container` / `containerName`。）

```yaml
databases:
  oglocal:
    dbType: opengauss
    standalone: {ip: 127.0.0.1, port: "5434", username: test1230, password: "<口令>"}
    container: opengauss-db     # ← 就这一行
```

| 故障类型 | 加了 `container` 之后 |
|---|---|
| chaosblade 类（`cpu.*` / `memory.*` / `disk.*` / `file.*` / `process.*`） | `docker exec <container> /opt/chaosblade/blade create ...`，在容器内生效 |
| 网络类（`faultinject.fault.network.*`） | **例外**：仍打到宿主机（容器网卡名与宿主机不同、网络命名空间隔离），引擎会打 WARN 日志说明忽略了这个容器名 |
| DBChaos 类（`connection.*` / `sql.*` / `txn.*` …，命令是 `java -jar DBChaos.jar`） | 不变：它通过 JDBC 连库，不需要在容器内跑 |

前置条件（都不满足时故障会注入失败或打到宿主机，脚本不做检测）：

1. **容器内已装 chaosblade**，`/opt/chaosblade/blade` 在容器里可执行（宿主机装了不算）；
2. **agent 的 OS 用户在 `docker` 组**，否则 `docker exec` 权限不足；
3. **容器名在 agent 主机上能解析**（agent 与容器同机，脚本下发的 agent 地址就是 `库IP:8000`）。

预检会在配了 `container` 的库那一行下面打印
`---- <dbKey>：chaosblade 类故障将在容器 <name> 内注入（docker exec；网络类故障仍走宿主机）`，
跑之前就能确认没配错。同样地，`index.csv` 里多了一列 `container`，事后能分辨每一轮打在哪；
引擎的 `run-meta.json` 也会在 `database.containerName` 记下容器名（没配就不写这个键）。

### 9.1 容器库上的「应用配置」：重启命令要自己写

后端**不会**替你合成重启命令（systemd / docker / gs_ctl 一概不猜），只把 `restartCmd`
原样下发给 agent。库跑在容器里时，宿主机上那条 `gs_ctl` / `systemctl` 会重启到**另一个**库：

```yaml
    container: og-db
    params:
      shared_buffers: 2GB
      max_connections: "500"
    # 在**宿主机**上执行、不套 docker exec → 直接 docker restart；
    # 必须 detach（nohup … &），否则会被 agent 侧 30 秒的单命令等待上限 kill 掉等待。
    restartCmd: 'nohup docker restart og-db >/tmp/rb_restart.log 2>&1 &'
```

另有一条容易踩的：磁盘类故障的路径按**容器内**路径解析，
`disk.fill.path` / `disk.filemove.src-path` / `disk.fileremoved.file-path` / `disk.filechmod.filepath`
要填 `SHOW data_directory` 读到的容器内路径，不是宿主机的挂载路径。
（文件类故障的路径可以不自己填，见第 10 节。）

---

## 10. 文件类故障：路径交给后端推荐（`--auto-file-targets`）

文件类故障要打哪个文件，取决于这一轮灌完数据后库长什么样（库目录在哪、最热的表是哪张、
它的段文件叫什么）。手填的路径一旦和实际不符，故障就打在空气上——**注入报成功、指标毫无变化**，
这类"假成功"最难查。所以脚本给了一根开关：

```bash
# 打开：所有文件类故障的路径参数换成 @auto: 哨兵，由引擎在开跑那一刻查库解析成真实路径
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute --auto-file-targets

# 不加：完全按 YAML 里写的参数跑（今天的默认行为）
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute
```

开关**只改路径参数**，`mark` / `content` / `duration` 这些仍按 YAML 写的样子；
数据库类故障（`sql.*` / `txn.*` / `connection.*` …）完全不受影响。dry-run 会先把替换结果打出来：

```
--auto-file-targets：1 轮的文件类故障改用推荐参数
  → oglocal_bs_O-filemove_r1：1 个文件类故障改用推荐参数 ——
    File Move（src-path → @auto:db-dir、dst-dir → @auto:pgdata）
```

### 10.1 四个故障各换成什么

哨兵取值来自后端 `FileFaultTargetService.suggestFor` 给每个故障标的**高强度档**推荐项——
就是前端故障表单点「推荐」会高亮的那一条，不是脚本自己编的：

| 故障 nameKey | 路径参数 | 打开的哨兵 | 对应后端的哪条规则 |
|---|---|---|---|
| `...disk.filemove` | `src-path` | `@auto:db-dir` | `filemove:high` → 整个库目录 |
| `...disk.filemove` | `dst-dir` | `@auto:pgdata` | `filemove:dst-dir` → 数据总目录（PG 的 PGDATA / MySQL 的 `@@datadir`） |
| `...disk.filechmod` | `filepath` | `@auto:db-dir` | `filechmod:high` → 整个库目录 |
| `...disk.fileappend` | `filepath` | `@auto:hottest-last-segment` | `fileappend:high` → 最热表的最后一段 |
| `...disk.fileremoved` | `file-path` | `@auto:hottest-file` | **后端没有这条规则**，脚本自定 → 取最热文件的第 0 段 |

哨兵能用的名字就这六个：`db-dir`、`hottest-file`、`hottest-last-segment`、
`hottest-mid-segment`（最热表第 1 段，且它不能是最后一段）、`datadir`、
`pgdata`（老名字，与 `datadir` 是同一个值）。
要别的组合，直接写进 YAML 的 `params` 即可（不加开关时逐字透传）；
名字写错引擎会报错并列出可选项，不会静默忽略。

哨兵是**按库类型现场解析**的，同一个名字在不同库上落到不同文件：

| | 数据总目录（`datadir` / `pgdata`） | 最热表的文件 | 分段 |
|---|---|---|---|
| PostgreSQL | `PGDATA` | `base/<oid>/<relfilenode>` | 1 GiB 一段，有第 0 段 / 最后一段 / 中间段之分 |
| MySQL | `@@datadir` | `<datadir>/<库>/<表>.ibd` | InnoDB 一表一个文件、不分段 |

所以 MySQL 上 `hottest-last-segment` 就是整张 `.ibd`（不报错），`hottest-mid-segment`
明确报"解析不出路径"（不分段的库没有中间段），其余库类型报"暂不支持按库类型…挑选目标文件"。

### 10.2 两个必须知道的

- **这四个 nameKey 不在引擎的故障目录里。** `--list-faults` 里它们标着 `*` 并注明"脚本自带"：
  引擎的 `config/fault-cases.yaml` 只收了 DBChaos 的 18 个库内故障，文件类是 chaosblade 的
  宿主机操作，引擎用 switch 直接接，所以目录接口查不到它们的条目。nameKey 是有效的。
- **建仓的那一轮不是问题，哨兵只是解析得晚。** `@auto:` 要在库里查表才能定位文件，
  而 `create` / `load` / `reload` 会先 DROP + CREATE 再灌数据 —— 文件号是那之后才生成的，
  提交那一刻解析必然过期。所以引擎在提交时**故意不解析**，把哨兵原样写进配置，
  由 job 在 `create + load` 做完之后、负载与故障注入开始之前解析：
  「先建仓完成 → 再确认文件故障的参数 → 再从第一个测试开始跑」。tpcc 每组第一轮强制
  reload，用这一条就能直接跑，不需要先跑一轮预热。
  dry-run 会把这类轮次单独列出来（`!` 开头，不算校验问题）。
  反过来，`create` / `load` / `reload` 全为假（打库里现成的数据）时引擎在提交那一刻就解析，
  解析不了当场拒绝。
