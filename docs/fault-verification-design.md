# 数据库故障注入效果验证机制 — 设计文档

> 版本：v2.2（中间态方案：**故障自查下沉 DBChaos + resbench 配置驱动**；v2.1 并入评审决议：i18n 兜底规则、无 check 故障提示、agent `/check` 定位澄清；v2.2 并入：验证时序与故障持续时间联动、轮询间隔 7s）
> 状态：评审中（v2.2 决议并入）
> 背景决策：v1 方案将验证 SQL 探针放在框架注册表，添加新故障需改框架代码，不满足"加故障只改故障实现"的诉求，已废弃。v2 采用：每个故障由**注入器自己实现自检（self-check）**，resbench 提供**统一调用协议**与**配置化故障目录**。

---

## 1. 背景与问题

### 1.1 现状

```
前端表单 → StartByFormService（18 类故障硬编码 switch）→ 生成 config XML
  → FaultRunner 调度 → FaultInjector → POST agent/inject {cmd,args}
    → agent exec() → `java -jar DBChaos.jar <dbType> <subsystem> <case_key> ...`
  → FaultResult{endPoint, result}（result 只是命令执行的原始字符串）
  → FaultInjectInfo 持久化 → 前端展示
```

### 1.2 核心问题

1. **注入 ≠ 生效**：`FaultResult` 只证明命令返回 0，不证明故障在数据库上产生预期效果（连接数飙升、死锁计数增长、锁等待出现……）。
2. **故障失败被静默**：DBChaos jar 缺失、DB 连接参数错误、目标表不存在、被 timeout 提前终止等，都表现为"注入成功但无效果"，现有链路无法发现。
3. **无统一判定接口**：框架没有结构化回答"这个故障生效没有"。

### 1.3 方案决策（v2）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 验证逻辑放哪 | **故障注入器内部（DBChaos 侧自检）** | 最懂"有没有生效"的是故障实现本身；框架侧 SQL 探针是次优理解且加故障要改框架 |
| 框架角色 | 统一协议调用 + 轮询判定 + 展示 | 不解析故障细节 |
| 故障目录 | **配置驱动**（resbench fault-cases.yaml） | 本次目标：加故障不动 Java 代码、不编译；中间态，未来可升级 registry 全动态 |
| 范围 | 仅数据库类型故障（DBChaos 18 类） | 主机级故障（chaosblade）后续迭代 |

**达成效果（本方案验收标准）**：
- 每个 DB 故障注入成功后，框架自动调用该故障的自检命令并给出四态判定。
- 统一接口：Java `FaultVerifier` + `FaultInjectInfo.verification` + REST。
- 加新故障 = DBChaos 写注入器（含自检）+ resbench `fault-cases.yaml` 加一段。**不改 Java 代码、不重新编译**。

---

## 2. 总体架构

```
┌─ DBChaos 仓库（故障实现层 —— 每个故障自带自检）────────────────────┐
│  interface SelfCheckable { SelfCheckResult check(params); }        │
│  CLI 新增 check 子命令                                             │
│    java -jar DBChaos.jar <dbType> check <subsystem> <case_key>     │
│          -url ... -user ... -password ... [-table ...]             │
│    → stdout JSON: {"enforced":bool,"summary":str,"checks":[...]}   │
│  （每个注入器实现 check()：查连接数/死锁计数/锁等待/死元组…）         │
└──────────────────────────────────────────────────────────────────┘
┌─ Agent（透明桥接，一次性改动）────────────────────────────────────┐
│  POST /check {cmd,args} → 执行 DBChaos check → 透传 JSON          │
└──────────────────────────────────────────────────────────────────┘
┌─ resbench（统一调用层 —— 本次主要建设工作）────────────────────────┐
│  fault-cases.yaml         故障目录（注入模板 + check 模板 + 参数）  │
│  FaultCatalogLoader       加载 yaml → 定义对象（替代硬编码 switch） │
│  FaultVerifier            注入成功 → warmup → 轮询 POST /check     │
│                           → 四态判定 ENFORCED/NOT_ENFORCED/...    │
│  FaultInjectInfo.verification  结果持久化 + REST 带出              │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. 核心契约：故障自检统一协议

> 这是框架与故障实现之间的唯一契约。DBChaos 侧按此实现，resbench 侧按此调用，两边并行开发互不阻塞。

### 3.1 自检接口（DBChaos 仓库内）

```java
// DBChaos 新增：SelfCheckable.java
public interface SelfCheckable {
    /** 自查当前注入效果。未生效返回 enforced=false，并给出证据。 */
    SelfCheckResult check(Map<String, String> params) throws Exception;
}

