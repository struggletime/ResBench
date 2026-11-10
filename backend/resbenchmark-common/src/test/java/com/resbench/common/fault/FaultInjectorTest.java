package com.resbench.common.fault;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import com.resbench.common.remote.RestfulCaller;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;

/**
 * 注入结果标记（v2.2：dispatch/markResult/VERIFYING 中间态）单测。
 */
public class FaultInjectorTest {

  private static final String CASE_XML =
      "<case>"
          + "<id>1</id><description>Test Fault</description>"
          + "<injection async=\"true\"><cmd>java</cmd><arg>-jar</arg><arg>dbchaos.jar</arg></injection>"
          + "<agents><agent>192.168.1.1:8000</agent></agents>"
          + "<autoRecovery>1</autoRecovery>"
          + "</case>";

  private static Fault faultFromXml() throws Exception {
    Document doc =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(CASE_XML.getBytes(StandardCharsets.UTF_8)));
    return Fault.loadFaultFromXML(doc).get(0);
  }

  private static FaultResult mark(String result) {
    FaultResult fr = new FaultResult();
    fr.setEndPoint("http://192.168.1.1:8000");
    FaultInjector.markResult(fr, result);
    return fr;
  }

  @Test
  public void testMarkResult_Dispatch_NotError() {
    FaultResult fr = mark("Executed: Dispatched: pid=4242");
    Assert.assertFalse(fr.isError());
    Assert.assertNull(fr.getReturncode());
    Assert.assertFalse(fr.isTimedOut());
  }

  @Test
  public void testMarkResult_Successful_Rc0() {
    FaultResult fr = mark("Executed: Successful: None: None");
    Assert.assertFalse(fr.isError());
    Assert.assertEquals(Integer.valueOf(0), fr.getReturncode());
  }

  @Test
  public void testMarkResult_Error_N_RcParsed() {
    FaultResult fr = mark("Executed: Error 1: boom");
    Assert.assertTrue(fr.isError());
    Assert.assertEquals(Integer.valueOf(1), fr.getReturncode());
  }

  @Test
  public void testMarkResult_CommandNotFound_Error() {
    FaultResult fr = mark("CommandNotFound: java: No such file or directory");
    Assert.assertTrue(fr.isError());
  }

  @Test
  public void testMarkResult_Timeout_ErrorAndTimedOut() {
    FaultResult fr = mark("TimeoutExpired=60");
    Assert.assertTrue(fr.isError());
    Assert.assertTrue(fr.isTimedOut());
  }

  @Test
  public void testMarkResult_Null_Error() {
    FaultResult fr = mark(null);
    Assert.assertTrue(fr.isError());
  }

  // ---- 生产路径实际收到的是 agent 的 {"result":"..."} 信封 ----
  // 此前 markResult 只按裸字符串判，信封导致 startsWith("Executed:") 永不命中，
  // 于是"注入失败"被记成成功、前端那枚失败徽章永远不亮。

  @Test
  public void testMarkResult_AgentEnvelope_Successful() {
    FaultResult fr = mark("{\"result\":\"Executed: Successful: None: None\"}");
    Assert.assertFalse(fr.isError());
    Assert.assertEquals(Integer.valueOf(0), fr.getReturncode());
  }

  @Test
  public void testMarkResult_AgentEnvelope_Error() {
    FaultResult fr = mark("{\"result\":\"Executed: Error 126: None: None\"}");
    Assert.assertTrue("信封没剥，失败会被记成成功", fr.isError());
    Assert.assertEquals(Integer.valueOf(126), fr.getReturncode());
  }

  @Test
  public void testMarkResult_AgentEnvelope_Timeout() {
    FaultResult fr = mark("{\"result\":\"TimeoutExpired=60\"}");
    Assert.assertTrue(fr.isError());
    Assert.assertTrue(fr.isTimedOut());
  }

  @Test
  public void testMarkResult_HttpNotOk_FalseLiteral() {
    // RestfulCaller 在 HTTP 非 200 时原样返回字面量 "false"
    FaultResult fr = mark("false");
    Assert.assertTrue(fr.isError());
  }

  @Test
  public void testMarkResult_BladeFailureJson() {
    // chaosblade 自己的失败 JSON（agent 回传子进程 stdout 之后才会出现）
    FaultResult fr = mark("{\"code\":63063,\"success\":false,\"error\":\"mv: Directory not empty\"}");
    Assert.assertTrue("chaosblade 内部失败要判为失败", fr.isError());
  }

  @Test
  public void testMarkResult_BladeFailureInsideEnvelope() {
    FaultResult fr = mark("{\"result\":\"{\\\"code\\\":63063,\\\"success\\\":false}\"}");
    Assert.assertTrue(fr.isError());
  }

  // ---- chaosblade 失败详情：恢复徽章要靠它区分「超时自愈」与真正的恢复失败 ----

  @Test
  public void testMarkResult_ChaosOsMissing_SelfRecovered() {
    // agent 回传 stderr 后的真实形态：Executed: Error 1: <stdout>: <stderr>
    FaultResult fr =
        mark(
            "{\"result\":\"Executed: Error 1: : {\\\"code\\\":63063,\\\"success\\\":false,"
                + "\\\"error\\\":\\\"destory experiment failed, cannot get the chaos_os program\\\"}\"}");
    Assert.assertTrue("退出码非零仍要记录为失败", fr.isError());
    Assert.assertEquals(Integer.valueOf(1), fr.getReturncode());
    Assert.assertEquals(Integer.valueOf(63063), fr.getBladeCode());
    Assert.assertTrue(fr.getBladeError().contains("cannot get the chaos_os program"));
    Assert.assertTrue("找不到 chaos_os = 故障已自行结束", fr.getSelfRecovered());
  }

  @Test
  public void testMarkResult_Blade63063OtherMessage_NotSelfRecovered() {
    // 实测：endpoint 不可达时 code 也是 63063，只是 error 文本不同 —— 不能只看 code
    FaultResult fr =
        mark(
            "{\"result\":\"Executed: Error 1: : {\\\"code\\\":63063,\\\"success\\\":false,"
                + "\\\"error\\\":\\\"command exec failed, exit status 2\\\"}\"}");
    Assert.assertTrue(fr.isError());
    Assert.assertEquals(Integer.valueOf(63063), fr.getBladeCode());
    Assert.assertEquals(Boolean.FALSE, fr.getSelfRecovered());
  }

  @Test
  public void testMarkResult_ExecErrorWithoutBladeDetail() {
    // 老 agent / 未 capture：没有 chaosblade 文本，不能猜成自愈
    FaultResult fr = mark("Executed: Error 1: None: None");
    Assert.assertTrue(fr.isError());
    Assert.assertNull(fr.getBladeCode());
    Assert.assertNull(fr.getBladeError());
    Assert.assertNull(fr.getSelfRecovered());
  }

  @Test
  public void testMarkResult_RawBladeChaosOsMissing_SelfRecovered() {
    FaultResult fr =
        mark(
            "{\"code\":63063,\"success\":false,"
                + "\"error\":\"destory experiment failed, cannot get the chaos_os program\"}");
    Assert.assertTrue(fr.isError());
    Assert.assertEquals(Integer.valueOf(63063), fr.getBladeCode());
    Assert.assertTrue(fr.getSelfRecovered());
  }

  // ---- disk fill 按 uid 撤销（{{uid}} 占位）----
  // 背景：chaosblade 的 disk fill 撤销会 kill 命令行含 "disk fill" 的进程，
  // 而 "blade destroy disk fill ..." 自己就命中该子串 → 每轮恢复都被 SIGKILL（rc=137、回执为空）。
  // 改成按 uid 撤销后命令行不含该串，这里覆盖 uid 的抓取、替换与兜底。

  private static final String DISK_FILL_CASE_XML =
      "<case>"
          + "<id>2</id><description>Disk Fill</description>"
          + "<injection><cmd>/opt/chaosblade/blade</cmd>"
          + "<arg>create</arg><arg>disk</arg><arg>fill</arg><arg>--percent</arg><arg>90</arg>"
          + "</injection>"
          + "<recovery><cmd>/opt/chaosblade/blade</cmd>"
          + "<arg>destroy</arg><arg>{{uid}}</arg>"
          + "</recovery>"
          + "<agents><agent>192.168.1.1:8000</agent></agents>"
          + "<autoRecovery>0</autoRecovery>"
          + "</case>";

  private static Fault diskFillFault() throws Exception {
    Document doc =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(DISK_FILL_CASE_XML.getBytes(StandardCharsets.UTF_8)));
    return Fault.loadFaultFromXML(doc).get(0);
  }

  @Test
  public void testExtractBladeUid_AgentEnvelope() {
    // 生产形态：agent 信封里包着 chaosblade 的成功 JSON
    String raw =
        "{\"result\":\"Executed: Successful: {\\\"code\\\":200,\\\"success\\\":true,"
            + "\\\"result\\\":\\\"108461d6a60d1c5f\\\"}\\n: \"}";
    Assert.assertEquals("108461d6a60d1c5f", FaultInjector.extractBladeUid(raw));
  }

  @Test
  public void testExtractBladeUid_BareSuccessJson() {
    Assert.assertEquals(
        "108461d6a60d1c5f",
        FaultInjector.extractBladeUid("{\"code\":200,\"success\":true,\"result\":\"108461d6a60d1c5f\"}"));
  }

  @Test
  public void testExtractBladeUid_FailureHasNoUid() {
    Assert.assertNull(
        FaultInjector.extractBladeUid(
            "{\"result\":\"Executed: Error 137: : \"}"));
    Assert.assertNull(
        FaultInjector.extractBladeUid("{\"code\":63063,\"success\":false,\"error\":\"boom\"}"));
    Assert.assertNull(FaultInjector.extractBladeUid(null));
  }

  @Test
  public void testResolveRecoveryArgs_NoPlaceholder_PassesThrough() {
    String[] args = {"destroy", "cpu", "fullload"};
    Assert.assertArrayEquals(
        args,
        FaultInjector.resolveRecoveryArgs(null, "http://192.168.1.1:8000", args).toArray());
  }

  @Test
  public void testResolveRecoveryArgs_PlaceholderReplacedByUid() throws Exception {
    Fault fault = diskFillFault();
    fault.putInjectionUid("http://192.168.1.1:8000", "108461d6a60d1c5f");
    Assert.assertEquals(
        java.util.List.of("destroy", "108461d6a60d1c5f"),
        FaultInjector.resolveRecoveryArgs(fault, "http://192.168.1.1:8000", fault.RECOVERY.getArgs()));
  }

  @Test
  public void testResolveRecoveryArgs_PlaceholderWithoutUid_ReturnsNull() throws Exception {
    // 注入没返回 uid（失败/老的乱序）时不能拼出 "destroy {{uid}}"，要交给 --timeout 兜底
    Fault fault = diskFillFault();
    Assert.assertNull(
        FaultInjector.resolveRecoveryArgs(fault, "http://192.168.1.1:8000", fault.RECOVERY.getArgs()));
  }

  @Test
  public void testMarkRecordAlreadyGone_67002_CountsAsSelfRecovered() {
    FaultResult fr =
        mark("{\"result\":\"Executed: Error 1: : {\\\"code\\\":67002,\\\"success\\\":false,"
            + "\\\"error\\\":\\\"`108461d6a60d1c5f` record not found\\\"}\"}");
    Assert.assertTrue(fr.isError());
    FaultInjector.markRecordAlreadyGone(fr);
    Assert.assertFalse("记录已不存在 = --timeout 先撤了，不算恢复失败", fr.isError());
    Assert.assertEquals(Boolean.TRUE, fr.getSelfRecovered());
  }

  @Test
  public void testMarkRecordAlreadyGone_OtherBladeCode_KeepsFailure() {
    FaultResult fr =
        mark("{\"result\":\"Executed: Error 1: : {\\\"code\\\":63063,\\\"success\\\":false,"
            + "\\\"error\\\":\\\"command exec failed\\\"}\"}");
    FaultInjector.markRecordAlreadyGone(fr);
    Assert.assertTrue(fr.isError());
  }

  @Test
  public void testDescribeRecovery_ShowsResolvedUid() throws Exception {
    Fault fault = diskFillFault();
    Assert.assertEquals(
        "/opt/chaosblade/blade destroy {{uid}}", FaultInjector.describeRecovery(fault));
    fault.putInjectionUid("http://192.168.1.1:8000", "108461d6a60d1c5f");
    Assert.assertEquals(
        "/opt/chaosblade/blade destroy 108461d6a60d1c5f", FaultInjector.describeRecovery(fault));
  }

  // ---- uid 撤销的硬超时兜底 ----
  // `blade destroy` 没有 --timeout 旗标，撤销卡住只能由 agent 到点 SIGKILL、引擎多等余量拿回执。

  @Test
  public void testRecoveryPayload_UidRecoveryCarriesAgentTimeout() throws Exception {
    Fault fault = diskFillFault();
    java.util.Map<String, Object> payload =
        FaultInjector.recoveryPayload(
            fault.RECOVERY, java.util.List.of("destroy", "108461d6a60d1c5f"), false, true, 30);
    Assert.assertEquals(30, payload.get("timeout"));
    Assert.assertEquals(java.util.List.of("destroy", "108461d6a60d1c5f"), payload.get("args"));
    Assert.assertEquals(Boolean.TRUE, payload.get("capture"));
  }

  @Test
  public void testRecoveryPayload_PlainRecoveryKeepsAgentDefault() {
    Command recovery =
        new Command("/opt/chaosblade/blade", new String[] {"destroy", "cpu", "fullload"});
    java.util.Map<String, Object> payload =
        FaultInjector.recoveryPayload(recovery, java.util.List.of("destroy", "cpu", "fullload"), false, false, 30);
    Assert.assertFalse("非 uid 撤销不该改 agent 的 60s 默认", payload.containsKey("timeout"));
  }

  @Test
  public void testUidRecoveryClientDeadline_CoversAgentHardTimeout() {
    // 默认 10s 往返限额盖不住 agent 的 30s 硬超时 —— 必须自动放宽，否则回执永远收不到
    Assert.assertEquals(
        FaultInjector.DEFAULT_UID_RECOVERY_AGENT_TIMEOUT_SEC * 1000
            + FaultInjector.UID_RECOVERY_GRACE_MS,
        FaultInjector.uidRecoveryClientDeadlineMs(0));
    // 调用方给的限额本来就更大时尊重它
    Assert.assertEquals(60_000, FaultInjector.uidRecoveryClientDeadlineMs(60_000));
  }

  @Test
  public void testUidRecoveryAgentTimeout_PropertyOverride() {
    System.setProperty(FaultInjector.UID_RECOVERY_AGENT_TIMEOUT_PROPERTY, "12");
    try {
      Assert.assertEquals(12, FaultInjector.uidRecoveryAgentTimeoutSec());
      Assert.assertEquals(
          12_000 + FaultInjector.UID_RECOVERY_GRACE_MS,
          FaultInjector.uidRecoveryClientDeadlineMs(0));
    } finally {
      System.clearProperty(FaultInjector.UID_RECOVERY_AGENT_TIMEOUT_PROPERTY);
    }
  }

  @Test
  public void testUidRecoveryAgentTimeout_IllegalPropertyFallsBack() {
    System.setProperty(FaultInjector.UID_RECOVERY_AGENT_TIMEOUT_PROPERTY, "abc");
    try {
      Assert.assertEquals(
          FaultInjector.DEFAULT_UID_RECOVERY_AGENT_TIMEOUT_SEC,
          FaultInjector.uidRecoveryAgentTimeoutSec());
    } finally {
      System.clearProperty(FaultInjector.UID_RECOVERY_AGENT_TIMEOUT_PROPERTY);
    }
  }

  @Test
  public void testMarkUidRecoveryTimedOut_NotesFallbackWithoutClaimingSuccess() {
    FaultResult fr = mark("TimeoutExpired=30");
    Assert.assertTrue(fr.isTimedOut());
    Assert.assertTrue(FaultInjector.markUidRecoveryTimedOut(fr, 30));
    Assert.assertTrue("撤销超时仍要如实记成未完成", fr.isError());
    Assert.assertTrue("要说明 --timeout 是兜底清理", fr.getResult().contains("--timeout"));
    Assert.assertNull("不能把超时猜成自愈", fr.getSelfRecovered());

    FaultResult ok = mark("{\"result\":\"Executed: Successful: None: None\"}");
    Assert.assertFalse(FaultInjector.markUidRecoveryTimedOut(ok, 30));
    Assert.assertNull("成功回执不该被追加任何说明", ok.getResult());
  }

  @Test
  public void testVerifyingState() throws Exception {
    Fault fault = faultFromXml();
    FaultVerificationResult state = FaultInjector.verifyingState(fault);
    Assert.assertEquals(FaultVerificationStatus.VERIFYING, state.getStatus());
    Assert.assertEquals("1", state.getFaultID());
    Assert.assertEquals("Test Fault", state.getFaultName());
    Assert.assertEquals("INJECT", state.getPhase());
  }

  // ---- 往返存活判定：请求发出去了，并且在限额内收到了 agent 的回复 ----

  @Test
  public void testEffectiveTimeout_ZeroOrNegative_BecomesDefault() {
    Assert.assertEquals(10_000, FaultInjector.DEFAULT_ROUND_TRIP_TIMEOUT_MS);
    // 0 曾经表示「无限等待」—— 那正是命令能晚到 68 秒而任何记录都查不到的原因
    Assert.assertEquals(10_000, FaultInjector.effectiveTimeoutMs(0));
    Assert.assertEquals(10_000, FaultInjector.effectiveTimeoutMs(-1));
    Assert.assertEquals(5_000, FaultInjector.effectiveTimeoutMs(5_000));
  }

  /** 造一个「agent 已经应答」的假调用；耗时给个固定值，断言才好写 */
  private static FaultInjector.AgentPost reply(boolean replied, int status, String body, String error) {
    return (path, param, timeoutMs) ->
        new RestfulCaller.HttpResult(replied, status, body, 1L, error, null);
  }

  @Test
  public void testCallAgent_Reply_Success() {
    FaultResult fr =
        FaultInjector.callAgent(
            "http://192.168.1.1:8000",
            reply(true, 200, "{\"result\":\"Executed: Successful: None: None\"}", null),
            "{}",
            0);
    Assert.assertTrue(fr.hasReply());
    Assert.assertFalse(fr.hasNoReply());
    Assert.assertFalse(fr.isLegacyResult());
    Assert.assertEquals(Integer.valueOf(200), fr.getHttpStatus());
    Assert.assertEquals("限额应当落在默认值上", Integer.valueOf(10_000), fr.getTimeoutMs());
    Assert.assertFalse(fr.isError());
    Assert.assertEquals(Integer.valueOf(0), fr.getReturncode());
    // 成功也要给出耗时：前端那枚徽章要写「注入成功 · Xs」
    Assert.assertNotNull(fr.getSentAt());
    Assert.assertNotNull(fr.getRespondedAt());
    Assert.assertNotNull(fr.getElapsedMs());
  }

  @Test
  public void testCallAgent_ContentError_StillCountsAsReplied() {
    FaultResult fr =
        FaultInjector.callAgent(
            "http://192.168.1.1:8000",
            reply(true, 200, "{\"result\":\"Executed: Error 1: boom\"}", null),
            "{}",
            0);
    // 「有没有回复」和「命令成没成」是两个独立维度，不能混成一个
    Assert.assertTrue("收到了回复，就该记成已应答", fr.hasReply());
    Assert.assertTrue(fr.isError());
    Assert.assertEquals(Integer.valueOf(1), fr.getReturncode());
  }

  @Test
  public void testCallAgent_HttpNon200_RepliedButFailed() {
    FaultResult fr =
        FaultInjector.callAgent("http://192.168.1.1:8000", reply(true, 500, "false", null), "{}", 0);
    Assert.assertTrue("非 200 也是收到了回复", fr.hasReply());
    Assert.assertEquals(Integer.valueOf(500), fr.getHttpStatus());
    Assert.assertTrue(fr.isError());
  }

  @Test
  public void testCallAgent_TransportError_NoReply() {
    FaultResult fr =
        FaultInjector.callAgent(
            "http://192.168.1.1:8000", reply(false, 0, "", "Connection refused"), "{}", 0);
    Assert.assertTrue(fr.hasNoReply());
    Assert.assertTrue(fr.isError());
    Assert.assertEquals(Integer.valueOf(0), fr.getHttpStatus());
    // 连不上不是「超出限额」，别混为一谈
    Assert.assertFalse(fr.getDeadlineExceeded());
    Assert.assertTrue(fr.getResult().contains("Connection refused"));
  }

  @Test
  public void testCallAgent_NoReplyWithinDeadline() {
    FaultInjector.AgentPost slow =
        (path, param, timeoutMs) -> {
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new RestfulCaller.HttpResult(true, 200, "{}", 500L, null, null);
        };
    long start = System.currentTimeMillis();
    FaultResult fr = FaultInjector.callAgent("http://192.168.1.1:8000", slow, "{}", 100);
    long wallClock = System.currentTimeMillis() - start;

    Assert.assertTrue(fr.hasNoReply());
    Assert.assertEquals(Boolean.TRUE, fr.getDeadlineExceeded());
    Assert.assertTrue(fr.isError());
    Assert.assertEquals(Integer.valueOf(100), fr.getTimeoutMs());
    Assert.assertTrue("耗时应当约等于限额，而不是陪着命令一起等", fr.getElapsedMs() >= 100);
    Assert.assertTrue("必须真的在限额附近放弃，实际 " + wallClock + "ms", wallClock < 450);
  }

  @Test
  public void testCallAgent_LateOrphanDoesNotRewriteResult() throws Exception {
    FaultInjector.AgentPost slow =
        (path, param, timeoutMs) -> {
          try {
            Thread.sleep(300);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new RestfulCaller.HttpResult(
              true, 200, "{\"result\":\"Executed: Successful: None: None\"}", 300L, null, null);
        };
    FaultResult fr = FaultInjector.callAgent("http://192.168.1.1:8000", slow, "{}", 50);
    Assert.assertTrue(fr.hasNoReply());

    String result = fr.getResult();
    Long elapsed = fr.getElapsedMs();
    Long respondedAt = fr.getRespondedAt();
    // 等孤儿调用跑完（300ms > 50ms 限额）—— 它绝不能改写已经定稿的记录
    Thread.sleep(400);

    Assert.assertEquals(result, fr.getResult());
    Assert.assertEquals(elapsed, fr.getElapsedMs());
    Assert.assertEquals(respondedAt, fr.getRespondedAt());
    Assert.assertTrue(fr.hasNoReply());
  }

  @Test
  public void testMarkResult_DoesNotTouchRoundTripFields() {
    FaultResult fr = new FaultResult();
    fr.setReplied(true);
    fr.setElapsedMs(123L);
    FaultInjector.markResult(fr, "Executed: Error 3: boom");
    Assert.assertTrue(fr.isError());
    // markResult 只管内容层，不许碰往返存活那几个字段
    Assert.assertEquals(Boolean.TRUE, fr.getReplied());
    Assert.assertEquals(Long.valueOf(123L), fr.getElapsedMs());
  }

  @Test
  public void testLegacyResult_WithoutRepliedField_IsRecognised() {
    // 老归档没有 replied 字段，反序列化后是 null —— 必须能识别成「老数据」，
    // 否则前端会把每一条老记录都读成「未应答」
    FaultResult legacy = new FaultResult();
    legacy.setResult("Executed: Successful: None: None");
    Assert.assertTrue(legacy.isLegacyResult());
    Assert.assertFalse(legacy.hasReply());
    Assert.assertFalse(legacy.hasNoReply());
  }
}