#!/usr/bin/env bash
# ============================================================================
# 四组补测：只跑「实验数据里缺的轮次」和「监控判据缺的轮次」
#
#   1/4 pg × tpcc          无（跳过）
#   2/4 pg × smallbank     无（跳过）
#   3/4 mysql × tpcc       无（跳过；16-process-kill 重测已于 20261105_232025 归档）
#   4/4 mysql × smallbank  16-process-kill 补测（2026-11-06 按要求加回）
#
# 2026-11-05：mysql 两组的 16-process-kill 已重测并归档 —— tpcc 20261105_232025
# （3/3 回执 code:200，RestartCount 28→31，outage 18/13/13 s）、smallbank 20261105_234733
# （3/3，RestartCount 33→36，outage 7/4/7 s）。上一轮（19:19 / 19:31）3/3 失败，根因是
# process-cmd 写成 /usr/sbin/mysqld —— 那是宿主机 systemd 的命令行，故障经
# docker exec mysql8 在容器 PID 命名空间里跑，容器内 mysqld 的 cmdline 是裸 mysqld，
# blade 报 47001 process not found；改成 mysqld 后正常。smallbank 23:32 的首轮重测
# 第 1 批 agent transport 超时且窗口零效果，按「任一批失败整轮剔除」弃用。
# 四组 16 项至此全部有归档轮次。MySQL×SmallBank 08 于 20261105_171859（90s 批间）、
# 09 于 20261105_173157；MySQL×TPC-C 08 的 90s 批间重测于 20261105_205723 收录，
# 替换了 20261104_205818。pg 两组 process.kill 各跑的一轮成功数据（20261105_185414 /
# 20261105_190641）已归档到 实验数据/pg-tpcc 与 实验数据/pg-smallbank。
# 已经归档的轮次不再重跑（详见各 yaml 头部与 docs/fault-15-table-archived.md）。
#
# 维护约定（每次收录后必须同步）：某轮一旦拷进 实验数据/<组>/ 完成归档，就立刻
# 把它从对应 yaml 里删掉，避免下次补测又跑一遍同参数的轮次。
# disk fill 四组统一：percent=100、path 写宿主机 bind mount、duration=120s；
# 走宿主 blade，不套 docker exec —— 容器被拖死也能按 timeout 删掉填充文件。
# chmod 的 mark 必须是三位 "000"：chaosblade 不接受 "0"（illegal 'mark' parameter）。
# 自检已改成按八进制数值比较（两边去前导 0），所以 stat 输出的 "0" 能对上 "000"。
#
# 每个 yaml 一轮只打一个故障、同一故障连打 3 次（60s/批，批间 30s；16-process-kill
# 例外：批间 90s，保证容器重启后库能重新接受连接），与主矩阵同构。
# file move 永远放在一组最后一轮；本脚本遇到任何一组失败**立即停下**，不继续跑下一组
# ——移动恢复失败时继续跑只会把后面的库一起带崩。
#
# 注意：均**不传** --auto-file-targets。PG 的路径在 yaml 里直接写成 @auto: 哨兵
# （hottest-file / pgdata / db-dir），引擎在开跑时解析；传到脚本反而会把 src-path
# 强行改回 db-dir 目录，而目录移不动（chaosblade file move 只对文件生效）。
# MySQL 的路径写死为容器内路径。
#
# 用法：
#   scripts/matrix-4groups/run-backfill-4.sh --dry-run   # 只打印 4 条计划
#   scripts/matrix-4groups/run-backfill-4.sh             # 正式跑
#   COOLDOWN=120 scripts/matrix-4groups/run-backfill-4.sh
#
# 结果：batch-results/backfill/<时间戳>_<库key>_<负载key>_<编排名>/，
#       日志：batch-results/backfill/logs/backfill-<时间戳>.log
# ============================================================================
set -u
cd "$(dirname "$0")/../.." || exit 1

ENGINE="${ENGINE:-http://127.0.0.1:8080}"
OUT="${OUT:-batch-results/backfill}"
COOLDOWN="${COOLDOWN:-60}"

# 与 run-nightly-4.sh 完全相同的 15 条指标（归档的 monitoring/series.json 就是这些）
METRICS="container.cpu.usage,container.memory.usage,net.qdisc.drop.percent,net.ping.loss,net.ping.rtt,net.qdisc.reorder.rate,net.tcp.dsack.rate,net.tcp.rto.rate,net.tcp.lost_retrans.rate,host.disk.usage,host.process.stopped,db.connections.usage,db.connections.open_rate,db.sessions,db.rollback.rate"

EXECUTE="--execute"
for arg in "$@"; do
  case "$arg" in
    --dry-run) EXECUTE="" ;;
    -h|--help) awk 'NR==1{next} /^set -u$/{exit} {print}' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（只支持 --dry-run）" >&2; exit 2 ;;
  esac
done

