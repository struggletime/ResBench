English | [简体中文](README_cn.md)

# Four-Group Matrix: MySQL / PostgreSQL × TPC-C / smallbank

Runs all 4 combinations × 16 rounds in one pass, producing the raw data for the four tables in `docs/matrix-4groups-evidence.md`.

**Each round runs only one fault, injecting the same fault 3 times in a row**: in the orchestration, each fault occupies its own
orchestration containing 3 completely identical batches written back to back (each batch's fault runs 60s + a 30s inter-batch interval).
So one group = 16 rounds = 48 injections; the first 15 rounds have a per-round load of 320s (60s setup + 3×90s + 20s tail), and
round 16's `process.kill` has a 90s inter-batch interval and a 560s load; one group is about 90 minutes of load, and 4 groups take about 6–8 hours
(smallbank also recreates and loads tables every round).
One fault per round ensures that each round's baseline and monitoring session serve only that single fault: the previous batch's recovery period
will not overlap the next batch's baseline, and there will be no "aftereffects of other faults mixed into the 3 runs".
Intensity is uniformly pushed to the highest level (two exceptions: MySQL's host-level `memory.load` must stay at 20%;
`connection.thread_saturation` uses overflow 300 rather than the maximum 434; see "Known Constraints" for the rationale).

**Composition of the 16 faults (identical for each group):**

| Category | Count | Fault |
|---|---|---|
| CPU | 1 | Full load `cpu.load` |
| Memory | 1 | Memory consumption `memory.load` |
| Network | 5 | Packet loss `network.package`, delay `network.delay`, reordering `network.unorder`, duplication `network.duplicate`, packet corruption `network.corrupt` |
| Disk / file | 3 | Disk fill `disk.fill`, file move `disk.filemove`, permission change to 000 `disk.filechmod` |
| Process | 2 | Process suspension `process.stop` (SIGSTOP) + process kill `process.kill` (SIGKILL; after container PID 1 is killed the container exits, and recovery relies on the host `docker start`) |
| Database layer | 4 | Connection storm `connection.surge`, connection exhaustion `connection.limit.exceeded`, massive transaction rollback `transaction.massive.rollback`, connection thread saturation `connection.thread_saturation` |

Not run: `disk.fileremoved` (delete), `disk.fileappend`, and the remaining database-layer faults
(plan flip / stack overflow family / read amplification / duplicate transactions / deadlock storm / MVCC bloat).
`process.kill` has been included (round 16 of each group, the last round): it stops the container together with the database, and recovery depends on
the host `docker start` and crash recovery; placing it in the middle would contaminate the baselines of subsequent rounds. The remaining rationale is documented one by one at the end of
`docs/matrix-4groups-evidence.md`.

## Files

| File | Purpose |
|---|---|
| `databases.yaml` | Two databases: pg :5433 (container `pg_tpcc32`), mysql :3306 (host `mysqld.service`, no container configured) |
| `workload-pg-tpcc.yaml` | PG TPC-C, 100 warehouses (matching the actual warehouse count in the database; if the precheck matches, no rebuild) |
| `workload-mysql-tpcc.yaml` | MySQL TPC-C, 10 warehouses (matching the actual warehouse count in the database) |
| `workload-smallbank.yaml` | smallbank, 100k accounts / 20 terminals, shared by both databases |
| `matrix-pg-tpcc.yaml` | PG × TPC-C, 16 rounds × 3 runs per round (table=stock, file path @auto:) |
| `matrix-pg-smallbank.yaml` | PG × smallbank, 16 rounds × 3 runs per round (table=accounts, file path @auto:) |
| `matrix-mysql-tpcc.yaml` | MySQL × TPC-C, 16 rounds × 3 runs per round (table=stock, explicit /var/lib/mysql path) |
| `matrix-mysql-smallbank.yaml` | MySQL × smallbank, 16 rounds × 3 runs per round (table=accounts, explicit path) |
| `run-all.sh` | Overall entry point for the 4 runs |

## Prerequisites

- The engine `http://127.0.0.1:8080` and res-manager `http://127.0.0.1:8090` are running;
- The passwords in `databases.yaml` are live values; do not commit or share them;
- The database host's `/` has only about 2.6 GiB left — so `disk.fill` **must not** target `/`:
  PG targets the data disk `/var/lib/postgresql/data` (→100%), MySQL targets the separate small partition `/boot` (→100%).

## How to Run

```bash
# Print the 4 plans only, without submitting
scripts/matrix-4groups/run-all.sh --dry-run

# Formal run (about 6–8 hours; 4 groups × 16 rounds, first 15 rounds 320s load, round 16 560s)
scripts/matrix-4groups/run-all.sh

# Run only the PG side / only the MySQL side
RUN_MYSQL=0 scripts/matrix-4groups/run-all.sh
RUN_PG=0 scripts/matrix-4groups/run-all.sh
```

The equivalent individual python commands (run from the repository root; `$METRICS` is defined below):

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

`$METRICS` is the 32 metrics at the top of `run-all.sh`; copy this section:

```bash
METRICS="container.cpu.cores,container.cpu.usage,container.memory.usage,container.disk.read,container.disk.write,host.cpu.usage,host.memory.usage,host.load.ratio,host.disk.read,host.disk.write,host.disk.usage,host.pressure.io,host.pressure.cpu,host.process.stopped,host.netstat.retrans,net.tcp.rto.rate,net.tcp.retrans.ratio,net.tcp.oo.rate,net.tcp.dsack.rate,db.tps.agent,db.connections.usage,db.connections.open_rate,db.sessions,db.threads.running,db.blocked.sessions,db.lock.wait.max,db.slow_sql.max,db.cache.hit,db.rollback.rate,ebpf.disk.avg,ebpf.disk.p99,ebpf.runq.avg"
```

## Which Process Does Process Suspension Target (`--auto-process-target`)

`process.stop`'s `--process-cmd` is chaosblade's **command-line substring match**; writing a bare process name by hand easily
attaches to another instance. There are two pairs of same-named processes on site:

| Database | Instance under test | Another instance on the same machine | Consequence of the bare name |
|---|---|---|---|
| PostgreSQL | Container `pg_tpcc32`, :5433 | Host `/usr/bin/postgres -D /mnt/nvme4n1/pg_data -p 5432` | `postgres` matches both databases at once (plus sessions such as `docker exec … psql -U postgres`) |
| MySQL | Host `mysqld.service`, :3306 | Container `mysql8` (host network, :33060, command line is bare `mysqld`) | `mysqld` will SIGSTOP the idle instance as well |

Therefore `--auto-process-target` is enabled by default, performing a read-only probe **before submission** (reusing the agent
`:8000` on the database host; `databases.yaml` can override it with `agentPort`):

1. `ss -lntpH "sport = :<db port>"` finds the **listening process of the database under test** (the port is the only hard anchor);
2. Take its `argv[0]` as the match string (PG → `postgres`, MySQL → `/usr/sbin/mysqld`);
3. Verify the match scope: if the database is in a container → the match set must fall inside the container, and the cgroup of the host listening process must
   belong to that container; if the database is on the host machine → all matched processes must be in the **same cgroup** as the listening process.

If any of these is not satisfied, it **reports an error on the spot and stops** (no guessing, no silent degradation); the error lists every instance matched.
dry-run also prints the recommended result, for example:

```
  ----  pg: process.stop auto target = 'postgres' (inside container pg_tpcc32, 7 processes matched at probe time, instance /docker/1eab…)
  ----  mysql: process.stop auto target = '/usr/sbin/mysqld' (host machine, 1 process matched at probe time, instance /system.slice/mysqld.service)
```

The number of matched processes is only a snapshot **at the moment of submission**: PG's backends grow with the stress-test connections, and chaosblade re-matches
by substring at the moment of injection, so what is actually SIGSTOPed is all processes matching at that time (postmaster + each backend
+ auxiliary processes such as logger/checkpointer), which is exactly the intended effect of "database process suspension"; MySQL has only one
mysqld process (threads are not processes), so the entire database stalls. Only when this switch is off does it use the `process-cmd` written by hand in the orchestration.

## Results

Each invocation creates a directory: `batch-results/4groups/<timestamp>_<dbkey>_<workloadkey>_<orchname>/`
(orchname = the top-level `name:` of the orchestration file), with each of the 16 rounds occupying a subdirectory:

```
runs-<timestamp>.csv                      ← one row per round, including fault_inject / monitor_verify / monitor_evidence
<dbkey>__<workloadkey>__<roundkey>/           ← round key looks like 03-net-loss, 11-process-stop
    <runname>.json                      ← engine archive (including each batch's verdict)
    <runname>.faults.json               ← injection commands and receipts
    <runname>.monitor.json              ← monitoring metric series (data source for the second/third columns)
```

To backfill only a single round (for example, the reordering round last time did not capture a curve), add `--only-orchestration`:

```bash
--orchestration scripts/matrix-4groups/matrix-pg-smallbank.yaml \
  --only-orchestration 05-net-reorder        # run only this round (3 injections)
```

## Known Constraints

- **MySQL's 3306 is the host systemd `mysqld.service`** (datadir `/var/lib/mysql`),
  not `mysql8` in docker: `ss -lntp` shows `:3306` belongs to PID 3161's
  `/usr/sbin/mysqld`, while `mysql8` (host network, PID 92835) only listens on `:33060`.
  Therefore the MySQL entry does not configure a container — otherwise cAdvisor would collect metrics from the other instance,
  and chaosblade would also be placed into a container without blade installed. Cost: the two MySQL rounds have no `container.*` metrics.
- **MySQL's `cpu.load` uses the host-level scope**: the container side fullload only consumes the 32-core quota, while the host side
  consumes all 160 cores for 60 seconds. This is the only fault in this deployment that cannot be capped; while it runs, other host workloads
  will be slowed down.
- **`memory.load` has different intensity on the two sides; this is the only place not executed at "maximum"**: PG uses the container-level
  `--mem-percent 90` (container limit 40GB, baseline already uses ~57%, close to the limit, postgres has OOM risk);
  MySQL uses the host machine, **keeping `--mem-percent 20`** (503GB machine, alloc≈57GB, leaving 400GB
  headroom) — 90 would request ~400GB, OOM the whole machine, and take down the PG container and engine on the same machine as well.
- **`disk.fill` targets differ on the two sides, both pushed to 100%**: PG targets `/var/lib/postgresql/data`
  (the only usable real data disk in the container, 5.8T, →100%; filling it will make PG's WAL writes fail and may trigger
  a crash restart); MySQL targets `/boot` (1014MB separate xfs, 29.3% → 100%). The container's `/` is the whole-machine overlay and already 100%, and `/dev/shm`
  is tmpfs (not collected by node_exporter), so neither can be used. chaosblade's fill is fallocate,
  create returns in 1–2 seconds (2026-10-26 e07 archive percent=95 measured 1.53s), and will not hit the engine's 10s round trip.
- **MySQL's process.stop uses `/usr/sbin/mysqld`**: chaosblade's `--process-cmd`
  matches by command-line substring; with the full path it only matches the host service; writing `mysqld` would SIGSTOP the
  idle instance in `mysql8` (:33060) as well. The PG side still uses `postgres` (the command wraps docker exec).
  These two values are the results probed by `--auto-process-target`; the copy written in the orchestration serves only as the reference when the switch is off.
- **All 5 network faults are applied to the host's `ens17f0`** (including the agent feedback channel), all pushed to the highest available level:
  loss 80%, delay 1000ms, reorder 50%/correlation 90, duplicate 90%, corrupt 90%.
  * Packet loss does not use 100%: 80% already takes down the control plane as well (in the e03 archive the 3rd batch times out at 10s directly);
    100% means not even the injection receipt comes back, and the entire batch is unverifiable.
  * Reordering does not use 90%+: repository measurements show that when the reorder hit rate is 100% there is actually no real reordering (2000/2000 hits,
    delay 0.06ms, reordering 0); only 30–50% produces real reordering, and 50% is the highest level that still produces real reordering.
- **`connection.thread_saturation` uses overflow 300, not the maximum 434**: neither PG nor MySQL has
  `thread_pool_attr`; DBChaos computes `min(64 + overflow, max_connections - 2)` based on a baseline of 64,
  so 300 → 364 resident sessions (≈ 73% of max_connections 500); 434 → 498/500,
  and even check's own connection and the engine's reconnection could be squeezed out. On 2026-09-03 there was already evidence that connection-type faults
  killed the workload (process terminated after 68 seconds of stalling), see `docs/troubleshooting-no-curve-and-zero-score.md`.
  Criteria: MySQL looks at "running threads" ↑ (the injected session state is Query, not Sleep); on the PG side `db.*`
  is in `og_*` scope and the DSN is not on the instance under test, so **there is no usable curve and the verdict will be NO_DATA**.
- **`host.process.stopped` has a resolution of about 30s and a lag of 40–60s**, so process.stop's
  duration is set to 60s; a window that is too short will not capture the T-state point.
- **The visible impact of file-type faults is milder on MySQL than on PG**: InnoDB always holds the fd of opened `.ibd` files, so
  move/chmod mainly affects newly opened files/reopened tables. The criteria combine the injection receipt with slow SQL/TPS.
- **PG file paths must use `--auto-file-targets`**: smallbank rebuilds tables every round, so the relfilenode changes and
  can only be queried at run time; MySQL conversely cannot use @auto (db-dir → datadir would move it into its own parent directory).
- When the script encounters the engine in "executing" state it waits; if the wait does not succeed, it stops this batch, and it will not stop other people's tasks; `run-all.sh`
  does not call `POST /web/bench/stop`.
