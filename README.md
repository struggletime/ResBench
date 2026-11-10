# ResBench - 数据库韧性测试平台 v1.0

ResBench 是一个面向数据库的韧性测评平台，通过在压力测试过程中注入各类不利因素（CPU/网络/磁盘/进程/数据库层故障），观测数据库在逆境下的吞吐量、延迟、恢复能力等表现，并基于韧性三角模型给出多维度的韧性评分。

测试用，其他人请忽略

| dbType     | username  | password     |
| :--------- | :-------- | :----------- |
| opengauss  | test1230  | YOUR_PASSWORD |
| gaussdb    | test1230  | YOUR_PASSWORD |
| oceanbase  | root\@sys | YOUR_PASSWORD |
| mysql      | root      | YOUR_PASSWORD |
| postgresql | postgres  | YOUR_PASSWORD |

## 平台架构

平台由三部分组成：

| 组件                              | 运行位置                      | 端口                | 作用                                                            |
| ------------------------------- | ------------------------- | ----------------- | ------------------------------------------------------------- |
| 主控端（Manager + Benchmark Engine） | 操作员机器                     | 8090 / 8080       | Manager 提供 Web 界面与 REST API（8090）；Engine 生成负载、采集指标、调度故障（8080） |
| Agent                           | 待测数据库所在服务器                | 8000              | Python FastAPI 服务，接收主控端指令，通过 ChaosBlade/DBChaos 执行故障注入与恢复     |
| Web 前端                          | 开发环境独立运行 / 生产随 Manager 部署 | 开发 5173 / 生产 8090 | 浏览器访问完成配置、测试、查看结果与对比                                          |

数据流：Web 前端 → Manager(8090) → Benchmark Engine(8080) → Agent(8000) → ChaosBlade → 待测数据库。Engine 同时通过 WebSocket 向 Agent 推送事务统计、向 Web 前端推送实时 TPS。

## 项目目录结构

```
Resbench/
├── frontend/              # 前端（React + Vite + Ant Design）
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── vite.config.js
├── backend/               # 后端（Java + Python）
│   ├── resbenchmark/      # 基准测试引擎（核心，生成负载、采集指标、调度故障）
│   ├── res-manager/       # 管理后台（Spring Boot，提供 REST API）
│   ├── resbenchmark-common/  # 公共库
│   ├── agent/             # Python Agent（部署在待测数据库服务器上）
│   ├── FaultInjection/    # 故障注入相关
│   ├── pom.xml
│   └── ...
├── start-dev.ps1          # Windows 开发环境启动脚本
├── start-dev.sh           # Linux/Mac 开发环境启动脚本
└── README.md
```

# 一、安装前准备

软件要求：

1. Java 21 运行环境 + Maven 3.6+
2. Node.js 16+ + pnpm 或 npm
3. Python 3.9+ 以及以下 Python 依赖库（用于测试结束后计算韧性评分）：
   pandas, scipy, numpy, matplotlib, scikit-learn, statsmodels, ruptures, pymser

# 二、启动开发环境

【Windows 系统】
在项目根目录下打开 PowerShell，执行：

```powershell
.\start-dev.ps1
```

脚本会自动启动三个服务：

- Benchmark 引擎（端口 8080）
- 管理服务（端口 8090）
- 前端开发服务器（端口 5173）

等待启动完成后，打开浏览器访问 <http://localhost:5173>

【Linux / macOS 系统】
打开终端，进入项目根目录，输入：

```shell
bash start-dev.sh
```

等待启动完成后，打开浏览器访问 <http://localhost:5173>

注意：`start-dev.sh` 需要执行权限，如果没有，先执行：

```shell
chmod +x start-dev.sh
```

如需结束，关闭所有终端窗口即可（Linux 下可按 Ctrl+C）。

