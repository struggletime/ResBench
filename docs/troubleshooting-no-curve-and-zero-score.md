# Troubleshooting Guide: No Monitoring Curve + All Resilience Metrics Zero

> Symptom: the frontend monitoring **curve is blank**, and the final **resilience metrics/scores are all 0**.
> Conclusion up front: these two symptoms **very likely share the same root cause** — the **result directory for this run is incomplete** (no `summary.json / raw.csv / faults.csv / monitor.csv / metric.json` was produced or archived).

---

## 1. Understand the Pipeline First (three stages; if any one breaks, both "no curve + 0 score" appear)

```
① Collection     Monitor thread periodically writes tmp/monitor.csv
② Archiving      DBWorkload finalization stage: tmp/monitor.csv → results/<historyName>/monitor.csv
                         tmp/faultInject.json → results/<historyName>/faultInject.json
                         tmp/job.json         → results/<historyName>/job.json
           └─ then calls ResilienceMetricReport.py -r <result directory> synchronously (runs twice in a row)
③ Scoring        The script reads *summary.json / *raw.csv / *faults.csv / monitor.csv under <result directory>
           writes <result directory>/metric.json, metric.csv, and writes the sqlite metric.db
④ Retrieval      Engine MetricUtils.getHistoryMetric(historyName) reads results/<HN>/metric.json → sends to frontend
```

**Key point**: `monitor.csv` only feeds the curve; **scores come from `metric.json`**, and `metric.json` is produced by the Python script, which in turn depends on `*summary.json` / `*raw.csv` / `*faults.csv`. Therefore, "no curve data" means the result directory for this run is most likely **incomplete as a whole**, and scores naturally cannot be produced either.

---

## 2. Logs Corresponding to No Curve

| Log | Level | Meaning |
|---|---|---|
| `No monitor data available for historyName: <HN>` | **INFO** | Neither `results/<HN>/monitor.csv` nor `tmp/monitor.csv` exists, or both contain only headers → the API returns empty (HTTP 200, frontend curve blank) |
| `No monitor file found for output directory: <dir>` | WARN | No monitor file at either location during the archiving stage (`DBWorkload`) |
| `Error while copying monitor file into <path>` | ERROR | Archiving copy failed (permissions/disk, etc.) |

> Note the fallback logic: when the history directory lacks monitor.csv, the API **falls back to reading `tmp/monitor.csv`**. This is why you sometimes see "a curve, but with the wrong content (actually data from another run)".

---

## 3. Logs Corresponding to All-Zero Scores (**Key**)

### 3.1 Engine Side: Missing Data Is "Silently Zeroed"; There Is Usually No ERROR

| Log | Level | Meaning |
|---|---|---|
| `No metric file found for test suite <suite>` | WARN | `results/<HN>/metric.json` **does not exist** → score retrieval returns an empty string → frontend shows 0 |
| `[SCORE_DEBUG] Exposing N fault scores for testSuite=<suite>` | INFO | The engine **successfully parsed scores for N faults** (N=0 or this line missing → nothing parsed) |
| `[TRIANGLE_DEBUG] Exposing N fault triangles ...` | INFO | Resilience triangles parsed successfully |
| `[TRIANGLE_DEBUG] No fault triangles found in metric.json ...` | WARN | No triangle data in metric.json |

⚠️ **Silent zeroing**: in the following cases, `MetricUtils` **logs no ERROR at all** and directly returns 0:
- `metric.json` parses to an **empty array** → returns an empty string directly (not even a WARN)
- Fields are `null` / `NaN` / `Infinity` / non-numeric → before parsing, `NaN|Infinity` is replaced with `0`, which then becomes `0.0` via `parseDoubleSafe`

⇒ **When troubleshooting "score 0", do not look only at ERROR logs**; you must also check whether the files exist and the lines above.

### 3.2 What Logs Appear When the Python Script Reports Errors

The script is invoked during the `DBWorkload` finalization stage (comment: *run metric report shell synchronously to ensure metric.json is ready*), and **the same command is executed twice in a row** (the second time to ensure that directories for cross-fault dependencies are ready).

**Visible in the engine logs (in order of appearance):**

