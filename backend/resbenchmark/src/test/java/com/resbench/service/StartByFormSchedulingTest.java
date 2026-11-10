package com.resbench.service;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.resbench.pojo.dto.StartByFormRequest;

/**
 * fixed-time 排程的纯单元测试：不启动 Spring，也不连数据库。
 *
 * <p>覆盖三件事：planningSeconds 绝对时刻直接生效、未写时保持原有组内偏移/组间 +30、
 * 绝对时刻组不会把后续相对组的基准抬高。
 */
public class StartByFormSchedulingTest {

  private static final String CPU = "faultinject.fault.cpu.load";

  @Test
  public void absolutePlanningOverridesGroupArithmetic() throws Exception {
    StartByFormRequest request = baseRequest();
    request.getFaults().add(fault(CPU, "g1", null, 32, 30));
    request.getFaults().add(fault(CPU, "g2", null, 93, 30));

    assertEquals(List.of(32, 93), planings(request));
  }

  @Test
  public void relativeDelayKeepsLegacyFixedTimeBehavior() throws Exception {
    StartByFormRequest request = baseRequest();
    request.getFaults().add(fault(CPU, "g1", 0, null, 30));
    request.getFaults().add(fault(CPU, "g2", 5, null, 30));

    // 组 1: 60；组 1 结束 90，组 2 基准 120，再 +5 → 125
    assertEquals(List.of(60, 125), planings(request));
  }

  @Test
  public void absoluteGroupDoesNotPushLaterRelativeGroupEarlier() throws Exception {
    StartByFormRequest request = baseRequest();
    request.getFaults().add(fault(CPU, "g1", null, 30, 30));
    request.getFaults().add(fault(CPU, "g2", 0, null, 30));

    // 绝对组 30..60 不影响初始基准 60；下一组从 max(60, 60+30)=90 开始
    assertEquals(List.of(30, 90), planings(request));
  }

  private static StartByFormRequest baseRequest() {
    StartByFormRequest request = new StartByFormRequest();
    request.setDbType("postgresql");
    request.setAutoTimingMode("fixed-time");
    request.getDatabase().setDeploymentType("standalone");
    StartByFormRequest.StandaloneDatabase db = request.getDatabase().getStandalone();
    db.setIp("127.0.0.1");
    db.setPort("5432");
    db.setUsername("u");
    db.setPassword("p");
    return request;
  }

  @Test
  public void legacyThreeBatchMatrixStillSchedulesAt60_150_240() throws Exception {
    StartByFormRequest request = baseRequest();
    request.getFaults().add(fault(CPU, "g1", 0, null, 60));
    request.getFaults().add(fault(CPU, "g2", 0, null, 60));
    request.getFaults().add(fault(CPU, "g3", 0, null, 60));

    // 现有 matrix-*.yaml 的典型形态：3 批、每批 60s，批间 30s
    assertEquals(List.of(60, 150, 240), planings(request));
  }

  private static StartByFormRequest.FaultItem fault(
      String nameKey, String groupKey, Integer delaySeconds, Integer planningSeconds,
      int durationSeconds) {
    StartByFormRequest.FaultItem item = new StartByFormRequest.FaultItem();
    item.setNameKey(nameKey);
    item.setGroupKey(groupKey);
    item.setDelaySeconds(delaySeconds);
    item.setPlanningSeconds(planningSeconds);
    item.getParams().put("duration", String.valueOf(durationSeconds));
    return item;
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> planings(StartByFormRequest request) throws Exception {
    FaultCatalogLoader loader = new FaultCatalogLoader();
    loader.load();
    // 本用例只用 OS 级 chaosblade 故障，resolverFor 只创建惰性解析器，不会连库。
    StartByFormService service =
        new StartByFormService(loader, new FileFaultTargetService(null, null));

    Method build = StartByFormService.class.getDeclaredMethod(
        "buildScheduledFaults", StartByFormRequest.class);
    build.setAccessible(true);
    List<Object> items = (List<Object>) build.invoke(service, request);

    List<Integer> result = new ArrayList<>();
    for (Object item : items) {
      Method planning = item.getClass().getDeclaredMethod("planningSeconds");
      planning.setAccessible(true);
      result.add((Integer) planning.invoke(item));
    }
    return result;
  }
}
