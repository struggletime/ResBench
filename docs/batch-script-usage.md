# 批量测试脚本使用说明（resbench_batch.py）

## 1. 它是干什么的

一个脚本跑完一大批组合压测，**全程不启动前端**。

- **一次调用 = 一个批次**：把 `数据库 × 背景负载 × 故障编排` 做**笛卡尔积**，**串行**跑完。
- 每一轮自动：起库/建表加载 → 按编排注入故障 → 等负载和故障都结束 → 归档 → 进下一轮。
- 只调用引擎（`resbenchmark`，默认 `:8080`）自己的接口，不依赖前端代码或构建。
- **提交的参数和前端表单一致，只多一个 `testName`** —— 前端表单里没填它，
  脚本主动补上组合名（`<库key>__<负载key>__<编排key>`），让每轮的结果目录自带环境信息，见第 5 节。
  （`containerName` 也是顶层字段，但**前端「数据库配置」里已经有对应的「容器名」输入框**，
  脚本这边就是 `databases[].container`，见第 3.1 节。）
- **Ctrl+C / `kill` 会把脚本自己刚提交的那一轮停掉**，不留它在引擎上继续跑；
  引擎上若是别人的任务，脚本认得出、不会碰。见第 8 节。

## 2. 快速开始

```bash
# ① 先只看计划，不提交任何任务（默认就是 dry-run）
python3 scripts/resbench_batch.py \
  --databases scripts/batch-example/databases.yaml \
  --workloads scripts/batch-example/workloads.yaml \
  --orchestration scripts/batch-example/orch/combo-conn-planflip.yaml \
  --orchestration scripts/batch-example/orch/serial-os-faults.yaml

# ② 计划确认无误，加 --execute 真正开跑
python3 scripts/resbench_batch.py ...同上... --execute
```

dry-run 会打印每一轮的库/负载/编排、每批故障的注入时刻表、以及算出来的负载时长。

## 3. 三份配置文件

三份**分开写**，格式都是 YAML。`key` 不发给后端，只用两件事：
脚本内部互相引用，以及**拼结果目录名**（`<库key>__<负载key>__<编排key>`，见第 5 节）。

### 3.1 数据库配置 `databases.yaml` —— 一个文件可写多个库

```yaml
databases:
  - key: og-local
    dbType: opengauss          # opengauss | mysql | postgresql | oceanbase | gaussdb
    deploymentType: standalone # standalone | distributed
    standalone:
      ip: YOUR_DB_HOST
      port: "5434"
      username: YOUR_USER
      password: <口令>
    # 可选：等于前端「应用配置」按钮，先改库上的缓冲池/最大连接数再压测
    dbParams:
      bufferPool: 2GB
      maxConnections: "500"
      restartCommand: "nohup gs_ctl restart -D /gaussdb/data >/tmp/x.log 2>&1 &"
    # 可选：数据库跑在 Docker 容器里时填容器名 → chaosblade 类故障在容器内注入
    # （cpu/memory/disk/file/process）。网络类故障例外，仍打到宿主机。
    # 前置：容器内已装 chaosblade、agent 的 OS 用户在 docker 组、容器名在 agent 主机可解析。
    container: opengauss-db
```

- `dbParams` 在**这个库的第一个轮次之前应用一次**，后续轮次沿用，不重复重启。
- 只有单机 `opengauss / postgresql / mysql` 支持；og/pg 必须重启才生效，所以要写 `restartCommand`。
- `container` 不填就是不套 `docker exec`，故障打在宿主机（与以前完全一致），
  请求体里也**不会出现** `containerName` 这个键。
  详细的适用故障类型与前置条件见 `docs/automation-matrix.md` §9。
