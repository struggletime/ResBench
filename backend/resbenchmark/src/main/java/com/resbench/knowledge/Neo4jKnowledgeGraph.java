package com.resbench.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * 故障知识图谱。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>neo4j</b>：{@link #connect} 包装官方 Driver，用一条只读 Cypher 查询编排中
 *       各故障之间的关系；</li>
 *   <li><b>in-memory</b>：{@link #inMemory} / {@link #fakeSeed} 用内存边集合模拟，
 *       供没有 Neo4j 实例时的演示和单测使用。</li>
 * </ul>
 *
 * <p>图谱 schema：
 * <pre>
 * (:Fault {nameKey: "faultinject.fault.xxx"})
 * (:Fault)-[:MUTEX]-&gt;(:Fault)
 * (:Fault)-[:DUPLICATE]-&gt;(:Fault)
 * </pre>
 */
public final class Neo4jKnowledgeGraph implements AutoCloseable {

  public static final String CYPHER_RELATIONS =
      "MATCH (a:Fault)-[r]->(b:Fault) "
          + "WHERE a.nameKey IN $nameKeys "
          + "AND b.nameKey IN $nameKeys "
          + "AND type(r) IN $relationTypes "
          + "RETURN a.nameKey AS fromKey, b.nameKey AS toKey, type(r) AS relationType";

  private static final List<String> RELATION_TYPES =
      List.of(RelationType.MUTEX.name(), RelationType.DUPLICATE.name());

  private final Driver driver;
  private final String database;
  private final List<FaultRelation> inMemoryRelations;

  private Neo4jKnowledgeGraph(Driver driver, String database, List<FaultRelation> inMemoryRelations) {
    this.driver = driver;
    this.database = database;
    this.inMemoryRelations = inMemoryRelations;
  }

  /**
   * 创建真实 Neo4j 图谱。Driver 是懒连接的，构造时不会访问服务器；
   * 需要显式调用 {@link #verifyConnectivity()} 或第一次查询时才会建连。
   */
  public static Neo4jKnowledgeGraph connect(
      String uri, String username, String password, String database, long connectionTimeoutMs) {
    Config config = Config.builder()
        .withConnectionTimeout(Math.max(connectionTimeoutMs, 1), TimeUnit.MILLISECONDS)
        .build();
    Driver driver = (username == null || username.isBlank())
        ? GraphDatabase.driver(uri, AuthTokens.none(), config)
        : GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    return new Neo4jKnowledgeGraph(driver, database, null);
  }

  public static Neo4jKnowledgeGraph inMemory(Collection<FaultRelation> relations) {
    List<FaultRelation> normalized = new ArrayList<>();
    if (relations != null) {
      for (FaultRelation relation : relations) {
        if (relation == null || relation.type() == null) {
          continue;
        }
        String from = normalizeNameKey(relation.fromNameKey());
        String to = normalizeNameKey(relation.toNameKey());
        if (from.isEmpty() || to.isEmpty()) {
          continue;
        }
        normalized.add(new FaultRelation(from, to, relation.type()));
      }
    }
    return new Neo4jKnowledgeGraph(null, null, Collections.unmodifiableList(normalized));
  }

  public static Neo4jKnowledgeGraph fakeSeed() {
    return inMemory(FakeKnowledgeGraphSeed.relations());
  }

  public boolean isInMemory() {
    return driver == null;
  }

  /** "neo4j" 或 "in-memory"。 */
  public String mode() {
    return isInMemory() ? "in-memory" : "neo4j";
  }

  /** 真实模式校验连通性；内存模式为空操作。 */
  public void verifyConnectivity() {
    if (driver != null) {
      driver.verifyConnectivity();
    }
  }

  /**
   * 查询给定 nameKey 集合内部的关系边（只读）。
   * 两个端点都在集合内的边才会返回；与集合外节点的边忽略。
   */
  public Set<FaultRelation> relationsAmong(Collection<String> rawNameKeys) {
    Set<String> nameKeys = new LinkedHashSet<>();
    if (rawNameKeys != null) {
      for (String raw : rawNameKeys) {
        String normalized = normalizeNameKey(raw);
        if (!normalized.isEmpty()) {
          nameKeys.add(normalized);
        }
      }
    }
    if (nameKeys.isEmpty()) {
      return Collections.emptySet();
    }

    if (isInMemory()) {
      Set<FaultRelation> result = new LinkedHashSet<>();
      for (FaultRelation relation : inMemoryRelations) {
        if (nameKeys.contains(relation.fromNameKey()) && nameKeys.contains(relation.toNameKey())) {
          result.add(relation);
        }
      }
      return result;
    }

    return queryNeo4j(nameKeys);
  }

  private Set<FaultRelation> queryNeo4j(Set<String> nameKeys) {
    Map<String, Object> parameters = Map.of(
        "nameKeys", new ArrayList<>(nameKeys),
        "relationTypes", RELATION_TYPES);

    try (Session session = openSession()) {
      return session.executeRead(tx -> {
        Set<FaultRelation> result = new LinkedHashSet<>();
        Result queryResult = tx.run(CYPHER_RELATIONS, parameters);
        while (queryResult.hasNext()) {
          Record record = queryResult.next();
          RelationType type = RelationType.fromName(record.get("relationType").asString(""));
          if (type == null) {
            continue;
          }
          String from = normalizeNameKey(record.get("fromKey").asString(""));
          String to = normalizeNameKey(record.get("toKey").asString(""));
          if (!from.isEmpty() && !to.isEmpty()) {
            result.add(new FaultRelation(from, to, type));
          }
        }
        return result;
      });
    }
  }

  private Session openSession() {
    if (database == null || database.isBlank()) {
      return driver.session();
    }
    return driver.session(SessionConfig.forDatabase(database));
  }

  /** nameKey 统一 trim + 小写，避免大小写/空格造成漏匹配。 */
  public static String normalizeNameKey(String nameKey) {
    return nameKey == null ? "" : nameKey.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public void close() {
    if (driver != null) {
      driver.close();
    }
  }
}
