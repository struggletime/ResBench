"""Adapt ResBench schedule/config XML, optionally checking a companion worker.

Never imports Java, evaluates command strings, expands arbitrary XIncludes,
connects to a database, or executes injection/recovery commands.
"""
from __future__ import annotations

import copy
import csv
from dataclasses import dataclass, field, replace
import hashlib
import ipaddress
from pathlib import Path
import re
from typing import Any, Mapping, Optional
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

if __package__:
    from . import constraint_checker as shared
    from . import native_config_facts as config_facts
else:
    import constraint_checker as shared
    import native_config_facts as config_facts

JAVA_INT_MAX = 2147483647
XINCLUDE = "{http://www.w3.org/2001/XInclude}include"
# Exact names observed in native configs; catalog bindings take precedence.
NATIVE_DESCRIPTION_ALIASES = {
    "Network Reorder": "network_packet_reordering",
    "Network Duplicate": "network_packet_duplication",
    "Memory Load": "memory_full",
    "File Chmod": "file_permission_change",
    "Disk Fill": "disk_full",
    "Process Stop": "process_suspend",
    "Gaussdb Process Shutdown": "process_terminate",
    # English descriptions written by ResBench startByForm; same event, different
    # name spelling (only a "Database "/"Massive " prefix or " Storm" suffix differs).
    "Process Kill": "process_terminate",
    "Database Connection Storm": "connection_storm",
    "Database Connection Exhaustion": "connection_exhaustion",
    "Massive Transaction Rollback Storm": "massive_transaction_rollback",
    # chaosblade "file delete" produces a missing file, i.e. the file_loss event.
    "File Delete": "file_loss",
    "\u8fde\u63a5\u98ce\u66b4": "connection_storm",
    "\u8fde\u63a5\u6570\u8017\u5c3d": "connection_exhaustion",
    "\u6d77\u91cf\u4e8b\u52a1\u56de\u6eda": "massive_transaction_rollback",
}
# These are event-family bindings, not claims that 80% memory/90% disk is full.
# Packet corruption and thread saturation deliberately have no fallback aliases.


def read_xml(path: Path) -> ET.Element:
    payload = Path(path).read_bytes()
    # No DTD/entities are needed by native plan files. Do not resolve them.
    if re.search(br"<!\s*(?:DOCTYPE|ENTITY)\b", payload.replace(b"\x00", b""), re.I):
        raise ValueError(f"DTD/entity declarations are not supported: {path}")
    return ET.fromstring(payload)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def integer(text: str, field_name: str, minimum: int = 0) -> int:
    if not re.fullmatch(r"[0-9]+", text.strip()):
        raise ValueError(f"{field_name} must be integer seconds/ID, not {text!r}")
    value = int(text)
    if not minimum <= value <= JAVA_INT_MAX:
        raise ValueError(f"{field_name} must be in [{minimum}, {JAVA_INT_MAX}]")
    return value


def child(element: ET.Element, name: str) -> str:
    items = element.findall(name)
    if len(items) != 1 or not (items[0].text or "").strip():
        raise ValueError(f"Expected exactly one nonempty {name} element")
    return (items[0].text or "").strip()


@dataclass(frozen=True)
class NativeFault:
    fault_id: str
    description: str
    agents: tuple[str, ...]
    auto_recovery: bool
    parameters: Mapping[str, str] = field(default_factory=dict)
    parameter_status: str = "unsupported_command"
    parameter_errors: tuple[str, ...] = ()
    check_configured: bool = False

    def known_host(self) -> Optional[str]:
        # An explicit single IPv4 agent is the only automatic host inference.
        # master/slave/all, DNS aliases and multiple agents remain unresolved.
        if len(self.agents) != 1:
            return None
        host, sep, port = self.agents[0].partition(":")
        if not sep or not port.isdigit() or not 0 < int(port) < 65536:
            return None
        try:
            return str(ipaddress.IPv4Address(host))
        except ipaddress.AddressValueError:
            return None


