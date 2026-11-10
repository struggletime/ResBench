import { useLanguage } from "../../i18n";
import { CHANNEL_STATE, faultChannelState, runChannelState } from "../../utils/channelState";

const STATUS_META = {
  ENFORCED: { key: 'verification.status.ENFORCED', color: '#15803d', bg: '#dcfce7', border: '#86efac' },
  NOT_ENFORCED: { key: 'verification.status.NOT_ENFORCED', color: '#b91c1c', bg: '#fee2e2', border: '#fca5a5' },
  ERROR: { key: 'verification.status.ERROR', color: '#c2410c', bg: '#ffedd5', border: '#fdba74' },
  UNKNOWN: { key: 'verification.status.UNKNOWN', color: '#525252', bg: '#f5f5f5', border: '#d4d4d4' },
  VERIFYING: { key: 'verification.status.VERIFYING', color: '#1d4ed8', bg: '#dbeafe', border: '#93c5fd' },
  PENDING: { key: 'verification.status.PENDING', color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb' },
};

/** 注入/恢复小徽章的配色（沿用状态徽章那套色板） */
const CHIP_META = {
  ok: { color: '#15803d', bg: '#dcfce7', border: '#86efac', mark: '✓' },
  fail: { color: '#b91c1c', bg: '#fee2e2', border: '#fca5a5', mark: '⚠' },
  pending: { color: '#1d4ed8', bg: '#dbeafe', border: '#93c5fd', mark: '…' },
  info: { color: '#525252', bg: '#f5f5f5', border: '#d4d4d4', mark: '⟳' },
  // 故障已自行结束（超时自愈）：恢复命令到了，但 chaos_os 已经不在了。
  // 它不是失败，所以单独一色，不并进红色的 recoverFailed。
  selfHealed: { color: '#0f766e', bg: '#ccfbf1', border: '#5eead4', mark: '↺' },
  // 长连接：骑上了通道是绿的，退回新建 HTTP 连接是琥珀色（不是错，但「没帮上忙」）
  channelWs: { color: '#15803d', bg: '#dcfce7', border: '#86efac', mark: '⇄' },
  channelFallback: { color: '#b45309', bg: '#fef3c7', border: '#fcd34d', mark: '⚠' },
};

/** 整轮通道状态的配色：绿=正常，红=整轮都没建立，琥珀=中途断开 */
const CHANNEL_PANEL = {
  [CHANNEL_STATE.OK]: { bg: '#f0fdf4', border: '#86efac', color: '#166534', icon: '⇄', key: 'channel.ok', hint: 'channel.okHint' },
  [CHANNEL_STATE.NEVER]: { bg: '#fef2f2', border: '#fca5a5', color: '#991b1b', icon: '⚠', key: 'channel.never', hint: 'channel.neverHint' },
  [CHANNEL_STATE.BROKEN]: { bg: '#fffbeb', border: '#fcd34d', color: '#92400e', icon: '⚠', key: 'channel.broken', hint: 'channel.brokenHint' },
};

/** 断开时刻（第一次观测到回退）在提示里按本地时间写出来 */
const formatClock = (epochMs) => {
  const date = new Date(Number(epochMs));
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
};

/** 恢复时点之后留一点宽限，避免"刚好到点还没写盘"被误判成未发出 */
const RECOVERY_GRACE_MS = 5000;

/**
 * 故障验证状态徽章条（v2.1 §8 + v2.2 增强 + 往返存活判定）。
 *
 * <p><b>判定标准是「请求发出去了，并且在限额内收到了 agent 的回复」</b>，不再靠回复内容反推：
 * 内容只用来补充说明（退出码、chaosblade 的报错），成没成看的是往返。
 * 每条注入/恢复各挂一枚小徽章，并按你的要求**成功失败都标出耗时**
 * （`注入成功 · 0.4s` / `未应答 · 10.0s`）。
 *
 * <p>「未应答」（发出去了、限额内没有回复）会明确说出来 —— 按约定十秒没有返回就说明
 * 出现了意料之外的事情，不能只躺在引擎日志里。
 *
 * <p>红色的醒目块不用悬停：把「注入成没成、恢复成没成、故障实际存在了多久」直接写出来。
 */
const VerificationBadges = ({ faultInjectInfo = [], showEmpty = true }) => {
  const { translate } = useLanguage();
  const records = Array.isArray(faultInjectInfo) ? faultInjectInfo : [];

  const results = (list) => (Array.isArray(list) ? list.filter(Boolean) : []);

  /**
   * 严格取数：`null` / `undefined` / 空串一律算「没有这个数据」。
   *
   * <p><b>不能直接 `Number(v)`</b> —— `Number(null)` 是 `0`，而 0 是有限数，于是
   * 网关给老记录补出来的 `null` 会被读成「耗时 0.0 秒」，看起来像注入瞬间就回来了。
   * 这不是理论问题：网关的 `FaultResultResponse` 有这几个新字段，读引擎 JSON 时
   * 老记录没这个键就成了 null，Spring 的 Jackson 又把 null 一并序列化出去
   * （`NON_NULL` 只设在 `JsonUtils.MAPPER` 上，没设在 Spring 的转换器上），
   * 前端一读就是 0。
   */
  const numOrNull = (v) => {
    if (v === null || v === undefined || v === '') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  };

  /** 往返耗时的最大值（秒，一位小数）；老数据没有就返回 null */
  const maxElapsedSec = (rs) => {
    const values = rs.map((r) => numOrNull(r.elapsedMs)).filter((n) => n !== null);
    return values.length ? Math.max(...values) / 1000 : null;
  };

  /** 本次实际生效的限额（秒） */
  const timeoutSec = (rs) => {
    const value = rs.map((r) => numOrNull(r.timeoutMs)).find((n) => n !== null && n > 0);
    return value === undefined ? null : value / 1000;
  };

  /** 「 · 3.2s」——成功和失败都要带上 */
  const elapsedSuffix = (rs) => {
    const sec = maxElapsedSec(rs);
    return sec === null ? '' : ` · ${sec.toFixed(1)}s`;
  };

  const isNoReply = (r) => r.replied === false;
  const isReplied = (r) => r.replied === true;

  /** 这条结果集有没有「要么回复了、要么没回复」的往返回执；没有就无从核对十秒限额 */
  const hasReplyVerdict = (rs) => rs.some((r) => isReplied(r) || isNoReply(r));

  /** 这条故障有没有「往返回执」数据；没有就是老归档，只能退回按内容判 */
  const hasRoundTripData = (f) =>
    [...results(f.faultResults), ...results(f.recoveryResult)].some((r) => isNoReply(r) || isReplied(r));

  const agentText = (rs) => rs.map((r) => r.result).filter(Boolean).join('\n');

  /** 一枚小徽章（注入/恢复各一枚）；拿不到状态就什么也不渲染 */
  const chip = (state) =>
    state ? (
      <span
        title={state.title || undefined}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 3,
          padding: '1px 6px',
          borderRadius: 999,
          fontSize: 11,
          color: state.meta.color,
          background: state.meta.bg,
          border: `1px solid ${state.meta.border}`,
        }}
      >
        {state.meta.mark} {translate(state.key)}
        {state.suffix || ''}
      </span>
    ) : null;

  /**
   * 注入命令的判定：**主判据是「有没有在限额内收到回复」**。
   *   未应答   → 发出去了但没有回复（最需要被看见的一种）
   *   失败     → 收到了回复，但命令本身报错（rc≠0 / blade 失败 JSON / HTTP≠200）
   *   未记录   → 旧格式归档，没有 `replied`，**判不出**是否十秒内应答 → 不给绿色
   *   成功     → 收到了回复、没有报错、耗时也算得出来
   */
  const injectState = (f) => {
    const rs = results(f.faultResults);
    if (rs.length === 0) {
      return { meta: CHIP_META.pending, key: 'verification.injectPending', title: '' };
    }
    const noReply = rs.filter(isNoReply);
    if (noReply.length) {
      const limit = timeoutSec(rs);
      return {
        meta: CHIP_META.fail,
        key: 'verification.injectNoReply',
        suffix: elapsedSuffix(rs),
        title: `${translate('verification.noReplyHint')}${limit ? `（${limit}s）` : ''}\n${agentText(noReply)}`,
      };
    }
    const failed = rs.filter((r) => r.error);
    if (failed.length) {
      return {
        meta: CHIP_META.fail,
        key: 'verification.injectFailed',
        suffix: elapsedSuffix(rs),
        title: agentText(failed),
      };
    }
    // 命令报错看得见（上面那条），但「十秒内应答了吗」在旧归档里没记 —— 不能当成功
    if (!hasReplyVerdict(rs) || maxElapsedSec(rs) === null) {
      return {
        meta: CHIP_META.info,
        key: 'verification.roundTripUnrecorded',
        title: translate('verification.roundTripUnrecordedHint'),
      };
    }
    return {
      meta: CHIP_META.ok,
      key: 'verification.injectOk',
      suffix: elapsedSuffix(rs),
      title: agentText(rs),
    };
  };

  /**
   * 恢复命令的判定。recoveryResult 为空时有三种情况，要分开说：
   *   · autoRecovery → 引擎本来就不发恢复命令，交给 chaosblade 的 --timeout 自愈
   *   · 还没到恢复时点 → 等待恢复
   *   · 过了时点仍为空 → **恢复未发出**（这几天反复出现、界面上却看不见的那个症状）
   */
  const recoveryState = (f) => {
    const rs = results(f.recoveryResult);
    if (rs.length > 0) {
      const noReply = rs.filter(isNoReply);
      if (noReply.length) {
        const limit = timeoutSec(rs);
        return {
          meta: CHIP_META.fail,
          key: 'verification.recoverNoReply',
          suffix: elapsedSuffix(rs),
          title: `${translate('verification.noReplyHint')}${limit ? `（${limit}s）` : ''}\n${agentText(noReply)}`,
        };
      }
      // 故障已超时自愈：chaosblade 返回 63063 且 error 是 cannot get the chaos_os program。
      // 引擎已经用「code + 文本」双重判定过，这里不能只看 bladeCode。
      const healed = rs.filter((r) => r.selfRecovered === true);
      if (healed.length) {
        const detail =
          healed.map((r) => r.bladeError).filter(Boolean).join('\n') || agentText(healed);
        return {
          meta: CHIP_META.selfHealed,
          key: 'verification.recoverSelfHealed',
          suffix: elapsedSuffix(rs),
          title: `${translate('verification.recoverSelfHealedHint')}\n${detail}`,
        };
      }
      const failed = rs.filter((r) => r.error);
      if (failed.length) {
        return {
          meta: CHIP_META.fail,
          key: 'verification.recoverFailed',
          suffix: elapsedSuffix(rs),
          title: agentText(failed),
        };
      }
      // 同注入：旧归档没记往返，恢复也不给绿色
      if (!hasReplyVerdict(rs) || maxElapsedSec(rs) === null) {
        return {
          meta: CHIP_META.info,
          key: 'verification.roundTripUnrecorded',
          title: translate('verification.roundTripUnrecordedHint'),
        };
      }
      return {
        meta: CHIP_META.ok,
        key: 'verification.recoverOk',
        suffix: elapsedSuffix(rs),
        title: agentText(rs),
      };
    }
    if (f.autoRecovery === true || f.isAutoRecovery === true) {
      return { meta: CHIP_META.info, key: 'verification.recoverAuto', title: '' };
    }
    const start = Number(f.startTime || 0);
    const due = start > 0 ? start + Number(f.faultDuration || 0) * 1000 : 0;
    if (due <= 0) {
      return { meta: CHIP_META.pending, key: 'verification.recoverPending', title: '' };
    }
    return Date.now() < due + RECOVERY_GRACE_MS
      ? { meta: CHIP_META.pending, key: 'verification.recoverPending', title: '' }
      : {
          meta: CHIP_META.fail,
          key: 'verification.recoverMissing',
          title: translate('verification.recoverMissingHint'),
        };
  };

  /**
   * 用真实记录**说事实**，不再拿计划时长去猜。
   *
   * <p>引擎现在会记下每一次注入/恢复的往返耗时与「有没有收到回复」，所以
   * 「注入花了多久」「这轮故障实际存在了多久」都是算得出来的。
   */
  const factsState = (f) => {
    const facts = [];
    const inject = results(f.faultResults);
    const recover = results(f.recoveryResult);

    // 旧归档一个往返数都没有：直接说清「为什么耗时是 —」，免得被读成 0 秒或者被当成漏了
    if (!hasRoundTripData(f)) {
      facts.push(translate('verification.factNoRoundTrip'));
    }

    const injectNoReply = inject.filter(isNoReply);
    const injectElapsed = maxElapsedSec(inject);
    if (injectNoReply.length) {
      facts.push(
        translate('verification.factInjectNoReply')
          .replace('{elapsed}', injectElapsed === null ? '—' : injectElapsed.toFixed(1))
          .replace('{limit}', timeoutSec(inject) === null ? '—' : String(timeoutSec(inject)))
      );
    } else if (injectElapsed !== null) {
      facts.push(
        translate('verification.factInjectTook').replace('{elapsed}', injectElapsed.toFixed(1))
      );
    }

    if (recover.filter(isNoReply).length) {
      facts.push(translate('verification.factRecoverNoReply'));
    }

    // 故障实际存在了多久：注入拿到回复的时刻 → 恢复发出的时刻
    const injectAck = inject.map((r) => numOrNull(r.respondedAt)).find((n) => n !== null && n > 0);
    const recoverSent = recover.map((r) => numOrNull(r.sentAt)).find((n) => n !== null && n > 0);
    const planned = numOrNull(f.faultDuration) === null ? 0 : Number(f.faultDuration);
    if (injectAck && recoverSent && recoverSent > injectAck && planned > 0) {
      const alive = (recoverSent - injectAck) / 1000;
      if (alive > planned * 1.5 + 5 || alive < planned * 0.5) {
        facts.push(
          translate('verification.factAlive')
            .replace('{alive}', alive.toFixed(1))
            .replace('{planned}', String(planned))
        );
      }
    }
    return facts;
  };

  /**
   * 老归档的兜底猜测（只对没有往返回执数据的记录用）。
   * 以前只有这一条路，所以只能拿 `endTime - startTime` 去猜时间线可不可信；
   * 现在有了真实耗时，新记录一律走 factsState 说事实。
   */
  const timelineState = (f) => {
    const s = Number(f.startTime || 0);
    const e = Number(f.endTime || 0);
    const planned = Number(f.faultDuration || 0);
    if (s <= 1e9 || e <= 1e9 || planned <= 0) {
      return null;
    }
    const actual = (e - s) / 1000;
    if (actual > planned * 1.5 + 5) {
      return {
        meta: CHIP_META.fail,
        key: 'verification.timelineSuspect',
        title: `${translate('verification.timelineTooLong')}（记录窗口 ${actual.toFixed(1)}s / 计划 ${planned}s）`,
      };
    }
    if (actual < planned * 0.5) {
      return {
        meta: CHIP_META.fail,
        key: 'verification.timelineSuspect',
        title: `${translate('verification.timelineTooShort')}（实际 ${actual.toFixed(1)}s / 计划 ${planned}s）`,
      };
    }
    return null;
  };

  /** 老记录才需要那枚猜测徽章 */
  const legacyTimeline = (f) => (hasRoundTripData(f) ? null : timelineState(f));

  // ---- 长连接：整轮状态 + 每条故障的通道标记 ----
  const channel = runChannelState(records);
  const channelMeta = CHANNEL_PANEL[channel.state];

  /**
   * 每条故障的通道标记**只在整轮是中途中断开时**才给。
   *
   * <p>整轮齐刷刷走 ws 或齐刷刷走 HTTP 的时候，上面那条横幅已经把事情说完了，
   * 再给 13 条故障各挂一枚一模一样的标记纯属噪声。而「中途断开」时，
   * 哪几条故障骑在通道上、哪几条退回了 HTTP，正是唯一能定位断点的信息。
   */
  const channelChip = (f) => {
    if (channel.state !== CHANNEL_STATE.BROKEN) return null;
    const state = faultChannelState(f);
    if (state === 'ws') {
      return { meta: CHIP_META.channelWs, key: 'channel.perWs', title: translate('channel.perHintWs') };
    }
    if (state === 'http') {
      return {
        meta: CHIP_META.channelFallback,
        key: 'channel.perHttp',
        title: translate('channel.perHintHttp'),
      };
    }
    if (state === 'mixed') {
      return {
        meta: CHIP_META.channelFallback,
        key: 'channel.perMixed',
        title: translate('channel.perHintMixed'),
      };
    }
    return null;
  };

  const channelHint = (() => {
    if (!channelMeta) return '';
    let hint = translate(channelMeta.hint);
    hint = hint.replace('{total}', String(channel.total));
    if (channel.state === CHANNEL_STATE.BROKEN) {
      hint = hint
        .replace('{time}', formatClock(channel.firstFallbackAt))
        .replace('{count}', String(channel.httpCountAfter));
    }
    return hint;
  })();

  const decided = records.filter((f) => f && f.verification);
  const pendings = records.filter(
    (f) => f && !f.verification && (!f.startTime || Number(f.startTime) <= 0)
  );
  // 已注入但验证结论还没写回来的（startTime>0、verification 为空）——
  // 这类原来既不进 decided 也不进 pendings，会被整个丢掉、界面上什么都不显示
  const inFlight = records.filter(
    (f) => f && !f.verification && Number(f.startTime || 0) > 0
  );

  if (records.length === 0) {
    if (!showEmpty) return null;
    return (
      <div style={{ fontSize: 12, color: '#9ca3af', padding: '2px 0' }}>
        {translate('verification.empty')}
      </div>
    );
  }

  const badge = (f, meta, text, reason) => {
    const inject = injectState(f);
    const recover = recoveryState(f);
    return (
      <span
        key={`${f.faultID || f.faultName || text}-${text}`}
        title={reason || undefined}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          padding: '3px 10px',
          borderRadius: 999,
          fontSize: 12,
          lineHeight: 1.5,
          color: meta.color,
          background: meta.bg,
          border: `1px solid ${meta.border}`,
          whiteSpace: 'nowrap',
        }}
      >
        <strong style={{ fontWeight: 600 }}>{f.faultName || f.faultID || '#'}</strong>
        <span>{text}</span>
        {chip(inject)}
        {chip(recover)}
        {chip(channelChip(f))}
        {chip(legacyTimeline(f))}
      </span>
    );
  };

  // ---- 醒目提示区：把「注入成没成、恢复成没成」直接写出来，不用悬停 ----
  const clip = (s, n = 140) => {
    // 悬浮提示里用换行分隔「说明」和「agent 原文」，摊平到一行时改成顿号，别连成一句
    const t = String(s || '').replace(/\s*\n+\s*/g, '；').replace(/[ \t]+/g, ' ').trim();
    return t.length > n ? `${t.slice(0, n)}…` : t;
  };
  const rowsWithState = records
    .filter(Boolean)
    .map((f) => ({
      f,
      inject: injectState(f),
      recover: recoveryState(f),
      legacy: legacyTimeline(f),
      facts: factsState(f),
      injectSec: maxElapsedSec(results(f.faultResults)),
      recoverSec: maxElapsedSec(results(f.recoveryResult)),
    }));
  const problems = rowsWithState.filter(
    ({ inject, recover, legacy }) =>
      inject.meta === CHIP_META.fail ||
      recover.meta === CHIP_META.fail ||
      (legacy && legacy.meta === CHIP_META.fail)
  );
  // 绿色面板要同时满足：两边都判成功、**且两个耗时都真的在** ——
  // 「✓ … 耗时 —」这种绿块比不给结论更误导（旧归档就会那样）
  const allOk = rowsWithState.filter(
    ({ inject, recover, injectSec, recoverSec }) =>
      inject.meta === CHIP_META.ok &&
      recover.meta === CHIP_META.ok &&
      injectSec !== null &&
      recoverSec !== null
  );

  // ---- 长连接横幅：直接回答「长连接断了没」 ----
  // 老归档（没有 transport）时 channelMeta 为 undefined，这里什么也不渲染 ——
  // 「未记录往返」那枚徽章已经说过这是旧数据了，不必再来一条。
  const channelPanel = channelMeta && (
    <div
      style={{
        margin: '4px 0 8px',
        padding: '6px 10px',
        borderRadius: 6,
        background: channelMeta.bg,
        border: `1px solid ${channelMeta.border}`,
        color: channelMeta.color,
        fontSize: 13,
        lineHeight: 1.7,
      }}
    >
      <span style={{ fontWeight: 600 }}>
        {channelMeta.icon} {translate('channel.title')}：{translate(channelMeta.key)}
      </span>
      <span style={{ marginLeft: 8, opacity: 0.9 }}>{channelHint}</span>
    </div>
  );

  const problemPanel = problems.length > 0 && (
    <div
      style={{
        margin: '4px 0 8px',
        padding: '8px 10px',
        borderRadius: 6,
        background: '#fef2f2',
        border: '1px solid #fca5a5',
        color: '#991b1b',
        fontSize: 13,
        lineHeight: 1.7,
      }}
    >
      <div style={{ fontWeight: 600, marginBottom: 2 }}>
        ⚠ {translate('verification.panelTitle')}
      </div>
      {problems.map(({ f, inject, recover, legacy, facts }) => (
        <div key={`p-${f.faultID || f.faultName}`}>
          <strong>{f.faultName || f.faultID || '#'}</strong>
          {inject.meta === CHIP_META.fail && (
            <span style={{ marginLeft: 8 }}>
              {translate('verification.panelInject')}：{translate(inject.key)}
              {inject.title ? ` —— ${clip(inject.title)}` : ''}
            </span>
          )}
          {recover.meta === CHIP_META.fail && (
            <span style={{ marginLeft: 8 }}>
              {translate('verification.panelRecover')}：{translate(recover.key)}
              {recover.title ? ` —— ${clip(recover.title)}` : ''}
            </span>
          )}
          {/* 事实行：耗时多少、故障实际存在了多久，都直接写出来 */}
          {facts.map((fact) => (
            <div key={`f-${fact}`} style={{ marginLeft: 8, opacity: 0.9 }}>
              · {fact}
            </div>
          ))}
          {legacy && (
            <span style={{ marginLeft: 8 }}>
              ⚠ {translate('verification.timelineSuspect')}：{clip(legacy.title)}
            </span>
          )}
        </div>
      ))}
    </div>
  );

  const okPanel = allOk.length > 0 && (
    <div
      style={{
        margin: '4px 0 8px',
        padding: '6px 10px',
        borderRadius: 6,
        background: '#f0fdf4',
        border: '1px solid #86efac',
        color: '#166534',
        fontSize: 13,
      }}
    >
      ✓{' '}
      {allOk
        .map(({ f, injectSec, recoverSec }) => {
          const parts = [`${f.faultName || f.faultID || '#'}`];
          parts.push(
            translate('verification.panelOkElapsed')
              .replace('{inject}', `${injectSec.toFixed(1)}s`)
              .replace('{recover}', `${recoverSec.toFixed(1)}s`)
          );
          return parts.join(' ');
        })
        .join('；')}
    </div>
  );

  return (
    <div>
      {channelPanel}
      {problemPanel}
      {okPanel}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, padding: '2px 0' }}>
      {decided.map((f) => {
        const v = f.verification;
        const meta = STATUS_META[v.status] || STATUS_META.UNKNOWN;
        const statusText = translate(meta.key);
        // UNKNOWN / ERROR 的原因直接上徽章（截断），不悬停也能看懂；其余显示结论
        let detail = '';
        if ((v.status === 'UNKNOWN' || v.status === 'ERROR') && v.error) {
          detail = v.error.length > 36 ? `${v.error.slice(0, 36)}…` : v.error;
        } else if (v.summary) {
          detail = v.summary;
        }
        const text = detail ? `${statusText} · ${detail}` : statusText;
        // 悬浮：优先「原因」完整文本，其次「结论」
        const reason = v.error
          ? `${translate('verification.reason')}: ${v.error}`
          : v.summary
            ? `${translate('verification.summary')}: ${v.summary}`
            : undefined;
        return badge(f, meta, text, reason);
      })}
      {inFlight.map((f) =>
        badge(f, STATUS_META.VERIFYING, translate(STATUS_META.VERIFYING.key))
      )}
      {pendings.map((f) =>
        badge(f, STATUS_META.PENDING, translate(STATUS_META.PENDING.key), translate('verification.status.PENDING'))
      )}
      </div>
    </div>
  );
};

export default VerificationBadges;
