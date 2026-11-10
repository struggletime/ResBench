# 如何新增一个数据库故障（DBChaos + ResBench 全链路）

> 适用版本：DBChaos 0.0.1 + ResBench fault-verification 分支
>
> 本文描述从“故障注入实现”到“ResBench 前端可选/验证”的完整接入流程。
> 核心思路：**故障实现放在 DBChaos，故障定义放在 ResBench YAML，前端自动动态展示。**

---

## 1. 整体架构回顾

```text
ResBench 前端
  → GET /suite/faults/catalog
      → ResBench Engine 读 backend/resbenchmark/config/fault-cases.yaml
          → 生成注入/自检命令
              → Agent /inject、/check
                  → DBChaos：java -jar DBChaos.jar --db <db> <subsystem> <case> ...
```

所以新增一个故障，通常需要改两个仓库：

| 层 | 需要做什么 | 文件位置 |
|---|---|---|
| DBChaos | 实现新的注入 Case（必要时实现 check 自检） | `DBChaos/src/chaos/inject/`、`DBChaos/resources/registry/registry.json` |
| ResBench | 在故障目录中注册该故障，配置参数、注入命令、自检命令 | `ResBench/backend/resbenchmark/config/fault-cases.yaml` |

前端通常**不需要改代码**：故障列表由 `/suite/faults/catalog` 动态驱动。

---

## 2. 完整流程图

```text
DBChaos 侧：
  1. 选择子系统 + 新故障关键字，如 txn / long_hold_lock
  2. 新建注入器类 LongHoldLockInject
  3. 在 registry.json 注册 Case
  4. （推荐）为它写 check 自检
  5. 构建 DBChaos.jar 并手工验证

ResBench 侧：
  6. 在 fault-cases.yaml 增加一个故障定义
  7. 配置 injection 命令模板
  8. （可选但推荐）配置 check 命令模板
  9. 重启/热加载 Engine
  10. 前端自动出现新故障，开始测试并观察验证徽章
```

---

## 3. DBChaos 侧：实现故障注入

### 3.1 确定子系统和 Case 名

DBChaos 已有子系统：

- `session`：连接与会话
- `sql`：SQL 编译与优化
- `exec`：执行引擎
- `txn`：事务与并发控制
- `storage`：存储引擎
- `buffer`：缓冲管理
- `log`：日志/恢复
- `quota`：资源治理
- `check`：自检专用子系统

新故障尽量挂到已有子系统，避免无谓新增子系统。

### 3.2 新建注入器类

在 `DBChaos/src/chaos/inject/` 下新建 `XxxInject.java`。

骨架：

```java
package chaos.inject;

import chaos.core.BaseFaultInject;

public class LongHoldLockInject extends BaseFaultInject {

    public LongHoldLockInject(String dbType) {
        super(dbType, "LONG_HOLD_LOCK");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        // 1. 解析参数
        String durationStr = getArg(args, "-duration");
        String table = getArg(args, "-table");
        if (durationStr == null || table == null) {
            System.err.println("缺少必填参数 -duration 或 -table");
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        int holders = parseIntArg(args, "-holders", 2);
        int rows = parseIntArg(args, "-rows", 500);

        // 2. 按数据库语系初始化 SQL / JDBC 逻辑
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            // PostgreSQL/openGauss 分支
        } else if ("mysql".equals(sqlType)) {
            // MySQL/OceanBase 分支
        }

        // 3. 执行注入逻辑（多线程、事务、建临时表等）
        // 4. 持续 durationMs，finally 中清理
        System.out.println("LongHoldLock 故障注入结束");
    }

    @Override
    public void printHelp() {
        System.out.println("\nlong_hold_lock 用法: java -jar DBChaos-0.0.1.jar --db <DB_TYPE> txn long_hold_lock [OPTIONS]");
        System.out.println("  -duration  必填。故障持续时长 (ms)");
        System.out.println("  -table     必填。目标表");
        System.out.println("  -holders   选填。持锁线程数");
        System.out.println("  -rows      选填。锁行数");
    }

    private int parseIntArg(String[] args, String key, int defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : Integer.parseInt(raw);
    }
}
```

关键点：

- 继承 `BaseFaultInject`，构造时调用 `super(dbType, "FAULT_TYPE")`；
- 使用 `getArg(args, key)` / `hasArg(args, key)` 解析参数；
- 用 `getStandardDbType()` 区分 `"postgresql"` / `"mysql"`；
- 如果某个数据库有独有差异，再用 `this.dbType` 加分支；
- 注入结束必须清理临时表/释放连接。

### 3.3 在 `registry.json` 注册 Case

编辑 `DBChaos/resources/registry/registry.json`，在对应子系统的 `cases` 数组中新增：