| Log | Level | Meaning |
|---|---|---|
| `Cannot find ResilienceMetricReport.py. user.dir=<dir>, outputDir=<dir>` | ERROR | **Script not found** → the engine startup directory does not match the script location (`user.dir` is the directory where the engine was started) |
| `Failed to check python version: ...` | WARN | The python interpreter is not on PATH |
| `Running <python> <script> -r <result directory> (cwd=<script directory>)` | INFO | About to execute |
| `Full command: <python> <script> -r <result directory>` | INFO | Full command |
| `Python interpreter: Python 3.x.y` | INFO | The interpreter version actually used |
| `Python script stdout (first run): ...` | **INFO** | **The script's normal/fatal output is here** (see `[FATAL]` below) |
| `Python script stderr (first run): ...` | **INFO** | **Tracebacks from uncaught exceptions are here** (see `RuntimeError` below) |
| `<cmd> first run failed. exitCode=N` | ERROR | The first run exited with a non-zero code |
| `Python script stdout/stderr (second run): ...` | INFO | Output of the second run |
| `<cmd> second run failed. exitCode=N` | ERROR | The second run also failed |
| `<cmd> execution failed. exitCode=N stderr=...` | ERROR | Aggregated failure information (including stderr) |
| `<cmd> execution also failed. exitCode=N stderr=...` | ERROR | Still failed after **switching interpreters** (`python3``python`) and retrying |
| `<cmd> succeeded on retry.` | INFO | Succeeded after switching interpreters |

**Content printed inside the script (appears in the stdout/stderr lines above):**

| Output | Location | Exit code | Meaning |
|---|---|---|---|
| `RuntimeError: No summary.json found or empty summary under <dir>. This directory may be incomplete (only monitor/fault files).` | **stderr** | **1** | **The result directory is incomplete** (fails at the constructor stage; the most common reason scores cannot be computed) |
| `RuntimeError: Invalid summary schema in <summary>. Missing keys: [...]. Please point -r to a complete benchmark result directory.` | **stderr** | **1** | summary.json is missing key fields |
| `ModuleNotFoundError: No module named 'evaluation_manage'` / `'scipy'` / `'pandas'` | **stderr** | **1** | Missing dependencies (`evaluation_manage.py` must be in the **same directory** as the script) |
| `[FATAL] Resilience metric pipeline failed: <e>` + traceback | **stdout** | **still 0** | Exception inside the scoring pipeline (caught) |
| `[FAULT_ERROR] fault=<name> failed: <e>` + traceback | **stdout** | 0 | Scoring failed for **a single fault**; that fault is marked `NOT_EVALUATED` (and `result.txt` is written in the fault directory), while other faults are computed as usual |
| `[SCORE_DUMP] fault=<name> throughput=.. latency=.. stability=.. defence=.. recovery=.. rto=.. adaptability=.. volatility=.. metric_status=..` | stdout | 0 | **Score snapshot after each fault is computed — the presence of this line means the script completed successfully** |
| `[TRIANGLE_DEBUG] ...` / `[FAULT_TRI_DEBUG] ...` | stdout | 0 | Triangle/process debug information |

### 3.3 ⚠️ Two Highly Misleading Points (Pay Close Attention)

1. **`INFO ... completed successfully.` does not prove that scoring succeeded.**
   The script catches exceptions from the scoring pipeline (prints `[FATAL]` and continues), and **the exit code remains 0**; Java only looks at the exit code → it prints "completed successfully". **The real error is in the `[FATAL]` on the `Python script stdout (...)` line**, so do not search only for "failed".

2. **`metric.json` may already exist even though the script still reports `[FATAL]`.**
   The order in `save_metric` is: write `metric.csv` first → then write `metric.json` → **finally** write the sqlite `metric.db` (which requires `summary["DBMS Type"]`/`["Test Suite"]` and a writable **script directory**). Therefore, when the sqlite stage fails, `metric.json` already exists → this appears as "the file exists but the score is 0".

---

## 4. Troubleshooting Steps

### Step 1  Capture the Key Logs First and See Everything at Once

```bash
cd <engine working directory>/logs
LOG=resbenchmark.log

grep -n "No monitor data available for historyName"              $LOG
grep -n "No monitor file found for output directory"             $LOG
grep -n "No metric file found for test suite"                    $LOG
grep -n -E "SCORE_DEBUG|No fault triangles"                      $LOG
grep -n -E "Running .*ResilienceMetricReport|Python interpreter"  $LOG
grep -n -E "Python script (stdout|stderr)|run failed|execution .*failed|Cannot find ResilienceMetricReport" $LOG
grep -n -E "\[FATAL\]|\[FAULT_ERROR\]|\[SCORE_DUMP\]|Traceback|RuntimeError|ModuleNotFoundError" $LOG
```

