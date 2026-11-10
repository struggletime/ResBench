#!/usr/bin/env bash
# 四组串行测试：pg×tpcc -> pg×smallbank -> mysql×tpcc -> mysql×smallbank
# 用法（过夜）：
#   nohup bash scripts/matrix-4groups/run-nightly-4.sh > nightly-$(date +%Y%m%d).log 2>&1 &
# 早上看：
#   tail -n 50 nightly-*.log
#   ls -lt batch-results/4groups/ | head
set -u
cd "$(dirname "$0")/../.."

METRICS="container.cpu.usage,container.memory.usage,net.qdisc.drop.percent,net.ping.loss,net.ping.rtt,net.qdisc.reorder.rate,net.tcp.dsack.rate,net.tcp.rto.rate,net.tcp.lost_retrans.rate,host.disk.usage,host.process.stopped,db.connections.usage,db.connections.open_rate,db.sessions,db.rollback.rate"

common_args() {
  echo --databases scripts/matrix-4groups/databases.yaml \
       --out batch-results/4groups \
       --monitor-metrics "$METRICS" \
       --execute
}

run_group() {
  local title="$1"; shift
  echo
  echo "==================== $(date '+%F %T') ${title} ===================="
  python3 scripts/resbench_batch.py "$@"
  echo "==================== $(date '+%F %T') ${title} 结束（exit=$?） ===================="
}

# 1) PostgreSQL × TPC-C
# run_group "pg x tpcc" \
#   $(common_args) \
#   --workloads scripts/matrix-4groups/workload-tpcc.yaml \
#   --orchestration scripts/matrix-4groups/matrix-pg-tpcc.yaml \
#   --only-db pg-2GbSharedbuffer-1000 \
#   --auto-file-targets --auto-process-target

# 2) PostgreSQL × smallbank
# run_group "pg x smallbank" \
#   $(common_args) \
#   --workloads scripts/matrix-4groups/workload-smallbank.yaml \
#   --orchestration scripts/matrix-4groups/matrix-pg-smallbank.yaml \
#   --only-db pg-2GbSharedbuffer-1000 \
#   --auto-file-targets --auto-process-target

# 3) MySQL × TPC-C（不加 --auto-file-targets：@auto 映射对 MySQL 会把库目录搬进父目录）
run_group "mysql x tpcc" \
  $(common_args) \
  --workloads scripts/matrix-4groups/workload-tpcc.yaml \
  --orchestration scripts/matrix-4groups/matrix-mysql-tpcc.yaml \
  --only-db mysql-2gbSharedBuffer-1000 \
  --auto-process-target

# 4) MySQL × smallbank（同样不加 --auto-file-targets）
# run_group "mysql x smallbank" \
#   $(common_args) \
#   --workloads scripts/matrix-4groups/workload-smallbank.yaml \
#   --orchestration scripts/matrix-4groups/matrix-mysql-smallbank.yaml \
#   --only-db mysql-2gbSharedBuffer-1000 \
#   --auto-process-target

echo
echo "==================== $(date '+%F %T') 四组全部结束 ===================="
