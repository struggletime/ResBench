# Batch Test Script Usage Guide (resbench_batch.py)

## 1. What It Does

Run an entire large batch of combination stress tests with a single script, **without starting the frontend at all**.

- **One invocation = one batch**: take the **Cartesian product** of `databases × background workloads × fault orchestrations` and run it **serially** to completion.
- Each round automatically: start database / create tables and load data → inject faults per the orchestration → wait for both the workload and the faults to finish → archive → move to the next round.
- Calls only the engine's own APIs (`resbenchmark`, default `:8080`); does not depend on frontend code or builds.
- **The submitted parameters are the same as the frontend form, with only one extra `testName`** — the frontend form does not fill it in,
  so the script fills in the combination name itself (`<db key>__<workload key>__<orchestration key>`), so that each round's result directory carries its own environment information; see Section 5.
  (`containerName` is also a top-level field, but **the frontend "Database Configuration" already has a corresponding "Container Name" input box**,
  and on the script side this is `databases[].container`, see Section 3.1.)
- **Ctrl+C / `kill` stops the round that the script itself just submitted**, without leaving it running on the engine;
  if the task on the engine belongs to someone else, the script recognizes it and will not touch it. See Section 8.

## 2. Quick Start

```bash
# ① Show only the plan first and submit no tasks (dry-run is the default)
python3 scripts/resbench_batch.py \
  --databases scripts/batch-example/databases.yaml \
  --workloads scripts/batch-example/workloads.yaml \
  --orchestration scripts/batch-example/orch/combo-conn-planflip.yaml \
  --orchestration scripts/batch-example/orch/serial-os-faults.yaml

# ② Once the plan is confirmed, add --execute to actually start
python3 scripts/resbench_batch.py ...same as above... --execute
```

dry-run prints each round's database/workload/orchestration, the injection timetable for each fault batch, and the computed workload duration.

## 3. The Three Configuration Files

The three files are written **separately**, all in YAML format. `key` is not sent to the backend; it is used for only two things:
internal cross-referencing within the script, and **composing the result directory name** (`<db key>__<workload key>__<orchestration key>`, see Section 5).

### 3.1 Database Configuration `databases.yaml` — One File Can Define Multiple Databases

```yaml
databases:
  - key: og-local
    dbType: opengauss          # opengauss | mysql | postgresql | oceanbase | gaussdb
    deploymentType: standalone # standalone | distributed
    standalone:
      ip: YOUR_DB_HOST
      port: "5434"
      username: YOUR_USER
      password: <password>
    # Optional: equivalent to the frontend "Apply Configuration" button; change the database's buffer pool / max connections before running the benchmark
    dbParams:
      bufferPool: 2GB
      maxConnections: "500"
      restartCommand: "nohup gs_ctl restart -D /gaussdb/data >/tmp/x.log 2>&1 &"
    # Optional: when the database runs in a Docker container, fill in the container name → chaosblade-type faults are injected inside the container
    # (cpu/memory/disk/file/process). Network faults are the exception and still target the host.
    # Prerequisites: chaosblade is installed in the container, the agent's OS user is in the docker group, and the container name resolves on the agent host.
    container: opengauss-db
```

- `dbParams` is applied **once before this database's first round**; subsequent rounds reuse it and do not restart again.
- Only standalone `opengauss / postgresql / mysql` are supported; og/pg require a restart to take effect, so `restartCommand` must be written.
- If `container` is left empty, no `docker exec` wrapper is used and faults target the host (exactly as before),
  and the `containerName` key also **does not appear** in the request body.
  For detailed applicable fault types and prerequisites, see `docs/automation-matrix.md` §9.
- For databases configured with a container, **`restartCommand` must be written in docker form yourself** — the backend does not compose
  systemd/docker/gs_ctl for you (it only forwards this command verbatim to the agent):

  ```yaml
      container: pg-docker
      dbParams:
        bufferPool: 2GB
        maxConnections: "500"
        # The command runs on the host without a docker exec wrapper, so use docker restart directly;
        # su - <user> -c merely borrows that user's docker group permission (not needed if the agent is already in the docker group).
        # Foreground (no &) and no redirection are recommended: see the two points below.
        restartCommand: "su - YOUR_USER -c \"docker restart pg-docker\""
  ```

