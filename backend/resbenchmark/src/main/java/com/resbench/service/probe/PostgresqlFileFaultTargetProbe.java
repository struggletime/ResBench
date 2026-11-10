package com.resbench.service.probe;

import com.resbench.pojo.dto.FileFaultTargetReport;
import com.resbench.pojo.dto.FileFaultTargetReport.RelationTarget;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 的 file 故障目标探针：靠库自己的 catalog 回答"文件在哪、多大、谁在被读"。
 *
 * <p>用到的东西：{@code SHOW data_directory}、{@code pg_relation_filepath}（相对 PGDATA 的路径）、
 * {@code pg_relation_size}（分段数由它算）、{@code pg_stat_user_tables} / {@code pg_stat_user_indexes}
 * （热度）。<b>全是只读查询，不碰容器文件系统</b> —— 引擎看不到容器的盘。
 *
 * <p>热度统计在库重启后会清零，这时不装懂：改按体量排序，并在 {@code statsFresh=false} 里说清楚。
 */
@Component
public class PostgresqlFileFaultTargetProbe implements FileFaultTargetProbe {

  /** PG 每个段文件 1 GiB：第 0 段没有后缀，第 i 段是 {@code <path>.i}。 */
  private static final long SEGMENT_BYTES = 1L << 30;

  @Override
  public Set<String> supportedDbTypes() {
    // openGauss / GaussDB 与 PG 同源（同样有 pg_relation_filepath / pg_stat_user_tables /
    // SHOW data_directory）。补测通过后把 "opengauss" / "gaussdb" 加到这个集合里即可 ——
    // 不用改任何别的代码，断言、分组、文案、@auto: 哨兵全部照旧。
    return Set.of("postgresql");
  }

  @Override
  public FileFaultTargetReport probe(Connection connection, String workload, List<String> tableWhitelist)
      throws SQLException {
    String dataDirectory =
        ProbeSupport.trimTrailingSlash(ProbeSupport.queryScalar(connection, "SHOW data_directory"));

    String databaseName = null;
    String databaseId = null;
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
            "SELECT current_database(), oid FROM pg_database WHERE datname = current_database()")) {
      if (rs.next()) {
        databaseName = rs.getString(1);
        databaseId = rs.getString(2);
      }
    }

    String inList = ProbeSupport.quotedIdentifierList(tableWhitelist);
    List<RelationTarget> relations = new ArrayList<>();
    int skippedEmpty = 0;

    if (!inList.isEmpty()) {
      skippedEmpty += collect(relations,
          connection,
          "SELECT c.relname, pg_relation_filepath(c.oid) AS p, pg_relation_size(c.oid) AS b,"
              + " COALESCE(s.seq_scan, 0) + COALESCE(s.idx_scan, 0) AS scans"
              + " FROM pg_class c LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid"
              + " WHERE c.relkind = 'r' AND c.relname IN (" + inList + ")",
          "table", dataDirectory);

      skippedEmpty += collect(relations,
          connection,
          "SELECT ic.relname AS idx_name, i.relname AS owner_table,"
              + " pg_relation_filepath(i.indexrelid) AS p, pg_relation_size(i.indexrelid) AS b,"
              + " COALESCE(i.idx_scan, 0) AS scans"
              + " FROM pg_stat_user_indexes i JOIN pg_class ic ON ic.oid = i.indexrelid"
              + " WHERE i.relname IN (" + inList + ")",
          "index", dataDirectory);
    }

    boolean statsFresh = relations.stream().anyMatch(target -> target.scans() > 0);

    List<String> notes = new ArrayList<>();
    if (!statsFresh) {
      notes.add("热度统计已清零（库重启过或刚建好）：清单按体量排序，不代表负载真的在读它");
    }
    if (skippedEmpty > 0) {
      notes.add(skippedEmpty + " 个关系当前还没有数据文件（0 字节），未列入");
    }

    return new FileFaultTargetReport(
        databaseName,
        databaseId,
        dataDirectory,
        // 这个库自己的目录：库文件都放在 <PGDATA>/base/<oid>/ 下
        StringUtils.isBlank(databaseId) ? null : ProbeSupport.join(dataDirectory, "base/" + databaseId),
        dataDirectory, // move 的落点取 PGDATA：它必然是 base/<oid> 的父目录 → 同一个文件系统 → rename(2)
        statsFresh,
        statsFresh ? "scans" : "bytes",
        relations,
        notes);
  }

  /**
   * 跑一条查询并把每行变成 {@link RelationTarget}。
   *
   * <p>查询列顺序固定为：名字、[索引多一列 owner]，相对路径、字节数、扫描次数。
   *
   * @return 被跳过的空关系数（0 字节 = 还没有数据文件，没什么可打的）
   */
  private static int collect(List<RelationTarget> out, Connection connection, String sql,
      String kind, String dataDirectory) throws SQLException {
    boolean index = "index".equals(kind);
    int skipped = 0;
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        int cursor = 1;
        String name = rs.getString(cursor++);
        String ownerTable = index ? rs.getString(cursor++) : null;
        String relpath = rs.getString(cursor++);
        long bytes = rs.getLong(cursor++);
        long scans = rs.getLong(cursor);
        if (StringUtils.isBlank(relpath) || bytes <= 0 || !ProbeSupport.isSafeIdentifier(name)) {
          skipped++;
          continue;
        }
        out.add(toTarget(name, kind, ownerTable, relpath, bytes, scans, dataDirectory));
      }
    }
    return skipped;
  }

  private static RelationTarget toTarget(String name, String kind, String ownerTable,
      String relpath, long bytes, long scans, String dataDirectory) {
    int segments = (int) Math.max(1L, (bytes + SEGMENT_BYTES - 1) / SEGMENT_BYTES);
    String first = ProbeSupport.join(dataDirectory, segmentPath(relpath, 0));
    String last = ProbeSupport.join(dataDirectory, segmentPath(relpath, segments - 1));
    String mid = segments >= 2 ? ProbeSupport.join(dataDirectory, segmentPath(relpath, 1)) : null;
    return new RelationTarget(name, kind, ownerTable, first, last, mid, bytes, scans, segments);
  }

  /** 第 0 段没有后缀；其余是 {@code <relpath>.<i>}。 */
  private static String segmentPath(String relpath, int index) {
    return index <= 0 ? relpath : relpath + "." + index;
  }
}
