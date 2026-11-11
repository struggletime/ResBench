"""Reviewed plan predicates; empirical obligations are reported, not proved.

Exact phrases only. Unknown or mixed conditions remain required facts. This
module never edits graph properties or infers an effect from an event name.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

if __package__:
    from . import constraint_checker as shared
else:
    import constraint_checker as shared

VERSION = "declared_plan_v1"

# Each conjunction retains target, resource, executor and observation limits.
# These names require declarations; matching an event type does not prove them.
SCOPE_REQUIREMENTS = {
    "same_db_shared_cpu_budget_with_rollback_work": ("same_database", "shared_cpu_budget"),
    "same_host_shared_cpu_budget": ("same_host", "shared_cpu_budget"),
    "same_connection_path_with_client_retry": ("same_connection_path", "client_retry"),
    "distributed_or_timeout_sensitive_transaction_path": ("same_transaction_path", "distributed_or_timeout_sensitive_transactions"),
    "cross_filesystem_move_to_affected_filesystem": ("cross_filesystem", "destination_on_affected_filesystem"),
    "rollback_wal_or_temp_path_on_affected_filesystem": ("rollback_path_on_affected_filesystem",),
    "transaction_path_with_retry_or_timeout": ("same_transaction_path", "retry_or_timeout_configured"),
    "same_memory_reclaim_domain_with_network_buffers": ("same_memory_domain", "network_buffers_in_memory_domain"),
    "rollback_logging_or_temp_files_accumulate_on_target_filesystem": ("rollback_path_on_affected_filesystem", "rollback_writes_configured"),
    "accepted_connections_and_authentication_share_cpu": ("same_database", "shared_cpu_budget", "session_authentication_configured"),
    "accepted_sessions_share_memory_budget": ("same_database", "same_memory_domain"),
    "fully_suspended_service_successful_write_window": ("planned_full_service_suspension", "successful_write_throughput_observation", "observation_within_suspension"),
    "fully_suspended_service_successful_throughput_window": ("planned_full_service_suspension", "successful_throughput_observation", "observation_within_suspension"),
    "suspended_frontend_with_independent_rollback_work": ("planned_full_frontend_suspension", "independent_rollback_worker"),
    "terminated_service_successful_throughput_window_without_failover": ("planned_full_service_termination", "successful_throughput_observation", "observation_within_termination", "no_restart_or_failover_configured"),
    "terminated_frontend_with_independent_rollback_work": ("planned_full_frontend_termination", "independent_rollback_worker"),
    "same_db_cpu_budget_with_effective_connection_storm": ("same_database", "shared_cpu_budget"),
    "same_tcp_flow_with_rtt_sensitive_loss_recovery": ("same_connection_path", "rtt_sensitive_loss_recovery_configured"),
    "same_recovery_record_current_file_scripts": ("same_recovery_record", "same_filesystem", "reviewed_file_scripts"),
    "same_db_memory_budget_with_session_allocations": ("same_database", "same_memory_domain"),
    "same_db_memory_budget_with_active_rollback_work": ("same_database", "same_memory_domain"),
    "fully_suspended_service_with_independent_cpu_stress": ("planned_full_service_suspension", "independent_cpu_stressor", "successful_throughput_observation", "observation_within_suspension"),
    "fully_terminated_service_with_persistent_disk_full": ("planned_full_service_termination", "independent_disk_pressure", "write_workload_on_affected_filesystem", "successful_throughput_observation", "observation_within_termination"),
}

# Only unambiguously runtime/measurement clauses move out of plan gating.
# Mixed target/resource/observation clauses are deliberately not in this set.
RUNTIME_CONDITIONS = frozenset({
    "rollback operations actually complete", "rollback work is measured",
    "rollback injector cannot run", "cpu_only loss already reaches the measurement floor",
    "network delay remains effective", "rtt and host scheduling delay are observed",
    "service already at a throughput floor", "actual attempt rate is measured",
    "admission control suppresses extra attempts", "rollback cause and count are classified",
    "move result and error are recorded", "file already absent",
    "rollback operations are active", "disk errors are distinguished from connection errors",
    "injector fails before rollback starts", "service already stopped",
    "attempt and success rates are observed", "loss causes complete disconnection",
    "rollback cause is classified", "actual transaction work is recorded",
    "only connection failures are observed", "rollback injector does not complete",
    "reclaim and rtt are observed", "oom termination",
    "rollback work remains active", "connections are actually accepted",
    "attempt rate is recorded", "storm generator is cpu limited",
    "new sessions allocate memory", "successful sessions are counted",
    "connections fail before session allocation", "disk remains full", "disk state is released",
    "memory pressure remains independently active",
    "suspension releases the only memory pressure", "delay injector remains active",
    "successful failover", "loss rule remains effective",
    "packet counters stop because the injector stops", "rollback worker remains active",
    "rollback never starts", "memory pressure remains active after termination",
    "termination releases the only memory pressure", "delay rule remains active",
    "termination stops the delay injector", "loss rule remains active",
    "packet counters are available", "loss injector stops",
    "termination also stops rollback",
    "termination releases deleted open files and clears disk pressure",
    "the storm produces verified connection attempts at a comparable offered rate",
    "database remains alive and foreground loss is not already saturated",
    "fixed_thread backpressure substantially reduces the actual storm rate",
    "admission control prevents meaningful additional database work",
    "application traffic remains active and loss is below complete disconnection",
    "actual rtt and drop rate are measured",
    "complete packet loss or timeout saturation hides any incremental effect",
    "new sessions are admitted and allocate memory",
    "actual connection attempt rates are measured and comparable",
    "database is not killed and foreground loss is not already saturated",
    "immediate process termination invalidates the intended two_event comparison",
    "rollback injector has live sessions and confirmed successful rollback operations",
    "rollback rate and transaction work are recorded and controlled",
    "database stays alive without a saturated service_loss floor",
    "rollback sessions fail to connect or never perform rollbacks",
    "backpressure substantially reduces actual rollback work",
    "database implements cheap aborts for the tested temporary_table workload and no positive interaction is measured",
    "disk_full state is independently verified and persists after process termination",
    "termination frees deleted_but_open files and clears disk exhaustion",
})

CONDITION_REQUIREMENTS = {
    "same db": ("same_database",), "same_db": ("same_database",),
    "same_database_instance": ("same_database",),
    "same host": ("same_host",), "same connection path": ("same_connection_path",),
    "same_tcp_flow": ("same_connection_path",),
    "same host cpu budget": ("same_host", "shared_cpu_budget"),
    "shared cpu budget": ("shared_cpu_budget",),
    "shared memory limit": ("same_memory_domain",),
    "retry policy is enabled": ("client_retry",),
    "client retry is enabled": ("client_retry",),
    "both instances use the reviewed file scripts": ("reviewed_file_scripts",),
    "both resolve to the same recovery_record path in the same filesystem namespace": ("same_recovery_record", "same_filesystem"),
    "ownership intervals from injection through completed recovery overlap": ("planned_intervals_overlap",),
    "all serving processes are suspended": ("planned_full_service_suspension",),
    "service processes are fully suspended": ("planned_full_service_suspension",),
    "frontend is fully suspended": ("planned_full_frontend_suspension",),
    "frontend is terminated": ("planned_full_frontend_termination",),
    "rollback worker is independent and active": ("independent_rollback_worker",),
    "no failover": ("no_failover_configured",),
    "no restart or failover": ("no_restart_or_failover_configured",),
    "throughput is the metric": ("throughput_observation",),
    "throughput is the target metric": ("throughput_observation",),
    "successful throughput is the metric": ("successful_throughput_observation",),
    "stress and database share the effective cpu budget": ("shared_cpu_budget",),
    "memory stress and database share a memory limit or reclaim domain": ("same_memory_domain",),
    "memory pressure affects the same database allocation or reclaim domain": ("same_memory_domain",),
    "both impairments are simultaneously effective on the same relevant tcp flows": ("same_connection_path", "planned_intervals_overlap"),
    "suspension covers every process needed to serve the measured endpoint": ("planned_full_service_suspension",),
    "no replica, failover or proxy serves successful transactions": ("no_alternate_serving_path_configured",),
    "an independent stressor still maintains verified cpu pressure": ("independent_cpu_stressor",),
    "termination makes the measured service unavailable with no failover": ("planned_full_service_termination", "no_failover_configured"),
}

# Split mixed clauses retain the plan requirement AND record the effect to verify.
MIXED_RUNTIME = frozenset({
    "all serving processes are suspended", "service processes are fully suspended",
    "frontend is fully suspended", "frontend is terminated",
    "rollback worker is independent and active",
    "both impairments are simultaneously effective on the same relevant tcp flows",
    "no replica, failover or proxy serves successful transactions",
    "an independent stressor still maintains verified cpu pressure",
    "termination makes the measured service unavailable with no failover",
})


def normalize(text: str) -> str:
    return " ".join(text.strip().casefold().replace("-", "_").split())


def clauses(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, (tuple, list, set)):
        return [text for part in value for text in clauses(part)]
    return [str(value).strip()] if str(value).strip() else []


@dataclass(frozen=True)
class PlanRule:
    requirements: tuple[str, ...]
    exclusions: tuple[str, ...]
    runtime_obligations: tuple[str, ...]


def interpret_rule(relation: shared.GraphRelation) -> PlanRule:
    required, excluded, runtime = [], [], []
    for field, values, destination in (
        ("scope", relation.scope, required),
        ("preconditions", relation.properties.get("preconditions"), required),
        ("exclusions", relation.properties.get("exclusions"), excluded),
    ):
        for text in clauses(values):
            key = normalize(text)
            if field != "scope" and key in RUNTIME_CONDITIONS:
                runtime.append(field + ": " + text)
                continue
            mapping = SCOPE_REQUIREMENTS if field == "scope" else CONDITION_REQUIREMENTS
            if key in mapping:
                destination.extend(mapping[key])
            elif field == "scope" and "+" in key and all(
                    part.strip().replace("_", "").isalnum() for part in key.split("+")):
                destination.extend(part.strip() for part in key.split("+"))
            else:
                destination.extend(CONDITION_REQUIREMENTS.get(key, (key,)))
            if key in MIXED_RUNTIME:
                runtime.append(field + ": " + text)
    return PlanRule(tuple(shared.deduplicate(required)), tuple(shared.deduplicate(excluded)),
                    tuple(shared.deduplicate(runtime)))


def scope_status(relation: shared.GraphRelation, source: shared.PlanCase,
                 target: shared.PlanCase, evaluate: Callable[..., shared.ConditionResult]) -> shared.ConditionResult:
    rule = interpret_rule(relation)
    details: dict[str, Any] = {
        "condition_basis": VERSION, "runtime_obligations": list(rule.runtime_obligations),
        "plan_requirements": list(rule.requirements), "plan_exclusions": list(rule.exclusions),
    }
    unknown = []
    for exclusion, conditions in ((False, rule.requirements), (True, rule.exclusions)):
        for condition in conditions:
            if condition == "planned_intervals_overlap":
                overlap = min(source.end_ms, target.end_ms) - max(source.start_ms, target.start_ms)
                result = shared.ConditionResult("true" if overlap > 0 else "false", "planned_overlap")
            else:
                result = evaluate(condition, source, target)
            if result.status == ("true" if exclusion else "false"):
                return shared.ConditionResult("false", "exclusion_applies" if exclusion else result.reason,
                                              {**details, "condition": condition})
            if result.status == "unknown":
                unknown.append(("exclusion:" if exclusion else "") + condition)
    if unknown:
        return shared.ConditionResult("unknown", "plan_fact_missing",
                                      {**details, "unknown_conditions": unknown})
    return shared.ConditionResult("true", "plan_scope_satisfied", details)


def rule_summary(relation: shared.GraphRelation) -> dict[str, Any]:
    rule = interpret_rule(relation)
    return {
        "relation_id": relation.relation_id, "relation": relation.relation,
        "source": relation.source, "target": relation.target,
        "semantics_version": VERSION, "plan_requirements": list(rule.requirements),
        "plan_exclusions": list(rule.exclusions),
        "runtime_obligations": list(rule.runtime_obligations),
        "original_scope": relation.scope,
        "original_preconditions": relation.properties.get("preconditions", []),
        "original_exclusions": relation.properties.get("exclusions", []),
        "effect_calibration_status": relation.properties.get("timing_status"),
        "plan_time_requirement": (relation.properties.get("required_state", "active")
                                  if relation.relation == "depends_on" else "declared_overlap_rule"),
        "declared_timing_mode": relation.properties.get("timing_mode", "overlap"),
        "unsupported_gap_fields": [name for name in ("min_gap_ms", "max_gap_ms")
                                   if name in relation.properties],
        "minimum_overlap_ms": relation.properties.get("min_overlap_ms", 0),
        "effect_verified": False,
    }
