#!/usr/bin/env bash
# plan_000001..plan_000010 的绝对时刻编排，四组依次跑。
#
# 用法：
#   scripts/matrix-4groups/run-plans.sh --dry-run   # 只打印计划，不提交
#   scripts/matrix-4groups/run-plans.sh             # 正式跑四组
#   RUN_PG=0 / RUN_MYSQL=0 scripts/matrix-4groups/run-plans.sh   # 只跑一侧
#
# PG 侧加 --auto-file-targets（编排里文件路径留空）；MySQL 侧路径写死，不加。
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

ENGINE="${ENGINE:-http://127.0.0.1:8080}"
MANAGER="${MANAGER:-http://127.0.0.1:8090}"
OUT="${OUT:-batch-results/4groups}"
COOLDOWN="${COOLDOWN:-60}"

EXECUTE="--execute"
for arg in "$@"; do
  case "$arg" in
    --dry-run) EXECUTE="" ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（只支持 --dry-run）" >&2; exit 2 ;;
  esac
done

RUN_PG="${RUN_PG:-1}"
RUN_MYSQL="${RUN_MYSQL:-1}"
DB=scripts/matrix-4groups/databases.yaml
FAILED=()

run_one() {
  local name="$1"; shift
  echo
  echo "=============================================================="
  echo "▶ $name"
  echo "  $*"
  echo "=============================================================="
  if ! "$@"; then
    echo "✘ $name 失败（继续下一组）"
    FAILED+=("$name")
  fi
}

cooldown() {
  [ -n "$EXECUTE" ] && sleep "$COOLDOWN"
  return 0
}

batch() {  # batch <workload> <orchestration> <only-db> [额外参数...]
  python3 scripts/resbench_batch.py \
    --engine "$ENGINE" --manager "$MANAGER" \
    --databases "$DB" --workloads "$1" --orchestration "$2" \
    --only-db "$3" --out "$OUT" $EXECUTE "${@:4}"
}

if [ "$RUN_PG" = "1" ]; then
  run_one "pg × tpcc" \
    batch scripts/matrix-4groups/workload-tpcc.yaml \
          scripts/matrix-4groups/plan-pg-tpcc.yaml \
          pg-2GbSharedbuffer-1000 --auto-file-targets
  cooldown
  run_one "pg × smallbank" \
    batch scripts/matrix-4groups/workload-smallbank.yaml \
          scripts/matrix-4groups/plan-pg-smallbank.yaml \
          pg-2GbSharedbuffer-1000 --auto-file-targets
  cooldown
fi

if [ "$RUN_MYSQL" = "1" ]; then
  run_one "mysql × tpcc" \
    batch scripts/matrix-4groups/workload-mysql-tpcc.yaml \
          scripts/matrix-4groups/plan-mysql-tpcc.yaml \
          mysql-2gbSharedBuffer-1000
  cooldown
  run_one "mysql × smallbank" \
    batch scripts/matrix-4groups/workload-smallbank.yaml \
          scripts/matrix-4groups/plan-mysql-smallbank.yaml \
          mysql-2gbSharedBuffer-1000
fi

echo
echo "=============================================================="
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "全部完成。结果在 $OUT/"
else
  echo "完成，但以下组合失败："
  for f in "${FAILED[@]}"; do echo "  ✘ $f"; done
  exit 1
fi
