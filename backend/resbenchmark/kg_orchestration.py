from __future__ import annotations

import argparse
import getpass
import json
import os
from pathlib import Path
import sys
from dataclasses import dataclass, field, replace
from typing import Any, Mapping, Optional

if __package__:
    from . import constraint_checker as shared
    from . import plan_rule_semantics as plan_semantics
else:
    import constraint_checker as shared
    import plan_rule_semantics as plan_semantics


@dataclass
class PlanCheckReport(shared.CheckReport):
    rule_interpretations: list[dict[str, Any]] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["semantics_version"] = plan_semantics.VERSION
        payload["time_basis"] = "declared_plan_intervals"
        payload["rule_interpretations"] = self.rule_interpretations
        payload["runtime_validation_required"] = True
        payload["dependency_policy"] = "selected source requires target; source applicability gate not added"
        for interaction in payload["active_interactions"]:
            interaction["interpretation"] = "potential_plan_interaction"
            interaction["effect_verified"] = False
        return payload


@dataclass
class NativeCheckReport(PlanCheckReport):
    native_input: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        payload = super().as_dict()
        payload["check_scope"] = "native_schedule_and_config_bindings"
        payload["native_input"] = self.native_input
        return payload


class OrchestrationChecker(shared.ConstraintChecker):
    """Check eligible rules only; never repair, reorder or execute events."""

    def __init__(
        self,
        graph_version: str = shared.GRAPH_VERSION,
        context: Optional[Mapping[str, Any]] = None,
        *,
        relation_mode: str = "strict",
    ) -> None:
        super().__init__(graph_version, relation_mode, context if context is not None else {})

    def check(
        self, plan_path: Path, cases: list[shared.PlanCase],
        plan_diagnostics: list[shared.Diagnostic], relations: list[shared.GraphRelation],
        graph_event_ids: Optional[set[str]] = None,
    ) -> PlanCheckReport:
        report = super().check(plan_path, cases, plan_diagnostics, relations, graph_event_ids)
        selected = [r for r in relations
                    if str(r.properties.get("validation_status", "proposed")).casefold() != "rejected"
                    and (self.relation_mode == "preview"
                         or shared.relation_status(r) == shared.FORMAL_RELATION_STATUS)]
        return PlanCheckReport(**vars(report), rule_interpretations=[
            plan_semantics.rule_summary(r) for r in sorted(selected, key=lambda r: r.relation_id)
        ])

    def phi_r(
        self,
        relation: shared.GraphRelation,
        source: shared.PlanCase,
        target: shared.PlanCase,
    ) -> shared.ConditionResult:
        """Phi_r(i,j;C): plan declarations, not empirical effect verification."""
        return plan_semantics.scope_status(relation, source, target, self._plan_condition)

    def _plan_condition(
        self, condition: str, source: shared.PlanCase, target: shared.PlanCase,
    ) -> shared.ConditionResult:
        # These resource identities must be explicit, never inferred from a host.
        fields = {"same_connection_budget": "connection_budget",
                  "same_memory_domain": "memory_domain",
                  "shared_cpu_budget": "cpu_budget"}
        supplied = shared.context_pair_fact(self.context, shared.case_ref(source),
                                            shared.case_ref(target), condition)
        if supplied is None:
            supplied = shared.context_global_fact(self.context, condition)
        if supplied is not None or condition not in fields:
            return self._evaluate_condition(condition, source, target)
        left = shared.case_fact(self.context, source, fields[condition])
        right = shared.case_fact(self.context, target, fields[condition])
        if not left or not right:
            return shared.ConditionResult("unknown", "plan_resource_identity_missing")
        return shared.ConditionResult("true" if left == right else "false",
                                      "declared_resource_identity")

    def theta_r(
        self,
        relation: shared.GraphRelation,
        source: shared.PlanCase,
        target: shared.PlanCase,
    ) -> shared.ConditionResult:
        """Theta_r(i,j): compare declared intervals, not measured effects."""
        # Empirical calibration does not gate arithmetic on a declared plan.
        # Keep every explicit overlap threshold and unsupported timing guard.
        properties = dict(relation.properties)
        empirical_status = properties.pop("timing_status", None)
        result = self._timing_status(replace(relation, properties=properties), source, target)
        return replace(result, details={
            **result.details, "time_basis": "declared_plan_intervals",
            "effect_calibration_status": empirical_status,
            "execution_effects_verified": False,
        })

    def psi_r(
        self,
        relation: shared.GraphRelation,
        source: shared.PlanCase,
        target: shared.PlanCase,
    ) -> shared.ConditionResult:
        """Psi_r(i,j): prerequisite state in the plan, never observed state."""
        state = str(relation.properties.get("required_state", "active"))
        return self._dependency_status(source, target, state)

    def _check_relations(
        self,
        report: shared.CheckReport,
        relations: list[shared.GraphRelation],
        graph_event_ids: Optional[set[str]],
    ) -> None:
        """Implement B, K, D, I and U from the paper's merged algorithm.

        The inherited check() selects rules using the explicit relation mode and
        applies final status precedence afterwards. Unknown results short-circuit
        within an instance pair; another target may still satisfy a dependency.
        """
        # B[(suite, event_type)] contains only valid XML instances.
        index: dict[tuple[str, str], list[shared.PlanCase]] = {}
        plan_event_ids: set[str] = set()
        for case in report.cases:
            if case.valid and case.event_id:
                index.setdefault((case.suite_name, case.event_id), []).append(case)
                plan_event_ids.add(case.event_id)

        if graph_event_ids is not None:
            for event_id in sorted(plan_event_ids - graph_event_ids):
                report.diagnostics.append(
                    shared.Diagnostic(
                        "ERROR",
                        "EVENT_NODE_MISSING",
                        f"Event {event_id} is absent from graph {self.graph_version}",
                        details={"event_id": event_id, "graph_version": self.graph_version},
                    )
                )

        checked_rule_ids: set[str] = set()  # K
        suite_names = sorted({suite for suite, _ in index})

        for relation in relations:
            severity = shared.severity_for_relation(relation)
            rule_status = shared.relation_status(relation)
            if relation.relation not in shared.ALLOWED_RELATIONS:
                report.diagnostics.append(
                    shared.Diagnostic(
                        "ERROR",
                        "UNKNOWN_RELATION_TYPE",
                        f"Unsupported relation type: {relation.relation}",
                        relation_id=relation.relation_id,
                    )
                )
                continue

            for suite_name in suite_names:
                sources = index.get((suite_name, relation.source), [])
                if not sources:
                    continue
                targets = index.get((suite_name, relation.target), [])
                if (rule_status == shared.CANDIDATE_RELATION_STATUS
                        and relation.relation_id not in checked_rule_ids
                        and (relation.relation == "depends_on" or targets)):
                    report.diagnostics.append(shared.Diagnostic(
                        "PENDING", "CANDIDATE_RULE_EVALUATED",
                        "Candidate evaluated for experiment only; not a validated constraint",
                        relation_id=relation.relation_id,
                        details={"rule_status": rule_status, "relation": relation.relation},
                    ))

                if relation.relation == "depends_on":
                    # Missing targets still constitute a checkable dependency.
                    checked_rule_ids.add(relation.relation_id)
                    required_state = str(relation.properties.get("required_state", "active"))
                    for source in sources:
                        if not targets:
                            report.diagnostics.append(
                                shared.Diagnostic(
                                    severity,
                                    "MISSING_DEPENDENCY",
                                    f"{shared.case_ref(source)} requires event {relation.target}",
                                    (shared.case_ref(source),),
                                    relation.relation_id,
                                    {"required_state": required_state},
                                )
                            )
                            continue

                        satisfied = False  # v
                        unknown_pairs: list[dict[str, Any]] = []  # Q
                        for target in targets:
                            stage = "scope"
                            result = self.phi_r(relation, source, target)
                            if result.status == "true":
                                stage = "prerequisite_state"
                                result = self.psi_r(relation, source, target)
                            if result.status == "true":
                                satisfied = True
                                break
                            if result.status == "unknown":
                                unknown_pairs.append(
                                    {
                                        "source_case": shared.case_ref(source),
                                        "target_case": shared.case_ref(target),
                                        "stage": stage,
                                        "reason": result.reason,
                                        "details": dict(result.details),
                                    }
                                )

                        if not satisfied:
                            report.diagnostics.append(
                                shared.Diagnostic(
                                    ("WARN" if severity == "ERROR" else "PENDING") if unknown_pairs else severity,
                                    "DEPENDENCY_UNRESOLVED" if unknown_pairs else "UNSATISFIED_DEPENDENCY",
                                    (
                                        f"Dependency for {shared.case_ref(source)} is indeterminate"
                                        if unknown_pairs
                                        else f"No target satisfies the dependency for {shared.case_ref(source)}"
                                    ),
                                    (shared.case_ref(source),),
                                    relation.relation_id,
                                    {"required_state": required_state, "unknown_pairs": unknown_pairs},
                                )
                            )

                elif targets:
                    checked_rule_ids.add(relation.relation_id)
                    for source in sources:
                        for target in targets:
                            stage = "TIMING"
                            result = self.theta_r(relation, source, target)
                            if result.status == "true":
                                stage = "SCOPE"
                                result = self.phi_r(relation, source, target)

                            pair_ids = (shared.case_ref(source), shared.case_ref(target))
                            if result.status == "unknown":
                                prefix = (
                                    "EXCLUSION"
                                    if relation.relation == "mutually_exclusive"
                                    else "INTERACTION"
                                )
                                report.diagnostics.append(
                                    shared.Diagnostic(
                                        "WARN" if severity == "ERROR" else "PENDING",
                                        f"{prefix}_{stage}_UNRESOLVED",
                                        f"Cannot determine {stage.lower()} for {relation.relation}",
                                        pair_ids,
                                        relation.relation_id,
                                        {
                                            "scope": relation.scope,
                                            "reason": result.reason,
                                            **result.details,
                                        },
                                    )
                                )
                            elif result.status == "true":
                                if relation.relation == "mutually_exclusive":
                                    report.diagnostics.append(
                                        shared.Diagnostic(
                                            severity,
                                            "MUTUAL_EXCLUSION_VIOLATION",
                                            f"{pair_ids[0]} and {pair_ids[1]} conflict",
                                            pair_ids,
                                            relation.relation_id,
                                            {"source": relation.source, "target": relation.target},
                                        )
                                    )
                                else:
                                    # Static matches are potential, not measured effects.
                                    report.active_interactions.append(
                                        shared.ActiveInteraction(
                                            relation_id=relation.relation_id,
                                            relation=relation.relation,
                                            source_case=pair_ids[0],
                                            target_case=pair_ids[1],
                                            source_event=source.event_id or relation.source,
                                            target_event=target.event_id or relation.target,
                                            scope=relation.scope,
                                            scope_status="true",
                                            rule_status=rule_status,
                                        )
                                    )

        report.checked_relation_count = len(checked_rule_ids)
        covered_types = {
            event_id
            for relation in relations
            if relation.relation_id in checked_rule_ids
            for event_id in (relation.source, relation.target)
        }
        report.uncovered_event_ids = sorted(plan_event_ids - covered_types)  # U