Interpretation:

| What you see | Conclusion |
|---|---|
| Has `[SCORE_DUMP]` | The script **finished computing**; if the frontend still shows 0, the problem is on the score retrieval/aggregation side (check N in `SCORE_DEBUG`) |
| Has `SCORE_DEBUG Exposing N` and N>0 | The engine **read scores for N faults**; if the frontend still shows 0, investigate aggregation/frontend |
| Has `No metric file found` | `metric.json` does not exist (scoring produced no output) |
| **No** `Python script stdout` line at all | **The script was never invoked** → the workload did not reach the finalization stage (process killed/OOM/manually stopped), or the script was not found |
| Has `Cannot find ResilienceMetricReport.py` | Script path problem → locate the script relative to the **engine startup directory** |
| `RuntimeError: No summary.json found ...` | **The result directory is incomplete** ← same source as "no curve" |

### Step 2  Go to the **Engine Working Directory** and See What the Same History Directory Actually Contains

```bash
cd <engine working directory>            # results/ and tmp/ are relative paths in the code; checking from another directory will mislead
HN=<historyName>
ls -l results/$HN/
ls -l results/$HN/metric.json results/$HN/metric.csv
ls -l results/$HN/*summary.json results/$HN/*raw.csv results/$HN/*faults.csv results/$HN/monitor.csv
ls -l tmp/monitor.csv
ls -l metric.db              # Note: the script cwd = the script's directory; metric.db is there
```

| What is in the directory | Conclusion |
|---|---|
| Only `faultInject.json` + `job.json` | **This run never really started / exited very early** → no curve and 0 score have the same root cause; check the task startup logs (DB connection, injection failure, stopped immediately) |
| Has `*summary.json`/`*raw.csv`, but **no** `metric.json` | It ran, but **scoring produced no output** → return to Step 1 and check the Python-related logs |
| Has `metric.json` | Inspect its content (see below) and confirm whether it is an empty array or has null fields |
| `curState` in `job.json` is not a normal termination state | The finalization process did not complete → neither archiving nor scoring is executed |

Inspect the content of metric.json:

```bash
python3 - <<'PY'
import json
d = json.load(open("results/<HN>/metric.json"))
print("fault count:", len(d))
print(json.dumps(d[:1], ensure_ascii=False, indent=2)[:1200])
PY
```
- `fault count: 0` → there is no `*faults.csv` in the result directory, so the script cannot parse any faults → the frontend naturally shows all 0
- Fields are `null` / `NaN` → the engine **silently turns them into 0**

### Step 3  Re-run the Script Manually (the Fastest Way to Pinpoint the Issue)

```bash
# ⚠️ Back up the result directory first! Re-running overwrites metric.json / metric.csv / metric.db
cp -r results/<HN> /tmp/results_<HN>.bak

cd <directory containing ResilienceMetricReport.py>     # cwd must be the script directory (metric.db must be written; evaluation_manage must be imported)
python3 --version
python3 ResilienceMetricReport.py -r <absolute path to result directory> ; echo "exit=$?"
```

| Result | Conclusion |
|---|---|
| `exit=1` + `RuntimeError: No summary.json found...` | The result directory is incomplete (missing summary.json) → return to Step 2 and check whether the run ended normally |
| `exit=1` + `ModuleNotFoundError` | The Python environment is missing dependencies (is `evaluation_manage.py` in the same directory as the script? Are scipy/pandas/matplotlib installed?) |
| `exit=1` + another traceback | Locate the issue based on the traceback |
| `exit=0` but stdout contains `[FATAL]` | Exception in the scoring pipeline/sqlite stage (common: missing `DBMS Type`/`Test Suite`, directory not writable) |
| `exit=0` and `[SCORE_DUMP]` is present | Scoring succeeded → the problem is on the read/display side |

### Step 4  Environment and Permission Checks

