# Database Fault Injection Verification Mechanism — Design Document

> Version: v2.2 (intermediate-state solution: **fault self-check pushed down to DBChaos + resbench configuration-driven**; v2.1 incorporates review resolutions: i18n fallback rules, no-check fault prompts, agent `/check` positioning clarification; v2.2 incorporates: verification timing coupled with fault duration, polling interval 7s)
> Status: under review (v2.2 resolutions incorporated)
> Background decision: the v1 solution placed verification SQL probes in the framework registry; adding a new fault required modifying framework code, which did not satisfy the requirement that "adding a fault only changes the fault implementation", so it was abandoned. v2 adopts: each fault implements its own self-check through the **injector itself**, and resbench provides a **unified invocation protocol** and a **configuration-based fault catalog**.

---

## 1. Background and Problem

### 1.1 Current State

```
Frontend form → StartByFormService (hardcoded switch for 18 fault types) → generate config XML
  → FaultRunner scheduling → FaultInjector → POST agent/inject {cmd,args}
    → agent exec() → `java -jar DBChaos.jar <dbType> <subsystem> <case_key> ...`
  → FaultResult{endPoint, result} (result is merely the raw string from command execution)
  → FaultInjectInfo persisted → frontend display
```

### 1.2 Core Problems

1. **Injection ≠ effect**: `FaultResult` only proves that the command returned 0; it does not prove that the fault produced the expected effect on the database (connection count surge, deadlock counter growth, lock waits appearing, ...).
2. **Fault failures are silent**: missing DBChaos jar, incorrect DB connection parameters, nonexistent target table, early termination by timeout, etc., all manifest as "injection succeeded but had no effect", which the current pipeline cannot detect.
3. **No unified determination interface**: the framework has no structured way to answer "did this fault take effect".

### 1.3 Solution Decisions (v2)

| Decision point | Conclusion | Rationale |
|---|---|---|
| Where verification logic lives | **Inside the fault injector (DBChaos-side self-check)** | The fault implementation itself best understands "whether it took effect"; framework-side SQL probes are a second-best understanding, and adding faults would require changing the framework |
| Framework role | Unified protocol invocation + polling determination + display | Does not parse fault details |
| Fault catalog | **Configuration-driven** (resbench fault-cases.yaml) | Goal of this iteration: adding a fault requires no Java code changes and no compilation; intermediate state, may later upgrade to a fully dynamic registry |
| Scope | Database-type faults only (18 DBChaos types) | Host-level faults (chaosblade) in a later iteration |

**Achieved outcome (acceptance criteria of this solution)**:
- After each DB fault is successfully injected, the framework automatically invokes that fault's self-check command and produces a four-state determination.
- Unified interface: Java `FaultVerifier` + `FaultInjectInfo.verification` + REST.
- Adding a new fault = write the injector in DBChaos (including self-check) + add a section to resbench `fault-cases.yaml`. **No Java code changes, no recompilation**.

---

## 2. Overall Architecture

```
┌─ DBChaos repository (fault implementation layer — each fault carries its own self-check) ─┐
│  interface SelfCheckable { SelfCheckResult check(params); }        │
│  CLI adds the check subcommand                                     │
│    java -jar DBChaos.jar <dbType> check <subsystem> <case_key>     │
│          -url ... -user ... -password ... [-table ...]             │
│    → stdout JSON: {"enforced":bool,"summary":str,"checks":[...]}   │
│  (each injector implements check(): query connection count/deadlock count/lock waits/dead tuples…) │
└──────────────────────────────────────────────────────────────────┘
┌─ Agent (transparent bridge, one-time change) ─────────────────────┐
│  POST /check {cmd,args} → execute DBChaos check → pass through JSON │
└──────────────────────────────────────────────────────────────────┘
┌─ resbench (unified invocation layer — main build work in this iteration) ─┐
│  fault-cases.yaml         fault catalog (injection template + check template + params) │
│  FaultCatalogLoader       load yaml → definition objects (replaces the hardcoded switch) │
│  FaultVerifier            injection succeeded → warmup → poll POST /check  │
│                           → four-state determination ENFORCED/NOT_ENFORCED/... │
│  FaultInjectInfo.verification  result persisted + exposed via REST │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. Core Contract: Unified Protocol for Fault Self-Check

> This is the only contract between the framework and fault implementations. The DBChaos side implements to it, the resbench side invokes according to it, and the two sides develop in parallel without blocking each other.

### 3.1 Self-Check Interface (inside the DBChaos repository)

```java
// New in DBChaos: SelfCheckable.java
public interface SelfCheckable {
    /** Self-check the current injection effect. Returns enforced=false if not in effect, with evidence. */
    SelfCheckResult check(Map<String, String> params) throws Exception;
}

