package com.resbench.common.fault;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;

/**
 * 验证状态判定逻辑单测（v2.2）：
 * <ul>
 *   <li>{@link FaultVerifier#effectiveTimeoutMs} 窗口公式（duration + grace，clamp，显式覆盖）</li>
 *   <li>{@link FaultVerifier#verifyAfterInjection} 的前置状态分支（无 check / 注入失败 / 无端点）</li>
 *   <li>{@link FaultVerifier#parseCheckResponse} 契约 JSON 解析</li>
 * </ul>
 * 不依赖真实 agent HTTP（需要轮询的有效路径不在单测范围）。
 */
public class FaultVerifierTest {

  private static final String CASE_NO_CHECK =
      "<case>"
          + "<id>1</id><description>Test Fault No Check</description>"
          + "<injection><cmd>echo</cmd><arg>x</arg></injection>"
          + "<agents><agent>192.168.1.1:8000</agent></agents>"
          + "<autoRecovery>1</autoRecovery>"
          + "</case>";

  private static final String CASE_WITH_CHECK =
      "<case>"
          + "<id>2</id><description>Test Fault With Check</description>"
          + "<injection><cmd>echo</cmd><arg>x</arg></injection>"
          + "<check><cmd>echo</cmd><arg>y</arg></check>"
          + "<agents><agent>192.168.1.1:8000</agent></agents>"
          + "<autoRecovery>1</autoRecovery>"
          + "</case>";

  private static Fault faultFromXml(String xml) throws Exception {
    Document doc =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    return Fault.loadFaultFromXML(doc).get(0);
  }

  // ------------------------------------------------------------------
  // effectiveTimeoutMs 窗口公式
  // ------------------------------------------------------------------

  @Test
  public void testEffectiveTimeout_StopsAtDuration() {
    // v2.2 修订：窗口 = 故障时长，故障恢复（duration 到点）即出结果，无 grace
    Assert.assertEquals(60_000L, FaultVerifier.effectiveTimeoutMs(0L, 60_000L));
    Assert.assertEquals(90_000L, FaultVerifier.effectiveTimeoutMs(0L, 90_000L));
    // 5s 故障被 7s 下限抬升（保证至少一轮轮询）
    Assert.assertEquals(7_000L, FaultVerifier.effectiveTimeoutMs(0L, 5_000L));
  }

  @Test
  public void testEffectiveTimeout_CapAt300s() {
    // 600s 故障 → 630s 被 cap 到 300s
    Assert.assertEquals(300_000L, FaultVerifier.effectiveTimeoutMs(0L, 600_000L));
    // 300s 故障 → 330s → cap 300s
    Assert.assertEquals(300_000L, FaultVerifier.effectiveTimeoutMs(0L, 300_000L));
  }

  @Test
  public void testEffectiveTimeout_ExplicitOverridesFormula() {
    // 显式 timeoutMs 绝对生效（不参与 clamp）
    Assert.assertEquals(123_000L, FaultVerifier.effectiveTimeoutMs(123_000L, 60_000L));
    Assert.assertEquals(1_000L, FaultVerifier.effectiveTimeoutMs(1_000L, 60_000L));
    Assert.assertEquals(999_999L, FaultVerifier.effectiveTimeoutMs(999_999L, 0L));
  }

  // ------------------------------------------------------------------
  // verifyAfterInjection 前置状态分支（不触发 HTTP/轮询）
  // ------------------------------------------------------------------

  @Test
  public void testVerifyAfterInjection_NoCheck_Unknown() throws Exception {
    Fault fault = faultFromXml(CASE_NO_CHECK);
    FaultVerificationResult result =
        FaultVerifier.verifyAfterInjection(fault, List.of(), List.of("192.168.1.1:8000"), 60_000L);
    Assert.assertEquals(FaultVerificationStatus.UNKNOWN, result.getStatus());
    Assert.assertTrue(result.getError().contains("未配置自检命令"));
  }

  @Test
  public void testVerifyAfterInjection_InjectionFailed_Error() throws Exception {
    Fault fault = faultFromXml(CASE_WITH_CHECK);
    FaultResult bad = new FaultResult();
    bad.setEndPoint("http://192.168.1.1:8000");
    bad.setResult("Executed: Error 1: boom");
    bad.setError(true);
    FaultVerificationResult result =
        FaultVerifier.verifyAfterInjection(fault, List.of(bad), List.of("192.168.1.1:8000"), 60_000L);
    Assert.assertEquals(FaultVerificationStatus.ERROR, result.getStatus());
    Assert.assertTrue(result.getError().contains("注入命令本身失败"));
  }