public class SelfCheckResult {
    private boolean enforced;              // 判定：效果达没达到
    private String summary;                // 人类可读结论，如 "deadlocks 0 → 37"
    private List<CheckItem> checks;        // 逐项证据
    // getters/setters/静态工厂……
}

public class CheckItem {
    private String name;       // 证据项名，如 "deadlocks_delta"
    private String observed;   // 实测值，如 "37"
    private String expected;   // 期望，如 ">1"
    private boolean matched;   // 该项是否满足
}
```

### 3.2 CLI check 子命令（DBChaas 主程序）

```
java -jar DBChaos.jar <dbType> check <subsystem> <case_key> [同注入有效参数]

示例：
java -jar DBChaos.jar opengauss check txn deadlock_storm \
    -url jdbc:postgresql://YOUR_DB_HOST:15432/postgres \
    -user benchmarksql -password '***' -table bmsql_stock

成功后 stdout 输出（唯一输出通道，禁止打日志到 stdout）：
{"enforced":true,"summary":"pg_stat_database.deadlocks 0 → 37",
 "checks":[{"name":"deadlocks_delta","observed":"37","expected":">1","matched":true}]}

约定：
- enforced 见 4.1 判定词表；未生效必须给 evidence（observed/expected）
- 注入器未实现 check / 参数缺失 / DB 不可达 → returncode != 0，stdout 输出 {"error":"<原因>"}
- check 必须瞬时返回（仅数据库查询），禁止长跑；CLI 层兜底 30s 超时
```

### 3.3 Agent 新增端点（Python FastAPI）

```
POST /check
body: {"cmd": "java", "args": ["-jar", "...", "<dbType>", "check", ...]}
行为: exec(commands)（复用 utils.tools.exec，timeout 30s）
返回:
  命令成功且 stdout 可解析为 {"enforced":...,"checks":[...]} → 原样透传该 JSON
  命令 returncode != 0 → {"error": "<stderr/原因>", "returncode": <rc>}
  stdout 非 JSON           → {"error": "invalid self-check output: <前200字符>"}
```

复用 `/inject` 的执行通道，仅语义不同；不新增依赖。

> **评审确认（v2.1）**：`/check` 与 `/inject` 结构完全同构——同一个执行器（`utils.tools.exec`）、同一超时（agent 侧 30s），只是语义不同（注入 vs 自检）。agent **不做任何故障语义判断**，仅做三件事：① 执行命令；② 校验 stdout 是否为契约 JSON；③ 错误包装。这层“执行 + 校验 + 包装”是框架四态判定的前提：透传 JSON 的 `enforced` 决定 ENFORCED/NOT_ENFORCED，`{"error":...}`（含格式非法）决定 ERROR（立即终止轮询），网络层失败决定 UNKNOWN。

---

## 4. 统一判定模型（resbench-common）

### 4.1 状态枚举

```java
public enum FaultVerificationStatus {
    ENFORCED,       // 轮询中收到一次合法契约且 enforced=true
    NOT_ENFORCED,   // 轮询满超时，最后一次 check 合法且 enforced=false
    ERROR,          // 注入命令失败，或 check 返回 {error:}/响应格式非法
    UNKNOWN         // 未配置 check 命令 / 网络层无法联系 agent（未获取任何有效结果）
}
```

### 4.2 结果对象

```java
public class FaultVerificationResult {
    String faultID;
    String faultName;
    String nameKey;                 // 如 faultinject.fault.txn.deadlock_storm
    String phase;                   // INJECT | RECOVER（本期只做 INJECT）
    FaultVerificationStatus status;
    String summary;                 // 自检 summary 或框架判定摘要
    List<CheckEvidence> evidence;   // 自检 checks[] 透传
    String endPoint;                // 执行自检的 agent 地址
    long warmupMs, pollIntervalMs, timeoutMs;
    long verifiedAt;
    String error;                   // ERROR/UNKNOWN 时的原因（所有 ERROR/UNKNOWN 分支必填）
}

