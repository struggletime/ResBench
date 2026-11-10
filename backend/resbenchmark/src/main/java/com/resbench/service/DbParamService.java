package com.resbench.service;

import com.resbench.exception.FaultInjectException;
import com.resbench.pojo.dto.DbParamRequest;
import com.resbench.pojo.dto.DbParamResponse;
import com.resbench.pojo.dto.TestConnectionRequest;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 数据库参数应用（只做单机 openGauss / PostgreSQL / MySQL）。
 *
 * <p>只处理两个语义化参数，参数名由本服务按 dbType 映射，避免调用方写错 GUC 名被静默忽略：
 * <ul>
 *   <li>{@code bufferPool}：og/pg → {@code shared_buffers}（{@code ALTER SYSTEM SET}）；mysql →
 *       {@code innodb_buffer_pool_size}（{@code SET GLOBAL}，单位必须是字节）</li>
 *   <li>{@code maxConnections}：都是 {@code max_connections}</li>
 * </ul>
 *
 * <p>流程：读现状 → 目标值已生效就直接返回（不写库不重启）→ 应用 → 需要重启则经 agent 下发
 * <b>显式配置</b>的重启命令并等它重新连上 → 读回核对。
 *
 * <p><b>不做还原</b>：应用后保持改后的状态（与既有约定一致）。
 */
@Service
public class DbParamService {
  private static final Logger LOG = LoggerFactory.getLogger(DbParamService.class);

  public static final String BUFFER_POOL = "bufferPool";
  public static final String MAX_CONNECTIONS = "maxConnections";

  private static final Set<String> SUPPORTED_TYPES = Set.of("postgresql", "opengauss", "mysql");
  private static final String MYSQL = "mysql";
  private static final String PG_BUFFER_PARAM = "shared_buffers";
  private static final String MYSQL_BUFFER_PARAM = "innodb_buffer_pool_size";
  private static final String CONN_PARAM = "max_connections";
  /** MySQL 把缓冲池向上取整到 chunk_size × instances，核对要用这个粒度做容差 */
  private static final String MYSQL_CHUNK_PARAM = "innodb_buffer_pool_chunk_size";
  private static final String MYSQL_INSTANCES_PARAM = "innodb_buffer_pool_instances";

  private static final String PG_SETTINGS_SQL =
      "SELECT name, setting, unit, context FROM pg_settings WHERE name IN (%s)";

  private static final int RECONNECT_INTERVAL_MS = 2000;
  private static final int DEFAULT_WAIT_SEC = 180;
  private static final int MIN_WAIT_SEC = 10;
  private static final int MAX_WAIT_SEC = 600;
  private static final int DEFAULT_AGENT_PORT = 8000;
  private static final long MAX_BYTES = 8L * 1024 * 1024 * 1024 * 1024;
  private static final Pattern UNIT_PATTERN = Pattern.compile("^(\\d*)\\s*([kKmMgGtT]?)[bB]?$");
  /** 容量写法：数字（可带小数）+ 可选 K/M/G/T + 可选 B。用白名单正则挡掉 NaN/Infinity/负数/科学计数法。 */
  private static final Pattern BYTES_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([KMGT]?)B?$");

  private final TestConnectionService testConnectionService;

  public DbParamService(TestConnectionService testConnectionService) {
    this.testConnectionService = testConnectionService;
  }

