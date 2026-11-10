# 排查指南：监控无曲线 + 韧性指标全 0

> 现象：前端监控**曲线空白**，最终**韧性指标/分数全 0**。
> 结论先行：这两个现象**极可能是同一个根因** —— 这次运行的**结果目录不完整**（没有产出或没有归档 `summary.json / raw.csv / faults.csv / monitor.csv / metric.json`）。

---

## 一、先理解链路（三个环节，任一断掉都会同时表现为"无曲线 + 0 分"）

```
① 采集     Monitor 线程周期性写 tmp/monitor.csv
② 归档     DBWorkload 收尾阶段：tmp/monitor.csv → results/<historyName>/monitor.csv
                         tmp/faultInject.json → results/<historyName>/faultInject.json
                         tmp/job.json         → results/<historyName>/job.json
           └─ 然后【同步】调用 ResilienceMetricReport.py -r <结果目录>（会连跑两次）
③ 算分     脚本读 <结果目录> 下的 *summary.json / *raw.csv / *faults.csv / monitor.csv
           写 <结果目录>/metric.json、metric.csv，并写 sqlite metric.db
④ 取分     引擎 MetricUtils.getHistoryMetric(historyName) 读 results/<HN>/metric.json → 给前端
```

**关键**：`monitor.csv` 只喂曲线；**分数来自 `metric.json`**，而 `metric.json` 由 python 脚本产出，脚本又依赖 `*summary.json` / `*raw.csv` / `*faults.csv`。所以"曲线没数据"说明这次运行的结果目录多半**整体不完整**，分数自然也出不来。

---

## 二、无曲线对应的日志

| 日志 | 级别 | 含义 |
|---|---|---|
| `No monitor data available for historyName: <HN>` | **INFO** | `results/<HN>/monitor.csv` 与 `tmp/monitor.csv` 都不存在或都只有表头 → 接口返回空（HTTP 200，前端曲线空白） |
| `No monitor file found for output directory: <dir>` | WARN | 归档阶段（`DBWorkload`）两处都没有 monitor 文件 |
| `Error while copying monitor file into <path>` | ERROR | 归档拷贝失败（权限/磁盘等） |

> 注意回退逻辑：历史目录缺 monitor.csv 时，接口会**回退去读 `tmp/monitor.csv`**。所以有时会看到"有曲线但内容不对（其实是另一个 run 的数据）"。

---

## 三、分数全 0 对应的日志（**重点**）

### 3.1 引擎侧：缺数据时是"静默归零"，多半没有 ERROR

| 日志 | 级别 | 含义 |
|---|---|---|
| `No metric file found for test suite <suite>` | WARN | `results/<HN>/metric.json` **不存在** → 取分返回空串 → 前端 0 |
| `[SCORE_DEBUG] Exposing N fault scores for testSuite=<suite>` | INFO | 引擎**成功解析到 N 个故障的分数**（N=0 或没这行 → 解析不到） |
| `[TRIANGLE_DEBUG] Exposing N fault triangles ...` | INFO | 韧性三角解析成功 |
| `[TRIANGLE_DEBUG] No fault triangles found in metric.json ...` | WARN | metric.json 里没有三角数据 |

⚠️ **静默归零**：`MetricUtils` 在以下情况**不会打任何 ERROR**，直接给 0：
- `metric.json` 解析成**空数组** → 直接返回空串（连 WARN 都没有）
- 字段是 `null` / `NaN` / `Infinity` / 非数字 → 解析前把 `NaN|Infinity` 替换成 `0`，再经 `parseDoubleSafe` 变 `0.0`

⇒ **排查"分数 0"不能只看 ERROR 日志**，必须结合文件是否存在 + 上面这几行。

### 3.2 python 脚本报错会出现什么日志

脚本调用发生在 `DBWorkload` 收尾阶段（注释：*run metric report shell synchronously to ensure metric.json is ready*），**同一个命令会连续执行两次**（第二次为了确保跨故障依赖的目录就绪）。

**引擎日志里能看到（按出现顺序）：**

