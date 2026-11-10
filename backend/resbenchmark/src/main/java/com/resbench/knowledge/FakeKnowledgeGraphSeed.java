package com.resbench.knowledge;

import java.util.List;

/**
 * 未配置 Neo4j 时使用的内存示例图谱。
 *
 * <p>这里只放两条能演示「通过 / 不通过」的最小关系，真实关系以 Neo4j 为准。
 * 对应的 Cypher 种子在 {@code scripts/neo4j/seed-fault-relations.cypher}。
 */
public final class FakeKnowledgeGraphSeed {

  private FakeKnowledgeGraphSeed() {
  }

  public static List<FaultRelation> relations() {
    return List.of(
        new FaultRelation(
            "faultinject.fault.connection.surge",
            "faultinject.fault.connection.limit.exceeded",
            RelationType.MUTEX),
        new FaultRelation(
            "faultinject.fault.sql.stack_overflow.sql_depth",
            "faultinject.fault.sql.stack_overflow.view_nest",
            RelationType.DUPLICATE));
  }
}