```bash
python3 -c "import scipy, pandas, matplotlib; print('deps ok')"
ls -l evaluation_manage.py                 # must be in the same directory as ResilienceMetricReport.py
df -h . ; touch results/<HN>/.wtest && rm results/<HN>/.wtest   # disk/writable
python3 -c "import sqlite3; sqlite3.connect('metric.db').close(); print('metric.db writable')"
```
- The script has a built-in `python3`↔`python` retry, indicating that **the interpreter environment is often missing**; be sure to confirm which machine, which interpreter, and which libraries are installed.
- Missing `pymser` **is not a problem** (the script wraps it in try/except).

### Step 5  Determine "Whether It Never Computed at All" (Timeline)

Compare the **timestamp** of the `Running ... -r <result directory>` line in the log with the timestamps of the files in the result directory:
- No such line → the finalization stage **never executed** (the run was interrupted/the process was killed)
- The line exists but there are no new files in the result directory → the script failed (return to Step 3)

---

## 5. Special Case: Script Reports `No summary.json found` + Result Directory Contains Only `job.json` / `faultInject.json`

### 5.1 This Error Is a "Result", Not a "Cause"

`No summary.json found or empty summary under <dir>` is thrown by `load_summary()`, which is called in **`MetricReport.__init__`** — that is, the script **determines that the result directory is incomplete right after startup**.

`summary.json` is a **workload output**, written by `DBWorkload` only when the workload **completes normally and enters the reporting stage**:

```
String summaryFileName = baseFileName + ".summary.json";        // DBWorkload.java:1147
```

`job.json` / `faultInject.json`, by contrast, are **task-state** files written as soon as the task ends (`writeFinalJobState` → copied to `results/<runId>/job.json`, `BenchmarkController.java:614-621`).

> ⇒ **If the result directory contains only `job.json` + `faultInject.json`, that means "it ran, but the workload did not finish"** (it ended before the reporting stage), so there is no `summary.json` / `raw.csv` / `monitor.csv`. **No curve, the script reporting No summary.json, and a score of 0 all share the same root cause.**

### 5.2 `job.json` Tells You Success or Failure Directly

```json
{"curState":"GRACEFUL","startTimeStamp":"1788423908804","stopTimeStamp":"1788424290049","success":false}
```

| Field | Meaning |
|---|---|
| `success` | **Whether the workload JVM's exit code was 0** (`CommandExecutor.isSuccess()`, i.e. `exitCode == 0`, `BenchmarkController.java:519-524`). It is also `false` when stopped manually |
| `curState` | `MANUAL` = manually stopped; `GRACEFUL` = normal termination flow (note: both normal and abnormal termination can be GRACEFUL) |
| `stopTimeStamp` | **Use this time to correlate with the logs** |

⚠️ **`job.json` does not store the failure reason** (`message` is not written into it); the reason exists only in the logs.

### 5.2.1 All Sources of `success=false`

The `success` field has only one meaning: **whether the workload JVM's exit code was 0** (`CommandExecutor.isSuccess()` → `exitCode == 0`). All assignment sites:

| # | Source | Code location | Description |
|---|---|---|---|
| 1 | **Default value (trap)** | `JobState()` constructor: `isSuccess=false; curState=RUNNING; stopTimeStamp=""`; when the task starts, `new JobState(RUNNING, start, false)` is written to **tmp/job.json** | The comment states "even if the job crashes" — this is an **initial placeholder**, not a failure determination |
| 2 | **Workload exit code non-zero** | `BenchmarkController:517` (set to false first) → `:520-525` (determined by exitCode) | **The main "real failure"** |
| 3 | **Manual stop** | `BenchmarkController:559-565`, `/bench/stop` → `destroyForcibly()` (SIGKILL) | `success=false` + `message="Task stopped manually"` → `curState` finally becomes `MANUAL` |
| 4 | **attach fails to load agent** | `BenchmarkController:504-509` (`ac=attach` branch; the workload is not run) | `success=false` + `message=exception information` |

Typical meanings of non-zero exit codes:

| exitCode | Meaning |
|---|---|
| 1 | Uncaught exception / `System.exit(1)` |
| 134 | JVM crash (SIGABRT) |
| **137** | **SIGKILL (OOM killer, `kill -9`, `destroyForcibly` from a manual stop) — most common** |
| 143 | SIGTERM (`kill`, container stop) |
| -1 | Timeout or execution exception (**the workload uses `-1` = no timeout**, so it is not killed due to timeout) |

