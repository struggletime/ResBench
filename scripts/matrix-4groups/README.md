# 四组矩阵：MySQL / PostgreSQL × TPC-C / smallbank

一次跑完 4 个组合 × 16 轮，产出 `docs/matrix-4groups-evidence.md` 里四张表的原始数据。

**一轮只跑一个故障，同一个故障在同一轮里连打 3 次**：编排里每个故障单独占一个
orchestration，里面连写 3 个完全相同的 batch（每批故障 60s + 批间间隔 30s）。
所以一组 = 16 轮 = 48 次注入；前 15 轮单轮负载 320s（60s 预置 + 3×90s + 20s 尾巴），
第 16 轮 `process.kill` 批间 90s、负载 560s；一组约 90 分钟负载，4 组约 6~8 小时
（smallbank 每轮还要重新建表灌数）。
一轮一个故障是为了让每轮的基线与监控会话只服务这一个故障：不会出现上一批的恢复期
盖到下一批基线上，也不会出现「3 次里混着别的故障的余波」。
强度统一拉到最高档（两处例外：MySQL 宿主机口径的 `memory.load` 必须保持 20%；
`connection.thread_saturation` 用溢出 300 而不是上限 434，理由见「已知约束」）。

**16 个故障的组成（每组相同）：**

| 类别 | 个数 | 故障 |
|---|---|---|
| CPU | 1 | 满载 `cpu.load` |
| 内存 | 1 | 内存占用 `memory.load` |
| 网络 | 5 | 丢包 `network.package`、延迟 `network.delay`、乱序 `network.unorder`、重复 `network.duplicate`、包损坏 `network.corrupt` |
| 磁盘 / 文件 | 3 | 磁盘写满 `disk.fill`、文件移走 `disk.filemove`、权限改 000 `disk.filechmod` |
| 进程 | 2 | 进程挂起 `process.stop`（SIGSTOP）+ 进程杀死 `process.kill`（SIGKILL，容器 PID 1 被杀后容器退出，恢复靠宿主机 `docker start`） |
| 数据库层 | 4 | 连接风暴 `connection.surge`、连接数耗尽 `connection.limit.exceeded`、大量事务回滚 `transaction.massive.rollback`、连接线程饱和 `connection.thread_saturation` |

不跑：`disk.fileremoved`（delete）、`disk.fileappend`，以及其余数据库层故障
（计划翻转 / 爆栈族 / 读放大 / 重复事务 / 死锁风暴 / MVCC 膨胀）。
`process.kill` 已加入（每组第 16 轮，最后一轮）：它会把容器连库一起停掉，恢复依赖
宿主机 `docker start` 与崩溃恢复，放中间会污染后续轮次基线。其余理由逐条写在
`docs/matrix-4groups-evidence.md` 文末。

## 文件

| 文件 | 作用 |
|---|---|
| `databases.yaml` | 两个库：pg :5433（容器 `pg_tpcc32`）、mysql :3306（宿主机 `mysqld.service`，不配 container） |
| `workload-pg-tpcc.yaml` | PG TPC-C，100 仓（与库内实际仓数一致，预检对上就不重建） |
| `workload-mysql-tpcc.yaml` | MySQL TPC-C，10 仓（与库内实际仓数一致） |
| `workload-smallbank.yaml` | smallbank，10 万账户 / 20 terminals，两个库共用 |
| `matrix-pg-tpcc.yaml` | PG × TPC-C，16 轮 × 每轮 3 次（table=stock，文件路径 @auto:） |
| `matrix-pg-smallbank.yaml` | PG × smallbank，16 轮 × 每轮 3 次（table=accounts，文件路径 @auto:） |
| `matrix-mysql-tpcc.yaml` | MySQL × TPC-C，16 轮 × 每轮 3 次（table=stock，显式 /var/lib/mysql 路径） |
| `matrix-mysql-smallbank.yaml` | MySQL × smallbank，16 轮 × 每轮 3 次（table=accounts，显式路径） |
| `run-all.sh` | 4 轮的总入口 |