- 配了容器的库，**`restartCommand` 要自己写成 docker 形式** —— 后端不替你合成
  systemd/docker/gs_ctl（它只把这条命令原样下发给 agent）：

  ```yaml
      container: pg-docker
      dbParams:
        bufferPool: 2GB
        maxConnections: "500"
        # 命令在宿主机上执行，不套 docker exec，所以直接 docker restart；
        # su - <用户> -c 只是借那个用户的 docker 组权限（agent 已在 docker 组就不需要）。
        # 建议前台（不加 &）、不重定向：理由见下面两条。
        restartCommand: "su - YOUR_USER -c \"docker restart pg-docker\""
  ```

- **重启命令怎么写，是有取舍的**（都在引擎的实现里）：
  **前台**跑，命令返回时容器已经起来了，引擎随后那句「重连成功」才有意义；代价是必须快
  —— agent 等单条命令的上限是 30 秒。**detach**（`nohup … &`）不会撞这个上限，
  但引擎是在命令返回之后**立刻**去连的，库这时往往还没停，于是「重连成功」是假的；
  用了 detach 就得自己到库上核 `SELECT pg_postmaster_start_time();`。
  另外**不要把输出重定向走**：agent 的 stdout/stderr 会被引擎收进 `restartOutput`（前端可见），
  `> /tmp/x.log 2>&1` 等于把唯一的报错藏进数据库主机的文件里。
- **应用后报「已执行，但读回不一致」时**，脚本会接着把证据打出来（`restarted` / `waitedSec` /
  下发的重启命令 / 具体差在哪两项），并给出最可能的原因。最常见的一条是：
  容器是**带命令行参数**启动的（镜像 CMD 里的 `-c shared_buffers=12GB` 之类），
  命令行优先级**高于** `ALTER SYSTEM` 写的 `postgresql.auto.conf` —— 这时只能改容器启动参数。
  注意 `waitedSec` **不是**判据：引擎是命令返回之后才开始连的，库若在命令返回前就已经起来，
  它同样是 0。**不阻断压测**：参数没生效时后续轮次照跑，只是这一轮的库参数不是你配的那个。

- 容器库上，磁盘类故障的路径（`disk.fill.path` / `disk.filemove.src-path` /
  `disk.fileremoved.file-path` / `disk.filechmod.filepath`）按**容器内**路径解析
  —— 要填 `SHOW data_directory` 读到的容器内路径，不是宿主机的挂载路径。
- 容器名会记进 `run-meta.json` 的 `database.containerName`，CSV 里也有一列 `container`，
  dry-run 的轮次列表里也会标出 `[容器 <名字>]`，事后能分辨这一轮打在哪。

### 3.2 背景负载配置 `workloads.yaml` —— 一个文件可写多个负载

```yaml
workloads:
  - key: sm-600            # 参与结果目录命名：<库key>__<负载key>__<编排key>
    name: smallbank        # smallbank | tpcc
    params:
      accountsNum: "1000"
      terminals: "20"
      duration: "600"      # 可选，只对没有故障的轮次生效，见第 4 节
      amalgamateWeight: "15"
      balanceWeight: "15"
      depositCheckingWeight: "15"
      sendPaymentWeight: "25"
      transactSavingsWeight: "15"
      writeCheckWeight: "15"
```

tpcc 的 `params` 是 `warehouses / concurrency / duration / reload`。
`reload: true` = 建表 + 加载数据。建仓很贵（本环境 1000 仓实测 **4h08m**），而引擎建不建仓
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

### 3.3 故障编排 `orch/*.yaml` —— 一个文件可以放**多个**编排

一个文件里能写多个编排，每个编排有自己的 `key`（用来区分各轮结果）：

```yaml
name: combo-demo              # 可选：这个文件的总编排 key，只给批次目录命名用

orchestrations:
  - key: combo-conn-planflip    # 这个编排的名字，参与结果目录命名

    batches:
      - duration: "60"          # 可选：把这一批所有故障的时长统一覆盖成 60s
        faults:                 # 同一个 faults 列表里 → 同时注入（组合故障）
          - nameKey: faultinject.fault.connection.surge
            params: { concurrent-connections: "200" }
          - nameKey: faultinject.fault.sql.plan_flip
            params: { threads: "16" }

      - faults:                 # 不同批 → 串行，上一批结束 +30s 再开
          - nameKey: faultinject.fault.transaction.massive.rollback
            params: { threads: "16", rollback-rate: "80" }

  - key: baseline               # 同一个文件里的第二个编排，接着跑下一轮
    batches: []                 # 空列表 = 这一轮不注入任何故障，只跑负载
```