```json
{
  "subsystem": "txn",
  "caseKey": "long_hold_lock",
  "title": "长事务持锁（新故障示例）",
  "description": "模拟多个会话长时间持有行锁，制造锁等待。",
  "injectorClass": "chaos.inject.LongHoldLockInject",
  "exampleArgs": "-duration 60000 -table bmsql_stock",
  "generatorProfiles": [
    {
      "id": 221,
      "key": "long_hold_lock",
      "description": "DBChaos Long Hold Lock",
      "category": "transaction and concurrency",
      "args": [
        "-duration", "60000",
        "-table", "bmsql_stock",
        "-holders", "2",
        "-rows", "500"
      ],
      "duringSec": 60
    }
  ]
}
```

### 3.4 构建并验证

```bash
cd DBChaos-20260630/DBChaos-20260630
./build_for_linux.sh DBChaos
```

验证 CLI 能识别：

```bash
java -jar DBChaos-0.0.1.jar --db opengauss txn long_hold_lock --help
```

---

## 4. DBChaos 侧：为故障添加 check（推荐）

如果没有 check，ResBench 注入后显示 `UNKNOWN`，无法验证故障是否生效。建议新故障同时提供 check。

### 4.1 新建 Check 类

在 `DBChaos/src/chaos/inject/` 下新建 `CheckLongHoldLockInject.java`。

```java
package chaos.inject;

import chaos.core.BaseFaultInject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class CheckLongHoldLockInject extends BaseFaultInject {

    public CheckLongHoldLockInject(String dbType) {
        super(dbType, "CHECK_LONG_HOLD_LOCK");
    }

    @Override
    public void execute(String[] args) throws Exception {
        if (args.length == 0 || hasArg(args, "-h") || hasArg(args, "--help")) {
            printHelp();
            return;
        }

        String table = getArg(args, "-table");
        if (table == null) table = "bmsql_stock";

        // 以 PostgreSQL 系为例：统计该表上的排他锁等待/持有会话数
        int locks = countLocks(table);
        boolean enforced = locks > 0;

        System.out.println(contractJson(
                enforced,
                "check-long-hold-lock",
                "lock_waiters",
                String.valueOf(locks),
                ">0",
                enforced));
    }

    private int countLocks(String table) throws Exception {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM pg_locks l JOIN pg_class c ON l.relation = c.oid " +
                     "WHERE c.relname = '" + table.replace("'", "''") + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Override
    public void printHelp() {
        System.out.println("\ncheck long_hold_lock 用法: java -jar DBChaos-0.0.1.jar --db <DB_TYPE> check long_hold_lock [OPTIONS]");
        System.out.println("  -table 选填。目标表名，默认 bmsql_stock");
        System.out.println("  -url/-user/-password 选填。连接覆盖参数");
    }

    private static String contractJson(
            boolean enforced, String summary,
            String name, String observed, String expected, boolean matched) {
        return "{\"enforced\":" + enforced
                + ",\"summary\":\"" + esc(summary)
                + "\",\"checks\":[{\"name\":\"" + esc(name)
                + "\",\"observed\":\"" + esc(observed)
                + "\",\"expected\":\"" + esc(expected)
                + "\",\"matched\":" + matched + "}]}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

约束：

- **stdout 只能输出一行契约 JSON**；
- check 必须是只读、瞬时查询；
- 建议按 `getStandardDbType()` 写 PostgreSQL / MySQL 两套查询。

### 4.2 在 `registry.json` 中注册 check

```json
{
  "subsystem": "check",
  "caseKey": "long_hold_lock",
  "title": "长事务持锁自检",
  "description": "检查 long_hold_lock 是否正在生效（按目标表锁数量判定）。",
  "injectorClass": "chaos.inject.CheckLongHoldLockInject",
  "exampleArgs": "-table bmsql_stock"
}
```

check 不需要 `generatorProfiles`，因为不会作为故障点进配置生成器。

### 4.3 验证 check

```bash
java -jar DBChaos-0.0.1.jar --db opengauss check long_hold_lock --help
```

---

## 5. ResBench 侧：在 fault-cases.yaml 中声明故障

编辑 `ResBench/backend/resbenchmark/config/fault-cases.yaml`，新增一段：

```yaml
faultinject.fault.txn.long_hold_lock:
  name: { zh: 长事务持锁, en: Long Transaction Lock Holding }
  description: { zh: 模拟多个会话长期持有行锁，制造锁等待与阻塞, en: Emulates long-running row-lock holders to cause lock waits }
  category: database
  autoRecovery: 1
  defaultDuration: 60
  asyncInjection: true
  params:
    table:
      type: string
      default: bmsql_stock
      required: true
      label: { zh: 目标表, en: Target Table }
    holders:
      type: int
      default: 2
      required: false
      label: { zh: 持锁线程数, en: Lock Holders }
    rows:
      type: int
      default: 500
      required: false
      label: { zh: 锁定行数, en: Locked Rows }
    duration:
      type: int
      unit: s
      default: 60
      required: false
      label: { zh: 持续时间, en: Duration }
  injection:
    command: >-
      java -jar {{DBCHAOS_JAR}} --db {{dbType}} txn long_hold_lock
      -duration {{duration|60|1000}} -table {{table|bmsql_stock}}
      -holders {{holders|2}} -rows {{rows|500}}
      -url {{dbUrl}} -user {{user}} -password {{password}}
  check:
    command: >-
      java -jar {{DBCHAOS_JAR}} --db {{dbType}} check long_hold_lock
      -table {{table|bmsql_stock}}
      -url {{dbUrl}} -user {{user}} -password {{password}}