> 说明：
>
> - 开发环境下前端通过 Vite 开发服务器（5173 端口）运行，API 请求代理到 8090 端口的管理服务
> - 启动脚本会先用 Maven 编译后端，再启动 Engine（占用约 2GB 堆内存），然后启动 Manager，最后启动前端
> - 两个后端进程缺一不可——Engine 缺失会导致"历史记录""对比"页面报错

# 三、Agent 详解与部署

Agent 是部署在**待测数据库所在服务器**上的 Python 服务，它是主控端和故障注入工具之间的"执行者"。主控端（Engine，8080 端口）通过 HTTP 发指令给 Agent，Agent 再调用 ChaosBlade 或 Java 脚本在本地执行具体的故障注入。

数据流：`Engine(8080) → Agent(8000) → ChaosBlade / Java脚本 → 待测数据库`

## 3.1 Agent 目录结构

```
backend/agent/
├── main.py                 # 入口，FastAPI 应用，监听 8000 端口
├── agent_monitor.sh        # 看门狗脚本（自动拉起 + crontab 保活）
├── pom.xml                 # Maven 打包配置
├── assembly/package.xml    # 打包产物描述
│
├── faults/                 # 故障注入 API 路由（按类别分模块）
│   ├── process.py          # /process/*  进程类故障（启停数据库实例）
│   ├── network.py          # /network/*  网络类故障
│   ├── hardware.py         # /hardware/* CPU/内存/磁盘类故障
│   ├── command.py          # /command/*  通用命令执行（最常用）
│   ├── file.py             # 文件类（占位）
│   └── facilities.py       # 设施类（占位）
│
├── dependencies/           # 公共依赖
│   └── fault_models.py     # 通用参数、Token 鉴权
│
├── utils/
│   └── tools.py            # 工具函数（exec 执行 shell 命令等）
│
├── scripts/                # 故障注入脚本
│   ├── java/               # 数据库层故障（Java 21+ 编写，16 种）
│   ├── cm_fault.sh         # CM 组件故障（GaussDB 用）
│   ├── file_chmod_fault.sh # 文件权限修改
│   ├── file_mv_fault.sh    # 文件移动/删除
│   ├── process_hang.sh     # 进程挂起
│   ├── process_resume.sh   # 进程恢复
│   ├── process_shutdown.sh # 进程终止
│   └── process_start_oceanbase.sh
│
├── DBChaos-20260610/       # DBChaos 工具（数据库层故障注入）
│   └── resources/registry/ # 故障用例注册（TSV 表格）
│
└── templates/              # Jinja2 模板（work_stats 监控页面）
```

## 3.2 核心 API 接口

Agent 基于 FastAPI 构建，启动后可通过 `http://<agent-ip>:8000/docs` 查看 Swagger 在线文档。

| 接口前缀                                          | 模块                   | 用途                                   |
| --------------------------------------------- | -------------------- | ------------------------------------ |
| `/command/exec`                               | `faults/command.py`  | **通用命令执行（最核心）** — 主控端直接下发 shell 命令执行 |
| `/process/instance/{os}/{dbtype}/{inst}/{op}` | `faults/process.py`  | 启停数据库实例（op=start/stop）               |
| `/network/{node}/{op}`                        | `faults/network.py`  | 网络类故障（丢包、延迟等）                        |
| `/hardware/mem/{node}/{op}`                   | `faults/hardware.py` | 内存类故障                                |
| `/hardware/disk/{node}/{op}`                  | `faults/hardware.py` | 磁盘类故障                                |
| `/work_stats`                                 | `main.py`            | 事务统计监控页面（HTML）                       |
| `/docs`                                       | -                    | Swagger API 文档                       |

> 💡 实际使用中，**`/command/exec`** **接口是最常用的**——主控端直接把要执行的命令（比如 `blade create network loss` 或 `java -cp ... MaxIOTest`）通过 POST 发给 Agent，Agent 直接在本地 shell 执行。

## 3.3 故障注入的两种方式

### 方式一：ChaosBlade（OS 层故障）

