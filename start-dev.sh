#!/usr/bin/env bash
set -euo pipefail

# All child JVM processes (including benchmark subprocess) inherit this heap
export JAVA_TOOL_OPTIONS="-Xmx2048M"

ROOT="$(cd "$(dirname "$0")" && pwd)"
LOGS_DIR="$ROOT/.codex/run-logs"
BACKEND_DIR="$ROOT/backend"
RESBENCHMARK_DIR="$BACKEND_DIR/resbenchmark"
RES_MANAGER_DIR="$BACKEND_DIR/res-manager"

mkdir -p "$LOGS_DIR"

log()   { echo "[$(date '+%H:%M:%S')] $*"; }
error() { echo "[$(date '+%H:%M:%S')] ERROR: $*" >&2; }

# 端口检测统一走这里：优先 lsof，其次 ss/fuser/nc（macOS 没有 ss，避免误判端口空闲）
port_in_use() {
    local port=$1
    if command -v lsof >/dev/null 2>&1; then
        lsof -i tcp:$port >/dev/null 2>&1
    elif command -v ss >/dev/null 2>&1; then
        ss -tlnp 2>/dev/null | grep -q ":$port "
    elif command -v fuser >/dev/null 2>&1; then
        fuser $port/tcp >/dev/null 2>&1
    else
        if command -v nc >/dev/null 2>&1; then
            nc -z 127.0.0.1 $port >/dev/null 2>&1
        else
            return 1
        fi
    fi
}

# 按端口杀真正持有端口的进程。
# 必须按端口杀而非只 kill 记录的 PID：spring-boot:run 会 fork 出独立 JVM 持有端口，
# Ctrl+C 时只杀 Maven 父进程会留下孤儿 JVM，之后重启永远撞 "Port already in use"。
kill_by_port() {
    local port=$1 pids=""
    if command -v lsof >/dev/null 2>&1; then
        pids=$(lsof -ti tcp:$port 2>/dev/null || true)
    elif command -v fuser >/dev/null 2>&1; then
        pids=$(fuser $port/tcp 2>/dev/null | tr -s ' ' | tr ' ' '\n' | grep -E '^[0-9]+$' || true)
    fi
    if [ -n "$pids" ]; then
        log "killing process(es) on port $port: $(echo "$pids" | tr '\n' ' ')"
        kill $pids 2>/dev/null || true
        sleep 1
        if port_in_use "$port"; then
            log "port $port still in use, sending SIGKILL"
            kill -9 $pids 2>/dev/null || true
        fi
    fi
}

wait_for_port() {
    local name=$1 port=$2 timeout=${3:-45}
    local deadline=$(($(date +%s) + timeout))
    while [[ $(date +%s) -lt $deadline ]]; do
        if port_in_use "$port"; then
            log "$name is ready on port $port"
            return 0
        fi
        sleep 1
    done
    error "$name failed to start on port $port within ${timeout}s"
    tail -30 "$LOGS_DIR/$name.out.log" 2>/dev/null || true
    tail -30 "$LOGS_DIR/$name.err.log" 2>/dev/null || true
    return 1
}

start_background() {
    local name=$1 workdir=$2 port=$3; shift 3
    if port_in_use "$port"; then
        log "$name already listening on port $port"
        log "WARNING: skipping start ($name). 如果这是残留的旧进程（新代码不生效），"
        log "         请先执行: lsof -ti tcp:$port | xargs kill  再重跑本脚本"
        return 0
    fi
    log "Starting $name on port $port"
    nohup "$@" > "$LOGS_DIR/$name.out.log" 2> "$LOGS_DIR/$name.err.log" &
    wait_for_port "$name" "$port"
}

cleanup() {
    log "Shutting down..."
    # 按端口杀，确保 fork 出来的 app JVM 也被清掉（否则 Ctrl+C 后它仍占着端口、旧代码继续服务）
    kill_by_port 8090
    kill_by_port 8080
    # 兜底：再杀记录的 Maven 包装进程
    kill "${RESBENCHMARK_PID:-}" "${RES_MANAGER_PID:-}" 2>/dev/null || true
    wait 2>/dev/null || true
    log "Done."
}
trap cleanup EXIT INT TERM

# ----  Step 1: Install common module  ----
log "Installing resbenchmark-common..."
cd "$BACKEND_DIR"
mvn -q install -pl resbenchmark-common -am -DskipTests -Dmaven.gitcommitid.skip=true

# ----  Step 2: Build resbenchmark + generate runtime classpath  ----
log "Building resbenchmark and generating runtime classpath..."
cd "$RESBENCHMARK_DIR"
mvn -q clean compile dependency:build-classpath \
    -Dmdep.outputFile=target/runtime-classpath.txt -Dclassifier= \
    -DskipTests -Dmaven.gitcommitid.skip=true \
    -P opengauss

# ----  Step 3: Start resbenchmark on port 8080  ----
start_background "resbenchmark-server" "$RESBENCHMARK_DIR" 8080 \
    mvn exec:java \
        -Dexec.mainClass=com.resbench.Application \
        -Dexec.args="server --server.port=8080" \
        -P opengauss
RESBENCHMARK_PID=$!

# ----  Step 4: Build and start res-manager on port 8090  ----
log "Building res-manager..."
cd "$RES_MANAGER_DIR"
mvn -q clean compile -DskipTests

start_background "res-manager" "$RES_MANAGER_DIR" 8090 \
    mvn spring-boot:run
RES_MANAGER_PID=$!

# ----  Done  ----
log "============================================"
log "  resbenchmark:  http://127.0.0.1:8080"
log "  res-manager:   http://127.0.0.1:8090"
log "============================================"
log "Press Ctrl+C to stop all services."
wait
