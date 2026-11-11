#!/usr/bin/env bash
# ============================================================================
# 启动 ResBench 自带的 Neo4j，并在首次启动后导入知识图谱。
#
# 幂等：已在运行则直接检查导入；导入脚本本身也会检测图谱是否已存在。
# 环境变量与 install-neo4j.sh 相同。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NEO4J_HOME_DIR="${RESBENCH_NEO4J_HOME:-$ROOT/neo4j}"
NEO4J_CURRENT="$NEO4J_HOME_DIR/current"
NEO4J_INSTALL_DIR="$(readlink -f "$NEO4J_CURRENT" 2>/dev/null || true)"
PASSWORD_FILE="${RESBENCH_NEO4J_PASSWORD_FILE:-$NEO4J_HOME_DIR/.password}"

log()  { printf '[neo4j] %s\n' "$*"; }
warn() { printf '[neo4j] WARNING: %s\n' "$*" >&2; }
die()  { printf '[neo4j] ERROR: %s\n' "$*" >&2; exit 1; }

[ -n "$NEO4J_INSTALL_DIR" ] && [ -x "$NEO4J_INSTALL_DIR/bin/neo4j" ] \
  || die "未安装 Neo4j。请先运行：bash $ROOT/scripts/neo4j/install-neo4j.sh"

# Neo4j 启动脚本需要 Java 21+。优先使用显式配置的 Java。
if [ -n "${NEO4J_JAVA_HOME:-}" ]; then
  export JAVA_HOME="$NEO4J_JAVA_HOME"
elif [ -z "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$(command -v java 2>/dev/null || true)"
  [ -n "$JAVA_BIN" ] || die "未找到 Java，请设置 JAVA_HOME"
  export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
fi

if "$NEO4J_INSTALL_DIR/bin/neo4j" status >/dev/null 2>&1; then
  log "Neo4j 已在运行"
else
  log "启动 Neo4j"
  "$NEO4J_INSTALL_DIR/bin/neo4j" start >/dev/null
fi

# 等待 HTTP 连接器就绪（Bolt 也会随之可用）。
log "等待 Neo4j 就绪（127.0.0.1:7474）"
ready=0
for _ in $(seq 1 120); do
  if curl -fsS -o /dev/null --max-time 2 http://127.0.0.1:7474 2>/dev/null; then
    ready=1
    break
  fi
  sleep 1
done
[ "$ready" -eq 1 ] || die "Neo4j 在 120s 内没有就绪，见 $NEO4J_HOME_DIR/logs"

# HTTP 就绪不代表数据库已经可查询；再用 cypher-shell 探一次 Bolt。
if [ -s "$PASSWORD_FILE" ] && [ -x "$NEO4J_INSTALL_DIR/bin/cypher-shell" ]; then
  bolt_ready=0
  for _ in $(seq 1 60); do
    if "$NEO4J_INSTALL_DIR/bin/cypher-shell" -a bolt://127.0.0.1:7687 -u neo4j \
        -p "$(cat "$PASSWORD_FILE")" -d neo4j "RETURN 1;" >/dev/null 2>&1; then
      bolt_ready=1
      break
    fi
    sleep 1
  done
  [ "$bolt_ready" -eq 1 ] || die "Neo4j Bolt 在 60s 内不可查询，见 $NEO4J_HOME_DIR/logs"
fi

log "Neo4j 已就绪：bolt://127.0.0.1:7687"

# 首次启动后导入知识图谱；失败不阻断 Engine 启动，但必须留下明确告警。
if ! bash "$ROOT/scripts/neo4j/import-graph.sh"; then
  warn "知识图谱导入失败，故障编排校验会返回 checked=false"
  exit 0
fi

log "启动完成"
