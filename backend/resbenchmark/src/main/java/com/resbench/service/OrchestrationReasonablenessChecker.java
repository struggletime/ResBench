package com.resbench.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.resbench.knowledge.FaultRelation;
import com.resbench.knowledge.Neo4jKnowledgeGraph;
import com.resbench.knowledge.Neo4jKnowledgeGraphProvider;
import com.resbench.knowledge.RelationType;
import com.resbench.pojo.dto.OrchestrationCheckResult;
import com.resbench.pojo.dto.OrchestrationCheckResult.Violation;
import com.resbench.pojo.dto.StartByFormRequest;

/**
 * 故障编排合理性检查器。
 *
 * <p>只有「同时注入」的故障之间才可能冲突：与 {@code StartByFormService} 的调度语义一致，
 * 同一个非空 {@code groupKey} 的故障为一组、同时注入；空 {@code groupKey} 的故障各自独立。
 * 因此只检查组内成员数 >= 2 的组合，不同组之间（串行注入）不做判定。
 *
 * <p>核心函数签名：
 * {@code check(Neo4jKnowledgeGraph graph, List<FaultItem> faults)}
 * ——第一个参数是知识图谱，第二个参数是一组故障编排。
 */
@Service
public class OrchestrationReasonablenessChecker {

  private static final String MODE_NONE = "none";

  private final Neo4jKnowledgeGraphProvider graphProvider;

  private final FaultCatalogLoader faultCatalogLoader;

  public OrchestrationReasonablenessChecker(
      Neo4jKnowledgeGraphProvider graphProvider, FaultCatalogLoader faultCatalogLoader) {
    this.graphProvider = graphProvider;
    this.faultCatalogLoader = faultCatalogLoader;
  }

  /**
   * Spring 入口：从 provider 取当前知识图谱后检查。
   * 没有需要同时性检查的组时，直接通过，不触发 Neo4j 连接。
   */
  public OrchestrationCheckResult check(List<StartByFormRequest.FaultItem> faults) {
    Map<String, List<StartByFormRequest.FaultItem>> groups = groupFaults(faults);
    if (countCheckedGroups(groups) == 0) {
      OrchestrationCheckResult result = OrchestrationCheckResult.pass(
          MODE_NONE, "没有同时注入的故障，编排合理。");
      result.setCheckedGroups(0);
      return result;
    }

    Neo4jKnowledgeGraph graph;
    try {
      graph = graphProvider.get();
    } catch (RuntimeException e) {
      return OrchestrationCheckResult.error(
          "unavailable", "编排校验失败：知识图谱不可用（" + rootMessage(e) + "）");
    }
    return check(graph, faults);
  }

  /**
   * 核心函数：用给定的知识图谱检查一组故障编排是否合理。
   *
   * @param graph  知识图谱；真实 Neo4j 或内存假图谱
   * @param faults 一组故障编排（与 startByForm 的 faults 相同结构）
   */
  public OrchestrationCheckResult check(
      Neo4jKnowledgeGraph graph, List<StartByFormRequest.FaultItem> faults) {
    Map<String, List<StartByFormRequest.FaultItem>> groups = groupFaults(faults);
    int checkedGroups = countCheckedGroups(groups);
    if (checkedGroups == 0) {
      OrchestrationCheckResult result = OrchestrationCheckResult.pass(
          graph == null ? MODE_NONE : graph.mode(), "没有同时注入的故障，编排合理。");
      result.setCheckedGroups(0);
      return result;
    }
    if (graph == null) {
      return OrchestrationCheckResult.error("unavailable", "编排校验失败：知识图谱不可用。");
    }

    Set<String> nameKeys = new LinkedHashSet<>();
    for (List<StartByFormRequest.FaultItem> group : groups.values()) {
      if (group.size() < 2) {
        continue;
      }
      for (StartByFormRequest.FaultItem item : group) {
        nameKeys.add(Neo4jKnowledgeGraph.normalizeNameKey(item.getNameKey()));
      }
    }

    Set<FaultRelation> relations;
    try {
      relations = graph.relationsAmong(nameKeys);
    } catch (RuntimeException e) {
      return OrchestrationCheckResult.error(
          graph.mode(), "编排校验失败：知识图谱查询失败（" + rootMessage(e) + "）");
    }

    Map<String, EnumSet<RelationType>> relationIndex = indexRelations(relations);
    List<Violation> violations = new ArrayList<>();

    for (Map.Entry<String, List<StartByFormRequest.FaultItem>> entry : groups.entrySet()) {
      List<StartByFormRequest.FaultItem> group = entry.getValue();
      if (group.size() < 2) {
        continue;
      }
      String groupKey = entry.getKey();

      // 同一组里同一个 nameKey 出现多次：重复注入同一个故障
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (StartByFormRequest.FaultItem item : group) {
        String key = Neo4jKnowledgeGraph.normalizeNameKey(item.getNameKey());
        counts.merge(key, 1, Integer::sum);
      }
      for (Map.Entry<String, Integer> count : counts.entrySet()) {
        if (count.getValue() < 2) {
          continue;
        }
        String name = displayName(count.getKey());
        violations.add(new Violation(
            RelationType.DUPLICATE.name(),
            groupKey,
            List.of(count.getKey(), count.getKey()),
            "同一组合故障中重复选择了故障「" + name + "」共 " + count.getValue() + " 次"));
      }

      // 组内两两查图谱关系
      for (int i = 0; i < group.size(); i++) {
        String left = Neo4jKnowledgeGraph.normalizeNameKey(group.get(i).getNameKey());
        for (int j = i + 1; j < group.size(); j++) {
          String right = Neo4jKnowledgeGraph.normalizeNameKey(group.get(j).getNameKey());
          if (left.equals(right)) {
            continue;
          }
          EnumSet<RelationType> types = relationIndex.get(pairKey(left, right));
          if (types == null) {
            continue;
          }
          if (types.contains(RelationType.MUTEX)) {
            violations.add(new Violation(
                RelationType.MUTEX.name(),
                groupKey,
                List.of(left, right),
                "故障「" + displayName(left) + "」与「" + displayName(right)
                    + "」互斥，不能在同一组中同时注入"));
          }
          if (types.contains(RelationType.DUPLICATE)) {
            violations.add(new Violation(
                RelationType.DUPLICATE.name(),
                groupKey,
                List.of(left, right),
                "故障「" + displayName(left) + "」与「" + displayName(right)
                    + "」语义重复，不能在同一组中同时注入"));
          }
        }
      }
    }

    OrchestrationCheckResult result;
    if (violations.isEmpty()) {
      result = OrchestrationCheckResult.pass(
          graph.mode(), "编排校验通过：所有同时注入的故障之间不存在互斥或重复关系。");
    } else {
      result = OrchestrationCheckResult.fail(
          graph.mode(),
          "编排校验未通过：发现 " + violations.size() + " 处冲突，请调整组合故障。",
          violations);
    }
    result.setCheckedGroups(checkedGroups);
    return result;
  }

