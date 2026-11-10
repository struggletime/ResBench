package com.resbench.util;

import java.util.Locale;

/**
 * 数据库类型归一化。
 *
 * <p>同一类数据库在不同来源里拼写不一致：引擎启动参数写 {@code postgresql}，
 * 而基准驱动写进 summary 的 {@code DBMS Type} 是 {@code POSTGRES}；
 * 前端筛选项既有内置的 {@code postgresql}，也有从归档里收集到的 {@code postgres}。
 * 若按字符串等值匹配，选 PostgreSQL 会把 DBMS Type=POSTGRES 的历史运行全部过滤掉。
 *
 * <p>这里把已知别名收敛到同一个「族」键，供历史检索的服务端与本地过滤共用。
 */
public final class DbTypeUtils {

  private DbTypeUtils() {
  }

  /** 归一化成族键；忽略大小写、空格与分隔符。空值返回空串。 */
  public static String family(String raw) {
    if (raw == null) {
      return "";
    }
    String key = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    if (key.isEmpty()) {
      return "";
    }
    // 用前缀匹配，兼容 "PostgreSQL 10.21 (Debian …)" 这类把版本串当类型传进来的旧数据
    if (key.startsWith("postgres") || key.startsWith("pgsql") || "pg".equals(key)) {
      return "postgresql";
    }
    if (key.startsWith("sqlserver") || key.startsWith("mssql")) {
      return "sqlserver";
    }
    if (key.startsWith("kingbase")) {
      return "kingbase";
    }
    if ("dm".equals(key) || key.startsWith("dameng")) {
      return "dm";
    }
    return key;
  }

  /** 两个类型串是否属于同一族；比较基准（right）为空时视为不限制。 */
  public static boolean sameFamily(String left, String right) {
    String wanted = family(right);
    return wanted.isEmpty() || wanted.equals(family(left));
  }
}