| 日志 | 级别 | 含义 |
|---|---|---|
| `Cannot find ResilienceMetricReport.py. user.dir=<dir>, outputDir=<dir>` | ERROR | **脚本没找到** → 引擎启动目录与脚本位置不匹配（`user.dir` 是启动引擎时所在目录） |
| `Failed to check python version: ...` | WARN | python 解释器不在 PATH |
| `Running <python> <script> -r <结果目录> (cwd=<脚本目录>)` | INFO | 即将执行 |
| `Full command: <python> <script> -r <结果目录>` | INFO | 完整命令 |
| `Python interpreter: Python 3.x.y` | INFO | 实际用的解释器版本 |
| `Python script stdout (first run): ...` | **INFO** | **脚本的正常/致命输出都在这里**（见下方 `[FATAL]`） |
| `Python script stderr (first run): ...` | **INFO** | **未捕获异常的 traceback 在这里**（见下方 `RuntimeError`） |
| `<cmd> first run failed. exitCode=N` | ERROR | 第一次退出码非 0 |
| `Python script stdout/stderr (second run): ...` | INFO | 第二次的输出 |
| `<cmd> second run failed. exitCode=N` | ERROR | 第二次也失败 |
| `<cmd> execution failed. exitCode=N stderr=...` | ERROR | 汇总失败信息（含 stderr） |
| `<cmd> execution also failed. exitCode=N stderr=...` | ERROR | **换解释器**（`python3``python`）重试后仍失败 |
| `<cmd> succeeded on retry.` | INFO | 换解释器后成功 |

**脚本内部打印的内容（会出现在上面 stdout/stderr 行里）：**

| 输出 | 出现位置 | 退出码 | 含义 |
|---|---|---|---|
| `RuntimeError: No summary.json found or empty summary under <dir>. This directory may be incomplete (only monitor/fault files).` | **stderr** | **1** | **结果目录不完整**（构造函数阶段就失败，最常见的"算不出分"原因） |
| `RuntimeError: Invalid summary schema in <summary>. Missing keys: [...]. Please point -r to a complete benchmark result directory.` | **stderr** | **1** | summary.json 缺关键字段 |
| `ModuleNotFoundError: No module named 'evaluation_manage'` / `'scipy'` / `'pandas'` | **stderr** | **1** | 依赖缺失（`evaluation_manage.py` 必须与脚本**同目录**） |
| `[FATAL] Resilience metric pipeline failed: <e>` + traceback | **stdout** | **仍是 0** | 评分流程内部异常（被 catch） |
| `[FAULT_ERROR] fault=<名字> failed: <e>` + traceback | **stdout** | 0 | **单个故障**评分失败，该故障被标 `NOT_EVALUATED`（并在故障目录写 `result.txt`），其他故障照常算 |
| `[SCORE_DUMP] fault=<名字> throughput=.. latency=.. stability=.. defence=.. recovery=.. rto=.. adaptability=.. volatility=.. metric_status=..` | stdout | 0 | **每个故障算完的分数快照 —— 有这行说明脚本算成功了** |
| `[TRIANGLE_DEBUG] ...` / `[FAULT_TRI_DEBUG] ...` | stdout | 0 | 三角/过程调试信息 |

### 3.3 ⚠️ 两个极易误判的点（务必注意）

1. **`INFO ... completed successfully.` 不能证明算分成功。**
   脚本把评分流程的异常 `catch` 住了（打印 `[FATAL]` 后继续），**退出码仍是 0**，Java 只看退出码 → 会打 "completed successfully"。**真正的错误在 `Python script stdout (...)` 那一行的 `[FATAL]` 里**，不要只搜 "failed"。

2. **`metric.json` 可能已生成，但脚本仍报 `[FATAL]`。**
   `save_metric` 的顺序是：先写 `metric.csv` → 再写 `metric.json` → **最后**写 sqlite `metric.db`（要取 `summary["DBMS Type"]`/`["Test Suite"]`，还要在**脚本所在目录**可写）。所以 sqlite 阶段报错时，`metric.json` 已经存在了 → 表现为"有文件但分数 0"。

---

## 四、排查步骤

### Step 1　先抓关键日志，一次看清

```bash
cd <引擎工作目录>/logs
LOG=resbenchmark.log

grep -n "No monitor data available for historyName"              $LOG
grep -n "No monitor file found for output directory"             $LOG
grep -n "No metric file found for test suite"                    $LOG
grep -n -E "SCORE_DEBUG|No fault triangles"                      $LOG
grep -n -E "Running .*ResilienceMetricReport|Python interpreter"  $LOG
grep -n -E "Python script (stdout|stderr)|run failed|execution .*failed|Cannot find ResilienceMetricReport" $LOG
grep -n -E "\[FATAL\]|\[FAULT_ERROR\]|\[SCORE_DUMP\]|Traceback|RuntimeError|ModuleNotFoundError" $LOG
```