顶层还支持另外两种写法，效果一样，**旧文件不用改**：

| 顶层写法 | 含义 |
|---|---|
| `orchestrations: [...]` | 一个文件多个编排，**每项必须写 `key`** |
| `key:` + `batches:` | 单编排文件（就是上面只写一项） |
| 直接写批次列表 | 单编排文件，`key` 取文件名 |

**顶层 `name:`（可选，三种写法都能加）** 是这个**文件**的总编排 key，只用来给
本次调用的批次目录命名（见第 5 节）。不写就退回文件名（`e1-orch.yaml` → `e1-orch`）。
**它和每个编排自己的 `key` 是两回事** —— 每轮的结果目录名和 `--only-orchestration`
仍然只看 `key`，加了 `name:` 不会影响它们。

- `key` 在**一次调用的所有文件、所有编排**之间不能重复 —— 结果目录名和
  `--only-orchestration` 都靠它区分。
- **同一批内同时注入，批与批之间串行**。想逐个串行，就每批只放一个故障；
  想把同一个故障串行注入 N 次，就写 N 个批。
- **`batches:` 写成空列表 = 这一轮不注入任何故障**，可以当基线/对照组。
  注意这时负载时长完全靠 `workloads.yaml` 里的 `duration` 决定（没有故障计划给它托底，
  见第 4 节），要和别的轮次可比就得显式写够。

`nameKey` 从引擎的故障目录里取：

```bash
curl -s http://127.0.0.1:8080/web/faults/catalog | jq '.[].nameKey'
```

> ⚠ 目录里的 18 个 DBChaos 故障**不写 duration 也能跑**（用目录默认值）。
> 但 OS 级 chaosblade 故障（`cpu.load` / `network.package` / `disk.fill` / `process.*` …）**不在目录里**，
> 必须显式写 `params.duration`，否则脚本启动时直接报错。

### 3.4 文件类故障的路径：交给后端推荐（`--auto-file-targets`）

`scripts/batch-example/file-orch.yaml` 里那些 `base/12994` / `141113` 是**手抄的 OID**，
重灌一次数据就全变了（那个文件头部写着"重灌过数据就要重取 OID"）。
加 `--auto-file-targets` 就不用抄了 —— 路径参数会被换成 `@auto:` 哨兵，
引擎查库解析成真实路径（这一轮要建表 / 灌数据时，脚本会先把建仓单独跑一轮，见本节末尾），随灌随对：

```bash
# 打开：文件类故障的路径改用后端推荐目标
python3 scripts/resbench_batch.py --databases ... --workloads ... \
    --orchestration scripts/batch-example/file-orch.yaml --auto-file-targets --execute

# 不加：完全按编排里写的路径跑（今天的默认行为）
python3 scripts/resbench_batch.py --databases ... --workloads ... \
    --orchestration scripts/batch-example/file-orch.yaml --execute
```

只改**路径参数**，`mark` / `content` / `duration` 照旧；数据库类故障（`sql.*` / `txn.*` …）
一条都不碰。dry-run 会先把替换表打出来：

```
--auto-file-targets：文件类故障的路径改用后端推荐目标
  filemove   dst-dir→@auto:pgdata  src-path→@auto:db-dir
  涉及 1 轮：pg-docker_… × tpcc-1000c-200 × file-move-high
  建仓与故障不放在同一轮里：仓数预检判定某对「库 × tpcc 负载」要建仓时，会**先单独跑一轮
  预热轮**（不带故障、reload=true）把仓建好，之后该对的正式轮一律 reload=false ——
  哨兵在正式轮提交时才解析，拿到的是刚建好的那份文件（建仓会重写文件号，提交那一刻算出来的
  路径建完就过期）。
```

