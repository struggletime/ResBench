#!/usr/bin/env bash
# ============================================================================
# plan_300 补测 —— MySQL × TPC-C，File Move + Process Stop 并集
#
#   编排：plan300-mysql-tpcc-retest.yaml
#   规模：130 个计划 / 298 个 batch / 486 个故障
#   口径：计划命中 File Move 或 Process Stop 就整份计划补测，全部 batch 原样保留。
#
# 用法：
#   scripts/matrix-4groups/run-plan300-mysql-tpcc-retest.sh --dry-run   # 只打印计划
#   scripts/matrix-4groups/run-plan300-mysql-tpcc-retest.sh             # 正式跑
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

ENGINE="${ENGINE:-http://127.0.0.1:8080}"
MANAGER="${MANAGER:-http://127.0.0.1:8090}"
OUT="${OUT:-batch-results/4groups}"

# 15 项监控指标，含 db.connections.open_rate（新建连接速率）。
METRICS="container.cpu.usage,container.memory.usage,net.qdisc.drop.percent,net.ping.loss,net.ping.rtt,net.qdisc.reorder.rate,net.tcp.dsack.rate,net.tcp.rto.rate,net.tcp.lost_retrans.rate,host.disk.usage,host.process.stopped,db.connections.usage,db.connections.open_rate,db.sessions,db.rollback.rate"

EXECUTE="--execute"
for arg in "$@"; do
  case "$arg" in
    --dry-run) EXECUTE="" ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（只支持 --dry-run）" >&2; exit 2 ;;
  esac
done

echo "▶ plan_300 补测：130 个计划 / 298 个 batch / 486 个故障"
echo "  编排：scripts/matrix-4groups/plan300-mysql-tpcc-retest.yaml"
echo "  库：mysql-2gbSharedBuffer-1000    负载：workload-mysql-tpcc.yaml"
echo "  结果：$OUT/"
echo

python3 scripts/resbench_batch.py \
  --engine "$ENGINE" --manager "$MANAGER" \
  --databases scripts/matrix-4groups/databases.yaml \
  --workloads scripts/matrix-4groups/workload-mysql-tpcc.yaml \
  --orchestration scripts/matrix-4groups/plan300-mysql-tpcc-retest.yaml \
  --only-db mysql-2gbSharedBuffer-1000 \
  --out "$OUT" \
  --monitor-metrics "$METRICS" $EXECUTE