public class CheckEvidence {
    String name; String observed; String expected; boolean matched;
}
```

### 4.3 判定词表（框架语义）

| 框架状态 | 触发条件 | 前端 |
|---|---|---|
| `ENFORCED` | 轮询中收到一次合法契约且 `enforced=true`（**立即终止轮询**） | ✅ 绿色 |
| `NOT_ENFORCED` | 轮询满超时，**最后一次** check 返回合法契约且 `enforced=false` | ❌ 红色 |
| `ERROR` | 注入命令即失败（不进入验证）；或任一 check 返回 `{error:}`/响应格式非法（**立即终止轮询**） | ⚠️ 橙色 |
| `UNKNOWN` | 无 check 模板；或网络层无法联系 agent，最后一次轮询无任何结果 | — 灰色（附原因） |

> **判定规则（评审修订）**：`enforced=true` 与 `ERROR` 出现即终止轮询；其余情况**以最后一次轮询结果为准**。格式正确的响应只产生 ENFORCED / NOT_ENFORCED；`{error:}` 或格式非法判 ERROR；只有网络层失败（连不上 / connect/read 超时，未拿到 agent 端任何响应）才判 UNKNOWN——"不知道 agent 端发生了什么"才算 unknown，拿到响应但内容报错/格式不对是明确知道出错了。**所有 UNKNOWN / ERROR 分支必须 `setError(原因)`**（如"未配置自检命令（无 check 模板）""check 调用失败: <异常>"），前端悬停徽章可查看。

---

## 5. resbench 故障目录配置化（fault-cases.yaml）

### 5.1 文件位置

- 运行时：`config/fault-cases.yaml`（与现有 config 模板同级，可外部维护）
- 打包兜底：`src/main/resources/fault-cases.yaml`（运行时文件不存在时加载此份）
- **加载顺序：外部文件优先，缺失自动 fallback 到 classpath**，启动日志记录实际来源

### 5.2 单条故障定义 schema

```yaml
faultinject.fault.txn.deadlock_storm:          # ← nameKey（唯一键）
  name: Deadlock Storm                          # 展示名（替代 description）
  description: 交叉等待图与死锁检测过载          # 可选，中文说明
  autoRecovery: 1                               # 与现 FaultTemplate.autoRecovery 一致
  params:                                       # 参数 schema → 前端表单渲染 + 校验 + 默认值
    duration:    { type: int,    unit: seconds, default: 60,   label: 持续时间 }
    table:       { type: string, default: bmsql_stock, label: 目标表 }
    anchors:     { type: int,    default: 2,    label: 锚点事务数 }
    anchor-rows: { type: int,    default: 20,   label: 锚点行数 }
    waiters:     { type: int,    default: 16,   label: 等待事务数 }
    hot-rows:    { type: int,    default: 32,   label: 热点行数 }
  injection:                                    # 注入命令模板
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} txn deadlock_storm
      -duration {{duration|60|1000}} -table {{table|bmsql_stock}}
      -anchors {{anchors|2}} -anchor-rows {{anchor-rows|20}}
      -waiters {{waiters|16}} -hot-rows {{hot-rows|32}}
      -url {{dbUrl}} -user {{user}} -password {{password}}
  check:                                        # 自检命令模板（本次新增）
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} check txn deadlock_storm
      -url {{dbUrl}} -user {{user}} -password {{password}} -table {{table|bmsql_stock}}
# 未配置 check 的故障 → 注入正常，验证判 UNKNOWN（老故障零影响）
```

### 5.3 模板语法

| 写法 | 语义 |
|---|---|
| `{{dbUrl}}` / `{{user}}` / `{{password}}` / `{{dbType}}` / `{{DBCHAOS_JAR}}`/`{{agentHost}}` | **框架上下文变量**，自动注入（来自表单 DB 连接信息与配置） |
| `{{param}}` | 表单参数，必填；缺失 → 生成配置时报错并提示 |
| `{{param|default}}` | 可空，缺省用 default |
| `{{param|default|1000}}` | 数值参数乘 1000（duration 秒→毫秒），类型须为 int/float |

### 5.4 目录接口（前端动态化的配套）

```
GET /web/faults/catalog → 读取 fault-cases.yaml，返回：
[ { "nameKey": "faultinject.fault.txn.deadlock_storm",
    "name": "Deadlock Storm",
    "description": "...",
    "params": [ {key,label,type,unit,default} ] } , ... ]

消费方：前端 FaultSelector（故障下拉 + 参数表单动态渲染）。
一次改造后，加故障只需在 yaml 加一段，前端自动出现。

#### 5.4.1 展示文本与 i18n 解析规则（评审确认 v2.1）

catalog 条目返回 `nameKey` + 展示文本（`name`/`description`/参数 `label`），其中 yaml 展示文本为**兜底语言**（建议统一为中文或统一为英文，文档级约定，两种语言并存时由词典控制切换）。

前端解析顺序（收口为一个 helper，如 `resolveFaultText(item)`）：

1. `translate(nameKey)` / `translate(参数 label)` 命中现有 zh/en 词典 → 用词典译文（保证中英文切换不丢）；
2. 词典未命中 → 回退使用 catalog 返回的 yaml 展示文本。