替换规则取自后端 `FileFaultTargetService.suggestFor` 各故障的**高强度档**推荐项
（前端故障表单点「推荐」会高亮的那一条），不是脚本编的：

| 故障 | 参数 | 打开的哨兵 | 后端规则 |
|---|---|---|---|
| `disk.filemove` | `src-path` / `dst-dir` | `@auto:db-dir` / `@auto:pgdata` | `filemove:high` / `filemove:dst-dir` |
| `disk.filechmod` | `filepath` | `@auto:db-dir` | `filechmod:high` |
| `disk.fileappend` | `filepath` | `@auto:hottest-last-segment` | `fileappend:high`（**单文件布局上后端不给这条推荐**，见下） |
| `disk.fileremoved` | `file-path` | `@auto:hottest-file` | 后端**没有**这条规则，脚本自定（最热文件第 0 段） |

低档（`level=low`，前端点「推荐」时另一条高亮项）的规则是：**只推最热的那一个文件** ——
PG 推最热关系的第 0 段，MySQL 推最热表的 `<表>.ibd`。同一个库里其余表的文件照样列出来，
但不标★（都标等于没标）。

引擎认的哨兵就这几个：`db-dir` / `hottest-file` / `hottest-last-segment` /
`hottest-mid-segment` / `datadir`（老名字 `pgdata` 是同一个值，也认）。写错引擎会报错并列出可选项，
不会静默忽略。想用别的档位，直接把哨兵写进编排的 `params`
（比如 `filepath: "@auto:hottest-mid-segment"`），不用开关也一样生效。

哨兵由引擎**按库类型现场解析**，同一个名字在不同库上落到不同文件：

| 库类型 | 数据总目录 | 一个表的文件 | 分段 |
|---|---|---|---|
| PostgreSQL | `PGDATA` | `PGDATA/base/<oid>/<relfilenode>` | 1 GiB 一段，有第 0 段 / 最后一段 / 中间段 |
| MySQL | `@@datadir` | `<datadir>/<库>/<表>.ibd` | 不分段（InnoDB 一表一个文件，索引就在这个 `.ibd` 里） |

所以在 MySQL 上：`hottest-last-segment` 就是整张表的 `.ibd`；**没有"非最后一段"** ——
写 `@auto:hottest-mid-segment` 会明确报"解析不出路径"并让你换 `hottest-file` / `db-dir`，
不会给一个错的文件。其余库类型没有探针，用哨兵会直接报"暂不支持按库类型…挑选目标文件"。

MySQL（以及 PG 上那些只有一段的表）上 **append 没有安全档**：每一档都追写在表的主数据文件末尾，
而引擎不传 `--enable-backup`。所以后端在这种布局上**不给 append 标推荐项**，清单里会多一条 note 说明；
`--auto-file-targets` 仍会把 `fileappend` 换成 `hottest-last-segment`（等于整张 `.ibd`），
脚本会把它列进"不可恢复"提醒里。要测 append 就用多段表的中间段，或干脆在 MySQL 上只测 chmod / move。

**两个必须知道的后果：**

- **append 被换成"最后一段"是不可恢复的那一档。** 引擎渲染的 append 命令从来不传
  `--enable-backup`，追加进去的字节不会被撤销 → 故障窗口结束后**这个表还是坏的**，
  要 TRUNCATE / 重灌才会好。这正是 `file-orch.yaml` 把 `file-append-high` 默认关掉的原因。
  要安全的低档就在编排里手写 `@auto:hottest-mid-segment`（不分段的库没有中间段，
  那边得直接写具体文件），或这一个编排别开开关。
  （计划里会点名把这条打出来。）
- **chmod 只映射到 `db-dir`（high）**：编排里原先打在单个文件上的 low 档会被提到 high，
  强度从"一个文件"变成"整个库目录"。

