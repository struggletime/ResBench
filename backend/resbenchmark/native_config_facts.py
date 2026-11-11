"""Extract declared facts from XML only; never execute or interpret shell code."""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
import re
from typing import Any, Mapping
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


@dataclass(frozen=True)
class InjectionFacts:
    parameters: Mapping[str, str] = field(default_factory=dict)
    status: str = "unsupported_command"
    errors: tuple[str, ...] = ()


def injection_facts(injection: ET.Element) -> InjectionFacts:
    """Accept reviewed direct argv schemas, never shell code or arbitrary jars.

    Unknown options make the command unsupported instead of guessing their
    arity or meaning. Raw commands/arguments are never included in reports.
    """
    cmd = (injection.findtext("cmd") or "").strip()
    name = re.split(r"[/\\]", cmd)[-1]
    args = [(node.text or "").strip() for node in injection.findall("arg")]
    if name in {"java", "java.exe"}:
        return _dbchaos_facts(injection, args)
    if name in {"blade", "blade.exe"} and args[:2] != ["create", "network"]:
        return _blade_resource_facts(injection, args)
    operations = {
        "loss": {"percent"}, "delay": {"time", "offset"},
        "reorder": {"percent", "time", "correlation", "gap"},
        "duplicate": {"percent"}, "corrupt": {"percent"},
    }
    if (name not in {"blade", "blade.exe"} or len(args) < 3
            or args[:2] != ["create", "network"] or args[2] not in operations):
        return InjectionFacts()
    operation = args[2]
    if any(node.attrib or len(node) for node in injection.findall("arg")):
        return InjectionFacts(status="invalid_arguments", errors=("Injection arg must be plain text",))
    allowed = {"interface", "timeout"} | operations[operation]
    options: dict[str, str] = {}
    index = 3
    while index < len(args):
        token = args[index]
        if not token.startswith("--"):
            return InjectionFacts(status="invalid_arguments", errors=("Unexpected positional network argument",))
        option, separator, value = token[2:].partition("=")
        if option not in allowed | {"force"}:
            return InjectionFacts(status="unsupported_options")
        if option in options:
            return InjectionFacts(status="invalid_arguments", errors=("Repeated network option: " + option,))
        if not separator:
            if option == "force":
                value = "true"
            else:
                index += 1
                if index >= len(args) or args[index].startswith("--"):
                    return InjectionFacts(status="invalid_arguments", errors=("Missing value for " + option,))
                value = args[index]
        options[option] = value
        index += 1

    errors = []
    required = {"interface", "time" if operation == "delay" else "percent"}
    for key in sorted(required - options.keys()):
        errors.append("Missing network option: " + key)
    if "interface" in options and not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", options["interface"]):
        errors.append("Unsupported or invalid literal network interface")
    if "force" in options and options["force"] not in {"true", "false"}:
        errors.append("force must be true or false")
    names = {"interface": "network_interface", "timeout": "injector_timeout_s",
             "percent": "percent", "time": "delay_ms", "offset": "delay_offset_ms",
             "correlation": "correlation_percent", "gap": "gap", "force": "force_requested"}
    result = {"injector": "chaosblade", "operation": "network_" + operation}
    for key, value in options.items():
        if key in {"interface", "force"}:
            result[names[key]] = value
            continue
        try:
            if key in {"timeout", "time", "offset", "gap"}:
                if not re.fullmatch(r"[0-9]+", value) or int(value) > 2147483647:
                    raise ValueError()
                if key in {"time", "gap"} and int(value) == 0:
                    raise ValueError()
                result[names[key]] = str(int(value))
            else:
                number = Decimal(value)
                if not number.is_finite() or not 0 <= number <= 100:
                    raise ValueError()
                result[names[key]] = format(number, "f")
        except (ValueError, InvalidOperation):
            errors.append("Invalid numeric network option: " + key)
    return InjectionFacts(result, "recognized" if not errors else "invalid_arguments", tuple(errors))


def _literal_options(injection: ET.Element, args: list[str], allowed: set[str],
                     prefix: str) -> tuple[dict[str, str], InjectionFacts | None]:
    if any(node.attrib or len(node) for node in injection.findall("arg")):
        return {}, InjectionFacts(status="invalid_arguments", errors=("Injection arg must be plain text",))
    result: dict[str, str] = {}
    index = 0
    while index < len(args):
        token = args[index]
        if not token.startswith(prefix):
            return {}, InjectionFacts(status="invalid_arguments", errors=("Unexpected positional injector argument",))
        key, separator, value = token[len(prefix):].partition("=")
        if key not in allowed:
            return {}, InjectionFacts(status="unsupported_options")
        if key in result:
            return {}, InjectionFacts(status="invalid_arguments", errors=("Repeated injector option: " + key,))
        if not separator:
            index += 1
            if index >= len(args) or args[index].startswith(prefix):
                return {}, InjectionFacts(status="invalid_arguments", errors=("Missing value for " + key,))
            value = args[index]
        if not value:
            return {}, InjectionFacts(status="invalid_arguments", errors=("Empty value for " + key,))
        result[key] = value
        index += 1
    return result, None


def _unsigned(value: str, minimum: int = 0, maximum: int = 2147483647) -> int:
    if not re.fullmatch(r"[0-9]+", value):
        raise ValueError()
    number = int(value)
    if not minimum <= number <= maximum:
        raise ValueError()
    return number