判读：

| 看到什么 | 结论 |
|---|---|
| 有 `[SCORE_DUMP]` | 脚本**算完了**；若前端仍 0，问题在取分/聚合侧（看 `SCORE_DEBUG` 的 N） |
| 有 `SCORE_DEBUG Exposing N` 且 N>0 | 引擎**读到了 N 个故障分数**；前端仍 0 则往聚合/前端查 |
| 有 `No metric file found` | `metric.json` 不存在（算分没产出） |
| 完全**没有** `Python script stdout` 行 | **脚本根本没被调用** → 工作负载没跑到收尾阶段（进程被 kill/OOM/手动停止），或脚本没找到 |
| 有 `Cannot find ResilienceMetricReport.py` | 脚本路径问题 → 用**引擎启动目录**为基准找脚本 |
| `RuntimeError: No summary.json found ...` | **结果目录不完整** ← 与"无曲线"同源 |

### Step 2　到**引擎工作目录**看同一个历史目录到底有什么

```bash
cd <引擎工作目录>            # 代码里 results/、tmp/ 都是相对路径，换目录查会误判
HN=<historyName>
ls -l results/$HN/
ls -l results/$HN/metric.json results/$HN/metric.csv
ls -l results/$HN/*summary.json results/$HN/*raw.csv results/$HN/*faults.csv results/$HN/monitor.csv
ls -l tmp/monitor.csv
ls -l metric.db              # 注意：脚本 cwd = 脚本所在目录，metric.db 在那里
```

| 目录里有什么 | 结论 |
|---|---|
| 只有 `faultInject.json` + `job.json` | **这次运行没真正跑起来 / 极早退出** → 无曲线 + 0 分同一根因；查任务启动日志（DB 连接、注入失败、被立即停止） |
| 有 `*summary.json`/`*raw.csv`，但**没有** `metric.json` | 跑过但**算分没产出** → 回到 Step 1 看 python 相关日志 |
| 有 `metric.json` | 看内容（见下），确认是否空数组 / 字段为 null |
| `job.json` 里 `curState` 不是正常结束态 | 收尾流程没走完 → 归档和算分都不会执行 |

查看 metric.json 内容：

```bash
python3 - <<'PY'
import json
d = json.load(open("results/<HN>/metric.json"))
print("故障条数:", len(d))
print(json.dumps(d[:1], ensure_ascii=False, indent=2)[:1200])
PY
```
- `故障条数: 0` → 结果目录里没有 `*faults.csv`，脚本解析不到任何故障 → 前端自然全 0
- 字段为 `null` / `NaN` → 会被引擎**静默变成 0**

### Step 3　手工复跑脚本（最快的定位手段）

```bash
# ⚠️ 先备份结果目录！复跑会覆盖 metric.json / metric.csv / metric.db
cp -r results/<HN> /tmp/results_<HN>.bak

cd <ResilienceMetricReport.py 所在目录>     # cwd 必须是脚本目录（要写 metric.db、要 import evaluation_manage）
python3 --version
python3 ResilienceMetricReport.py -r <结果目录绝对路径> ; echo "exit=$?"
```

| 结果 | 结论 |
|---|---|
| `exit=1` + `RuntimeError: No summary.json found...` | 结果目录不完整（缺 summary.json）→ 回到 Step 2 看运行是否正常结束 |
| `exit=1` + `ModuleNotFoundError` | python 环境缺依赖（`evaluation_manage.py` 是否与脚本同目录？scipy/pandas/matplotlib 是否装了？） |
| `exit=1` + 其他 traceback | 按 traceback 定位 |
| `exit=0` 但 stdout 有 `[FATAL]` | 评分流程/sqlite 阶段异常（常见：`DBMS Type`/`Test Suite` 缺失、目录不可写） |
| `exit=0` 且有 `[SCORE_DUMP]` | 算分成功 → 问题在读取/展示侧 |

### Step 4　环境与权限检查