**建仓单独占一轮，不跟正式测试挤在一起。** 原因：`reload` 会先 DROP + CREATE 再灌数据，
文件号是那之后才生成的；而哨兵要在**提交那一刻**解析成真实路径。两件事放进同一次请求，
解析出来的路径建完仓就过期，故障会打在已经不存在的文件上（"注入成功但指标毫无变化"，
最难查的那种）。

所以脚本把「重新 load」当成一个独立阶段，顺序是：

1. **仓数预检**（`POST /web/bench/tpcc/warehouse/verify`，前端「仓数预检」同一个接口）。
   每个「库 × tpcc 负载」在该对开跑前只查一次。仓数对得上 → 直接进第 3 步，不建仓。
2. **预热轮**：判定要建仓时，先单独跑一轮 —— 不带任何故障（因此没有哨兵）、`reload=true`。
   跑完数据就位（运行名带 `-prime` 后缀，归档里一眼看出这不是一次正式测试）。
3. **正式轮**：`reload=false`，带真正的故障编排。哨兵在提交那一刻解析，拿到的就是预热轮
   刚建好的那份文件。这一对的后续轮次同样 `reload=false`，不再重复建仓。

预热轮的负载照跑（和正式轮同样的 duration），不改成 `execute=false`：那样不产出
summary/monitor/metric，归档永远不算「齐」，脚本只能一直等到超时。等它的上限同样是
`负载时长 + --build-timeout`；它没跑成（超时 / 引擎判失败）时本批次会停下来，不会带着
"数据可能没就位"往下跑。

不做建仓的轮次（`reload=false`，直接打库里现成的数据）哨兵本来就在提交那一刻解析，
解析不了当场拒绝 —— 那一轮不会有新文件号，早失败比跑一半再失败好。

## 4. 负载时长是自动算出来的

故障时刻由引擎排，脚本按同一套规则先算一遍：

```
第 1 批注入时刻      t = 60s
第 i 批结束时刻      = 该批注入时刻 + 批内最大 duration
第 i+1 批注入时刻    = 上一批结束 + 30s
计划终点 E           = 最后一批结束时刻
负载时长             = E + tail（tail 默认 20s）
```

有故障的轮次，负载时长**完全按计划算**（`计划终点 + tail`）。`workloads.yaml` 里写的
`duration` 不参与：以前取 `max(你写的, 算出来的)`，改了故障时长之后负载还是旧计划的长度，
计划与实际对不上。想拉长稳态尾巴请调 `--tail`。`duration` 只对没有故障的编排
（`batches: []`）生效 —— 那种轮次的计划终点是 60s，不写就只跑 80s。

## 5. 结果存在哪

### 每轮运行：`backend/resbenchmark/results/<运行名>/`

**运行名自带实验环境**，脚本发 `testName` 让引擎按这个格式生成：

```
smallbank_og-local__sm-auto__serial-os-faults_20260930_002754
└─负载名─┘└──库key──┘└负载key┘└──编排key──┘└──时间戳──┘
```

（前缀必须保留负载名，否则历史检索的 `startsWith` 过滤会查不到这一轮。）
同样的组合名也会出现在 `config/generated/` 里那几份输入 XML 的文件名上。

目录里：

| 文件 | 内容 |
|---|---|
| **`run-meta.json`** | **实验环境标注**：库类型/部署方式/库参数、负载名与全部参数、故障清单（nameKey + 参数 + 分组）、起跑选项、标签、备注 |
| `<负载>_<日期>.config.xml` | 实际生效的配置：连接目标、故障注入/恢复命令全文、agent 地址、负载参数（**不含用户名密码**） |
| `job.json` | 起止时间、成功与否、结束状态 |
| `monitor.csv` | 每秒采样的吞吐与延迟 |
| `faultInject.json` | 每个故障的注入/恢复结果、耗时 |
| `<负载>_<日期>.faults.csv` | 每个故障的**实际注入时刻 + 持续时长**，用来核对计划 |
| `<负载>_<日期>.summary.json` | 数据库版本、负载类型、terminals、scalefactor |
| `metric.json` | 韧性指标（稳定性/防御/恢复/RTO）；**没生成就是缺失** |
| `.raw.csv` / `.results.csv` / `.samples.csv` / `.data_loss.csv` | 明细数据 |