public class SelfCheckResult {
    private boolean enforced;              // determination: whether the effect was achieved
    private String summary;                // human-readable conclusion, e.g. "deadlocks 0 → 37"
    private List<CheckItem> checks;        // item-by-item evidence
    // getters/setters/static factory...
}

public class CheckItem {
    private String name;       // evidence item name, e.g. "deadlocks_delta"
    private String observed;   // measured value, e.g. "37"
    private String expected;   // expectation, e.g. ">1"
    private boolean matched;   // whether this item is satisfied
}
```

### 3.2 CLI check Subcommand (DBChaos main program)

```
java -jar DBChaos.jar <dbType> check <subsystem> <case_key> [same valid parameters as injection]

Example:
java -jar DBChaos.jar opengauss check txn deadlock_storm \
    -url jdbc:postgresql://YOUR_DB_HOST:15432/postgres \
    -user benchmarksql -password '***' -table bmsql_stock

On success, stdout output (the only output channel; logging to stdout is forbidden):
{"enforced":true,"summary":"pg_stat_database.deadlocks 0 → 37",
 "checks":[{"name":"deadlocks_delta","observed":"37","expected":">1","matched":true}]}

Conventions:
- For enforced, see the determination vocabulary in 4.1; if not in effect, evidence (observed/expected) must be provided
- Injector has not implemented check / missing parameters / DB unreachable → returncode != 0, stdout outputs {"error":"<reason>"}
- check must return promptly (database queries only); long-running execution is forbidden; the CLI layer enforces a 30s timeout as a fallback
```

### 3.3 New Agent Endpoint (Python FastAPI)

```
POST /check
body: {"cmd": "java", "args": ["-jar", "...", "<dbType>", "check", ...]}
behavior: exec(commands) (reuses utils.tools.exec, timeout 30s)
returns:
  command succeeded and stdout parses as {"enforced":...,"checks":[...]} → pass through that JSON verbatim
  command returncode != 0 → {"error": "<stderr/reason>", "returncode": <rc>}
  stdout is not JSON         → {"error": "invalid self-check output: <first 200 chars>"}
```

Reuses the `/inject` execution channel, differing only in semantics; no new dependencies.

> **Review confirmation (v2.1)**: `/check` and `/inject` are completely isomorphic in structure—the same executor (`utils.tools.exec`), the same timeout (30s on the agent side), differing only in semantics (injection vs. self-check). The agent **performs no fault-semantic judgment whatsoever**; it does only three things: (1) execute the command; (2) validate whether stdout is the contract JSON; (3) wrap errors. This layer of "execute + validate + wrap" is the precondition for the framework's four-state determination: the `enforced` in the passed-through JSON determines ENFORCED/NOT_ENFORCED, `{"error":...}` (including malformed format) determines ERROR (terminating polling immediately), and network-layer failures determine UNKNOWN.

---

## 4. Unified Determination Model (resbench-common)

### 4.1 Status Enumeration

```java
public enum FaultVerificationStatus {
    ENFORCED,       // received a valid contract once during polling with enforced=true
    NOT_ENFORCED,   // polling reached timeout; the last check was valid and enforced=false
    ERROR,          // injection command failed, or check returned {error:}/invalid response format
    UNKNOWN         // no check command configured / network layer cannot reach the agent (no valid result obtained)
}
```

### 4.2 Result Object

```java
public class FaultVerificationResult {
    String faultID;
    String faultName;
    String nameKey;                 // e.g. faultinject.fault.txn.deadlock_storm
    String phase;                   // INJECT | RECOVER (only INJECT in this iteration)
    FaultVerificationStatus status;
    String summary;                 // self-check summary or framework determination summary
    List<CheckEvidence> evidence;   // self-check checks[] passed through
    String endPoint;                // agent address that executed the self-check
    long warmupMs, pollIntervalMs, timeoutMs;
    long verifiedAt;
    String error;                   // reason for ERROR/UNKNOWN (mandatory for all ERROR/UNKNOWN branches)
}