  public DbParamResponse handle(DbParamRequest request) {
    long started = System.currentTimeMillis();
    DbParamResponse response = new DbParamResponse();
    try {
      String dbType = normalizeDbType(request);
      validateStandalone(request);
      response.setDbType(dbType);

      // 客户端显式发 "restart": null 时 Jackson 会把字段置成 null（默认值只在字段缺失时生效）。
      // 这里统一兜住：否则后面到处判空，而且 NPE 会把"必须配置重启命令"这条最该讲清的提示
      // 变成 "Cannot invoke ... because ... is null"。
      DbParamRequest.Restart restart =
          request.getRestart() == null ? new DbParamRequest.Restart() : request.getRestart();

      Map<String, String> actualNames = actualNames(dbType);
      response.setActualNames(actualNames);

      Map<String, String> targets = resolveTargets(request, response);
      if (targets.isEmpty()) {
        throw new IllegalArgumentException("请先填写要修改的参数（缓冲池大小 / 最大连接数上限）");
      }
      response.setTargets(targets);

      // 语句先生成好：连不上数据库时也能看到"本来要执行什么"（排查用），只算这一次，下面复用
      Map<String, String> statements = buildStatements(dbType, actualNames, targets);
      response.setStatements(new ArrayList<>(statements.values()));

      Map<String, Setting> before = readSettings(request, dbType, readNames(dbType, actualNames, targets));
      response.setBefore(friendlyAll(actualNames, before));

      boolean matched = targets.entrySet().stream()
          .allMatch(entry -> valueMatches(
              before.get(actualNames.get(entry.getKey())),
              entry.getValue(),
              granuleFor(entry.getKey(), dbType, before)));
      if (matched) {
        // 共用库：能不动就不动，避免白重启一次
        response.setAlreadyEffective(true);
        response.setSuccess(true);
        response.setAfter(new LinkedHashMap<>(response.getBefore()));
        // 一条都没执行，别把语句留在排查字段里误导人
        response.setStatements(new ArrayList<>());
        response.setMessage("参数已是目标值（" + describe(targets) + "），未做任何改动");
        response.setElapsedMs(System.currentTimeMillis() - started);
        return response;
      }

      executeAll(request, statements.values());
      response.setApplied(true);

      boolean requiresRestart = requiresRestart(actualNames.values(), before);
      response.setRequiresRestart(requiresRestart);

      if (requiresRestart) {
        String command = StringUtils.trimToEmpty(restart.getCommand());
        if (command.isEmpty()) {
          // 服务端不猜部署方式（systemd / docker / gs_ctl 差别太大）→ 必须显式配置
          response.setSuccess(false);
          response.setMessage("已写入参数，但 " + describe(actualNames, targets)
              + " 属于 postmaster 参数，必须重启数据库才生效；你还没有配置重启命令。" + restartExample(dbType, request));
          response.setElapsedMs(System.currentTimeMillis() - started);
          return response;
        }
        String failure = restartAndWait(request, response, command, waitSec(restart));
        if (failure != null) {
          // 失败原因由 restartAndWait 给全（agent 没起 / 命令返回非 0 / 重连超时 各不相同），
          // 这里不要再统一改写成"已下发重启命令"，那会把用户引到错误方向
          response.setSuccess(false);
          response.setMessage(failure);
          response.setElapsedMs(System.currentTimeMillis() - started);
          return response;
        }
      }

      Map<String, Setting> after = readSettings(request, dbType, actualNames.values());
      response.setAfter(friendlyAll(actualNames, after));
      response.setMismatches(compare(dbType, actualNames, targets, after));
      response.setSuccess(response.getMismatches().isEmpty());
      response.setMessage(successMessage(response));
      response.setElapsedMs(System.currentTimeMillis() - started);
      return response;
    } catch (IllegalArgumentException e) {
      response.setSuccess(false);
      response.setMessage(e.getMessage());
      response.setElapsedMs(System.currentTimeMillis() - started);
      return response;
    } catch (Exception e) {
      LOG.error("数据库参数操作失败: dbType={}, params={}", request.getDbType(), request.getParams(), e);
      response.setSuccess(false);
      response.setMessage(translateError(e));
      response.setElapsedMs(System.currentTimeMillis() - started);
      return response;
    }
  }

  // ------------------------------------------------------------------ 目标解析与校验

  private static String normalizeDbType(DbParamRequest request) {
    String value = normalizeAlias(request.getDbType()).toLowerCase(Locale.ROOT);
    if (!SUPPORTED_TYPES.contains(value)) {
      throw new IllegalArgumentException(
          "数据库参数应用目前只支持单机的 postgresql / opengauss / mysql，当前=" + request.getDbType());
    }
    return value;
  }

  /** postgre 是 postgresql 的别名；连接测试的 switch 只认后者，别让别名漏下去。 */
  private static String normalizeAlias(String dbType) {
    String value = StringUtils.trimToEmpty(dbType);
    return "postgre".equalsIgnoreCase(value) ? "postgresql" : value;
  }