效果：老故障译文零成本保留；新故障 yaml 写一份文本即可立刻显示，且不影响两种语言的切换。
```

---

## 6. 统一验证执行器（FaultVerifier）

### 6.1 流程（注入后验证）

```
FaultRunner 注入任务（duration = 本故障保持时长，来自 yaml defaultDuration / 表单值）
  ├─ FaultInjector.injectFaults()
  │    └─ 任一 FaultResult 含错误 / agent 不可达 / 注入结果为空
  │          └─ verification = ERROR，结束          （不进入验证）
  ├─ FaultVerifier.verifyAfterInjection(fault, dbservice, agentEndpoint, durationMs)
  │    ├─ 1. 取 fault.CHECK 命令（XML <check>，来自 yaml 模板渲染）
  │    │      └─ 无 → UNKNOWN（附原因：未配置自检命令），结束
  │    ├─ 2. warmup（默认 3s）——等待故障效果显现；yaml check.warmupMs 可覆盖
  │    ├─ 3. 轮询循环（pollIntervalMs 默认 7s；**整轮结束时间与 duration 联动**，见 §6.4）：
  │    │      POST agent/check {cmd,args}
  │    │      ├─ enforced=true            → ENFORCED，跳出
  │    │      ├─ 返回 {error:}/格式非法    → ERROR，跳出
  │    │      ├─ enforced=false           → 记 NOT_ENFORCED + evidence，继续轮询
  │    │      └─ 网络层失败（连不上/超时）  → 记 UNKNOWN（附原因：最后一次异常），继续轮询
  │    │      超时 → 以最后一次轮询结果为准（NOT_ENFORCED 附最后 evidence；UNKNOWN 附最后一次异常）
  │    └─ 4. 组装 FaultVerificationResult（warmupMs/pollIntervalMs/timeoutMs 记实际生效值）
  └─ 写回 faultInjectInfo.setVerification(...) → writeFaults() 持久化
```

### 6.2 关键约定

1. **验证连接与注入命令共用同一 DB 凭据**（check 模板内 {{user}}/{{password}}），不新增配置。
2. **check 瞬时性**：单次 request timeout 15s（agent 侧兜底 30s）；单次探测慢不影响整轮窗口，见 §6.4。
3. **串行执行**：验证在注入任务内串行（warmup + 整轮窗口，窗口见 §6.4），不阻塞其他故障调度；并发故障场景验证同样并发（实例独立）。
4. **判定结果不阻断**：ENFORCED / NOT_ENFORCED / ERROR / UNKNOWN 都只标记+告警，不做自动重试（本期）。
5. **开关**：`fault.verification.enabled=true|false`，false 恢复旧行为（不调用 check）。

### 6.3 配置项（合并入现有配置）

```properties
fault.verification.enabled=true
fault.verification.warmupMs=3000          # 首探测前等待（全局默认；yaml check.warmupMs 可覆盖）
fault.verification.pollIntervalMs=7000    # 轮询间隔（v2.2：2s → 7s；yaml check.pollIntervalMs 可覆盖）
fault.verification.graceMs=30000          # 与 duration 联动时的结束余量（v2.2 新增）
fault.verification.capMs=300000           # 整轮窗口上限 5min（v2.2 新增，防超长 duration 阻塞调度）
fault.verification.minTimeoutMs=7000      # 整轮窗口下限 ≥1 次探测（v2.2 新增）
fault.verification.timeoutMs=(未设置)      # 手动绝对覆盖：显式设置时取代公式，不加 grace/cap（运维干预用）
fault.verification.maxRequestTimeoutMs=15000
fault.verification.catalogPath=config/fault-cases.yaml   # 缺省此路径，再 fallback classpath
```

### 6.4 验证时序与故障持续时间联动（v2.2）

**动机**：v2.1 整轮窗口写死 30s —— 5s 短故障要白等 30s，120s 长故障在效果活跃期还没验完就提前收工，两类都不合理。整轮窗口改为跟随本故障的保持时长。

**窗口公式**（由 `FaultRunner` 传入本故障 `durationMs = work.getDuration() * 1000`）：

```
effectiveTimeoutMs = clamp(durationMs + graceMs, minTimeoutMs, capMs)
```

- 关闭时间轴：验证起点 = 注入完成时刻；结束点 = 注入完成 + `effectiveTimeoutMs`，即**故障保持期 + 结束缓冲**。
- `graceMs` 的意义：保持期结束后捕捉"恢复边界前最后一次确认"，并容忍调度抖动/轮询间隔余量。
- `minTimeoutMs` / `capMs` 的意义：短故障（如 1s）至少有 1 次探测；超长 duration（如 1h）不无限占用注入调度线程（串行验证的执行模型约束）。
- 手动干预：显式设置 `fault.verification.timeoutMs` 后，**整体取代公式**（不做 clamp、不加 grace）——适合运维对特定环境拍固定值。

**每故障 yaml 覆盖**（粒度最小，优先级最高，透传 XML `<check>`）：

```yaml
faultinject.fault.txn.deadlock_storm:
  check:
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} check txn deadlock_storm
      -url {{dbUrl}} -user {{user}} -password {{password}}
    timeoutMs: 90000      # 可选：整轮窗口（clamp 到 [minTimeoutMs, capMs]）
    pollIntervalMs: 5000  # 可选：该故障专用轮询间隔
    warmupMs: 1000        # 可选：该故障专用首探测等待
