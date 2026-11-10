# How to Add a New Database Fault (DBChaos + ResBench End to End)

> Applies to: DBChaos 0.0.1 + ResBench fault-verification branch
>
> This document describes the complete integration process from "fault injection implementation" to "ResBench frontend selectable/verifiable".
> Core idea: **Fault implementations live in DBChaos, fault definitions live in the ResBench YAML, and the frontend displays them automatically and dynamically.**

---

## 1. Architecture Overview

```text
ResBench frontend
  → GET /suite/faults/catalog
      → ResBench Engine reads backend/resbenchmark/config/fault-cases.yaml
          → generates injection/self-check commands
              → Agent /inject, /check
                  → DBChaos: java -jar DBChaos.jar --db <db> <subsystem> <case> ...
```

Therefore, adding a new fault usually requires modifying two repositories:

| Layer | What to do | File location |
|---|---|---|
| DBChaos | Implement a new injection Case (and a check self-test when necessary) | `DBChaos/src/chaos/inject/`, `DBChaos/resources/registry/registry.json` |
| ResBench | Register the fault in the fault catalog, and configure its parameters, injection command, and check command | `ResBench/backend/resbenchmark/config/fault-cases.yaml` |

The frontend usually **does not need code changes**: the fault list is driven dynamically by `/suite/faults/catalog`.

---

## 2. Complete Workflow

```text
DBChaos side:
  1. Choose a subsystem + a new fault keyword, e.g. txn / long_hold_lock
  2. Create the injector class LongHoldLockInject
  3. Register the Case in registry.json
  4. (Recommended) Write a check self-test for it
  5. Build DBChaos.jar and verify manually

ResBench side:
  6. Add a fault definition in fault-cases.yaml
  7. Configure the injection command template
  8. (Optional but recommended) Configure the check command template
  9. Restart/hot-reload the Engine
  10. The new fault appears automatically in the frontend; start testing and observe the verification badge
```

---

## 3. DBChaos Side: Implementing Fault Injection

### 3.1 Determine the Subsystem and Case Name

DBChaos has the following subsystems:

- `session`: connections and sessions
- `sql`: SQL compilation and optimization
- `exec`: execution engine
- `txn`: transactions and concurrency control
- `storage`: storage engine
- `buffer`: buffer management
- `log`: logging/recovery
- `quota`: resource governance
- `check`: dedicated subsystem for self-checks

Attach new faults to existing subsystems whenever possible, and avoid adding new subsystems unnecessarily.

### 3.2 Create the Injector Class

Create `XxxInject.java` under `DBChaos/src/chaos/inject/`.