  private static void validateStandalone(DbParamRequest request) {
    String deploymentType = StringUtils.trimToEmpty(request.getDeploymentType()).toLowerCase(Locale.ROOT);
    if (!"standalone".equals(deploymentType)) {
      throw new IllegalArgumentException(
          "数据库参数应用只支持单机数据库（deploymentType=standalone），当前=" + request.getDeploymentType());
    }
    DbParamRequest.StandaloneDatabase standalone = request.getStandalone();
    if (standalone == null
        || StringUtils.isBlank(standalone.getIp())
        || StringUtils.isBlank(standalone.getPort())
        || StringUtils.isBlank(standalone.getUsername())
        || StringUtils.isBlank(standalone.getPassword())) {
      throw new IllegalArgumentException("数据库连接信息不完整（需要 ip / port / username / password）");
    }
  }

  private static Map<String, String> actualNames(String dbType) {
    Map<String, String> names = new LinkedHashMap<>();
    names.put(BUFFER_POOL, MYSQL.equals(dbType) ? MYSQL_BUFFER_PARAM : PG_BUFFER_PARAM);
    names.put(MAX_CONNECTIONS, CONN_PARAM);
    return names;
  }

  /**
   * 要读回来的参数名。MySQL 要多读两个：核对缓冲池时得用 chunk_size × instances 当取整容差，
   * 否则用户填 100M（MySQL 会取整成 128M）会被误报成"没生效"。
   */
  private static Collection<String> readNames(
      String dbType, Map<String, String> actualNames, Map<String, String> targets) {
    List<String> names = new ArrayList<>(actualNames.values());
    if (MYSQL.equals(dbType) && targets.containsKey(BUFFER_POOL)) {
      names.add(MYSQL_CHUNK_PARAM);
      names.add(MYSQL_INSTANCES_PARAM);
    }
    return names;
  }

  /** 语义化键 → 目标值；空值表示"这个参数不动"，不认识的键记进 ignored。 */
  private static Map<String, String> resolveTargets(DbParamRequest request, DbParamResponse response) {
    Map<String, String> raw = request.getParams() == null ? Map.of() : request.getParams();
    Map<String, String> targets = new LinkedHashMap<>();
    List<String> unknown = new ArrayList<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      String key = StringUtils.trimToEmpty(entry.getKey());
      String value = StringUtils.trimToEmpty(entry.getValue());
      if (value.isEmpty()) {
        continue;
      }
      if (BUFFER_POOL.equalsIgnoreCase(key)) {
        targets.put(BUFFER_POOL, value);
      } else if (MAX_CONNECTIONS.equalsIgnoreCase(key)) {
        targets.put(MAX_CONNECTIONS, value);
      } else if (StringUtils.isNotBlank(key)) {
        unknown.add(key);
      }
    }
    response.setIgnored(unknown);