⚠️ **`curState` cannot determine success or failure**: in `writeFinalJobState`, `finalState = result.isSuccess() ? GRACEFUL : GRACEFUL` — **both branches are `GRACEFUL`**, so even a failure is `GRACEFUL` (only when the message contains "manual stop/stopped" is it `MANUAL`). **Only the `success` field can distinguish success from failure.**

Decision table:

| Location | curState | stopTimeStamp | success | Conclusion |
|---|---|---|---|---|
| `tmp/job.json` | RUNNING | **empty** | false | The task is running (initial value); **this is not a failure** |
| `results/<HN>/job.json` | GRACEFUL | has a value | **false** | **Workload exit code non-zero (real failure)** |
| `results/<HN>/job.json` | MANUAL | has a value | false | Manually stopped |
| `results/<HN>/job.json` | GRACEFUL | **empty** | false | ⚠️ This is **the initial value copied during the workload reporting stage** (`DBWorkload:1264-1266`); **it does not reflect actual success or failure** → rely on `tmp/job.json` or the logs (such a directory always has summary.json) |
| Any | Any | Any | true | The workload exited normally |

### 5.2.2 ⚠️ Gap in Failure Reason Recording

- The engine **does not record the exit code**; it only calls `LOG.error(commandResult.getStderr())` (`BenchmarkController:528`).
- `message` (= workload stderr) exists only **in memory** (available via `/bench/status`) and **is lost when the engine restarts**; `job.json` does not store message either.
- **When a process is SIGKILLed, stderr is empty** → the engine log may contain only an empty ERROR, **revealing nothing** → you must check:
  - The workload's own log: `logs/resbenchmark_job.log`
  - OS level: `dmesg -T | grep -i -E "oom|killed process"`, `journalctl -k`, exit codes 137/143

### 5.3 Three Steps to Locate the Real Failure Cause

```bash
cd <engine working directory>

# ① Failure flag and time
cat results/<HN>/job.json

# ② Engine log: workload stderr (LOG.error(commandResult.getStderr()) in finally, BenchmarkController:528)
grep -n -A5 -B5 "ERROR" logs/resbenchmark.log | tail -40

# ③ The workload's own log (the workload is a separate JVM: java -jar ./resbenchmark.jar job …, logs configured by logback-job.xml)
tail -100 logs/resbenchmark_job.log

# ④ Key criterion: did it enter the reporting stage?
grep -an -E "Output data loss|Running .*ResilienceMetricReport|Benchmark completed" logs/resbenchmark_job.log
```

