#!/usr/bin/env bash
# ============================================================================
# 将 ResBench 知识图谱导入本地 Neo4j。
#
# 幂等：图谱已完整（17 个 AdverseEvent / 41 条 RELATES_TO）时直接跳过。
# 导入文件使用 CREATE，不能对已有数据的库重复执行；脚本会先检查节点数。
#
# 环境变量：
#   RESBENCH_KG_IMPORT_FILE    import.cypher 路径
#                             默认 <root>/scripts/neo4j/graph/import.cypher
#   RESBENCH_KG_IMPORT_SKIP_HASH=1  跳过 SHA256 校验（替换图谱时使用）
# 其它变量同 install-neo4j.sh。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NEO4J_HOME_DIR="${RESBENCH_NEO4J_HOME:-$ROOT/neo4j}"
NEO4J_CURRENT="$NEO4J_HOME_DIR/current"
NEO4J_INSTALL_DIR="$(readlink -f "$NEO4J_CURRENT" 2>/dev/null || true)"
PASSWORD_FILE="${RESBENCH_NEO4J_PASSWORD_FILE:-$NEO4J_HOME_DIR/.password}"
GRAPH_FILE="${RESBENCH_KG_IMPORT_FILE:-$ROOT/scripts/neo4j/graph/import.cypher}"
NEO4J_URI="${RESBENCH_NEO4J_URI:-bolt://127.0.0.1:7687}"
NEO4J_USER="${RESBENCH_NEO4J_USERNAME:-neo4j}"
NEO4J_DB="${RESBENCH_NEO4J_DATABASE:-neo4j}"
EXPECTED_NODES=17
EXPECTED_RELATIONS=41

log()  { printf '[neo4j] %s\n' "$*"; }
warn() { printf '[neo4j] WARNING: %s\n' "$*" >&2; }
die()  { printf '[neo4j] ERROR: %s\n' "$*" >&2; exit 1; }

[ -n "$NEO4J_INSTALL_DIR" ] && [ -x "$NEO4J_INSTALL_DIR/bin/cypher-shell" ] \
  || die "Neo4j 未安装。请先运行：bash $ROOT/scripts/neo4j/install-neo4j.sh"
[ -s "$PASSWORD_FILE" ] || die "缺少 Neo4j 密码文件：$PASSWORD_FILE"
[ -f "$GRAPH_FILE" ] || die "缺少图谱文件：$GRAPH_FILE"

# 校验图谱文件完整性。
if [ "${RESBENCH_KG_IMPORT_SKIP_HASH:-0}" != "1" ]; then
  SHA_FILE="$GRAPH_FILE.sha256"
  if [ -f "$SHA_FILE" ]; then
    expected="$(awk '{print $1}' "$SHA_FILE" | head -1)"
    actual="$(sha256sum "$GRAPH_FILE" | awk '{print $1}')"
    [ "$expected" = "$actual" ] \
      || die "图谱文件 SHA256 不匹配：期望 $expected，实际 $actual"
  else
    warn "未找到 $SHA_FILE，跳过完整性校验"
  fi
fi

if [ -n "${NEO4J_JAVA_HOME:-}" ]; then
  export JAVA_HOME="$NEO4J_JAVA_HOME"
elif [ -z "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$(command -v java 2>/dev/null || true)"
  [ -n "$JAVA_BIN" ] && export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
fi

CS="$NEO4J_INSTALL_DIR/bin/cypher-shell"
PASS="$(cat "$PASSWORD_FILE")"

count_query() {
  local query="$1"
  "$CS" -a "$NEO4J_URI" -u "$NEO4J_USER" -p "$PASS" -d "$NEO4J_DB" \
    --format plain "$query" 2>&1 | tail -n 1 | tr -d '[:space:]'
}

count_nodes() {
  count_query "MATCH (n:AdverseEvent) RETURN count(n) AS c;"
}

count_relations() {
  count_query "MATCH ()-[r:RELATES_TO]->() RETURN count(r) AS c;"
}

NODE_COUNT="$(count_nodes)"
if ! [[ "$NODE_COUNT" =~ ^[0-9]+$ ]]; then
  die "无法连接 Neo4j 或查询失败：$NODE_COUNT"
fi

if [ "$NODE_COUNT" -eq "$EXPECTED_NODES" ]; then
  REL_COUNT="$(count_relations)"
  if [[ "$REL_COUNT" =~ ^[0-9]+$ ]] && [ "$REL_COUNT" -eq "$EXPECTED_RELATIONS" ]; then
    log "知识图谱已存在（AdverseEvent=$NODE_COUNT, RELATES_TO=$REL_COUNT），跳过导入"
    exit 0
  fi
  die "图谱不完整（AdverseEvent=$NODE_COUNT, RELATES_TO=$REL_COUNT）。请清空 $NEO4J_HOME_DIR/data 后重试。"
fi

if [ "$NODE_COUNT" -ne 0 ]; then
  die "目标库已有 $NODE_COUNT 个 AdverseEvent（期望 0 或 $EXPECTED_NODES）。为避免重复数据，拒绝导入；请清空 $NEO4J_HOME_DIR/data 后重试。"
fi

log "导入知识图谱：$GRAPH_FILE"
"$CS" -a "$NEO4J_URI" -u "$NEO4J_USER" -p "$PASS" -d "$NEO4J_DB" \
  --fail-fast -f "$GRAPH_FILE" || die "cypher-shell 导入失败"

NODE_COUNT="$(count_nodes)"
REL_COUNT="$(count_relations)"
if [ "$NODE_COUNT" != "$EXPECTED_NODES" ] || [ "$REL_COUNT" != "$EXPECTED_RELATIONS" ]; then
  die "导入后计数不符：AdverseEvent=$NODE_COUNT（期望 $EXPECTED_NODES），RELATES_TO=$REL_COUNT（期望 $EXPECTED_RELATIONS）"
fi

log "导入完成：AdverseEvent=$NODE_COUNT, RELATES_TO=$REL_COUNT"
