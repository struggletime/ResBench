#!/usr/bin/env bash
# 停止 ResBench 自带的 Neo4j。幂等：未安装或未运行都返回 0。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NEO4J_HOME_DIR="${RESBENCH_NEO4J_HOME:-$ROOT/neo4j}"
NEO4J_CURRENT="$NEO4J_HOME_DIR/current"
NEO4J_INSTALL_DIR="$(readlink -f "$NEO4J_CURRENT" 2>/dev/null || true)"

log() { printf '[neo4j] %s\n' "$*"; }

if [ -z "$NEO4J_INSTALL_DIR" ] || [ ! -x "$NEO4J_INSTALL_DIR/bin/neo4j" ]; then
  log "未安装，跳过"
  exit 0
fi

if [ -n "${NEO4J_JAVA_HOME:-}" ]; then
  export JAVA_HOME="$NEO4J_JAVA_HOME"
elif [ -z "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$(command -v java 2>/dev/null || true)"
  if [ -n "$JAVA_BIN" ]; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
  fi
fi

if "$NEO4J_INSTALL_DIR/bin/neo4j" status >/dev/null 2>&1; then
  log "停止 Neo4j"
  "$NEO4J_INSTALL_DIR/bin/neo4j" stop >/dev/null || true
else
  log "Neo4j 未运行"
fi
