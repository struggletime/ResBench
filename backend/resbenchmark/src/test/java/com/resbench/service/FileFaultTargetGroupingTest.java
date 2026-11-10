package com.resbench.service;

import com.resbench.pojo.dto.FileFaultTargetReport;
import com.resbench.pojo.dto.FileFaultTargetReport.RelationTarget;
import com.resbench.pojo.dto.FileFaultTargetResponse.Group;
import com.resbench.pojo.dto.FileFaultTargetResponse.Item;
import com.resbench.pojo.dto.StartByFormRequest;
import com.resbench.service.probe.FileFaultTargetProbe;
import com.resbench.service.probe.FileFaultTargetProbeRegistry;
import com.resbench.service.probe.MysqlFileFaultTargetProbe;
import com.resbench.service.probe.PostgresqlFileFaultTargetProbe;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * file 故障目标清单的纯逻辑单测：表白名单、排序、{@code @auto:} 哨兵、按表分组、探针注册。
 *
 * <p><b>不打开任何数据库连接</b> —— 分组用的中性数据是手工构造的；哨兵测试只走"不需要连库"
 * 的那几条分支（原样透传 / 哨兵名写错 / reload=true 拒绝）。
 */
public class FileFaultTargetGroupingTest {

  private static final String PGDATA = "/var/lib/postgresql/data";
  private static final String DBDIR = PGDATA + "/base/12994";

  private static final String STOCK = "base/12994/141113";
  private static final String STOCK_PKEY = "base/12994/141116";
  private static final String CUSTOMER = "base/12994/141138";

  // ------------------------------------------------------------------ 构造中性数据

  private static String segmentPath(String relpath, int index) {
    return index <= 0 ? relpath : relpath + "." + index;
  }

  private static RelationTarget table(String name, String relpath, long bytes, long scans, int segments) {
    return new RelationTarget(name, "table", null,
        absolute(segmentPath(relpath, 0)),
        absolute(segmentPath(relpath, segments - 1)),
        segments >= 2 ? absolute(segmentPath(relpath, 1)) : null,
        bytes, scans, segments);
  }

  private static RelationTarget index(String name, String ownerTable, String relpath, long bytes,
      long scans, int segments) {
    return new RelationTarget(name, "index", ownerTable,
        absolute(segmentPath(relpath, 0)),
        absolute(segmentPath(relpath, segments - 1)),
        segments >= 2 ? absolute(segmentPath(relpath, 1)) : null,
        bytes, scans, segments);
  }

  private static String absolute(String relpath) {
    return PGDATA + "/" + relpath;
  }

  private static FileFaultTargetReport report(boolean statsFresh, List<RelationTarget> relations) {
    return new FileFaultTargetReport("postgres", "12994", PGDATA, DBDIR, PGDATA, statsFresh,
        statsFresh ? "scans" : "bytes", relations, new ArrayList<>());
  }

  /** stock（35 段，最热）+ 它的索引 + customer（单段）。 */
  private static FileFaultTargetReport typicalReport() {
    List<RelationTarget> relations = new ArrayList<>();
    relations.add(table("stock", STOCK, 40_800_000_000L, 759_371_242L, 35));
    relations.add(index("stock_pkey", "stock", STOCK_PKEY, 3_694_000_000L, 500_000_000L, 3));
    relations.add(table("customer", CUSTOMER, 22_000_000_000L, 117_259_408L, 1));
    return report(true, relations);
  }

  // ------------------------------------------------------------------ MySQL 的中性数据

  private static final String MYSQL_DATADIR = "/var/lib/mysql";
  private static final String MYSQL_DBDIR = MYSQL_DATADIR + "/resbench_demo";

  /** InnoDB：一个表一个 .ibd，不分段、没有独立的索引文件，就是探针会交上来的形状。 */
  private static RelationTarget mysqlTable(String name, long bytes, long io) {
    String path = MYSQL_DBDIR + "/" + name + ".ibd";
    return new RelationTarget(name, "table", null, path, path, null, bytes, io, 1);
  }

  private static FileFaultTargetReport mysqlReport(boolean statsFresh, List<RelationTarget> relations) {
    return new FileFaultTargetReport("resbench_demo", null, MYSQL_DATADIR, MYSQL_DBDIR, MYSQL_DATADIR,
        statsFresh, statsFresh ? "scans" : "bytes", relations, new ArrayList<>());
  }