```

优先级（高 → 低）：**yaml check.<key> > 全局系统属性 > 公式默认值**。

**轮询次数参考**（pollIntervalMs=7s、graceMs=30s、warmup=3s）：

| duration | 整轮窗口 | 探测次数（约） |
|---|---|---|
| 5s | 35s | 4 |
| 30s | 60s | 8 |
| 60s | 90s | 12 |
| 120s | 150s | 21 |
| 600s | 300s（cap 截断） | 42 |
| 1h | 300s（cap 截断） | 42 |

**实现面（v2.2 待实施）**：
1. `FaultVerifier`：`pollIntervalMs` 默认改 7000；新增 `graceMs()/capMs()/minTimeoutMs()`；`verifyAfterInjection` 增加 `durationMs` 入参，内部 `resolveEffectiveTimeoutMs(faultCheck, durationMs)` 合并 yaml 覆盖（`Fault.CHECK` 扩展 timeoutMs/pollIntervalMs/warmupMs 字段，XML `<check>` 属性透传，老 XML 无属性 → null → 全局默认）。
2. `FaultInjector.FaultRunner` 两处调用点传入 `work.getDuration() * 1000`。
3. `FaultDefinition`（yaml 加载）与 `StartByFormService.replaceFaultCases` 输出 `<check timeoutMs=... pollIntervalMs=... warmupMs=...>`。
4. `FaultVerificationResult` 已含实际生效的 warmupMs/pollIntervalMs/timeoutMs —— 前端徽章 hover 直接可见，**前端零改动**。

---

## 7. XML 扩展与数据通道

### 7.1 Fault / XML

- `Fault` 新增字段 `public final Command CHECK;`（可空）。
- `loadFaultFromXML` 解析 `<case>` 内**可选** `<check>` 节点（结构同 `<injection>`：`<cmd>` + `args`）。
- 老 XML 无 `<check>`：CHECK=null → 验证返回 UNKNOWN，不影响现有流程。
- `StartByFormService.replaceFaultCases` 生成 `<case>` 时，若有 check 模板则输出 `<check>`。

### 7.2 FaultInjectInfo（数据通道）

新增字段（可空，老数据兼容）：

```java
private FaultVerificationResult verification;          // 注入后验证结果
private FaultVerificationResult recoveryVerification;  // 预留：恢复后验证（本期不填）
```

随 `tmp/faultInject.json` 与 history 持久化；现有 `getFaultInjectionInfo` 自动带出，前端直接消费，无需新查询接口。

### 7.3 REST

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/web/faults/catalog` | 故障目录（yaml 驱动），前端列表/表单渲染 |
| `POST` | `/web/fault/verify` | **手动补验**：`{databaseIp, agentPort?, nameKey, params?}` → 即时执行一次 check 并返回 `FaultVerificationResult`（运行外/历史场景排查用） |
| `POST` | `/web/fault/orchestration/check` | 编排合理性校验（Engine）：`{faults: [{nameKey, groupKey, ...}]}` → 基于 Neo4j 知识图谱检查同组同时注入的故障是否互斥/重复，详见 [orchestration-reasonableness-check.md](orchestration-reasonableness-check.md) |
| `POST` | `/suite/fault/orchestration/check` | 编排合理性校验（Manager 转发）：`{envInfo, faults}` → 按 `envInfo` 转发到对应 Engine |
| `GET` | （现有）`getFaultInjectionInfo` | 已内嵌 verification |

---

## 8. 前端展示

1. **FaultSelector**：故障下拉与参数表单改为调用 `GET /web/faults/catalog` 动态渲染（删除硬编码列表）。
2. **执行记录（FaultCard / History 报告）**：每个故障显示状态徽章：