@dataclass(frozen=True)
class NativeBundle:
    config_path: Path
    worker_path: Optional[Path]
    suite_name: Optional[str]
    duration_s: Optional[int]
    faults: Mapping[str, NativeFault]
    config_sha256: str
    worker_sha256: Optional[str]
    worker_embedded: bool = False
    workload: Mapping[str, Any] = field(default_factory=dict)

    @classmethod
    def read(cls, config_path: Path, worker_path: Optional[Path] = None,
             *, require_worker: bool = True) -> NativeBundle:
        config_path = Path(config_path).resolve(strict=True)
        root = read_xml(config_path)
        if root.tag != "parameters":
            raise ValueError("Native main config must have a parameters root")
        if require_worker and any(n.tag == XINCLUDE for top in root for n in top.iter() if n is not top):
            raise ValueError("Nested XIncludes require manual review")
        containers = root.findall("faultCases")
        if len(containers) != 1:
            raise ValueError("Native config needs one faultCases block")
        faults: dict[str, NativeFault] = {}
        for node in containers[0].findall("case"):
            key = str(integer(child(node, "id"), "faultCases/case/id", 1))
            if key in faults:
                raise ValueError(f"Duplicate fault definition ID: {key}")
            description = child(node, "description")
            injection = node.findall("injection")
            if len(injection) != 1:
                raise ValueError(f"Fault {key} needs one injection definition")
            child(injection[0], "cmd")  # Validate presence only. Never execute.
            declared = config_facts.injection_facts(injection[0])
            auto = child(node, "autoRecovery")
            if auto not in {"0", "1"}:
                raise ValueError(f"Fault {key}: autoRecovery must be 0 or 1")
            if auto == "0":
                recoveries = node.findall("recovery")
                if len(recoveries) != 1:
                    raise ValueError(f"Fault {key} needs a recovery definition")
                child(recoveries[0], "cmd")
            agents = tuple((n.text or "").strip() for n in node.findall("agents/agent"))
            if not agents or any(not agent for agent in agents):
                raise ValueError(f"Fault {key} has missing agent declarations")
            faults[key] = NativeFault(key, description, agents, auto == "1",
                                      declared.parameters, declared.status, declared.errors,
                                      node.find("check") is not None)
        if not faults:
            raise ValueError("Native config has no fault definitions")

        workload = config_facts.workload_facts(root)
        if not require_worker:
            if worker_path is not None:
                raise ValueError("worker_path requires explicit worker validation")
            # Two-XML checks do not open, expand, or infer any worker includes.
            return cls(config_path, None, None, None, faults,
                       sha256_file(config_path), None, workload=workload)

        embedded = root.findall("works")
        includes = root.findall(XINCLUDE)
        if embedded and includes:
            raise ValueError("Ambiguous worker selection: embedded works and XInclude")
        if embedded:
            if len(embedded) != 1 or worker_path is not None:
                raise ValueError("Embedded worker cannot be replaced by an unrelated worker file")
            worker_path = config_path
            works = embedded[0].findall("work")
        else:
            if len(includes) != 1 or includes[0].get("parse", "xml") != "xml":
                raise ValueError("Expected one local XML worker XInclude")
            include = includes[0]
            if (include.get("xpointer") or root.get("{http://www.w3.org/XML/1998/namespace}base")
                    or include.get("{http://www.w3.org/XML/1998/namespace}base")):
                raise ValueError("xpointer/xml:base includes require manual review")
            href = include.get("href", "")
            parsed = urlsplit(href)
            if not href or parsed.scheme or parsed.netloc or parsed.query or parsed.fragment:
                raise ValueError("Worker include must be a local relative XML path")
            discovered = (config_path.parent / unquote(parsed.path)).resolve(strict=True)
            if worker_path is not None and Path(worker_path).resolve(strict=True) != discovered:
                raise ValueError("--worker differs from the main config worker reference")
            worker_path = discovered
            worker = read_xml(worker_path)
            if worker.tag != "works":
                raise ValueError("Worker XML root must be works")
            if any(n.tag == XINCLUDE for n in worker.iter()):
                raise ValueError("Nested worker includes are not supported")
            works = worker.findall("work")
        if len(works) != 1:
            raise ValueError("Native adapter currently requires one work phase per config")
        duration = integer(child(works[0], "time"), "work/time", 1)
        suite = child(works[0], "testSuite")
        return cls(config_path, worker_path, suite, duration, faults,
                   sha256_file(config_path), sha256_file(worker_path), bool(embedded), workload)

    def metadata(self) -> dict[str, Any]:
        return {
            "profile": "native", "config_path": str(self.config_path),
            "worker_path": str(self.worker_path) if self.worker_path is not None else None,
            "suite_name": self.suite_name,
            "work_duration_s": self.duration_s, "config_sha256": self.config_sha256,
            "worker_sha256": self.worker_sha256, "commands_executed": False,
            "runtime_effects_verified": False,
            "worker_checked": self.worker_path is not None,
            "work_window_checked": self.duration_s is not None,
            "workload": dict(self.workload),
            "unchecked": (["worker suite reference", "work phase end"]
                          if self.worker_path is None else []) + ["runtime injection/recovery effects"],
        }


