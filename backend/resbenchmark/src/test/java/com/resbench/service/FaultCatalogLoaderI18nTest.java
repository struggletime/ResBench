package com.resbench.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * v2.2 yaml 多语言（{zh,en} 对象 → JSON 串 → 前端解析 / 后端 plainName）单测。
 */
public class FaultCatalogLoaderI18nTest {

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  public void testI18n_NullUsesFallback() {
    Assert.assertEquals("fallback-文本", FaultCatalogLoader.i18n(null, "fallback-文本"));
  }

  @Test
  public void testI18n_PlainStringKeptAsIs() {
    Assert.assertEquals("并发连接数", FaultCatalogLoader.i18n("并发连接数", "fallback"));
  }

  @Test
  public void testI18n_MapSerializedToJson() {
    String json = FaultCatalogLoader.i18n(map("zh", "连接风暴", "en", "Connection Storm"), "fallback");
    Assert.assertTrue(json.startsWith("{"));
    Assert.assertTrue(json.contains("连接风暴"));
    Assert.assertTrue(json.contains("Connection Storm"));
  }

  @Test
  public void testPlain_TextPassThrough() {
    Assert.assertEquals("Connection Storm", FaultCatalogLoader.plain("Connection Storm"));
    Assert.assertNull(FaultCatalogLoader.plain(null));
    // plain 原样透传（不 trim）：null、空白、普通文本都保持原样
    Assert.assertEquals(" hello ", FaultCatalogLoader.plain(" hello "));
  }

  @Test
  public void testPlain_JsonTakesZhFirst() {
    String json = FaultCatalogLoader.i18n(map("zh", "并发连接数", "en", "Concurrent Connections"), "");
    Assert.assertEquals("并发连接数", FaultCatalogLoader.plain(json));
  }

  @Test
  public void testPlain_JsonFallsBackToEn() {
    String json = FaultCatalogLoader.i18n(map("en", "Concurrent Connections"), "");
    Assert.assertEquals("Concurrent Connections", FaultCatalogLoader.plain(json));
  }

  @Test
  public void testPlain_InvalidJsonPassThrough() {
    Assert.assertEquals("{not json", FaultCatalogLoader.plain("{not json"));
  }
}