```
✅ ENFORCED      绿色   效果已验证达成（悬停/展开显示 summary 与 checks[] 逐项证据）
❌ NOT_ENFORCED  红色   轮询满超时、最后一次 check 合法且 enforced=false（注入了但没效果；展开 evidence 支持排查）
⚠️ ERROR        橙色   注入命令失败，或 check 返回 {error:}/响应格式非法（显示 error）
—  UNKNOWN      灰色   未配置自检 / 网络层无法联系 agent，无法确认（老故障或未实现 check 的故障；显示原因 tooltip）
⏳ VERIFYING    蓝色   验证进行中（仅运行中轮询场景）
```

3. 韧性三角图图例同步使用同一徽章数据源（verification 字段），不做第二套逻辑。
4. （评审补充 v2.1）无自检故障的提示：故障卡片可显示“无自检”小标记，或在注入前提示“该故障未配置自检，注入后无法验证效果，是否继续？”——仅为提示，**不阻断**注入与测试（照常注入、照常测试，结果 UNKNOWN）。

---

## 9. 边界情况与判定规则

| 场景 | 判定 | 说明 |
|---|---|---|
| agent 不可达 / 注入 returncode≠0 / /inject 超时 / 注入结果为空 | `ERROR` | 注入链路失败，不进入验证 |
| 注入成功、无 `<check>` 模板 | `UNKNOWN` | 不假装成功，前端灰色；附原因"未配置自检命令（无 check 模板）" |
| check 返回 `enforced=true` | `ENFORCED` | 立即终止轮询；附 summary + checks 证据 |
| check 返回合法契约且 `enforced=false`，轮询满超时且最后一次仍为 false | `NOT_ENFORCED` | 以最后一次为准；附最后 evidence |
| check 返回 `{error:}` / 输出非法 JSON / 缺 enforced 字段 | `ERROR` | agent 端明确出错，立即终止轮询 |
| check 网络层失败（连不上 / connect/read 超时），最后一次轮询无结果 | `UNKNOWN` | 未拿到 agent 端任何响应，无法确定发生了什么；附最后一次异常 |
| 轮询中最后一次结果为网络失败，此前出现过 enforced=false | `UNKNOWN` | 以最后一次为准——期间到底发生了什么无法确定；附最后一次异常 |
| 故障 duration 过短，验证未完成即恢复 | 按最后一次轮询结果（通常 NOT_ENFORCED/UNKNOWN），summary 注明"验证期间故障已恢复" | 通过 verifiedAt 与 startTime 比较判断 |
| MySQL 系故障注入器未实现 check（首期） | `UNKNOWN` | 首期先保 openGauss 系强证据故障 |

---

## 10. 兼容性

1. 老 XML/历史数据：`verification` 为 null，前端显示"—"。
2. 老故障 yaml 未配 check：注入行为不变，验证 UNKNOWN。
3. `fault.verification.enabled=false` 整体关闭验证，完全恢复旧行为。
4. DBChaos 未升级（无 check 子命令）时：agent `/check` 返回 returncode≠0 → 框架判 ERROR（不中断），区别于注入成功但未生效的 NOT_ENFORCED。
5. `FaultVerificationResult`/`CheckEvidence` 反序列化按 `@JsonIgnoreProperties(ignoreUnknown=true)` 风格，字段增减向前兼容。

---

## 11. 实施步骤（评审通过后执行）

### 阶段 A — DBChaos 仓库（故障实现层）

| 步骤 | 内容 |
|---|---|
| A1 | 新增 `SelfCheckable` 接口 + `SelfCheckResult`/`CheckItem` 模型 |
| A2 | CLI 主程序支持 `check` 子命令（解析同注入参数 → 实例化注入器 → check() → stdout JSON，30s 兜底超时） |
| A3 | 18 个注入器实现 `check()`。**首期优先实现强证据类**（见 §11.4 清单），其余暂不实现 → 对应 yaml **不配 check 模板**（验证 UNKNOWN）；已配模板但注入器抛"未实现"（returncode≠0）→ 验证 ERROR |
| A4 | （可选）cases.tsv 增加自检支持列，方便 agent/人工确认覆盖度 |

### 阶段 B — Agent（桥接层）

| 步骤 | 内容 |
|---|---|
| B1 | `faults/` 下新增 `check.py`（或并入 command.py）：`POST /check` 端点，执行命令 + 解析/透传 JSON + 错误包装 |

### 阶段 C — resbench（统一调用层，主要工作）