  // ------------------------------------------------------------------ 表白名单

  @Test
  public void testTableWhitelist_Tpcc_OnlyOwnTables() {
    List<String> names = FileFaultTargetService.tableWhitelist("tpcc", "postgresql");
    Assert.assertTrue("少了表：" + names,
        names.containsAll(List.of("warehouse", "item", "stock", "district", "customer", "history",
            "oorder", "new_order", "order_line")));
    for (String name : names) {
      Assert.assertFalse("遗留表不该被算进来：" + name, name.startsWith("bmsql_"));
    }
  }

  @Test
  public void testTableWhitelist_BenchmarkSqlMapsToTpcc() {
    // 前端提交的负载名是 benchmarkSQL，资源目录按引擎内部名 tpcc 组织 —— 必须翻译
    List<String> fromRequestName = FileFaultTargetService.tableWhitelist("benchmarkSQL", "postgresql");
    List<String> fromEngineName = FileFaultTargetService.tableWhitelist("tpcc", "postgresql");
    Assert.assertEquals(fromEngineName, fromRequestName);
    Assert.assertTrue("翻译没生效，拿到的是：" + fromRequestName, fromRequestName.contains("stock"));
  }

  @Test
  public void testTableWhitelist_CaseInsensitive() {
    Assert.assertEquals(FileFaultTargetService.tableWhitelist("tpcc", "postgresql"),
        FileFaultTargetService.tableWhitelist("BenchmarkSQL", "postgresql"));
  }

  @Test
  public void testTableWhitelist_SmallbankKeepsItsOwnTables() {
    // 证明 payload 不是把 tpcc 写死了
    List<String> names = FileFaultTargetService.tableWhitelist("smallbank", "postgresql");
    Assert.assertFalse(names.isEmpty());
    Assert.assertFalse("smallbank 不该拿到 tpcc 的表", names.contains("stock"));
  }

  @Test
  public void testTableWhitelist_UnknownWorkload_ErrorListsAvailableNames() {
    try {
      FileFaultTargetService.tableWhitelist("no-such-benchmark", "postgresql");
      Assert.fail("应当抛错");
    } catch (IllegalArgumentException e) {
      // 名字对不上时要能一眼看出该填什么，而不是只丢一句"找不到"
      Assert.assertTrue("报错要列出可用的 benchmark 名：" + e.getMessage(),
          e.getMessage().contains("tpcc") && e.getMessage().contains("smallbank"));
      Assert.assertTrue("报错要说明归一化后的名字：" + e.getMessage(),
          e.getMessage().contains("归一化后"));
    }
  }

  @Test
  public void testDdlSuffix_RequestDbTypeToEnumName() {
    // 请求侧用 postgresql，而枚举名与 DDL 文件名都是 postgres
    Assert.assertEquals("postgres", FileFaultTargetService.ddlSuffix("postgresql"));
    Assert.assertEquals("postgres", FileFaultTargetService.ddlSuffix("postgre"));
    Assert.assertEquals("mysql", FileFaultTargetService.ddlSuffix("mysql"));
  }

  // ------------------------------------------------------------------ 哨兵

  @Test
  public void testSentinel_PlainPathPassesThrough() {
    StartByFormRequest request = new StartByFormRequest();
    Map<String, String> params = new LinkedHashMap<>();
    params.put("filepath", "/var/lib/postgresql/data/base/12994/141113");
    Map<String, String> resolved = new FileFaultTargetService(null, null)
        .resolverFor(request).resolveAll(params);
    Assert.assertEquals("/var/lib/postgresql/data/base/12994/141113", resolved.get("filepath"));
  }

  @Test
  public void testSentinel_UnknownName_ThrowsWithoutTouchingDatabase() {
    StartByFormRequest request = new StartByFormRequest();
    Map<String, String> params = new LinkedHashMap<>();
    params.put("filepath", "@auto:hottest");
    try {
      new FileFaultTargetService(null, null).resolverFor(request).resolveAll(params);
      Assert.fail("应当抛错");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue("报错要说清可用的是哪些：" + e.getMessage(),
          e.getMessage().contains("@auto:hottest-file"));
    }
  }