## 前置

- 引擎 `http://127.0.0.1:8080`、res-manager `http://127.0.0.1:8090` 在跑；
- `databases.yaml` 里的口令是现场值，不要提交/外传；
- 数据库主机 `/` 只剩约 2.6 GiB —— 所以 `disk.fill` **不能**打 `/`：
  PG 打数据盘 `/var/lib/postgresql/data`（→100%），MySQL 打独立小分区 `/boot`（→100%）。

## 跑法

```bash
# 只看 4 条计划，不提交
scripts/matrix-4groups/run-all.sh --dry-run

# 正式跑（约 6~8 小时；4 组 × 16 轮，前 15 轮负载 320s、第 16 轮 560s）
scripts/matrix-4groups/run-all.sh

# 只跑 PG 侧 / 只跑 MySQL 侧
RUN_MYSQL=0 scripts/matrix-4groups/run-all.sh
RUN_PG=0 scripts/matrix-4groups/run-all.sh
```

等价的单条 python 命令（在仓库根目录执行，`$METRICS` 定义见下）：

```bash
# 1) pg × tpcc
python3 scripts/resbench_batch.py --databases scripts/matrix-4groups/databases.yaml \
  --workloads scripts/matrix-4groups/workload-pg-tpcc.yaml \
  --orchestration scripts/matrix-4groups/matrix-pg-tpcc.yaml --only-db pg \
  --auto-file-targets --auto-process-target \
  --out batch-results/4groups --monitor-metrics "$METRICS" --execute

# 2) pg × smallbank
python3 scripts/resbench_batch.py --databases scripts/matrix-4groups/databases.yaml \
  --workloads scripts/matrix-4groups/workload-smallbank.yaml \
  --orchestration scripts/matrix-4groups/matrix-pg-smallbank.yaml --only-db pg \
  --auto-file-targets --auto-process-target \
  --out batch-results/4groups --monitor-metrics "$METRICS" --execute

# 3) mysql × tpcc
python3 scripts/resbench_batch.py --databases scripts/matrix-4groups/databases.yaml \
  --workloads scripts/matrix-4groups/workload-mysql-tpcc.yaml \
  --orchestration scripts/matrix-4groups/matrix-mysql-tpcc.yaml --only-db mysql \
  --auto-process-target \
  --out batch-results/4groups --monitor-metrics "$METRICS" --execute

# 4) mysql × smallbank
python3 scripts/resbench_batch.py --databases scripts/matrix-4groups/databases.yaml \
  --workloads scripts/matrix-4groups/workload-smallbank.yaml \
  --orchestration scripts/matrix-4groups/matrix-mysql-smallbank.yaml --only-db mysql \
  --auto-process-target \
  --out batch-results/4groups --monitor-metrics "$METRICS" --execute
```

`$METRICS` 就是 `run-all.sh` 顶部那 32 个指标，复制这一段：

```bash
METRICS="container.cpu.cores,container.cpu.usage,container.memory.usage,container.disk.read,container.disk.write,host.cpu.usage,host.memory.usage,host.load.ratio,host.disk.read,host.disk.write,host.disk.usage,host.pressure.io,host.pressure.cpu,host.process.stopped,host.netstat.retrans,net.tcp.rto.rate,net.tcp.retrans.ratio,net.tcp.oo.rate,net.tcp.dsack.rate,db.tps.agent,db.connections.usage,db.connections.open_rate,db.sessions,db.threads.running,db.blocked.sessions,db.lock.wait.max,db.slow_sql.max,db.cache.hit,db.rollback.rate,ebpf.disk.avg,ebpf.disk.p99,ebpf.runq.avg"
```

## 进程挂起挂谁（`--auto-process-target`）

`process.stop` 的 `--process-cmd` 是 chaosblade 的**命令行子串匹配**，手写裸进程名很容易
挂到别的实例上。现场就有两对同名进程：

