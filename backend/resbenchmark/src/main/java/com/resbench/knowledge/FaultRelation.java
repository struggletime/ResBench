package com.resbench.knowledge;

/**
 * 知识图谱中的一条故障关系边。
 *
 * @param fromNameKey 起点故障 nameKey（已归一化）
 * @param toNameKey   终点故障 nameKey（已归一化）
 * @param type        关系类型
 */
public record FaultRelation(String fromNameKey, String toNameKey, RelationType type) {
}