Agent 调用本地安装的 ChaosBlade 工具执行系统级故障。

适用于：CPU 打满、内存打满、网络丢包/延迟、磁盘填满、进程挂起/终止等**操作系统层面**的故障。

示例：

```bash
/opt/chaosblade/blade create network loss --percent 30 --interface eth0
```

### 方式二：Java 脚本（数据库层故障）

Agent 执行 Java 程序直接连接数据库，从内部制造故障。需要 Java 21+ 和对应数据库的 JDBC 驱动。

适用于：连接风暴、事务回滚风暴、栈溢出、IO 饱和、线程池耗尽等**数据库内部**的故障。

Java 故障脚本位于 `backend/agent/scripts/java/`，共 16 种故障 ID（101\~116）：

| 故障ID | 故障名称                          | Java 类                                      |
| ---- | ----------------------------- | ------------------------------------------- |
| 101  | IO 容量上限                       | `MaxIOTest`                                 |
| 102  | 内存容量上限                        | `MaxMemoryTest`                             |
| 103  | CPU 容量上限                      | `MaxCPUTest`                                |
| 104  | 句柄耗尽                          | -                                           |
| 105  | 存储过程爆栈                        | `StackOverflowTest -type proc_recurse`      |
| 106  | 函数爆栈                          | `StackOverflowTest -type func_recurse`      |
| 107  | 事务爆栈                          | `StackOverflowTest -type trans_recurse`     |
| 108  | 递归调用爆栈                        | `StackOverflowTest -type sql_depth`         |
| 109  | 大量事务回滚                        | `MassiveRollbackTest`                       |
| 110  | max\_prepared\_transaction 上限 | `UncommittedTransactionFaultInject`         |
| 111  | 事务不提交                         | `MaxPreparedTransactionFaultInject`         |
| 112  | 重复执行                          | `DuplicateTransactionFaultInject`           |
| 113  | 突发大量连接请求                      | `MaxConnectionTest -mode conn_storm`        |
| 114  | 连接数超过限制                       | `MaxConnectionTest -mode conn_exhaustion`   |
| 115  | 活跃线程池上限                       | `MaxConnectionTest -mode thread_saturation` |
| 116  | max\_dynamic\_memory 上限       | -                                           |

详细用法见 [scripts/java/readme.md](backend/agent/scripts/java/readme.md)。

## 3.4 部署与启动

以下操作**均在待测数据库所在服务器上执行**。

### 1. 环境依赖

Python 3.9+ 以及依赖库：

```
pip3 install websockets fastapi uvicorn pandas Jinja2 pymser numpy matplotlib scipy pyyaml scikit-learn statsmodels ruptures
```

### 2. ChaosBlade

- 下载地址：<https://github.com/chaosblade-io/chaosblade/releases/download/v1.7.4/chaosblade-1.7.4-linux-amd64.tar.gz>
- 解压缩到 `/opt` 目录：

```shell
tar -xvzf chaosblade-1.7.4-linux-amd64.tar.gz -C /opt/
```

- 进容器注入（`containerName` 非空）时还要在容器内装同一版本，并额外补 yaml 与
  `blade-cg` 包装脚本，见 [scripts/chaosblade/README.md](scripts/chaosblade/README.md)。

### 3. DBChaos

将 `DBChaos` 文件夹复制到 `/opt/` 目录下即可。

### 4. 启动 Agent

将 `backend/agent` 文件夹复制到目标服务器，然后执行：

```shell
# 在 agent 上级目录执行
sudo sh agent/agent_monitor.sh -p python3 check
# 如果报错（\r 字符问题），先执行：
sed -i 's/\r$//' agent/agent_monitor.sh
```

`agent_monitor.sh check` 会做两件事：

1. **自动拉起**：检查 `main.py` 进程是否存活，挂了就 `nohup` 重启；
2. **Crontab 保活**：把自身注册到 crontab，**每分钟执行一次 check**，确保就算机器重启了 Agent 也能自动起来。