```bash
python3 -c "import scipy, pandas, matplotlib; print('deps ok')"
ls -l evaluation_manage.py                 # 必须与 ResilienceMetricReport.py 同目录
df -h . ; touch results/<HN>/.wtest && rm results/<HN>/.wtest   # 磁盘/可写
python3 -c "import sqlite3; sqlite3.connect('metric.db').close(); print('metric.db writable')"
```
- 脚本自带 `python3`↔`python` 重试，说明**解释器环境经常缺失**，务必确认哪台机、哪个解释器、装了哪些库。
- `pymser` 缺失**不是问题**（脚本用 try/except 包住了）。

### Step 5　判断"是不是压根没算"（时间线）

把日志里 `Running ... -r <结果目录>` 的**时间戳**与结果目录内文件的时间戳对比：
- 没有这行 → 收尾阶段**根本没执行**（运行被中断/进程被杀）
- 有这行但结果目录里没有新文件 → 脚本失败了（回到 Step 3）

---

## 五、专项：脚本报 `No summary.json found` + 结果目录只有 `job.json` / `faultInject.json`

### 5.1 这个报错是"结果"不是"原因"

`No summary.json found or empty summary under <dir>` 由 `load_summary()` 抛出，而它在 **`MetricReport.__init__`** 里被调用 —— 也就是脚本**刚启动就判定结果目录不完整**。

`summary.json` 是**工作负载产出**，由 `DBWorkload` 在工作负载**正常跑完、进入报告阶段**时才写：

```
String summaryFileName = baseFileName + ".summary.json";        // DBWorkload.java:1147
```

而 `job.json` / `faultInject.json` 是**任务状态类**文件，任务一结束就落盘（`writeFinalJobState` → 拷到 `results/<runId>/job.json`，`BenchmarkController.java:614-621`）。

> ⇒ **结果目录里只有 `job.json` + `faultInject.json`，说明"跑了，但工作负载没跑完"**（在报告阶段之前就结束了），所以没有 `summary.json` / `raw.csv` / `monitor.csv`。**无曲线、脚本报 No summary.json、分数 0，是同一个根因。**

### 5.2 `job.json` 直接告诉你成败

```json
{"curState":"GRACEFUL","startTimeStamp":"1788423908804","stopTimeStamp":"1788424290049","success":false}
```

| 字段 | 含义 |
|---|---|
| `success` | **工作负载 JVM 的退出码是否为 0**（`CommandExecutor.isSuccess()` 即 `exitCode == 0`，`BenchmarkController.java:519-524`）。手工停止也会是 `false` |
| `curState` | `MANUAL` = 手工停止；`GRACEFUL` = 正常结束流程（注意：正常/异常都可能是 GRACEFUL） |
| `stopTimeStamp` | **拿这个时间去日志里对时** |

⚠️ **`job.json` 不保存失败原因**（`message` 没写进去），原因只在日志里。

### 5.2.1 `success=false` 的全部来源

`success` 的语义只有一个：**工作负载 JVM 的退出码是否为 0**（`CommandExecutor.isSuccess()` → `exitCode == 0`）。全部赋值点：

| # | 来源 | 代码位置 | 说明 |
|---|---|---|---|
| 1 | **默认值（陷阱）** | `JobState()` 构造：`isSuccess=false; curState=RUNNING; stopTimeStamp=""`；任务启动时 `new JobState(RUNNING, start, false)` 写入 **tmp/job.json** | 注释写明 "even if the job crashes" —— 这是**初始占位**，不代表失败判定 |
| 2 | **工作负载退出码非 0** | `BenchmarkController:517`（先置 false）→ `:520-525`（按 exitCode 判定） | **主要的"真失败"** |
| 3 | **手工停止** | `BenchmarkController:559-565`，`/bench/stop` → `destroyForcibly()`（SIGKILL） | `success=false` + `message="任务已被手工停止"` → `curState` 最终为 `MANUAL` |
| 4 | **attach 载入 agent 失败** | `BenchmarkController:504-509`（`ac=attach` 分支，不跑工作负载） | `success=false` + `message=异常信息` |

非 0 退出码的典型含义：

| exitCode | 含义 |
|---|---|
| 1 | 未捕获异常 / `System.exit(1)` |
| 134 | JVM 崩溃（SIGABRT） |
| **137** | **SIGKILL（OOM killer、`kill -9`、手工停止的 `destroyForcibly`）—— 最常见** |
| 143 | SIGTERM（`kill`、容器停止） |
| -1 | 超时或执行异常（**工作负载用 `-1` = 无超时**，不会因超时被杀） |

