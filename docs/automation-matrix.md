# Automated Test Matrix (fixed-time / multi-orchestration / composite faults)

Script: `scripts/resbench_matrix.py`, example configuration: `scripts/matrix-example.yaml`

## 1. What It Solves

- **Test matrix**: `databases × workloads × orchestrations × repeat`; one configuration, run serially to completion;
- **Composite faults**: multiple faults within the same `stage` fire **simultaneously** (mapped to the same engine `groupKey`);
  multiple `stage`s = **serial cascade** (the engine automatically leaves 30s between groups);
- **Fixed-time mode**: only `autoTimingMode=fixed-time` is sent, so trigger times can be precisely estimated;
- **Automatic archiving**: each round passes `testName / dbKey / orchestrationId / labels / source=script`,
  and after the run `/web/bench/history/query` writes metrics and artifact completeness into `index.csv`,
  so this batch of matrix results can be searched directly on the frontend history page;
- **Resume from checkpoint**: `--resume <batch directory>` skips rounds that already succeeded;
- **Dry-run by default**: no jobs are submitted unless `--execute` is given.

## 2. Run Name Rules

The script passes `testName = <dbKey>_<workloadKey>_<orchestrationId>_r<n>`, and the engine assembles it according to its rules into

```
<workload>_<dbKey>_<workloadKey>_<orchestrationId>_r<n>_<yyyyMMdd_HHmmss>
Example: smallbank_oglocal_std_combo-cpu-net_r1_20260921_120000
```

The prefix is always the workload name, satisfying the `historyName.startsWith(testSuiteName)` filter requirement of `/bench/result`, `/bench/event`, and `history/list`.

## 3. Fault Scheduling (Fully Consistent with the Engine)

The rules of the engine's `StartByFormService.buildScheduledFaults`, replicated 1:1 by the script for estimation and validation:

```
Group baseline = 60s (first group)
Trigger time of a fault within a group = group baseline + delaySeconds
Group end = max(trigger time + duration)
Next group baseline = group end + 30s
```

`duration` is taken from `fault.params.duration` (seconds); if absent, the `defaultDuration` in the fault catalog is used. The script explicitly adds `duration` to the request parameters to ensure that the "estimate" matches the "actual run".

> Verified: against a real run `results/smallbank_20260827_013845` (5 fault groups, engine records
> `faultDelay = 60/120/180/240/300`, `faultDuration = 30/30/30/30/60`),
> the start times predicted by the script match the engine's actual records **one by one**, and the planned end point is likewise 360s.

Estimated duration of a single round = `max(workload.params.duration, planned end point) + load overhead + recovery tail`;
`load overhead` can be overridden with `engine.estimatedOverheadSec` or `workloads.<key>.estimatedOverheadSec` (increasing it is recommended for TPCC).

## 4. Configuration Structure

