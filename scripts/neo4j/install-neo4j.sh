#!/usr/bin/env bash
# ============================================================================
# 安装 ResBench 自带的 Neo4j Community（官方 tar 包）。
#
# 用途：故障编排合理性校验（kg_orchestration.py）需要从 Neo4j 读取知识图谱。
# 幂等：已解压则直接复用；只在首次安装时下载、生成密码、设置初始密码。
#
# 目录布局（默认在仓库/部署根目录下）：
#   neo4j/neo4j-community-2026.09.0/   Neo4j 安装目录
#   neo4j/current -> neo4j-community-2026.09.0
#   neo4j/data/                        数据目录（升级版本时保留）
#   neo4j/logs/                        日志目录
#   neo4j/.password                   生成的初始密码（600）
#
# 可用环境变量覆盖：
#   RESBENCH_NEO4J_VERSION   版本，默认 2026.09.0（与图谱导出源一致）
#   RESBENCH_NEO4J_HOME      安装根目录，默认 <root>/neo4j
#   RESBENCH_NEO4J_URL       tar 包地址
#   RESBENCH_NEO4J_ARCHIVE   本地 tar 包路径（离线安装/目标机下载被地域限制时用）
#   RESBENCH_NEO4J_SHA256    tar 包 SHA256
#   RESBENCH_NEO4J_PASSWORD_FILE  密码文件路径
#   JAVA_HOME / NEO4J_JAVA_HOME    Java 21+ 的安装目录
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NEO4J_VERSION="${RESBENCH_NEO4J_VERSION:-2026.09.0}"
NEO4J_DIST="neo4j-community-${NEO4J_VERSION}"
NEO4J_HOME_DIR="${RESBENCH_NEO4J_HOME:-$ROOT/neo4j}"
NEO4J_INSTALL_DIR="$NEO4J_HOME_DIR/$NEO4J_DIST"
NEO4J_CURRENT="$NEO4J_HOME_DIR/current"
DATA_DIR="$NEO4J_HOME_DIR/data"
LOGS_DIR="$NEO4J_HOME_DIR/logs"
PASSWORD_FILE="${RESBENCH_NEO4J_PASSWORD_FILE:-$NEO4J_HOME_DIR/.password}"
ARCHIVE_URL="${RESBENCH_NEO4J_URL:-https://dist.neo4j.org/${NEO4J_DIST}-unix.tar.gz}"
ARCHIVE_FILE="${RESBENCH_NEO4J_ARCHIVE:-}"
# https://dist.neo4j.org/neo4j-community-2026.09.0-unix.tar.gz.sha256
ARCHIVE_SHA256="${RESBENCH_NEO4J_SHA256:-cdaa0905d9a533bbbc474d239c268f69978982eb5ca3177666c04c9792d1c399}"