| Symptom | Conclusion |
|---|---|
| `job.json` has a `success` field, but `resbenchmark_job.log` does **not** contain `Benchmark completed` / `Running ... ResilienceMetricReport` | **The workload ended before the reporting stage** → no summary.json (the customer's case) |
| `resbenchmark_job.log` **cuts off abruptly at some point with no exception stack trace** | **The process was terminated externally or crashed** (OOM / killed) → check the OS level: `dmesg -T \| grep -i -E "oom\|killed process"`, `journalctl`, exit codes 137/143 |
| The log contains `SQLException` / `too many clients` / connection-related exceptions | Faults exhausted the connections; the workload could not obtain a connection and exited |
| Has `OutOfMemoryError` | Insufficient memory (log volume/data volume too large) |
| `curState=MANUAL` | Manually stopped (`/bench/stop`) |

> ⚠️ `logs/resbenchmark_job.log` **accumulates across multiple runs**, so you must use timestamps to distinguish which run it belongs to (align with `job.json.startTimeStamp`/`stopTimeStamp`).

### 5.4 Empirical Evidence (Reproducing the Same Symptom Locally)

| History directory | Files | job.json | Description |
|---|---|---|---|
| `smallbank_20260903_145911` | **18** (summary.json/raw.csv/monitor.csv/… complete) | `success:true` | It finished, but there is **no metric.json** (see 5.5) |
| `smallbank_20260903_162508` | **2** (job.json + faultInject.json) | **`success:false`** | Started at 16:25:09 → the log **cuts off abruptly at 16:31:03 with no exception stack trace** → never entered the reporting stage |
| `smallbank_20260903_143623` | **1** (faultInject.json only) | none | Ended even earlier |

- The 16:25 run: all 4 faults (connection storm/connection pool exhaustion/connection thread saturation/massive transaction rollback) were **injected successfully and ENFORCED**, but the log cut off shortly after `Elapsed 68112ms, it's too long for SpecialWorkRunner interval=1000` (**a 68-second stall**) → a classic "connections occupied, causing a hang → process terminated".
- All reporting-stage log lines appear at **15:17** (belonging to the successful 14:59 run); the 16:25 run has **none of them** → the conclusion is clear.

### 5.5 ⚠️ A Second Independent Problem: Missing Python Dependencies (Happening Locally Right Now)

Even for the **successful** run (`success:true`, complete summary.json), scoring still fails:

```
ModuleNotFoundError: No module named 'sklearn'
  File ".../ResilienceMetricReport.py", line 14, in <module>
    from evaluation_manage import integrated_change_point_detection
  File ".../evaluation_manage.py", line 4, in <module>
    from sklearn.cluster import DBSCAN
```

- It fails at the **import stage** (before `main()`) → exit code 1 → the engine **re-runs it a second time**, then **switches to the `python` interpreter** and retries; all three attempts fail the same way → no `metric.json` is produced.
- Corroboration: `results/smallbank_20260903_145911/` (complete summary.json) **has no metric.json**; across the whole repository, only the history from **8/24–8/27** has `metric.json`, and **none after September** → the Python environment on that machine has degraded.
- **This error is unrelated to "no curve"; even a successful run still gets a score of 0**, so it must be resolved separately.

Dependency self-check (all of these are required by the header of `evaluation_manage.py`):

```bash
for m in numpy scipy pandas matplotlib statsmodels ruptures sklearn; do
  python3 -c "import $m" 2>/dev/null && echo "OK   $m" || echo "MISS $m"; done
```

Local test results: `numpy/scipy/pandas/matplotlib` OK; **`statsmodels`, `ruptures`, and `sklearn` are missing** (missing `pymser` is harmless; the script wraps it in try/except). Fix: `pip3 install scikit-learn statsmodels ruptures`.

### 5.6 ⚠️ Another Possibility: Wrong `-r` Argument

The path in the error message is `under results/`. **If `-r` is passed `results/` (the result root directory) instead of `results/<historyName>`, summary.json will inevitably not be found** — it exists only in the history subdirectory.

In the normal flow, the engine passes the **absolute path of the history directory** automatically (empirical log):

```
Running python3 .../ResilienceMetricReport.py -r /home/morning/resbench/backend/resbenchmark/results/smallbank_20260903_145911
```

So distinguish between: **the customer running the script manually (very likely passing a wrong `-r`)**, and **the engine invoking it automatically** (in which case it is the run in 5.3 that did not finish).

---

## 6. Conclusion and a One-Sentence Reply to the Customer

> "**No curve**" and "**all scores are 0**" very likely share **the same root cause**: the result directory for this run is incomplete, or archiving/scoring did not execute successfully after the run finished.
> Please provide three things: ① the `historyName` from the engine logs; ② the **file list** of `results/<historyName>/` for that run; ③ the `Python script stdout/stderr` lines and the `[FATAL] / [FAULT_ERROR] / SCORE_DEBUG` related lines in the logs.
> With these three items, it can be determined which category applies: **the run never started** / **the run finished but archiving failed** / **archiving succeeded but scoring failed** / **scoring succeeded but the read side returns 0**.

> Pay special attention during troubleshooting: the engine side extensively uses **silent zeroing** for "missing data" (returns an empty string or writes 0, and logs no ERROR); **looking only at ERROR logs will lead to misjudgment**.

> **Specific reply for "the result directory contains only job.json + faultInject.json"**: this run did not finish (`success:false` in `job.json`); the workload ended before the reporting stage that writes `summary.json`, so there is neither a monitoring curve nor input for scoring. The script reporting `No summary.json found` is an inevitable result, not the cause. Please provide, in order: ① the full text of `job.json`; ② 100 lines before and after the interruption point in `logs/resbenchmark_job.log` (correlate using `stopTimeStamp`); ③ the ERROR lines at that time in `logs/resbenchmark.log`. In addition, please **also check whether the Python dependencies on the CPU side are complete** (`python3 -c "import sklearn, statsmodels, ruptures"`); even if the run succeeds, missing dependencies will also cause a score of 0.
