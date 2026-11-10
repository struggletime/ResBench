/**
 * 长连接（常驻 WebSocket 命令通道）状态判定。
 *
 * <p>引擎给每一次注入/恢复都记了两个字段（引擎 {@code FaultResult} → 网关
 * {@code FaultResultResponse}）：
 *   · {@code transport}    —— {@code "ws"} 骑在 t+0 建好的常驻命令通道上 / {@code "http"} 又新建了一条 TCP 连接
 *   · {@code channelAlive} —— 走通道时，发送那一刻通道是否 OPEN
 *
 * <p><b>它们是「长连接有没有用上」的唯一判据</b>，因为故障窗口里 agent 端没有日志可看：
 * filemove 恢复失败的根因就是「在 t+30 那一刻新建连接建不起来」，而 {@code "http"} 恰恰
 * 意味着这一次又新建了连接。
 *
 * <p><b>能给出什么、给不出什么</b>：这两个字段只在**故障调用发生的那一刻**被记录，
 * 两次调用之间没有任何东西在采样通道。所以：
 *   · 能给出：每次调用骑没骑上通道（离散的点）、整轮是「从未建立 / 一直正常 / 中途断开」
 *   · 给不出：「通道在第几秒断的」。{@code firstFallbackAt} 是「第一次观测到回退」的时刻，
 *     是断开时刻的**上界**而不是断开时刻本身。
 *
 * <p>不过这个上界是可靠的：通道断了不会重连（本轮设计如此），一旦回退就不会再回到 ws，
 * 所以「第一次回退」之后的所有调用都必然也是回退——从那一刻起可以放心地整段标注。
 */

/** 整轮的通道状态 */
export const CHANNEL_STATE = {
  OK: 'ok', // 每一次调用都骑上了通道
  NEVER: 'never', // 有调用，但一次都没骑上 —— 通道在开头就没建起来
  BROKEN: 'broken', // 先骑上过，之后回退了 —— 通道中途断了
  UNKNOWN: 'unknown', // 老归档没有 transport 记录，或本轮还没有任何调用
};

/**
 * 这一次调用骑上常驻通道了吗。
 *
 * <p>{@code transport === 'ws'} 且 {@code channelAlive === false} 是可能的（健康检查与发送
 * 之间通道刚好断掉），这时命令<b>并没有</b>发出去，不能算「用上了长连接」——所以判据是
 * {@code channelAlive !== false} 而不是 {@code === true}：老数据没有 channelAlive 时不该被
 * 误判成没骑上。
 */
export function rodeChannel(call) {
  return call?.transport === 'ws' && call?.channelAlive !== false;
}

/** 一条记录有没有 transport 数据；老归档没有 */
export function hasTransportData(record) {
  const results = [
    ...(Array.isArray(record?.faultResults) ? record.faultResults : []),
    ...(Array.isArray(record?.recoveryResult) ? record.recoveryResult : []),
  ];
  return results.some((r) => r && r.transport);
}

/**
 * 把一轮归档摊平成调用序列（一次注入或一次恢复 = 一个点）。
 * 保持原始顺序：`sentAt` 缺失时（老数据）也不至于把顺序打乱。
 */
export function collectCalls(records) {
  const calls = [];
  (Array.isArray(records) ? records : []).forEach((record, recordIndex) => {
    if (!record) return;
    const phases = [
      ['inject', record.faultResults],
      ['recover', record.recoveryResult],
    ];
    phases.forEach(([phase, list]) => {
      (Array.isArray(list) ? list : []).forEach((r, resultIndex) => {
        if (!r || !r.transport) return; // 没有 transport = 老数据，不进统计
        calls.push({
          faultName: record.faultName || record.faultID || '',
          faultID: record.faultID,
          phase,
          transport: r.transport,
          channelAlive: r.channelAlive,
          sentAt: typeof r.sentAt === 'number' && r.sentAt > 1e9 ? r.sentAt : null,
          rode: rodeChannel(r),
          order: recordIndex * 100 + resultIndex,
        });
      });
    });
  });
  return calls;
}

/** 按发生顺序排序；没有 sentAt 的排在最后，用原始顺序做稳定兜底 */
function inOrder(calls) {
  return [...calls].sort((a, b) => {
    if (a.sentAt === null && b.sentAt === null) return a.order - b.order;
    if (a.sentAt === null) return 1;
    if (b.sentAt === null) return -1;
    return a.sentAt - b.sentAt || a.order - b.order;
  });
}

/**
 * 整轮的通道状态。
 *
 * @returns {{state: string, total: number, wsCount: number, httpCount: number,
 *            httpCountAfter: number, firstFallbackAt: number|null, firstCallAt: number|null}}
 *   `firstFallbackAt` / `httpCountAfter` 只在 {@link CHANNEL_STATE.BROKEN} 时有意义：
 *   前者是**第一次观测到回退**的时刻（断开时刻的上界），后者是那之后回退的调用次数。
 */
export function runChannelState(records) {
  const calls = collectCalls(records);
  if (calls.length === 0) {
    return {
      state: CHANNEL_STATE.UNKNOWN,
      total: 0,
      wsCount: 0,
      httpCount: 0,
      httpCountAfter: 0,
      firstFallbackAt: null,
      firstCallAt: null,
    };
  }

  const ordered = inOrder(calls);
  const wsCount = calls.filter((c) => c.rode).length;
  const httpCount = calls.length - wsCount;

  let state = CHANNEL_STATE.BROKEN;
  if (wsCount === 0) state = CHANNEL_STATE.NEVER;
  else if (httpCount === 0) state = CHANNEL_STATE.OK;

  let firstFallbackAt = null;
  let httpCountAfter = 0;
  if (state === CHANNEL_STATE.BROKEN) {
    let seenWs = false;
    for (let i = 0; i < ordered.length; i += 1) {
      const call = ordered[i];
      if (call.rode) {
        seenWs = true;
        continue;
      }
      if (seenWs) {
        firstFallbackAt = call.sentAt;
        // 断开之后（含这一点）的所有调用必然也都是回退 —— 不重连，所以不会再回到 ws
        httpCountAfter = ordered.slice(i).filter((c) => !c.rode).length;
        break;
      }
    }
  }

  const sentTimes = ordered.map((c) => c.sentAt).filter((n) => n !== null);
  return {
    state,
    total: calls.length,
    wsCount,
    httpCount,
    httpCountAfter,
    firstFallbackAt,
    firstCallAt: sentTimes.length ? sentTimes[0] : null,
  };
}

/**
 * 单条故障的通道状态：`ws`（两边都骑上了）/ `http`（一次都没骑上）/
 * `mixed`（一边骑上一边走 HTTP）/ `unknown`（老归档）。
 */
export function faultChannelState(record) {
  const calls = collectCalls([record]);
  if (calls.length === 0) return 'unknown';
  const wsCount = calls.filter((c) => c.rode).length;
  if (wsCount === calls.length) return 'ws';
  if (wsCount === 0) return 'http';
  return 'mixed';
}
