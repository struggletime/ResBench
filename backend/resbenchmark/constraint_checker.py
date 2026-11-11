"""Check declared ResBench XML events against Neo4j event nodes and relations.

This module only parses and validates configuration. It never invokes a fault
injector, recovery command, or database workload. A live Neo4j connection is
opened only from main(), after the caller explicitly runs the CLI.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping, Optional


GRAPH_VERSION = "resbench-kg-v1"
TIME_MODEL = "unified_event_end"
ALLOWED_RELATIONS = {
    "depends_on",
    "mutually_exclusive",
    "enhances",
    "masks",
}
FORMAL_RELATION_STATUS = "formal"
CANDIDATE_RELATION_STATUS = "candidate"
REPORT_STATUS_PASS_CHECKED_CONSTRAINTS = "PASS_CHECKED_CONSTRAINTS"
REPORT_STATUS_VIOLATION = "VIOLATION"
REPORT_STATUS_INDETERMINATE = "INDETERMINATE"
REPORT_STATUS_NO_RULES = "NO_APPLICABLE_RULES"
REPORT_STATUS_INVALID = "INVALID_INPUT"


@dataclass(frozen=True)
class GraphEvent:
    event_id: str
    name: str = ""
    xml_descriptions: tuple[str, ...] = ()
    aliases: tuple[str, ...] = ()


@dataclass(frozen=True)
class PlanCase:
    case_id: str
    suite_name: str
    description: str
    event_id: Optional[str]
    planning_s: float
    during_s: float
    start_ms: float
    end_ms: float
    recovery_end_ms: Optional[float]
    ownership_end_ms: Optional[float]
    valid: bool
    group_key: Optional[str]
    attributes: Mapping[str, str]
    parameters: Mapping[str, str]
    declared_effects: tuple[str, ...]


@dataclass(frozen=True)
class GraphRelation:
    relation_id: str
    source: str
    target: str
    relation: str
    scope: str
    properties: Mapping[str, Any]


@dataclass(frozen=True)
class Diagnostic:
    severity: str
    code: str
    message: str
    case_ids: tuple[str, ...] = ()
    relation_id: Optional[str] = None
    details: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ActiveInteraction:
    relation_id: str
    relation: str
    source_case: str
    target_case: str
    source_event: str
    target_event: str
    scope: str
    scope_status: str
    rule_status: str


@dataclass(frozen=True)
class ConditionResult:
    status: str
    reason: str = ""
    details: Mapping[str, Any] = field(default_factory=dict)


@dataclass
class CheckReport:
    plan_path: str
    graph_version: str
    relation_mode: str
    cases: list[PlanCase] = field(default_factory=list)
    diagnostics: list[Diagnostic] = field(default_factory=list)
    active_interactions: list[ActiveInteraction] = field(default_factory=list)
    loaded_relation_count: int = 0
    selected_relation_count: int = 0
    formal_relation_count: int = 0
    candidate_relation_count: int = 0
    checked_relation_count: int = 0
    unknown_count: int = 0
    graph_event_ids: list[str] = field(default_factory=list)
    uncovered_event_ids: list[str] = field(default_factory=list)
    status: str = REPORT_STATUS_NO_RULES

    @property
    def errors(self) -> list[Diagnostic]:
        return [item for item in self.diagnostics if item.severity == "ERROR"]

    @property
    def warnings(self) -> list[Diagnostic]:
        return [item for item in self.diagnostics if item.severity == "WARN"]

    @property
    def pending(self) -> list[Diagnostic]:
        return [item for item in self.diagnostics if item.severity == "PENDING"]

    def as_dict(self) -> dict[str, Any]:
        return {
            "plan_path": self.plan_path,
            "graph_version": self.graph_version,
            "relation_mode": self.relation_mode,
            "time_model": TIME_MODEL,
            "check_scope": "declared_xml_plan",
            "execution_effects_verified": False,
            "status": self.status,
            "cases": [asdict(item) for item in self.cases],
            "diagnostics": [asdict(item) for item in self.diagnostics],
            "active_interactions": [asdict(item) for item in self.active_interactions],
            "summary": {
                "case_count": len(self.cases),
                "error_count": len(self.errors),
                "warning_count": len(self.warnings),
                "pending_count": len(self.pending),
                "active_interaction_count": len(self.active_interactions),
                "loaded_relation_count": self.loaded_relation_count,
                "selected_relation_count": self.selected_relation_count,
                "formal_relation_count": self.formal_relation_count,
                "candidate_relation_count": self.candidate_relation_count,
                "checked_relation_count": self.checked_relation_count,
                "unknown_count": self.unknown_count,
                "graph_event_count": len(self.graph_event_ids),
                "uncovered_event_ids": self.uncovered_event_ids,
            },
        }


class GraphEventCatalog:
    def __init__(self, events: Iterable[GraphEvent]) -> None:
        self.by_id: dict[str, GraphEvent] = {}
        self._by_alias: dict[str, list[GraphEvent]] = {}
        for event in events:
            if not isinstance(event.event_id, str) or not event.event_id.strip():
                raise ValueError("图谱事件节点缺少有效 id")
            if event.event_id in self.by_id:
                raise ValueError(f"图谱版本内存在重复事件 id: {event.event_id}")
            self.by_id[event.event_id] = event
            for alias in (event.event_id, event.name, *event.xml_descriptions, *event.aliases):
                if not isinstance(alias, str):
                    raise ValueError(f"图谱事件 {event.event_id} 的名称或别名不是字符串")
                normalized = normalize_text(alias)
                if not normalized:
                    continue
                bucket = self._by_alias.setdefault(normalized, [])
                if all(item.event_id != event.event_id for item in bucket):
                    bucket.append(event)

    def resolve(self, description: str) -> tuple[Optional[GraphEvent], list[GraphEvent]]:
        matches = self._by_alias.get(normalize_text(description), [])
        return (matches[0] if len(matches) == 1 else None, matches)


class XmlPlanReader:
    def __init__(self, time_scale_ms: float = 1000.0) -> None:
        self.time_scale_ms = time_scale_ms

    def read(self, path: Path, catalog: GraphEventCatalog) -> tuple[list[PlanCase], list[Diagnostic]]:
        root = ET.parse(path).getroot()
        cases: list[PlanCase] = []
        diagnostics: list[Diagnostic] = []
        seen_ids: set[tuple[str, str]] = set()

        suites = [root] if root.tag == "testSuite" else root.findall(".//testSuite")
        for suite_index, suite in enumerate(suites, start=1):
            suite_name = (suite.get("name") or "").strip() or f"suite-{suite_index}"
            for case_element in suite.findall("./case"):
                case_id = case_element.get("id", "").strip()
                description = child_text(case_element, "description")
                explicit_ids = deduplicate(
                    value.strip()
                    for value in (case_element.get("event_id", ""), child_text(case_element, "event_id"))
                    if value.strip()
                )
                planning_text = child_text(case_element, "planning")
                during_text = child_text(case_element, "during")
                group_key = optional_child_text(case_element, "groupKey")
                attributes = read_case_attributes(case_element)
                parameters = read_parameters(case_element)
                declared_effects = read_declared_effects(case_element)
                case_ref_value = f"{suite_name}::{case_id}" if case_id else ""

                identity_valid = True
                if not case_id:
                    identity_valid = False
                    diagnostics.append(Diagnostic("ERROR", "MISSING_CASE_ID", "case 缺少 id 属性"))
                    case_id = f"<missing-{len(cases) + 1}>"
                    case_ref_value = f"{suite_name}::{case_id}"
                elif (suite_name, case_id) in seen_ids:
                    identity_valid = False
                    diagnostics.append(
                        Diagnostic(
                            "ERROR",
                            "DUPLICATE_CASE_ID",
                            f"同一 testSuite 内 case id 重复: {case_id}",
                            (case_ref_value,),
                        )
                    )
                seen_ids.add((suite_name, case_id))

                event, matches = catalog.resolve(description)
                if len(explicit_ids) > 1:
                    event = None
                    diagnostics.append(
                        Diagnostic(
                            "ERROR", "CONFLICTING_EVENT_IDS", "case 属性与子元素中的 event_id 不一致",
                            (case_ref_value,), details={"event_ids": explicit_ids},
                        )
                    )
                elif explicit_ids:
                    event = catalog.by_id.get(explicit_ids[0])
                    if event is None:
                        diagnostics.append(
                            Diagnostic(
                                "ERROR", "EVENT_NODE_MISSING",
                                f"指定 event_id 在当前图谱版本中不存在: {explicit_ids[0]}",
                                (case_ref_value,),
                            )
                        )
                    elif matches and all(item.event_id != event.event_id for item in matches):
                        diagnostics.append(
                            Diagnostic(
                                "ERROR", "EVENT_ID_DESCRIPTION_MISMATCH",
                                "event_id 与 description 指向不同的图谱事件",
                                (case_ref_value,),
                                details={"event_id": event.event_id, "description_matches": [item.event_id for item in matches]},
                            )
                        )
                        event = None
                elif event is None and not matches:
                    diagnostics.append(
                        Diagnostic(
                            "ERROR",
                            "UNKNOWN_EVENT_DESCRIPTION",
                            f"图谱中找不到事件描述 {description!r}；请提供 event_id 或维护节点名称/别名",
                            (case_ref_value,),
                        )
                    )
                elif event is None:
                    diagnostics.append(
                        Diagnostic(
                            "ERROR",
                            "AMBIGUOUS_EVENT_DESCRIPTION",
                            f"事件描述匹配多个图谱节点: {description!r}；请使用明确的 event_id",
                            (case_ref_value,),
                            details={"matches": [item.event_id for item in matches]},
                        )
                    )

                planning_s = parse_nonnegative_number(
                    planning_text, "planning", case_ref_value, diagnostics
                )
                during_s = parse_positive_number(during_text, "during", case_ref_value, diagnostics)
                unified_end_valid = validate_unified_end_fields(
                    case_element, case_ref_value, diagnostics
                )
                valid = (
                    identity_valid
                    and event is not None
                    and planning_s is not None
                    and during_s is not None
                    and unified_end_valid
                )
                planning_value = planning_s if planning_s is not None else 0.0
                during_value = during_s if during_s is not None else 0.0
                active_start_ms = planning_value * self.time_scale_ms
                active_end_ms = (planning_value + during_value) * self.time_scale_ms
                if valid and (
                    not math.isfinite(active_start_ms)
                    or not math.isfinite(active_end_ms)
                    or active_end_ms <= active_start_ms
                ):
                    valid = False
                    diagnostics.append(Diagnostic(
                        "ERROR", "INVALID_TIME_INTERVAL",
                        "换算后的时间区间必须有限，且结束时间必须晚于开始时间",
                        (case_ref_value,),
                    ))
                    active_start_ms = active_start_ms if math.isfinite(active_start_ms) else 0.0
                    active_end_ms = active_end_ms if math.isfinite(active_end_ms) else 0.0
                # Retain report fields as aliases, not independent time inputs.
                recovery_end_ms = active_end_ms if valid else None
                ownership_end_ms = recovery_end_ms

                if event and declared_effects and set(declared_effects) != {event.event_id}:
                    diagnostics.append(
                        Diagnostic(
                            "WARN",
                            "DECLARED_EFFECTS_UNCHECKED",
                            f"case {case_ref_value} 声明了附加或替换效果；当前只检查主事件，请为各效果提供独立 case 及时间信息",
                            (case_ref_value,),
                            details={"primary_event": event.event_id, "declared_effects": list(declared_effects)},
                        )
                    )

                cases.append(
                    PlanCase(
                        case_id=case_id,
                        suite_name=suite_name,
                        description=description,
                        event_id=event.event_id if event else None,
                        planning_s=planning_value,
                        during_s=during_value,
                        start_ms=active_start_ms,
                        end_ms=active_end_ms,
                        recovery_end_ms=recovery_end_ms,
                        ownership_end_ms=ownership_end_ms,
                        valid=valid,
                        group_key=group_key,
                        attributes=attributes,
                        parameters=parameters,
                        declared_effects=declared_effects,
                    )
                )

        if not cases:
            diagnostics.append(Diagnostic("ERROR", "EMPTY_PLAN", "XML 中没有找到 testSuite/case"))
        return cases, diagnostics


class Neo4jRelationStore:
    def __init__(
        self,
        uri: str,
        user: str,
        password: str,
        graph_version: str,
        relation_mode: str,
        database: str = "neo4j",
    ) -> None:
        self.uri = uri
        self.user = user
        self.password = password
        self.graph_version = graph_version
        self.relation_mode = relation_mode
        self.database = database
        self._driver: Any = None
        self._read_access: Any = None

    def __enter__(self) -> "Neo4jRelationStore":
        try:
            from neo4j import GraphDatabase, READ_ACCESS
        except ImportError as error:
            raise RuntimeError("缺少 neo4j Python driver，请先安装 neo4j 包") from error
        self._driver = GraphDatabase.driver(self.uri, auth=(self.user, self.password))
        self._read_access = READ_ACCESS
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        if self._driver is not None:
            self._driver.close()

    def load_relations(self) -> list[GraphRelation]:
        query = """
        MATCH (source:AdverseEvent {graph_version: $graph_version})
              -[r:RELATES_TO]->
              (target:AdverseEvent {graph_version: $graph_version})
        WHERE r.graph_version = $graph_version
          AND r.relation IN $allowed_relations
          AND coalesce(r.validation_status, 'proposed') <> 'rejected'
          AND (
            $relation_mode = 'preview'
            OR (
              r.validation_status = 'validated'
              AND r.compile_enabled = true
              AND r.example_only = false
              AND r.scope_implemented = true
            )
          )
        RETURN r.id AS relation_id,
               source.id AS source,
               target.id AS target,
               r.relation AS relation,
               coalesce(r.scope, '') AS scope,
               properties(r) AS properties
        ORDER BY relation_id
        """
        with self._driver.session(
            database=self.database, default_access_mode=self._read_access
        ) as session:
            result = session.run(
                query,
                graph_version=self.graph_version,
                allowed_relations=sorted(ALLOWED_RELATIONS),
                relation_mode=self.relation_mode,
            )
            return [
                GraphRelation(
                    relation_id=str(record["relation_id"]),
                    source=str(record["source"]),
                    target=str(record["target"]),
                    relation=str(record["relation"]),
                    scope=str(record["scope"]),
                    properties=dict(record["properties"] or {}),
                )
                for record in result
            ]

    def load_events(self) -> list[GraphEvent]:
        query = """
        MATCH (event:AdverseEvent {graph_version: $graph_version})
        RETURN event.id AS event_id, properties(event) AS properties
        ORDER BY event_id
        """
        with self._driver.session(
            database=self.database, default_access_mode=self._read_access
        ) as session:
            result = session.run(query, graph_version=self.graph_version)
            events: list[GraphEvent] = []
            for record in result:
                properties = record["properties"]
                for key in ("xml_descriptions", "aliases"):
                    if not isinstance(properties.get(key, []), (list, tuple)):
                        raise ValueError(f"图谱节点 {record['event_id']} 的 {key} 必须是字符串列表")
                events.append(
                    GraphEvent(
                        event_id=record["event_id"],
                        name=properties.get("name", ""),
                        xml_descriptions=tuple(properties.get("xml_descriptions", [])),
                        aliases=tuple(properties.get("aliases", [])),
                    )
                )
            return events


class ConstraintChecker:
    def __init__(self, graph_version: str, relation_mode: str, context: Mapping[str, Any]) -> None:
        if relation_mode not in {"strict", "preview"}:
            raise ValueError("relation_mode 必须为 strict 或 preview")
        self.graph_version = graph_version
        self.relation_mode = relation_mode
        self.context = context

    def check(
        self,
        plan_path: Path,
        cases: list[PlanCase],
        plan_diagnostics: list[Diagnostic],
        relations: list[GraphRelation],
        graph_event_ids: Optional[set[str]] = None,
    ) -> CheckReport:
        # Enforce the same selection boundary for callers that bypass Neo4j.
        selected_relations = sorted(
            (
                relation for relation in relations
                if str(relation.properties.get("validation_status", "proposed")).casefold() != "rejected"
                and (
                    self.relation_mode == "preview"
                    or relation_status(relation) == FORMAL_RELATION_STATUS
                )
            ),
            key=lambda relation: relation.relation_id,
        )
        report = CheckReport(
            plan_path=str(plan_path),
            graph_version=self.graph_version,
            relation_mode=self.relation_mode,
            cases=cases,
            diagnostics=list(plan_diagnostics),
            loaded_relation_count=len(relations),
            selected_relation_count=len(selected_relations),
            formal_relation_count=sum(
                relation_status(item) == FORMAL_RELATION_STATUS for item in relations
            ),
            candidate_relation_count=sum(
                relation_status(item) == CANDIDATE_RELATION_STATUS for item in relations
            ),
            graph_event_ids=sorted(graph_event_ids or set()),
        )
        if self.relation_mode == "preview":
            report.diagnostics.append(
                Diagnostic(
                    "WARN",
                    "PREVIEW_RELATION_MODE",
                    "当前读取候选关系；候选关系只产生 PENDING 结果，不代表正式可编排约束",
                )
            )
        self._check_time_intervals(report)
        self._check_relations(report, selected_relations, graph_event_ids)
        self._finalize_report(report, selected_relations)
        return report

    def _check_time_intervals(self, report: CheckReport) -> None:
        for case in report.cases:
            if not case.valid:
                continue
            if case.end_ms <= case.start_ms:
                report.diagnostics.append(
                    Diagnostic(
                        "ERROR",
                        "INVALID_TIME_INTERVAL",
                        f"{case_ref(case)} 的活动时间区间无效",
                        (case_ref(case),),
                        details={"start_ms": case.start_ms, "end_ms": case.end_ms},
                    )
                )

    def _check_relations(
        self,
        report: CheckReport,
        relations: list[GraphRelation],
        graph_event_ids: Optional[set[str]],
    ) -> None:
        cases_by_suite_event: dict[tuple[str, str], list[PlanCase]] = {}
        plan_event_ids: set[str] = set()
        for case in report.cases:
            if case.valid and case.event_id:
                cases_by_suite_event.setdefault((case.suite_name, case.event_id), []).append(case)
                plan_event_ids.add(case.event_id)

        if graph_event_ids is not None:
            for event_id in sorted(plan_event_ids - graph_event_ids):
                report.diagnostics.append(
                    Diagnostic(
                        "ERROR",
                        "EVENT_NODE_MISSING",
                        f"事件 {event_id} 在指定 graph_version 中没有对应的 AdverseEvent 节点",
                        details={"event_id": event_id, "graph_version": self.graph_version},
                    )
                )

        checked_relation_ids: set[str] = set()
        suite_names = sorted({case.suite_name for case in report.cases})
        for relation in relations:
            if relation.relation not in ALLOWED_RELATIONS:
                report.diagnostics.append(
                    Diagnostic(
                        "ERROR",
                        "UNKNOWN_RELATION_TYPE",
                        f"图谱关系类型不支持: {relation.relation}",
                        relation_id=relation.relation_id,
                    )
                )
                continue

            relation_has_source = any(
                (suite_name, relation.source) in cases_by_suite_event for suite_name in suite_names
            )
            if not relation_has_source:
                continue
            for suite_name in suite_names:
                source_cases = cases_by_suite_event.get((suite_name, relation.source), [])
                if not source_cases:
                    continue
                target_cases = cases_by_suite_event.get((suite_name, relation.target), [])
                # A missing dependency is checkable; other rules need an actual pair.
                if not target_cases and relation.relation != "depends_on":
                    continue
                checked_relation_ids.add(relation.relation_id)
                if relation.relation == "depends_on":
                    self._check_dependency(report, relation, source_cases, target_cases)
                elif relation.relation == "mutually_exclusive":
                    self._check_exclusion(report, relation, source_cases, target_cases)
                else:
                    self._record_interaction(report, relation, source_cases, target_cases)
        report.checked_relation_count = len(checked_relation_ids)

        checked_relations = [item for item in relations if item.relation_id in checked_relation_ids]
        covered_event_ids = {item.source for item in checked_relations} | {item.target for item in checked_relations}
        report.uncovered_event_ids = sorted(plan_event_ids - covered_event_ids)

    def _check_dependency(
        self,
        report: CheckReport,
        relation: GraphRelation,
        source_cases: list[PlanCase],
        target_cases: list[PlanCase],
    ) -> None:
        required_state = str(relation.properties.get("required_state", "active"))
        severity = severity_for_relation(relation)
        for source in source_cases:
            if not target_cases:
                report.diagnostics.append(
                    Diagnostic(
                        severity,
                        "MISSING_DEPENDENCY",
                        f"{case_ref(source)} 需要事件 {relation.target}，但测试计划中没有该事件",
                        (case_ref(source),),
                        relation.relation_id,
                        {"required_state": required_state},
                    )
                )
                continue

            unknown_pairs: list[tuple[str, str]] = []
            satisfied = False
            for target in target_cases:
                scope_result = self._scope_status(relation, source, target)
                if scope_result.status == "false":
                    continue
                if scope_result.status == "unknown":
                    unknown_pairs.append((case_ref(source), case_ref(target)))
                    continue
                timing_result = self._dependency_status(source, target, required_state)
                if timing_result.status == "true":
                    satisfied = True
                    break
                if timing_result.status == "unknown":
                    unknown_pairs.append((case_ref(source), case_ref(target)))

            if satisfied:
                continue
            if unknown_pairs:
                report.diagnostics.append(
                    Diagnostic(
                        "WARN" if severity == "ERROR" else "PENDING",
                        "DEPENDENCY_UNRESOLVED",
                        f"{case_ref(source)} 的依赖关系存在无法由计划确认的 scope 或状态条件",
                        (case_ref(source),),
                        relation.relation_id,
                        {"required_state": required_state, "unknown_pairs": unknown_pairs},
                    )
                )
            else:
                report.diagnostics.append(
                    Diagnostic(
                        severity,
                        "UNSATISFIED_DEPENDENCY",
                        f"{case_ref(source)} 的依赖关系 {relation.source} depends_on {relation.target} 未满足",
                        (case_ref(source),),
                        relation.relation_id,
                        {"required_state": required_state},
                    )
                )

    @staticmethod
    def _dependency_status(
        source: PlanCase, target: PlanCase, required_state: str
    ) -> ConditionResult:
        # These are distinct prerequisite states, not alternative event windows.
        if required_state == "active":
            satisfied = target.start_ms <= source.start_ms and target.end_ms >= source.end_ms
            return ConditionResult("true" if satisfied else "false", "active_interval")
        if required_state == "active_at_start":
            satisfied = target.start_ms <= source.start_ms < target.end_ms
            return ConditionResult("true" if satisfied else "false", "active_at_start")
        if required_state == "cleared":
            satisfied = target.end_ms <= source.start_ms
            return ConditionResult("true" if satisfied else "false", "cleared_at_end")
        return ConditionResult("unknown", "unsupported_required_state", {"required_state": required_state})

    def _check_exclusion(
        self,
        report: CheckReport,
        relation: GraphRelation,
        source_cases: list[PlanCase],
        target_cases: list[PlanCase],
    ) -> None:
        severity = severity_for_relation(relation)
        for source in source_cases:
            for target in target_cases:
                timing_result = self._timing_status(relation, source, target)
                if timing_result.status == "false":
                    continue
                pair_ids = (case_ref(source), case_ref(target))
                if timing_result.status == "unknown":
                    report.diagnostics.append(
                        Diagnostic(
                            "WARN" if severity == "ERROR" else "PENDING",
                            "EXCLUSION_TIMING_UNRESOLVED",
                            f"无法确认互斥关系 {relation.relation_id} 的时间条件",
                            pair_ids,
                            relation.relation_id,
                            timing_result.details,
                        )
                    )
                    continue
                scope_result = self._scope_status(relation, source, target)
                if scope_result.status == "false":
                    continue
                if scope_result.status == "unknown":
                    report.diagnostics.append(
                        Diagnostic(
                            "WARN" if severity == "ERROR" else "PENDING",
                            "EXCLUSION_SCOPE_UNRESOLVED",
                            f"无法确认互斥关系 {relation.relation_id} 的适用范围，未自动判定冲突",
                            pair_ids,
                            relation.relation_id,
                            {"scope": relation.scope, **scope_result.details},
                        )
                    )
                    continue
                report.diagnostics.append(
                    Diagnostic(
                        severity,
                        "MUTUAL_EXCLUSION_VIOLATION",
                        f"{case_ref(source)} 与 {case_ref(target)} 在互斥时间窗口内同时有效",
                        pair_ids,
                        relation.relation_id,
                        {"source": relation.source, "target": relation.target},
                    )
                )

    def _record_interaction(
        self,
        report: CheckReport,
        relation: GraphRelation,
        source_cases: list[PlanCase],
        target_cases: list[PlanCase],
    ) -> None:
        rule_status = relation_status(relation)
        for source in source_cases:
            for target in target_cases:
                pair_ids = (case_ref(source), case_ref(target))
                timing_result = self._timing_status(relation, source, target)
                if timing_result.status == "false":
                    continue
                if timing_result.status == "unknown":
                    report.diagnostics.append(
                        Diagnostic(
                            "WARN" if rule_status == FORMAL_RELATION_STATUS else "PENDING",
                            "INTERACTION_TIMING_UNRESOLVED",
                            f"{relation.relation} 关系的时间条件尚不能确认",
                            pair_ids,
                            relation.relation_id,
                            timing_result.details,
                        )
                    )
                    continue
                scope_result = self._scope_status(relation, source, target)
                if scope_result.status == "unknown":
                    report.diagnostics.append(
                        Diagnostic(
                            "WARN" if rule_status == FORMAL_RELATION_STATUS else "PENDING",
                            "INTERACTION_SCOPE_UNRESOLVED",
                            f"{relation.relation} 关系已满足时间条件，但 scope 尚不能由 XML 和 context 证明",
                            pair_ids,
                            relation.relation_id,
                            {"scope": relation.scope, **scope_result.details},
                        )
                    )
                elif scope_result.status == "true":
                    if rule_status == CANDIDATE_RELATION_STATUS:
                        report.diagnostics.append(
                            Diagnostic(
                                "PENDING",
                                "CANDIDATE_INTERACTION",
                                f"{relation.relation} 关系仅为候选规则，当前 interaction 不能作为正式编排约束",
                                pair_ids,
                                relation.relation_id,
                                {"scope": relation.scope},
                            )
                        )
                    report.active_interactions.append(
                        ActiveInteraction(
                            relation_id=relation.relation_id,
                            relation=relation.relation,
                            source_case=case_ref(source),
                            target_case=case_ref(target),
                            source_event=source.event_id or relation.source,
                            target_event=target.event_id or relation.target,
                            scope=relation.scope,
                            scope_status=scope_result.status,
                            rule_status=rule_status,
                        )
                    )

    def _timing_status(
        self, relation: GraphRelation, source: PlanCase, target: PlanCase
    ) -> ConditionResult:
        if not source.valid or not target.valid:
            return ConditionResult("false", "invalid_case")

        # Old overlap labels are equivalent. Never reinterpret a sequential rule.
        legacy_mode = str(relation.properties.get("timing_mode", "overlap")).casefold()
        legacy_gaps = [key for key in ("min_gap_ms", "max_gap_ms") if key in relation.properties]
        if legacy_mode not in {"overlap", "exclusive_ownership"} or legacy_gaps:
            return ConditionResult(
                "unknown",
                "unsupported_legacy_timing",
                {"legacy_timing_mode": legacy_mode, "unsupported_gap_fields": legacy_gaps},
            )
        if str(relation.properties.get("timing_status", "calibrated")).casefold() == "uncalibrated":
            return ConditionResult("unknown", "uncalibrated_timing")

        overlap_ms = min(source.end_ms, target.end_ms) - max(source.start_ms, target.start_ms)
        if overlap_ms <= 0:
            return ConditionResult("false", "no_overlap", {"overlap_ms": overlap_ms})
        minimum = finite_property(relation.properties, "min_overlap_ms", 0.0)
        if minimum is None or minimum < 0:
            return ConditionResult("unknown", "invalid_min_overlap")
        return ConditionResult(
            "true" if overlap_ms >= minimum else "false",
            "overlap",
            {"overlap_ms": overlap_ms, "min_overlap_ms": minimum},
        )

    def _scope_status(
        self, relation: GraphRelation, source: PlanCase, target: PlanCase
    ) -> ConditionResult:
        preconditions = condition_names(relation.scope)
        preconditions.extend(condition_names(relation.properties.get("preconditions", [])))
        exclusions = condition_names(relation.properties.get("exclusions", []))
        if not preconditions and relation.scope.strip():
            return ConditionResult("unknown", "unrecognized_scope", {"scope": relation.scope})

        unknown: list[str] = []
        for condition in deduplicate(preconditions):
            result = self._evaluate_condition(condition, source, target)
            if result.status == "false":
                return result
            if result.status == "unknown":
                unknown.append(condition)
        for condition in deduplicate(exclusions):
            result = self._evaluate_condition(condition, source, target)
            if result.status == "true":
                return ConditionResult("false", "exclusion_applies", {"condition": condition})
            if result.status == "unknown":
                unknown.append(f"exclusion:{condition}")
        if unknown:
            return ConditionResult("unknown", "scope_fact_missing", {"unknown_conditions": unknown})
        return ConditionResult("true", "scope_satisfied")

    def _evaluate_condition(
        self, condition: str, source: PlanCase, target: PlanCase
    ) -> ConditionResult:
        pair_fact = context_pair_fact(self.context, case_ref(source), case_ref(target), condition)
        if pair_fact is not None:
            return ConditionResult("true" if pair_fact else "false", "pair_context")
        global_fact = context_global_fact(self.context, condition)
        if global_fact is not None:
            return ConditionResult("true" if global_fact else "false", "global_context")

        field_by_condition = {
            "same_host": "host",
            "same_database": "database",
            "same_connection_path": "connection_path",
            "same_filesystem": "filesystem",
            "same_recovery_record": "recovery_record",
        }
        field_name = field_by_condition.get(condition)
        if field_name is None:
            boolean_case_conditions = {
                "client_retry": "client_retry",
                "fully_suspended": "service_fully_suspended",
                "suspended_frontend": "suspended_frontend",
                "terminated_service": "service_terminated",
            }
            boolean_field = boolean_case_conditions.get(condition)
            if boolean_field is not None:
                left = parse_text_bool(case_fact(self.context, source, boolean_field))
                right = parse_text_bool(case_fact(self.context, target, boolean_field))
                if left is None or right is None:
                    return ConditionResult(
                        "unknown", "boolean_case_fact_missing", {"field": boolean_field}
                    )
                return ConditionResult("true" if left and right else "false", "boolean_case_fact")
            return ConditionResult("unknown", "context_only_condition")
        left = case_fact(self.context, source, field_name)
        right = case_fact(self.context, target, field_name)
        if left is None or right is None:
            return ConditionResult("unknown", "case_fact_missing", {"field": field_name})
        return ConditionResult("true" if left == right else "false", "case_fact_comparison")

    def _finalize_report(self, report: CheckReport, relations: list[GraphRelation]) -> None:
        report.unknown_count = sum(
            1
            for item in report.diagnostics
            if item.code.endswith("UNRESOLVED")
            or item.code in {"DECLARED_EFFECTS_UNCHECKED", "PREVIEW_RELATION_MODE"}
        )
        invalid_codes = {
            "MISSING_CASE_ID",
            "DUPLICATE_CASE_ID",
            "UNKNOWN_EVENT_DESCRIPTION",
            "AMBIGUOUS_EVENT_DESCRIPTION",
            "INVALID_NUMBER",
            "NEGATIVE_TIME",
            "NON_POSITIVE_DURATION",
            "INVALID_TIME_INTERVAL",
            "INCOMPATIBLE_TIME_MODEL",
            "CONFLICTING_EVENT_IDS",
            "EVENT_ID_DESCRIPTION_MISMATCH",
            "EVENT_NODE_MISSING",
            "UNKNOWN_RELATION_TYPE",
            "EMPTY_PLAN",
        }
        if any(item.severity == "ERROR" and item.code in invalid_codes for item in report.diagnostics):
            report.status = REPORT_STATUS_INVALID
        elif report.errors:
            report.status = REPORT_STATUS_VIOLATION
        elif report.pending or any(
            item.severity == "WARN"
            and item.code in {
                "EXCLUSION_SCOPE_UNRESOLVED",
                "EXCLUSION_TIMING_UNRESOLVED",
                "INTERACTION_SCOPE_UNRESOLVED",
                "INTERACTION_TIMING_UNRESOLVED",
                "DEPENDENCY_UNRESOLVED",
                "DECLARED_EFFECTS_UNCHECKED",
            }
            for item in report.diagnostics
        ):
            report.status = REPORT_STATUS_INDETERMINATE
        elif not relations or report.checked_relation_count == 0:
            report.status = REPORT_STATUS_NO_RULES
        else:
            report.status = REPORT_STATUS_PASS_CHECKED_CONSTRAINTS


def case_ref(case: PlanCase) -> str:
    return f"{case.suite_name}::{case.case_id}"


def relation_status(relation: GraphRelation) -> str:
    properties = relation.properties
    is_formal = (
        str(properties.get("validation_status", "")).casefold() == "validated"
        and properties.get("compile_enabled") is True
        and properties.get("example_only") is False
        and properties.get("scope_implemented") is True
    )
    return FORMAL_RELATION_STATUS if is_formal else CANDIDATE_RELATION_STATUS


def severity_for_relation(relation: GraphRelation) -> str:
    return "ERROR" if relation_status(relation) == FORMAL_RELATION_STATUS else "PENDING"


def deduplicate(items: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def finite_property(
    properties: Mapping[str, Any], name: str, default: Optional[float] = None
) -> Optional[float]:
    value = properties.get(name, default)
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def condition_names(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        result: list[str] = []
        for item in value:
            result.extend(condition_names(item))
        return result
    text = str(value).strip().casefold().replace("-", "_")
    if not text:
        return []
    aliases = (
        ("same_connection_path", "same_connection_path"),
        ("same_tcp_flow", "same_connection_path"),
        ("client_retry", "client_retry"),
        ("same_database_instance", "same_database"),
        ("same_database", "same_database"),
        ("same_db", "same_database"),
        ("same_host", "same_host"),
        ("same_filesystem", "same_filesystem"),
        ("same_recovery_record", "same_recovery_record"),
        ("same_memory_domain", "same_memory_domain"),
        ("same_reclaim_domain", "same_memory_domain"),
        ("same_memory", "same_memory_domain"),
        ("cross_filesystem", "cross_filesystem"),
        ("affected_filesystem", "affected_filesystem"),
        ("fully_suspended", "fully_suspended"),
        ("suspended_frontend", "suspended_frontend"),
        ("terminated_service", "terminated_service"),
        ("terminated_frontend", "terminated_service"),
    )
    if any(separator in text for separator in (",", "+", "&", "|", ";")):
        pieces = re.split(r"[,+&|;]", text)
        return [item for piece in pieces for item in condition_names(piece)]
    found: list[str] = []
    for alias, canonical in aliases:
        if alias in text:
            found.append(canonical)
    return deduplicate(found) if found else [text]


def strict_context_bool(value: Any) -> Optional[bool]:
    return value if isinstance(value, bool) else None


def context_global_fact(context: Mapping[str, Any], name: str) -> Optional[bool]:
    facts = context.get("global_facts", {})
    if not isinstance(facts, Mapping):
        return None
    return strict_context_bool(facts.get(name))


def context_pair_fact(
    context: Mapping[str, Any], source: str, target: str, name: str
) -> Optional[bool]:
    facts = context.get("pairs", {})
    if not isinstance(facts, Mapping):
        return None
    keys = (f"{source}|{target}", f"{target}|{source}", f"{source}->{target}")
    for key in keys:
        entry = facts.get(key)
        if isinstance(entry, Mapping) and name in entry:
            return strict_context_bool(entry[name])
    return None


def parse_text_bool(value: Optional[str]) -> Optional[bool]:
    if value is None:
        return None
    normalized = value.strip().casefold()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    return None


def case_fact(context: Mapping[str, Any], case: PlanCase, field_name: str) -> Optional[str]:
    case_facts = context.get("cases", {})
    if isinstance(case_facts, Mapping):
        facts = case_facts.get(case_ref(case))
        if isinstance(facts, Mapping) and field_name in facts:
            value = facts[field_name]
            return str(value) if value is not None else None
    return case.attributes.get(field_name) or case.parameters.get(field_name)


def normalize_text(value: str) -> str:
    return " ".join(value.strip().casefold().split())


def child_text(element: ET.Element, tag: str) -> str:
    child = element.find(tag)
    return "" if child is None or child.text is None else child.text.strip()


def optional_child_text(element: ET.Element, tag: str) -> Optional[str]:
    value = child_text(element, tag)
    return value or None


def validate_unified_end_fields(
    element: ET.Element, case_id: str, diagnostics: list[Diagnostic]
) -> bool:
    """Accept omitted or zero legacy durations; reject every conflicting value."""
    names = {
        "recoveryDuring", "recovery_during", "recoveryDuration", "recovery_duration",
        "ownershipDuring", "ownership_during", "ownershipDuration", "ownership_duration",
    }
    values = [(name, value) for name, value in element.attrib.items() if name in names]
    values.extend((item.tag, item.text or "") for item in element if item.tag in names)
    # Read raw entries so duplicate aliases cannot hide a nonzero duration.
    for item in element.findall("./params/param") + element.findall("./parameters/parameter"):
        name = (item.get("name") or item.get("key") or "").strip()
        if name in names:
            values.append((name, item.get("value") or item.text or ""))
    valid = True
    for name, value in values:
        seconds = parse_nonnegative_number(value, name, case_id, diagnostics)
        if seconds is None:
            valid = False
        elif seconds != 0:
            valid = False
            diagnostics.append(Diagnostic(
                "ERROR", "INCOMPATIBLE_TIME_MODEL",
                f"{name} 与统一结束时刻冲突；请省略该字段或设为 0",
                (case_id,), details={"field": name, "value": seconds, "time_model": TIME_MODEL},
            ))
    return valid


def read_case_attributes(element: ET.Element) -> dict[str, str]:
    names = {
        "host",
        "database",
        "connection_path",
        "filesystem",
        "interface",
        "process",
        "service",
        "target",
        "target_path",
        "source_path",
        "destination_path",
        "recovery_record",
        "client_retry",
        "service_fully_suspended",
        "suspended_frontend",
        "service_terminated",
    }
    result: dict[str, str] = {}
    for name in names:
        value = (element.get(name) or "").strip() or optional_child_text(element, name) or ""
        if value:
            result[name] = value
    return result


def read_parameters(element: ET.Element) -> dict[str, str]:
    parameters: dict[str, str] = {}
    for item in element.findall("./params/param") + element.findall("./parameters/parameter"):
        name = (item.get("name") or item.get("key") or "").strip()
        value = (item.get("value") or item.text or "").strip()
        if name:
            parameters[name] = value
    return parameters


def read_declared_effects(element: ET.Element) -> tuple[str, ...]:
    values: list[str] = []
    for path in ("./declaredEffects/effect", "./executionEffects/effect", "./actualEvents/event"):
        for item in element.findall(path):
            value = (item.get("event_id") or item.get("id") or item.text or "").strip()
            if value:
                values.append(value)
    return tuple(deduplicate(values))


def parse_nonnegative_number(
    value: str, field_name: str, case_id: str, diagnostics: list[Diagnostic]
) -> Optional[float]:
    try:
        number = float(value)
    except (TypeError, ValueError):
        diagnostics.append(
            Diagnostic("ERROR", "INVALID_NUMBER", f"case {case_id} 的 {field_name} 不是数字", (case_id,))
        )
        return None
    if not math.isfinite(number):
        diagnostics.append(
            Diagnostic(
                "ERROR",
                "INVALID_NUMBER",
                f"case {case_id} 的 {field_name} 必须是有限数字",
                (case_id,),
            )
        )
        return None
    if number < 0:
        diagnostics.append(
            Diagnostic("ERROR", "NEGATIVE_TIME", f"case {case_id} 的 {field_name} 不能为负数", (case_id,))
        )
        return None
    return number


def parse_positive_number(
    value: str, field_name: str, case_id: str, diagnostics: list[Diagnostic]
) -> Optional[float]:
    number = parse_nonnegative_number(value, field_name, case_id, diagnostics)
    if number is not None and number <= 0:
        diagnostics.append(
            Diagnostic("ERROR", "NON_POSITIVE_DURATION", f"case {case_id} 的 {field_name} 必须大于 0", (case_id,))
        )
        return None
    return number


def load_context(path: Optional[Path]) -> dict[str, Any]:
    if path is None:
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("context JSON 必须是对象")
    global_facts = payload.get("global_facts", {})
    pairs = payload.get("pairs", {})
    cases = payload.get("cases", {})
    if not isinstance(global_facts, Mapping):
        raise ValueError("context.global_facts 必须是对象")
    if not isinstance(pairs, Mapping):
        raise ValueError("context.pairs 必须是对象")
    if not isinstance(cases, Mapping):
        raise ValueError("context.cases 必须是对象")
    if any(strict_context_bool(value) is None for value in global_facts.values()):
        raise ValueError("context.global_facts 的值必须是真正的 JSON 布尔值")
    for pair_key, pair_value in pairs.items():
        if not isinstance(pair_value, Mapping):
            raise ValueError(f"context.pairs[{pair_key!r}] 必须是对象")
        if any(strict_context_bool(value) is None for value in pair_value.values()):
            raise ValueError(f"context.pairs[{pair_key!r}] 的条件值必须是真正的 JSON 布尔值")
    legacy_boolean_keys = [
        key for key, value in payload.items()
        if key not in {"global_facts", "pairs", "cases"} and isinstance(value, bool)
    ]
    if legacy_boolean_keys:
        raise ValueError(
            "scope 条件必须放在 global_facts/pairs 中，不能使用会误用于所有实例对的顶层布尔值: "
            + ", ".join(legacy_boolean_keys)
        )
    return payload


def print_report(report: CheckReport) -> None:
    print(f"Plan: {report.plan_path}")
    print(f"Graph: {report.graph_version} ({report.relation_mode})")
    print("Check scope: declared XML plan; actual injection/recovery effects are not verified")
    print("Time model: unified event end (activity, event cleanup and resource release)")
    print(
        f"Status: {report.status} | Cases: {len(report.cases)} | "
        f"Errors: {len(report.errors)} | Warnings: {len(report.warnings)} | "
        f"Pending: {len(report.pending)} | Active interactions: {len(report.active_interactions)}"
    )
    print(
        f"Relations: loaded={report.loaded_relation_count}, selected={report.selected_relation_count}, "
        f"formal={report.formal_relation_count}, "
        f"candidate={report.candidate_relation_count}, checked={report.checked_relation_count}, "
        f"unknown={report.unknown_count}"
    )
    if report.uncovered_event_ids:
        print(f"Events without checked rules: {', '.join(report.uncovered_event_ids)}")
    if report.status == REPORT_STATUS_NO_RULES:
        print("No applicable rules were checked; this is not a pass result.")
    for item in report.diagnostics:
        suffix = f" [{item.relation_id}]" if item.relation_id else ""
        cases = f" cases={','.join(item.case_ids)}" if item.case_ids else ""
        print(f"{item.severity} {item.code}{suffix}{cases}: {item.message}")
    for interaction in report.active_interactions:
        print(
            f"{interaction.rule_status.upper()} "
            f"{interaction.relation} {interaction.source_case}->{interaction.target_case} "
            f"({interaction.relation_id})"
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check ResBench XML plan constraints")
    parser.add_argument("--plan", required=True, type=Path, help="XML test plan")
    parser.add_argument("--neo4j-uri", default="bolt://localhost:7687")
    parser.add_argument("--neo4j-user", default=os.getenv("NEO4J_USERNAME", "neo4j"))
    parser.add_argument("--neo4j-database", default="neo4j")
    parser.add_argument("--neo4j-password-env", default="NEO4J_PASSWORD")
    parser.add_argument("--graph-version", default=GRAPH_VERSION)
    parser.add_argument(
        "--relation-mode",
        choices=("strict", "preview"),
        default="strict",
        help="strict（默认，论文主流程）检查已验证可编排关系；preview 为候选关系辅助预览",
    )
    parser.add_argument("--context", type=Path, help="optional JSON scope facts")
    parser.add_argument("--json-out", type=Path, help="optional JSON report path")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        context = load_context(args.context)
        password = os.getenv(args.neo4j_password_env)
        if not password:
            raise RuntimeError(
                f"环境变量 {args.neo4j_password_env} 未设置；不会连接 Neo4j"
            )
        with Neo4jRelationStore(
            args.neo4j_uri,
            args.neo4j_user,
            password,
            args.graph_version,
            args.relation_mode,
            args.neo4j_database,
        ) as store:
            relations = store.load_relations()
            catalog = GraphEventCatalog(store.load_events())
        cases, plan_diagnostics = XmlPlanReader().read(args.plan, catalog)
        report = ConstraintChecker(args.graph_version, args.relation_mode, context).check(
            args.plan, cases, plan_diagnostics, relations, set(catalog.by_id)
        )
        print_report(report)
        if args.json_out:
            args.json_out.write_text(
                json.dumps(report.as_dict(), ensure_ascii=False, indent=2), encoding="utf-8"
            )
    except Exception as error:
        print(f"检查器初始化失败: {error}", file=sys.stderr)
        return 2

    return {
        REPORT_STATUS_PASS_CHECKED_CONSTRAINTS: 0,
        REPORT_STATUS_VIOLATION: 1,
        REPORT_STATUS_INVALID: 2,
        REPORT_STATUS_INDETERMINATE: 3,
        REPORT_STATUS_NO_RULES: 4,
    }.get(report.status, 2)


if __name__ == "__main__":
    raise SystemExit(main())
