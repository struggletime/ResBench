#!/usr/bin/env bash
# ============================================================================
# 四组矩阵总入口：PostgreSQL / MySQL × TPC-C / smallbank
#
# 每组 16 轮：**一轮只跑一个故障、这个故障在同一轮里连打 3 次**（3 个完全相同的
# batch，每批 60s + 批间 30s；第 16 轮 process.kill 批间 90s）。16 个故障的组成与顺序
# 见 matrix-*.yaml：
#   cpu.load 1 + memory.load 1 + 网络 5（loss/delay/reorder/duplicate/corrupt）
#   + 磁盘文件 3（disk.fill / filemove / filechmod，不跑 delete、不跑 append）
#   + 进程 2（process.stop 挂起：SIGSTOP，process-cmd 默认现场探测，见
#     scripts/resbench_batch.py --auto-process-target；process.kill 杀死：SIGKILL，
#     容器 PID 1 被杀后容器退出，恢复靠引擎到点的 docker start，放每组最后一轮）
#   + 数据库层 4（连接风暴 / 连接数耗尽 / 大量事务回滚 / 连接线程饱和）
# 单轮时刻：第 1 批 +60s 注入，3 批计划终点 300s，负载 320s（含 20s 尾巴）；
# 第 16 轮计划终点 540s、负载 560s。
# 一组 16 轮 ≈ 5360s 负载（90 分钟：前 15 轮 4800s + 第 16 轮 560s）；
# 4 组全部跑完约 6~8 小时
# （smallbank 每轮都要重新建表灌数，另加十几分钟到半小时）。
#
# 用法：
#   scripts/matrix-4groups/run-all.sh --dry-run     # 只打印 4 条计划，不提交
#   scripts/matrix-4groups/run-all.sh               # 正式跑
#   RUN_PG=0 / RUN_MYSQL=0 scripts/matrix-4groups/run-all.sh   # 只跑一侧
#
# 结果目录：batch-results/4groups/<时间戳>_<库key>_<负载key>_<编排名>/
# 16 轮各一个子目录，每轮有 <run>.json / <run>.faults.json / <run>.monitor.json，
# 以及汇总的 runs-<ts>.csv。
#
# 约束（来自这次测试的要求）：
#   * 不碰引擎上别人的任务：脚本撞上「执行中」会等，等不到就停本批次；
#   * 不调用 POST /web/bench/stop；
#   * 库口令只写在 databases.yaml 里，本脚本不打印。
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

ENGINE="${ENGINE:-http://127.0.0.1:8080}"
MANAGER="${MANAGER:-http://127.0.0.1:8090}"
OUT="${OUT:-batch-results/4groups}"
COOLDOWN="${COOLDOWN:-60}"

# 进程挂起挂谁：默认现场探测（按数据库端口找监听进程 → 取 argv[0] → 核对命中范围）。
# 想用手写在编排里的 process-cmd，就 PROCESS_TARGET= scripts/matrix-4groups/run-all.sh
PROCESS_TARGET="${PROCESS_TARGET-AUTO}"
if [ "$PROCESS_TARGET" = "AUTO" ]; then PROCESS_TARGET="--auto-process-target"; fi

# 32 条指标（res-manager 上限 32）。16 个故障的判据覆盖：
#   cpu / mem      → container.cpu.cores / container.memory.usage（PG 容器）
#                    host.cpu.usage / host.memory.usage（MySQL 宿主机）
#   网络 5 个      → net.tcp.rto.rate / retrans.ratio / oo.rate / dsack.rate
#                    + host.netstat.retrans（loss/corrupt 看 rto，reorder 看 oo，
#                    duplicate 看 dsack，delay 看 TPS 间接信号）
#   disk.fill      → host.disk.usage（按 mountpoint 区分 /boot 与数据盘）
#   filemove/chmod → db.slow_sql.max / db.tps.agent / ebpf.disk.*
#   process.stop   → host.process.stopped（0 → 正数）+ db.tps.agent 归零
#   process.kill   → container.cpu.usage / db.tps.agent 在同一窗口归零、docker start 后回升
#                    （EFFECT_RULES 暂无 process.kill 规则，监控判定会是 NO_DATA，先看曲线）
#   数据库层 4 个  → db.connections.usage / db.rollback.rate / db.lock.wait.max /
#                    db.blocked.sessions / db.sessions
METRICS="container.cpu.cores,container.cpu.usage,container.memory.usage"
METRICS="$METRICS,container.disk.read,container.disk.write"
METRICS="$METRICS,host.cpu.usage,host.memory.usage,host.load.ratio,host.disk.read,host.disk.write"
METRICS="$METRICS,host.disk.usage,host.pressure.io,host.pressure.cpu,host.process.stopped,host.netstat.retrans"
METRICS="$METRICS,net.tcp.rto.rate,net.tcp.retrans.ratio,net.tcp.oo.rate,net.tcp.dsack.rate"
METRICS="$METRICS,db.tps.agent,db.connections.usage,db.connections.open_rate,db.sessions,db.threads.running"
METRICS="$METRICS,db.blocked.sessions,db.lock.wait.max,db.slow_sql.max,db.cache.hit,db.rollback.rate"
METRICS="$METRICS,ebpf.disk.avg,ebpf.disk.p99,ebpf.runq.avg"