- **How the restart command is written involves trade-offs** (all in the engine's implementation):
  running in the **foreground** means the container is already up when the command returns, so the engine's subsequent "reconnect succeeded" is meaningful; the cost is that it must be fast
  — the agent's limit for waiting on a single command is 30 seconds. **Detach** (`nohup … &`) does not hit this limit,
  but the engine connects **immediately** after the command returns, and the database is often not down yet at that point, so "reconnect succeeded" is fake;
  if you use detach, you must verify on the database yourself with `SELECT pg_postmaster_start_time();`.
  Also **do not redirect the output away**: the agent's stdout/stderr are captured by the engine into `restartOutput` (visible in the frontend),
  so `> /tmp/x.log 2>&1` hides the only error message in a file on the database host.
- **When applying reports "executed, but read-back mismatch"**, the script then prints the evidence (`restarted` / `waitedSec` /
  the issued restart command / exactly which two items differ) and gives the most likely cause. The most common one is:
  the container was started **with command-line arguments** (such as `-c shared_buffers=12GB` in the image CMD),
  and command-line priority is **higher** than `postgresql.auto.conf` written by `ALTER SYSTEM` — in this case you can only change the container's startup arguments.
  Note that `waitedSec` is **not** a criterion: the engine only starts connecting after the command returns, so if the database had already started before the command returned,
  it is likewise 0. **The benchmark is not blocked**: subsequent rounds still run when the parameters did not take effect, except that this round's database parameters are not the ones you configured.

- On containerized databases, disk fault paths (`disk.fill.path` / `disk.filemove.src-path` /
  `disk.fileremoved.file-path` / `disk.filechmod.filepath`) are resolved as **in-container** paths
  — fill in the in-container path read from `SHOW data_directory`, not the host mount path.
- The container name is recorded in `run-meta.json` as `database.containerName`; there is also a `container` column in the CSV,
  and the dry-run round list also marks it as `[container <name>]`, so you can tell afterwards where each round ran.

### 3.2 Background Workload Configuration `workloads.yaml` — One File Can Define Multiple Workloads

```yaml
workloads:
  - key: sm-600            # Participates in the result directory name: <db key>__<workload key>__<orchestration key>
    name: smallbank        # smallbank | tpcc
    params:
      accountsNum: "1000"
      terminals: "20"
      duration: "600"      # Optional; only takes effect for rounds without faults, see Section 4
      amalgamateWeight: "15"
      balanceWeight: "15"
      depositCheckingWeight: "15"
      sendPaymentWeight: "25"
      transactSavingsWeight: "15"
      writeCheckWeight: "15"
```

tpcc's `params` are `warehouses / concurrency / duration / reload`.
`reload: true` = create tables + load data. Building warehouses is expensive (measured at **4h08m** for 1000 warehouses in this environment), and whether the engine builds warehouses
depends only on `runOptions.reload` and **never asks how many warehouses already exist in the database** — so before the **first round** of each (database + this workload),
the script first queries the actual warehouse count in the database (`POST /web/bench/tpcc/warehouse/verify`):

| What is queried | What the script does |
|---|---|
| Warehouse count = configuration | Run it directly as a normal test without building warehouses; subsequent rounds also skip building — saving this warehouse build |
| Warehouse count ≠ configuration (not built / too many / too few) | **First run a separate round to build warehouses** (warm-up round: no faults, `reload=true`), then **wait for it to finish** and run the formal round (the wait limit is `--build-timeout`); all subsequent rounds for this pair use `reload=false` |
| Cannot be determined (cannot connect to database / no permission / timeout / engine is an old build) | **Do not guess**; execute according to the `reload` configured here |

Two points to note:

- The criterion **looks only at the warehouse count, not at districts**. Therefore "warehouse count matches but district row count is insufficient" (previous load interrupted,
  data incomplete) counts as **passing** and is not rebuilt — the script prints a conspicuous `!` warning with the actual/expected district values,
  and this round's metrics should be read as "data may be incomplete".
- "Cannot be determined" is not the same as "warehouse count mismatch": the count is **unknown** rather than **different**, so in this case the script will not trigger a
  multi-hour warehouse build out of thin air, but falls back to the `reload` configuration. In the example, tpcc entries all say `reload: true`, meaning in this situation
  it **will still build warehouses**, except that the script explicitly states "could not query, executing per configuration".

For each (database + workload), the query happens **only once in the first round**; subsequent rounds do not query again and do not re-read the YAML.

### 3.3 Fault Orchestration `orch/*.yaml` — One File Can Hold **Multiple** Orchestrations

One file can contain multiple orchestrations, each with its own `key` (used to distinguish results across rounds):

```yaml
name: combo-demo              # Optional: the overall orchestration key for this file, used only for naming the batch directory

orchestrations:
  - key: combo-conn-planflip    # The name of this orchestration; participates in the result directory name

    batches:
      - duration: "60"          # Optional: uniformly override the duration of all faults in this batch to 60s
        faults:                 # Within the same faults list → inject simultaneously (combined faults)
          - nameKey: faultinject.fault.connection.surge
            params: { concurrent-connections: "200" }
          - nameKey: faultinject.fault.sql.plan_flip
            params: { threads: "16" }

      - faults:                 # Different batches → serial; start 30s after the previous batch ends
          - nameKey: faultinject.fault.transaction.massive.rollback
            params: { threads: "16", rollback-rate: "80" }

  - key: baseline               # The second orchestration in the same file; runs the next round
    batches: []                 # Empty list = inject no faults in this round; run only the workload
```

The top level also supports two other forms with the same effect; **old files do not need to be changed**:

| Top-level form | Meaning |
|---|---|
| `orchestrations: [...]` | Multiple orchestrations in one file; **each item must have a `key`** |
| `key:` + `batches:` | Single-orchestration file (i.e. the one-item form above) |
| A bare batch list | Single-orchestration file; `key` is taken from the file name |

**The top-level `name:` (optional; can be added to all three forms)** is the overall orchestration key for this **file**, used only to name the batch directory
for this invocation (see Section 5). If omitted, it falls back to the file name (`e1-orch.yaml` → `e1-orch`).
**It is distinct from each orchestration's own `key`** — each round's result directory name and `--only-orchestration`
still use only `key`; adding `name:` does not affect them.

- `key` must be unique **across all files and all orchestrations in one invocation** — the result directory name and
  `--only-orchestration` both rely on it for distinction.
- **Faults within the same batch are injected simultaneously; batches run serially**. To run faults one at a time serially,
  put only one fault in each batch; to inject the same fault serially N times, write N batches.
- **Writing `batches:` as an empty list = inject no faults in this round**, usable as a baseline/control group.
  Note that in this case the workload duration is determined entirely by `duration` in `workloads.yaml` (there is no fault plan to back it up,
  see Section 4), so to be comparable with other rounds you must write enough explicitly.

`nameKey` is taken from the engine's fault catalog:

```bash
curl -s http://127.0.0.1:8080/web/faults/catalog | jq '.[].nameKey'
```

> ⚠ The 18 DBChaos faults in the catalog **run even without writing duration** (using catalog defaults).
> But OS-level chaosblade faults (`cpu.load` / `network.package` / `disk.fill` / `process.*` …) **are not in the catalog**,
> and `params.duration` must be written explicitly; otherwise the script errors out directly at startup.

### 3.4 Paths for File Faults: Let the Backend Recommend (`--auto-file-targets`)

Those `base/12994` / `141113` values in `scripts/batch-example/file-orch.yaml` are **hand-copied OIDs**;
reloading the data once changes them all (the file header says "re-fetch the OIDs after reloading data").
Adding `--auto-file-targets` saves you from copying them — path parameters are replaced with `@auto:` sentinels,
and the engine resolves them into real paths by querying the database (when this round needs table creation / data loading, the script first runs a separate warehouse-building round, see the end of this section), staying correct as data is loaded:

```bash
# Enabled: file fault paths use backend-recommended targets
python3 scripts/resbench_batch.py --databases ... --workloads ... \
    --orchestration scripts/batch-example/file-orch.yaml --auto-file-targets --execute

# Not added: run exactly with the paths written in the orchestration (today's default behavior)
python3 scripts/resbench_batch.py --databases ... --workloads ... \
    --orchestration scripts/batch-example/file-orch.yaml --execute
```

Only **path parameters** are changed; `mark` / `content` / `duration` stay as is; database faults (`sql.*` / `txn.*` …)
are not touched at all. dry-run first prints the substitution table:

```
--auto-file-targets: file fault paths use backend-recommended targets
  filemove   dst-dir→@auto:pgdata  src-path→@auto:db-dir
  1 round involved: pg-docker_… × tpcc-1000c-200 × file-move-high
  Warehouse building and faults are not put in the same round: when the warehouse count pre-check determines that a given pair "database × tpcc workload" needs warehouse building, it **first runs a separate round
  as a warm-up round** (no faults, reload=true) to build the warehouses, after which formal rounds for that pair always use reload=false —
  sentinels are resolved only when the formal round is submitted, and what they get is the file set that was just built (warehouse building rewrites file numbers, so the path computed at the moment of
  submission is already stale once building completes).
```

The substitution rules come from the **high-intensity level** recommendations for each fault in the backend's `FileFaultTargetService.suggestFor`
(the item highlighted when you click "Recommend" in the frontend fault form); they are not invented by the script:

| Fault | Parameter | Sentinel enabled | Backend rule |
|---|---|---|---|
| `disk.filemove` | `src-path` / `dst-dir` | `@auto:db-dir` / `@auto:pgdata` | `filemove:high` / `filemove:dst-dir` |
| `disk.filechmod` | `filepath` | `@auto:db-dir` | `filechmod:high` |
| `disk.fileappend` | `filepath` | `@auto:hottest-last-segment` | `fileappend:high` (**the backend does not provide this recommendation on single-file layouts**, see below) |
| `disk.fileremoved` | `file-path` | `@auto:hottest-file` | The backend has **no** such rule; the script defines it itself (segment 0 of the hottest file) |

The low-level (`level=low`, the other highlighted item when you click "Recommend" in the frontend) rule is: **recommend only the single hottest file** —
PG recommends segment 0 of the hottest relation, MySQL recommends `<table>.ibd` of the hottest table. Files of other tables in the same database are still listed,
but are not marked ★ (marking all equals marking none).

The engine recognizes only these sentinels: `db-dir` / `hottest-file` / `hottest-last-segment` /
`hottest-mid-segment` / `datadir` (the old name `pgdata` is the same value and is also recognized). If you write one incorrectly, the engine errors out and lists the options,
and does not silently ignore it. To use another level, write the sentinel directly into the orchestration's `params`
(for example `filepath: "@auto:hottest-mid-segment"`); it takes effect even without the switch.

Sentinels are resolved **on the spot by database type** by the engine; the same name maps to different files on different databases:

| Database type | Data root directory | Files of one table | Segments |
|---|---|---|---|
| PostgreSQL | `PGDATA` | `PGDATA/base/<oid>/<relfilenode>` | 1 GiB per segment; segment 0 / last segment / middle segment |
| MySQL | `@@datadir` | `<datadir>/<database>/<table>.ibd` | No segmentation (InnoDB uses one file per table; indexes live in this `.ibd`) |

So on MySQL: `hottest-last-segment` is the entire table's `.ibd`; **there is no "non-last segment"** —
writing `@auto:hottest-mid-segment` explicitly reports "unable to resolve path" and asks you to switch to `hottest-file` / `db-dir`,
rather than giving you a wrong file. Other database types have no probe; using a sentinel directly reports "selecting target files by database type is not yet supported".

On MySQL (and on PG tables that have only one segment), **append has no safe level**: every level appends to the end of the table's primary data file,
and the engine does not pass `--enable-backup`. Therefore the backend **does not mark append with a recommendation** on such layouts, and an extra note appears in the list explaining this;
`--auto-file-targets` still replaces `fileappend` with `hottest-last-segment` (equivalent to the entire `.ibd`),
and the script lists it in the "irrecoverable" reminder. To test append, use the middle segment of a multi-segment table, or simply test only chmod / move on MySQL.

**Two consequences you must know:**

- **append being replaced with "last segment" is the irrecoverable level.** The append command rendered by the engine never passes
  `--enable-backup`, so the appended bytes are not undone → after the fault window ends, **the table is still corrupted**,
  and it requires TRUNCATE / reloading to recover. This is exactly why `file-orch.yaml` disables `file-append-high` by default.
  For a safe low level, hand-write `@auto:hottest-mid-segment` in the orchestration (databases without segmentation have no middle segment,
  so there you must write a concrete file), or do not enable the switch for that orchestration.
  (The plan will call this out by name.)
- **chmod is only mapped to `db-dir` (high)**: the low level that originally targeted a single file in the orchestration is raised to high,
  changing the intensity from "one file" to "the entire database directory".

**Warehouse building occupies its own round and does not share a round with the formal test.** Reason: `reload` first DROP + CREATE and then loads data,
and file numbers are generated only after that; sentinels, however, must be resolved into real paths **at the moment of submission**. If both are put into the same request,
the resolved paths become stale as soon as building completes, and faults hit files that no longer exist ("injection succeeds but metrics do not change at all",
the hardest kind to diagnose).

So the script treats "reload again" as a separate stage, in this order:

1. **Warehouse count pre-check** (`POST /web/bench/tpcc/warehouse/verify`, the same API as the frontend's "Warehouse Count Pre-check").
   Each "database × tpcc workload" pair is queried only once before that pair starts. If the warehouse count matches → go directly to step 3 without building warehouses.
2. **Warm-up round**: when warehouse building is determined to be necessary, first run a separate round — with no faults (hence no sentinels) and `reload=true`.
   When it finishes, the data is in place (the run name carries a `-prime` suffix, so you can tell at a glance in the archive that this is not a formal test).
3. **Formal round**: `reload=false`, with the real fault orchestration. Sentinels are resolved at the moment of submission, and what they get is the file set
   just built by the warm-up round. Subsequent rounds for this pair likewise use `reload=false` and do not rebuild warehouses.

The warm-up round still runs the workload (same duration as the formal round) and is not changed to `execute=false`: that would produce no
summary/monitor/metric, so the archive would never count as "complete", and the script could only wait until it times out. Its wait limit is likewise
`workload duration + --build-timeout`; if it fails (timeout / engine judges failure), this batch stops and does not continue with
"data may not be in place".

Rounds that do not build warehouses (`reload=false`, directly hitting data already present in the database) have their sentinels resolved at the moment of submission anyway,
and are rejected on the spot if resolution fails — that round will not get new file numbers, and failing early is better than failing halfway through.

## 4. Workload Duration Is Computed Automatically

Fault timing is scheduled by the engine; the script pre-computes it using the same set of rules:

```
Batch 1 injection time      t = 60s
Batch i end time            = injection time of that batch + maximum duration within the batch
Batch i+1 injection time    = end of previous batch + 30s
Plan end E                  = end time of the last batch
Workload duration           = E + tail (tail defaults to 20s)
```

For rounds with faults, the workload duration is **computed entirely from the plan** (`plan end + tail`). The `duration` written in `workloads.yaml`
does not participate: previously it took `max(yours, computed)`, so after changing fault durations the workload still had the old plan's length and
the plan did not match reality. To extend the steady-state tail, adjust `--tail`. `duration` only takes effect for orchestrations without faults
(`batches: []`) — for such rounds the plan end is 60s, and without writing it the workload runs only 80s.

## 5. Where Results Are Stored

### Per-round Runs: `backend/resbenchmark/results/<run name>/`

**The run name embeds the experimental environment**; the script sends `testName` so that the engine generates it in this format:

```
smallbank_og-local__sm-auto__serial-os-faults_20260930_002754
└─workload name─┘└──db key──┘└workload key┘└──orchestration key──┘└──timestamp──┘
```

(The prefix must retain the workload name; otherwise the history search's `startsWith` filter will not find this round.)
The same combination name also appears in the file names of those input XMLs in `config/generated/`.

Inside the directory:

| File | Contents |
|---|---|
| **`run-meta.json`** | **Experimental environment annotation**: database type/deployment mode/database parameters, workload name and all parameters, fault list (nameKey + parameters + grouping), startup options, tags, notes |
| `<workload>_<date>.config.xml` | The configuration actually in effect: connection target, full text of fault injection/recovery commands, agent address, workload parameters (**excluding username and password**) |
| `job.json` | Start/end time, success or not, end status |
| `monitor.csv` | Throughput and latency sampled every second |
| `faultInject.json` | Injection/recovery result and elapsed time for each fault |
| `<workload>_<date>.faults.csv` | Each fault's **actual injection time + duration**, for verifying against the plan |
| `<workload>_<date>.summary.json` | Database version, workload type, terminals, scalefactor |
| `metric.json` | Resilience metrics (stability/defence/recovery/RTO); **missing means it was not generated** |
| `.raw.csv` / `.results.csv` / `.samples.csv` / `.data_loss.csv` | Detailed data |

`connectionArchived: false` in `run-meta.json` means **connection information is intentionally not archived** (no username or password).

### The Script's Own Index: `batch-results/` (Changeable with `--out`)

**One invocation = one batch directory**; all artifacts from this invocation land inside it and are not mixed with other batches.
The directory name embeds the experimental environment of this run:

```
batch-results/
└── 20261001_122713_pg-docker_tpcc-360_e1/          ← <timestamp>_<db key>_<workload key>_<overall orchestration key>
    ├── runs-20261001_122713.csv                    ← one row per round, one file for the whole batch
    └── pg-docker__tpcc-360__e01-cpu-load-high/     ← matches the combination name segment in the engine run name
        └── tpcc_pg-docker__tpcc-360__e01-cpu-load-high_20261001_122715.json
```

- **The overall orchestration key** comes from the top-level `name:` of the orchestration file (see Section 3.3); if absent, the file name is used;
- All three key segments come from the combinations **actually being run this time** (after `--only-db` / `--only-workload` /
  `--only-orchestration` filtering), not from everything written in the configuration files;
- When multiple databases / workloads / orchestration files are selected at once, the same segment is joined with `+`:
  `20261001_122713_db-a+db-b_w1_e1+e2`;
- `runs-<batch timestamp>.csv` — one row per round, containing the run name, `suite`, database/workload/orchestration, plan end, actual workload duration,
  success or not, elapsed time, whether artifacts are complete, and each metric;
- `<run name>.json` — the complete raw row for this round in archive search;
- `<run name>.faults.json` — this round's injection/recovery result and self-check status for each fault
  (the raw return of `GET /web/bench/inject/info`).
- The three columns `fault_inject` / `fault_inject_errors` / `fault_verify` in the CSV answer
  **whether the fault actually took effect**:

  | Column | Value | Meaning |
  |---|---|---|
  | `fault_inject` | `ok` / `failed:2/3` / `unknown` / empty | All injection commands passed → `ok`; if any returns `Executed: Error <n>`, an agent call fails, or the self-check reports `NOT_ENFORCED` → `failed`; empty for baseline rounds (`batches: []`) |
  | `fault_inject_errors` | e.g. `Disk Fill(rc=1)` | Compact details of failed items; when `unknown`, records the reason |
  | `fault_verify` | e.g. `UNKNOWN:3`, `ENFORCED:3` | Summary of self-check status. `UNKNOWN` = no self-check command configured for this fault (true for all OS-level chaosblade faults); not counted as a failure |

  **For rounds with `fault_inject=failed`, the `defence` / `recovery` / `rto` in the same row cannot be used to answer "what happens when this fault is added"**
  — the fault was not injected, so the database in this round ran without the fault, and the scores only reflect its normal behavior. At wrap-up, the script lists these rounds together with the failure reasons.

  > Why the script parses this itself: the `error` field in the engine archive is always `false`
  > (`FaultInjector.markResult` receives the agent's `{"result": ...}` JSON envelope and cannot match the bare string),
  > and `enforced_fault_count` was measured to always be 0. The truth is written only in the raw string returned by the agent.
- `json_file` in the CSV is a path **relative to the current directory**; as before, you can `cat` it directly,
  except that a batch directory layer is now prefixed; if the entire batch directory is moved, it becomes invalid (moving the entire `--out` together does not have this problem).
- Each row is written to disk immediately after completion (flush + fsync), so completed records are not lost even if interrupted midway.
- Each round is also tagged with `batch / <db key> / <workload key> / <orchestration key>`;
  **the frontend history page can search for this batch of results using these tags**, and can also search directly for the combination name by "test name".

> **Old flat results under `--out` are not migrated or deleted**; the new layout only affects subsequent invocations.

## 6. Common Parameters

| Parameter | Default | Description |
|---|---|---|
| `--engine` | `http://127.0.0.1:8080` | Engine address |
| `--execute` | Off | Without it, this is a dry-run that only prints the plan |
| `--out` | `batch-results/` | Results **root** directory: each invocation creates a batch directory beneath it (see Section 5) |
| `--tail` | `20` | How many more seconds the workload runs after the fault plan ends |
| `--only-db` / `--only-workload` / `--only-orchestration` | None | Run only a subset of the Cartesian product; repeatable |
| `--end-margin` | `600` | Timeout determination: if it has not finished within workload duration + this value, report a timeout (**normal** rounds) |
| `--build-timeout` | `21600` | Wait limit for the **warehouse-building warm-up round** to finish (6 hours). 1000 warehouses measured at about 4h08m; increase it for more warehouses. If it times out, it **stops the whole batch** and reports this (it will not continue with "data may not be in place", nor leave behind a string of empty rounds blocked by "running") |
| `--busy-wait` | `600` | Maximum seconds to wait when submission hits "running" (another task is already on the engine). Before waiting, it prints **the state reported by the engine itself** (`end` / `startTimestamp` / `message`); if the wait fails, it prints cleanup instructions and **stops the whole batch** — the engine accepts only one task at a time, and continuing would only produce a string of empty rounds blocked by the same gate |
| `--auto-file-targets` | Off | File fault paths use backend-recommended real paths (`@auto:` sentinels), see Section 3.4. Without it, run with the paths written in the orchestration |
| `--keep-run-on-interrupt` | Off | On Ctrl+C / `kill`, do **not** stop this round's benchmark task on the engine. By default it is stopped (see Section 8) |

## 7. Known Pitfalls (the Script Prints the Evidence and Remedy Directly)

### 7.1 The Engine Says "Running" but No Task Is Actually Running

The engine allows a new task based only on the temporary state file `backend/resbenchmark/tmp/job.json` in the working directory:
every `GET /web/bench/status` re-reads it, and if the file says `curState: RUNNING`, it re-installs the "lock"
(`BenchMarkStatus.reset`: `end = (curState != RUNNING)`). This RUNNING is a **remnant** —
a load that was interrupted halfway (for example, an engine restart took down both the server and the child process) leaves no terminal state:
neither of the two paths that write the terminal state (the child process finishing on its own, the server-side wrap-up) was reached, and the engine does no reconciliation at startup.

Clearing it **does not require restarting the engine**:

```bash
curl -s http://127.0.0.1:8080/web/bench/status   # See which run startTimestamp refers to; empty message = it thinks it is still running
cat backend/resbenchmark/tmp/job.json
# {"curState":"RUNNING","startTimeStamp":"1790938926097","stopTimeStamp":"","success":false}
#        ↑ change to "MANUAL" (or "GRACEFUL"), add a stopTimeStamp, keep success false
curl -s http://127.0.0.1:8080/web/bench/status   # Once end returns to true, it is released
```

- The status shows as "task was manually stopped" (the engine currently has only the three states RUNNING / GRACEFUL / MANUAL,
  with no notion of "interrupted"); it was not actually stopped manually by anyone.
- `POST /web/bench/stop` **cannot clear this file in this situation** — it is only responsible for killing the current child process;
  only if the child process actually exists and is killed does the engine's async wrap-up write the terminal state into this file. A remnant means
  there is no child process anymore, so stop returns success while the file stays as it was (a genuinely running task is not in this category, see Section 8).
- Restarting the engine also does not clear it (no reconciliation at startup).
- This file is temporary: the next successful submission deletes the entire `tmp/`.

### 7.2 "Failed to Apply Database Parameters (Non-blocking for the Benchmark): Executed, but Read-back Mismatch"

The read-back is **telling the truth**: `shared_buffers` / `max_connections` are postmaster parameters,
and `ALTER SYSTEM` only writes `postgresql.auto.conf`, so a restart is required for them to take effect. So this message means
"the values were written, but this instance did not come back up with the new values". The script prints `restarted` / `waitedSec` /
the issued restart command / which two items differ, and gives the most likely cause based on whether `restarted` is true:

| What the engine reports | How the script explains it |
|---|---|
| `restarted=true`, read-back still old values | The restart command may have targeted a different instance/container than the one the engine connects to; the target instance may have been started with `-c shared_buffers=…` command-line arguments (common in docker image CMD), where command-line priority is **higher** than `auto.conf`; with the detach form, `returncode=0` does not mean the restart succeeded |
| `restarted=false` | The restart itself did not succeed (agent did not start / command returned non-zero / reconnect did not happen), with the reason in the message and engine logs |

`waitedSec` is **not** a criterion: the engine only starts connecting after the command returns, so if the database had already started before the command returned,
it is likewise 0. To know whether a restart actually happened, comparing `pg_postmaster_start_time()`
or the `source` of that parameter in `pg_settings` on the database is more accurate. For how to write the restart command itself, see §3.1.

## 8. What Happens on Interruption (Ctrl+C / `kill`)

**Default: stop the round that the script itself just submitted**, rather than leaving it running on the engine for hours.
`kill <pid>` (SIGTERM) follows the same path — previously this path was completely unmanaged, and the process would simply die silently.

The wrap-up order is intentional: local first, then network:

1. **Faithfully append a row** to the batch CSV for this round, with `note` stating that it was interrupted and the archive is incomplete (write to disk first, so it is not
   swallowed by a second Ctrl+C);
2. Close the CSV (rows already `fsync`ed are not lost);
3. `POST /web/bench/stop` (body `[]`, the same path as the frontend "Stop" button) → the engine side
   `destroyForcibly` kills the child JVM; once the child process dies, the engine's async wrap-up writes the terminal state into
   `tmp/job.json`, **releasing the gate as well** (the next submission will not hit "running" again);
4. Print the cost of this interruption;
5. Exit code `130`; the summary says "this batch stopped early: N rounds planned, only reached round k".

**Safety line for a shared engine**: the engine's process handle is a **singleton global slot**, and `stop` kills "the current one".
So before acting, the script claims ownership using the `startTimestamp` written by the engine itself:

| Engine state | Script |
|---|---|
| `end=true` | No task is running → do nothing |
| `startTimestamp` ≥ this submission's timestamp | It is our round → stop it |
| `startTimestamp` earlier | **It is someone else's task → never touch it**, and prints that statement |

Therefore: **pressing Ctrl+C while waiting for someone else's task will not affect them** (this is exactly what `--busy-wait` is meant to protect).
To do the opposite — "interrupt but keep this round's task" — add `--keep-run-on-interrupt`.

Three things to know:

- **Injected faults are not automatically recovered**. The engine-side "recovery" step for this round will not execute; OS-level chaosblade
  faults carry their own `duration` and expire on their own, while DBChaos-type faults are driven by the killed process and terminate with it.
  The full recovery command text is in the archived `<workload>_<date>.config.xml`; if you are really unsure, clean up on the database according to it.
- This round's **archive is incomplete** (no `metric.json`, etc.), and that row in the batch CSV does not represent a complete run.
- **`kill -9` / closing the terminal directly cannot be covered** (the script has no chance to wrap up, the task remains on the engine, and the gate stays occupied).
  To stop manually:

  ```bash
  curl -X POST http://127.0.0.1:8080/web/bench/stop \
       -H 'Content-Type: application/json' -d '[]'
  ```

  Note: this command both kills the process and writes the terminal state only when there **really is a child process running**; if there is no longer a
  child process on the engine (for example a remnant left by a previous interrupted restart), it returns success but `tmp/job.json` remains
  `RUNNING` — handle that case as described in §7.1.