| 库 | 被测实例 | 同一台机器上的另一个实例 | 裸名字的后果 |
|---|---|---|---|
| PostgreSQL | 容器 `pg_tpcc32`，:5433 | 宿主 `/usr/bin/postgres -D /mnt/nvme4n1/pg_data -p 5432` | `postgres` 会同时命中两个库（还有 `docker exec … psql -U postgres` 这类会话） |
| MySQL | 宿主 `mysqld.service`，:3306 | 容器 `mysql8`（host 网络，:33060，命令行就是裸 `mysqld`） | `mysqld` 会把空闲实例一起 SIGSTOP |

所以默认开 `--auto-process-target`，在**提交前**做一次只读探测（复用数据库主机上的 agent
`:8000`，`databases.yaml` 可用 `agentPort` 覆盖）：

1. `ss -lntpH "sport = :<库端口>"` 找到**被测库的监听进程**（端口是唯一硬锚点）；
2. 取它的 `argv[0]` 当匹配串（PG → `postgres`，MySQL → `/usr/sbin/mysqld`）；
3. 核对命中范围：库在容器里 → 命中集合必须落在容器内，且宿主监听进程的 cgroup 必须
   属于该容器；库在宿主机 → 所有命中进程必须与监听进程**同 cgroup**。

任何一条不满足就**当场报错并停下**（不猜、不静默降级），错误里会列出命中到的每个实例。
dry-run 也会打印推荐结果，例如：

```
  ----  pg：process.stop 自动目标 = 'postgres'（容器 pg_tpcc32 内，探测时命中 7 个进程，实例 /docker/1eab…）
  ----  mysql：process.stop 自动目标 = '/usr/sbin/mysqld'（宿主机，探测时命中 1 个进程，实例 /system.slice/mysqld.service）
```

命中的进程数只是**提交那一刻**的快照：PG 的 backend 随压测连接增长，chaosblade 是在注入
那一刻重新按子串匹配的，所以真正被 SIGSTOP 的是当时全部匹配进程（postmaster + 各 backend
+ logger/checkpointer 等辅助进程），这正是「数据库进程挂起」该有的效果；MySQL 只有一个
mysqld 进程（线程不是进程），整库停摆。不开这个开关时，才用编排里手写的 `process-cmd`。

## 结果

每次调用建一个目录：`batch-results/4groups/<时间戳>_<库key>_<负载key>_<编排名>/`
（编排名 = 编排文件顶层的 `name:`），16 轮各占一个子目录：

```
runs-<时间戳>.csv                      ← 每轮一行，含 fault_inject / monitor_verify / monitor_evidence
<库key>__<负载key>__<轮key>/           ← 轮 key 形如 03-net-loss、11-process-stop
    <运行名>.json                      ← 引擎归档（含每批判定）
    <运行名>.faults.json               ← 注入命令与回执
    <运行名>.monitor.json              ← 监控指标序列（第二列/第三列的数据源）
```

想只补跑某一轮（比如上次乱序那轮没采到曲线），加 `--only-orchestration`：

```bash
--orchestration scripts/matrix-4groups/matrix-pg-smallbank.yaml \
  --only-orchestration 05-net-reorder        # 只跑这一轮（3 次注入）
```

## 已知约束

- **MySQL 的 3306 是宿主机 systemd 的 `mysqld.service`**（datadir `/var/lib/mysql`），
  不是 docker 里的 `mysql8`：`ss -lntp` 显示 `:3306` 属于 PID 3161 的
  `/usr/sbin/mysqld`，而 `mysql8`（host 网络，PID 92835）只监听 `:33060`。
  所以 MySQL 入口不配 container —— 否则 cAdvisor 会采到另一个实例的指标，
  chaosblade 也会被套进没装 blade 的容器。代价：MySQL 两轮没有 `container.*` 指标。
- **MySQL 的 `cpu.load` 是宿主机口径**：容器侧 fullload 只吃 32 核配额，宿主机侧
  会吃满 160 核 60 秒。这是这套部署上唯一无法限幅的故障，跑的时候宿主机其它负载
  会被拖慢。