```yaml
engine:
  baseUrl: http://127.0.0.1:8080   # The only thing you need to know: where the engine is (can also be overridden with --engine)
  # All other keys have defaults; write them only when needed (defaults in parentheses):
  #   env (openGauss)            Environment name → envInfo in the request; the engine does not read it, the manager uses it to select the engine
  #   monitorIntervalSec (1)     Monitoring sampling interval → actually sent to the engine (-im)
  #   pollIntervalSec (5)        How often the script polls /web/bench/status
  #   startTimeoutSec (180)      Timeout for waiting until the job actually starts running
  #   endMarginSec (300)         Margin for waiting for the end (recovery/verification tail)
  #   settleSec (20)             Wait after the end for the archive to land on disk before querying
  #   cooldownSec (30)           Cooldown between rounds
  #   tailSec (60)               For estimation: recovery/verification tail
  #   minSlackSec (30)           Workload duration must exceed the fault schedule by at least this much
  #   estimatedOverheadSec (120) Estimated table-creation/load overhead

archive:                # Archive metadata
  labels: [matrix, nightly]
  note: "Automated matrix"

databases:
  <dbKey>:
    dbType: opengauss | mysql | postgresql | oceanbase | gaussdb
    deploymentType: standalone | distributed
    standalone: {ip, port, username, password}
    distributed: {...}          # oceanbase/gaussdb
    params:                     # Database configuration parameters (not connection info) → archived, searchable and comparable
      shared_buffers: 2GB
      max_connections: "500"
    container: opengauss-db     # Optional: see §9 In-Container Injection

workloads:
  <workloadKey>:
    name: smallbank | tpcc
    params: {duration: "600", rate: "200", ...}
    runOptions: {reload: true}   # For tpcc, the script automatically enables reload on the first round of each group
    estimatedOverheadSec: 600

orchestrations:
  # nameKey must come from the engine's real catalog: python3 scripts/resbench_matrix.py --list-faults
  - id: combo-conn-planflip
    note: "Connection storm + execution plan flip occurring simultaneously"
    stages:
      - key: s1                  # Faults with the same key trigger simultaneously
        faults:
          - {nameKey: faultinject.fault.connection.surge, delay: 0,
             params: {concurrent-connections: "200", duration: "60"}}
          - {nameKey: faultinject.fault.sql.plan_flip, delay: 0,
             params: {threads: "16", duration: "60"}}
      - key: s2                  # Next stage runs serially (the engine automatically leaves a 30s interval)
        faults:
          - {nameKey: faultinject.fault.transaction.massive.rollback, delay: 30,
             params: {threads: "16", duration: "60"}}

matrix:
  repeat: 1
  exclude: ["mysqllocal@w32"]    # A whole label can also be written
```

> **Why does the `engine` section contain only `baseUrl`?** Because the remaining keys are not "engine configuration items" but pacing parameters of the script acting as an **external driver** (polling/waiting/cooldown/estimation) -- the engine API has no such concepts, so naturally the frontend has no corresponding input fields either. The frontend's role is to "submit a test once"; once submitted, it is done (the engine runs asynchronously), and it does not sit outside the engine waiting. Conversely: the script must know where the engine is, while the frontend does not need to (same origin).
> In addition, `env` is consumed by the **manager** (`benchMarkAddr.getBenchMarkAddr(envInfo)` uses it to choose which engine), and the engine itself does not look at `envInfo` at all; `monitorIntervalSec` is the one actually sent to the engine (`-im`).

## 5. Command Line

```bash
# First inspect which faults actually exist in the engine (nameKey/name/default duration/required parameters) -- mandatory before writing an orchestration
python3 scripts/resbench_matrix.py --list-faults

# Preview the matrix, the estimated duration of each round, and the total duration (dry-run by default, no jobs submitted)
python3 scripts/resbench_matrix.py --config scripts/matrix-example.yaml

# Environment preflight only: database connectivity for each entry (engine /web/bench/connection/test) + whether the archive query API is available
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --preflight-only

# Offline preview (do not connect to the engine to validate the fault catalog)
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --skip-catalog-check

# Actual execution
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute

# For file-type faults, let the backend recommend the paths (@auto: sentinels), see Section 10; without this flag, run with the parameters written in the YAML
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute --auto-file-targets

# Run only some orchestrations / override the repeat count / specify the output directory
python3 scripts/resbench_matrix.py -c cfg.yaml --execute --only combo-cpu-net,single-disk --repeat 3
python3 scripts/resbench_matrix.py -c cfg.yaml --execute --out runs/batch-20260921

# Resume from checkpoint (skip rounds that already succeeded)
python3 scripts/resbench_matrix.py -c cfg.yaml --execute --resume runs/batch-20260921

# By default, --execute runs the preflight first and aborts if any database is unreachable (to avoid discovering it halfway through a long matrix)
#   --skip-preflight  Skip the preflight
#   --force           Continue even if the preflight fails
```

Preflight output example:

```
Running preflight (database connections + archive API)...
  PASS  oglocal (opengauss) connected: connection successful
  FAIL  mysqllocal (mysql): unable to connect to the database server; check whether the IP and port are correct...
  PASS  Archive query API available: index.csv will include metrics and artifact completeness
```