def check_xml_plan(
    plan_path: Path,
    events: list[shared.GraphEvent],
    relations: list[shared.GraphRelation],
    *,
    graph_version: str = shared.GRAPH_VERSION,
    relation_mode: str = "strict",
    context: Optional[Mapping[str, Any]] = None,
    native_config: Optional[Path] = None,
    worker_path: Optional[Path] = None,
    event_map: Optional[Path] = None,
) -> shared.CheckReport:
    """Parse XML and check an already loaded graph without network access.

    XML or graph initialization failures raise an exception. Invalid individual
    cases remain in the report with diagnostics and are excluded from pairing.
    """
    catalog = shared.GraphEventCatalog(events)
    if native_config is not None:
        if __package__:
            from . import native_plan_adapter as native
        else:
            import native_plan_adapter as native
        bundle = native.NativeBundle.read(
            native_config, worker_path, require_worker=worker_path is not None
        )
        adapted = native.adapt_plan(
            plan_path, bundle, catalog,
            bindings=native.load_event_map(event_map, bundle), context=context,
        )
        report = OrchestrationChecker(graph_version, adapted.context, relation_mode=relation_mode).check(
            plan_path, adapted.cases, adapted.diagnostics, relations, set(catalog.by_id)
        )
        # Input adaptation is outside the unchanged relation algorithm.
        if any(d.severity == "ERROR" and d.code.startswith("NATIVE_") for d in adapted.diagnostics):
            report.status = shared.REPORT_STATUS_INVALID
        return NativeCheckReport(**vars(report), native_input=adapted.metadata)
    if worker_path is not None or event_map is not None:
        raise ValueError("--worker/--event-map require --native-config")
    cases, diagnostics = shared.XmlPlanReader().read(plan_path, catalog)
    return OrchestrationChecker(graph_version, context, relation_mode=relation_mode).check(
        plan_path, cases, diagnostics, relations, set(catalog.by_id)
    )


