#!/usr/bin/env bash
# ============================================================================
# plan_300 全量计划 —— MySQL × TPC-C，两段串行：
#   1/2 plan300-mysql-tpcc-main.yaml      225 个计划 / 474 批（不含 File Move）
#   2/2 plan300-mysql-tpcc-filemove.yaml   75 个计划 / 167 批（含 File Move 的整份计划）
#
# 两段的结果分别落在 batch-results/4groups/ 下不同的批次目录里。
# MySQL 侧路径已写死，不加 --auto-file-targets。
#
# 用法：
#   scripts/matrix-4groups/run-plan300-mysql-tpcc.sh --dry-run   # 只打印计划
#   scripts/matrix-4groups/run-plan300-mysql-tpcc.sh             # 正式跑两段
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

ENGINE="${ENGINE:-http://127.0.0.1:8080}"
MANAGER="${MANAGER:-http://127.0.0.1:8090}"
OUT="${OUT:-batch-results/4groups}"
COOLDOWN="${COOLDOWN:-60}"

# 15 项监控指标，含 db.connections.open_rate（新建连接速率）。
METRICS="container.cpu.usage,container.memory.usage,net.qdisc.drop.percent,net.ping.loss,net.ping.rtt,net.qdisc.reorder.rate,net.tcp.dsack.rate,net.tcp.rto.rate,net.tcp.lost_retrans.rate,host.disk.usage,host.process.stopped,db.connections.usage,db.connections.open_rate,db.sessions,db.rollback.rate"

EXECUTE="--execute"
for arg in "$@"; do
  case "$arg" in
    --dry-run) EXECUTE="" ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（只支持 --dry-run）" >&2; exit 2 ;;
  esac
done

DB=scripts/matrix-4groups/databases.yaml
WL=scripts/matrix-4groups/workload-mysql-tpcc.yaml
MAIN=scripts/matrix-4groups/plan300-mysql-tpcc-main.yaml
FILEMOVE=scripts/matrix-4groups/plan300-mysql-tpcc-filemove.yaml
FAILED=()

run_one() {
  local name="$1"; shift
  echo
  echo "=============================================================="
  echo "▶ $name"
  echo "  $*"
  echo "=============================================================="
  if ! "$@"; then
    echo "✘ $name 失败（继续下一段）"
    FAILED+=("$name")
  fi
}

batch() {  # batch <orchestration>
  python3 scripts/resbench_batch.py \
    --engine "$ENGINE" --manager "$MANAGER" \
    --databases "$DB" --workloads "$WL" --orchestration "$1" \
    --only-db mysql-2gbSharedBuffer-1000 --out "$OUT" \
    --monitor-metrics "$METRICS" $EXECUTE
}

# run_one "1/2 主批次（无 File Move）" batch "$MAIN"
# [ -n "$EXECUTE" ] && sleep "$COOLDOWN"
run_one "2/2 File Move 批次" batch "$FILEMOVE"

echo
echo "=============================================================="
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "全部完成。结果在 $OUT/"
else
  echo "完成，但以下段失败："
  for f in "${FAILED[@]}"; do echo "  ✘ $f"; done
  exit 1
fi