`run-meta.json` 里的 `connectionArchived: false` 表示**连接信息有意不归档**（不含用户名密码）。

### 脚本自己的索引：`batch-results/`（可用 `--out` 改）

**一次调用 = 一个批次目录**，本次的全部产物都落在里面，不会跟别的批次混在一起。
目录名自带这次跑的实验环境：

```
batch-results/
└── 20261001_122713_pg-docker_tpcc-360_e1/          ← <时间戳>_<库key>_<负载key>_<总编排key>
    ├── runs-20261001_122713.csv                    ← 每轮一行，整个批次一个文件
    └── pg-docker__tpcc-360__e01-cpu-load-high/     ← 和引擎运行名里那段组合名一致
        └── tpcc_pg-docker__tpcc-360__e01-cpu-load-high_20261001_122715.json
```

- **总编排 key** 取编排文件顶层的 `name:`（见第 3.3 节），不写就用文件名；
- 三段 key 都来自**这次真正要跑的**组合（过完 `--only-db` / `--only-workload` /
  `--only-orchestration` 筛选），不是配置文件里全写了什么；
- 一次选了多个库 / 多个负载 / 多个编排文件时，同一段用 `+` 连起来：
  `20261001_122713_db-a+db-b_w1_e1+e2`；
- `runs-<批次时间戳>.csv` —— 每行一轮，含运行名、`suite`、库/负载/编排、计划终点、实际负载时长、
  成功与否、耗时、产物是否完整、各项指标；
- `<运行名>.json` —— 这一轮在归档检索里的整行原始数据；
- `<运行名>.faults.json` —— 这一轮每个故障的注入/恢复结果与自检状态
  （`GET /web/bench/inject/info` 的原始返回）。
- CSV 里的 `fault_inject` / `fault_inject_errors` / `fault_verify` 三列回答的是
  **故障有没有真的生效**：

  | 列 | 取值 | 含义 |
  |---|---|---|
  | `fault_inject` | `ok` / `failed:2/3` / `unknown` / 空 | 注入命令全通 → `ok`；任一条返回 `Executed: Error <n>`、agent 调用失败、或自检 `NOT_ENFORCED` → `failed`；基线轮（`batches: []`）为空 |
  | `fault_inject_errors` | 如 `Disk Fill(rc=1)` | 失败项的紧凑明细；`unknown` 时写原因 |
  | `fault_verify` | 如 `UNKNOWN:3`、`ENFORCED:3` | 自检状态汇总。`UNKNOWN` = 该故障没有配置自检命令（OS 级 chaosblade 故障都是这样），不算失败 |

  **`fault_inject=failed` 的轮次，同一行的 `defence` / `recovery` / `rto` 不能用来回答"加上这个故障会怎样"**
  —— 故障没打上去，这一轮数据库是在没有故障的状态下跑的，分数只反映它平时的样子。收尾时脚本会把这些轮次连同失败原因统一列出来。

  > 为什么脚本要自己解析：引擎归档里的 `error` 字段恒为 `false`
  > （`FaultInjector.markResult` 拿到的是 agent 的 `{"result": ...}` JSON 外壳，匹配不上裸串），
  > `enforced_fault_count` 实测也恒为 0。只有 agent 返回的原始串里才写着真相。
- CSV 里的 `json_file` 是**从当前目录出发**的路径，跟以前一样可以直接 `cat`，
  只是前面多了批次目录这一层；整个批次目录被搬走时它会失效（把整个 `--out` 一起搬没这个问题）。
- 每行写完立刻落盘（flush + fsync），中途中断也不丢已完成记录。
- 每轮还会打上 `batch / <库key> / <负载key> / <编排key>` 标签，
  **前端历史页可以用这些标签检索到这批结果**，也可以直接按"测试名"搜组合名。

> `--out` 下**以前平铺的老结果不会被迁移或删除**，新布局只影响之后的调用。