  @Test
  public void testVerifyAfterInjection_NoEndpoint_Unknown() throws Exception {
    Fault fault = faultFromXml(CASE_WITH_CHECK);
    FaultVerificationResult result =
        FaultVerifier.verifyAfterInjection(fault, List.of(), List.of(), 60_000L);
    Assert.assertEquals(FaultVerificationStatus.UNKNOWN, result.getStatus());
    Assert.assertTrue(result.getError().contains("无可用 agent 端点"));
  }

  @Test
  public void testVerifyAfterInjection_FaultCheckAttrTimeoutParsed() throws Exception {
    // <check timeoutMs="..."> 属性应被解析为 per-fault 覆盖（通过 Fault 字段暴露）
    String xml =
        "<case>"
            + "<id>3</id><description>Attr Fault</description>"
            + "<injection><cmd>echo</cmd><arg>x</arg></injection>"
            + "<check timeoutMs=\"12000\" pollIntervalMs=\"777\" warmupMs=\"111\"><cmd>echo</cmd><arg>y</arg></check>"
            + "<agents><agent>192.168.1.1:8000</agent></agents>"
            + "<autoRecovery>1</autoRecovery>"
            + "</case>";
    Fault fault = faultFromXml(xml);
    Assert.assertEquals(Long.valueOf(12_000L), fault.CHECK_TIMEOUT_MS);
    Assert.assertEquals(Long.valueOf(777L), fault.CHECK_POLL_INTERVAL_MS);
    Assert.assertEquals(Long.valueOf(111L), fault.CHECK_WARMUP_MS);
    Assert.assertTrue(fault.ASYNC == false);
  }

  @Test
  public void testVerifyAfterInjection_InjectionAsyncAttrParsed() throws Exception {
    String xml =
        "<case>"
            + "<id>4</id><description>Async Fault</description>"
            + "<injection async=\"true\"><cmd>java</cmd><arg>-jar</arg><arg>x.jar</arg></injection>"
            + "<agents><agent>192.168.1.1:8000</agent></agents>"
            + "<autoRecovery>1</autoRecovery>"
            + "</case>";
    Fault fault = faultFromXml(xml);
    Assert.assertTrue(fault.ASYNC);
  }

  // ------------------------------------------------------------------
  // parseCheckResponse 契约 JSON 解析
  // ------------------------------------------------------------------

  @Test
  public void testParseCheckResponse_Valid() {
    FaultVerifier.CheckResponse parsed =
        FaultVerifier.parseCheckResponse(
            "{\"enforced\":true,\"summary\":\"conn storm ok\","
                + "\"checks\":[{\"name\":\"session_num\",\"observed\":\"210\",\"expected\":\">200\",\"matched\":true}]}");
    Assert.assertNull(parsed.error);
    Assert.assertEquals(Boolean.TRUE, parsed.enforced);
    Assert.assertEquals("conn storm ok", parsed.summary);
    Assert.assertEquals(1, parsed.evidence.items.size());
  }

  @Test
  public void testParseCheckResponse_NotEnforced() {
    FaultVerifier.CheckResponse parsed =
        FaultVerifier.parseCheckResponse("{\"enforced\":false,\"summary\":\"no effect\",\"checks\":[]}");
    Assert.assertNull(parsed.error);
    Assert.assertEquals(Boolean.FALSE, parsed.enforced);
    Assert.assertEquals(0, parsed.evidence.items.size());
  }

  @Test
  public void testParseCheckResponse_MissingEnforced_ReturnsError() {
    FaultVerifier.CheckResponse parsed =
        FaultVerifier.parseCheckResponse("{\"error\":\"max_connection not implemented\"}");
    Assert.assertNotNull(parsed.error);
    Assert.assertEquals("max_connection not implemented", parsed.error);
    Assert.assertNull(parsed.enforced);
  }

  @Test
  public void testParseCheckResponse_InvalidJson_ReturnsError() {
    FaultVerifier.CheckResponse parsed = FaultVerifier.parseCheckResponse("画像关键字 (ID) | 功能描述 (Description)");
    Assert.assertNotNull(parsed.error);
    Assert.assertTrue(parsed.error.startsWith("invalid self-check output:"));
  }

  @Test
  public void testParseCheckResponse_EmptyBody_ReturnsError() {
    FaultVerifier.CheckResponse parsed = FaultVerifier.parseCheckResponse("");
    Assert.assertNotNull(parsed.error);
    Assert.assertEquals("empty/invalid response from agent", parsed.error);
  }
}