    String bufferPool = targets.get(BUFFER_POOL);
    if (bufferPool != null && parseBytes(bufferPool) == null) {
      throw new IllegalArgumentException(
          "缓冲池大小无法识别：" + bufferPool + "（支持 512M / 2GB / 2147483648 这类写法）");
    }
    String maxConnections = targets.get(MAX_CONNECTIONS);
    if (maxConnections != null
        && (!maxConnections.matches("\\d+") || Long.parseLong(maxConnections) <= 0)) {
      throw new IllegalArgumentException("最大连接数上限必须是正整数，当前=" + maxConnections);
    }
    return targets;
  }

  // ------------------------------------------------------------------ SQL 生成

  private static Map<String, String> buildStatements(
      String dbType, Map<String, String> actualNames, Map<String, String> targets) {
    Map<String, String> statements = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : targets.entrySet()) {
      String param = actualNames.get(entry.getKey());
      String value = entry.getValue();
      if (MYSQL.equals(dbType)) {
        statements.put(entry.getKey(), "SET GLOBAL " + param + " = " + mysqlLiteral(value));
      } else {
        statements.put(
            entry.getKey(), "ALTER SYSTEM SET " + param + " = '" + value.replace("'", "''") + "'");
      }
    }
    return statements;
  }

  /** MySQL 的容量类参数单位是字节，必须换算；纯数字原样；其余当字符串加引号。 */
  private static String mysqlLiteral(String value) {
    if (value.matches("\\d+")) {
      return value;
    }
    Long bytes = parseBytes(value);
    if (bytes != null) {
      return String.valueOf(bytes);
    }
    return "'" + value.replace("'", "''") + "'";
  }

  /** 解析 2G / 2GB / 512M / 2147483648 这类容量或纯数字写法为字节数；解析不了返回 null。 */
  static Long parseBytes(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    // 用白名单正则收口：Double.parseDouble 会放行 NaN / Infinity / 1e9 / 0x1p3，还会放过负数，
    // 这些值一旦落到 MySQL 的 SET GLOBAL 上就是把库改坏或报数据库原始错误，
    // 必须在这里当成"无法识别"挡掉（返回 null 由调用方报输入不合法）。
    Matcher matcher = BYTES_PATTERN.matcher(value.trim().toUpperCase(Locale.ROOT));
    if (!matcher.matches()) {
      return null;
    }
    long multiplier = switch (matcher.group(2)) {
      case "K" -> 1024L;
      case "M" -> 1024L * 1024;
      case "G" -> 1024L * 1024 * 1024;
      case "T" -> 1024L * 1024 * 1024 * 1024;
      default -> 1L;
    };
    try {
      double bytes = Double.parseDouble(matcher.group(1)) * multiplier;
      // NaN / 负数 / 0 / 大到离谱 一并拒掉
      if (!(bytes > 0) || bytes > MAX_BYTES) {
        return null;
      }
      return (long) bytes;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 把 DB 报告的单位（8kB / kB / MB …）换算成字节；无单位返回 null。 */
  static Long unitBytes(String unit) {
    String text = StringUtils.trimToEmpty(unit);
    if (text.isEmpty()) {
      return null;
    }
    Matcher matcher = UNIT_PATTERN.matcher(text);
    if (!matcher.matches()) {
      return null;
    }
    long count = matcher.group(1).isEmpty() ? 1L : Long.parseLong(matcher.group(1));
    long base = switch (StringUtils.upperCase(matcher.group(2))) {
      case "K" -> 1024L;
      case "M" -> 1024L * 1024;
      case "G" -> 1024L * 1024 * 1024;
      case "T" -> 1024L * 1024 * 1024 * 1024;
      default -> 1L;
    };
    return count * base;
  }

  // ------------------------------------------------------------------ 读 / 写 / 核对

  private Map<String, Setting> readSettings(DbParamRequest request, String dbType, Collection<String> names)
      throws Exception {
    Map<String, Setting> settings = new LinkedHashMap<>();
    String in = names.stream().map(name -> "'" + name + "'").reduce((a, b) -> a + "," + b).orElse("''");
    try (Connection connection = openConnection(request);
        Statement statement = connection.createStatement()) {
      if (MYSQL.equals(dbType)) {
        try (ResultSet rs =
            statement.executeQuery("SHOW VARIABLES WHERE Variable_name IN (" + in + ")")) {
          while (rs.next()) {
            settings.put(
                rs.getString(1).toLowerCase(Locale.ROOT), new Setting(rs.getString(2), null, null));
          }
        }
      } else {
        try (ResultSet rs = statement.executeQuery(String.format(PG_SETTINGS_SQL, in))) {
          while (rs.next()) {
            settings.put(
                rs.getString("name").toLowerCase(Locale.ROOT),
                new Setting(rs.getString("setting"), rs.getString("unit"), rs.getString("context")));
          }
        }
      }
    }
    return settings;
  }

  private void executeAll(DbParamRequest request, Collection<String> statements) throws Exception {
    try (Connection connection = openConnection(request);
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        LOG.info("执行数据库参数变更: {}", sql);
        statement.execute(sql);
      }
    }
  }

  /** 读回核对：DB 以 8kB 块回报的要换算到字节再比，MySQL 的字节值才能和 "2G" 这种写法对上。 */
  private static List<String> compare(
      String dbType,
      Map<String, String> actualNames,
      Map<String, String> targets,
      Map<String, Setting> after) {
    List<String> mismatches = new ArrayList<>();
    for (Map.Entry<String, String> entry : targets.entrySet()) {
      String semantic = entry.getKey();
      String param = actualNames.get(semantic);
      Setting setting = after.get(param);
      if (setting == null) {
        mismatches.add(label(semantic) + "（" + param + "）读不到");
        continue;
      }
      if (!valueMatches(setting, entry.getValue(), granuleFor(semantic, dbType, after))) {
        mismatches.add(label(semantic) + "（" + param + "）：期望 "
            + friendly(semantic, new Setting(entry.getValue(), null, null))
            + "，实际读回 " + friendly(semantic, setting));
      }
    }
    return mismatches;
  }

  /**
   * 读回的值算不算"已经是目标值"。
   *
   * 容量类参数会被数据库向上取整：PG 的 shared_buffers 以 8kB 块为单位，MySQL 的
   * innodb_buffer_pool_size 会向上取整到 chunk_size × instances。用户填 100M 时
   * MySQL 实际生效 128M —— 这是成功，不是失败。所以多出来的部分不到一个粒度就算通过
   * （粒度拿不到时仍按严格相等比）。
   */
  private static boolean valueMatches(Setting setting, String target, Long granule) {
    if (setting == null) {
      return false;
    }
    Long actual = parseBytes(setting.setting());
    Long unitBytes = unitBytes(setting.unit());
    Long expected = parseBytes(target);
    if (actual != null && unitBytes != null) {
      actual = actual * unitBytes;
    }
    if (actual != null && expected != null) {
      if (actual.equals(expected)) {
        return true;
      }
      // 只接受"向上取整"：actual < expected 说明参数没生效，不能放行
      return granule != null && granule > 0 && actual > expected && actual - expected < granule;
    }
    return StringUtils.trimToEmpty(setting.setting())
        .equalsIgnoreCase(StringUtils.trimToEmpty(target));
  }

  /**
   * 该参数在这个库上的取整粒度（字节）；非容量参数或拿不到粒度时返回 null。
   * 必须用**读回后**的 settings 算：MySQL 的 instances 会随缓冲池大小变化。
   */
  private static Long granuleFor(String semantic, String dbType, Map<String, Setting> settings) {
    if (!BUFFER_POOL.equals(semantic)) {
      return null;
    }
    if (MYSQL.equals(dbType)) {
      Long chunk = parseBytes(valueOf(settings.get(MYSQL_CHUNK_PARAM)));
      Long instances = parseBytes(valueOf(settings.get(MYSQL_INSTANCES_PARAM)));
      if (chunk == null || instances == null) {
        return null;
      }
      return chunk * instances;
    }
    Setting setting = settings.get(PG_BUFFER_PARAM);
    return setting == null ? null : unitBytes(setting.unit());
  }

  private static String valueOf(Setting setting) {
    return setting == null ? null : setting.setting();
  }

  private static boolean requiresRestart(Collection<String> params, Map<String, Setting> before) {
    for (String param : params) {
      Setting setting = before.get(param);
      // PG 的 context：postmaster = 必须重启；sighup = reload；user = 立即生效
      if (setting != null
          && "postmaster".equalsIgnoreCase(StringUtils.trimToEmpty(setting.context()))) {
        return true;
      }
    }
    return false;
  }

  /** 展示用：容量类参数换算成人类可读单位，其余原样。 */
  private static String friendly(String semantic, Setting setting) {
    if (setting == null) {
      return "读不到";
    }
    String raw = StringUtils.trimToEmpty(setting.setting());
    if (!BUFFER_POOL.equals(semantic)) {
      return raw;
    }
    Long value = parseBytes(raw);
    if (value == null) {
      return raw;
    }
    Long unitBytes = unitBytes(setting.unit());
    return humanize(unitBytes == null ? value : value * unitBytes);
  }

  private static String humanize(long bytes) {
    long gb = 1024L * 1024 * 1024;
    long mb = 1024L * 1024;
    if (bytes >= gb && bytes % gb == 0) {
      return (bytes / gb) + "GB";
    }
    if (bytes >= mb && bytes % mb == 0) {
      return (bytes / mb) + "MB";
    }
    if (bytes >= 1024L && bytes % 1024L == 0) {
      return (bytes / 1024L) + "KB";
    }
    return bytes + "B";
  }

  private static Map<String, String> friendlyAll(
      Map<String, String> actualNames, Map<String, Setting> settings) {
    Map<String, String> display = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : actualNames.entrySet()) {
      display.put(entry.getKey(), friendly(entry.getKey(), settings.get(entry.getValue())));
    }
    return display;
  }

  // ------------------------------------------------------------------ 重启（经 agent）

  /**
   * 经 agent 下发重启命令并轮询重连。
   *
   * @return null 表示已重启且重连成功；否则是给人看的失败原因（调用方直接用，不要再改写）
   */
  private String restartAndWait(
      DbParamRequest request, DbParamResponse response, String command, int waitSec) {
    String agentHost = "http://" + StringUtils.trimToEmpty(request.getStandalone().getIp()) + ":"
        + (request.getAgentPort() == null ? DEFAULT_AGENT_PORT : request.getAgentPort());
    response.setRestartCommand(command);
    response.setWaitedSec(0);

    FaultInjectUtil.FaultInjectResult result;
    try {
      // 重启命令用 detach 写法（nohup ... &）时 agent 立刻返回；真正的判据是下面的重连
      result = FaultInjectUtil.execFaultInjectCommand(agentHost, command, waitSec + 30);
    } catch (FaultInjectException e) {
      response.setRestarted(false);
      return "已写入参数，但无法通过 agent（" + agentHost + "）下发重启命令：" + e.getMessage()
          + "。请确认 agent 已在数据库主机上运行";
    }
    String output = StringUtils.defaultString(result.getStdout())
        + StringUtils.defaultString(result.getStderr());
    response.setRestartOutput(output);
    if (result.getReturncode() != null && result.getReturncode() != 0) {
      // 命令本身就失败了（路径写错 / 服务名不对）：这是确定失败，不要再白等整个 waitSec
      response.setRestarted(false);
      return "已写入参数，但重启命令返回非 0（returncode=" + result.getReturncode()
          + "），数据库很可能没有重启。" + tail(output) + " 请检查重启命令：" + command;
    }

    long begin = System.currentTimeMillis();
    long deadline = begin + waitSec * 1000L;
    while (System.currentTimeMillis() < deadline) {
      if (Thread.currentThread().isInterrupted()) {
        // 被中断后 Thread.sleep 会立即返回，继续循环就变成忙转（还会顺带泄漏驱动类加载器）
        response.setRestarted(false);
        return "等待数据库重连时被中断，数据库状态未知，请人工确认（重启命令：" + command + "）";
      }
      Connection ignored = null;
      try {
        ignored = openConnection(request);
        response.setRestarted(true);
        response.setWaitedSec((int) ((System.currentTimeMillis() - begin) / 1000));
        return null;
      } catch (Exception e) {
        sleep();
      } finally {
        closeQuietly(ignored);
      }
    }
    response.setRestarted(false);
    response.setWaitedSec(waitSec);
    return "已下发重启命令，但 " + waitSec + " 秒内没能重新连上数据库，请人工确认数据库状态"
        + tail(output) + "（重启命令：" + command + "）";
  }

  /** 命令输出只把尾部摘要带进提示里；完整输出在 restartOutput 字段，前端可作 tooltip。 */
  private static String tail(String output) {
    String text = StringUtils.trimToEmpty(output);
    if (text.isEmpty()) {
      return "";
    }
    int max = 300;
    return " 命令输出：" + (text.length() > max ? "…" + text.substring(text.length() - max) : text);
  }

  /** waitSec 来自客户端，钳到 [10, 600]：否则一个请求能把引擎线程占住好几天。 */
  private static int waitSec(DbParamRequest.Restart restart) {
    Integer value = restart.getWaitSec();
    if (value == null) {
      return DEFAULT_WAIT_SEC;
    }
    return Math.min(MAX_WAIT_SEC, Math.max(MIN_WAIT_SEC, value));
  }

  private String restartExample(String dbType, DbParamRequest request) {
    String dir = readDataDirectory(request);
    String target = StringUtils.isBlank(dir) ? "<数据目录>" : dir;
    if ("opengauss".equals(dbType)) {
      return " 例如：nohup su - omm -c \"gs_ctl restart -D " + target
          + "\" >/tmp/resbench_restart.log 2>&1 &";
    }
    return " 例如：nohup pg_ctl restart -D " + target
        + " >/tmp/resbench_restart.log 2>&1 &（或 nohup systemctl restart postgresql"
        + " >/tmp/resbench_restart.log 2>&1 &）";
  }

  private String readDataDirectory(DbParamRequest request) {
    try (Connection connection = openConnection(request);
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SHOW data_directory")) {
      return rs.next() ? rs.getString(1) : null;
    } catch (Exception e) {
      return null;
    }
  }

  // ------------------------------------------------------------------ 文案

  private static String successMessage(DbParamResponse response) {
    if (!response.getMismatches().isEmpty()) {
      return "已执行，但读回不一致：" + String.join("；", response.getMismatches());
    }
    String changes = describeChanges(response);
    if (response.isRestarted()) {
      return "已应用并重启数据库（等待 " + response.getWaitedSec() + "s 后重新连上）：" + changes;
    }
    return "已应用并生效：" + changes;
  }

  private static String describe(Map<String, String> targets) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, String> entry : targets.entrySet()) {
      parts.add(label(entry.getKey()) + " "
          + friendly(entry.getKey(), new Setting(entry.getValue(), null, null)));
    }
    return String.join("、", parts);
  }

  private static String describe(Map<String, String> actualNames, Map<String, String> targets) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, String> entry : targets.entrySet()) {
      parts.add(label(entry.getKey()) + "（" + actualNames.get(entry.getKey()) + "）");
    }
    return String.join("、", parts);
  }

  private static String describeChanges(DbParamResponse response) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, String> entry : response.getTargets().entrySet()) {
      String key = entry.getKey();
      String before = StringUtils.defaultIfBlank(response.getBefore().get(key), "读不到");
      String after = StringUtils.defaultIfBlank(response.getAfter().get(key), "读不到");
      parts.add(label(key) + " " + before + " → " + after);
    }
    return String.join("、", parts);
  }

  private static String label(String semantic) {
    return BUFFER_POOL.equals(semantic) ? "缓冲池" : "最大连接数";
  }

  private static String translateError(Exception e) {
    String message = StringUtils.defaultString(e.getMessage(), e.getClass().getName());
    String lower = message.toLowerCase(Locale.ROOT);
    // 权限：改参数才需要的权限。必须排在"认证失败"前面 —— MySQL 的密码错也是 "Access denied ..."
    if (lower.contains("must be superuser")
        || lower.contains("super privilege")
        || lower.contains("system_variables_admin")
        || lower.contains("insufficient privilege")
        || (lower.contains("permission denied") && !lower.contains("for user"))) {
      return "当前数据库账号没有修改该参数的权限（PG / openGauss 需要 superuser，"
          + "MySQL 需要 SUPER 或 SYSTEM_VARIABLES_ADMIN）：" + message;
    }
    // 账号 / 密码不对，或该账号不允许从这个地址登录
    if (lower.contains("password authentication failed")
        || lower.contains("authentication failed")
        || lower.contains("access denied for user")
        || lower.contains("no pg_hba.conf entry")) {
      return "数据库账号或密码不正确，或该账号不允许从本机登录：" + message;
    }
    // 连不上（MySQL 的 "Communications link failure" 不带 connect 字样，得单独列）
    if (lower.contains("connect")
        || lower.contains("refused")
        || lower.contains("timeout")
        || lower.contains("timed out")
        || lower.contains("unknownhost")
        || lower.contains("communications link failure")
        || lower.contains("link failure")) {
      return "无法连接到数据库服务器，请检查 IP、端口、账号密码以及数据库是否已启动：" + message;
    }
    return "数据库参数操作失败：" + message;
  }

  // ------------------------------------------------------------------ 连接复用

  /** 建连逻辑与连接测试完全同源（驱动选择 / URL 拼装 / 租户重试都在 TestConnectionService 里）。 */
  private Connection openConnection(DbParamRequest request) throws Exception {
    return testConnectionService.openConnection(toConnectionRequest(request));
  }

  private static TestConnectionRequest toConnectionRequest(DbParamRequest request) {
    TestConnectionRequest connection = new TestConnectionRequest();
    connection.setEnvInfo(request.getEnvInfo());
    // 必须传归一化后的类型：连接测试的 switch 不认识 "postgre" 这个别名，
    // 否则参数能改、连接却报"暂不支持该数据库类型"
    connection.setDbType(normalizeAlias(request.getDbType()));
    connection.setDeploymentType(request.getDeploymentType());
    TestConnectionRequest.StandaloneDatabase standalone = new TestConnectionRequest.StandaloneDatabase();
    DbParamRequest.StandaloneDatabase source = request.getStandalone();
    if (source != null) {
      standalone.setIp(source.getIp());
      standalone.setPort(source.getPort());
      standalone.setUsername(source.getUsername());
      standalone.setPassword(source.getPassword());
    }
    connection.setStandalone(standalone);
    return connection;
  }

  private static void closeQuietly(Connection connection) {
    if (connection != null) {
      try {
        connection.close();
      } catch (Exception ignored) {
        // 连接已经断掉时 close 也会抛，忽略
      }
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(RECONNECT_INTERVAL_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private record Setting(String setting, String unit, String context) {}
}