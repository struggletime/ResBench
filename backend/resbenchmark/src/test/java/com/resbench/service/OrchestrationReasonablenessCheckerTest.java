package com.resbench.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.resbench.knowledge.FaultRelation;
import com.resbench.knowledge.Neo4jKnowledgeGraph;
import com.resbench.knowledge.Neo4jKnowledgeGraphProvider;
import com.resbench.knowledge.Neo4jProperties;
import com.resbench.knowledge.RelationType;
import com.resbench.pojo.dto.OrchestrationCheckResult;
import com.resbench.pojo.dto.StartByFormRequest;

/**
 * 编排合理性检查器的纯单元测试：不启动 Spring，不连接真实 Neo4j。
 */
public class OrchestrationReasonablenessCheckerTest {

  private static final String SURGE = "faultinject.fault.connection.surge";
  private static final String LIMIT = "faultinject.fault.connection.limit.exceeded";
  private static final String SQL_DEPTH = "faultinject.fault.sql.stack_overflow.sql_depth";
  private static final String VIEW_NEST = "faultinject.fault.sql.stack_overflow.view_nest";

  private final OrchestrationReasonablenessChecker checker =
      new OrchestrationReasonablenessChecker(null, null);

  @Test
  public void singleFaultIsReasonable() {
    OrchestrationCheckResult result = checker.check(
        Neo4jKnowledgeGraph.fakeSeed(), List.of(fault(SURGE, null)));

    assertTrue(result.isChecked());
    assertTrue(result.isReasonable());
    assertEquals(0, result.getCheckedGroups());
    assertEquals("in-memory", result.getMode());
    assertTrue(result.getViolations().isEmpty());
  }

  @Test
  public void sameNameKeyTwiceInSameGroupIsDuplicate() {
    OrchestrationCheckResult result = checker.check(
        Neo4jKnowledgeGraph.fakeSeed(),
        List.of(fault(SURGE, "g1"), fault(SURGE, "g1")));

    assertTrue(result.isChecked());
    assertFalse(result.isReasonable());
    assertEquals(1, result.getCheckedGroups());
    assertEquals(1, result.getViolations().size());
    assertEquals("DUPLICATE", result.getViolations().get(0).getType());
    assertEquals("g1", result.getViolations().get(0).getGroupKey());
    assertEquals(List.of(SURGE, SURGE), result.getViolations().get(0).getNameKeys());
    assertTrue(result.getViolations().get(0).getMessage().contains("重复选择"));
  }

  @Test
  public void mutexPairInSameGroupIsRejected() {
    OrchestrationCheckResult result = checker.check(
        Neo4jKnowledgeGraph.fakeSeed(),
        List.of(fault(SURGE, "g1"), fault(LIMIT, "g1")));

    assertTrue(result.isChecked());
    assertFalse(result.isReasonable());
    assertEquals(1, result.getCheckedGroups());
    assertEquals(1, result.getViolations().size());
    assertEquals("MUTEX", result.getViolations().get(0).getType());
    assertTrue(result.getViolations().get(0).getMessage().contains("互斥"));
  }

  @Test
  public void duplicateEdgeInSameGroupIsRejected() {
    OrchestrationCheckResult result = checker.check(
        Neo4jKnowledgeGraph.fakeSeed(),
        List.of(fault(SQL_DEPTH, "g1"), fault(VIEW_NEST, "g1")));

    assertTrue(result.isChecked());
    assertFalse(result.isReasonable());
    assertEquals(1, result.getViolations().size());
    assertEquals("DUPLICATE", result.getViolations().get(0).getType());
    assertTrue(result.getViolations().get(0).getMessage().contains("语义重复"));
  }

  @Test
  public void mutexPairInDifferentGroupsIsReasonable() {
    // 不同 groupKey = 串行注入，不同时
    OrchestrationCheckResult result = checker.check(
        Neo4jKnowledgeGraph.fakeSeed(),
        List.of(fault(SURGE, "g1"), fault(LIMIT, "g2")));

    assertTrue(result.isChecked());
    assertTrue(result.isReasonable());
    assertEquals(0, result.getCheckedGroups());
    assertTrue(result.getViolations().isEmpty());
  }