public class CheckEvidence {
    String name; String observed; String expected; boolean matched;
}
```

### 4.3 Determination Vocabulary (framework semantics)

| Framework status | Trigger condition | Frontend |
|---|---|---|
| `ENFORCED` | Received a valid contract once during polling with `enforced=true` (**polling terminated immediately**) | ✅ green |
| `NOT_ENFORCED` | Polling reached timeout; the **last** check returned a valid contract with `enforced=false` | ❌ red |
| `ERROR` | The injection command itself failed (verification not entered); or any check returned `{error:}`/invalid response format (**polling terminated immediately**) | ⚠️ orange |
| `UNKNOWN` | No check template; or the network layer cannot reach the agent and the last poll has no result | — gray (with reason) |

> **Determination rules (review revision)**: polling is terminated as soon as `enforced=true` or `ERROR` appears; in all other cases **the last poll result prevails**. A correctly formatted response can only produce ENFORCED / NOT_ENFORCED; `{error:}` or an invalid format is determined as ERROR; only network-layer failures (cannot connect / connect/read timeout, without receiving any response from the agent side) are determined as UNKNOWN—only "not knowing what happened on the agent side" counts as unknown; receiving a response whose content is an error or whose format is wrong means it is clearly known that an error occurred. **All UNKNOWN / ERROR branches must call `setError(reason)`** (e.g. "no self-check command configured (no check template)", "check invocation failed: <exception>"), viewable by hovering over the badge in the frontend.

---

## 5. Configuration-Based resbench Fault Catalog (fault-cases.yaml)

### 5.1 File Location

- Runtime: `config/fault-cases.yaml` (at the same level as the existing config templates, externally maintainable)
- Packaging fallback: `src/main/resources/fault-cases.yaml` (loaded when the runtime file does not exist)
- **Loading order: external file takes precedence; if missing, automatically falls back to classpath**, and the startup log records the actual source

### 5.2 Single Fault Definition Schema

```yaml
faultinject.fault.txn.deadlock_storm:          # ← nameKey (unique key)
  name: Deadlock Storm                          # display name (replaces description)
  description: Cross-wait graph and deadlock detection overload   # optional, Chinese description
  autoRecovery: 1                               # consistent with the current FaultTemplate.autoRecovery
  params:                                       # parameter schema → frontend form rendering + validation + default values
    duration:    { type: int,    unit: seconds, default: 60,   label: Duration }
    table:       { type: string, default: bmsql_stock, label: Target table }
    anchors:     { type: int,    default: 2,    label: Anchor transaction count }
    anchor-rows: { type: int,    default: 20,   label: Anchor row count }
    waiters:     { type: int,    default: 16,   label: Waiting transaction count }
    hot-rows:    { type: int,    default: 32,   label: Hot row count }
  injection:                                    # injection command template
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} txn deadlock_storm
      -duration {{duration|60|1000}} -table {{table|bmsql_stock}}
      -anchors {{anchors|2}} -anchor-rows {{anchor-rows|20}}
      -waiters {{waiters|16}} -hot-rows {{hot-rows|32}}
      -url {{dbUrl}} -user {{user}} -password {{password}}
  check:                                        # self-check command template (new in this iteration)
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} check txn deadlock_storm
      -url {{dbUrl}} -user {{user}} -password {{password}} -table {{table|bmsql_stock}}
# Faults without configured check → injection proceeds normally, verification is UNKNOWN (zero impact on old faults)
```

### 5.3 Template Syntax

| Syntax | Semantics |
|---|---|
| `{{dbUrl}}` / `{{user}}` / `{{password}}` / `{{dbType}}` / `{{DBCHAOS_JAR}}`/`{{agentHost}}` | **Framework context variables**, injected automatically (from the form's DB connection information and configuration) |
| `{{param}}` | Form parameter, required; if missing → error when generating configuration, with a prompt |
| `{{param|default}}` | Nullable, uses default when omitted |
| `{{param|default|1000}}` | Numeric parameter multiplied by 1000 (duration seconds → milliseconds), type must be int/float |

### 5.4 Catalog Interface (supporting frontend dynamization)

```
GET /web/faults/catalog → read fault-cases.yaml, return:
[ { "nameKey": "faultinject.fault.txn.deadlock_storm",
    "name": "Deadlock Storm",
    "description": "...",
    "params": [ {key,label,type,unit,default} ] } , ... ]