- **`memory.load` 两侧强度不同，这是唯一没有按「最高」执行的地方**：PG 走容器口径
  `--mem-percent 90`（容器上限 40GB，基线已占 ~57%，贴近上限，postgres 有 OOM 风险）；
  MySQL 走宿主机，**保持 `--mem-percent 20`**（503GB 机器，alloc≈57GB，留 400GB
  余量）—— 90 会申请 ~400GB，整机 OOM，会连带拖死同机的 PG 容器与引擎。
- **`disk.fill` 两侧目标不同，都拉到 100%**：PG 打 `/var/lib/postgresql/data`
  （容器里唯一可用的真实数据盘，5.8T，→100%，写满会让 PG 的 WAL 写入失败、可能触发
  崩溃重启）；MySQL 打 `/boot`（1014MB 独立 xfs，29.3% → 100%）。容器内的 `/` 是整机 overlay 且已 100%，`/dev/shm`
  是 tmpfs（node_exporter 不采），都不能用。chaosblade 的 fill 是 fallocate，
  create 1~2 秒返回（2026-10-26 e07 归档 percent=95 实测 1.53s），不会撞引擎 10s 往返。
- **MySQL 的 process.stop 用 `/usr/sbin/mysqld`**：chaosblade 的 `--process-cmd`
  按命令行子串匹配，带完整路径只命中宿主服务；写 `mysqld` 会把 `mysql8` 里那个
  空闲实例（:33060）也一起 SIGSTOP。PG 侧仍用 `postgres`（命令套 docker exec）。
  这两个值是 `--auto-process-target` 探测出来的结果，编排里写的那份只作为不开开关时的口径。
- **网络 5 个故障都打在宿主机 `ens17f0` 上**（含 agent 回传通道），全部拉到最高可用档：
  loss 80%、delay 1000ms、reorder 50%/correlation 90、duplicate 90%、corrupt 90%。
  * 丢包不用 100%：80% 已经把控制面一起打瘫（e03 归档里第 3 批直接 10s 超时），
    100% 连注入回执都回不来，整批不可验证。
  * 乱序不用 90%+：仓库实测 reorder 命中率 100% 时反而没有真乱序（2000/2000 命中、
    delay 0.06ms、乱序 0），30–50% 才产生真乱序，50% 是仍能出真乱序的最高档。
- **`connection.thread_saturation` 取溢出 300，不是上限 434**：PG/MySQL 都没有
  `thread_pool_attr`，DBChaos 按 64 的基线算 `min(64 + overflow, max_connections - 2)`，
  300 → 364 个常驻会话（≈ max_connections 500 的 73%）；434 → 498/500，
  连 check 自己的连接、引擎重连都可能被挤掉。2026-09-03 已有连接类故障把工作负载
  打死（卡顿 68 秒后进程终止）的实证，见 `docs/troubleshooting-no-curve-and-zero-score.md`。
  判据：MySQL 看「运行中线程数」↑（注入会话状态是 Query 不是 Sleep）；PG 侧 `db.*`
  是 `og_*` 口径、DSN 不在被测实例上，**没有可用曲线，判定会是 NO_DATA**。
- **`host.process.stopped` 的分辨率约 30s、滞后 40~60s**，所以 process.stop 的
  持续时间定 60s；窗口太短会采不到 T 状态点。
- **文件类故障的可见影响 MySQL 比 PG 温和**：InnoDB 一直握着已打开 `.ibd` 的 fd，
  move/chmod 主要影响新开文件/重新打开表。判据以注入回执 + 慢 SQL/TPS 合看。
- **PG 文件路径必须带 `--auto-file-targets`**：smallbank 每轮重建表，relfilenode 会变，
  只能开跑时现查；MySQL 反过来不能用 @auto（db-dir → datadir 是搬进自己的父目录）。
- 脚本撞上引擎「执行中」会等，等不到就停本批次，不会去停别人的任务；`run-all.sh`
  不会调用 `POST /web/bench/stop`。