Old builds (without the new API) are not marked as failures; they only produce `SKIP`/`WARN`, and the matrix can still run as usual.

## 6. Artifacts and Querying

```
runs/<batch>/
  matrix.json            # Expansion of this matrix and the configuration path
  index.csv              # One row per round: label/historyName/planned and actual duration/success and key metrics/completeness
  cells/<label>/
    request.json         # The request actually submitted (connection info redacted)
    schedule.json        # Fault schedule (absolute trigger times)
    result.json          # Archive row retrieved from history/query after the run (metrics + artifact completeness)
```

`index.csv` can be used directly for pivot tables (db × workload × orchestration), and the same batch of runs can also be searched directly on the frontend history page by labels (`archive.labels`), orchestration id, `dbKey`, and source `script`.

## 7. Known Constraints (Validated or Avoided by the Script)

- The engine runs only one round at a time → the script executes serially, with `cooldownSec` cooldown between rounds;
- Workload names other than `smallbank`/`tpcc` are rejected by the engine; the `oceanbase + tpcc` combination is invalid;
- Distributed deployment supports only `oceanbase`/`gaussdb`;
- TPCC requires `reload=true` for table creation and loading; the script automatically enables it on the first round of each `(dbKey, workloadKey)` group;
- `workload.params.duration` must be ≥ the planned fault end point (otherwise it is truncated); the script warns about this during the dry-run phase;
- **`nameKey` must match the engine catalog verbatim** (self-invented names are caught by the dry-run), and omitting required parameters is also caught;
- Some faults are coupled to workload table names (e.g. the `table` of `txn.deadlock_storm` defaults to TPCC's `bmsql_stock`, and the `table` of `txn.uncommitted_txn` is required); when running smallbank, either change `table` or exclude it with `matrix.exclude`.

## 8. Database Parameters Are Actually Applied to the Database

`databases[].params` used to be read by the engine only into the `run-meta.json` archive, so the resulting run had nothing to do with the written parameters.
The semantics are now:

| Case | Behavior |
|---|---|
| `shared_buffers` / `max_connections` specified in `params` | Applied with the specified values; og/pg require a restart, and **the engine is responsible for restarting and waiting until it reconnects** |
| Not specified in `params` (or empty) | **Do nothing**; use the database's existing configuration directly |
| After the run | **Not restored**; the modified state is kept |

MySQL recognizes `innodb_buffer_pool_size` / `max_connections`, applied online with `SET GLOBAL` **without a restart**.
Other `params` keys (such as `work_mem`) are ignored with a notice.

### Calling the Engine API Instead of Connecting to the Database Yourself

The script hands the parameters to the engine's `POST /web/bench/db/params` (the frontend "Database Configuration → Apply Configuration" button goes through the same implementation); reading the current state / generating SQL / restarting / polling for reconnection / reading back for verification are all performed by the engine:

```
Script → POST <engine>/web/bench/db/params
  { dbType, deploymentType, standalone{ip,port,username,password},
    params{bufferPool, maxConnections},          ← semantic keys; the script translates them from the real parameter names
    restart{command, waitSec}, agentPort }
   ← {success, message, actualNames, targets, before, after, statements,
      alreadyEffective, requiresRestart, applied, restarted, waitedSec, mismatches}
```

The engine's internal execution order (executed only once per database entry):

```
Read current state (pg_settings / SHOW VARIABLES)
  └ Target value already effective → skip, no unnecessary restart
Apply (ALTER SYSTEM SET / SET GLOBAL)
Restart (og/pg only; deliver restartCmd to the database host via agent /command/exec)
  → afterwards retry the JDBC connection every 2 seconds, up to waitSec (default 180, server-side clamped to [10,600])
Read back for verification
  └ Verification mismatch → success=false + mismatches (there is no rollback, so "not effective" must never count as success)
```

**Why no longer let the script connect to the database and execute SQL itself**: that logic already exists in the engine, and two implementations would evolve separately -- the key-name contract, rounding tolerance (MySQL rounds `100M` to `128M`), and error messages would all diverge.
Now the script is responsible only for parameter-name translation and result mapping; failure messages are taken directly from the engine, consistent with what is seen in the UI.

> Requires an engine **build that includes this API**: an old build is marked FAIL directly during the preflight ("the engine has no parameter-application API (old build)"), rather than being discovered halfway through a long matrix.

### Configuration

```yaml
databases:
  oglocal:
    params: {shared_buffers: 2GB, max_connections: "500"}
    agentPort: 8000          # The engine delivers the restart command to dbIP:agentPort (default 8000)
    # Command to execute when a restart is needed; make sure it is detached. The engine does not guess the deployment method (systemd/docker/gs_ctl); it must be given explicitly.
    # The agent's user identity = the user that started the agent; openGauss cannot be started as root, so you usually need to su to the database's OS user;
    # in single-node mode, also include the -Z parameter you installed yourself (e.g. -Z single_node).
    restartCmd: 'nohup su - YOUR_USER -c "gs_ctl restart -D /home/YOUR_USER/gaussdb-data/data -Z single_node" >/tmp/rb_restart.log 2>&1 &'
    waitSec: 180
  mysqllocal:
    params: {innodb_buffer_pool_size: 2G, max_connections: "500"}
    # MySQL applies online → no restartCmd needed
```

`sqlRunner` is deprecated (the script no longer connects to the database to execute SQL itself). Leaving it in the configuration does not cause an error; the preflight will note that it can be removed.

Standalone verification (without running the matrix):

```bash
python3 scripts/resbench_matrix.py -c cfg.yaml --apply-db-params oglocal
python3 scripts/resbench_matrix.py -c cfg.yaml --apply-db-params all
```

The preflight of `--preflight-only` / `--execute` will: probe whether the engine has the parameter-application API (probed with empty `params`, **without touching the database**), and print for each entry what will be applied and whether a restart is needed. **A missing `restartCmd` is marked as a failure directly during the preflight**, rather than being discovered halfway through a long matrix.

### Three Pitfalls

| Pitfall | Basis | Consequence | Remedy |
|---|---|---|---|
| The agent's upper limit for waiting on a single command is **30 seconds** | `asyncio.wait_for(..., timeout=30)` in `agent/faults/command.py` | A restart command that is not detached is killed by the agent while waiting and returns `returncode=-1` | Always use `nohup ... >log 2>&1 &` for `restartCmd`; the success criterion is whether the engine "can reconnect", not whether the command returns 0 |
| **Which user the agent runs as** determines whether `su` works | `/command/exec` uses `create_subprocess_shell`, identity = the user that started the agent | When not root, `su - omm` reads the password from stdin → authentication failure (reported explicitly, not silently) | Run the agent as root (can `su` to any user), or configure NOPASSWD for `sudo -u <user> ...` |
| Changing the bufferpool is a **global side effect** | And by convention it is not restored | This database keeps the modified configuration after the run, affecting other users | Confirm during testing that this database has no other workloads |

### Artifacts

- Per batch, `runs/<batch>/db-params.json`: records per database `action` / `targets` / `before` / `after` / `statements` /
  `restarted` / `waitedSec` / `mismatches` / `reason` (the Chinese description provided by the engine; refer to it directly on failure);
- `index.csv` gains a `dbParams` column (e.g. `shared_buffers=2GB+max_connections=500+restart`),
  making it easy to distinguish "actually running on 2GB" from "only registered as 2GB";
- `database.params` in the archive now records **the actual effective values read back by the engine** (real parameter names): when MySQL rounds `100M`
  to `128M`, the value recorded is `128MB`. When the engine cannot report it (old build / the request itself failed), it falls back to the requested values written in the configuration.
- Parameter values still go into the engine's `run-meta.json` archive, and the history page can search and compare by configuration.
## 9. In-Container Injection (Database Running in Docker)

When the database runs in a container, CPU / memory / disk / process faults from the host's perspective cannot reach inside the container.
Adding a `container` under the database configuration makes the engine wrap these chaosblade faults in a layer of `docker exec` executed inside the container.
(**The frontend "Database Configuration" has the same input field "Container Name"**; filling it in follows the same path; on the script side it is `container` / `containerName`.)

```yaml
databases:
  oglocal:
    dbType: opengauss
    standalone: {ip: 127.0.0.1, port: "5434", username: test1230, password: "<password>"}
    container: opengauss-db     # ← Just this one line
```

| Fault type | After adding `container` |
|---|---|
| chaosblade types (`cpu.*` / `memory.*` / `disk.*` / `file.*` / `process.*`) | `docker exec <container> /opt/chaosblade/blade create ...`, effective inside the container |
| Network types (`faultinject.fault.network.*`) | **Exception**: still applied to the host (container NIC names differ from the host's, and network namespaces are isolated); the engine logs a WARN explaining that this container name was ignored |
| DBChaos types (`connection.*` / `sql.*` / `txn.*` …, command is `java -jar DBChaos.jar`) | Unchanged: it connects to the database via JDBC and does not need to run inside the container |

Prerequisites (if any is unmet, fault injection fails or hits the host; the script performs no checks):

1. **chaosblade is installed inside the container**, and `/opt/chaosblade/blade` is executable in the container (installing it on the host does not count);
2. **The agent's OS user is in the `docker` group**, otherwise `docker exec` lacks permission;
3. **The container name is resolvable on the agent host** (the agent and the container are on the same machine; the agent address sent by the script is `dbIP:8000`).

The preflight prints the following under the line of each database configured with `container`:
`---- <dbKey>: chaosblade-type faults will be injected inside container <name> (docker exec; network faults still go to the host)`, so you can confirm the configuration is correct before running. Likewise, `index.csv` gains a `container` column, so you can tell afterwards which target each round hit;
the engine's `run-meta.json` also records the container name under `database.containerName` (the key is omitted if not configured).

### 9.1 "Apply Configuration" on Containerized Databases: You Must Write the Restart Command Yourself

The backend will **not** synthesize the restart command for you (it does not guess systemd / docker / gs_ctl at all); it only delivers `restartCmd` to the agent verbatim. When the database runs in a container, the `gs_ctl` / `systemctl` command on the host will restart **a different** database:

```yaml
    container: og-db
    params:
      shared_buffers: 2GB
      max_connections: "500"
    # Executed on the **host**, without wrapping in docker exec → use docker restart directly;
    # it must be detached (nohup ... &), otherwise the agent-side 30-second single-command wait limit will kill the wait.
    restartCmd: 'nohup docker restart og-db >/tmp/rb_restart.log 2>&1 &'
```

Another easy trap: disk fault paths are resolved as **in-container** paths.
`disk.fill.path` / `disk.filemove.src-path` / `disk.fileremoved.file-path` / `disk.filechmod.filepath`
must be filled with the in-container paths read from `SHOW data_directory`, not the host mount paths.
(Paths for file-type faults can be left unfilled; see Section 10.)

---

## 10. File-Type Faults: Let the Backend Recommend Paths (`--auto-file-targets`)

Which file a file-type fault should target depends on what the database looks like after this round's data load (where the database directory is, which table is hottest, and what its segment files are called). Once a manually filled path does not match reality, the fault hits nothing -- **injection reports success while metrics do not change at all**, and this kind of "false success" is the hardest to diagnose. Therefore the script provides a switch:

```bash
# Enable: replace the path parameters of all file-type faults with @auto: sentinels, and the engine resolves them into real paths by querying the database at the moment the run starts
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute --auto-file-targets

# Without this flag: run exactly with the parameters written in the YAML (today's default behavior)
python3 scripts/resbench_matrix.py -c scripts/matrix-example.yaml --execute
```

The switch **changes only path parameters**; `mark` / `content` / `duration` and the like remain as written in the YAML;
database-type faults (`sql.*` / `txn.*` / `connection.*` …) are completely unaffected. The dry-run prints the replacement result first:

```
--auto-file-targets: file-type faults in 1 round use recommended parameters
  → oglocal_bs_O-filemove_r1: 1 file-type fault uses recommended parameters --
    File Move (src-path → @auto:db-dir, dst-dir → @auto:pgdata)
```

### 10.1 What Each of the Four Faults Is Replaced With

The sentinel values come from the **high-intensity tier** recommendations that the backend's `FileFaultTargetService.suggestFor` marks for each fault --
exactly the entry highlighted when you click "Recommend" in the frontend fault form, not invented by the script:

| Fault nameKey | Path parameter | Sentinel enabled | Corresponding backend rule |
|---|---|---|---|
| `...disk.filemove` | `src-path` | `@auto:db-dir` | `filemove:high` → the entire database directory |
| `...disk.filemove` | `dst-dir` | `@auto:pgdata` | `filemove:dst-dir` → the data home directory (PGDATA for PG / `@@datadir` for MySQL) |
| `...disk.filechmod` | `filepath` | `@auto:db-dir` | `filechmod:high` → the entire database directory |
| `...disk.fileappend` | `filepath` | `@auto:hottest-last-segment` | `fileappend:high` → the last segment of the hottest table |
| `...disk.fileremoved` | `file-path` | `@auto:hottest-file` | **The backend has no such rule**; defined by the script → takes segment 0 of the hottest file |

There are only six usable sentinel names: `db-dir`, `hottest-file`, `hottest-last-segment`,
`hottest-mid-segment` (segment 1 of the hottest table, and it must not be the last segment), `datadir`,
`pgdata` (an old name, the same value as `datadir`).
For other combinations, write them directly into the YAML `params` (passed through verbatim when the switch is off);
a misspelled name causes the engine to report an error and list the options, rather than being silently ignored.

Sentinels are **resolved on the spot according to database type**; the same name resolves to a different file on different databases:

| | Data home directory (`datadir` / `pgdata`) | Hottest table's file | Segmentation |
|---|---|---|---|
| PostgreSQL | `PGDATA` | `base/<oid>/<relfilenode>` | 1 GiB per segment, with segment 0 / last segment / middle segments |
| MySQL | `@@datadir` | `<datadir>/<database>/<table>.ibd` | InnoDB uses one file per table and does not segment |

Therefore, on MySQL, `hottest-last-segment` is the entire `.ibd` (without error), while `hottest-mid-segment`
explicitly reports "cannot resolve a path" (a non-segmented database has no middle segment); other database types report "selecting target files by database type is not yet supported".

### 10.2 Two Things You Must Know

- **These four nameKeys are not in the engine's fault catalog.** In `--list-faults` they are marked with `*` and noted as "provided by the script":
  the engine's `config/fault-cases.yaml` includes only the 18 in-database faults of DBChaos, whereas file-type faults are chaosblade
  host operations that the engine handles directly with a switch, so the catalog API cannot find their entries. The nameKeys are valid.
- **The round that creates the warehouse is not a problem; the sentinel is simply resolved later.** `@auto:` requires querying the database to locate files,
  while `create` / `load` / `reload` first DROP + CREATE and then load data -- file numbers are generated only after that,
  so resolving at submission time would inevitably be stale. Therefore the engine **deliberately does not resolve** at submission; it writes the sentinel into the configuration as-is,
  and the job resolves it after `create + load` completes and before the workload and fault injection begin:
  "first finish creating the warehouse → then confirm the parameters of the file faults → then start running from the first test".
  For tpcc, the first round of each group forces reload, so this rule alone allows a direct run without a warm-up round first.
  The dry-run lists such rounds separately (prefixed with `!`, not counted as validation problems).
  Conversely, when `create` / `load` / `reload` are all false (operating on data already present in the database), the engine resolves at submission time and rejects on the spot if resolution fails.
