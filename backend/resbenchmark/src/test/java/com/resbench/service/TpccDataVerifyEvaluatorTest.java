package com.resbench.service;

import com.resbench.service.TpccDataVerifyEvaluator.Evaluation;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * TPCC 仓数检查的纯逻辑单测：结论口径、文案、以及"表不存在 / 没权限"两个判据。
 *
 * <p><b>不打开任何数据库连接</b> —— 判据用的是手工构造的 {@link SQLException}。
 */
public class TpccDataVerifyEvaluatorTest {

  private static Map<String, String> params(String warehouses) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("warehouses", warehouses);
    return map;
  }

  // ------------------------------------------------------------ 结论口径

  @Test
  public void testMatch() {
    Evaluation e = TpccDataVerifyEvaluator.evaluate(10, true, 10, true, 100);
    Assert.assertEquals(TpccDataVerifyEvaluator.MATCH, e.conclusion());
    Assert.assertTrue(e.message().contains("10"));
  }

  @Test
  public void testMatch_SingleWarehouse() {
    Assert.assertEquals(TpccDataVerifyEvaluator.MATCH,
        TpccDataVerifyEvaluator.evaluate(1, true, 1, true, 10).conclusion());
  }

  @Test
  public void testMismatch_FewerThanConfigured() {
    Evaluation e = TpccDataVerifyEvaluator.evaluate(10, true, 8, true, 80);
    Assert.assertEquals(TpccDataVerifyEvaluator.MISMATCH, e.conclusion());
    Assert.assertTrue(e.message().contains("上一次加载可能没跑完"));
  }

  @Test
  public void testMismatch_MoreThanConfigured() {
    // 实测场景：表单填 200、库里躺着上一轮 1000 仓
    Evaluation e = TpccDataVerifyEvaluator.evaluate(200, true, 1000, true, 10000);
    Assert.assertEquals(TpccDataVerifyEvaluator.MISMATCH, e.conclusion());
    Assert.assertTrue(e.message().contains("上一次加载留下的数据"));
    Assert.assertTrue(e.message().contains("reload"));
  }

  @Test
  public void testIncomplete_DistrictShort() {
    // 仓数对得上，但 district 少 3 行 → 疑似加载中断，绝不能报 MATCH
    Evaluation e = TpccDataVerifyEvaluator.evaluate(10, true, 10, true, 97);
    Assert.assertEquals(TpccDataVerifyEvaluator.INCOMPLETE, e.conclusion());
    Assert.assertTrue(e.message().contains("100"));
  }

  @Test
  public void testIncomplete_DistrictTableMissing() {
    Assert.assertEquals(TpccDataVerifyEvaluator.INCOMPLETE,
        TpccDataVerifyEvaluator.evaluate(10, true, 10, false, 0).conclusion());
  }

  @Test
  public void testIncomplete_IsNotCalledSuccess() {
    // warehouse 行是每个仓开始加载时就写入的 —— 文案不许说成"建仓成功"
    Evaluation e = TpccDataVerifyEvaluator.evaluate(10, true, 10, true, 50);
    Assert.assertFalse(e.message().contains("建仓成功"));
    Assert.assertTrue(e.message().contains("不代表建仓已完成"));
  }

  @Test
  public void testMismatch_DistrictAlsoShort() {
    Evaluation e = TpccDataVerifyEvaluator.evaluate(10, true, 4, true, 12);
    Assert.assertEquals(TpccDataVerifyEvaluator.MISMATCH, e.conclusion());
    Assert.assertTrue(e.message().contains("40")); // 按实际仓数推算的 district 行数
  }

  @Test
  public void testAbsent_TableDoesNotExist() {
    Evaluation e = TpccDataVerifyEvaluator.evaluate(10, false, 0, false, 0);
    Assert.assertEquals(TpccDataVerifyEvaluator.ABSENT, e.conclusion());
    Assert.assertTrue(e.message().contains("尚未建仓"));
    Assert.assertTrue(e.message().contains("没有 warehouse 表"));
  }

  @Test
  public void testAbsent_TablePresentButEmpty() {
    Evaluation e = TpccDataVerifyEvaluator.evaluate(10, true, 0, true, 0);
    Assert.assertEquals(TpccDataVerifyEvaluator.ABSENT, e.conclusion());
    Assert.assertTrue(e.message().contains("一行都没有"));
  }

  @Test
  public void testExpectedDistricts() {
    Assert.assertEquals(10, TpccDataVerifyEvaluator.expectedDistricts(1));
    Assert.assertEquals(10000, TpccDataVerifyEvaluator.expectedDistricts(1000));
  }

  // ------------------------------------------------------------ 表不存在 / 没权限

  @Test
  public void testTableMissing_PostgresFamily() {
    SQLException e = new SQLException("ERROR: relation \"warehouse\" does not exist", "42P01");
    Assert.assertTrue(TpccDataVerifyEvaluator.isTableMissing(e, "warehouse"));
    Assert.assertFalse(TpccDataVerifyEvaluator.isPermissionDenied(e));
  }

  @Test
  public void testTableMissing_MySqlFamily() {
    SQLException e =
        new SQLException("Table 'resbench_demo.warehouse' doesn't exist", "42S02", 1146);
    Assert.assertTrue(TpccDataVerifyEvaluator.isTableMissing(e, "warehouse"));
  }

  @Test
  public void testTableMissing_MySqlErrorCodeOnly() {
    SQLException e = new SQLException("Table 'resbench_demo.district' doesn't exist", null, 1146);
    Assert.assertTrue(TpccDataVerifyEvaluator.isTableMissing(e, "district"));
  }

  @Test
  public void testTableMissing_PermissionDeniedIsNotMissing() {
    // 顺序反了就会把"看不到"谎报成"没有"
    SQLException e = new SQLException("permission denied for table warehouse", "42501");
    Assert.assertFalse(TpccDataVerifyEvaluator.isTableMissing(e, "warehouse"));
    Assert.assertTrue(TpccDataVerifyEvaluator.isPermissionDenied(e));
  }

  @Test
  public void testPermissionDenied_MySqlCodes() {
    Assert.assertTrue(TpccDataVerifyEvaluator.isPermissionDenied(
        new SQLException("Access denied for user 'x'@'%'", "42000", 1045)));
    Assert.assertTrue(TpccDataVerifyEvaluator.isPermissionDenied(
        new SQLException("SELECT command denied to user 'x'@'%'", "42000", 1142)));
  }

  @Test
  public void testConnectionFailureIsNeither() {
    SQLException e = new SQLException("Connection refused", "08006");
    Assert.assertFalse(TpccDataVerifyEvaluator.isTableMissing(e, "warehouse"));
    Assert.assertFalse(TpccDataVerifyEvaluator.isPermissionDenied(e));
  }

  @Test
  public void testTableMissing_MessageFallback() {
    SQLException e = new SQLException("ERROR: relation \"warehouse\" does not exist", (String) null);
    Assert.assertTrue(TpccDataVerifyEvaluator.isTableMissing(e, "warehouse"));
    // 消息里没提到目标表也没提 relation/table 时，不认（避免误吞别的错）
    Assert.assertFalse(TpccDataVerifyEvaluator.isTableMissing(
        new SQLException("syntax error at or near \"FROM\"", (String) null), "warehouse"));
  }

  // ------------------------------------------------------------ 配置仓数解析

  @Test
  public void testParseWarehouseCount() {
    Assert.assertEquals(Integer.valueOf(1000),
        TpccDataVerifyService.parseWarehouseCount(params("1000")));
    Assert.assertEquals(Integer.valueOf(7), TpccDataVerifyService.parseWarehouseCount(params(" 7 ")));
    // 空 / 缺键 / 非数字 / 非正数一律判为"无效"，不学 intValue 退化成 1
    Assert.assertNull(TpccDataVerifyService.parseWarehouseCount(params("")));
    Assert.assertNull(TpccDataVerifyService.parseWarehouseCount(params("   ")));
    Assert.assertNull(TpccDataVerifyService.parseWarehouseCount(params("abc")));
    Assert.assertNull(TpccDataVerifyService.parseWarehouseCount(params("0")));
    Assert.assertNull(TpccDataVerifyService.parseWarehouseCount(params("-5")));
    Assert.assertNull(TpccDataVerifyService.parseWarehouseCount(new LinkedHashMap<>()));
    Assert.assertNull(TpccDataVerifyService.parseWarehouseCount(null));
  }
}