DB=scripts/matrix-4groups/databases.yaml
WL_TPCC=scripts/matrix-4groups/workload-tpcc.yaml
WL_SB=scripts/matrix-4groups/workload-smallbank.yaml
Y_PG_TPCC=scripts/matrix-4groups/matrix-pg-tpcc-backfill.yaml
Y_PG_SB=scripts/matrix-4groups/matrix-pg-smallbank-backfill.yaml
Y_MY_TPCC=scripts/matrix-4groups/matrix-mysql-tpcc-backfill.yaml
Y_MY_SB=scripts/matrix-4groups/matrix-mysql-smallbank-backfill.yaml

# 编排文件里还有 `- key:` 轮次才提交；空编排（全部已归档）直接跳过。
has_rounds() { grep -qE '^[[:space:]]*-[[:space:]]*key:' "$1"; }

mkdir -p "$OUT/logs"
STARTED_AT=$(date +%Y%m%d_%H%M%S)
LOG="$OUT/logs/backfill-$STARTED_AT.log"

log() { printf '%s\n' "$*" | tee -a "$LOG"; }

run_group() {  # run_group <标题> <参数...>
  local title="$1"; shift
  log ""
  log "=============================================================="
  log "▶ $(date '+%F %T') ${title}"
  log "  $*"
  log "=============================================================="
  if "$@" 2>&1 | tee -a "$LOG"; then
    log "✔ $(date '+%F %T') ${title} 完成"
  else
    log "✘ $(date '+%F %T') ${title} 失败（rc=${PIPESTATUS[0]}）"
    log "  已停止：补测按顺序依赖数据库完好，先检查上一轮的 file move 是否已移回原位。"
    log "  日志：$LOG"
    exit 1
  fi
  sleep "$COOLDOWN"
}

log "引擎  $ENGINE"
log "结果  $OUT"
log "模式  ${EXECUTE:-（dry-run，不提交）}"
log "日志  $LOG"

if [ -n "$EXECUTE" ] && ! curl -sf --max-time 5 "$ENGINE/web/bench/status" >/dev/null; then
  log "✘ 引擎不可达：$ENGINE/web/bench/status"
  exit 3
fi

# 1) PostgreSQL × TPC-C —— **不加** --auto-file-targets：路径是 yaml 里的显式 @auto: 哨兵
if has_rounds "$Y_PG_TPCC"; then
  run_group "1/4 pg × tpcc 补测" \
    python3 scripts/resbench_batch.py \
      --databases "$DB" --workloads "$WL_TPCC" \
      --orchestration "$Y_PG_TPCC" \
      --only-db pg-2GbSharedbuffer-1000 \
      --out "$OUT" --monitor-metrics "$METRICS" \
      $EXECUTE
else
  log "跳过 1/4 pg × tpcc：$(basename "$Y_PG_TPCC") 没有待补测轮次"
fi

# 2) PostgreSQL × SmallBank —— 同上
if has_rounds "$Y_PG_SB"; then
  run_group "2/4 pg × smallbank 补测" \
    python3 scripts/resbench_batch.py \
      --databases "$DB" --workloads "$WL_SB" \
      --orchestration "$Y_PG_SB" \
      --only-db pg-2GbSharedbuffer-1000 \
      --out "$OUT" --monitor-metrics "$METRICS" \
      $EXECUTE
else
  log "跳过 2/4 pg × smallbank：$(basename "$Y_PG_SB") 没有待补测轮次"
fi

# 3) MySQL × TPC-C —— 同样不加 --auto-file-targets：MySQL 的文件路径在 yaml 里写死
if has_rounds "$Y_MY_TPCC"; then
  run_group "3/4 mysql × tpcc 补测" \
    python3 scripts/resbench_batch.py \
      --databases "$DB" --workloads "$WL_TPCC" \
      --orchestration "$Y_MY_TPCC" \
      --only-db mysql-2gbSharedBuffer-1000 \
      --out "$OUT" --monitor-metrics "$METRICS" \
      $EXECUTE
else
  log "跳过 3/4 mysql × tpcc：$(basename "$Y_MY_TPCC") 没有待补测轮次"
fi

# 4) MySQL × SmallBank —— 同上
if has_rounds "$Y_MY_SB"; then
  run_group "4/4 mysql × smallbank 补测" \
    python3 scripts/resbench_batch.py \
      --databases "$DB" --workloads "$WL_SB" \
      --orchestration "$Y_MY_SB" \
      --only-db mysql-2gbSharedBuffer-1000 \
      --out "$OUT" --monitor-metrics "$METRICS" \
      $EXECUTE
else
  log "跳过 4/4 mysql × smallbank：$(basename "$Y_MY_SB") 没有待补测轮次"
fi

log ""
log "=============================================================="
log "$(date '+%F %T') 四组补测流程结束（空编排已跳过）。结果在 $OUT/，日志 $LOG"
log "下一步：按归档去重/替换规则，把新一轮放进 实验数据/<组>/，再刷新 index.html 与 docs/fault-15-table.md"
