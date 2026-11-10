#!/usr/bin/env bash
# ============================================================================
# fake-check.sh —— 模拟 agent /check 的契约 JSON 输出（验证轮询链路测试用）
#
# 背景：DBChaos 尚未实现契约自检（check 子命令输出的是 ANSI 表格而非契约 JSON），
# 用本脚本顶替 yaml 里的 check.command，即可在不动 DBChaos 的情况下复现验证轮询的
# 四种结局：ENFORCED / NOT_ENFORCED / UNKNOWN(超时) / UNKNOWN(非 JSON 被 agent 包装)。
#
# 用法（第一个参数 = 模式）：
#   fake-check.sh eventual [N] [key]      前 N 次返回 enforced=false，第 N+1 次起
#                                         返回 enforced=true  → 最终 ENFORCED
#   fake-check.sh never  [key]            永远返回 enforced=false → 轮询窗口耗尽
#                                         后 NOT_ENFORCED
#   fake-check.sh timeout [SECONDS] [key] 挂起 SECONDS（默认 60）不输出 → agent
#                                         exec_capture 30s 兜底 / 后端 15s 请求超时
#                                         → 始终无有效响应 → UNKNOWN
#   fake-check.sh invalid [key]           输出非 JSON（带 ANSI 转义的表格，模拟
#                                         DBChaos 画像帮助）→ check.py 包装为
#                                         {"error":"invalid self-check output: ..."}
#                                         → UNKNOWN
#   fake-check.sh reset [key]             清零该 key 的调用计数
#
# 状态：每次被调用读计数文件并自增，通过 key 隔离（不同故障用不同 key，
#       避免共享计数）。计数文件默认 /tmp/fake-check-<key>.state（可覆盖环境变量）。
#
# 观测：每次调用向 stderr 打印一行（agent 日志可见），并追加到 /tmp/fake-check-<key>.log。
#
# 在 fault-cases.yaml 里使用示例（契约 JSON 不能含空格，整体绕过框架 argv 拆分）：
#   check:
#     command: 'bash /opt/agent/faults/fake-check.sh eventual 3 storm'
#     # 换成 never / "timeout 60" / invalid 即可复现其它结局
# ============================================================================

STATE_DIR="${FAKE_CHECK_STATE_DIR:-/tmp}"
POSITIONAL=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    reset) MODE=reset; shift ;;
    eventual) MODE=eventual; shift ;;
    never) MODE=never; shift ;;
    timeout) MODE=timeout; shift ;;
    invalid) MODE=invalid; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done

if [ -z "$MODE" ]; then
  echo "usage: fake-check.sh <eventual [N]|never|timeout [SECONDS]|invalid|reset> [key]" >&2
  exit 1
fi

# mode 专用参数
# mode 专用参数（按模式解析，key 不串位）
if [ "$MODE" = "eventual" ]; then
  N="${POSITIONAL[0]:-2}"
  KEY="${POSITIONAL[1]:-default}"
elif [ "$MODE" = "timeout" ]; then
  SECONDS="${POSITIONAL[0]:-60}"
  KEY="${POSITIONAL[1]:-default}"
else
  # never / invalid / reset：唯一的可选参数就是 key
  KEY="${POSITIONAL[0]:-default}"
  SECONDS=60
  N=2
fi

STATE_FILE="$STATE_DIR/fake-check-${KEY}.state"
LOG_FILE="$STATE_DIR/fake-check-${KEY}.log"

if [ "$MODE" = "reset" ]; then
  rm -f "$STATE_FILE" "$LOG_FILE"
  echo "[fake-check] counter reset for key=$KEY" >&2
  exit 0
fi

# ---------- 读计数并自增 ----------
COUNT=0
[ -f "$STATE_FILE" ] && COUNT=$(cat "$STATE_FILE" 2>/dev/null || echo 0)
COUNT=$((COUNT + 1))
echo "$COUNT" > "$STATE_FILE"

log() { echo "[fake-check][key=$KEY][call #$COUNT] $*" | tee -a "$LOG_FILE" >&2; }

case "$MODE" in
  eventual)
    if [ "$COUNT" -gt "$N" ]; then
      log "eventual: enforced=true (fault now effective, count=$COUNT > N=$N)"
      ENFORCED=true
      SUMMARY="eventual-success-after-${N}-tries"
      MATCHED=true
    else
      log "eventual: enforced=false (warming up, count=$COUNT <= N=$N)"
      ENFORCED=false
      SUMMARY="fault-not-yet-effective-try-${COUNT}"
      MATCHED=false
    fi
    ;;
  never)
    log "never: enforced=false (permanent)"
    ENFORCED=false
    SUMMARY="never-enforced"
    MATCHED=false
    ;;
  timeout)
    log "timeout: sleeping ${SECONDS}s without output (will trigger agent/backend timeout)"
    sleep "$SECONDS"
    ENFORCED=false
    SUMMARY="should-not-be-seen"
    MATCHED=false
    ;;
  invalid)
    log "invalid: emitting non-JSON with ANSI escapes (like DBChaos help table)"
    printf '\033[2m  画像关键字 (ID)         | 功能描述 (Description)\033[0m\n'
    printf '\033[33m  plan_flip         \033[0m | 执行计划跳变\n'
    printf '\033[33m  max_connection    \033[0m | 数据库最大连接数\n'
    exit 0   # 已输出非法内容，不再打印 JSON
    ;;
  *)
    echo "unknown mode: $MODE" >&2
    exit 1
    ;;
esac

# ---------- 契约 JSON（无空格，整体单 token，兼容框架 argv 拆分） ----------
echo "{\"enforced\":${ENFORCED},\"summary\":\"${SUMMARY}\",\"checks\":[{\"name\":\"mock_metric\",\"observed\":\"mock\",\"expected\":\"mock\",\"matched\":${MATCHED}}]}"