  @Test
  public void testSentinel_ReloadTrue_LeavesSentinelForTheJobInsteadOfRefusing() {
    // 这一轮会 DROP+CREATE：提交时解析必然过期，但也不该拒绝 —— 哨兵原样写进配置，
    // 由 job 在建完之后、负载开始之前解析（DBWorkload#resolveDeferredFaultTargets）。
    StartByFormRequest request = new StartByFormRequest();
    request.getRunOptions().setReload(Boolean.TRUE);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("filepath", "@auto:hottest-file");
    Map<String, String> resolved = new FileFaultTargetService(null, null)
        .resolverFor(request).resolveAll(params);
    Assert.assertEquals("@auto:hottest-file", resolved.get("filepath"));
  }

  @Test
  public void testSentinel_CreateOrLoadAlsoDefers_NoDatabaseTouch() {
    // create / load 也一样：建表灌数据在 job 里做，文件号那之后才存在
    for (boolean create : new boolean[] {true, false}) {
      StartByFormRequest request = new StartByFormRequest();
      request.getRunOptions().setCreate(create);
      request.getRunOptions().setLoad(!create);
      request.getRunOptions().setReload(Boolean.FALSE);
      Map<String, String> params = new LinkedHashMap<>();
      params.put("src-path", "@auto:db-dir");
      // testConnectionService 是 null：真去连库会 NPE，所以这里顺带证明了"延后时一次库都不连"
      Map<String, String> resolved = new FileFaultTargetService(null, null)
          .resolverFor(request).resolveAll(params);
      Assert.assertEquals("@auto:db-dir", resolved.get("src-path"));
    }
  }

  @Test
  public void testSentinel_NothingRebuilt_ResolvesNowAndFailsFast() {
    // create/load/reload 全为假 = 打库里现成的数据 → 现在就解析；连不上库（这里是 null 服务）
    // 应当当场抛错，而不是把一个 @auto: 字面量放过去
    StartByFormRequest request = new StartByFormRequest();
    request.setDbType("postgresql");
    request.getRunOptions().setCreate(Boolean.FALSE);
    request.getRunOptions().setLoad(Boolean.FALSE);
    request.getRunOptions().setReload(Boolean.FALSE);
    request.getWorkload().setName("tpcc");
    Map<String, String> params = new LinkedHashMap<>();
    params.put("src-path", "@auto:db-dir");
    try {
      new FileFaultTargetService(null, null).resolverFor(request).resolveAll(params);
      Assert.fail("不重建时必须当场解析：解析不了就该拒绝这一轮");
    } catch (RuntimeException e) {
      Assert.assertNotNull(e.getMessage());
    }
  }

  @Test
  public void testSubstituteSentinels_ReplacesInsideLongArgument() {
    // filemove 的恢复命令整条脚本在一个 arg 里，源与目标都在里面 —— 不能只比对"整个 arg 等于哨兵"
    Map<String, String> values = new LinkedHashMap<>();
    values.put("db-dir", "/var/lib/postgresql/data/base/12994");
    values.put("pgdata", "/var/lib/postgresql/data");
    String script = "rm -rf '@auto:db-dir'; /opt/chaosblade/blade destroy file move"
        + " --filepath '@auto:db-dir' --target '@auto:pgdata'";
    Assert.assertEquals(
        "rm -rf '/var/lib/postgresql/data/base/12994'; /opt/chaosblade/blade destroy file move"
            + " --filepath '/var/lib/postgresql/data/base/12994' --target '/var/lib/postgresql/data'",
        FileFaultTargetService.substituteSentinels(script, values));
  }