## 6. 常用参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--engine` | `http://127.0.0.1:8080` | 引擎地址 |
| `--execute` | 关 | 不加就是 dry-run，只打印计划 |
| `--out` | `batch-results/` | 结果**根**目录：每次调用在它下面建一个批次目录（见第 5 节） |
| `--tail` | `20` | 故障计划结束后，负载再跑多少秒 |
| `--only-db` / `--only-workload` / `--only-orchestration` | 无 | 只跑笛卡尔积的子集，可重复 |
| `--end-margin` | `600` | 超时判定：负载时长 + 本值仍未结束就报超时（**普通**轮次） |
| `--build-timeout` | `21600` | **建仓预热轮**的等结束上限（6 小时）。1000 仓实测约 4h08m，仓数更多就往大调；它超时会**停下整批**并提示（不会带着"数据可能没就位"往下跑，也不会留下一串被「执行中」挡掉的空轮） |
| `--busy-wait` | `600` | 提交时撞上「执行中」（引擎上已经有别的任务）最多等多少秒。等待前会打印**引擎自己说的状态**（`end` / `startTimestamp` / `message`），等不到就打印清理办法并**停下整批** —— 引擎同一时刻只收一个任务，接着跑只会得到一串被同一道门挡掉的空轮 |
| `--auto-file-targets` | 关 | 文件类故障的路径改用后端推荐的真实路径（`@auto:` 哨兵），见第 3.4 节。不加就按编排里写的路径跑 |
| `--keep-run-on-interrupt` | 关 | Ctrl+C / `kill` 时**不**停引擎上本轮的压测任务。默认会停（见第 8 节） |

## 7. 已知坑（脚本会直接把证据和解法打出来）

### 7.1 引擎说「执行中」，但确实没有任务在跑

引擎放行新任务只看工作目录下的临时状态文件 `backend/resbenchmark/tmp/job.json`：
每次 `GET /web/bench/status` 都会重读它，文件里写着 `curState: RUNNING` 就把「锁」重新装上
（`BenchMarkStatus.reset`：`end = (curState != RUNNING)`）。这个 RUNNING 是**残骸**——
一次跑到一半被中断的加载（例如引擎重启把服务器和子进程一起带走）不会留下终止态：
写终止态的两条路（子进程自己跑完、服务器侧的收尾）都没走到，引擎启动也不做任何对账。

清掉它**不用重启引擎**：

```bash
curl -s http://127.0.0.1:8080/web/bench/status   # 看 startTimestamp 是哪次起跑；message 为空=它认为还在跑
cat backend/resbenchmark/tmp/job.json
# {"curState":"RUNNING","startTimeStamp":"1790938926097","stopTimeStamp":"","success":false}
#        ↑ 改成 "MANUAL"（或 "GRACEFUL"），补一个 stopTimeStamp，success 保持 false
curl -s http://127.0.0.1:8080/web/bench/status   # end 变回 true 即放行
```

- 状态会显示成「任务已经被手工停止」（引擎目前只有 RUNNING / GRACEFUL / MANUAL 三个状态，
  没有「被中断」这个说法），不是真的被谁手工停过。
- `POST /web/bench/stop` **在这种情况下清不掉这个文件** —— 它只负责杀掉当前那个子进程；
  只有子进程真的存在并被杀，引擎 async 的收尾才会把终止态写进这个文件。残骸意味着
  已经没有子进程了，所以 stop 会返回成功、文件却照旧（真的在跑的任务不在这一列，见第 8 节）。
- 重启引擎也清不掉它（启动时不做对账）。
- 这个文件是临时的：下一次成功的提交会删掉整个 `tmp/`。

### 7.2 「应用数据库参数失败（不阻断压测）：已执行，但读回不一致」

读回是**真话**：`shared_buffers` / `max_connections` 是 postmaster 参数，
`ALTER SYSTEM` 只写了 `postgresql.auto.conf`，要重启才生效。所以这条的意思是
「值写进去了，但这个实例没有带着新值重新起来」。脚本会把 `restarted` / `waitedSec` /
下发的重启命令 / 差在哪两项一起打出来，并按 `restarted` 的真假给出最可能的原因：