⚠️ **`curState` 不能判断成败**：`writeFinalJobState` 里 `finalState = result.isSuccess() ? GRACEFUL : GRACEFUL` —— **两个分支都是 `GRACEFUL`**，所以即使失败也是 `GRACEFUL`（只有 message 含"手工停止/stopped"才会是 `MANUAL`）。**只有 `success` 字段能区分成败。**

判定表：

| 位置 | curState | stopTimeStamp | success | 结论 |
|---|---|---|---|---|
| `tmp/job.json` | RUNNING | **空** | false | 任务在跑（初值），**不是失败** |
| `results/<HN>/job.json` | GRACEFUL | 有值 | **false** | **工作负载退出码非 0（真失败）** |
| `results/<HN>/job.json` | MANUAL | 有值 | false | 手工停止 |
| `results/<HN>/job.json` | GRACEFUL | **空** | false | ⚠️ 这是**工作负载报告阶段抄的初始值**（`DBWorkload:1264-1266`），**不反映真实成败** → 以 `tmp/job.json` 或日志为准（这种目录一定有 summary.json） |
| 任意 | 任意 | 任意 | true | 工作负载正常退出 |

### 5.2.2 ⚠️ 失败原因的记录缺口

- 引擎**不记录退出码**，只 `LOG.error(commandResult.getStderr())`（`BenchmarkController:528`）。
- `message`（= 工作负载 stderr）只存在**内存**里（`/bench/status` 可取），**引擎重启即失**；`job.json` 里也不存 message。
- **进程被 SIGKILL 时 stderr 是空的** → 引擎日志里可能只有一条空 ERROR，**什么都看不出来** → 必须看：
  - 工作负载自己的日志：`logs/resbenchmark_job.log`
  - OS 层：`dmesg -T | grep -i -E "oom|killed process"`、`journalctl -k`、退出码 137/143

### 5.3 三步定位真正的失败原因

```bash
cd <引擎工作目录>

# ① 失败标志与时刻
cat results/<HN>/job.json

# ② 引擎日志：工作负载 stderr（finally 里 LOG.error(commandResult.getStderr())，BenchmarkController:528）
grep -n -A5 -B5 "ERROR" logs/resbenchmark.log | tail -40

# ③ 工作负载自己的日志（工作负载是独立 JVM：java -jar ./resbenchmark.jar job …，日志见 logback-job.xml）
tail -100 logs/resbenchmark_job.log

# ④ 关键判据：有没有走进报告阶段
grep -an -E "Output data loss|Running .*ResilienceMetricReport|Benchmark completed" logs/resbenchmark_job.log
```

| 现象 | 结论 |
|---|---|
| `job.json` 有 `success` 字段、但 `resbenchmark_job.log` 里**没有** `Benchmark completed` / `Running ... ResilienceMetricReport` | **工作负载在报告阶段前就结束了** → 无 summary.json（客户的情形） |
| `resbenchmark_job.log` 在某个时刻**直接断掉、没有异常堆栈** | **进程被外部终止或崩溃**（OOM / 被 kill）→ 到 OS 层查：`dmesg -T \| grep -i -E "oom\|killed process"`、`journalctl`、退出码 137/143 |
| 日志里有 `SQLException` / `too many clients` / 连接相关异常 | 故障把连接占满，工作负载拿不到连接而退出 |
| 有 `OutOfMemoryError` | 内存不足（日志量/数据量过大） |
| `curState=MANUAL` | 被手工停止（`/bench/stop`） |

> ⚠️ `logs/resbenchmark_job.log` 是**跨多次运行累积**的，必须用时间戳区分是哪一次 run（用 `job.json.startTimeStamp`/`stopTimeStamp` 对齐）。

### 5.4 实证（本机复现同一现象）

| 历史目录 | 文件 | job.json | 说明 |
|---|---|---|---|
| `smallbank_20260903_145911` | **18 个**（summary.json/raw.csv/monitor.csv/… 齐全） | `success:true` | 跑完了，但**没有 metric.json**（见 5.5） |
| `smallbank_20260903_162508` | **2 个**（job.json + faultInject.json） | **`success:false`** | 16:25:09 启动 → 日志 16:31:03 **直接断掉、无异常堆栈** → 没进报告阶段 |
| `smallbank_20260903_143623` | **1 个**（仅 faultInject.json） | 无 | 更早就结束了 |