  /**
   * 按同时注入语义分组：非空 groupKey 同组；空 groupKey 每个故障独立。
   * 返回的 LinkedHashMap 保持编排中的出现顺序，保证校验结果顺序稳定。
   */
  private Map<String, List<StartByFormRequest.FaultItem>> groupFaults(
      List<StartByFormRequest.FaultItem> faults) {
    Map<String, List<StartByFormRequest.FaultItem>> groups = new LinkedHashMap<>();
    if (faults == null) {
      return groups;
    }
    int index = 0;
    for (StartByFormRequest.FaultItem item : faults) {
      if (item == null || item.getNameKey() == null || item.getNameKey().isBlank()) {
        continue;
      }
      String groupKey = item.getGroupKey();
      if (groupKey == null || groupKey.isBlank()) {
        // 空 groupKey 在引擎里会被拆成独立的调度组，永远不会同时注入
        groupKey = "__single_" + index;
      }
      index++;
      groups.computeIfAbsent(groupKey, key -> new ArrayList<>()).add(item);
    }
    return groups;
  }

  private int countCheckedGroups(Map<String, List<StartByFormRequest.FaultItem>> groups) {
    int count = 0;
    for (List<StartByFormRequest.FaultItem> group : groups.values()) {
      if (group.size() >= 2) {
        count++;
      }
    }
    return count;
  }

  /** 把关系边按无向 pair 归并，避免存边方向影响判定。 */
  private Map<String, EnumSet<RelationType>> indexRelations(Set<FaultRelation> relations) {
    Map<String, EnumSet<RelationType>> index = new HashMap<>();
    if (relations == null) {
      return index;
    }
    for (FaultRelation relation : relations) {
      if (relation == null || relation.type() == null) {
        continue;
      }
      index.computeIfAbsent(
              pairKey(relation.fromNameKey(), relation.toNameKey()),
              key -> EnumSet.noneOf(RelationType.class))
          .add(relation.type());
    }
    return index;
  }

  private static String pairKey(String left, String right) {
    String a = Neo4jKnowledgeGraph.normalizeNameKey(left);
    String b = Neo4jKnowledgeGraph.normalizeNameKey(right);
    return a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
  }

  private String displayName(String nameKey) {
    if (faultCatalogLoader == null) {
      return nameKey;
    }
    try {
      return faultCatalogLoader.get(nameKey)
          .map(FaultCatalogLoader.FaultDefinition::plainName)
          .filter(name -> name != null && !name.isBlank())
          .orElse(nameKey);
    } catch (RuntimeException e) {
      return nameKey;
    }
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null || message.isBlank()
        ? current.getClass().getSimpleName()
        : message;
  }
}
