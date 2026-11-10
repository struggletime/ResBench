package com.resbench.service.probe;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 探针共用的几个小工具：都是"连库问一句话"、"拼路径"、"拼 IN 列表"这种每个库都一样的活。
 *
 * <p>放在这里只是为了不让第二个探针把第一个的实现再抄一遍 —— <b>不含任何库特有的判断或文案</b>，
 * 那些仍然各归各的探针（见 {@link FileFaultTargetProbe} 的分工说明）。
 */
final class ProbeSupport {

  /**
   * 标识符形状的白名单。表名本身来自仓库里的 DDL 资源（可信），这里再兜一道，
   * 保证拼进 SQL 的字符串里不可能有引号/分号。
   */
  static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private ProbeSupport() {}

  /** 查一个标量（第一行第一列）；没有行就返回 null。 */
  static String queryScalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  /** 把表名拼成 SQL 里的字符串字面量列表：{@code 'a','b'}；空名与形状可疑的名字直接丢掉。 */
  static String quotedIdentifierList(List<String> names) {
    StringBuilder builder = new StringBuilder();
    if (names == null) {
      return "";
    }
    for (String name : names) {
      if (!isSafeIdentifier(name)) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append('\'').append(name).append('\'');
    }
    return builder.toString();
  }

  static boolean isSafeIdentifier(String name) {
    return StringUtils.isNotBlank(name) && SAFE_IDENTIFIER.matcher(name).matches();
  }

  static String join(String directory, String relative) {
    return directory + "/" + relative;
  }

  /** 去掉尾部的斜杠（PostgreSQL 的 {@code SHOW data_directory} 与 MySQL 的 {@code @@datadir} 都可能带）。 */
  static String trimTrailingSlash(String path) {
    String value = StringUtils.trimToEmpty(path);
    while (value.length() > 1 && value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }
}