def load_event_map(path: Optional[Path], bundle: NativeBundle) -> dict[str, str]:
    """Optional reviewed CSV: config_sha256,fault_id,event_id (no commands)."""
    if path is None:
        return {}
    result: dict[str, str] = {}
    with Path(path).open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if not {"config_sha256", "fault_id", "event_id"}.issubset(reader.fieldnames or []):
            raise ValueError("Event map needs config_sha256,fault_id,event_id columns")
        for row in reader:
            if row["config_sha256"].strip().lower() != bundle.config_sha256:
                raise ValueError("Event map is not bound to this config hash")
            key = str(integer(row["fault_id"], "event map fault_id", 1))
            event_id = row["event_id"].strip()
            if key in result or key not in bundle.faults or not event_id:
                raise ValueError(f"Invalid/duplicate event binding for fault {key}")
            result[key] = event_id
    return result


def resolve_type(fault: NativeFault, catalog: shared.GraphEventCatalog,
                 bindings: Mapping[str, str]) -> str:
    event, matches = catalog.resolve(fault.description)
    explicit = bindings.get(fault.fault_id)
    if explicit is not None:
        if explicit not in catalog.by_id or (matches and all(m.event_id != explicit for m in matches)):
            raise ValueError(f"Event binding conflicts with catalog for fault {fault.fault_id}")
        return explicit
    if not matches:
        alias = NATIVE_DESCRIPTION_ALIASES.get(fault.description)
        if alias in catalog.by_id:
            return alias
    if event is None:
        raise ValueError(f"Fault {fault.fault_id} description is unknown/ambiguous: {fault.description!r}")
    return event.event_id


@dataclass
class AdaptedPlan:
    cases: list[shared.PlanCase]
    diagnostics: list[shared.Diagnostic]
    context: dict[str, Any]
    metadata: dict[str, Any]