  @Test
  public void testSubstituteSentinels_NoSentinelIsIdentity_UnknownNameThrows() {
    Assert.assertEquals("/tmp/x", FileFaultTargetService.substituteSentinels("/tmp/x", Map.of()));
    Assert.assertNull(FileFaultTargetService.substituteSentinels(null, Map.of()));
    try {
      FileFaultTargetService.substituteSentinels("@auto:nope", Map.of());
      Assert.fail("不认识的哨兵必须抛错，绝不能把字面量放过去");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().contains("@auto:hottest-file"));
    }
    try {
      // 名字对但这一轮取不到值 → 也不能放过去
      FileFaultTargetService.substituteSentinels("@auto:hottest-mid-segment", new LinkedHashMap<>());
      Assert.fail("解析不出值必须抛错");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().contains("@auto:hottest-mid-segment"));
    }
  }

  // ------------------------------------------------------------------ 挑文件 / 排序

  @Test
  public void testTargets_PicksHottestByScans() {
    FileFaultTargetService.Targets targets = FileFaultTargetService.targets(typicalReport());
    Assert.assertEquals(DBDIR, targets.dbDir());
    Assert.assertEquals(PGDATA, targets.dataDirectory());
    Assert.assertEquals(absolute(STOCK), targets.hottestFile());
    Assert.assertEquals(absolute(STOCK + ".34"), targets.hottestLastSegment());
    Assert.assertEquals(absolute(STOCK + ".1"), targets.hottestMidSegment());
  }

  @Test
  public void testTargets_MidSegment_FallsBackToNextRelationWithMultipleSegments() {
    List<RelationTarget> relations = new ArrayList<>();
    relations.add(table("tiny_hot", "base/12994/141200", 8_192L, 999_999_999L, 1));
    relations.add(table("big", "base/12994/141201", 3_000_000_000L, 10L, 3));
    FileFaultTargetService.Targets targets = FileFaultTargetService.targets(report(true, relations));
    Assert.assertEquals(absolute("base/12994/141200"), targets.hottestFile());
    Assert.assertEquals("最热的那张只有一个段，mid 要顺位到下一个多段的",
        absolute("base/12994/141201.1"), targets.hottestMidSegment());
  }

  @Test
  public void testRank_StatsCold_RanksByBytesNotScans() {
    List<RelationTarget> relations = new ArrayList<>();
    relations.add(table("small_but_scanned", "base/12994/141300", 1_000_000L, 900_000_000L, 1));
    relations.add(table("huge_but_cold", "base/12994/141301", 50_000_000_000L, 0L, 47));
    List<RelationTarget> ranked = FileFaultTargetService.rank(report(false, relations));
    Assert.assertEquals("统计不可信时要按体量排，不能假装知道谁热",
        "huge_but_cold", ranked.get(0).name());
  }

  // ------------------------------------------------------------------ 按表分组

  @Test
  public void testGroups_DirectoriesFirst_AndOnlyTwoKinds() {
    List<Group> groups = FileFaultTargetService.buildGroups(typicalReport(), "", "");
    Assert.assertEquals("directories", groups.get(0).getKind());
    Assert.assertEquals("目录", groups.get(0).getTitle());
    Assert.assertEquals(2, groups.get(0).getItems().size());
    Assert.assertEquals("db-dir", groups.get(0).getItems().get(0).getRole());
    Assert.assertEquals("datadir", groups.get(0).getItems().get(1).getRole());

    for (Group group : groups) {
      for (Item item : flatten(group)) {
        String kind = item.getKind();
        Assert.assertTrue("kind 只能是 directory/file，实际是「" + kind + "」（" + item.getPath() + "）",
            "directory".equals(kind) || "file".equals(kind));
      }
    }
  }

  @Test
  public void testGroups_OneGroupPerTable_IndexNestedUnderItsTable() {
    List<Group> groups = FileFaultTargetService.buildGroups(typicalReport(), "", "");
    // 目录组 + stock + customer，各一张表一组
    Assert.assertEquals(3, groups.size());
    Group stock = groups.get(1);
    Assert.assertEquals("stock", stock.getTitle());
    Assert.assertEquals("table", stock.getKind());
    Assert.assertEquals("索引要挂在它所属的表下面", 1, stock.getChildren().size());
    Assert.assertEquals("stock_pkey", stock.getChildren().get(0).getTitle());
    Assert.assertEquals("index", stock.getChildren().get(0).getKind());
  }

  @Test
  public void testGroups_SingleSegmentTable_EmitsOnlyOneSegmentItem() {
    List<Group> groups = FileFaultTargetService.buildGroups(typicalReport(), "", "");
    Group customer = groups.get(2);
    Assert.assertEquals("customer", customer.getTitle());
    Assert.assertEquals("单段的关系只出一项", 1, customer.getItems().size());
    Item only = customer.getItems().get(0);
    Assert.assertEquals("only-segment", only.getRole());
    Assert.assertTrue(only.getLabel().contains("唯一一段"));
    Assert.assertEquals(0, customer.getChildren().size());
  }

  @Test
  public void testGroups_SingleSegmentTable_OnlyTheHottestOneIsRecommendedForLow() {
    // PG 里也会出现单段的表（smallbank 那种没长到 1 GiB 的表）。规则与 MySQL 一致：
    // 只有最热的那张标★，其余的单段表只列出来、不推荐。
    List<Group> groups = FileFaultTargetService.buildGroups(typicalReport(),
        "faultinject.fault.disk.filemove", "low");
    Group customer = groups.get(2);
    Assert.assertEquals("customer", customer.getTitle());
    Assert.assertEquals("only-segment", customer.getItems().get(0).getRole());
    Assert.assertFalse("不是最热那张，不该标★", customer.getItems().get(0).isRecommended());
  }

  @Test
  public void testGroups_MultiSegmentTable_HasThreePlacements() {
    List<Group> groups = FileFaultTargetService.buildGroups(typicalReport(), "", "");
    List<String> roles = groups.get(1).getItems().stream().map(Item::getRole).toList();
    Assert.assertEquals(List.of("first-segment", "last-segment", "mid-segment"), roles);
  }

  @Test
  public void testGroups_Recommendation_MatchesNameKeyAndLevel() {
    List<Group> groups = FileFaultTargetService.buildGroups(typicalReport(),
        "faultinject.fault.disk.filemove", "high");
    Item databaseDir = groups.get(0).getItems().get(0);
    Assert.assertTrue("move:high 应当推荐整个库目录", databaseDir.isRecommended());
    Assert.assertFalse("move:high 不该推荐 PGDATA", groups.get(0).getItems().get(1).isRecommended());
    Assert.assertFalse("低档的第一个段不该被推荐", groups.get(1).getItems().get(0).isRecommended());

    List<Group> low = FileFaultTargetService.buildGroups(typicalReport(),
        "faultinject.fault.disk.filemove", "low");
    Assert.assertTrue("move:low 应当推荐最热表的第 0 段", low.get(1).getItems().get(0).isRecommended());
  }

  // ------------------------------------------------------------------ 探针注册

  private static FileFaultTargetProbe fakeProbe(String dbType) {
    return new FileFaultTargetProbe() {
      @Override
      public Set<String> supportedDbTypes() {
        return Set.of(dbType);
      }

      @Override
      public FileFaultTargetReport probe(Connection connection, String workload,
          List<String> tableWhitelist) {
        return null;
      }
    };
  }

  @Test
  public void testRegistry_FindsProbeByDbType() {
    FileFaultTargetProbeRegistry registry =
        new FileFaultTargetProbeRegistry(List.of(fakeProbe("fakedb")));
    Assert.assertTrue(registry.find("fakedb").isPresent());
    Assert.assertTrue(registry.supportedDbTypes().contains("fakedb"));
    Assert.assertFalse("没声明的 dbType 不该被认领", registry.find("postgresql").isPresent());
  }

  @Test(expected = IllegalStateException.class)
  public void testRegistry_DuplicateDbType_ThrowsAtStartup() {
    new FileFaultTargetProbeRegistry(List.of(fakeProbe("fakedb"), fakeProbe("fakedb")));
  }

  @Test
  public void testProbeKey_JobSideEnumNameAndRequestSideNameBothHitTheProbe() {
    // job 侧递进来的是枚举名 postgres（DatabaseType.POSTGRES.name()），探针登记的是请求侧的
    // postgresql。差这一个字母就是「没有探针」，而且是在 job 建完库、正要开跑的那一刻才炸。
    // 2026-10-14 10:44 那几轮就是这么废掉的（job 起来 1 秒就退出）。
    Assert.assertEquals("postgresql", FileFaultTargetService.probeKey("postgres"));
    Assert.assertEquals("postgresql", FileFaultTargetService.probeKey("POSTGRES"));
    Assert.assertEquals("postgresql", FileFaultTargetService.probeKey("postgresql"));
    Assert.assertEquals("postgresql", FileFaultTargetService.probeKey("postgre"));
    Assert.assertEquals("mysql", FileFaultTargetService.probeKey("MYSQL"));
    Assert.assertEquals("", FileFaultTargetService.probeKey(null));
  }

  @Test
  public void testProbeKey_EveryRegisteredProbeIsReachableByItsOwnEnumName() {
    // 反向断言：每个已登记探针对应的枚举名（请求侧名字大写）也要能被查到 ——
    // 新加一个探针时，忘了补别名就会在这里挂，而不是等一轮跑废。
    FileFaultTargetProbeRegistry registry = springSideProbes();
    for (String dbType : registry.supportedDbTypes()) {
      Assert.assertTrue("请求侧名字自己当然要认：" + dbType,
          registry.find(FileFaultTargetService.probeKey(dbType)).isPresent());
    }
    Assert.assertTrue("job 侧的枚举名 postgres 也要落到 PG 探针上",
        registry.find(FileFaultTargetService.probeKey("postgres")).isPresent());
    Assert.assertTrue("job 侧的枚举名 mysql 也要落到 MySQL 探针上",
        registry.find(FileFaultTargetService.probeKey("MYSQL")).isPresent());
  }

  /** Spring 侧实际收集到的那批（每个探针类上的 {@code @Component} 换成手工 new 一遍）。 */
  private static FileFaultTargetProbeRegistry springSideProbes() {
    return new FileFaultTargetProbeRegistry(
        List.of(new PostgresqlFileFaultTargetProbe(), new MysqlFileFaultTargetProbe()));
  }

  @Test
  public void testProbes_SpringSideAndJobSideAreTheSameBatch() {
    // 探针有两批登记：Spring 侧靠 @Component 自动收集，job 侧（另一个 JVM，没有 Spring）靠
    // FileFaultTargetService.STANDALONE_PROBES 里手写的那一份。**只加了前者**的话，@auto: 会一路
    // 跑到 job 建完库、正要开跑负载时才报"没有探针"（2026-10-14 就是这么废掉三轮的）。
    // 引擎启动时会为这种不一致打 WARN，这里提前一整个构建周期拦住它。
    Assert.assertEquals("两批探针登记必须一致，缺的在下面这个集合里",
        springSideProbes().supportedDbTypes(),
        FileFaultTargetService.STANDALONE_PROBES.supportedDbTypes());
    Assert.assertTrue(springSideProbes().supportedDbTypes().containsAll(List.of("postgresql", "mysql")));
  }

  // ------------------------------------------------------------------ MySQL

  @Test
  public void testTableWhitelist_MysqlDdlParsesToTheSameTpccTables() {
    // MySQL 的 DDL 是另一份资源（ddl-mysql.sql），表名得解析得出来 —— 否则探针会去查一个空集合
    List<String> names = FileFaultTargetService.tableWhitelist("tpcc", "mysql");
    Assert.assertEquals(9, names.size());
    Assert.assertTrue(names.containsAll(List.of("warehouse", "stock", "customer", "oorder", "order_line")));
  }

  @Test
  public void testMysqlTargets_EveryRelationIsOneSegment_SoNoMidSegment() {
    List<RelationTarget> relations = List.of(
        mysqlTable("stock", 40_800_000_000L, 900_000L),
        mysqlTable("customer", 22_000_000_000L, 100L));
    FileFaultTargetService.Targets targets =
        FileFaultTargetService.targets(mysqlReport(true, relations));

    Assert.assertEquals("库目录 = <datadir>/<库>", MYSQL_DBDIR, targets.dbDir());
    Assert.assertEquals(MYSQL_DATADIR, targets.dataDirectory());
    Assert.assertEquals(MYSQL_DBDIR + "/stock.ibd", targets.hottestFile());
    Assert.assertEquals("InnoDB 的 .ibd 不分段：最后一段就是唯一那一段",
        targets.hottestFile(), targets.hottestLastSegment());
    Assert.assertNull("不分段的库没有 mid —— @auto:hottest-mid-segment 要明确报错，不能给一个错的文件",
        targets.hottestMidSegment());
  }

  @Test
  public void testMysqlSentinels_DatadirAndPgdataAreTheSameThing() {
    Map<String, String> values = FileFaultTargetService.sentinelValues(
        mysqlReport(true, List.of(mysqlTable("stock", 100L, 1L))));
    Assert.assertEquals("MySQL 的数据总目录就是 @@datadir", MYSQL_DATADIR, values.get("datadir"));
    Assert.assertEquals("datadir 是中性叫法，pgdata 是老名字，两者同值",
        values.get("datadir"), values.get("pgdata"));
    Assert.assertTrue("datadir 必须是引擎认识的哨兵名",
        FileFaultTargetService.SENTINEL_NAMES.contains("datadir"));
    Assert.assertTrue("老编排里的 @auto:pgdata 不能失效",
        FileFaultTargetService.SENTINEL_NAMES.contains("pgdata"));
  }

  @Test
  public void testMysqlGroups_OnlyTables_NoIndexGroupAndNoSegmentedItems() {
    List<Group> groups = FileFaultTargetService.buildGroups(
        mysqlReport(true, List.of(mysqlTable("stock", 40_800_000_000L, 900_000L))),
        "faultinject.fault.disk.filemove", "low");

    Assert.assertEquals("目录组 + 表组，就这些", 2, groups.size());
    Group directories = groups.get(0);
    Assert.assertEquals("db-dir", directories.getItems().get(0).getRole());
    Assert.assertEquals("datadir", directories.getItems().get(1).getRole());
    for (Item item : flatten(directories)) {
      Assert.assertFalse("清单里不该再出现 PG 专属的叫法：" + item.getLabel(),
          item.getLabel() != null && item.getLabel().contains("PGDATA"));
    }

    Group stock = groups.get(1);
    Assert.assertEquals("InnoDB 的索引在 .ibd 里面，查不到索引文件", 0, stock.getChildren().size());
    Assert.assertEquals(List.of("only-segment"),
        stock.getItems().stream().map(Item::getRole).toList());
    Assert.assertTrue("move:low 打的就是最热表唯一那一段",
        stock.getItems().get(0).isRecommended());
  }

  @Test
  public void testMysqlGroups_LowRecommendsOnlyTheHottestFile() {
    // 低档的语义是"只打一个文件"：9 张表全标★等于没标，所以单文件布局只推最热的那张表
    List<RelationTarget> relations = List.of(
        mysqlTable("order_line", 367_900_000L, 3_154_623L),
        mysqlTable("stock", 350_500_000L, 1_191_673L));
    List<Group> groups = FileFaultTargetService.buildGroups(
        mysqlReport(true, relations), "faultinject.fault.disk.filemove", "low");

    List<Item> recommended = flattenAll(groups).stream()
        .filter(item -> "file".equals(item.getKind()))
        .filter(Item::isRecommended)
        .toList();
    Assert.assertEquals("低档只能有一个推荐项", 1, recommended.size());
    Assert.assertEquals("推的是最热那张表的 .ibd", MYSQL_DBDIR + "/order_line.ibd",
        recommended.get(0).getPath());
  }

  @Test
  public void testMysqlGroups_AppendHasNoSafeTargetOnSingleFileLayouts() {
    // InnoDB 的 .ibd 不分段 → append 只能追加在主数据文件末尾，而且引擎不传 --enable-backup。
    // 这种时候一个推荐项都不该给（宁可让人自己看 note），否则会把人引到不可恢复的那一档。
    List<Group> groups = FileFaultTargetService.buildGroups(
        mysqlReport(true, List.of(mysqlTable("accounts", 240_000L, 178_472L))),
        "faultinject.fault.disk.fileappend", "high");

    Assert.assertTrue("单文件上 append 没有安全档，不该标推荐",
        flattenAll(groups).stream().noneMatch(Item::isRecommended));
  }

  @Test
  public void testMysqlGroups_AppendFaultIsRecognisedByShortKey() {
    Assert.assertTrue(FileFaultTargetService.isAppendFault("faultinject.fault.disk.fileappend"));
    Assert.assertFalse(FileFaultTargetService.isAppendFault("faultinject.fault.disk.filemove"));
    Assert.assertFalse(FileFaultTargetService.isAppendFault(""));
  }

  @Test
  public void testMysqlGrouping_StatsCold_RanksByBytes() {
    // performance_schema 读不到时探针给 scans=0 → 上层必须按体量排，而不是假装知道谁热
    List<RelationTarget> relations = List.of(
        mysqlTable("small", 1_000_000L, 0L),
        mysqlTable("huge", 50_000_000_000L, 0L));
    List<RelationTarget> ranked = FileFaultTargetService.rank(mysqlReport(false, relations));
    Assert.assertEquals("huge", ranked.get(0).name());
  }

  private static List<Item> flattenAll(List<Group> groups) {
    List<Item> items = new ArrayList<>();
    for (Group group : groups) {
      items.addAll(flatten(group));
    }
    return items;
  }

  private static List<Item> flatten(Group group) {
    List<Item> items = new ArrayList<>(group.getItems());
    for (Group child : group.getChildren()) {
      items.addAll(flatten(child));
    }
    return items;
  }
}