Consumer: frontend FaultSelector (fault dropdown + dynamic rendering of the parameter form).
After this one-time change, adding a fault only requires adding a section to the yaml, and it automatically appears in the frontend.

#### 5.4.1 Display Text and i18n Resolution Rules (review confirmation v2.1)

A catalog entry returns `nameKey` + display text (`name`/`description`/parameter `label`), where the yaml display text serves as the **fallback language** (it is recommended to standardize on either Chinese or English as a documentation-level convention; when both languages coexist, the dictionary controls switching).

Frontend resolution order (consolidated into one helper, e.g. `resolveFaultText(item)`):

1. `translate(nameKey)` / `translate(parameter label)` hits the existing zh/en dictionary → use the dictionary translation (ensuring no loss when switching between Chinese and English);
2. dictionary miss → fall back to the yaml display text returned by the catalog.

Effect: translations for old faults are preserved at zero cost; new faults need only one text entry in the yaml to display immediately, without affecting switching between the two languages.
```

---

## 6. Unified Verification Executor (FaultVerifier)

### 6.1 Flow (verification after injection)

```
FaultRunner injection task (duration = hold time of this fault, from yaml defaultDuration / form value)
  ├─ FaultInjector.injectFaults()
  │    └─ any FaultResult contains an error / agent unreachable / injection result empty
  │          └─ verification = ERROR, end          (verification not entered)
  ├─ FaultVerifier.verifyAfterInjection(fault, dbservice, agentEndpoint, durationMs)
  │    ├─ 1. obtain fault.CHECK command (XML <check>, rendered from the yaml template)
  │    │      └─ none → UNKNOWN (with reason: no self-check command configured), end
  │    ├─ 2. warmup (default 3s) — wait for the fault effect to manifest; can be overridden by yaml check.warmupMs
  │    ├─ 3. polling loop (pollIntervalMs default 7s; **the whole-round end time is coupled with duration**, see §6.4):
  │    │      POST agent/check {cmd,args}
  │    │      ├─ enforced=true            → ENFORCED, break
  │    │      ├─ returns {error:}/invalid format → ERROR, break
  │    │      ├─ enforced=false           → record NOT_ENFORCED + evidence, continue polling
  │    │      └─ network-layer failure (cannot connect/timeout) → record UNKNOWN (with reason: last exception), continue polling
  │    │      timeout → the last poll result prevails (NOT_ENFORCED with the last evidence; UNKNOWN with the last exception)
  │    └─ 4. assemble FaultVerificationResult (record the actually effective warmupMs/pollIntervalMs/timeoutMs)
  └─ write back faultInjectInfo.setVerification(...) → persist via writeFaults()
```

### 6.2 Key Conventions

1. **Verification connections and injection commands share the same DB credentials** ({{user}}/{{password}} inside the check template), with no new configuration.
2. **Instantaneity of check**: a single request timeout of 15s (30s fallback on the agent side); a slow single probe does not affect the whole-round window, see §6.4.
3. **Serial execution**: verification runs serially within the injection task (warmup + whole-round window, see §6.4) and does not block scheduling of other faults; verification in concurrent fault scenarios is likewise concurrent (independent instances).
4. **Determination results do not block**: ENFORCED / NOT_ENFORCED / ERROR / UNKNOWN only mark + alert, with no automatic retry (this iteration).
5. **Switch**: `fault.verification.enabled=true|false`; false restores the old behavior (no check invocation).

### 6.3 Configuration Items (merged into the existing configuration)

```properties
fault.verification.enabled=true
fault.verification.warmupMs=3000          # wait before the first probe (global default; can be overridden by yaml check.warmupMs)
fault.verification.pollIntervalMs=7000    # polling interval (v2.2: 2s → 7s; can be overridden by yaml check.pollIntervalMs)
fault.verification.graceMs=30000          # end margin when coupled with duration (new in v2.2)
fault.verification.capMs=300000           # upper bound of the whole-round window, 5min (new in v2.2, prevents very long durations from blocking scheduling)
fault.verification.minTimeoutMs=7000      # lower bound of the whole-round window, ≥1 probe (new in v2.2)
fault.verification.timeoutMs=(not set)      # manual absolute override: when explicitly set, replaces the formula, without adding grace/cap (for operational intervention)
fault.verification.maxRequestTimeoutMs=15000
fault.verification.catalogPath=config/fault-cases.yaml   # this path by default, then fall back to classpath
```

### 6.4 Coupling of Verification Timing with Fault Duration (v2.2)

**Motivation**: in v2.1 the whole-round window was hardcoded to 30s — a short 5s fault would wait 30s in vain, and a long 120s fault would stop early before verification completed while the effect was still active; both cases are unreasonable. The whole-round window is changed to follow the hold time of the current fault.

**Window formula** (`FaultRunner` passes in the current fault's `durationMs = work.getDuration() * 1000`):

```
effectiveTimeoutMs = clamp(durationMs + graceMs, minTimeoutMs, capMs)
```

- Timeline: verification start = injection completion time; end point = injection completion + `effectiveTimeoutMs`, i.e. **fault hold period + end buffer**.
- Meaning of `graceMs`: after the hold period ends, capture the "last confirmation before the recovery boundary", and tolerate scheduling jitter / polling interval margin.
- Meaning of `minTimeoutMs` / `capMs`: short faults (e.g. 1s) have at least 1 probe; extremely long durations (e.g. 1h) do not occupy the injection scheduling thread indefinitely (an execution-model constraint of serial verification).
- Manual intervention: after explicitly setting `fault.verification.timeoutMs`, it **wholly replaces the formula** (no clamp, no grace added) — suitable for operations pinning a fixed value for a specific environment.

**Per-fault yaml override** (finest granularity, highest priority, passed through XML `<check>`):

```yaml
faultinject.fault.txn.deadlock_storm:
  check:
    command: >-
      java -jar {{DBCHAOS_JAR}} {{dbType}} check txn deadlock_storm
      -url {{dbUrl}} -user {{user}} -password {{password}}
    timeoutMs: 90000      # optional: whole-round window (clamped to [minTimeoutMs, capMs])
    pollIntervalMs: 5000  # optional: polling interval dedicated to this fault
    warmupMs: 1000        # optional: first-probe wait dedicated to this fault