  @Test
  public void blankGroupKeysAreNeverSimultaneous() {
    OrchestrationCheckResult result = checker.check(
        Neo4jKnowledgeGraph.fakeSeed(),
        List.of(fault(SURGE, null), fault(LIMIT, "  ")));

    assertTrue(result.isChecked());
    assertTrue(result.isReasonable());
    assertEquals(0, result.getCheckedGroups());
  }

  @Test
  public void threeFaultGroupIsCheckedPairwise() {
    String a = "fault.a";
    String b = "fault.b";
    String c = "fault.c";
    Neo4jKnowledgeGraph graph = Neo4jKnowledgeGraph.inMemory(List.of(
        new FaultRelation(a, b, RelationType.MUTEX),
        new FaultRelation(b, c, RelationType.DUPLICATE)));

    OrchestrationCheckResult result = checker.check(
        graph, List.of(fault(a, "g1"), fault(b, "g1"), fault(c, "g1")));

    assertFalse(result.isReasonable());
    assertEquals(1, result.getCheckedGroups());
    assertEquals(2, result.getViolations().size());
    assertEquals("MUTEX", result.getViolations().get(0).getType());
    assertEquals(List.of(a, b), result.getViolations().get(0).getNameKeys());
    assertEquals("DUPLICATE", result.getViolations().get(1).getType());
    assertEquals(List.of(b, c), result.getViolations().get(1).getNameKeys());
  }

  @Test
  public void mixedGroupsOnlyCheckMultiFaultGroups() {
    OrchestrationCheckResult result = checker.check(
        Neo4jKnowledgeGraph.fakeSeed(),
        List.of(fault(SURGE, "g1"), fault(LIMIT, "g1"), fault(SQL_DEPTH, null)));

    assertFalse(result.isReasonable());
    assertEquals(1, result.getCheckedGroups());
    assertEquals(1, result.getViolations().size());
    assertEquals("g1", result.getViolations().get(0).getGroupKey());
  }

  @Test
  public void fakeSeedContainsMutexAndDuplicateEdges() {
    Neo4jKnowledgeGraph graph = Neo4jKnowledgeGraph.fakeSeed();
    var relations = graph.relationsAmong(List.of(SURGE, LIMIT, SQL_DEPTH, VIEW_NEST));

    assertEquals(2, relations.size());
    assertTrue(relations.stream()
        .anyMatch(r -> r.type() == RelationType.MUTEX));
    assertTrue(relations.stream()
        .anyMatch(r -> r.type() == RelationType.DUPLICATE));
  }

  @Test
  public void realModeConstructionDoesNotConnect() {
    Neo4jKnowledgeGraph graph = Neo4jKnowledgeGraph.connect(
        "bolt://127.0.0.1:1", "neo4j", "secret", "neo4j", 500);
    try {
      assertFalse(graph.isInMemory());
      assertEquals("neo4j", graph.mode());
    } finally {
      graph.close();
    }
  }

  @Test
  public void singleFaultDoesNotTouchBrokenGraph() {
    Neo4jProperties properties = new Neo4jProperties();
    properties.setUri("bolt://127.0.0.1:1");
    properties.setConnectionTimeoutMs(200);
    OrchestrationReasonablenessChecker springChecker =
        new OrchestrationReasonablenessChecker(new Neo4jKnowledgeGraphProvider(properties), null);

    // 只有单个故障时不需要图谱，不能因为 Neo4j 不可用而失败
    OrchestrationCheckResult result = springChecker.check(List.of(fault(SURGE, "g1")));

    assertTrue(result.isChecked());
    assertTrue(result.isReasonable());
    assertEquals("none", result.getMode());
    assertEquals(0, result.getCheckedGroups());
  }

  @Test
  public void nullGraphWithSimultaneousFaultsReportsUnavailable() {
    OrchestrationCheckResult result = checker.check(
        null, List.of(fault(SURGE, "g1"), fault(LIMIT, "g1")));

    assertFalse(result.isChecked());
    assertFalse(result.isReasonable());
    assertEquals("unavailable", result.getMode());
    assertTrue(result.getMessage().contains("知识图谱不可用"));
  }

  private static StartByFormRequest.FaultItem fault(String nameKey, String groupKey) {
    StartByFormRequest.FaultItem item = new StartByFormRequest.FaultItem();
    item.setNameKey(nameKey);
    item.setGroupKey(groupKey);
    return item;
  }
}