- 16:25 那次 run：4 个故障（连接风暴/连接池耗尽/连接线程饱和/海量事务回滚）都**注入成功且 ENFORCED**，但日志在 `Elapsed 68112ms, it's too long for SpecialWorkRunner interval=1000`（**卡顿 68 秒**）之后不久就断了 → 典型的"连接被占用导致卡死 → 进程被终止"。
- 报告阶段的日志行全部出现在 **15:17**（属于 14:59 那次成功 run），16:25 那次**一行都没有** → 结论明确。

### 5.5 ⚠️ 第二个独立问题：python 依赖缺失（本机正在发生）

即使是**成功**的那次 run（`success:true`、summary.json 齐全），算分依然失败：

```
ModuleNotFoundError: No module named 'sklearn'
  File ".../ResilienceMetricReport.py", line 14, in <module>
    from evaluation_manage import integrated_change_point_detection
  File ".../evaluation_manage.py", line 4, in <module>
    from sklearn.cluster import DBSCAN
```

- 这是 **import 阶段**就死（在 `main()` 之前）→ 退出码 1 → 引擎**重跑第二次**、再**换 `python` 解释器**重试，三次全部同样失败 → 不产出 `metric.json`。
- 佐证：`results/smallbank_20260903_145911/`（summary.json 齐全）**没有 metric.json**；全库中只有 **8/24–8/27** 的历史有 `metric.json`，9 月之后**全部没有** → 该机器的 python 环境已退化。
- **这个错误与"没有曲线"无关，运行成功也照样 0 分**，必须单独解决。

依赖自检（`evaluation_manage.py` 头部需要全部）：

```bash
for m in numpy scipy pandas matplotlib statsmodels ruptures sklearn; do
  python3 -c "import $m" 2>/dev/null && echo "OK   $m" || echo "MISS $m"; done
```

本机实测：`numpy/scipy/pandas/matplotlib` OK；**`statsmodels`、`ruptures`、`sklearn` 缺失**（`pymser` 缺失无害，脚本已 try/except 包住）。修复：`pip3 install scikit-learn statsmodels ruptures`。

### 5.6 ⚠️ 还有一种可能：`-r` 参数传错

报错文案里的路径是 `under results/`。**如果 `-r` 传的是 `results/`（结果根目录）而不是 `results/<historyName>`，那必然找不到 summary.json** —— 它只存在于历史子目录里。

正常流程由引擎自动传入**绝对路径的历史目录**（实证日志）：

```
Running python3 .../ResilienceMetricReport.py -r /home/morning/resbench/backend/resbenchmark/results/smallbank_20260903_145911
```

所以要区分：**客户是手工执行脚本（很可能 `-r` 传错）**，还是**引擎自动调用**（那就是 5.3 的运行没跑完）。

---

## 六、结论与一句话回复客户

> "**没有曲线**"和"**分数全 0**"很可能是**同一个根因**：这次运行的结果目录不完整，或跑完后归档/算分没有执行成功。
> 请提供三样东西：① 引擎日志里的 `historyName`；② 该次运行的 `results/<historyName>/` **文件清单**；③ 日志中 `Python script stdout/stderr` 与 `[FATAL] / [FAULT_ERROR] / SCORE_DEBUG` 相关行。
> 有这三样即可判定属于哪一类：**运行没跑起来** / **跑完但归档失败** / **归档成功但算分失败** / **算分成功但读取侧为 0**。

> 排查时特别注意：引擎侧对"缺数据"大量采用**静默归零**（返回空串或写 0，且不打 ERROR），**只看 ERROR 日志会漏判**。

> **针对"结果目录只有 job.json + faultInject.json"的具体回复**：这次运行没有跑完（`job.json` 的 `success:false`），工作负载在写 `summary.json` 的报告阶段之前就结束了，所以既没有监控曲线，也没有算分输入，脚本报 `No summary.json found` 是必然结果、不是原因。请按顺序提供：① `job.json` 全文；② `logs/resbenchmark_job.log` 中断处前后 100 行（用 `stopTimeStamp` 对时）；③ `logs/resbenchmark.log` 中该时刻的 ERROR 行。另外请**顺带核对 cpu 侧 python 依赖是否齐全**（`python3 -c "import sklearn, statsmodels, ruptures"`），即使运行成功，缺依赖也会导致 0 分。