Agent 启动后监听 `0.0.0.0:8000`，日志写入 `agent/agent.log`。

## 3.5 排错指南

| 问题           | 排查方法                                                                                                       |
| ------------ | ---------------------------------------------------------------------------------------------------------- |
| 主控端连不上 Agent | 1. 检查 8000 端口防火墙/安全组是否开放；2. `pgrep -f "python3.*main.py"` 确认进程存活；3. 查看 `agent/agent.log`                   |
| 故障注入执行失败     | 1. 查看 Agent 日志 `agent/agent.log`；2. 确认 ChaosBlade/Java 脚本路径正确；3. 网络类故障检查 `interface` 参数是否为真实网卡名（`ip a` 查询） |
| Agent 进程经常挂  | 确认 `agent_monitor.sh` 已注册到 crontab（`crontab -l` 查看），它会每分钟检查并自动拉起                                           |

# 四、Web 界面使用指南

开发环境下浏览器访问 `http://localhost:5173`，左侧导航分为两组：

- **配置**：数据库配置 / 工作负载配置 / 不利因素注入
- **结果**：测试记录 / 对比测试结果

典型工作流：**配置数据库 → 配置工作负载 → 配置不利因素 → 开始测试 → 实时观测 → 查看历史评分 → 对比多次测试**。首次使用可点击页面右上角的「新手教程」按钮走完一遍引导。

## 4.1 数据库配置

| 字段         | 说明                                                         |
| ---------- | ---------------------------------------------------------- |
| 部署类型       | 单节点数据库 / 分布式数据库（影响可选的数据库类型）                                |
| 数据库类型      | 单节点：openGauss / GaussDB / MySQL / PostgreSQL；分布式：OceanBase |
| IP 地址 / 端口 | 待测数据库的连接地址                                                 |
| 用户名 / 密码   | 数据库登录凭证                                                    |

操作流程：

1. 选择部署类型，系统会自动加载该模式下支持的数据库类型列表；
2. 填写连接信息，先点「测试数据库连接」，状态显示「已连接」/「连接成功」后再点「保存」；
3. 保存成功后才能进入下一步工作负载配置。

## 4.2 工作负载配置

支持三种工作负载，按页面下拉切换：

### 4.2.1 BenchmarkSQL（TPC-C）

| 参数               | 说明                |
| ---------------- | ----------------- |
| 仓数（warehouses）   | TPC-C 仓库数量，决定数据规模 |
| 并发数（concurrency） | 并发终端数             |
| 持续时间（duration）   | 单位秒               |
| 重新加载数据           | 重新建表并装载测试数据       |

### 4.2.2 SmallBank

| 参数                | 说明                       |
| ----------------- | ------------------------ |
| 账户数量（accountsNum） | 测试账户总数                   |
| 并发终端数（terminals）  | 并发连接数                    |
| 持续时间（duration）    | 单位秒                      |
| 事务权重配置            | 6 类事务的执行比例，**总和必须为 100** |

6 类事务及默认权重（5,25,20,15,15,20）：

- Amalgamate（合并账户）
- Balance（余额查询）
- DepositChecking（支票存款）
- SendPayment（付款转账）
- TransactSavings（储蓄交易）
- WriteCheck（写支票）

### 4.2.3 YCSB

| 参数              | 说明                           |
| --------------- | ---------------------------- |
| 工作负载类型          | YCSB 内置负载模板                  |
| 记录数 / 操作数 / 线程数 | 数据规模与并发度                     |
| 持续时间（秒）         | 与"操作数"至少填一项                  |
| 请求分布            | uniform / zipfian / latest 等 |
| 读/写/插入/扫描/更新比例  | 5 项比例之和必须为 1.0               |

> 工作负载修改后必须先点「保存」才能开始测试，否则会提示"工作负载已修改，请先保存后再开始测试"。

## 4.3 不利因素注入（核心）