def adapt_plan(plan_path: Path, bundle: NativeBundle, catalog: shared.GraphEventCatalog,
               *, bindings: Optional[Mapping[str, str]] = None,
               context: Optional[Mapping[str, Any]] = None) -> AdaptedPlan:
    root = read_xml(Path(plan_path))
    facts = copy.deepcopy(dict(context or {}))
    for key in ("cases", "pairs", "global_facts"):
        if not isinstance(facts.get(key, {}), Mapping):
            raise ValueError(f"context.{key} must be an object")
    if any(not isinstance(v, bool) for v in facts.get("global_facts", {}).values()):
        raise ValueError("Global facts must be actual booleans")
    for values in facts.get("pairs", {}).values():
        if not isinstance(values, Mapping) or any(not isinstance(v, bool) for v in values.values()):
            raise ValueError("Pair facts must be objects with actual boolean values")
    for key, values in facts.get("cases", {}).items():
        if not isinstance(key, str) or not isinstance(values, Mapping):
            raise ValueError("Case context must map case references to fact objects")
        if any(value is not None and not isinstance(value, (str, bool, int, float)) for value in values.values()):
            raise ValueError("Case context values must be scalar facts")
    if any(key not in {"global_facts", "pairs", "cases"} and isinstance(value, bool)
           for key, value in facts.items()):
        raise ValueError("Boolean context facts must be scoped to global_facts or pairs")
    diagnostics: list[shared.Diagnostic] = []
    cases: list[shared.PlanCase] = []
    def error(code: str, message: str, refs: tuple[str, ...] = ()) -> None:
        diagnostics.append(shared.Diagnostic("ERROR", "NATIVE_" + code, message, refs))

    suites = root.findall("testSuite")
    if (root.tag != "testSuites" or root.attrib or len(suites) != 1
            or any(node.tag != "testSuite" for node in root)):
        error("STRUCTURE", "Expected testSuites containing exactly one native testSuite")
        return AdaptedPlan([], diagnostics, facts, bundle.metadata())
    suite = suites[0]
    suite_name = (suite.get("name") or "").strip()
    if set(suite.attrib) != {"name"} or not suite_name or suite_name != suite.get("name"):
        error("STRUCTURE", "Native testSuite requires a nonempty, unpadded name")
    if bundle.suite_name is not None and suite.attrib != {"name": bundle.suite_name}:
        error("SUITE_BINDING", "Plan suite does not match the worker suite")
    if any(node.tag != "case" for node in suite):
        error("STRUCTURE", "Unexpected child in native testSuite")
    global_invalid = bool(diagnostics)
    seen: set[str] = set()
    duplicates: set[str] = set()
    known_refs: set[str] = set()
    provenance: dict[str, str] = {}
    fault_declarations: dict[str, Any] = {}
    for position, node in enumerate(suite.findall("case"), 1):
        before = len(diagnostics)
        raw_id = node.get("id", "")
        key = raw_id or f"<missing-{position}>"
        try:
            key = str(integer(raw_id, "case/@id", 1))
        except ValueError as exc:
            error("ID", str(exc))
        ref = f"{suite_name}::{key}"
        known_refs.add(ref)
        if key in seen:
            duplicates.add(key)
            error("DUPLICATE_ID", "Repeated fault IDs alias Java Case identities; use distinct existing definitions", (ref,))
        seen.add(key)
        allowed = {"description", "planning", "during", "groupKey"}
        if set(node.attrib) != {"id"} or any(n.tag not in allowed or n.attrib or len(n) for n in node):
            error("EXTENSION_FIELDS", "Native case accepts only id and original child fields", (ref,))
        description = ""
        start = duration = 0
        try:
            description = child(node, "description")
            start = integer(child(node, "planning"), "planning")
            duration = integer(child(node, "during"), "during", 1)
        except ValueError as exc:
            error("FIELDS", str(exc), (ref,))
        if bundle.duration_s is not None and start + duration > bundle.duration_s:
            error("WORK_WINDOW", "Event extends beyond the configured work phase", (ref,))
        groups = node.findall("groupKey")
        if len(groups) > 1:
            error("FIELDS", "Duplicate groupKey", (ref,))
        fault = bundle.faults.get(key)
        event_id: Optional[str] = None
        attributes: dict[str, str] = {}
        parameters: dict[str, str] = {}
        if fault is None:
            error("FAULT_BINDING", f"No configured fault definition for ID {key}", (ref,))
        else:
            if description != fault.description:
                error("DESCRIPTION_BINDING", "Schedule description differs from the bound fault definition", (ref,))
            try:
                event_id = resolve_type(fault, catalog, bindings or {})
            except ValueError as exc:
                error("EVENT_BINDING", str(exc), (ref,))
            host = fault.known_host()
            if host is not None:
                attributes["host"] = host
                provenance[ref + "/host"] = "single explicit IPv4 agent in native config"
            parameters = dict(fault.parameters)
            for name in parameters:
                provenance[ref + "/parameters/" + name] = f"faultCases/case[id={key}]/injection/arg"
            fault_declarations[ref] = {
                "parameter_status": fault.parameter_status,
                "auto_recovery_configured": fault.auto_recovery,
                "check_configured": fault.check_configured,
                "check_executed": False,
                "declaration_only": True,
                "timing_consistency": "not_checked",
            }
            if fault.parameter_errors:
                error("INJECTION_PARAMETERS", "; ".join(fault.parameter_errors), (ref,))
            elif fault.parameter_status == "recognized":
                timeout = int(parameters.get("injector_timeout_s", "0"))
                fault_declarations[ref]["timing_consistency"] = (
                    "consistent" if timeout == duration and timeout > 0 else
                    "conflict" if timeout > 0 else "unknown")
                if timeout > 0 and timeout != duration:
                    error("DURATION_MISMATCH",
                          f"during={duration}s differs from configured injector timeout={timeout}s", (ref,))
                elif (parameters.get("operation") == "process_kill"
                      and parameters.get("signal") in {"9", "15"} and timeout == 0):
                    fault_declarations[ref].update(
                        timing_consistency="declared_plan_interval_only",
                        recovery_effect_verified=False,
                        declared_duration_is_recovery_time=False,
                    )
                elif fault.auto_recovery and timeout == 0:
                    diagnostics.append(shared.Diagnostic(
                        "PENDING", "NATIVE_DURATION_UNRESOLVED",
                        "Automatic recovery has no positive timeout to corroborate during", (ref,)))
            elif fault.auto_recovery:
                diagnostics.append(shared.Diagnostic(
                    "PENDING", "NATIVE_DURATION_UNRESOLVED",
                    "Automatic recovery timing cannot be checked for this unsupported command", (ref,)))
            supplied = facts.get("cases", {}).get(ref, {})
            if not isinstance(supplied, Mapping):
                error("CONTEXT", "Case context must be an object", (ref,))
            elif host and "host" in supplied and supplied["host"] != host:
                error("CONTEXT_CONFLICT", "Context host contradicts the configured agent", (ref,))
            for name, value in parameters.items():
                if name in supplied and str(supplied[name]) != value:
                    error("CONTEXT_CONFLICT", "Context contradicts configured parameter " + name, (ref,))
        valid = not global_invalid and not any(d.severity == "ERROR" for d in diagnostics[before:])
        end_ms = (start + duration) * 1000
        cases.append(shared.PlanCase(
            key, suite_name, description, event_id, start, duration,
            start * 1000, end_ms, end_ms if valid else None, end_ms if valid else None,
            valid, (groups[0].text or "").strip() if groups else None, attributes, parameters, (),
        ))
    if duplicates:
        cases = [replace(c, valid=False) if c.case_id in duplicates else c for c in cases]
    if not cases:
        error("EMPTY_PLAN", "Native schedule has no cases")
    if set(facts.get("cases", {})) - known_refs:
        error("CONTEXT_BINDING", "Context contains case keys absent from this plan")
    for key in facts.get("pairs", {}):
        if not isinstance(key, str):
            raise ValueError("Pair context keys must be case-reference strings")
        pair = re.split(r"->|\|", key)
        if len(pair) != 2 or any(ref not in known_refs for ref in pair):
            error("CONTEXT_BINDING", "Pair context refers to a different plan or unknown case")
    has_host_claim = "same_host" in facts.get("global_facts", {}) or any(
        "same_host" in values for values in facts.get("pairs", {}).values()
    )
    for left in cases if has_host_claim else ():
        for right in cases:
            if left.case_id == right.case_id:
                continue
            a, b = left.attributes.get("host"), right.attributes.get("host")
            if a is None or b is None:
                continue
            supplied = shared.context_pair_fact(facts, shared.case_ref(left), shared.case_ref(right), "same_host")
            if supplied is None:
                supplied = shared.context_global_fact(facts, "same_host")
            if supplied is not None and supplied != (a == b):
                error("CONTEXT_CONFLICT", "same_host assertion contradicts configured agent identities",
                      (shared.case_ref(left), shared.case_ref(right)))
    if (sha256_file(bundle.config_path) != bundle.config_sha256
            or (bundle.worker_path is not None and sha256_file(bundle.worker_path) != bundle.worker_sha256)):
        error("INPUT_CHANGED", "Configuration changed during input adaptation")
    metadata = {**bundle.metadata(), "suite_name": suite_name, "plan_sha256": sha256_file(plan_path),
                "fact_provenance": provenance, "scope_context_supplied": bool(context),
                "fault_declarations": fault_declarations,
                "binding_complete": not any(d.severity == "ERROR" for d in diagnostics)}
    return AdaptedPlan(cases, diagnostics, facts, metadata)
