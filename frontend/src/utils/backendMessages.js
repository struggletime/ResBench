// 后端验证/自检错误文案的英文映射。
//
// 引擎（FaultVerifier / BenchmarkController）与 agent（DBChaos）的错误文本是硬编码中文，
// 并随 verification.error / summary 原样回传。英文界面不能直接把这些字符串画出来，
// 所以这里只对「引擎自己产生、文案固定」的部分做映射；agent 的自由文本里能识别的
// 中文片段（如「未知的内核子系统」）一并替换，其余无法翻译的原文保持原样。

const EXACT_MESSAGES = [
  ['未配置自检命令（无 check 模板）', 'verification.backend.noCheckTemplate'],
  ['注入命令本身失败，不进入验证阶段', 'verification.backend.injectionCommandFailed'],
  ['无可用 agent 端点', 'verification.backend.noAgentEndpoint'],
  ['check 模板渲染为空', 'verification.backend.checkTemplateEmpty'],
];

const NO_REPLY_PREFIX = /^注入未在\s*(\d+)ms\s*内收到 agent 应答([\s\S]*)$/;
const VERIFY_TIMEOUT = /^轮询至超时（(\d+)ms），故障效果始终未达成$/;
const CHECK_CALL_UNKNOWN = /^未知[：:]\s*自检调用超时\/异常，无法获知 agent 状态[：:]\s*([\s\S]*)$/;
const CHECK_CALL_FAILED = /^check 调用失败[：:]\s*([\s\S]*)$/;

const BUT_ENFORCED = '，但自检显示故障已生效';
const AND_UNKNOWN = '，且自检无法判定 agent 状态';

/** 替换可识别的中文片段；不认识的文本原样保留（可能包含 agent 原始输出）。 */
function localizeFragments(text, translate) {
  return String(text ?? '')
    .replace(/未知的内核子系统\s*[：:]\s*/g, `${translate('verification.backend.unknownKernelSubsystem')}: `)
    .replace(/监控功能未启用/g, translate('monitoringPage.reason.disabled'))
    .replace(/Agent 不可用/g, translate('monitoringPage.reason.agentUnavailable'))
    .replace(/cAdvisor 未就绪/g, translate('monitoringPage.reason.cadvisorUnavailable'))
    .replace(/native probe 未加载/g, translate('monitoringPage.reason.nativeProbeMissing'))
    .replace(/collector 未启用/g, translate('monitoringPage.reason.collectorDisabled'))
    .replace(/Agent 会话创建失败，压测不受影响/g, translate('monitoringPage.reason.sessionFailed'))
    .replace(/Agent 心跳失败，压测不受影响/g, translate('monitoringPage.reason.heartbeatFailed'))
    .replace(new RegExp(BUT_ENFORCED, 'g'), translate('verification.backend.butEnforcedSuffix'))
    .replace(
      new RegExp(`${AND_UNKNOWN}\\s*[：:]\\s*`, 'g'),
      translate('verification.backend.andUnknownSuffix')
    )
    .replace(/；/g, '; ')
    .replace(/，/g, ', ')
    .replace(/、/g, ', ')
    .replace(/。/g, '. ')
    .replace(/：/g, ': ');
}

/**
 * 把后端验证文案翻译成当前语言。
 * 只在英文界面生效；中文界面原样返回，避免把 agent 原文改得面目全非。
 */
export function localizeBackendText(text, translate, lang) {
  if (text == null || lang !== 'en' || typeof translate !== 'function') return text;
  const value = String(text);

  const noReply = value.match(NO_REPLY_PREFIX);
  if (noReply) {
    let out = translate('verification.backend.injectNoReply', { ms: noReply[1] });
    const rest = noReply[2] || '';
    if (rest.startsWith(BUT_ENFORCED)) {
      out += translate('verification.backend.butEnforcedSuffix');
      const detail = rest.slice(BUT_ENFORCED.length).replace(/^[：:]\s*/, '');
      if (detail) out += `: ${localizeFragments(detail, translate)}`;
    } else if (rest.startsWith(AND_UNKNOWN)) {
      out += translate('verification.backend.andUnknownSuffix');
      const detail = rest.slice(AND_UNKNOWN.length).replace(/^[：:]\s*/, '');
      if (detail) out += localizeFragments(detail, translate);
    } else if (/^[；;]/.test(rest)) {
      out += `; ${localizeFragments(rest.slice(1).trim(), translate)}`;
    } else if (rest.trim()) {
      out += localizeFragments(rest, translate);
    }
    return out;
  }

  const timeout = value.match(VERIFY_TIMEOUT);
  if (timeout) {
    return translate('verification.backend.verifyTimeout', { ms: timeout[1] });
  }

  const unknown = value.match(CHECK_CALL_UNKNOWN);
  if (unknown) {
    return translate('verification.backend.checkCallUnknown', {
      detail: localizeFragments(unknown[1] || 'unknown', translate),
    });
  }

  const failed = value.match(CHECK_CALL_FAILED);
  if (failed) {
    return translate('verification.backend.checkCallFailed', {
      detail: localizeFragments(failed[1] || '', translate),
    });
  }

  for (const [zh, key] of EXACT_MESSAGES) {
    if (value.trim() === zh) return translate(key);
  }

  return localizeFragments(value, translate);
}

export default localizeBackendText;