```

Priority (high → low): **yaml check.<key> > global system property > formula default**.

**Polling count reference** (pollIntervalMs=7s, graceMs=30s, warmup=3s):

| duration | Whole-round window | Number of probes (approx.) |
|---|---|---|
| 5s | 35s | 4 |
| 30s | 60s | 8 |
| 60s | 90s | 12 |
| 120s | 150s | 21 |
| 600s | 300s (truncated by cap) | 42 |
| 1h | 300s (truncated by cap) | 42 |

**Implementation surface (to be implemented in v2.2)**:
1. `FaultVerifier`: change the default `pollIntervalMs` to 7000; add `graceMs()/capMs()/minTimeoutMs()`; add a `durationMs` parameter to `verifyAfterInjection`, internally `resolveEffectiveTimeoutMs(faultCheck, durationMs)` merges yaml overrides (`Fault.CHECK` extended with timeoutMs/pollIntervalMs/warmupMs fields, XML `<check>` attributes passed through; old XML without attributes → null → global default).
2. `FaultInjector.FaultRunner`: pass `work.getDuration() * 1000` at both call sites.
3. `FaultDefinition` (yaml loading) and `StartByFormService.replaceFaultCases` output `<check timeoutMs=... pollIntervalMs=... warmupMs=...>`.
4. `FaultVerificationResult` already contains the actually effective warmupMs/pollIntervalMs/timeoutMs — directly visible when hovering over the frontend badge, **with zero frontend changes**.

---

## 7. XML Extension and Data Channel

### 7.1 Fault / XML

- `Fault` adds a new field `public final Command CHECK;` (nullable).
- `loadFaultFromXML` parses the **optional** `<check>` node inside `<case>` (same structure as `<injection>`: `<cmd>` + `args`).
- Old XML without `<check>`: CHECK=null → verification returns UNKNOWN without affecting the existing flow.
- When `StartByFormService.replaceFaultCases` generates `<case>`, it outputs `<check>` if a check template exists.

### 7.2 FaultInjectInfo (data channel)

New fields (nullable, compatible with old data):

```java
private FaultVerificationResult verification;          // verification result after injection
private FaultVerificationResult recoveryVerification;  // reserved: verification after recovery (not populated in this iteration)
```

Persisted with `tmp/faultInject.json` and history; the existing `getFaultInjectionInfo` brings them out automatically and the frontend consumes them directly, with no new query interface needed.

### 7.3 REST

| Method | Path | Description |
|---|---|---|
| `GET` | `/web/faults/catalog` | Fault catalog (yaml-driven), frontend list/form rendering |
| `POST` | `/web/fault/verify` | **Manual re-verification**: `{databaseIp, agentPort?, nameKey, params?}` → immediately executes one check and returns `FaultVerificationResult` (for out-of-run/historical scenario troubleshooting) |
| `POST` | `/web/fault/orchestration/check` | Orchestration reasonableness check (Engine): `{faults: [{nameKey, groupKey, ...}]}` → based on the Neo4j knowledge graph, checks whether faults injected simultaneously in the same group are mutually exclusive/duplicated; see [orchestration-reasonableness-check.md](orchestration-reasonableness-check.md) |
| `POST` | `/suite/fault/orchestration/check` | Orchestration reasonableness check (Manager forwarding): `{envInfo, faults}` → forwards to the corresponding Engine according to `envInfo` |
| `GET` | (existing) `getFaultInjectionInfo` | verification already embedded |

---

## 8. Frontend Display

1. **FaultSelector**: the fault dropdown and parameter form are changed to render dynamically by calling `GET /web/faults/catalog` (removing the hardcoded list).
2. **Execution record (FaultCard / History report)**: each fault displays a status badge:

```
✅ ENFORCED      green   effect verified as achieved (hover/expand shows summary and item-by-item evidence from checks[])
❌ NOT_ENFORCED  red     polling reached timeout, last check was valid and enforced=false (injected but had no effect; expand evidence to aid troubleshooting)
⚠️ ERROR        orange  injection command failed, or check returned {error:}/invalid response format (shows error)
—  UNKNOWN      gray    no self-check configured / network layer cannot reach the agent, cannot confirm (old faults or faults without implemented check; shows reason tooltip)
⏳ VERIFYING    blue    verification in progress (only in the running polling scenario)
```

3. The resilience triangle chart legend uses the same badge data source (verification field) without a second set of logic.
4. (Review supplement v2.1) Prompt for faults without self-check: the fault card can display a small "no self-check" marker, or prompt before injection "This fault has no self-check configured; its effect cannot be verified after injection. Continue?" — this is only a prompt and **does not block** injection or testing (inject as usual, test as usual, result is UNKNOWN).

---

## 9. Edge Cases and Determination Rules

| Scenario | Determination | Description |
|---|---|---|
| agent unreachable / injection returncode≠0 / /inject timeout / injection result empty | `ERROR` | injection pipeline failed, verification not entered |
| injection succeeded, no `<check>` template | `UNKNOWN` | does not pretend success, gray in frontend; with reason "no self-check command configured (no check template)" |
| check returns `enforced=true` | `ENFORCED` | polling terminated immediately; with summary + checks evidence |
| check returns a valid contract with `enforced=false`, polling reached timeout and the last result is still false | `NOT_ENFORCED` | last result prevails; with the last evidence |
| check returns `{error:}` / invalid JSON output / missing enforced field | `ERROR` | explicit error on the agent side, polling terminated immediately |
| check network-layer failure (cannot connect / connect/read timeout), last poll has no result | `UNKNOWN` | no response received from the agent side, cannot determine what happened; with the last exception |
| last result during polling is a network failure, with enforced=false seen earlier | `UNKNOWN` | last result prevails — what actually happened in between cannot be determined; with the last exception |
| fault duration too short, recovered before verification completes | per the last poll result (usually NOT_ENFORCED/UNKNOWN), summary notes "fault already recovered during verification" | determined by comparing verifiedAt with startTime |
| MySQL-family fault injectors have not implemented check (first phase) | `UNKNOWN` | first phase prioritizes openGauss-family faults with strong evidence |

---

## 10. Compatibility

1. Old XML/historical data: `verification` is null, the frontend displays "—".
2. Old fault yaml without configured check: injection behavior unchanged, verification UNKNOWN.
3. `fault.verification.enabled=false` disables verification entirely, fully restoring the old behavior.
4. When DBChaos has not been upgraded (no check subcommand): agent `/check` returns returncode≠0 → framework determines ERROR (does not interrupt), distinct from NOT_ENFORCED where injection succeeded but had no effect.
5. `FaultVerificationResult`/`CheckEvidence` deserialization follows the `@JsonIgnoreProperties(ignoreUnknown=true)` style, with field additions and removals forward-compatible.

---

## 11. Implementation Steps (executed after review approval)

### Phase A — DBChaos Repository (fault implementation layer)

| Step | Content |
|---|---|
| A1 | Add the `SelfCheckable` interface + `SelfCheckResult`/`CheckItem` models |
| A2 | CLI main program supports the `check` subcommand (parse the same parameters as injection → instantiate the injector → check() → stdout JSON, 30s fallback timeout) |
| A3 | 18 injectors implement `check()`. **The first phase prioritizes strong-evidence types** (see the list in §11.4); the rest are not implemented for now → their corresponding yaml **does not configure a check template** (verification UNKNOWN); if a template is configured but the injector throws "not implemented" (returncode≠0) → verification ERROR |
| A4 | (Optional) cases.tsv adds a self-check support column, making it convenient for the agent/manual confirmation of coverage |

### Phase B — Agent (bridge layer)

| Step | Content |
|---|---|
| B1 | Add `check.py` under `faults/` (or merge into command.py): `POST /check` endpoint, executing the command + parsing/passing through JSON + error wrapping |

### Phase C — resbench (unified invocation layer, main work)

| Step | Content |
|---|---|
| C1 | common module: `FaultVerificationStatus` / `FaultVerificationResult` / `CheckEvidence` |
| C2 | Add a CHECK field to `Fault` + XML `<check>` parsing (`loadFaultFromXML` / `FaultRunner` usage sites) |
| C3 | `config/fault-cases.yaml`: migrate 18 fault types (injection template + check template + params schema), and commit a classpath fallback copy |
| C4 | `FaultCatalogLoader`: load yaml → definition objects; template rendering engine (`{{var}}`/`{{var|default}}`/`{{var|default|mult}}`/context variables) |
| C5 | `StartByFormService` removes the hardcoded switch, instead looking up the catalog by nameKey to generate `FaultCommand + check command` |
| C6 | `FaultVerifier`: polling determination executor (§6.1 flow) + configuration items |
| C7 | `FaultRunner` wiring: call verify after successful injection → write back to `FaultInjectInfo.verification` |
| C8 | REST: `GET /web/faults/catalog` + `POST /web/fault/verify` |
| C9 | Unit tests: template rendering, determination state machine, XML parsing compatibility |

### Phase D — Frontend

| Step | Content |
|---|---|
| D1 | FaultSelector changed to dynamic rendering via the catalog interface |
| D2 | FaultCard / History add status badges + evidence expansion |
| D3 | Resilience triangle chart legend connected to the same data source |

### Phase E — Integration Verification

| Step | Content |
|---|---|
| E1 | In a real openGauss environment, for each of the 8 first-phase strong-evidence faults: inject → observe that the verification output matches the measured results |
| E2 | Negative-case validation: first run a fault not on the target table (no effect) → assert that NOT_ENFORCED is correctly reported |
| E3 | Compatibility regression: old XML (without check) runs through, historical reports render normally |

### 11.4 First-Phase Self-Check Priorities (DBChaos A3)

| Priority | Fault | Self-check evidence (implemented inside the injector) |
|---|---|---|
| P0 | txn.deadlock_storm | `pg_stat_database.deadlocks` increment > 1 |
| P0 | connection.limit.exceeded | attempt to establish a connection, expect `too many clients already` |
| P0 | txn.uncommitted_txn | target row `UPDATE` expects lock wait/timeout, or `pg_locks` sessions holding locks |
| P0 | transaction.massive.rollback | `xact_rollback` increment across two samples + rollback ratio |
| P0 | storage.mvcc_bloat | continuous growth of `n_dead_tup` |
| P1 | connection.surge / thread_saturation | `pg_stat_activity` connection count/active sessions reach the threshold |
| P1 | sql.plan_flip | multiple EXPLAIN observations show oscillation of scan method |
| P1 | txn.max_prepared | `pg_prepared_xacts` approaches the upper limit |
| P2 | 5 stack overflow types / read_amp_trap / duplicate_txn / buffer.memory_pressure | implement, or do not configure a check template for now (UNKNOWN); configured template but injector not implemented → ERROR (suggested checkpoints below) |
| P2 | MySQL family | UNKNOWN is allowed in the first phase (no check template configured); supplement after DBChaos has MySQL views |

Suggested checkpoints for stack overflow types (P2): the self-check issues an isomorphic deep SQL and expects `stack depth limit exceeded`, or collects errors from DB logs (requires `verification.dbLogPath` configuration, purely optional).

---

## 12. Risks and Open Questions

| # | Item | Impact | Mitigation |
|---|---|---|---|
| 1 | Whether the existing DBChaos CLI parameter parser supports the new `check` subcommand | A2 workload | Extend the existing DBChaos CLI framework with one subcommand; command parameters share the parser with injection |
| 2 | check must be instantaneous (within 30s per invocation) | a single slow check slows down that round of probing | yaml `check.timeoutMs` only bounds the whole-round window (clamp see §6.4); single-probe timeout is covered by the framework's 15s request timeout and only affects that probe |
| 3 | MySQL-family DBChaos injectors have a self-check gap in the first phase | MySQL fault verification is UNKNOWN | the first phase focuses on the openGauss family; MySQL is assigned P2 and not forced |
| 4 | Transaction-type fault self-check and the injector execute concurrently on the same table | the self-check may be blocked by locks | self-check SQL is read-only and sets `statement_timeout`; for lock-wait faults the self-check itself is the evidence (expects timeout) |
| 5 | yaml and the DBChaos registry are maintained in two places (inherent to the intermediate state) | fault definitions may become out of sync | the document explicitly stipulates: **yaml is the source of truth on the resbench side, and new faults must be synchronized on both sides**; eliminated in the future when upgrading to a fully dynamic registry |
| 6 | duration shorter than the verification window | recovered before verification completes | verification duration < duration is OK; when duration < warmup+timeout, output includes a note |
| 7 | DBChaos jar path (`/opt/DBChaos/DBChaos.jar`) inconsistent with the verification environment | the check command cannot find the jar | follow the existing `DBCHAOS_JAR` constant, and manage it uniformly in yaml templates via `{{DBCHAOS_JAR}}` |

---

## Appendix A: Complete fault-cases.yaml Example (deadlock_storm)

```yaml
faultinject.fault.txn.deadlock_storm:
  name: Deadlock Storm
  description: Cross-wait graph and deadlock detection overload
  autoRecovery: 1
  params:
    duration:    { type: int,    unit: seconds, default: 60, label: Duration }
    table:       { type: string, default: bmsql_stock, label: Target table }
    anchors:     { type: int,    default: 2,  label: Anchor transaction count }
    anchor-rows: { type: int,    default: 20, label: Anchor row count }
    waiters:     { type: int,    default: 16, label: Waiting transaction count }
    hot-rows:    { type: int,    default: 32, label: Hot row count }
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

