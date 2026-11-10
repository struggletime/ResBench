package com.resbench.common.fault;

import org.junit.Assert;
import org.junit.Test;

/**
 * 往返限额的默认值单测：0 不再表示「无限等待」。
 */
public class AutoTimingConfigTest {

  @Test
  public void testDefaults_MatchRoundTripDefault() {
    Assert.assertEquals(
        FaultInjector.DEFAULT_ROUND_TRIP_TIMEOUT_MS, AutoTimingConfig.DEFAULT_INJECT_TIMEOUT_MS);
    Assert.assertEquals(
        FaultInjector.DEFAULT_ROUND_TRIP_TIMEOUT_MS, AutoTimingConfig.DEFAULT_RECOVER_TIMEOUT_MS);
  }

  @Test
  public void testSanitize_ZeroBecomesDefault() {
    AutoTimingConfig config = AutoTimingConfig.createDefault();
    config.setInjectTimeoutMs(0);
    config.setRecoverTimeoutMs(0);
    config.sanitize();
    // 0 曾经被当成「无限等」放行，正是那条路让注入命令能晚到 68 秒而无记录可查
    Assert.assertEquals(AutoTimingConfig.DEFAULT_INJECT_TIMEOUT_MS, config.getInjectTimeoutMs());
    Assert.assertEquals(AutoTimingConfig.DEFAULT_RECOVER_TIMEOUT_MS, config.getRecoverTimeoutMs());
  }

  @Test
  public void testSanitize_NegativeBecomesDefault() {
    AutoTimingConfig config = AutoTimingConfig.createDefault();
    config.setInjectTimeoutMs(-1);
    config.setRecoverTimeoutMs(-1);
    config.sanitize();
    Assert.assertEquals(AutoTimingConfig.DEFAULT_INJECT_TIMEOUT_MS, config.getInjectTimeoutMs());
    Assert.assertEquals(AutoTimingConfig.DEFAULT_RECOVER_TIMEOUT_MS, config.getRecoverTimeoutMs());
  }

  @Test
  public void testSanitize_PositiveIsKept() {
    AutoTimingConfig config = AutoTimingConfig.createDefault();
    config.setInjectTimeoutMs(7000);
    config.setRecoverTimeoutMs(250);
    config.sanitize();
    Assert.assertEquals(7000, config.getInjectTimeoutMs());
    Assert.assertEquals(250, config.getRecoverTimeoutMs());
  }
}