```

说明：

- `{{DBCHAOS_JAR}}` / `{{dbType}}` / `{{dbUrl}}` / `{{user}}` / `{{password}}` 是框架上下文变量；
- `{{duration|60|1000}}` 表示：表单参数 duration 缺省 60，再乘 1000 转毫秒；
- 不配 `check` 时故障仍可注入，但验证状态为 UNKNOWN；
- `asyncInjection: true` 表示 DBChaos 持续型故障使用 Agent dispatch 模式。

---

## 6. 前端

**通常不需要改前端代码。**

前端启动时会调用 `/suite/faults/catalog`，把 yaml 中新增的故障动态加入：

- 分类 tab 自动出现；
- 参数表单自动渲染；
- 故障卡片自动显示 `hasCheck`；
- 注入后会显示 ENFORCED / NOT_ENFORCED / ERROR / UNKNOWN 徽章。

如果前端内置 `frontend/src/config/faultConfig.js` 中没有该类型，`hydrateFaultCatalog()` 会自动创建动态定义。

---

## 7. 重启/生效与验证

### 7.1 重新构建 DBChaos

```bash
cd DBChaos-20260630/DBChaos-20260630
./build_for_linux.sh DBChaos
```

并部署到 Agent 实际使用的路径，例如：

```bash
cp DBChaos-0.0.1.jar /opt/DBChaos/DBChaos.jar
```

### 7.2 重启 ResBench Engine / 重新加载目录

修改 `fault-cases.yaml` 后，重启 Engine 让 `FaultCatalogLoader` 重新加载：

```text
停止 Engine → 启动 Engine → 检查日志：
[FaultCatalog] loaded N fault definitions from ...
```

### 7.3 手工验证

先验证 DBChaos 命令：

```bash
java -jar DBChaos-0.0.1.jar --db opengauss txn long_hold_lock --help
java -jar DBChaos-0.0.1.jar --db opengauss check long_hold_lock --help
```

再在前端“数据库配置 → 工作负载配置 → 不利因素注入”中看到新故障，加入并开始测试。

---

## 8. 检查清单

| 检查项 | 是否完成 |
|---|---|
| DBChaos 注入器类已创建 | ☐ |
| DBChaos `registry.json` 已注册 Case | ☐ |
| DBChaos 已重新构建 | ☐ |
| （推荐）check 类已创建并在 registry 注册 | ☐ |
| ResBench `fault-cases.yaml` 已新增故障定义 | ☐ |
| `{{DBCHAOS_JAR}}/{{dbType}}/{{dbUrl}}` 等模板变量正确 | ☐ |
| Engine 重启后日志显示加载到该故障 | ☐ |
| 前端能展示该故障和参数表单 | ☐ |
| 测试中能注入，并看到验证徽章 | ☐ |

---

## 9. 常见问题

### 9.1 不写 check，会影响使用吗？

不影响注入。ResBench 会显示 `UNKNOWN`，表示“未配置自检，无法确认是否生效”。

### 9.2 为什么 check 输出必须是 JSON？

ResBench Agent 的 `/check` 只做透传：

```text
DBChaos check stdout → Agent 校验是否为契约 JSON → ResBench 判定状态
```

如果 stdout 里有额外日志、ANSI 彩色字符，Agent 会返回错误，ResBench 显示 `ERROR`。

### 9.3 只需要支持 openGauss，也要写 MySQL 分支吗？

建议按 `getStandardDbType()` 写两套，或者至少写 PostgreSQL 系分支。

如果不适配 MySQL，使用 `--db mysql` 时该 Case 会执行不兼容 SQL 而失败。如果当前只面向 openGauss 部署，可以只在代码注释中说明“暂只支持 PG 系”，ResBench yaml 侧仅对该 DB 显示。

### 9.4 能否用脚手架自动生成？

可以：

```bash
cd DBChaos-20260630/DBChaos-20260630
./scripts/scaffold/new_case.sh \
  --subsystem txn \
  --case long_hold_lock \
  --class-name LongHoldLockInject \
  --title "长事务持锁" \
  --description "模拟长时间持锁" \
  --example-args "-duration 60000 -table bmsql_stock" \
  --generator-id 221 \
  --generator-key long_hold_lock \
  --generator-description "DBChaos Long Hold Lock" \
  --generator-category "transaction and concurrency" \
  --generator-args "-duration 60000 -table bmsql_stock -holders 2 -rows 500" \
  --generator-during-sec 60
```

脚手架的 check 类需要自行补充，因为当前脚手架只生成注入器骨架。