### 4.3.1 编排模式

| 模式   | 适用场景                                    |
| ---- | --------------------------------------- |
| 单项模式 | 按列表顺序逐个注入不利因素，适合快速追加、单点演练               |
| 组合模式 | 将多个不利因素打包为一组同时注入，可设统一持续时间与启动延迟，适合关键场景压测 |

### 4.3.2 不利因素分类

通过上方分类标签切换，共 6 大类、16 种不利因素：

| 分类      | 不利因素                           |
| ------- | ------------------------------ |
| CPU     | CPU 负载                         |
| 网络      | 网络丢包、网络延迟、网络包乱序、网络包重复、网络包损坏          |
| 内存      | 内存负载                           |
| 磁盘 / IO | 文件移动、文件删除、磁盘文件追加、磁盘文件权限修改、磁盘填满 |
| 进程      | 进程挂起、进程终止                      |
| 数据库     | 连接数耗尽、事务栈溢出、大量事务回滚             |

### 4.3.3 操作流程

1. **选择**：点选不利因素卡片，可多选，选中后高亮并标号；
2. **添加**：点「添加」按钮加入下方不利列表；组合模式下可点「创建组合」把 ≥2 个不利因素打包（可设组持续时间和启动延迟）；
3. **排序**：拖拽卡片左侧手柄调整执行顺序（顺序即注入次序）；
4. **配参**：点击列表中的不利卡片，下方展开参数面板修改具体值（如 CPU 占用率、丢包率、网络接口名、延迟时间、文件路径、进程名、回滚比例等）；
5. **校验编排（可选）**：点「校验编排」，基于知识图谱检查同组同时注入的故障之间是否存在互斥/重复关系，结果在按钮下方展示；校验只提示，不阻断开始测试；
6. **开始测试**：点「开始测试」，Engine 会先建表装载、再压测，按列表顺序触发不利因素；测试中可随时点「中止测试」提前结束。

> 编排合理性校验的实现、图谱 schema 与接口见 [docs/orchestration-reasonableness-check.md](docs/orchestration-reasonableness-check.md)。

### 4.3.4 常用参数说明

- `duration`：不利因素持续时间（秒），0 表示持续到手动停止；
- `interface`：网卡接口名（如 eth0 / ens17f0），网络类故障必填；
- `cpu-percent` / `memory-percent`：CPU/内存占用百分比（0–100），`memory-percent` 是唯一参数。
  **宿主部署**下它是 chaosblade 的整机口径（读 `/proc/meminfo`）；
  **配置了 `containerName` 的容器部署**下引擎会追加 `--channel nsexec --ns_target 1`，
  按容器 cgroup 的 `memory.limit_in_bytes` 计算（40 GiB 上限的容器配 90 就是按 40 GiB 算）。
  容器内需要 chaosblade **1.7.4** + 打过 `ns_target` 补丁的 yaml + `blade-cg` 包装脚本，
  安装步骤见 [scripts/chaosblade/README.md](scripts/chaosblade/README.md)；
  版本不够时会直接报 `unknown flag: --ns_target`，注入即失败（不会静默退回整机口径）；
- `process-cmd`：目标进程命令名（如 gaussdb、cm\_server、nginx）；
- `rollback-rate`：事务回滚比例（0–100%）；
- `path` / `filepath`：磁盘类故障的目标路径，必须存在且可写。

> 进程终止、OS 重启等高危故障在 `backend/resbenchmark/config/<db>/*_config_chaosblade.xml` 中标记为 `autoRecovery=1`，即注入后由系统自动恢复；其余默认需手动恢复或测试结束后由 Engine 触发 recovery 命令。

## 4.4 实时性能监控

测试启动后，「不利因素注入」页面下方会自动展示**实时性能图**：

- 实时 TPS 曲线与平均延迟；
- 顶部状态指示：🟢 正常 / 🔴 不利因素中；
- 支持暂停/继续刷新；
- 测试结束后会自动绘制**韧性三角分析图**，标注三个关键点：不利因素开始、性能谷底、恢复完成，并叠加基线参考。