Generated XML `<case>`:

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

## Appendix B: A Complete Timing Example

```
t=0    FaultRunner schedules Deadlock Storm (duration 60s; verification window = 60+30=90s)
t=0    FaultInjector → agent /inject → DBChaos injector starts (thread runs for 60s)
t≈0.2  inject returncode=0, FaultResult has no error
t=3.5  warmup (3s) ends → 1st POST agent/check → {"enforced":false,"summary":"deadlocks 0→3"} (below threshold)
t=10.5 2nd (7s interval) → {"enforced":true,"summary":"deadlocks 0→37","checks":[…]}
       → status=ENFORCED, FaultInjectInfo.verification written to disk
t=60   recovery; frontend shows ✅ Deadlock Storm ENFORCED (evidence expandable)
```

---

## Change Log

| Version | Changes |
|---|---|
| v1 (abandoned) | Framework-side JDBC SQL probe registry; adding a fault required changing the framework → did not satisfy the requirement |
| v2 (this version) | Fault self-check pushed down to DBChaos (SelfCheckable + CLI check); resbench configuration-driven (fault-cases.yaml) + unified verification executor + catalog/re-verification REST + frontend badges |
| v2.1 (review supplement) | (1) i18n fallback rules (approach 2: dictionary first, yaml text as fallback, §5.4.1); (2) faults without check are injected as usual, and the frontend only prompts without blocking (§8.4); (3) agent `/check` positioning clarification: an executor isomorphic to `/inject`, doing only execution + JSON validation + error wrapping, with no fault semantics (§3.3) |
| v2.2 (timing resolution) | (1) the verification whole-round window is coupled with fault duration (§6.4: `effectiveTimeoutMs = clamp(durationMs + graceMs, minTimeoutMs, capMs)`); (2) default polling interval 2s → **7s**; (3) yaml `check.timeoutMs/pollIntervalMs/warmupMs` per-fault overrides take precedence over global; (4) new `fault.verification.graceMs/capMs/minTimeoutMs` configuration; (5) explicit `fault.verification.timeoutMs` is an absolute override (no grace/cap added) |