| 步骤 | 内容 |
|---|---|
| C1 | common 模块：`FaultVerificationStatus` / `FaultVerificationResult` / `CheckEvidence` |
| C2 | `Fault` 增加 CHECK 字段 + XML `<check>` 解析（`loadFaultFromXML` / `FaultRunner` 使用处） |
| C3 | `config/fault-cases.yaml`：18 类故障迁移（注入模板 + check 模板 + params schema），并落一份 classpath 兜底 |
| C4 | `FaultCatalogLoader`：加载 yaml → 定义对象；模板渲染引擎（`{{var}}`/`{{var|default}}`/`{{var|default|mult}}`/上下文变量） |
| C5 | `StartByFormService` 删除硬编码 switch，改按 nameKey 查 catalog 生成 `FaultCommand + check 命令` |
| C6 | `FaultVerifier`：轮询判定执行器（§6.1 流程）+ 配置项 |
| C7 | `FaultRunner` 接线：注入成功后调 verify → 写回 `FaultInjectInfo.verification` |
| C8 | REST：`GET /web/faults/catalog` + `POST /web/fault/verify` |
| C9 | 单测：模板渲染、判定状态机、XML 解析兼容 |

### 阶段 D — 前端

| 步骤 | 内容 |
|---|---|
| D1 | FaultSelector 改为目录接口动态渲染 |
| D2 | FaultCard / History 增加状态徽章 + evidence 展开 |
| D3 | 韧性三角图图例接入同一数据源 |

### 阶段 E — 联调验证

| 步骤 | 内容 |
|---|---|
| E1 | 真实 openGauss 环境，对首期 8 类强证据故障逐一：注入 → 观察 verification 输出与实测一致 |
| E2 | 负例验证：先跑一个不在目标表上的故障（无效果）→ 断言 NOT_ENFORCED 正确报出 |
| E3 | 兼容回归：老 XML（无 check）跑通，历史报告正常渲染 |

### 11.4 首期自检优先级（DBChaos A3）

| 优先级 | 故障 | 自检证据（注入器内实现） |
|---|---|---|
| P0 | txn.deadlock_storm | `pg_stat_database.deadlocks` 增量 > 1 |
| P0 | connection.limit.exceeded | 尝试建连，期望 `too many clients already` |
| P0 | txn.uncommitted_txn | 目标行 `UPDATE` 期望锁等待/超时，或 `pg_locks` 持锁会话 |
| P0 | transaction.massive.rollback | `xact_rollback` 两次采样增量 + 回滚占比 |
| P0 | storage.mvcc_bloat | `n_dead_tup` 连续增长 |
| P1 | connection.surge / thread_saturation | `pg_stat_activity` 连接数/活跃会话达阈值 |
| P1 | sql.plan_flip | 多次 EXPLAIN 观测扫描方式振荡 |
| P1 | txn.max_prepared | `pg_prepared_xacts` 逼近上限 |
| P2 | 栈溢出 5 类 / read_amp_trap / duplicate_txn / buffer.memory_pressure | 实现，或暂不配 check 模板（UNKNOWN）；已配模板但注入器未实现 → ERROR（下表：建议检查点） |
| P2 | MySQL 系 | 首期允许 UNKNOWN（不配 check 模板），DBChaos 有 MySQL 视图后再补 |

栈溢出类建议检查点（P2）：自检发起同构深 SQL 期望 `stack depth limit exceeded`，或从 DB 日志统计错误（需 `verification.dbLogPath` 配置，纯可选）。

---

## 12. 风险与待确认

| # | 事项 | 影响 | 对策 |
|---|---|---|---|
| 1 | DBChaos CLI 现有参数解析器是否支持新增 `check` 子命令 | A2 工作量 | 以 DBChaos 现有 CLI 框架扩展一个子命令即可，命令参数与注入共用解析器 |
| 2 | check 必须瞬时（单次 30s 内） | 单次慢检查会拖慢该轮探测 | yaml `check.timeoutMs` 只限整轮窗口（clamp 见 §6.4），单次探测超时由框架 15s request timeout 兜底、只影响该次 |
| 3 | MySQL 系 DBChaos 注入器首期自检缺口 | MySQL 故障验证 UNKNOWN | 首期聚焦 openGauss 系，MySQL 归 P2，不硬凑 |
| 4 | 事务类故障自检与注入器并发执行同一表 | 自检可能被锁阻塞 | 自检 SQL 只读、加 `statement_timeout`；锁等待类故障的自检本身就是证据（期望超时） |
| 5 | yaml 与 DBChaos registry 双处维护（中间态固有） | 故障定义可能不同步 | 文档明确规定：**yaml 是 resbench 侧事实源，新故障必须两边同步**；未来升级 registry 全动态时消除 |
| 6 | duration 短于验证窗口 | 验证未完成已恢复 | 验证时长 < duration 即 OK；duration < warmup+timeout 时输出附注 |
| 7 | DBChaos jar 路径（`/opt/DBChaos/DBChaos.jar`）与验证环境不一致 | check 命令找不到 jar | 沿用现有 `DBCHAOS_JAR` 常量，yaml 模板用 `{{DBCHAOS_JAR}}` 统一管理 |