## 4.5 测试记录与韧性评分

「测试记录」页面列出所有历史测试，可按测试 ID、测试名、数据库类型搜索筛选。点击某条记录的「查看报告」后，下方展开：

1. **韧性评分雷达图**：直观展示本次测试的多维得分；
2. **雷达评分表**：列出各维度得分、权重及计算口径说明，点「查看计算公式」可看详细公式；
3. **韧性三角分析图**：吞吐量随时间的变化曲线与三个关键节点；
4. **测试报告预览**：包含测试档案、关键韧性指标、可视化分析、维度评分明细、结论摘要，支持「导出 PDF」。

## 4.6 对比测试结果

「对比测试结果」页面支持把多次正常结束的测试横向对比：

1. 在历史记录选择器中按测试 ID / 数据库类型搜索；
2. 点「添加」把记录加入对比列表（**至少选 2 条**）；
3. 选择对比维度：
   - 数据库对比（同种不利、不同数据库）
   - 负载对比（同库、不同工作负载）
   - 不利因素对比（同库、不同不利因素配置）
4. 页面下方依次展示：多系列条形图（关键性能与韧性指标）、重叠雷达图对比、独立折线图、叠加 TPS 曲线对比，可「导出图表」。

# 五、韧性评分维度说明

平台基于韧性三角模型，从以下维度量化数据库韧性（分值越高表现越好，偏离度除外）：

| 维度       | 含义                          |
| -------- | --------------------------- |
| 吞吐量评分    | 韧性三角阶段整体性能水平百分比（TPS 与基准值之比） |
| 延迟评分     | 反映响应速度，越高代表延迟越低             |
| 稳定性评分    | 韧性三角阶段性能抖动率与延迟抖动率的加权和       |
| 抵抗能力评分   | 抵抗阶段损失性能与抵抗时间项的加权和          |
| 恢复能力评分   | 恢复阶段恢复性能与恢复时间项的加权和          |
| 适应能力评分   | 连续 n 次新测试数值与原始差值的加权算术平均     |
| 波动率评分    | 性能曲线方差的倒数，越高越稳定             |
| RTO 评分   | 实际恢复时间 ≤ RTO 的达标比例          |
| 指标偏离度    | 多次测试结果的加权差异，越低越一致           |
| 不利扰动持续时间 | 多次测试中从逆境恢复的平均时间，越短恢复越快      |

# 六、新手引导教程

平台内置两段交互式教程，首次使用建议先走一遍：

- **新手教程**：从配置数据库 → 负载模型 → 不利注入 → 测试记录 → 对比结果，1 分钟了解核心工作流；
- **不利注入教程**：详细演示编排模式选择、不利分类切换、卡片选择、添加、拖拽排序、参数修改、开始测试与实时图表。

入口：页面右上角「新手教程」「不利注入教程」按钮，可随时回放。

# 七、配置文件说明（进阶）

`backend/resbenchmark/config/` 目录存放各类 XML 配置，Engine 运行时按当前工作目录相对路径读取：

| 路径                                                                                                                                             | 作用                                                                          |
| ---------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `backend/resbenchmark/config/<db>/*_config_chaosblade.xml`                                                                                     | 各数据库的连接信息与故障用例定义（db ∈ gaussdb / mysql / oceanbase / opengauss / postgresql） |
| `backend/resbenchmark/config/fault-cases-generic.xml`                                                                                          | 通用故障用例                                                                      |
| `backend/resbenchmark/config/smallbank-worker.xml` `backend/resbenchmark/config/tpcc-worker.xml` `backend/resbenchmark/config/real-worker.xml` | 工作负载阶段定义（time/rate/weights/testSuite）                                       |
| `backend/resbenchmark/config/smallbank-transactionTypes.xml`                                                                                   | SmallBank 事务类型声明                                                            |
| `backend/resbenchmark/config/plugin.xml`                                                                                                       | 基准插件注册（smallbank → SmallBankBenchmark，tpcc → TPCCBenchmark）                 |

