package com.resbench.common.fault.channel;

import com.resbench.common.remote.RestfulCaller;

import java.net.ServerSocket;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * 通道的"没连上"那条路 —— 这一轮要跑真机，先在这里把最便宜的边界钉住：
 * key 归一化（不然同一个 agent 会开两条连接）、开关、连不上时既不抛异常也不干等。
 */
public class ChannelRegistryTest {

  @After
  public void clearProperty() {
    System.clearProperty("fault.channel.enabled");
  }

  /** 注入侧存 {@code http://ip:port}、resolveAgentHostPorts 返回裸 {@code ip:port} —— 必须是同一个 key */
  @Test
  public void testKey_NormalizesSchemeAndTrailingSlash() {
    String bare = ChannelRegistry.key("127.0.0.1:8000");
    Assert.assertEquals(bare, ChannelRegistry.key("http://127.0.0.1:8000"));
    Assert.assertEquals(bare, ChannelRegistry.key("ws://127.0.0.1:8000"));
    Assert.assertEquals(bare, ChannelRegistry.key("http://127.0.0.1:8000/"));
    Assert.assertEquals(bare, ChannelRegistry.key("  127.0.0.1:8000  "));
    Assert.assertEquals("127.0.0.1:8000", bare);
    // 不同 agent 不能撞在一起
    Assert.assertNotEquals(bare, ChannelRegistry.key("127.0.0.2:8000"));
    Assert.assertNotEquals(bare, ChannelRegistry.key("127.0.0.1:8001"));
    Assert.assertEquals("", ChannelRegistry.key(null));
  }

  @Test
  public void testEnabled_DefaultsOnAndCanBeTurnedOff() {
    Assert.assertTrue("默认开着 —— 这一轮就是来试它的", ChannelRegistry.enabled());
    System.setProperty("fault.channel.enabled", "false");
    Assert.assertFalse(ChannelRegistry.enabled());
    // 关掉时连通道都不该建：调用方据此直接走 HTTP，行为与改动之前逐字一致
    Assert.assertNull(ChannelRegistry.get("127.0.0.1:8000"));
  }

  /** 连不上时必须快速返回 false，不能让预热在每个死端点上干等满超时 */
  @Test
  public void testAwaitConnected_HandshakeFailure_ReturnsFalseFast() throws Exception {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    }

    AgentCommandChannel channel = ChannelRegistry.get("127.0.0.1:" + deadPort);
    Assert.assertNotNull(channel);

    long start = System.currentTimeMillis();
    Assert.assertFalse(channel.awaitConnected(3_000));
    long elapsed = System.currentTimeMillis() - start;
    Assert.assertTrue("握手失败应当立刻收尾，实际等了 " + elapsed + "ms", elapsed < 2_000);
    Assert.assertFalse(channel.isOpen());
    Assert.assertNotNull("断掉的原因要留下来，归档里才有得写", channel.getClosedReason());
    channel.close();
  }

  /** 通道没打开时 post 不许抛异常：它将退回调用方的 fallback 判定 */
  @Test
  public void testPost_NotOpen_ReportsWsTransportWithoutThrowing() throws Exception {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    }

    AgentCommandChannel channel = ChannelRegistry.get("127.0.0.1:" + deadPort);
    channel.awaitConnected(3_000);

    RestfulCaller.HttpResult result = channel.post("/inject", "{\"cmd\":\"echo\"}", 500);
    Assert.assertFalse(result.replied);
    Assert.assertEquals(0, result.status);
    // 这两笔是本次新加的判据：走的是通道、发送那一刻通道不是 OPEN。
    // 归档里没有它们就分不清"新建连接失败"和"通道上的别的问题"。
    Assert.assertEquals("ws", result.transport);
    Assert.assertEquals(Boolean.FALSE, result.channelAlive);
    Assert.assertNotNull(result.error);
    channel.close();
  }
}