def check_with_neo4j(
    plan_path: Path,
    *,
    password: str,
    uri: str = "bolt://localhost:7687",
    user: str = "neo4j",
    database: str = "neo4j",
    graph_version: str = shared.GRAPH_VERSION,
    relation_mode: str = "strict",
    context: Optional[Mapping[str, Any]] = None,
    native_config: Optional[Path] = None,
    worker_path: Optional[Path] = None,
    event_map: Optional[Path] = None,
) -> shared.CheckReport:
    """Explicit I/O entry: read Neo4j, close the driver, then check the XML."""
    with shared.Neo4jRelationStore(
        uri, user, password, graph_version, relation_mode, database
    ) as store:
        events = store.load_events()
        relations = store.load_relations()
    return check_xml_plan(
        plan_path, events, relations, graph_version=graph_version, relation_mode=relation_mode, context=context,
        native_config=native_config, worker_path=worker_path, event_map=event_map,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check declared XML plan relations using the unchanged paper algorithm")
    parser.add_argument("--plan", type=Path, required=True, help="Existing ResBench XML plan")
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--config", "--native-config", dest="native_config", type=Path,
                        help="Companion ResBench main XML; worker references are not followed")
    inputs.add_argument("--legacy-plan-only", action="store_true",
                        help="Explicit compatibility mode without main-config binding")
    parser.add_argument("--worker", type=Path, help="Optional explicit opt-in to legacy worker validation")
    parser.add_argument("--event-map", type=Path, help="Optional reviewed config-bound ID-to-type CSV")
    parser.add_argument("--neo4j-uri", default="bolt://localhost:7687")
    parser.add_argument("--neo4j-user", default=os.getenv("NEO4J_USERNAME", "neo4j"))
    parser.add_argument("--neo4j-database", default="neo4j")
    parser.add_argument("--neo4j-password-env", default="NEO4J_PASSWORD")
    parser.add_argument("--graph-version", default=shared.GRAPH_VERSION)
    parser.add_argument("--relation-mode", choices=("strict", "preview"), default="strict",
                        help="Preview non-rejected candidates without treating them as validated constraints")
    parser.add_argument("--context", type=Path, help="Optional declared plan/scenario facts JSON; not event templates or measured-effect proof")
    parser.add_argument("--json-out", type=Path, help="Optional report output; not an input requirement")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        plan_path = args.plan.resolve(strict=True)
        if not plan_path.is_file():
            raise ValueError(f"XML plan is not a file: {plan_path}")
        if args.native_config is None and (args.worker is not None or args.event_map is not None):
            raise ValueError("--worker/--event-map require --config")
        context = shared.load_context(args.context)
        password = os.getenv(args.neo4j_password_env)
        if not password:
            if not sys.stdin.isatty():
                raise ValueError(f"Set {args.neo4j_password_env} or run in an interactive terminal")
            password = getpass.getpass("Neo4j password: ")
        if not password:
            raise ValueError("Neo4j password must not be empty")
        report = check_with_neo4j(
            plan_path,
            password=password,
            uri=args.neo4j_uri,
            user=args.neo4j_user,
            database=args.neo4j_database,
            graph_version=args.graph_version,
            relation_mode=args.relation_mode,
            context=context,
            native_config=args.native_config,
            worker_path=args.worker,
            event_map=args.event_map,
        )
        shared.print_report(report)
        print("Plan-level semantics: intervals and declared conditions only; effects require runtime experiments.")
        if isinstance(report, NativeCheckReport):
            print("Native schedule/main-config checks completed; runtime effects are not verified.")
            if not report.native_input["worker_checked"]:
                print("Worker not read: work-phase bounds and worker suite references were not checked.")
            print(f"Config: {report.native_input['config_path']}")
        if args.json_out:
            args.json_out.write_text(
                json.dumps(report.as_dict(), ensure_ascii=False, indent=2, allow_nan=False),
                encoding="utf-8",
            )
    except (Exception, KeyboardInterrupt) as error:
        print(f"Check failed: {error}", file=sys.stderr)
        return 2

    return {
        shared.REPORT_STATUS_PASS_CHECKED_CONSTRAINTS: 0,
        shared.REPORT_STATUS_VIOLATION: 1,
        shared.REPORT_STATUS_INVALID: 2,
        shared.REPORT_STATUS_INDETERMINATE: 3,
        shared.REPORT_STATUS_NO_RULES: 4,
    }[report.status]


if __name__ == "__main__":
    raise SystemExit(main())
