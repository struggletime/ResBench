# ResBench

**数据库韧性评测平台**

[English](README.md) | 简体中文

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE-APACHE)

## 目录

- [概述](#概述)
- [核心能力](#核心能力)
- [系统架构](#系统架构)
- [仓库结构](#仓库结构)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [Agent 部署](#agent-部署)
- [Web 控制台](#web-控制台)
- [韧性评分](#韧性评分)
- [配置文件](#配置文件)
- [故障排查](#故障排查)
- [文档](#文档)
- [致谢](#致谢)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

## 概述

ResBench 是一个开源的数据库韧性评测平台，用于评估数据库系统在不利条件下的表现。平台在运行接近生产形态的工作负载的同时注入操作系统层和数据库层故障，实时采集吞吐量与延迟，并基于韧性三角模型给出多维度的韧性评分。

平台面向数据库工程师、平台团队和研究人员，提供可复现、可横向比较的度量结果，用于回答两个核心问题：数据库在压力下如何退化，以及退化后多快能够恢复。ResBench 支持 openGauss、GaussDB、MySQL、PostgreSQL 和 OceanBase。

基准测试引擎派生自 [BenchBase](https://github.com/cmu-db/benchbase)，并针对韧性评测进行了较大幅度的修改和扩展。详情见[致谢](#致谢)。

## 核心能力

- **工作负载生成。** 内置 TPC-C（BenchmarkSQL）和 SmallBank 两类工作负载，可按数据规模、并发度、运行时长和事务比例进行配置。
- **分层故障注入。** 通过 ChaosBlade 注入操作系统层故障，通过 DBChaos 和基于 JDBC 的程序注入数据库层故障。
- **灵活的编排方式。** 故障既可以按顺序逐项注入，也可以打包为并发组，统一设置持续时间和启动延迟。可选的编排校验会基于知识图谱检查同组故障之间是否存在互斥或重复关系。
- **实时可观测性。** 运行过程中展示实时 TPS 和延迟曲线、当前故障状态，并支持暂停和恢复刷新。
- **可复现的评分。** 基于韧性三角模型输出十个维度的评分，包括吞吐量、延迟、稳定性、抵抗能力、恢复能力、适应能力、波动率、RTO 达标率、指标偏离度和不利扰动持续时间。
- **历史分析。** 持久化测试记录，生成可导出 PDF 的报告，并支持按数据库、工作负载和故障配置进行横向对比。
- **基于 Agent 的执行。** 由管理服务、基准测试引擎和部署在待测数据库侧的轻量 Agent 协同完成评测。

## 系统架构

ResBench 由四个组件组成：

| 组件 | 运行位置 | 默认端口 | 职责 |
| --- | --- | --- | --- |
| Web 控制台 | 开发机或管理服务所在主机 | 5173（开发）/ 8090（生产） | 配置、运行控制、实时图表、报告与对比 |
| 管理服务（`res-manager`） | 主控端 | 8090 | REST API、编排、历史记录、报告生成 |
| 基准测试引擎（`resbenchmark`） | 主控端 | 8080 | 工作负载生成、指标采集、故障调度、评分 |
| Agent | 待测数据库所在主机 | 8000 | 通过 ChaosBlade、DBChaos 或 JDBC 程序执行故障注入与恢复 |

数据流：

```text
Web 控制台 → 管理服务（8090） → 基准测试引擎（8080） → Agent（8000）
                                                    → ChaosBlade / DBChaos / JDBC
                                                    → 待测数据库
```

引擎通过 WebSocket 向 Agent 推送事务统计，并向 Web 控制台推送实时 TPS。

## 仓库结构

```text
ResBench/
├── frontend/                  Web 控制台（React 19 + Vite + Ant Design）
├── backend/
│   ├── resbenchmark/          基准测试引擎：工作负载、指标、故障调度
│   ├── res-manager/           Spring Boot 管理服务与 REST API
│   ├── resbenchmark-common/   公共库
│   └── agent/                 部署在数据库侧的 Python FastAPI Agent
├── scripts/                   批量编排、监控与 ChaosBlade 辅助脚本
├── docs/                      设计与运维文档
├── start-dev.ps1              Windows 开发环境启动脚本
├── start-dev.sh               Linux 与 macOS 开发环境启动脚本
└── README.md
```

## 环境要求

| 角色 | 要求 |
| --- | --- |
| 主控端 | JDK 21、Maven 3.6 及以上、Node.js 20.19+（或 22.12+）及 npm |
| 评分流程 | Python 3.9 及以上，以及 `pandas`、`numpy`、`scipy`、`matplotlib`、`scikit-learn`、`statsmodels`、`ruptures`、`pymser` |
| Agent 主机 | Python 3.9 及以上，以及 `fastapi`、`uvicorn`、`websockets`、`pandas`、`Jinja2`、`PyYAML`、`numpy`、`scipy`、`matplotlib`、`scikit-learn`、`statsmodels`、`ruptures`、`pymser` |
| 故障工具 | 操作系统层故障需要 ChaosBlade 1.7.4；数据库层故障需要 DBChaos、JDK 21 以及目标数据库的 JDBC 驱动 |

## 快速开始

### Windows

在仓库根目录打开 PowerShell：

```powershell
.\start-dev.ps1
```

### Linux 与 macOS

在仓库根目录执行：

```bash
chmod +x start-dev.sh
bash start-dev.sh
```

在 Windows 下，启动脚本会启动三个服务：

- 基准测试引擎，端口 8080
- 管理服务，端口 8090
- 前端开发服务器，端口 5173

在 Linux 与 macOS 下，启动脚本只启动基准测试引擎（8080）和管理服务（8090），前端需要单独启动：

```bash
cd frontend
npm install
npm run dev
```

待服务就绪后，在浏览器中访问 <http://localhost:5173>。

> 开发环境下，前端运行在 Vite 开发服务器（5173），并将 API 请求代理到 8090 端口的管理服务。启动脚本会先用 Maven 编译后端，再启动引擎（占用约 2 GB 堆内存），随后启动管理服务。各服务需要同时运行；引擎未启动时，历史记录和对比页面无法正常加载。

结束开发环境时，关闭各终端窗口，或在 Linux 与 macOS 下按 `Ctrl+C`。

## Agent 部署

Agent 是部署在**待测数据库所在主机**上的 Python 服务。它通过 HTTP 接收基准测试引擎的指令，并在本地通过 ChaosBlade、DBChaos 或 Java 程序执行故障注入。

```text
引擎（8080） → Agent（8000） → ChaosBlade / DBChaos / JDBC 程序 → 待测数据库
```

### 目录结构

```text
backend/agent/
├── main.py                      FastAPI 应用，监听 8000 端口
├── agent_monitor.sh             看门狗与 crontab 保活脚本
├── pom.xml                      Maven 打包配置
├── assembly/package.xml         打包描述文件
├── faults/
│   ├── process.py               /process/*   进程类故障
│   ├── network.py               /network/*   网络类故障
│   ├── hardware.py              /hardware/*  CPU、内存、磁盘类故障
│   ├── command.py               /command/*   通用命令执行
│   ├── file.py                  /file/*      文件类故障
│   └── facilities.py            /facilities/* 设施类故障
├── dependencies/
│   └── fault_models.py          公共模型与 Token 鉴权
├── utils/
│   └── tools.py                 工具函数，包括 shell 命令执行
├── scripts/
│   ├── java/                    数据库层故障程序
│   ├── cm_fault.sh              集群管理组件故障（GaussDB）
│   ├── file_chmod_fault.sh      文件权限修改
│   ├── file_mv_fault.sh         文件移动与删除
│   ├── process_hang.sh          进程挂起
│   ├── process_resume.sh        进程恢复
│   ├── process_shutdown.sh      进程终止
│   └── process_start_oceanbase.sh
├── DBChaos/                     数据库层故障注入工具
└── templates/                   work statistics 页面使用的 Jinja2 模板
```

### 核心接口

Agent 启动后，可通过 `http://<agent-host>:8000/docs` 查看交互式 API 文档。

| 接口 | 模块 | 用途 |
| --- | --- | --- |
| `/command/exec` | `faults/command.py` | 通用命令执行，是主要的集成入口 |
| `/process/instance/{os}/{dbtype}/{inst}/{op}` | `faults/process.py` | 启动或停止数据库实例（`op=start` 或 `stop`） |
| `/network/{node}/{op}` | `faults/network.py` | 丢包、延迟等网络类故障 |
| `/hardware/mem/{node}/{op}` | `faults/hardware.py` | 内存类故障 |
| `/hardware/disk/{node}/{op}` | `faults/hardware.py` | 磁盘类故障 |
| `/work_stats` | `main.py` | 事务统计页面（HTML） |
| `/docs` | — | Swagger API 文档 |

实际使用中，`/command/exec` 是最常用的接口。引擎将 `blade create network loss` 或 Java 调用等 shell 命令发送给 Agent，由 Agent 在本地执行。

### 注入方式

**ChaosBlade：操作系统层故障**

Agent 调用本地安装的 ChaosBlade 可执行文件。该方式覆盖 CPU 打满、内存压力、网络丢包与延迟、磁盘填满、进程挂起与终止等场景。

```bash
/opt/chaosblade/blade create network loss --percent 30 --interface eth0
```

**DBChaos 与 Java 程序：数据库层故障**

Agent 执行 DBChaos 用例或基于 JDBC 的 Java 程序，直接连接数据库，从数据库内部制造故障。该方式需要 JDK 21 和目标数据库对应的 JDBC 驱动，覆盖连接风暴、事务回滚风暴、栈溢出、I/O 饱和、线程池耗尽等场景。

Java 故障目录使用以下标识，对应程序位于 `backend/agent/scripts/java/`。

| 故障 ID | 故障名称 |
| --- | --- |
| 101 | I/O 容量上限 |
| 102 | 内存容量上限 |
| 103 | CPU 容量上限 |
| 104 | 句柄耗尽 |
| 105 | 存储过程爆栈 |
| 106 | 函数爆栈 |
| 107 | 事务爆栈 |
| 108 | 递归调用爆栈 |
| 109 | 大量事务回滚 |
| 110 | `max_prepared_transaction` 上限 |
| 111 | 事务不提交 |
| 112 | 重复执行 |
| 113 | 突发大量连接请求 |
| 114 | 连接数超过限制 |
| 115 | 活跃线程池上限 |
| 116 | `max_dynamic_memory` 上限 |

命令示例见 [backend/agent/scripts/java/readme.md](backend/agent/scripts/java/readme.md)。

### 安装步骤

以下操作均在待测数据库所在主机上执行。

1. 安装 Python 依赖：

   ```bash
   pip3 install websockets fastapi uvicorn pandas Jinja2 pymser numpy matplotlib scipy pyyaml scikit-learn statsmodels ruptures
   ```

2. 安装 ChaosBlade 1.7.4：

   ```bash
   tar -xvzf chaosblade-1.7.4-linux-amd64.tar.gz -C /opt/
   ```

   如需在容器内注入，需要在容器中安装相同版本的 ChaosBlade，并补充经过 `ns_target` 补丁的 YAML 与 `blade-cg` 包装脚本，具体步骤见 [scripts/chaosblade/README.md](scripts/chaosblade/README.md)。

3. 将 `DBChaos` 目录复制到 `/opt/`。

4. 将 `backend/agent` 复制到目标主机，并在其上级目录启动 Agent：

   ```bash
   sudo sh agent/agent_monitor.sh -p python3 check
   ```

   如果脚本报 `\r` 字符错误，先转换行尾：

   ```bash
   sed -i 's/\r$//' agent/agent_monitor.sh
   ```

`agent_monitor.sh check` 完成两项工作：

1. **进程守护。** 检查 `main.py` 是否存活，异常退出时通过 `nohup` 重新拉起。
2. **Crontab 保活。** 将自身注册到 crontab，每分钟执行一次检查，确保主机重启后 Agent 能够自动恢复。

Agent 监听 `0.0.0.0:8000`，日志写入 `agent/agent.log`。

### 运维提示

| 问题 | 处理方式 |
| --- | --- |
| 主控端无法连接 Agent | 确认防火墙或安全组已开放 8000 端口，使用 `pgrep -f "python3.*main.py"` 确认进程存活，并查看 `agent/agent.log` |
| 故障注入失败 | 查看 `agent/agent.log`，确认 ChaosBlade 与 Java 脚本路径正确，并确认 `interface` 参数与真实网卡名一致（可通过 `ip a` 查询） |
| Agent 进程异常退出 | 确认 `agent_monitor.sh` 已注册到 crontab（`crontab -l`）；看门狗每分钟检查并自动拉起进程 |

## Web 控制台

开发环境下访问 <http://localhost:5173>。左侧导航分为两组：

- **配置**：数据库配置、工作负载配置、不利因素注入
- **结果**：测试记录、对比测试结果

典型工作流为：配置数据库 → 配置工作负载 → 配置不利因素 → 开始测试 → 实时观测 → 查看韧性评分 → 对比多次测试。首次使用时，可通过右上角的内置引导教程完成一遍完整流程。

### 数据库配置

| 字段 | 说明 |
| --- | --- |
| 数据库类型 | openGauss、GaussDB、MySQL、PostgreSQL、OceanBase |
| 主机与端口 | 待测数据库的连接地址 |
| 用户名与密码 | 数据库登录凭证 |

1. 选择数据库类型，控制台会加载对应的连接字段。
2. 填写连接信息并选择**测试连接**。状态显示连接成功后，选择**保存**。
3. 保存配置后，方可进入工作负载配置。

### 工作负载配置

页面支持两类工作负载。

**BenchmarkSQL（TPC-C）**

| 参数 | 说明 |
| --- | --- |
| 仓数 | TPC-C 仓库数量，决定数据规模 |
| 并发数 | 并发终端数 |
| 持续时间 | 运行时长，单位为秒 |
| 重新加载数据 | 重新建表并装载数据集 |

**SmallBank**

| 参数 | 说明 |
| --- | --- |
| 账户数量 | 测试账户总数 |
| 并发终端数 | 并发连接数 |
| 持续时间 | 运行时长，单位为秒 |
| 事务权重 | 六类事务的执行比例，总和必须为 100 |

六类事务及默认权重（5、25、20、15、15、20）分别为 Amalgamate、Balance、DepositChecking、SendPayment、TransactSavings 和 WriteCheck。

> 修改工作负载后必须先保存，才能开始测试。

### 不利因素注入

**编排模式**

| 模式 | 适用场景 |
| --- | --- |
| 单项模式 | 按列表顺序逐项注入，适合单故障实验和增量追加 |
| 组合模式 | 将多个故障打包为一组并发注入，可统一设置持续时间和启动延迟，适合复合场景 |

**故障分类**

| 分类 | 故障 |
| --- | --- |
| CPU | CPU 负载 |
| 内存 | 内存负载 |
| 网络 | 网络丢包、网络延迟、网络包乱序、网络包重复、网络包损坏 |
| 磁盘与 I/O | 文件移动、文件删除、文件追加、文件权限修改、磁盘填满 |
| 进程 | 进程挂起、进程终止 |
| 数据库 | 连接风暴、连接数耗尽、线程池饱和、事务栈溢出、大量事务回滚、执行计划翻转、读取放大、长事务持锁 |

**操作流程**

1. **选择**：点选故障卡片，可多选；选中后高亮并标号。
2. **添加**：将所选故障加入下方故障列表。组合模式下，可将两个及以上故障创建为组合，并设置组合的持续时间和启动延迟。
3. **排序**：拖拽卡片左侧手柄调整顺序，顺序即注入次序。
4. **配参**：选中列表中的卡片，在参数面板中修改具体数值，例如 CPU 占用率、丢包率、网卡名、延迟时间、文件路径、进程名和回滚比例。
5. **校验编排**（可选）：基于知识图谱检查同组并发注入的故障之间是否存在互斥或重复关系。校验结果仅供参考，不阻断测试。
6. **开始测试**：引擎先建表装载，再运行工作负载，并按列表顺序触发故障。运行中的测试可随时中止。

**常用参数**

- `duration`：故障持续时间，单位为秒；`0` 表示持续到手动停止。
- `interface`：网卡接口名，例如 `eth0`；网络类故障必填。
- `cpu-percent` 与 `memory-percent`：CPU 或内存占用百分比，取值范围为 0 到 100。
  - 宿主部署下，`memory-percent` 按整机口径计算，读取 `/proc/meminfo`。
  - 配置了 `containerName` 的容器部署下，引擎会追加 `--channel nsexec --ns_target 1`，按容器 cgroup 的 `memory.limit_in_bytes` 计算。例如，40 GiB 上限的容器配置 90，表示按 40 GiB 的 90% 计算。
  - 容器内注入需要 ChaosBlade 1.7.4、经过 `ns_target` 补丁的 YAML 以及 `blade-cg` 包装脚本，安装步骤见 [scripts/chaosblade/README.md](scripts/chaosblade/README.md)。版本不匹配时会直接报 `unknown flag: --ns_target`，不会静默回退到整机口径。
- `process-cmd`：目标进程名，例如 `gaussdb`、`cm_server` 或 `nginx`。
- `rollback-rate`：事务回滚比例，取值范围为 0 到 100。
- `path` 与 `filepath`：磁盘类故障的目标路径，路径必须存在且可写。

> 进程终止、操作系统重启等高危故障在 `backend/resbenchmark/config/<db>/*_config_chaosblade.xml` 中标记为 `autoRecovery=1`，注入后由系统自动恢复。其余故障需要手动恢复，或由引擎在测试结束后触发恢复命令。

### 实时监控

测试运行期间，不利因素注入页面展示实时性能面板，包括：

- 实时 TPS 与平均延迟曲线；
- 正常运行与故障注入中的状态指示；
- 图表刷新的暂停与恢复控制；
- 测试结束后生成的韧性三角分析图，标注故障开始、性能谷底和恢复完成三个关键点，并叠加基线参考。

### 测试记录与报告

测试记录页面列出历史测试，支持按测试 ID、测试名称和数据库类型筛选。选择**查看报告**后，页面展开以下内容：

1. 韧性评分雷达图；
2. 评分表，包含各维度得分、权重和计算口径说明，并支持查看详细公式；
3. 韧性三角分析图，展示吞吐量随时间的变化和三个关键节点；
4. 报告预览，包含测试档案、关键韧性指标、可视化分析、维度评分明细和结论摘要，并支持导出 PDF。

### 对比测试

对比页面支持将两次及以上已完成的测试进行横向比较：

1. 在历史记录选择器中按测试 ID 或数据库类型查找测试。
2. 将至少两条记录加入对比集合。
3. 选择对比维度：
   - 数据库对比：相同故障、不同数据库；
   - 负载对比：相同数据库、不同工作负载；
   - 故障对比：相同数据库、不同故障配置。
4. 查看分组条形图、叠加雷达图、独立折线图和叠加 TPS 曲线，图表支持导出。

### 引导教程

控制台内置两段交互式教程：一段覆盖端到端工作流的入门教程，一段演示编排模式选择、分类切换、故障排序、参数编辑、测试执行和实时图表的故障注入教程。两段教程均可从右上角随时回放。

## 韧性评分

ResBench 基于韧性三角模型计算评分。除特别说明外，分值越高表示韧性越好。

| 维度 | 含义 |
| --- | --- |
| 吞吐量评分 | 韧性三角区间内的整体性能水平，以相对基线 TPS 的百分比表示 |
| 延迟评分 | 响应速度评分，分值越高表示延迟越低 |
| 稳定性评分 | 故障期间吞吐量抖动与延迟抖动的加权组合 |
| 抵抗能力评分 | 退化阶段性能损失与退化持续时间的加权组合 |
| 恢复能力评分 | 恢复阶段恢复后的性能与恢复时长的加权组合 |
| 适应能力评分 | 连续多次测试结果与原始基线差值的加权移动平均 |
| 波动率评分 | 性能曲线方差的倒数，分值越高表示曲线越稳定 |
| RTO 评分 | 实际恢复时间满足目标恢复时间（RTO）的测试比例 |
| 指标偏离度 | 多次测试结果之间的加权离散程度，越低表示一致性越好 |
| 不利扰动持续时间 | 多次测试中从不利条件恢复的平均时长，越短表示恢复越快 |

## 配置文件

`backend/resbenchmark/config/` 目录存放引擎运行时读取的 XML 配置文件，路径相对于工作目录。

| 路径 | 用途 |
| --- | --- |
| `backend/resbenchmark/config/<db>/*_config_chaosblade.xml` | 各数据库的连接信息与故障用例定义（`db` 为 `gaussdb`、`mysql`、`oceanbase`、`opengauss` 或 `postgresql`） |
| `backend/resbenchmark/config/fault-cases-generic.xml` | 通用故障用例 |
| `backend/resbenchmark/config/smallbank-worker.xml`、`tpcc-worker.xml`、`real-worker.xml` | 工作负载阶段定义（`time`、`rate`、`weights`、`testSuite`） |
| `backend/resbenchmark/config/smallbank-transactionTypes.xml` | SmallBank 事务类型声明 |
| `backend/resbenchmark/config/plugin.xml` | 基准插件注册（`smallbank` → `SmallBankBenchmark`，`tpcc` → `TPCCBenchmark`） |

`*_config_chaosblade.xml` 中的每个 `<case>` 定义一组故障注入与恢复命令，关键字段如下：

- `<agents>`：执行该故障的 Agent 地址，例如 `master:8000`；
- `<injection>` 与 `<recovery>`：注入与恢复命令，通常调用 `/opt/chaosblade/blade`；
- `<autoRecovery>`：`1` 表示注入后自动恢复；`0` 表示需要显式执行恢复命令。

> 不要使用多个引擎进程（例如 8080 和 8081 端口）并发访问同一数据库或 schema。`DROP TABLE` 与 `SELECT` 在锁冲突下可能死锁。请使用不同数据库，或串行执行任务。

## 故障排查

**开发环境启动失败。**

确认已安装 JDK 21（`java -version`）、Maven（`mvn -v`）和 Node.js（`node -v`）。首次启动时，前端依赖会自动安装。

**浏览器无法访问应用。**

开发环境下访问地址为 <http://localhost:5173>，而非 8090 端口。确认三个服务均已成功启动且没有报错，并预留足够时间等待 Maven 编译和服务启动。

**历史记录与对比页面报错。**

基准测试引擎未正常运行。检查引擎进程，确认 8080 端口未被其他程序占用，然后重启开发环境。

**端口已被占用。**

- 前端：修改 `frontend/vite.config.js` 中的 `server.port`。
- 管理服务：修改 `backend/res-manager/src/main/resources/application.properties` 中的 `server.port`，并同步修改 `frontend/vite.config.js` 中的代理目标地址。
- 引擎：修改启动脚本中的引擎端口，并同步修改管理服务中配置的引擎地址。

**需要查看详细日志。**

开发环境下，日志输出在启动各服务的终端中。Agent 日志位于数据库主机的 `agent/agent.log`。

**主控端无法连接 Agent。**

确认 Agent 所在主机的 8000 端口已开放，核对控制台中填写的数据库地址，并确认 Agent 进程存活（`pgrep -f "python3.*main.py"`）。如果进程不存在，执行 `agent_monitor.sh check` 重新拉起。

**故障注入执行失败。**

确认 ChaosBlade 与 DBChaos 已安装在数据库主机的 `/opt` 目录，`/opt/chaosblade/blade` 具有可执行权限，网络类故障的 `interface` 参数与真实网卡名一致，进程类故障的 `process-cmd` 与实际进程名一致。详细信息请查看 `agent/agent.log`。

**测试结束后没有生成韧性评分。**

评分由 Python 完成，需要安装[环境要求](#环境要求)中列出的科学计算依赖。缺少依赖会导致评分生成失败。

**SmallBank 事务权重无法保存。**

六类事务权重之和必须等于 100，且每项权重必须是 0 到 100 的整数。

## 文档

- [故障验证设计](docs/fault-verification-design.md)
- [新增故障类型](docs/how-to-add-new-fault.md)
- [无曲线与零评分排查](docs/troubleshooting-no-curve-and-zero-score.md)
- [自动化矩阵](docs/automation-matrix.md)
- [批量脚本使用说明](docs/batch-script-usage.md)
- [ChaosBlade 配置](scripts/chaosblade/README.md)
- [Java 故障脚本](backend/agent/scripts/java/readme.md)

部分文档目前仅提供中文版本。

## 致谢

ResBench 的基准测试引擎派生自 [BenchBase](https://github.com/cmu-db/benchbase)，后者是卡内基梅隆大学数据库组维护的 [OLTPBenchmark Project](https://github.com/oltpbenchmark/oltpbench) 的分支。`backend/resbenchmark` 以及 `backend/resbenchmark-common` 中的部分代码基于 BenchBase 源码修改而来。

上游代码在本项目中经过了较大幅度的修改：包名由 `com.oltpbenchmark` 改为 `com.resbench`，并新增或重写了故障注入、编排、监控、评分和 Web 控制台等模块。派生源文件中保留了原始的版权声明和 Apache License 2.0 许可头。

BenchBase 与 OLTPBench 均采用 [Apache License, Version 2.0](LICENSE-APACHE) 许可。必需的归属声明见 [NOTICE](NOTICE)。

如果在学术工作中使用 ResBench，建议同时引用 OLTPBench 原始论文：

> D. E. Difallah, A. Pavlo, C. Curino, and P. Cudré-Mauroux, "OLTP-Bench: An Extensible Testbed for Benchmarking Relational Databases," PVLDB, vol. 7, no. 4, pp. 277–288, 2013.

## 贡献指南

欢迎提交贡献。对于较大的改动，请先通过 Issue 讨论方案，再提交 Pull Request。请遵循现有代码风格，保持改动聚焦，并在适当场景补充测试。

## 许可证

ResBench 原始代码以 [MIT License](LICENSE) 发布。派生自 BenchBase 的部分仍遵循 [Apache License, Version 2.0](LICENSE-APACHE)，其原始版权与许可声明保留在相应源文件中。详情见 [NOTICE](NOTICE)。
