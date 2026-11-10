package com.resbench.service;

import com.resbench.pojo.dto.FileFaultTargetReport;
import com.resbench.pojo.dto.FileFaultTargetReport.RelationTarget;
import com.resbench.pojo.dto.FileFaultTargetRequest;
import com.resbench.pojo.dto.FileFaultTargetResponse;
import com.resbench.pojo.dto.FileFaultTargetResponse.Group;
import com.resbench.pojo.dto.FileFaultTargetResponse.Item;
import com.resbench.pojo.dto.StartByFormRequest;
import com.resbench.pojo.dto.TestConnectionRequest;
import com.resbench.service.probe.FileFaultTargetProbe;
import com.resbench.service.probe.FileFaultTargetProbeRegistry;
import com.resbench.service.probe.MysqlFileFaultTargetProbe;
import com.resbench.service.probe.PostgresqlFileFaultTargetProbe;
import com.resbench.types.DatabaseType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "file 类故障该用哪个文件"的调度层：开连接 → 解析表白名单 → 按 dbType 找探针 → 把探针返回的中性数据
 * 变成按表分组的候选清单 → 护栏 → 解析 {@code @auto:} 哨兵。
 *
 * <p><b>这一层是所有数据库共用的</b>：分组形状、{@code [目录]/[文件]} 标签、说明文字、推荐规则、
 * 哨兵语义都在这里。要接一个新库，只需要加一个探针（见
 * {@link com.resbench.service.probe.FileFaultTargetProbe}），这里一行都不用改。
 *
 * <p>两个入口都汇到 {@link #probe}：
 * <ul>
 *   <li>HTTP：{@link #handle(FileFaultTargetRequest)} —— 前端点「选择文件」时调，给人看/给人挑；</li>
 *   <li>开跑：{@link #resolverFor(StartByFormRequest)} —— 编排里写 {@code @auto:xxx}，引擎在建故障模板时解析。</li>
 * </ul>
 */
@Service
public class FileFaultTargetService {
  private static final Logger LOG = LoggerFactory.getLogger(FileFaultTargetService.class);

  /** 哨兵前缀。不以它开头的值<b>原样透传</b> —— 现有手写路径的编排行为完全不变。 */
  public static final String SENTINEL_PREFIX = "@auto:";

  /** 候选清单里最多列多少个关系；超过就按排序截断并在 notes 里说明（tpcc 9 张表远没到）。 */
  private static final int RELATION_LIMIT = 30;

  /**
   * 全部可用的哨兵名（不带前缀）。写错名字时不必连库，直接按这个列表报错。
   *
   * <p>{@code datadir} 与 {@code pgdata} 是<b>同一个东西</b>（数据总目录）：前者是中性叫法
   * （MySQL 的 datadir），后者是 PG 时代的名字，留着是为了不让已经写好的编排作废。
   */
  static final List<String> SENTINEL_NAMES =
      List.of("db-dir", "hottest-file", "hottest-last-segment", "hottest-mid-segment", "datadir",
          "pgdata");

  private static final Pattern CREATE_TABLE = Pattern.compile(
      "(?i)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
          + "([`\"\\[]?[A-Za-z_][A-Za-z0-9_]*[`\"\\]]?)(?:\\s*\\.\\s*([`\"\\[]?[A-Za-z_][A-Za-z0-9_]*[`\"\\]]?))?");

  private static final String NOTE_CATALOG_ONLY =
      "清单来自数据库目录里记的信息（catalog），引擎看不到容器的文件系统：某个文件若被上一次 move 挪走还没还回来，这里照样会列出来";
  private static final String NOTE_SEGMENTS_ONLY_SAMPLED =
      "每个关系只列第 0 段 / 最后一段 / 第 1 段；其余段可以自己填（例如 …/141113.7）";
  private static final String NOTE_APPEND_SINGLE_FILE =
      "这个库里出现了单文件的表（InnoDB 的 .ibd，或者 PG 里只有一段的表）：append 没有「非最后一段」可打，"
          + "每一档都追加在主数据文件末尾，而引擎的 append 命令不传 --enable-backup —— 写进去恢复不了，"
          + "所以 append 在这类文件上不给推荐项。要测 append 请打多段表的中间段，或在这些表上用 chmod / move";

  /**
   * 探针登记的「无 Spring」版本，给 job 进程用 —— job 是另一个 JVM，取不到 {@code @Component}。
   * 加新探针时这里要跟着加一个；两批不一致会在引擎启动时打 WARN（见构造器），不会静默。
   */
  static final FileFaultTargetProbeRegistry STANDALONE_PROBES =
      new FileFaultTargetProbeRegistry(
          List.of(new PostgresqlFileFaultTargetProbe(), new MysqlFileFaultTargetProbe()));

  private final TestConnectionService testConnectionService;
  private final FileFaultTargetProbeRegistry probeRegistry;

  public FileFaultTargetService(TestConnectionService testConnectionService,
      FileFaultTargetProbeRegistry probeRegistry) {
    this.testConnectionService = testConnectionService;
    this.probeRegistry = probeRegistry;
    // job 侧延后解析用的是 STANDALONE_PROBES。两批不一致 = 某些库类型的 @auto: 会在 job 里
    // 报「没有探针」，那要等建完库（可能几小时）才炸，代价太大 —— 启动时就说清楚。
    if (probeRegistry != null
        && !probeRegistry.supportedDbTypes().equals(STANDALONE_PROBES.supportedDbTypes())) {
      LOG.warn("file 故障探针登记不一致：Spring={}，job 内置={}。@auto: 延后解析在 job 里只认内置的那批",
          probeRegistry.supportedDbTypes(), STANDALONE_PROBES.supportedDbTypes());
    }
  }

  // ------------------------------------------------------------------ HTTP 入口

  public FileFaultTargetResponse handle(FileFaultTargetRequest request) {
    long startedAt = System.currentTimeMillis();
    FileFaultTargetResponse response = new FileFaultTargetResponse();
    response.setCheckedAt(OffsetDateTime.now().toString());
    try {
      String dbType = normalize(request.getDbType());
      response.setDbType(dbType);
      String workload = param(request.getParams(), "workload");
      if (StringUtils.isBlank(workload)) {
        throw new IllegalArgumentException(
            "缺少 workload：params.workload 必须填（tpcc / smallbank …）。引擎要靠它定位 benchmark 的 DDL，"
                + "才知道哪些表算数 —— 不限定表的话，库里遗留的同名表会被当成候选，跑出来是「假故障」");
      }

      FileFaultTargetReport report = probe(toConnectionRequest(request, dbType), dbType,
          workload.trim());

      FileFaultTargetResponse.Database database = new FileFaultTargetResponse.Database();
      database.setName(report.databaseName());
      database.setId(report.databaseId());
      response.setDatabase(database);
      response.setDataDirectory(report.dataDirectory());
      response.setStatsFresh(report.statsFresh());
      response.setRankBasis(report.rankBasis());

      String nameKey = StringUtils.trimToEmpty(param(request.getParams(), "nameKey"));
      String level = StringUtils.trimToEmpty(param(request.getParams(), "level"));

      List<String> notes = new ArrayList<>(report.notes());
      notes.add(NOTE_CATALOG_ONLY);
      if (report.relations().stream().anyMatch(relation -> relation.segments() >= 2)) {
        // 不分段的库（InnoDB 的 .ibd）没有"其余段"这回事，那句话对它只是噪音
        notes.add(NOTE_SEGMENTS_ONLY_SAMPLED);
      }
      if (isAppendFault(nameKey)
          && report.relations().stream().anyMatch(relation -> relation.segments() <= 1)) {
        notes.add(NOTE_APPEND_SINGLE_FILE);
      }
      response.setNotes(notes);

      response.setGroups(buildGroups(report, nameKey, level));
      response.setSuccess(true);
      response.setMessage("共 " + report.relations().size() + " 个关系（表与索引）");
      LOG.info("file 故障目标清单: dbType={}, workload={}, database={}, 关系数={}, statsFresh={}",
          dbType, workload, report.databaseName(), report.relations().size(), report.statsFresh());
    } catch (IllegalArgumentException e) {
      response.setSuccess(false);
      response.setMessage(e.getMessage());
      LOG.warn("file 故障目标清单被拒绝: dbType={}, 原因={}", request.getDbType(), e.getMessage());
    } catch (SQLException e) {
      response.setSuccess(false);
      response.setMessage("无法读取目标库的文件信息（SQL 失败）：" + StringUtils.defaultString(e.getMessage()));
      LOG.warn("file 故障目标清单 SQL 失败: dbType={}, state={}, code={}",
          request.getDbType(), e.getSQLState(), e.getErrorCode(), e.getMessage());
    } catch (Exception e) {
      response.setSuccess(false);
      response.setMessage("无法读取目标库的文件信息："
          + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getName()));
      LOG.error("file 故障目标清单失败: dbType={}", request.getDbType(), e);
    }
    response.setElapsedMs(System.currentTimeMillis() - startedAt);
    return response;
  }

  // ------------------------------------------------------------------ 开跑入口（@auto:）

  /**
   * 给一次 {@code startByForm} 造一个解析器。惰性：只有真的遇到 {@code @auto:} 才连库，
   * 而且一次运行只连一次（多个故障共用同一份结果）。
   */
  public SentinelResolver resolverFor(StartByFormRequest request) {
    return new SentinelResolver(request);
  }

  /** 把一组参数里的 {@code @auto:} 哨兵替换成真实路径；不认的哨兵、解析不了的情况都直接抛错。 */
  public final class SentinelResolver {
    private final StartByFormRequest request;
    private Map<String, String> sentinelValues;
    private String failure;

    private SentinelResolver(StartByFormRequest request) {
      this.request = request;
    }

    /** 返回一份新 map，原 map 不动。 */
    public Map<String, String> resolveAll(Map<String, String> params) {
      if (params == null || params.isEmpty()) {
        return params;
      }
      Map<String, String> resolved = new LinkedHashMap<>(params);
      boolean touched = false;
      for (Map.Entry<String, String> entry : params.entrySet()) {
        String value = entry.getValue();
        if (value == null || !value.trim().startsWith(SENTINEL_PREFIX)) {
          continue;
        }
        // 名字写错当场报错，跟这一轮重不重建无关 —— 写错了永远跑不了，别拖到建完库才说
        String sentinel = sentinelName(value);
        if (!SENTINEL_NAMES.contains(sentinel)) {
          throw new IllegalArgumentException(unknownSentinelMessage(entry.getKey(), sentinel));
        }
        if (deferred()) {
          // 这一轮要 create / load / reload：建表与灌数据是在 job 里做的，文件号要等那之后才存在，
          // 此刻解析出来的必然过期。哨兵**原样**写进配置，由 job 在建完之后、负载与故障注入
          // 开始之前自己解析（DBWorkload#resolveDeferredFaultTargets）。
          resolved.put(entry.getKey(), value);
        } else {
          resolved.put(entry.getKey(), resolve(entry.getKey(), value));
        }
        touched = true;
      }
      return touched ? resolved : params;
    }

    /**
     * 这一轮的文件号在<b>提交这一刻</b>存在吗？
     *
     * <p>{@code create} / {@code load} / {@code reload} 任一为真 → job 会先建表灌数据，文件号
     * 那时候才生成，所以延后到 job 里解析；三个都为假（打库里现成的数据）→ 现在就解析，
     * 解析不了当场拒绝，保持 fail fast。
     */
    private boolean deferred() {
      StartByFormRequest.RunOptions options = request.getRunOptions();
      if (options == null) {
        return false;
      }
      return Boolean.TRUE.equals(options.getCreate())
          || Boolean.TRUE.equals(options.getLoad())
          || Boolean.TRUE.equals(options.getReload());
    }

    private String sentinelName(String value) {
      return value.trim().substring(SENTINEL_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
    }

    private String resolve(String key, String value) {
      String sentinel = sentinelName(value);
      // 先查名字合不合法 —— 写错了不必去连库，直接说清楚，也别让它变成一个"连不上"的假原因
      if (!SENTINEL_NAMES.contains(sentinel)) {
        throw new IllegalArgumentException(unknownSentinelMessage(key, sentinel));
      }
      Map<String, String> values = sentinelValuesOrFail();
      String resolved = values.get(sentinel);
      if (StringUtils.isBlank(resolved)) {
        throw new IllegalArgumentException(unresolvedSentinelMessage(key, sentinel));
      }
      return resolved;
    }

    private Map<String, String> sentinelValuesOrFail() {
      if (failure != null) {
        throw new IllegalArgumentException(failure);
      }
      if (sentinelValues != null) {
        return sentinelValues;
      }
      try {
        String dbType = normalize(request.getDbType());
        String workload = request.getWorkload() == null ? null : request.getWorkload().getName();
        if (StringUtils.isBlank(workload)) {
          failure = "请求里没有 workflow 名，无法定位 benchmark 的 DDL，不能解析 @auto: 哨兵";
          throw new IllegalArgumentException(failure);
        }
        FileFaultTargetReport report =
            probe(toConnectionRequest(request, dbType), dbType, workload.trim());
        sentinelValues = sentinelValues(report);
        LOG.info("@auto: 哨兵解析基准: database={}, dataDirectory={}, 关系数={}",
            report.databaseName(), report.dataDirectory(), report.relations().size());
        return sentinelValues;
      } catch (IllegalArgumentException e) {
        failure = StringUtils.defaultIfBlank(failure, e.getMessage());
        throw e;
      } catch (Exception e) {
        failure = "解析 @auto: 哨兵失败（连库/查询出错）："
            + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getName());
        LOG.error("解析 @auto: 哨兵失败", e);
        throw new IllegalArgumentException(failure, e);
      }
    }

  }

  // ------------------------------------------------------------------ job 侧入口（延后解析）

  /**
   * <b>job 进程</b>用的解析入口：连接由调用方给 —— 用 benchmark 自己的连接，保证"看这个库的文件"
   * 和"往这个库打故障"是同一个目标（这一段跟提交侧的 {@code probe} 是同一份逻辑，只是没有 Spring）。
   *
   * <p>为什么要有这条路：这一轮要 create / load 时，文件号是 job 建完表才生成的，提交那一刻
   * 解析出来的必然过期。所以提交侧把 {@code @auto:} 原样写进配置，job 在建完之后调这里补上。
   */
  public static Map<String, String> resolveSentinels(
      Connection connection, String dbType, String workload) throws Exception {
    String key = probeKey(dbType);
    FileFaultTargetProbe probe = STANDALONE_PROBES.find(key).orElseThrow(() ->
        new IllegalArgumentException(String.format(
            "暂不支持按库类型「%s」挑选 file 故障的目标文件。当前有探针的是：%s",
            dbType, STANDALONE_PROBES.supportedDbTypes())));
    List<String> whitelist = tableWhitelist(workload, dbType);
    return sentinelValues(probe.probe(connection, workload, whitelist));
  }

  /**
   * job 侧递给这里的 dbType 是 <b>枚举名</b>（{@code DatabaseType.POSTGRES.name()} → {@code postgres}），
   * 而探针登记用的是<b>请求侧</b>的名字（{@code postgresql}）。两者只差一个词，但 {@code find} 是精确
   * 匹配 —— 差这一个字母就是「没有探针」，而且是在 job 建完库、正要开跑负载的那一刻才炸，
   * 代价是一整轮。所以入口处先归一到请求侧的名字，和 {@link #ddlSuffix} 处理的是同一件事。
   *
   * <p>只影响探针查表；{@code tableWhitelist} 仍拿原始值（{@code ddlSuffix} 两种写法都认）。
   */
  static String probeKey(String dbType) {
    String value = normalize(dbType);
    return "postgres".equals(value) ? "postgresql" : value;
  }

  /** 这段文本里有没有 {@code @auto:} 哨兵。没有的话 job 一次库都不连。 */
  public static boolean hasSentinel(String text) {
    return text != null && text.contains(SENTINEL_PREFIX);
  }

  /**
   * 把文本里出现的每个 {@code @auto:<名>} 换成解析出来的真实路径。
   *
   * <p>允许哨兵<b>嵌在长字符串里</b>：filemove 的恢复命令是
   * {@code /bin/sh -c "rm -rf '<源>'; blade destroy file move --filepath '<源>' --target '<目标>'"}，
   * 源路径与目标目录都在这一个 arg 里，所以不能只比对"整个 arg 恰好等于哨兵"。
   *
   * <p>名字不认识、或这一轮解析不出值，都直接抛错 —— <b>绝不静默把 {@code @auto:} 字面量放过去</b>，
   * 那样注入的是一个不存在的路径，跑出来是"注入成功但指标毫无变化"的假故障。
   */
  public static String substituteSentinels(String text, Map<String, String> values) {
    if (!hasSentinel(text)) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length() + 32);
    int index = 0;
    while (true) {
      int at = text.indexOf(SENTINEL_PREFIX, index);
      if (at < 0) {
        break;
      }
      out.append(text, index, at);
      int nameStart = at + SENTINEL_PREFIX.length();
      int cursor = nameStart;
      while (cursor < text.length() && isSentinelNameChar(text.charAt(cursor))) {
        cursor++;
      }
      String name = text.substring(nameStart, cursor).toLowerCase(Locale.ROOT);
      if (!SENTINEL_NAMES.contains(name)) {
        throw new IllegalArgumentException(unknownSentinelMessage(null, name));
      }
      String resolved = values == null ? null : values.get(name);
      if (StringUtils.isBlank(resolved)) {
        throw new IllegalArgumentException(unresolvedSentinelMessage(null, name));
      }
      out.append(resolved);
      index = cursor;
    }
    out.append(text, index, text.length());
    return out.toString();
  }

  /** 哨兵名允许的字符：小写字母、数字、连字符。读到别的东西就说明名字到头了。 */
  private static boolean isSentinelNameChar(char c) {
    return c == '-' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /** {@code faultinject.fault.disk.fileappend} → true。单文件布局上 append 没有安全档，要用它判断。 */
  static boolean isAppendFault(String nameKey) {
    String shortKey = StringUtils.trimToEmpty(nameKey);
    int dot = shortKey.lastIndexOf('.');
    if (dot >= 0) {
      shortKey = shortKey.substring(dot + 1);
    }
    return "fileappend".equals(shortKey);
  }

  /** 名字写错的统一说法。key 为空就不提参数名（job 侧手上只有一整条命令）。 */
  static String unknownSentinelMessage(String key, String name) {
    String where = StringUtils.isBlank(key) ? "" : "参数 " + key + " 写了";
    return String.format("%s不认识的哨兵「%s%s」。可用的是：%s", where, SENTINEL_PREFIX, name,
        SENTINEL_NAMES.stream().map(valid -> SENTINEL_PREFIX + valid)
            .collect(Collectors.joining(" / ")));
  }

  /** 名字合法但这个目标这一轮取不到值（最热的关系只有一个段 —— 有的库压根不分段，例如 InnoDB 的 .ibd）。 */
  static String unresolvedSentinelMessage(String key, String name) {
    String where = StringUtils.isBlank(key) ? "" : "参数 " + key + " 的";
    return String.format(
        "%s哨兵「%s%s」这一轮解析不出路径：最热的关系只有一个段（InnoDB 的 .ibd 这类库不分段），"
            + "没有「最后一段」之前的那一段可打。换 %shottest-file 或 %sdb-dir",
        where, SENTINEL_PREFIX, name, SENTINEL_PREFIX, SENTINEL_PREFIX);
  }

  // ------------------------------------------------------------------ 连库 + 探针

  /**
   * 护栏都在这里：没有探针 / 表白名单为空 / 连不上库，一律抛异常，绝不回退到某个写死的路径。
   */
  FileFaultTargetReport probe(TestConnectionRequest connection, String dbType, String workload)
      throws Exception {
    FileFaultTargetProbe probe = probeRegistry.find(dbType).orElseThrow(() ->
        new IllegalArgumentException(String.format(
            "暂不支持按库类型「%s」挑选 file 故障的目标文件。当前有探针的是：%s。"
                + "要接入新库：实现 FileFaultTargetProbe，在 supportedDbTypes() 里声明「%s」即可，"
                + "断言、分组、文案、@auto: 哨兵全部复用",
            dbType, probeRegistry.supportedDbTypes(), dbType)));

    List<String> whitelist = tableWhitelist(workload, dbType);
    if (whitelist.isEmpty()) {
      throw new IllegalArgumentException(String.format(
          "从 benchmark「%s」的 DDL 里没解析出任何 CREATE TABLE，无法确定哪些表算数；"
              + "检查 /benchmarks/%s/ddl-*.sql 是否存在、内容是否正常", workload, workload));
    }

    try (Connection connection1 = testConnectionService.openConnection(connection)) {
      return probe.probe(connection1, workload, whitelist);
    }
  }

  // ------------------------------------------------------------------ 表白名单

  /**
   * 这个负载<b>真正会碰的表</b>，从 benchmark 的 DDL 资源里解析。
   *
   * <p>这是"哪些表算数"的唯一真值来源：库里可能躺着别的负载留下的表（实测有 96 GB、{@code scans=0}
   * 的遗留表），不限定的话按体量排序会优先选中它们，跑出来就是"假故障"。
   */
  static List<String> tableWhitelist(String workload, String dbType) {
    // 请求侧叫 benchmarkSQL，资源目录按引擎内部的 benchmark 名 tpcc 组织 —— 先翻译，复用现成规则
    String normalized = StartByFormService.normalizeWorkloadName(workload);
    String path = ddlResourcePath(normalized, dbType);
    if (path == null) {
      // 归一化后还没有，试一次忽略大小写的目录匹配（名字大小写写错也能救回来）
      String folded = caseInsensitiveBenchmark(normalized);
      if (folded != null) {
        normalized = folded;
        path = ddlResourcePath(normalized, dbType);
      }
    }
    if (path == null) {
      List<String> available = benchmarkDirectories();
      throw new IllegalArgumentException(String.format(
          "没找到 benchmark「%s」的 DDL 资源。%n  归一化后 = %s（引擎内部的 benchmark 名）%n"
              + "  试过：%s%n  可用：%s",
          workload, normalized, String.join("、", candidateDdlPaths(normalized, dbType)),
          available.isEmpty() ? "（列不出来，见 src/main/resources/benchmarks/）" : String.join(" ", available)));
    }
    String ddl;
    try {
      ddl = readResource(path);
    } catch (IOException e) {
      throw new IllegalArgumentException("读取 DDL 资源失败：" + path + "：" + e.getMessage(), e);
    }
    LinkedHashSet<String> names = new LinkedHashSet<>();
    Matcher matcher = CREATE_TABLE.matcher(StringUtils.defaultString(ddl));
    while (matcher.find()) {
      String raw = matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
      String name = stripIdentifier(raw);
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return new ArrayList<>(names);
  }

  private static List<String> candidateDdlPaths(String workload, String dbType) {
    return List.of("/benchmarks/" + workload + "/ddl-" + ddlSuffix(dbType) + ".sql",
        "/benchmarks/" + workload + "/ddl-generic.sql");
  }

  private static String ddlResourcePath(String workload, String dbType) {
    for (String path : candidateDdlPaths(workload, dbType)) {
      if (FileFaultTargetService.class.getResource(path) != null) {
        return path;
      }
    }
    return null;
  }

  /** 忽略大小写找一个存在的 benchmark 目录名；列不出来（比如打成 jar）就返回 null。 */
  private static String caseInsensitiveBenchmark(String normalized) {
    for (String name : benchmarkDirectories()) {
      if (name.equalsIgnoreCase(normalized)) {
        return name;
      }
    }
    return null;
  }

  /**
   * {@code /benchmarks/} 下有哪些目录 —— 只用来让"名字对不上"的报错自解释。
   *
   * <p>引擎是 {@code exec:java} 从 {@code target/classes} 跑的，拿到的是 {@code file:} URL，
   * 列得出来；万一在 jar 里列不出来就返回空集合，<b>不报错</b>（列不出来只是少了个提示）。
   */
  private static List<String> benchmarkDirectories() {
    try {
      URL root = FileFaultTargetService.class.getResource("/benchmarks");
      if (root == null || !"file".equals(root.getProtocol())) {
        return List.of();
      }
      try (Stream<Path> paths = Files.list(Path.of(root.toURI()))) {
        return paths.filter(Files::isDirectory)
            .map(path -> path.getFileName().toString())
            .sorted()
            .toList();
      }
    } catch (Exception e) {
      return List.of();
    }
  }

  /**
   * 请求侧用的 dbType 是 {@code postgresql}，而枚举名与 DDL 文件名都是 {@code postgres}
   * （{@code DatabaseType.get} 只认枚举名，认不出 postgresql），所以这里补一层别名。
   */
  static String ddlSuffix(String dbType) {
    String value = normalize(dbType);
    DatabaseType type = value.isEmpty() ? null : DatabaseType.get(value);
    if (type != null) {
      return type.name().toLowerCase(Locale.ROOT);
    }
    return "postgresql".equals(value) ? "postgres" : value;
  }

  /** {@code public.foo} / {@code `foo`} / {@code [foo]} → {@code foo}。 */
  private static String stripIdentifier(String raw) {
    String value = StringUtils.trimToEmpty(raw);
    int dot = value.lastIndexOf('.');
    if (dot >= 0) {
      value = value.substring(dot + 1);
    }
    value = StringUtils.strip(value, "`\"[]");
    return value;
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = FileFaultTargetService.class.getResourceAsStream(path)) {
      if (in == null) {
        return null;
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ------------------------------------------------------------------ 中性数据 → 候选清单

  /**
   * 全部 {@code @auto:} 目标（{@code datadir} 与 {@code pgdata} 共用 {@code dataDirectory} 一项，
   * 它们是同一个东西的两个名字）。{@code hottestMidSegment} 可能为 null —— 最热的关系只有一个段，
   * 或者这个库压根不分段（InnoDB 的 .ibd）。
   */
  public record Targets(String dbDir, String hottestFile, String hottestLastSegment,
      String hottestMidSegment, String dataDirectory) {}

  static Map<String, String> sentinelValues(FileFaultTargetReport report) {
    Targets targets = targets(report);
    Map<String, String> values = new LinkedHashMap<>();
    values.put("db-dir", targets.dbDir());
    values.put("hottest-file", targets.hottestFile());
    values.put("hottest-last-segment", targets.hottestLastSegment());
    values.put("hottest-mid-segment", targets.hottestMidSegment());
    values.put("datadir", targets.dataDirectory());
    values.put("pgdata", targets.dataDirectory());
    return values;
  }

  /** 排序 + 取头名。纯函数，便于单测。 */
  static Targets targets(FileFaultTargetReport report) {
    List<RelationTarget> ranked = rank(report);
    RelationTarget hottest = ranked.isEmpty() ? null : ranked.get(0);
    RelationTarget multi = ranked.stream().filter(r -> r.segments() >= 2).findFirst().orElse(null);
    return new Targets(
        report.databaseDirectory(),
        hottest == null ? null : hottest.firstSegment(),
        hottest == null ? null : hottest.lastSegment(),
        multi == null ? null : multi.midSegment(),
        report.dataDirectory());
  }

  /** 按 {@code rankBasis} 排序：热度可信看 scans，不可信看体量；并列再比另一项，最后按名字，保证可复现。 */
  static List<RelationTarget> rank(FileFaultTargetReport report) {
    List<RelationTarget> relations = new ArrayList<>(report.relations());
    Comparator<RelationTarget> comparator = report.statsFresh()
        ? Comparator.comparingLong(RelationTarget::scans).reversed()
            .thenComparing(Comparator.comparingLong(RelationTarget::bytes).reversed())
        : Comparator.comparingLong(RelationTarget::bytes).reversed()
            .thenComparing(Comparator.comparingLong(RelationTarget::scans).reversed());
    relations.sort(comparator.thenComparing(RelationTarget::name));
    return relations;
  }

  /**
   * 按"目录 → 每张表（表下挂它的堆文件和它的各个索引）"分组。
   *
   * <p>纯函数：所有标签、说明、{@code suggestFor} 都在这里生成，探针不参与 —— 接了新库，清单的样子和
   * 说法自动一致。
   */
  static List<Group> buildGroups(FileFaultTargetReport report, String nameKey, String level) {
    List<Group> groups = new ArrayList<>();
    groups.add(directoryGroup(report, nameKey, level));

    List<RelationTarget> ranked = rank(report);
    List<RelationTarget> tables = ranked.stream().filter(r -> !r.isIndex()).toList();
    List<RelationTarget> indexes = ranked.stream().filter(RelationTarget::isIndex).toList();
    if (tables.size() + indexes.size() > RELATION_LIMIT) {
      // 截断了要说出来，别让清单读起来像"只有这些"
      int keepTables = Math.min(tables.size(), RELATION_LIMIT);
      tables = tables.subList(0, keepTables);
      int keepIndexes = Math.max(0, RELATION_LIMIT - keepTables);
      indexes = indexes.subList(0, Math.min(indexes.size(), keepIndexes));
    }

    boolean hottestMarked = false;
    List<String> consumedIndexes = new ArrayList<>();
    for (RelationTarget table : tables) {
      boolean hottest = !hottestMarked;
      Group group = new Group();
      group.setTitle(table.name());
      group.setKind("table");
      group.setSubtitle(subtitle("表", table, report.statsFresh() && hottest));
      hottestMarked = true;
      group.setItems(fileItems(table, "堆文件", "（表）", nameKey, level, hottest));

      for (RelationTarget index : indexes) {
        if (!StringUtils.equals(index.ownerTable(), table.name())) {
          continue;
        }
        consumedIndexes.add(index.name());
        Group child = new Group();
        child.setTitle(index.name());
        child.setKind("index");
        child.setSubtitle(subtitle("索引", index, false));
        child.setItems(fileItems(index, "索引文件",
            "（" + table.name() + " 的索引）", nameKey, level, false));
        group.getChildren().add(child);
      }
      groups.add(group);
    }

    // 索引查到了、但它的表不在白名单里（理论上不会），也不要悄悄丢掉
    List<RelationTarget> orphans = indexes.stream()
        .filter(index -> !consumedIndexes.contains(index.name()))
        .toList();
    if (!orphans.isEmpty()) {
      Group group = new Group();
      group.setTitle("其它索引");
      group.setKind("index");
      group.setSubtitle("归属的表不在本次负载的表集合里");
      for (RelationTarget index : orphans) {
        group.getItems().addAll(fileItems(index, "索引文件",
            "（" + StringUtils.defaultString(index.ownerTable()) + "）", nameKey, level, false));
      }
      groups.add(group);
    }
    return groups;
  }

  private static Group directoryGroup(FileFaultTargetReport report, String nameKey, String level) {
    Group group = new Group();
    group.setTitle("目录");
    group.setKind("directories");

    Item databaseDir = new Item();
    databaseDir.setPath(report.databaseDirectory());
    databaseDir.setKind("directory");
    databaseDir.setLabel("整个库目录");
    databaseDir.setBelongsTo("database " + report.databaseName()
        + (StringUtils.isBlank(report.databaseId()) ? "" : "（oid " + report.databaseId() + "）"));
    databaseDir.setRole("db-dir");
    databaseDir.setSuggestFor(List.of("filechmod:high", "filemove:high"));
    databaseDir.setNote("这个库自己的目录，也就是高强度档要打的那个。chmod 000 会去掉目录的 x 位，"
        + "目录下所有文件都打不开；move 走则整库从数据总目录里消失。");
    group.getItems().add(databaseDir);

    Item dataDir = new Item();
    dataDir.setPath(report.dataDirectory());
    dataDir.setKind("directory");
    dataDir.setLabel("数据总目录");
    dataDir.setBelongsTo("整个实例");
    dataDir.setRole("datadir");
    dataDir.setSuggestFor(List.of("filemove:dst-dir"));
    dataDir.setNote("move 故障的落点目录：它在库目录的上一层，同一个文件系统，所以改文件名是瞬间完成的"
        + "（不会像跨盘那样拷数据）。当打击目标会把别的库和配置一起卷进来，不建议。");
    group.getItems().add(dataDir);

    applyRecommendation(group, nameKey, level);
    return group;
  }

  private static List<Item> fileItems(RelationTarget relation, String prefix, String belongsSuffix,
      String nameKey, String level, boolean hottest) {
    List<Item> items = new ArrayList<>();
    String belongsTo = relation.name() + belongsSuffix;
    if (relation.segments() <= 1) {
      // 只有一个段（InnoDB 的 .ibd，或者 PG 里还没长到第二个段的表）：这一个文件既是第 0 段
      // 也是最后一段。低档只推**最热的那一个** —— 每张表都标★等于没标；而 append 在这一类
      // 文件上没有"非最后一段"可打，写了就是往主数据文件末尾追加，不标推荐（理由见 note）。
      items.add(item(relation, relation.firstSegment(), prefix + " · 唯一一段",
          belongsTo, "only-segment",
          hottest ? List.of("filechmod:low", "filemove:low") : List.of(),
          "唯一的文件（这个库不分段，或者这张表还没长到第二个段）：chmod / move 打它是小范围命中。"
              + "append 在单段文件上没有「非最后一段」这一档可选，每一档都落在主数据文件末尾，"
              + "而引擎渲染的 append 命令不传 --enable-backup —— 写进去恢复不了，所以不标推荐。",
          nameKey, level));
      return items;
    }
    items.add(item(relation, relation.firstSegment(), prefix + " · 第 0 段",
        belongsTo, "first-segment",
        List.of("filechmod:low", "filemove:low"),
        "第 0 段（含元页）。覆盖范围小，但每次扫这张表都要从它开头读。",
        nameKey, level));
    items.add(item(relation, relation.lastSegment(),
        prefix + " · 最后一段（第 " + (relation.segments() - 1) + " 段）",
        belongsTo, "last-segment",
        List.of("fileappend:high"),
        "最后一段：追加进去的字节落在文件末尾。⚠ 引擎渲染的 append 命令不传 --enable-backup，"
            + "追加进去恢复不了，故障窗口过去之后这个文件可能还是坏的。",
        nameKey, level));
    items.add(item(relation, relation.midSegment(), prefix + " · 第 1 段（非最后）",
        belongsTo, "mid-segment",
        List.of("fileappend:low"),
        "非最后一段：关系长度取自编号最大的段，写在这里的字节不会被读到 → 近似无害，"
            + "是 append 的「低」端。",
        nameKey, level));
    return items;
  }

  private static Item item(RelationTarget relation, String path, String label, String belongsTo,
      String role, List<String> suggestFor, String note, String nameKey, String level) {
    Item item = new Item();
    item.setPath(path);
    item.setKind("file");
    item.setLabel(label);
    item.setBelongsTo(belongsTo);
    item.setRole(role);
    item.setBytes(relation.bytes());
    item.setSegments(relation.segments());
    item.setScans(relation.scans());
    item.setSuggestFor(suggestFor);
    item.setNote(note);
    item.setRecommended(matches(suggestFor, nameKey, level));
    return item;
  }

  private static void applyRecommendation(Group group, String nameKey, String level) {
    for (Item item : group.getItems()) {
      item.setRecommended(matches(item.getSuggestFor(), nameKey, level));
    }
  }

  /** {@code faultinject.fault.disk.filemove} + {@code low} → 命中 {@code "filemove:low"}。 */
  private static boolean matches(List<String> suggestFor, String nameKey, String level) {
    String shortKey = StringUtils.trimToEmpty(nameKey);
    int dot = shortKey.lastIndexOf('.');
    if (dot >= 0) {
      shortKey = shortKey.substring(dot + 1);
    }
    if (shortKey.isEmpty() || suggestFor == null) {
      return false;
    }
    String wanted = shortKey + ":" + StringUtils.trimToEmpty(level);
    for (String candidate : suggestFor) {
      if (StringUtils.isBlank(level) ? candidate.startsWith(shortKey + ":") : candidate.equals(wanted)) {
        return true;
      }
    }
    return false;
  }

  private static String subtitle(String kind, RelationTarget relation, boolean hottest) {
    StringBuilder builder = new StringBuilder(kind);
    if (hottest) {
      builder.append(" · 最热");
    }
    builder.append(" · ").append(String.format("%,d", relation.scans())).append(" 次扫描")
        .append(" · ").append(humanBytes(relation.bytes()))
        .append(" · ").append(relation.segments()).append(" 段");
    return builder.toString();
  }

  static String humanBytes(long bytes) {
    if (bytes >= 1L << 30) {
      return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
    }
    if (bytes >= 1L << 20) {
      return String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1L << 20));
    }
    if (bytes >= 1L << 10) {
      return String.format(Locale.ROOT, "%.1f KB", bytes / (double) (1L << 10));
    }
    return bytes + " B";
  }

  // ------------------------------------------------------------------ 请求转换

  private static TestConnectionRequest toConnectionRequest(FileFaultTargetRequest request,
      String dbType) {
    TestConnectionRequest connection = new TestConnectionRequest();
    connection.setEnvInfo(request.getEnvInfo());
    connection.setDbType(dbType);
    connection.setDeploymentType(request.getDeploymentType());
    TestConnectionRequest.StandaloneDatabase standalone = new TestConnectionRequest.StandaloneDatabase();
    if (request.getStandalone() != null) {
      standalone.setIp(request.getStandalone().getIp());
      standalone.setPort(request.getStandalone().getPort());
      standalone.setUsername(request.getStandalone().getUsername());
      standalone.setPassword(request.getStandalone().getPassword());
    }
    connection.setStandalone(standalone);
    TestConnectionRequest.DistributedDatabase distributed = new TestConnectionRequest.DistributedDatabase();
    if (request.getDistributed() != null) {
      distributed.setIp(request.getDistributed().getIp());
      distributed.setPort(request.getDistributed().getPort());
      distributed.setUsername(request.getDistributed().getUsername());
      distributed.setPassword(request.getDistributed().getPassword());
    }
    connection.setDistributed(distributed);
    return connection;
  }

  /**
   * 压测开始前解析 {@code @auto:} 用的连接信息。file/disk 故障都是往<b>单机</b>目标上打的，
   * 所以这里只认 standalone；分布式部署（OceanBase 等）本来也没有 file 故障的探针。
   */
  private static TestConnectionRequest toConnectionRequest(StartByFormRequest request, String dbType) {
    TestConnectionRequest connection = new TestConnectionRequest();
    connection.setEnvInfo(request.getEnvInfo());
    connection.setDbType(dbType);
    StartByFormRequest.Database database = request.getDatabase();
    connection.setDeploymentType(database == null ? "standalone" : database.getDeploymentType());

    TestConnectionRequest.StandaloneDatabase standalone = new TestConnectionRequest.StandaloneDatabase();
    if (database != null && database.getStandalone() != null) {
      standalone.setIp(database.getStandalone().getIp());
      standalone.setPort(database.getStandalone().getPort());
      standalone.setUsername(database.getStandalone().getUsername());
      standalone.setPassword(database.getStandalone().getPassword());
    }
    connection.setStandalone(standalone);
    connection.setDistributed(new TestConnectionRequest.DistributedDatabase());
    return connection;
  }

  private static String param(Map<String, String> params, String key) {
    return params == null ? null : params.get(key);
  }

  /** 与 {@code TpccDataVerifyService.normalizeDbType} 同语义。 */
  private static String normalize(String dbType) {
    String value = StringUtils.trimToEmpty(dbType).toLowerCase(Locale.ROOT);
    return "postgre".equals(value) ? "postgresql" : value;
  }
}
