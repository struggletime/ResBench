package com.resbench.knowledge;

/**
 * 故障知识图谱中的关系类型。
 *
 * <p>关系是无向语义：MUTEX / DUPLICATE 都表示两个故障不能在同一组中同时注入，
 * 存图时只需要存一条边（任一方向）。
 */
public enum RelationType {
  /** 互斥：两个故障不能同时注入。 */
  MUTEX,
  /** 重复：两个不同 nameKey 表达同一种故障效果，同时注入没有意义。 */
  DUPLICATE;

  /** 图谱中的关系名转枚举；未知关系返回 null（检查器忽略未知类型）。 */
  public static RelationType fromName(String name) {
    if (name == null) {
      return null;
    }
    try {
      return valueOf(name.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
