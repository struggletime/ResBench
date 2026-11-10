# ResBench

**Database Resilience Benchmarking Platform**

English | [简体中文](README_cn.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## Contents

- [Overview](#overview)
- [Key Capabilities](#key-capabilities)
- [Architecture](#architecture)
- [Repository Structure](#repository-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Agent Deployment](#agent-deployment)
- [Web Console](#web-console)
- [Resilience Scoring](#resilience-scoring)
- [Configuration Reference](#configuration-reference)
- [Troubleshooting](#troubleshooting)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Overview

ResBench is an open-source platform for evaluating the resilience of database systems under adverse conditions. It executes production-like workloads while injecting operating-system and database-level faults, captures throughput and latency in real time, and derives a multi-dimensional resilience score based on the resilience triangle model.

The platform is designed for database engineers, platform teams, and researchers who need reproducible, comparable measurements of how a database degrades under stress and how quickly it recovers. It supports single-node and distributed deployments, including openGauss, GaussDB, MySQL, PostgreSQL, and OceanBase.

## Key Capabilities

- **Workload generation.** Built-in support for TPC-C (BenchmarkSQL), SmallBank, and YCSB workloads, configurable by data scale, concurrency, duration, and transaction mix.
- **Layered fault injection.** Operating-system faults through ChaosBlade and database-level faults through DBChaos and JDBC-based programs.
- **Flexible orchestration.** Faults can be injected sequentially or as a synchronized group with a shared duration and start delay. An optional validation step checks the plan against a knowledge graph for conflicting or duplicate faults.
- **Real-time observability.** Live TPS and latency charts, current fault status, and pause/resume controls during a run.
- **Reproducible scoring.** A ten-dimensional score derived from the resilience triangle, including throughput, latency, stability, resistance, recovery, adaptability, volatility, RTO compliance, metric deviation, and perturbation duration.
- **Historical analysis.** Persistent test records, generated reports with PDF export, and side-by-side comparison across databases, workloads, and fault configurations.
- **Distributed execution.** A manager, a benchmark engine, and lightweight agents that run next to the database under test.

## Architecture

ResBench consists of four components:

| Component | Host | Default port | Responsibility |
| --- | --- | --- | --- |
| Web console | Developer workstation or manager host | 5173 (development) / 8090 (production) | Configuration, run control, live charts, reports, and comparison |
| Manager (`res-manager`) | Control host | 8090 | REST API, orchestration, history records, and report generation |
| Benchmark Engine (`resbenchmark`) | Control host | 8080 | Workload generation, metric collection, fault scheduling, and scoring |
| Agent | Database host | 8000 | Executes fault injection and recovery through ChaosBlade, DBChaos, or JDBC programs |

Data flow:

```text
Web console → Manager (8090) → Benchmark Engine (8080) → Agent (8000)
                                                    → ChaosBlade / DBChaos / JDBC
                                                    → Database under test
```

The engine pushes transaction statistics to the agent over WebSocket and streams live TPS to the web console.

## Repository Structure

```text
ResBench/
├── frontend/                  Web console (React 19 + Vite + Ant Design)
├── backend/
│   ├── resbenchmark/          Benchmark engine: workloads, metrics, fault scheduling
│   ├── res-manager/           Spring Boot management service and REST API
│   ├── resbenchmark-common/   Shared libraries
│   └── agent/                 Python FastAPI agent deployed with the database
├── scripts/                   Batch orchestration, monitoring, and ChaosBlade helpers
├── docs/                      Design and operational documentation
├── start-dev.ps1              Windows development launcher
├── start-dev.sh               Linux and macOS development launcher
└── README.md
```

## Prerequisites

| Role | Requirements |
| --- | --- |
| Control host | JDK 21, Maven 3.6 or later, Node.js 20.19+ (or 22.12+) with npm |
| Scoring pipeline | Python 3.9 or later with `pandas`, `numpy`, `scipy`, `matplotlib`, `scikit-learn`, `statsmodels`, `ruptures`, and `pymser` |
| Agent host | Python 3.9 or later with `fastapi`, `uvicorn`, `websockets`, `pandas`, `Jinja2`, `PyYAML`, `numpy`, `scipy`, `matplotlib`, `scikit-learn`, `statsmodels`, `ruptures`, and `pymser` |
| Fault tooling | ChaosBlade 1.7.4 for operating-system faults; DBChaos and JDK 21 for database-level faults; a JDBC driver for the target database |

## Getting Started

### Windows

From the repository root in PowerShell:

```powershell
.\start-dev.ps1
```

### Linux and macOS

From the repository root:

```bash
chmod +x start-dev.sh
bash start-dev.sh
```

The launcher starts three services:

- Benchmark Engine on port 8080
- Manager on port 8090
- Frontend development server on port 5173

Once all services are ready, open <http://localhost:5173>.

> In development, the frontend runs on the Vite development server (5173) and proxies API requests to the manager on port 8090. The launcher first compiles the backend with Maven, starts the engine (approximately 2 GB of heap), then starts the manager, and finally starts the frontend. All three services must remain running; without the engine, the history and comparison pages cannot load.

To stop the development environment, close the terminals or press `Ctrl+C` on Linux and macOS.

## Agent Deployment

The agent is a Python service deployed on the **database host**. It receives commands from the benchmark engine over HTTP and executes fault injection locally through ChaosBlade, DBChaos, or Java programs.

```text
Engine (8080) → Agent (8000) → ChaosBlade / DBChaos / JDBC program → Database under test
```

### Directory Layout

```text
backend/agent/
├── main.py                      FastAPI application, listens on port 8000
├── agent_monitor.sh             Watchdog and crontab-based keepalive
├── pom.xml                      Maven packaging configuration
├── assembly/package.xml         Package assembly descriptor
├── faults/
│   ├── process.py               /process/*   Process faults
│   ├── network.py               /network/*   Network faults
│   ├── hardware.py              /hardware/*  CPU, memory, and disk faults
│   ├── command.py               /command/*   Generic command execution
│   ├── file.py                  /file/*      File faults
│   └── facilities.py            /facilities/* Facility faults
├── dependencies/
│   └── fault_models.py          Shared models and token authentication
├── utils/
│   └── tools.py                 Utility functions, including shell execution
├── scripts/
│   ├── java/                    Database-level fault programs
│   ├── cm_fault.sh              Cluster manager faults (GaussDB)
│   ├── file_chmod_fault.sh      File permission changes
│   ├── file_mv_fault.sh         File move and removal
│   ├── process_hang.sh          Process suspension
│   ├── process_resume.sh        Process recovery
│   ├── process_shutdown.sh      Process termination
│   └── process_start_oceanbase.sh
├── DBChaos/                     Database-level fault injection tool
└── templates/                   Jinja2 templates for the work statistics page
```

### Core API

The agent exposes interactive API documentation at `http://<agent-host>:8000/docs`.

| Endpoint | Module | Purpose |
| --- | --- | --- |
| `/command/exec` | `faults/command.py` | Generic command execution; the primary integration point |
| `/process/instance/{os}/{dbtype}/{inst}/{op}` | `faults/process.py` | Start or stop a database instance (`op=start` or `stop`) |
| `/network/{node}/{op}` | `faults/network.py` | Network faults such as packet loss and latency |
| `/hardware/mem/{node}/{op}` | `faults/hardware.py` | Memory faults |
| `/hardware/disk/{node}/{op}` | `faults/hardware.py` | Disk faults |
| `/work_stats` | `main.py` | Transaction statistics page (HTML) |
| `/docs` | — | Swagger API documentation |

In practice, `/command/exec` is the most frequently used endpoint. The engine sends a shell command such as `blade create network loss` or a Java invocation, and the agent executes it locally.

### Injection Mechanisms

**ChaosBlade for operating-system faults**

The agent invokes a locally installed ChaosBlade binary. This mechanism covers CPU saturation, memory pressure, packet loss, latency, disk fill, and process suspension or termination.

```bash
/opt/chaosblade/blade create network loss --percent 30 --interface eth0
```

**DBChaos and Java programs for database-level faults**

The agent runs DBChaos cases or JDBC-based Java programs that connect to the database and induce faults from within the database layer. This mechanism requires JDK 21 and the corresponding JDBC driver. It covers connection storms, transaction rollback storms, stack overflow, I/O saturation, thread-pool exhaustion, and related conditions.

The Java fault catalog uses the following identifiers. The corresponding programs are located in `backend/agent/scripts/java/`.

| ID | Fault |
| --- | --- |
| 101 | I/O capacity saturation |
| 102 | Memory capacity saturation |
| 103 | CPU capacity saturation |
| 104 | Handle exhaustion |
| 105 | Stored procedure stack overflow |
| 106 | Function stack overflow |
| 107 | Transaction stack overflow |
| 108 | Recursive call stack overflow |
| 109 | Mass transaction rollback |
| 110 | `max_prepared_transaction` limit reached |
| 111 | Uncommitted transactions |
| 112 | Duplicate execution |
| 113 | Connection storm |
| 114 | Connection limit exceeded |
| 115 | Active thread-pool saturation |
| 116 | `max_dynamic_memory` limit reached |

See [backend/agent/scripts/java/readme.md](backend/agent/scripts/java/readme.md) for command examples.

### Installation

Perform the following steps on the database host.

1. Install the Python dependencies:

   ```bash
   pip3 install websockets fastapi uvicorn pandas Jinja2 pymser numpy matplotlib scipy pyyaml scikit-learn statsmodels ruptures
   ```

2. Install ChaosBlade 1.7.4:

   ```bash
   tar -xvzf chaosblade-1.7.4-linux-amd64.tar.gz -C /opt/
   ```

   For containerized injection, install the same ChaosBlade version inside the container and add the patched YAML and `blade-cg` wrapper described in [scripts/chaosblade/README.md](scripts/chaosblade/README.md).

3. Copy the `DBChaos` directory to `/opt/`.

4. Copy `backend/agent` to the target host and start the agent from its parent directory:

   ```bash
   sudo sh agent/agent_monitor.sh -p python3 check
   ```

   If the script reports `\r` character errors, convert the line endings first:

   ```bash
   sed -i 's/\r$//' agent/agent_monitor.sh
   ```

`agent_monitor.sh check` performs two functions:

1. **Process supervision.** It checks whether `main.py` is running and restarts it with `nohup` if necessary.
2. **Crontab keepalive.** It registers itself in crontab and runs every minute, so the agent recovers automatically after a host restart.

The agent listens on `0.0.0.0:8000` and writes logs to `agent/agent.log`.

### Operational Notes

| Issue | Resolution |
| --- | --- |
| The control host cannot reach the agent | Verify that port 8000 is open in the firewall or security group, confirm the process with `pgrep -f "python3.*main.py"`, and inspect `agent/agent.log` |
| Fault injection fails | Inspect `agent/agent.log`, verify the ChaosBlade and Java script paths, and confirm that the `interface` parameter matches a real network interface (`ip a`) |
| The agent process exits unexpectedly | Confirm that `agent_monitor.sh` is registered in crontab (`crontab -l`); the watchdog checks every minute and restarts the process |

## Web Console

In development, open <http://localhost:5173>. The navigation contains two groups:

- **Configuration**: Database, Workload, Fault Injection
- **Results**: Test Records, Comparison

A typical workflow is: configure the database, configure the workload, configure faults, start the test, observe live metrics, review the resilience score, and compare runs. First-time users can follow the built-in guided tours from the top-right corner of the console.

### Database Configuration

| Field | Description |
| --- | --- |
| Deployment type | Single-node or distributed; determines the available database types |
| Database type | Single-node: openGauss, GaussDB, MySQL, PostgreSQL. Distributed: OceanBase |
| Host and port | Connection address of the database under test |
| Username and password | Database credentials |

1. Select the deployment type. The console loads the supported database types.
2. Enter the connection details and select **Test Connection**. Once the status reports a successful connection, select **Save**.
3. Saving the configuration unlocks workload configuration.

### Workload Configuration

Three workload types are available from the page selector.

**BenchmarkSQL (TPC-C)**

| Parameter | Description |
| --- | --- |
| Warehouses | Number of TPC-C warehouses; determines data scale |
| Concurrency | Number of concurrent terminals |
| Duration | Run duration in seconds |
| Reload data | Recreates tables and reloads the dataset |

**SmallBank**

| Parameter | Description |
| --- | --- |
| Accounts | Total number of test accounts |
| Terminals | Number of concurrent connections |
| Duration | Run duration in seconds |
| Transaction weights | Execution ratio of the six transaction types; must total 100 |

The six transaction types and their default weights (5, 25, 20, 15, 15, 20) are Amalgamate, Balance, DepositChecking, SendPayment, TransactSavings, and WriteCheck.

**YCSB**

| Parameter | Description |
| --- | --- |
| Workload template | Built-in YCSB workload |
| Record count, operation count, threads | Data scale and concurrency |
| Duration | At least one of duration or operation count must be provided |
| Request distribution | `uniform`, `zipfian`, `latest`, and others |
| Read, write, insert, scan, update ratios | The five ratios must total 1.0 |

> A modified workload must be saved before a test can start.

### Fault Injection

**Orchestration modes**

| Mode | Use case |
| --- | --- |
| Sequential | Injects faults one at a time in list order; suited to incremental and single-fault experiments |
| Grouped | Injects a set of faults concurrently with a shared duration and start delay; suited to compound scenarios |

**Fault categories**

| Category | Faults |
| --- | --- |
| CPU | CPU load |
| Memory | Memory load |
| Network | Packet loss, latency, reordering, duplication, and corruption |
| Disk and I/O | File move, file removal, file append, permission change, and disk fill |
| Process | Process suspension and process termination |
| Database | Connection storm, connection exhaustion, thread-pool saturation, transaction stack overflow, mass rollback, SQL plan flip, read amplification, and long-transaction lock contention |

**Procedure**

1. **Select** one or more fault cards. Selected cards are highlighted and numbered.
2. **Add** the selection to the fault list. In grouped mode, two or more faults can be combined into a group with its own duration and start delay.
3. **Order** the list by dragging the handle on each card. The order determines injection order.
4. **Configure** parameters by selecting a card in the list. The parameter panel exposes values such as CPU percentage, packet-loss rate, network interface, delay, file path, process name, and rollback rate.
5. **Validate** the plan, optionally. The knowledge-graph check reports conflicting or duplicate faults within the same group. Validation is advisory and does not block execution.
6. **Start the test.** The engine creates and loads the schema, runs the workload, and injects faults in list order. A running test can be stopped at any time.

**Common parameters**

- `duration`: fault duration in seconds; `0` runs until the test is stopped manually.
- `interface`: network interface name, such as `eth0`; required for network faults.
- `cpu-percent` and `memory-percent`: utilization percentages from 0 to 100.
  - On a host deployment, `memory-percent` applies to the whole machine, based on `/proc/meminfo`.
  - On a container deployment with `containerName` configured, the engine adds `--channel nsexec --ns_target 1`, and the percentage is calculated against the container cgroup limit (`memory.limit_in_bytes`). For example, 90 in a 40 GiB container means 90% of 40 GiB.
  - Containerized injection requires ChaosBlade 1.7.4, the YAML patch for `ns_target`, and the `blade-cg` wrapper. Installation instructions are in [scripts/chaosblade/README.md](scripts/chaosblade/README.md). An unsupported version fails with `unknown flag: --ns_target`; the engine does not silently fall back to host-level semantics.
- `process-cmd`: target process name, such as `gaussdb`, `cm_server`, or `nginx`.
- `rollback-rate`: transaction rollback percentage from 0 to 100.
- `path` and `filepath`: target path for disk faults; the path must exist and be writable.

> High-impact faults such as process termination and OS restart are marked with `autoRecovery=1` in `backend/resbenchmark/config/<db>/*_config_chaosblade.xml`. The system performs recovery automatically after injection. Other faults require a manual recovery step or an engine-triggered recovery command after the test.

### Real-Time Monitoring

During a test, the Fault Injection page displays a live performance panel with:

- TPS and average latency over time;
- a status indicator for normal operation and active fault injection;
- pause and resume controls for chart refresh;
- a resilience triangle chart generated at the end of the run, marking the fault start, the performance trough, and recovery completion against a baseline reference.

### Test Records and Reports

The Test Records page lists historical runs and supports filtering by test ID, test name, and database type. Selecting **View Report** expands:

1. a resilience score radar chart;
2. a score table with dimension values, weights, and calculation notes, including a detailed formula view;
3. a resilience triangle chart showing throughput over time and the three key points;
4. a report preview containing the test profile, key resilience metrics, visual analysis, dimension scores, and a conclusion summary, with PDF export.

### Comparison

The Comparison page compares two or more completed runs:

1. Locate runs by test ID or database type in the history selector.
2. Add at least two runs to the comparison set.
3. Choose a comparison dimension:
   - database comparison: same fault, different databases;
   - workload comparison: same database, different workloads;
   - fault comparison: same database, different fault configurations.
4. Review grouped bar charts, overlapping radar charts, individual line charts, and overlaid TPS curves. Charts can be exported.

### Guided Tours

The console includes two interactive tours: an onboarding tour that covers the end-to-end workflow and a dedicated fault-injection tour that demonstrates orchestration modes, category selection, ordering, parameter editing, test execution, and live charts. Both can be replayed from the top-right corner.

## Resilience Scoring

ResBench derives its scores from the resilience triangle model. Unless noted otherwise, higher values indicate better resilience.

| Dimension | Meaning |
| --- | --- |
| Throughput score | Overall performance level during the resilience-triangle window, expressed as a percentage of baseline TPS |
| Latency score | Response-speed score; higher values indicate lower latency |
| Stability score | Weighted combination of throughput and latency jitter during the incident |
| Resistance score | Weighted combination of performance loss and time under degradation |
| Recovery score | Weighted combination of recovered performance and recovery duration |
| Adaptability score | Weighted moving average of the difference between consecutive runs and the original baseline |
| Volatility score | Inverse of performance-curve variance; higher values indicate a more stable curve |
| RTO score | Proportion of runs whose recovery time meets the target recovery time objective |
| Metric deviation | Weighted dispersion across repeated runs; lower values indicate greater consistency |
| Perturbation duration | Average time to recover across repeated runs; lower values indicate faster recovery |

## Configuration Reference

The `backend/resbenchmark/config/` directory contains XML configuration files read by the engine at runtime, relative to the working directory.

| Path | Purpose |
| --- | --- |
| `backend/resbenchmark/config/<db>/*_config_chaosblade.xml` | Connection settings and fault cases for each database (`db` is one of `gaussdb`, `mysql`, `oceanbase`, `opengauss`, or `postgresql`) |
| `backend/resbenchmark/config/fault-cases-generic.xml` | Generic fault cases |
| `backend/resbenchmark/config/smallbank-worker.xml`, `tpcc-worker.xml`, `real-worker.xml` | Workload phase definitions (`time`, `rate`, `weights`, `testSuite`) |
| `backend/resbenchmark/config/smallbank-transactionTypes.xml` | SmallBank transaction type declarations |
| `backend/resbenchmark/config/plugin.xml` | Benchmark plugin registration (`smallbank` → `SmallBankBenchmark`, `tpcc` → `TPCCBenchmark`) |

Each `<case>` in a `*_config_chaosblade.xml` file defines a group of injection and recovery commands. The key fields are:

- `<agents>`: agent addresses that execute the fault, for example `master:8000`;
- `<injection>` and `<recovery>`: injection and recovery commands, typically invoking `/opt/chaosblade/blade`;
- `<autoRecovery>`: `1` performs recovery automatically after injection; `0` requires an explicit recovery command.

> Do not run concurrent jobs against the same database or schema from multiple engine processes (for example, ports 8080 and 8081). `DROP TABLE` and `SELECT` operations can deadlock under lock contention. Use separate databases or execute jobs serially.

## Troubleshooting

**The development launcher fails to start.**

Verify that JDK 21 is installed (`java -version`), Maven is available (`mvn -v`), and Node.js is installed (`node -v`). On the first run, the frontend dependencies are installed automatically.

**The browser cannot reach the application.**

In development, use <http://localhost:5173>, not port 8090. Confirm that all three services started without errors, and allow time for Maven compilation and service startup.

**The History and Comparison pages report errors.**

The benchmark engine is not running. Check the engine process, confirm that port 8080 is not occupied by another process, and restart the development environment.

**A port is already in use.**

- Frontend: change `server.port` in `frontend/vite.config.js`.
- Manager: change `server.port` in `backend/res-manager/src/main/resources/application.properties`, and update the proxy target in `frontend/vite.config.js`.
- Engine: change the engine port in the launcher and update the engine address used by the manager.

**Detailed logs are required.**

Development logs are written to the terminal that started each service. Agent logs are written to `agent/agent.log` on the database host.

**The control host cannot reach the agent.**

Confirm that port 8000 is open on the agent host, verify the database address in the console, and check that the agent process is running (`pgrep -f "python3.*main.py"`). If it is not running, start it with `agent_monitor.sh check`.

**Fault injection fails.**

Verify that ChaosBlade and DBChaos are installed under `/opt` on the database host, that `/opt/chaosblade/blade` is executable, and that the network interface and process name parameters match the target host. Inspect `agent/agent.log` for details.

**No resilience score is produced after a test.**

Scoring is performed by Python and requires the scientific computing dependencies listed in [Prerequisites](#prerequisites). Missing packages cause score generation to fail.

**SmallBank transaction weights cannot be saved.**

The six weights must total exactly 100, and each weight must be an integer from 0 to 100.

## Documentation

- [Fault verification design](docs/fault-verification-design.md)
- [Adding a new fault](docs/how-to-add-new-fault.md)
- [Troubleshooting missing curves and zero scores](docs/troubleshooting-no-curve-and-zero-score.md)
- [Automation matrix](docs/automation-matrix.md)
- [Batch script usage](docs/batch-script-usage.md)
- [ChaosBlade setup](scripts/chaosblade/README.md)
- [Java fault scripts](backend/agent/scripts/java/readme.md)
- [Monitoring module deployment guide (Chinese)](监控模块部署指南-CentOS7.6.md)

Some documents are currently available in Chinese only.

## Contributing

Contributions are welcome. For significant changes, please open an issue to discuss the proposal before submitting a pull request. Follow the existing code style, keep changes focused, and include tests where appropriate.

## License

ResBench is released under the [MIT License](LICENSE).
