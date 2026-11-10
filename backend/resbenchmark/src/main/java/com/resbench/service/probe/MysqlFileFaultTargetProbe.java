package com.resbench.service.probe;

import com.resbench.pojo.dto.FileFaultTargetReport;
import com.resbench.pojo.dto.FileFaultTargetReport.RelationTarget;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * MySQL 的 file 故障目标探针：靠库自己的 catalog 回答"文件在哪、多大、谁在被读"。
 *
 * <p>用到的东西：{@code @@datadir}（数据总目录）、{@code DATABASE()}（连接所在的库，也就是压测库）、
 * {@code information_schema.TABLES}（表名与估算大小）、InnoDB 的数据文件视图（真实 {@code .ibd} 路径）、
 * {@code performance_schema.table_io_waits_summary_by_table}（热度）。
 * <b>全是只读查询，不碰容器文件系统</b> —— 引擎看不到容器的盘，库多半还跑在容器里。
 *
 * <h2>与 PostgreSQL 不同的三点（都不是 bug，是 InnoDB 的本来样子）</h2>
 *
 * <ul>
 *   <li><b>不分段</b>：一个表一个 {@code .ibd}，没有 PG 那种 1 GiB 一段的 {@code .1} / {@code .2}。
 *       所以每个关系都是 {@code segments=1}、{@code lastSegment == firstSegment}、{@code midSegment=null}，
 *       上层会自动少出"最后一段 / 非最后一段"两项；{@code @auto:hottest-mid-segment} 在 MySQL 上
 *       也就会明确报"解析不出路径"，而不是给一个错的文件。</li>
 *   <li><b>没有单独的索引文件</b>：InnoDB 的二级索引在 {@code .ibd} 里面，catalog 里查不到"索引文件"，
 *       所以清单里只有表这一层。</li>
 *   <li><b>表大小是估算值</b>：{@code information_schema.TABLES} 的 {@code DATA_LENGTH + INDEX_LENGTH}
 *       （MySQL 自己的 {@code SHOW TABLE STATUS} 报的也是这两个），不像 PG 的 {@code pg_relation_size}
 *       那样是精确值；8.0 起这个值还是缓存的（{@code information_schema_stats_expiry} 默认 24 小时）。
 *       这一点会写进 notes。</li>
 *   <li><b>分区表 / 常规表空间里的表不列</b>：分区表一个表对应多个文件（{@code <表>#p#<分区>.ibd}），
 *       常规表空间里的表（{@code CREATE TABLE … TABLESPACE ts}）没有自己的 {@code .ibd} —— 这两类
 *       指哪个文件都不对，只写进 notes，不猜路径。</li>
 * </ul>
 *
 * <p>热度统计（{@code performance_schema}）读不到时<b>降级不报错</b>：改成按体量排序，并在 notes 里
 * 说清是为什么 —— 与 PG 那边"统计被清零就按体量排"是同一种老实做法。真正的失败（连不上、
 * {@code @@datadir} 拿不到、连接没有默认库）照旧抛 {@link SQLException}。
 */
@Component
public class MysqlFileFaultTargetProbe implements FileFaultTargetProbe {

  /** InnoDB 单表一个文件。 */
  private static final String IB_FILE_SUFFIX = ".ibd";

  /**
   * 数据文件视图的两套名字：8.0 起去掉了 {@code SYS}，5.7 还是老名字。
   * 先试新的，报错（表不存在）再试旧的 —— 两个版本的用户都不用手工挑。
   */
  private static final String[][] DATAFILE_VIEWS = {
      {"information_schema.INNODB_TABLESPACES", "information_schema.INNODB_DATAFILES"},
      {"information_schema.INNODB_SYS_TABLESPACES", "information_schema.INNODB_SYS_DATAFILES"},
  };

  @Override
  public Set<String> supportedDbTypes() {
    // OceanBase 的 MySQL 模式不声明：它的表空间与 @@datadir 语义跟 MySQL 不是一回事，
    // 要接的话得先在一套真环境上把下面这些 catalog 逐条核过，不能靠"协议兼容"就往上套。
    return Set.of("mysql");
  }

  @Override
  public FileFaultTargetReport probe(Connection connection, String workload, List<String> tableWhitelist)
      throws SQLException {
    String dataDirectory =
        ProbeSupport.trimTrailingSlash(ProbeSupport.queryScalar(connection, "SELECT @@datadir"));
    if (StringUtils.isBlank(dataDirectory)) {
      throw new SQLException("读不到 @@datadir（返回空），无法给出这个实例的数据总目录");
    }

    // 连接落在哪个库，压测的表就在哪个库：TestConnectionService 与压测用的是同一个
    // jdbc:mysql://…/resbench_demo，所以这里问连接自己最准。为空说明连接没带默认库，
    // 那种情况下列出来的任何一个表名都不知属于谁 —— 直接失败，不猜。
    String databaseName = ProbeSupport.queryScalar(connection, "SELECT DATABASE()");
    if (StringUtils.isBlank(databaseName)) {
      throw new SQLException("这个连接没有默认库（SELECT DATABASE() 为空）：没法确定要查哪个库的表，"
          + "请在连接串里带上库名");
    }
    String databaseDirectory = ProbeSupport.join(dataDirectory, databaseName);

    boolean filePerTable =
        !"OFF".equalsIgnoreCase(StringUtils.trimToEmpty(
            ProbeSupport.queryScalar(connection, "SELECT @@innodb_file_per_table")));

    // 盘上的文件名不一定等于 information_schema 里的表名：lower_case_table_names != 0 的服务器
    // （Windows / macOS 的默认）把 InnoDB 的表名按小写存盘。只有"拼路径"这一路需要管它 ——
    // 从数据文件视图读到的路径是库自己记的，本来就是对的。
    boolean lowerCaseOnDisk =
        !"0".equals(StringUtils.trimToEmpty(
            ProbeSupport.queryScalar(connection, "SELECT @@lower_case_table_names")));

    List<String> notes = new ArrayList<>();
    CatalogPaths catalog = catalogDatafilePaths(connection, dataDirectory, databaseName, notes);
    Map<String, Long> ioCounts = tableIoCounts(connection, notes);

    List<RelationTarget> relations = new ArrayList<>();
    int skippedOtherEngine = 0;
    int skippedNoFile = 0;
    String inList = ProbeSupport.quotedIdentifierList(tableWhitelist);
    if (!inList.isEmpty()) {
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery(
              "SELECT TABLE_NAME, ENGINE,"
                  + " COALESCE(DATA_LENGTH, 0) + COALESCE(INDEX_LENGTH, 0) AS BYTES"
                  + " FROM information_schema.TABLES"
                  + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'"
                  + " AND TABLE_NAME IN (" + inList + ")")) {
        while (rs.next()) {
          String name = rs.getString("TABLE_NAME");
          if (!ProbeSupport.isSafeIdentifier(name)) {
            continue;
          }
          if (!"InnoDB".equalsIgnoreCase(StringUtils.trimToEmpty(rs.getString("ENGINE")))) {
            skippedOtherEngine++;
            continue;
          }
          String path = catalog.paths().get(name);
          if (StringUtils.isBlank(path)) {
            if (catalog.readable() || !filePerTable) {
              // 视图读得出来却没有这张表 = 它不在自己的 .ibd 里（共享表空间 / 常规表空间 / 分区表）；
              // file_per_table=OFF 同理。两种情况下 <表>.ibd 都是个不存在的路径 —— 宁可不列，
              // 也不给一个指不到的文件。
              skippedNoFile++;
              continue;
            }
            String onDiskName = lowerCaseOnDisk ? name.toLowerCase(Locale.ROOT) : name;
            path = ProbeSupport.join(databaseDirectory, onDiskName + IB_FILE_SUFFIX);
          }
          long bytes = rs.getLong("BYTES");
          long scans = ioCounts.getOrDefault(name, 0L);
          relations.add(new RelationTarget(name, "table", null, path, path, null, bytes, scans, 1));
        }
      }
    }

    if (!filePerTable) {
      notes.add("innodb_file_per_table=OFF：表数据都在共享表空间（<datadir>/ibdata1*）里，没有"
          + "每张表一个的 .ibd 文件，所以清单里只有目录，没有表文件");
    }
    if (skippedOtherEngine > 0) {
      notes.add(skippedOtherEngine + " 张表的引擎不是 InnoDB（每张表的文件布局各不相同），未列入");
    }
    if (skippedNoFile > 0) {
      notes.add(skippedNoFile + " 张表没有自己的 .ibd 文件（在共享表空间 / 常规表空间里，或是分区表："
          + "分区表一个表多份文件），未列入");
    }
    if (!relations.isEmpty()) {
      notes.add("InnoDB 的索引和数据在同一个 .ibd 里，没有独立的索引文件 —— 清单只有表这一层");
      notes.add("表大小取 information_schema 的估算值（DATA_LENGTH + INDEX_LENGTH，不是文件实际占用；"
          + "8.0 起这个值默认缓存 24 小时），排序看个大概就好");
    }

    boolean statsFresh = relations.stream().anyMatch(target -> target.scans() > 0);
    if (!statsFresh && relations.size() > 1) {
      // 一条都不热 = 统计不可信（刚重启、刚清空、或压根读不到），这时按体量排并说清楚
      notes.add("没有可用的热度统计：清单按体量排序，不代表负载真的在读它");
    }

    return new FileFaultTargetReport(
        databaseName,
        // MySQL 没有 PG 的 oid 那种库标识，留空；上层会少印一段"（oid …）"
        null,
        dataDirectory,
        databaseDirectory,
        // move 的落点取 datadir：库目录（<datadir>/<库>）就在它下面 → 同一个文件系统 → rename(2)。
        // 跨盘 mv 会退化成"先拷再删"，2026-10-07 就拿 /tmp 当落点把宿主机根分区撑到 100% 过。
        dataDirectory,
        statsFresh,
        statsFresh ? "scans" : "bytes",
        relations,
        notes);
  }

  /**
   * 数据文件视图读出来的结果。
   *
   * <p>{@code readable=false} = <b>问不出来</b>（视图不存在 / 没权限 / 字段对不上），这时上层只能按
   * {@code <datadir>/<库>/<表>.ibd} 拼；{@code readable=true} 而某张表不在 {@code paths} 里，
   * 含义完全不同 —— 是"这张表本来就没有自己的 .ibd"。两件事分开，上层才不会把不存在的路径编出来。
   */
  private record CatalogPaths(boolean readable, Map<String, String> paths) {

    static CatalogPaths unavailable() {
      return new CatalogPaths(false, Map.of());
    }
  }

  /**
   * 从 InnoDB 的数据文件视图里读<b>真实</b>的 {@code .ibd} 路径 —— 它比"按 {@code <datadir>/<库>/<表>.ibd}
   * 拼一个"更权威：{@code DATA DIRECTORY} 建到别处的表、常规表空间里的表，都不是那个形状。
   *
   * <p>视图名 5.7 与 8.0 不同（8.0 去掉了 {@code SYS}），先试新的再试旧的。两个视图都需要
   * {@code PROCESS} 权限，普通账号读不到 —— 那就回落到拼路径，并把这件事写进 notes。
   */
  private static CatalogPaths catalogDatafilePaths(Connection connection, String dataDirectory,
      String databaseName, List<String> notes) {
    String prefix = databaseName + "/";
    SQLException last = null;
    for (String[] views : DATAFILE_VIEWS) {
      Map<String, String> paths = new LinkedHashMap<>();
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery(
              "SELECT t.NAME AS TS_NAME, d.PATH AS TS_PATH"
                  + " FROM " + views[0] + " t JOIN " + views[1] + " d ON d.SPACE = t.SPACE")) {
        while (rs.next()) {
          // NAME 对每表一个文件的表空间是 "<库>/<表>"；共享表空间（innodb_system）、undo、
          // 常规表空间那种行是别的形状，一律靠前缀滤掉
          String name = rs.getString("TS_NAME");
          String path = rs.getString("TS_PATH");
          if (StringUtils.isBlank(name) || StringUtils.isBlank(path) || !name.startsWith(prefix)) {
            continue;
          }
          paths.put(name.substring(prefix.length()), absolute(dataDirectory, path));
        }
        if (!paths.isEmpty()) {
          notes.add("每张表的路径取自 InnoDB 的数据文件视图（" + views[1] + "），是库自己记的真实路径");
        }
        return new CatalogPaths(true, paths);
      } catch (SQLException e) {
        last = e;
      }
    }
    notes.add("读不到 InnoDB 的数据文件视图（" + DATAFILE_VIEWS[0][1] + " / " + DATAFILE_VIEWS[1][1]
        + "：可能是版本不同，或者账号没有 PROCESS 权限），路径改按 <datadir>/<库>/<表>.ibd 拼出"
        + (last == null ? "" : "（最后一个错误：" + last.getMessage() + "）"));
    return CatalogPaths.unavailable();
  }

  /**
   * 每张表的 I/O 次数，当作"热度"用 —— 对应 PG 那边的 {@code seq_scan + idx_scan}。
   *
   * <p>{@code performance_schema} 关着、没权限、或这个汇总表不存在，都返回空 map 让上层按体量排；
   * 会让"读不到"这件事写进 notes，不会静默变成"所有表都没人读"。
   *
   * <p>计数是<b>内存里的累计值</b>：库一重启就归零（谁都能 {@code TRUNCATE} 它），所以"一条记录都没有"
   * 与"这个库真的没人读"在上层是同一件事 —— 都是按体量排，并在 notes 里说清。
   */
  private static Map<String, Long> tableIoCounts(Connection connection, List<String> notes) {
    Map<String, Long> counts = new LinkedHashMap<>();
    try {
      String enabled = StringUtils.trimToEmpty(
          ProbeSupport.queryScalar(connection, "SELECT @@performance_schema"));
      if ("0".equals(enabled) || "OFF".equalsIgnoreCase(enabled)) {
        notes.add("performance_schema 关着（@@performance_schema=OFF），拿不到表的 I/O 次数："
            + "清单按体量排序");
        return counts;
      }
    } catch (SQLException e) {
      // 连这个变量都问不到：下面的查询多半也问不到，交给同一个 catch 去说，不重复写两条 note
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
            "SELECT OBJECT_NAME, COUNT_STAR FROM performance_schema.table_io_waits_summary_by_table"
                + " WHERE OBJECT_SCHEMA = DATABASE()")) {
      while (rs.next()) {
        String name = rs.getString(1);
        if (StringUtils.isNotBlank(name)) {
          counts.put(name, rs.getLong(2));
        }
      }
      if (counts.isEmpty()) {
        notes.add("performance_schema 里这个库一条表 I/O 记录都没有（库刚重启、或统计刚被清过），"
            + "清单按体量排序");
      }
    } catch (SQLException e) {
      notes.add("读不到 performance_schema.table_io_waits_summary_by_table（performance_schema 没开、"
          + "没有 SELECT 权限、或该表不存在：原因「" + e.getMessage() + "」）：清单按体量排序");
    }
    return counts;
  }

  /**
   * 视图里的 {@code PATH} 默认是相对 datadir 的 {@code ./<库>/<表>.ibd}（带前导 {@code ./}），
   * 用 {@code DATA DIRECTORY} 建到别处的表则给绝对路径。
   */
  private static String absolute(String dataDirectory, String path) {
    String value = StringUtils.trimToEmpty(path);
    if (value.startsWith("/")) {
      return value;
    }
    if (value.startsWith("./")) {
      value = value.substring(2);
    }
    return ProbeSupport.join(dataDirectory, value);
  }
}