EXECUTE="--execute"
for arg in "$@"; do
  case "$arg" in
    --dry-run) EXECUTE="" ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（只支持 --dry-run）" >&2; exit 2 ;;
  esac
done

RUN_PG="${RUN_PG:-1}"
RUN_MYSQL="${RUN_MYSQL:-1}"

mkdir -p "$OUT/logs"
STARTED_AT=$(date +%Y%m%d_%H%M%S)
LOG="$OUT/logs/run-all-$STARTED_AT.log"
FAILED=()

log() { printf '%s\n' "$*" | tee -a "$LOG"; }

run_one() {
  local name="$1"; shift
  log ""
  log "=============================================================="
  log "▶ $name"
  log "  $*"
  log "=============================================================="
  if "$@" 2>&1 | tee -a "$LOG"; then
    log "✔ $name 完成"
    return 0
  fi
  log "✘ $name 失败（rc=${PIPESTATUS[0]}），继续下一轮；最后统一汇总"
  FAILED+=("$name")
  return 1
}

batch() {  # batch <db-file> <workload-file> <orchestration> [额外参数...]
  local dbfile="$1" wlfile="$2" orch="$3"; shift 3
  python3 scripts/resbench_batch.py \
    --engine "$ENGINE" --manager "$MANAGER" \
    --databases "$dbfile" --workloads "$wlfile" --orchestration "$orch" \
    --out "$OUT" --monitor-metrics "$METRICS" \
    $EXECUTE $PROCESS_TARGET "$@"
}

DB=scripts/matrix-4groups/databases.yaml
WL_PG_TPCC=scripts/matrix-4groups/workload-pg-tpcc.yaml
WL_MYSQL_TPCC=scripts/matrix-4groups/workload-mysql-tpcc.yaml
WL_SMALLBANK=scripts/matrix-4groups/workload-smallbank.yaml

preflight() {
  log "引擎  $ENGINE"
  log "管理端 $MANAGER"
  log "结果  $OUT"
  log "模式  ${EXECUTE:-（dry-run，不提交）}"
  log "指标  $METRICS"
  if [ -n "$EXECUTE" ]; then
    if ! curl -sf --max-time 5 "$ENGINE/web/bench/status" >/dev/null; then
      log "✘ 引擎不可达：$ENGINE/web/bench/status"; exit 3
    fi
    if ! curl -sf --max-time 5 "$MANAGER/monitoring/catalog" >/dev/null; then
      # 目录接口只是探活，取不到也不拦（有的部署把前缀改了）
      log "! 管理端探活未通过：$MANAGER/monitoring/catalog（监控会话可能建不起来，继续）"
    fi
  fi
}

preflight

if [ "$RUN_PG" = "1" ]; then
  # PG 的文件类故障路径走 @auto:：smallbank 每轮重建表，relfilenode 会变，只能开跑时现查。
  run_one "1/4 pg × tpcc（16 轮，每轮同一个故障 ×3）" \
    batch "$DB" "$WL_PG_TPCC" scripts/matrix-4groups/matrix-pg-tpcc.yaml \
      --only-db pg --auto-file-targets
  sleep "$COOLDOWN"
  run_one "2/4 pg × smallbank（16 轮，每轮同一个故障 ×3）" \
    batch "$DB" "$WL_SMALLBANK" scripts/matrix-4groups/matrix-pg-smallbank.yaml \
      --only-db pg --auto-file-targets
  sleep "$COOLDOWN"
fi

if [ "$RUN_MYSQL" = "1" ]; then
  # MySQL 入口不带 container：3306 是宿主机 mysqld.service，文件路径与 process-cmd
  # 都是宿主机口径，所以**不加** --auto-file-targets（@auto 映射对 MySQL 会把库目录
  # 搬进它自己的父目录）。
  run_one "3/4 mysql × tpcc（16 轮，每轮同一个故障 ×3）" \
    batch "$DB" "$WL_MYSQL_TPCC" scripts/matrix-4groups/matrix-mysql-tpcc.yaml \
      --only-db mysql
  sleep "$COOLDOWN"
  run_one "4/4 mysql × smallbank（16 轮，每轮同一个故障 ×3）" \
    batch "$DB" "$WL_SMALLBANK" scripts/matrix-4groups/matrix-mysql-smallbank.yaml \
      --only-db mysql
fi

log ""
log "=============================================================="
if [ ${#FAILED[@]} -eq 0 ]; then
  log "全部完成。结果在 $OUT/，日志 $LOG"
else
  log "完成，但有 ${#FAILED[@]} 轮失败："
  for f in "${FAILED[@]}"; do log "  ✘ $f"; done
  log "日志：$LOG"
  exit 1
fi