`*_config_chaosblade.xml` 中每个 `<case>` 定义一组故障注入/恢复命令，关键字段：

- `<agents>`：执行该故障的 Agent 地址（如 `master:8000`）；
- `<injection>` / `<recovery>`：注入与恢复命令（通常调用 `/opt/chaosblade/blade`）；
- `<autoRecovery>`：1 表示注入后自动恢复（如进程被 kill 后由集群管理拉起），0 表示需显式 recovery 命令。

> 注意：多个 Engine 进程（如 8080 与 8081）不要并发对同一数据库/schema 跑作业，DROP TABLE 与 SELECT 会因锁冲突导致死锁。请使用不同数据库或串行执行。

# 常见问题

Q: 运行 start-dev.ps1 后启动失败？
A: 请检查：

1. Java 21+ 是否已安装，在命令行输入 `java -version` 确认；
2. Maven 是否已安装，输入 `mvn -v` 确认；
3. Node.js 是否已安装，输入 `node -v` 确认；
4. 前端 node\_modules 是否存在，首次启动会自动安装依赖。

Q: 浏览器显示"无法访问此网站"？
A: 请确认：

1. 开发环境下访问地址是 <http://localhost:5173>（不是 8090）；
2. 三个服务（Engine / Manager / 前端）都启动成功，没有红色报错；
3. 启动后等待一段时间再试（Maven 编译和服务启动需要时间）。

Q: 页面能打开，但"历史记录"和"对比"页面提示错误？
A: 说明 Benchmark 引擎没有正常启动。请检查：

1. Engine 进程是否正常运行（没有报错）；
2. 端口 8080 是否被其他程序占用；
3. 可以尝试停止所有服务，重新运行启动脚本。

Q: 端口被占用了怎么办？
A:

- **前端端口**：修改 `frontend/vite.config.js` 中的 `server.port`；
- **Manager 端口**：修改 `backend/res-manager/src/main/resources/application.properties` 中的 `server.port`，同时修改 `frontend/vite.config.js` 中的代理目标地址；
- **Engine 端口**：修改启动脚本中的 Engine 端口配置，以及 Manager 配置文件中调用 Engine 的地址。

Q: 如何查看详细的错误日志？
A:

- 开发环境：各终端窗口中显示的就是日志；
- Agent 端日志：待测数据库服务器的 `agent/agent.log`。

Q: Agent 启动后主控端连不上？
A: 请检查：

1. Agent 所在服务器的 8000 端口是否已开放（防火墙/安全组）；
2. 数据库配置界面填写的数据库 IP 地址等信息是否正确；
3. Agent 进程是否存活：`pgrep -f "python3.*main.py"`，若不在可重新执行 `agent_monitor.sh check` 拉起。

Q: 故障注入执行失败？
A: 请检查：

1. ChaosBlade、DBChaos 是否已正确安装在待测数据库所在服务器的 `/opt` 目录；
2. `/opt/chaosblade/blade` 是否可执行；
3. 查看 Agent 日志：`agent/agent.log`；
4. 网络类故障的 `interface` 参数是否为待测机器真实的网卡名（用 `ip a` 查询）；
5. 进程类故障的 `process-cmd` 是否与实际进程名一致。

Q: 测试结束后看不到韧性评分？
A: 韧性评分由 Python 脚本计算，依赖 Python 科学计算库。请确认主控端机器已安装 Python 3.9+ 及前述依赖库（pandas / scipy / ruptures / pymser 等），缺库会导致评分生成失败。

Q: SmallBank 事务权重保存不上？
A: 6 类事务权重之和必须等于 100，否则会提示"权重总和必须为 100，当前总和为 X"。每项必须是 0–100 的整数。