Skeleton:

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

        // 1. Parse arguments
        String durationStr = getArg(args, "-duration");
        String table = getArg(args, "-table");
        if (durationStr == null || table == null) {
            System.err.println("Missing required parameter -duration or -table");
            printHelp();
            return;
        }

        long durationMs = Long.parseLong(durationStr);
        int holders = parseIntArg(args, "-holders", 2);
        int rows = parseIntArg(args, "-rows", 500);

        // 2. Initialize SQL / JDBC logic per database family
        String sqlType = getStandardDbType();
        if ("postgresql".equals(sqlType)) {
            // PostgreSQL/openGauss branch
        } else if ("mysql".equals(sqlType)) {
            // MySQL/OceanBase branch
        }

        // 3. Execute the injection logic (multi-threading, transactions, temporary tables, etc.)
        // 4. Last for durationMs, clean up in finally
        System.out.println("LongHoldLock fault injection finished");
    }

    @Override
    public void printHelp() {
        System.out.println("\nlong_hold_lock usage: java -jar DBChaos-0.0.1.jar --db <DB_TYPE> txn long_hold_lock [OPTIONS]");
        System.out.println("  -duration  Required. Fault duration (ms)");
        System.out.println("  -table     Required. Target table");
        System.out.println("  -holders   Optional. Number of lock-holding threads");
        System.out.println("  -rows      Optional. Number of locked rows");
    }

    private int parseIntArg(String[] args, String key, int defaultValue) {
        String raw = getArg(args, key);
        return raw == null ? defaultValue : Integer.parseInt(raw);
    }
}
```

Key points:

- Extend `BaseFaultInject` and call `super(dbType, "FAULT_TYPE")` in the constructor;
- Use `getArg(args, key)` / `hasArg(args, key)` to parse arguments;
- Use `getStandardDbType()` to distinguish `"postgresql"` / `"mysql"`;
- If a database has its own unique differences, add branches based on `this.dbType`;
- Temporary tables must be cleaned up and connections released when injection ends.

### 3.3 Register the Case in `registry.json`

Edit `DBChaos/resources/registry/registry.json` and add the following to the `cases` array of the corresponding subsystem:

```json
{
  "subsystem": "txn",
  "caseKey": "long_hold_lock",
  "title": "Long transaction lock holding (new fault example)",
  "description": "Simulates multiple sessions holding row locks for a long time, causing lock waits.",
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

### 3.4 Build and Verify

```bash
cd DBChaos-20260630/DBChaos-20260630
./build_for_linux.sh DBChaos
```

Verify that the CLI recognizes it:

```bash
java -jar DBChaos-0.0.1.jar --db opengauss txn long_hold_lock --help
```

---

## 4. DBChaos Side: Adding a check for the Fault (Recommended)

Without a check, ResBench displays `UNKNOWN` after injection and cannot verify whether the fault took effect. It is recommended to provide a check together with a new fault.

### 4.1 Create the Check Class

Create `CheckLongHoldLockInject.java` under `DBChaos/src/chaos/inject/`.

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

        // Using the PostgreSQL family as an example: count the sessions waiting for/holding exclusive locks on the table
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
        System.out.println("\ncheck long_hold_lock usage: java -jar DBChaos-0.0.1.jar --db <DB_TYPE> check long_hold_lock [OPTIONS]");
        System.out.println("  -table Optional. Target table name, defaults to bmsql_stock");
        System.out.println("  -url/-user/-password Optional. Connection override parameters");
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

Constraints:

- **stdout must output only one line of contract JSON**;
- check must be a read-only, instantaneous query;
- It is recommended to write two sets of queries for PostgreSQL / MySQL based on `getStandardDbType()`.

### 4.2 Register the check in `registry.json`

```json
{
  "subsystem": "check",
  "caseKey": "long_hold_lock",
  "title": "Long transaction lock-holding self-check",
  "description": "Checks whether long_hold_lock is currently in effect (based on the number of locks on the target table).",
  "injectorClass": "chaos.inject.CheckLongHoldLockInject",
  "exampleArgs": "-table bmsql_stock"
}
```

check does not need `generatorProfiles`, because it is not used as a fault point by the configuration generator.

### 4.3 Verify the check

```bash
java -jar DBChaos-0.0.1.jar --db opengauss check long_hold_lock --help
```

---

## 5. ResBench Side: Declaring the Fault in fault-cases.yaml

Edit `ResBench/backend/resbenchmark/config/fault-cases.yaml` and add a new block:

```yaml
faultinject.fault.txn.long_hold_lock:
  name: { zh: Long Transaction Lock Holding, en: Long Transaction Lock Holding }
  description: { zh: Emulates multiple sessions holding row locks for a long time, causing lock waits and blocking, en: Emulates long-running row-lock holders to cause lock waits }
  category: database
  autoRecovery: 1
  defaultDuration: 60
  asyncInjection: true
  params:
    table:
      type: string
      default: bmsql_stock
      required: true
      label: { zh: Target Table, en: Target Table }
    holders:
      type: int
      default: 2
      required: false
      label: { zh: Lock Holders, en: Lock Holders }
    rows:
      type: int
      default: 500
      required: false
      label: { zh: Locked Rows, en: Locked Rows }
    duration:
      type: int
      unit: s
      default: 60
      required: false
      label: { zh: Duration, en: Duration }
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

Notes:

- `{{DBCHAOS_JAR}}` / `{{dbType}}` / `{{dbUrl}}` / `{{user}}` / `{{password}}` are framework context variables;
- `{{duration|60|1000}}` means: the form parameter duration defaults to 60, then is multiplied by 1000 to convert it to milliseconds;
- If `check` is not configured, the fault can still be injected, but the verification status is UNKNOWN;
- `asyncInjection: true` indicates that the DBChaos continuous fault uses the Agent dispatch mode.

---

## 6. Frontend

**No frontend code changes are usually required.**

At startup, the frontend calls `/suite/faults/catalog` and dynamically adds the faults newly declared in the yaml:

- Category tabs appear automatically;
- The parameter form is rendered automatically;
- Fault cards automatically display `hasCheck`;
- After injection, an ENFORCED / NOT_ENFORCED / ERROR / UNKNOWN badge is displayed.

If the fault type is not present in the frontend built-in `frontend/src/config/faultConfig.js`, `hydrateFaultCatalog()` automatically creates a dynamic definition.

---

## 7. Restart/Take Effect and Verification

### 7.1 Rebuild DBChaos

```bash
cd DBChaos-20260630/DBChaos-20260630
./build_for_linux.sh DBChaos
```

and deploy it to the path actually used by the Agent, for example:

```bash
cp DBChaos-0.0.1.jar /opt/DBChaos/DBChaos.jar
```

### 7.2 Restart the ResBench Engine / Reload the Catalog

After modifying `fault-cases.yaml`, restart the Engine to make `FaultCatalogLoader` reload it:

```text
Stop Engine → Start Engine → Check the log:
[FaultCatalog] loaded N fault definitions from ...
```

### 7.3 Manual Verification

First verify the DBChaos commands:

```bash
java -jar DBChaos-0.0.1.jar --db opengauss txn long_hold_lock --help
java -jar DBChaos-0.0.1.jar --db opengauss check long_hold_lock --help
```

Then locate the new fault in the frontend under "Database Configuration → Workload Configuration → Fault Injection", add it, and start the test.

---

## 8. Checklist

| Check Item | Completed |
|---|---|
| DBChaos injector class created | ☐ |
| Case registered in DBChaos `registry.json` | ☐ |
| DBChaos rebuilt | ☐ |
| (Recommended) check class created and registered in the registry | ☐ |
| Fault definition added to ResBench `fault-cases.yaml` | ☐ |
| Template variables such as `{{DBCHAOS_JAR}}/{{dbType}}/{{dbUrl}}` are correct | ☐ |
| After the Engine restarts, the log shows that the fault was loaded | ☐ |
| The frontend can display the fault and its parameter form | ☐ |
| The fault can be injected during a test, and the verification badge is displayed | ☐ |

---

## 9. FAQ

### 9.1 Does not writing a check affect usage?

It does not affect injection. ResBench displays `UNKNOWN`, which means "no self-check configured, effectiveness cannot be confirmed".

### 9.2 Why must the check output be JSON?

The ResBench Agent's `/check` only passes data through:

```text
DBChaos check stdout → Agent validates whether it is contract JSON → ResBench determines the status
```

If stdout contains extra logs or ANSI color characters, the Agent returns an error and ResBench displays `ERROR`.

### 9.3 If I only need to support openGauss, do I still need to write a MySQL branch?

It is recommended to write two sets based on `getStandardDbType()`, or at least write the PostgreSQL-family branch.

If MySQL is not supported, the Case will execute incompatible SQL and fail when `--db mysql` is used. If the current deployment targets openGauss only, you can note "PG family only for now" in code comments and display the fault only for that DB on the ResBench yaml side.

### 9.4 Can a scaffold generate it automatically?

Yes:

```bash
cd DBChaos-20260630/DBChaos-20260630
./scripts/scaffold/new_case.sh \
  --subsystem txn \
  --case long_hold_lock \
  --class-name LongHoldLockInject \
  --title "Long transaction lock holding" \
  --description "Simulates long-term lock holding" \
  --example-args "-duration 60000 -table bmsql_stock" \
  --generator-id 221 \
  --generator-key long_hold_lock \
  --generator-description "DBChaos Long Hold Lock" \
  --generator-category "transaction and concurrency" \
  --generator-args "-duration 60000 -table bmsql_stock -holders 2 -rows 500" \
  --generator-during-sec 60
```

The check class for the scaffold must be added manually, because the current scaffold only generates the injector skeleton.