def _percentage(value: str, maximum: int = 100) -> str:
    number = Decimal(value)
    if not number.is_finite() or not 0 <= number <= maximum:
        raise ValueError()
    return format(number, "f")


def _blade_resource_facts(injection: ET.Element, args: list[str]) -> InjectionFacts:
    schemas = {
        ("cpu", "fullload"): (set(), set()),
        ("mem", "load"): ({"mode", "mem-percent"}, {"mode", "mem-percent"}),
        ("file", "move"): ({"filepath", "target"}, {"filepath", "target"}),
        ("file", "chmod"): ({"filepath", "mark"}, {"filepath", "mark"}),
        ("disk", "fill"): ({"path", "percent"}, {"path", "percent"}),
        ("process", "stop"): ({"process-cmd"}, {"process-cmd"}),
        ("process", "kill"): ({"process-cmd", "signal"}, {"process-cmd", "signal"}),
    }
    if len(args) < 3 or args[0] != "create" or tuple(args[1:3]) not in schemas:
        return InjectionFacts()
    allowed, required = schemas[tuple(args[1:3])]
    options, failure = _literal_options(injection, args[3:], allowed | {"timeout"}, "--")
    if failure is not None:
        return failure
    errors = ["Missing injector option: " + key for key in sorted(required - options.keys())]
    result = {"injector": "chaosblade", "operation": "_".join(args[1:3])}
    for key, value in options.items():
        try:
            if key == "timeout":
                result["injector_timeout_s"] = str(_unsigned(value))
            elif key == "signal":
                result["signal"] = str(_unsigned(value, 1, 64))
            elif key in {"mem-percent", "percent"}:
                result["memory_percent" if key == "mem-percent" else "percent"] = _percentage(value)
            elif key == "mode":
                if value not in {"ram", "cache"}:
                    raise ValueError()
                result["memory_mode"] = value
            elif key == "mark":
                if not re.fullmatch(r"[0-7]{3,4}", value):
                    raise ValueError()
                result["permission_mark"] = value
            # Paths/selectors are not filesystem/process identity or proof of effect.
            # Leave them out of reports to avoid exposing arbitrary argument text.
        except (ValueError, InvalidOperation):
            errors.append("Invalid injector option: " + key)
    return InjectionFacts(result, "recognized" if not errors else "invalid_arguments", tuple(errors))


def _dbchaos_facts(injection: ET.Element, args: list[str]) -> InjectionFacts:
    # This schema is the exported DBChaos CLI, not generic java -jar syntax.
    if (len(args) < 6 or args[0] != "-jar"
            or re.split(r"[/\\]", args[1])[-1] != "DBChaos.jar"
            or args[2] != "--db" or args[3] not in {"mysql", "postgresql", "opengauss"}
            or tuple(args[4:6]) not in {("session", "max_connection"), ("log", "massive_rollback")}):
        return InjectionFacts()
    session = args[4] == "session"
    required = {"duration", "url", "user", "password"} | (
        {"mode", "count"} if session else {"threads", "rate"})
    options, failure = _literal_options(injection, args[6:], required, "-")
    if failure is not None:
        return failure
    errors = ["Missing DBChaos option: " + key for key in sorted(required - options.keys())]
    result = {"injector": "dbchaos", "operation": args[5], "database_engine": args[3]}
    for key in sorted(options.keys() - {"url", "user", "password"}):
        value = options[key]
        try:
            if key == "duration":
                milliseconds = _unsigned(value, 1, 2147483647 * 1000)
                if milliseconds % 1000:
                    raise ValueError()
                result["declared_duration_ms"] = str(milliseconds)
                result["injector_timeout_s"] = str(milliseconds // 1000)
            elif key in {"count", "threads"}:
                result[key] = str(_unsigned(value, 1))
            elif key == "rate":
                result["rollback_rate"] = _percentage(value, 1)
            elif key == "mode":
                if value not in {"conn_storm", "conn_exhaustion", "thread_saturation"}:
                    raise ValueError()
                result["mode"] = value
        except (ValueError, InvalidOperation):
            errors.append("Invalid DBChaos option: " + key)
    return InjectionFacts(result, "recognized" if not errors else "invalid_arguments", tuple(errors))


def database_endpoint(text: str) -> dict[str, Any] | None:
    """Report a declared JDBC endpoint without credentials or query parameters."""
    if not text.startswith(("jdbc:postgresql://", "jdbc:mysql://")):
        return None
    try:
        parsed = urlsplit(text[5:])
        if not parsed.hostname or not parsed.path.strip("/"):
            return None
        return {"engine": parsed.scheme, "host": parsed.hostname,
                "port": parsed.port, "database": parsed.path.lstrip("/")}
    except ValueError:
        return None


def workload_facts(root: ET.Element) -> dict[str, Any]:
    """Keep workload declarations separate from event-target scope facts."""
    result: dict[str, Any] = {}
    for tag in ("type", "isolation", "batchsize", "scalefactor", "terminals"):
        value = (root.findtext(tag) or "").strip()
        if value:
            result[tag] = value
    reconnect = (root.findtext("reconnectOnConnectionFailure") or "").strip().lower()
    if reconnect in {"true", "false"}:
        result["reconnect_on_failure_configured"] = reconnect == "true"
    endpoint = database_endpoint((root.findtext("url") or "").strip())
    if endpoint is not None:
        result["database_endpoint"] = endpoint
    nodes = [database_endpoint((node.text or "").strip()) for node in root.findall("nodes/node")]
    result["declared_database_nodes"] = [node for node in nodes if node is not None]
    return result