---

## 附录 A：fault-cases.yaml 完整样例（deadlock_storm）

```yaml
faultinject.fault.txn.deadlock_storm:
  name: Deadlock Storm
  description: 交叉等待图与死锁检测过载
  autoRecovery: 1
  params:
    duration:    { type: int,    unit: seconds, default: 60, label: 持续时间 }
    table:       { type: string, default: bmsql_stock, label: 目标表 }
    anchors:     { type: int,    default: 2,  label: 锚点事务数 }
    anchor-rows: { type: int,    default: 20, label: 锚点行数 }
    waiters:     { type: int,    default: 16, label: 等待事务数 }
    hot-rows:    { type: int,    default: 32, label: 热点行数 }
  injection:
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} txn deadlock_storm
      -duration {{duration|60|1000}} -table {{table|bmsql_stock}}
      -anchors {{anchors|2}} -anchor-rows {{anchor-rows|20}}
      -waiters {{waiters|16}} -hot-rows {{hot-rows|32}}
      -url {{dbUrl}} -user {{user}} -password {{password}}
  check:
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} check txn deadlock_storm
      -url {{dbUrl}} -user {{user}} -password {{password}} -table {{table|bmsql_stock}}
```

生成后的 XML `<case>`：

```xml
<case>
  <id>3</id>
  <description>Deadlock Storm</description>
  <agents><agent>YOUR_DB_HOST:8000</agent></agents>
  <injection>
    <cmd>java</cmd>
    <arg>-jar</arg><arg>/opt/DBChaos/DBChaos.jar</arg>
    <arg>opengauss</arg><arg>txn</arg><arg>deadlock_storm</arg>
    <arg>-duration</arg><arg>60000</arg><arg>-table</arg><arg>bmsql_stock</arg>
    <!-- … -->
  </injection>
  <check>
    <cmd>java</cmd>
    <arg>-jar</arg><arg>/opt/DBChaos/DBChaos.jar</arg>
    <arg>opengauss</arg><arg>check</arg><arg>txn</arg><arg>deadlock_storm</arg>
    <arg>-url</arg><arg>jdbc:postgresql://…</arg><arg>-user</arg><arg>…</arg>
  </check>
  <autoRecovery>1</autoRecovery>
</case>
```

## 附录 B：一次完整时序示例

```
t=0    FaultRunner 调度 Deadlock Storm（duration 60s；验证窗口 = 60+30=90s）
t=0    FaultInjector → agent /inject → DBChaos 注入器启动（线程持续 60s）
t≈0.2  inject returncode=0，FaultResult 无错误
t=3.5  warmup（3s）结束 → 第 1 次 POST agent/check → {"enforced":false,"summary":"deadlocks 0→3"}（不足阈值）
t=10.5 第 2 次（7s 间隔）→ {"enforced":true,"summary":"deadlocks 0→37","checks":[…]}
       → status=ENFORCED，FaultInjectInfo.verification 写盘
t=60   恢复；前端展示 ✅ Deadlock Storm ENFORCED（evidence 可展开）
```

---

## 变更记录

| 版本 | 变更 |
|---|---|
| v1（废弃） | 框架侧 JDBC SQL 探针注册表；加故障需改框架 → 不满足诉求 |
| v2（本版） | 故障自查下沉 DBChaos（SelfCheckable + CLI check）；resbench 配置驱动（fault-cases.yaml）+ 统一验证执行器 + 目录/补验 REST + 前端徽章 |
| v2.1（评审补充） | ① i18n 兜底规则（方式 2：词典优先、yaml 文本兜底，§5.4.1）；② 无 check 故障照常注入，前端仅提示不阻断（§8.4）；③ agent `/check` 定位澄清：与 `/inject` 同构的执行器，只做执行+JSON 校验+错误包装，不做故障语义（§3.3） |
| v2.2（时序决议） | ① 验证整轮窗口与故障持续时间联动（§6.4：`effectiveTimeoutMs = clamp(durationMs + graceMs, minTimeoutMs, capMs)`）；② 轮询间隔默认 2s → **7s**；③ yaml `check.timeoutMs/pollIntervalMs/warmupMs` 每故障覆盖优先于全局；④ `fault.verification.graceMs/capMs/minTimeoutMs` 新增配置；⑤ 显式 `fault.verification.timeoutMs` 为绝对覆盖（不加 grace/cap） |