| 引擎报的 | 脚本怎么解释 |
|---|---|
| `restarted=true`，读回还是旧值 | 重启命令打的可能不是引擎连的这个实例/容器；目标实例可能是带 `-c shared_buffers=…` 命令行参数启动的（docker 镜像 CMD 里常见），命令行优先级**高于** `auto.conf`；detach 写法下 `returncode=0` 不代表重启成功 |
| `restarted=false` | 重启这一次就没成功（agent 没起 / 命令返回非 0 / 等不到重连），原因在 message 与引擎日志里 |

`waitedSec` **不是**判据：引擎是命令返回之后才开始连的，库若在命令返回前就已经起来，
它同样是 0。想知道重启到底发生过没有，到库上比 `pg_postmaster_start_time()`
或 `pg_settings` 里该参数的 `source` 更准。重启命令本身怎么写，见 §3.1。

## 8. 中断（Ctrl+C / `kill`）时会怎样

**默认：把脚本自己刚提交的那一轮停掉**，而不是留它在引擎上继续跑几小时。
`kill <pid>`（SIGTERM）走同一条路径 —— 以前这条路是完全没人管的，进程会直接静默死掉。

收尾顺序是有意的，都是先本地、后网络：

1. 本轮**如实补写一行**进批次 CSV，`note` 注明被中断、归档不完整（先落盘，别被第二次
   Ctrl+C 吃掉）；
2. 关掉 CSV（已经 `fsync` 过的行不会丢）；
3. `POST /web/bench/stop`（body `[]`，和前端「停止」按钮同一条路）→ 引擎侧
   `destroyForcibly` 杀掉那个子 JVM；子进程一死，引擎 async 的收尾会把终止态写进
   `tmp/job.json`，**闸门一起释放**（下一次提交不会再撞「执行中」）；
4. 打印这次中断的代价；
5. 退出码 `130`，summary 里写「本批次提前停止：计划 N 轮，只跑到第 k 轮」。

**共用引擎的安全线**：引擎的进程句柄是**单例全局槽**，`stop` 杀的是"当前那一个"。
所以脚本动手前先拿引擎自己写的 `startTimestamp` 认领：

| 引擎状态 | 脚本 |
|---|---|
| `end=true` | 没有任务在跑 → 什么都不做 |
| `startTimestamp` ≥ 本次提交时刻 | 是我们那一轮 → 停 |
| `startTimestamp` 更早 | **是别人的任务 → 绝不动**，并把这句话打出来 |

因此：**等别人的任务时按 Ctrl+C，不会连累别人**（这也正是 `--busy-wait` 要保护的东西）。
想反过来「中断但保留本轮任务」，加 `--keep-run-on-interrupt`。

三点要知道的：

- **注入的故障不会被自动恢复**。这一轮引擎侧的「恢复」步骤不会执行；OS 级 chaosblade
  故障本身带 `duration`、会自己到期，DBChaos 类由被杀掉的进程驱动、随之中止。
  恢复命令全文在归档的 `<负载>_<日期>.config.xml` 里，真不放心就到库上按它清一遍。
- 这一轮的**归档不完整**（没有 `metric.json` 等），批次 CSV 里那一行不代表一次完整运行。
- **`kill -9` / 直接关终端覆盖不到**（脚本没机会收尾，引擎上的任务会留下、闸门被占）。
  手工停：

  ```bash
  curl -X POST http://127.0.0.1:8080/web/bench/stop \
       -H 'Content-Type: application/json' -d '[]'
  ```

  注意：这条命令在"**确实有子进程在跑**"时才既杀进程又写终止态；如果引擎上已经没有
  子进程（比如上次被重启打断留下的残骸），它会返回成功、但 `tmp/job.json` 照旧是
  `RUNNING` —— 那种情况按 §7.1 处理。