log()  { printf '[neo4j] %s\n' "$*"; }
warn() { printf '[neo4j] WARNING: %s\n' "$*" >&2; }
die()  { printf '[neo4j] ERROR: %s\n' "$*" >&2; exit 1; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

java_bin() {
  if [ -n "${NEO4J_JAVA_HOME:-}" ] && [ -x "$NEO4J_JAVA_HOME/bin/java" ]; then
    printf '%s\n' "$NEO4J_JAVA_HOME/bin/java"
    return 0
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    printf '%s\n' "$JAVA_HOME/bin/java"
    return 0
  fi
  command -v java 2>/dev/null || true
}

java_major() {
  "$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' \
    | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

# ---- 1. 前置检查 ----------------------------------------------------------
need_cmd tar
need_cmd sha256sum
if [ -z "$ARCHIVE_FILE" ]; then
  need_cmd curl
elif [ ! -r "$ARCHIVE_FILE" ]; then
  die "RESBENCH_NEO4J_ARCHIVE 指定的文件不可读：$ARCHIVE_FILE"
fi

JAVA_BIN="$(java_bin)"
[ -n "$JAVA_BIN" ] || die "未找到 Java。Neo4j ${NEO4J_VERSION} 需要 Java 21+，请设置 JAVA_HOME。"
JAVA_MAJOR="$(java_major "$JAVA_BIN")"
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || [ "$JAVA_MAJOR" -lt 21 ]; then
  die "Java 版本过低（$("$JAVA_BIN" -version 2>&1 | head -1)）。Neo4j ${NEO4J_VERSION} 需要 Java 21+。"
fi
log "Java: $JAVA_BIN (major $JAVA_MAJOR)"

mkdir -p "$NEO4J_HOME_DIR" "$DATA_DIR" "$LOGS_DIR"

# ---- 2. 下载并解压（已存在则跳过） ---------------------------------------
if [ ! -x "$NEO4J_INSTALL_DIR/bin/neo4j" ]; then
  TMP_DIR="$(mktemp -d "$NEO4J_HOME_DIR/.download.XXXXXX")"
  trap 'rm -rf "$TMP_DIR"' EXIT
  ARCHIVE="$TMP_DIR/neo4j.tar.gz"
  if [ -n "$ARCHIVE_FILE" ]; then
    log "使用本地 tar 包 $ARCHIVE_FILE"
    cp "$ARCHIVE_FILE" "$ARCHIVE" || die "复制本地 tar 包失败：$ARCHIVE_FILE"
  else
    log "下载 $ARCHIVE_URL"
    curl -fL --retry 3 --connect-timeout 15 -o "$ARCHIVE" "$ARCHIVE_URL" \
      || die "下载 Neo4j 失败：$ARCHIVE_URL"
  fi

  log "校验 SHA256"
  printf '%s  %s\n' "$ARCHIVE_SHA256" "$ARCHIVE" | sha256sum -c - >/dev/null \
    || die "Neo4j tar 包 SHA256 不匹配，拒绝解压"

  log "解压到 $NEO4J_HOME_DIR"
  tar -xzf "$ARCHIVE" -C "$TMP_DIR" || die "解压 Neo4j 失败"
  [ -d "$TMP_DIR/$NEO4J_DIST" ] || die "tar 包结构异常：未找到 $NEO4J_DIST"
  rm -rf "$NEO4J_INSTALL_DIR"
  mv "$TMP_DIR/$NEO4J_DIST" "$NEO4J_INSTALL_DIR"
  rm -rf "$TMP_DIR"
  trap - EXIT
else
  log "已安装：$NEO4J_INSTALL_DIR"
fi

ln -sfn "$NEO4J_INSTALL_DIR" "$NEO4J_CURRENT"

# ---- 3. 写 neo4j.conf（只追加一次受管块） --------------------------------
CONF="$NEO4J_INSTALL_DIR/conf/neo4j.conf"
MARKER="# === ResBench managed block (do not edit) ==="
if ! grep -qF "$MARKER" "$CONF" 2>/dev/null; then
  log "写入 neo4j.conf"
  cat >> "$CONF" <<EOF

$MARKER
server.default_listen_address=127.0.0.1
server.bolt.listen_address=127.0.0.1:7687
server.http.listen_address=127.0.0.1:7474
server.directories.data=$DATA_DIR
server.directories.logs=$LOGS_DIR
server.memory.heap.initial_size=512m
server.memory.heap.max_size=1g
server.memory.pagecache.size=512m
# === end ResBench managed block ===
EOF
fi

# ---- 4. 生成初始密码（只生成一次） --------------------------------------
mkdir -p "$(dirname "$PASSWORD_FILE")"
if [ ! -s "$PASSWORD_FILE" ]; then
  log "生成 Neo4j 初始密码：$PASSWORD_FILE"
  PASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 || true)"
  [ "${#PASS}" -eq 32 ] || die "生成随机密码失败"
  ( umask 077; printf '%s' "$PASS" > "$PASSWORD_FILE" )
fi
chmod 600 "$PASSWORD_FILE"

# ---- 5. 首次安装时设置初始密码 ------------------------------------------
if [ ! -f "$DATA_DIR/dbms/auth" ]; then
  log "设置 Neo4j 初始密码"
  PASSPHRASE="$(cat "$PASSWORD_FILE")"
  if ! JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$JAVA_BIN")")}" \
      "$NEO4J_INSTALL_DIR/bin/neo4j-admin" dbms set-initial-password "$PASSPHRASE" >/dev/null 2>&1; then
    [ -f "$DATA_DIR/dbms/auth" ] || die "设置 Neo4j 初始密码失败（见 $LOGS_DIR）"
  fi
fi

# ---- 6. Python driver 检查（只提示，不自动安装） ------------------------
PY_BIN="${RESBENCH_PYTHON:-python3}"
if command -v "$PY_BIN" >/dev/null 2>&1; then
  if ! "$PY_BIN" -c 'import neo4j' >/dev/null 2>&1; then
    warn "Python 解释器 $PY_BIN 缺少 neo4j 包；编排校验会失败。"
    warn "请执行：$PY_BIN -m pip install -r $ROOT/scripts/neo4j/requirements.txt"
  fi
else
  warn "未找到 Python 解释器 $PY_BIN；编排校验会失败。"
fi

log "安装完成"
log "  NEO4J_HOME : $NEO4J_INSTALL_DIR"
log "  current    : $NEO4J_CURRENT"
log "  data       : $DATA_DIR"
log "  password   : $PASSWORD_FILE"
log "下一步：bash $ROOT/scripts/neo4j/start-neo4j.sh"
