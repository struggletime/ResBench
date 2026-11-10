/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package com.resbench.api;

import com.resbench.WorkloadConfiguration;
import com.resbench.catalog.AbstractCatalog;
import com.resbench.types.DatabaseType;
import com.resbench.common.util.ClassUtil;
import com.resbench.util.SQLUtil;
import com.resbench.util.ScriptRunner;
import com.resbench.util.ThreadUtil;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Base class for all benchmark implementations */
public abstract class BenchmarkModule {
  private static final Logger LOG = LoggerFactory.getLogger(BenchmarkModule.class);
  private static final String OPENGAUSS_DRIVER_CLASS = "org.postgresql.Driver";
  private static final String OPENGAUSS_DRIVER_HINT = "opengauss-jdbc";
  private static volatile Driver openGaussDriver;
  private static volatile ClassLoader openGaussDriverLoader;
  /** 是否已记录过实际连接驱动信息（压测会大量建连，只记第一次避免刷屏） */
  private static final AtomicBoolean connectedInfoLogged = new AtomicBoolean(false);

  /** The workload configuration for this benchmark invocation */
  protected final WorkloadConfiguration workConf;

  /** Class loader variable for this benchmark */
  protected ClassLoader classLoader;

  /** These are the variations of the Procedure's Statement SQL */
  protected final StatementDialects dialects;

  /** Supplemental Procedures */
  private final Set<Class<? extends Procedure>> supplementalProcedures = new HashSet<>();

  /** A single Random object that should be re-used by all a benchmark's components */
  private static final ThreadLocal<Random> rng = new ThreadLocal<>();

  private AbstractCatalog catalog = null;

  /**
   * Constructor!
   *
   * @param workConf
   */
  public BenchmarkModule(WorkloadConfiguration workConf) {
    this.workConf = workConf;
    this.dialects = new StatementDialects(workConf);
    // setClassLoader();
    this.classLoader = Thread.currentThread().getContextClassLoader();
  }

  /**
   * Instantiates the classLoader variable, needs to be overwritten if benchmark uses a custom
   * implementation.
   */
  protected void setClassLoader() {
    this.classLoader = Thread.currentThread().getContextClassLoader();
  }

  // --------------------------------------------------------------------------
  // DATABASE CONNECTION
  // --------------------------------------------------------------------------

  /**
   * 建连瞬时失败（TCP 超时/被拒/重置）时的重试预算。
   *
   * <p>实测：数据库所在主机会偶发 30~60 秒的「建连黑洞」——这段时间里 JDBC 建连每次都以
   * connectTimeout 打满失败，窗口过去后立刻恢复（19:41 的那轮在第 3 次重试时恢复；19:48 的那轮
   * 只有 3 次机会，32 秒后放弃，整轮测试作废）。所以这里按「总预算 + 指数退避」重试，而不是固定
   * 次数：预算内恢复就继续跑，数据库真的不可用时等满预算后照样报错。
   * 重试只针对网络类异常，鉴权失败、连接数超限等确定性错误仍然立即抛出。
   *
   * <p>-Dconn.retry.attempts=8 -Dconn.retry.backoff.ms=1000 -Dconn.retry.max.backoff.ms=5000
   * -Dconn.retry.budget.ms=90000
   */
  private static final int CONN_MAX_ATTEMPTS =
      Math.max(1, Integer.getInteger("conn.retry.attempts", 8));
  private static final long CONN_RETRY_BACKOFF_MS =
      Math.max(0L, Long.getLong("conn.retry.backoff.ms", 1000L));
  private static final long CONN_RETRY_MAX_BACKOFF_MS =
      Math.max(CONN_RETRY_BACKOFF_MS, Long.getLong("conn.retry.max.backoff.ms", 5000L));
  private static final long CONN_RETRY_BUDGET_MS =
      Math.max(1L, Long.getLong("conn.retry.budget.ms", 90_000L));

  public final Connection makeConnection() throws SQLException {
    final long deadlineNanos = System.nanoTime() + CONN_RETRY_BUDGET_MS * 1_000_000L;
    long backoffMs = CONN_RETRY_BACKOFF_MS;
    for (int attempt = 1; ; attempt++) {
      final long seq = CONN_SEQ.incrementAndGet();
      final long t0 = System.nanoTime();
      final String tid = Thread.currentThread().getName();
      logConnStart(seq, tid);
      try {
        Connection connection = makeConnectionInner();
        if (CONN_TRACE) {
          LOG.info("[conn-trace] #{} 连接成功 tid={} 耗时={}ms 尝试={}",
              seq, tid, elapsedMs(t0), attempt);
        }
        return connection;
      } catch (SQLException | RuntimeException e) {
        logConnFailure(seq, tid, t0, e);
        final long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (attempt >= CONN_MAX_ATTEMPTS
            || remainingMs <= 0
            || !isRetryableConnectionFailure(e)) {
          throw e;
        }
        final long sleepMs = Math.min(backoffMs, remainingMs);
        LOG.warn(
            "[conn-trace] #{} 建连遇到瞬时网络错误，{}ms 后重试（第 {} 次，剩余预算 {}s）: {}",
            seq, sleepMs, attempt + 1, remainingMs / 1000, e.getMessage());
        sleepBeforeConnectionRetry(sleepMs);
        backoffMs = Math.min(CONN_RETRY_MAX_BACKOFF_MS, Math.max(1L, backoffMs * 2));
      }
    }
  }

  /** 只重试网络链路类异常；鉴权、连接数超限、SQL 语法等确定性错误不重试。 */
  private static boolean isRetryableConnectionFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.net.SocketTimeoutException
          || cause instanceof java.net.ConnectException
          || cause instanceof java.net.NoRouteToHostException
          || cause instanceof java.net.SocketException
          || cause instanceof java.sql.SQLTransientConnectionException) {
        return true;
      }
      if (cause == cause.getCause()) {
        break;
      }
    }
    return false;
  }

  private static void sleepBeforeConnectionRetry(long sleepMs) throws SQLException {
    if (sleepMs <= 0) {
      return;
    }
    try {
      Thread.sleep(sleepMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("建连重试被中断", e);
    }
  }

  private Connection makeConnectionInner() throws SQLException {
    if (workConf.getDatabaseType() == DatabaseType.OPENGAUSS) {
      return makeOpenGaussConnection();
    }

    if (StringUtils.isEmpty(workConf.getUsername())) {
      Connection connection = DriverManager.getConnection(workConf.getUrl());
      logConnectedDriver(connection, workConf.getUrl());
      return connection;
    } else {
      Connection connection = DriverManager.getConnection(
          workConf.getUrl(), workConf.getUsername(), workConf.getPassword());
      logConnectedDriver(connection, workConf.getUrl());
      return connection;
    }
  }

  private Connection makeOpenGaussConnection() throws SQLException {
    Driver driver = getOpenGaussDriver();
    if (driver == null) {
      LOG.warn("openGauss JDBC driver not found on classpath, falling back to DriverManager");
      if (StringUtils.isEmpty(workConf.getUsername())) {
        Connection connection = DriverManager.getConnection(workConf.getUrl());
        logConnectedDriver(connection, workConf.getUrl());
        return connection;
      }
      Connection connection = DriverManager.getConnection(workConf.getUrl(), workConf.getUsername(), workConf.getPassword());
      logConnectedDriver(connection, workConf.getUrl());
      return connection;
    }

    Properties properties = new Properties();
    if (StringUtils.isNotEmpty(workConf.getUsername())) {
      properties.setProperty("user", workConf.getUsername());
      properties.setProperty("password", StringUtils.defaultString(workConf.getPassword()));
    }
    Connection connection = driver.connect(workConf.getUrl(), properties);
    if (connection == null) {
      throw new SQLException("openGauss 驱动未接受该连接串: " + workConf.getUrl());
    }
    logConnectedDriver(connection, workConf.getUrl());
    return connection;
  }

  private void logConnectedDriver(Connection connection, String url) {
    if (!connectedInfoLogged.compareAndSet(false, true)) {
      return; // 只记第一次，避免压测大量连接刷日志
    }
    try {
      DatabaseMetaData meta = connection.getMetaData();
      LOG.info("实际连接信息(首次): url={}, 驱动名称={}, 驱动版本={}, 数据库产品={}, 数据库版本={}",
          url, meta.getDriverName(), meta.getDriverVersion(),
          meta.getDatabaseProductName(), meta.getDatabaseProductVersion());
    } catch (SQLException e) {
      LOG.warn("读取连接元数据失败: {}", e.getMessage());
    }
  }

  // --------------------------------------------------------------------------
  // 连接诊断：只打印，不改连接行为；失败时额外做一次「同刻裸探针」。
  //   -Dconn.trace.enabled=false  整体关闭
  //   -Dconn.trace.probe=false    只关裸探针（其余打印保留）
  // --------------------------------------------------------------------------
  private static final AtomicLong CONN_SEQ = new AtomicLong();
  private static final AtomicBoolean CONN_ENV_LOGGED = new AtomicBoolean();
  private static final AtomicInteger CONN_PROBE_COUNT = new AtomicInteger();
  private static final AtomicLong CONN_PROBE_LAST_MS = new AtomicLong();
  private static final boolean CONN_TRACE =
      !"false".equalsIgnoreCase(System.getProperty("conn.trace.enabled", "true"));
  private static final boolean CONN_PROBE =
      !"false".equalsIgnoreCase(System.getProperty("conn.trace.probe", "true"));
  private static final int RAW_PROBE_TIMEOUT_MS = 3000;
  private static final int RAW_PROBE_MAX = 5;
  private static final long RAW_PROBE_MIN_GAP_MS = 1000L;

  private static long elapsedMs(long sinceNanos) {
    return (System.nanoTime() - sinceNanos) / 1_000_000L;
  }

  private void logConnStart(long seq, String tid) {
    if (!CONN_TRACE) {
      return;
    }
    if (CONN_ENV_LOGGED.compareAndSet(false, true)) {
      String[] target = parseTarget();
      String resolved;
      try {
        resolved = (target == null) ? "?" : Arrays.toString(InetAddress.getAllByName(target[0]));
      } catch (Exception e) {
        resolved = "解析失败: " + e;
      }
      LOG.info(
          "[conn-trace] 环境: dbType={} java={} preferIPv4Stack={} url={} 目标解析={} fd={} fd上限={} 堆上限={}MB",
          workConf.getDatabaseType(),
          System.getProperty("java.version"),
          System.getProperty("java.net.preferIPv4Stack"),
          workConf.getUrl(),
          resolved,
          openFdCount(),
          fdLimit(),
          Runtime.getRuntime().maxMemory() / 1024 / 1024);
    }
    LOG.info("[conn-trace] #{} 开始连接 tid={} url={}", seq, tid, workConf.getUrl());
  }

  private void logConnFailure(long seq, String tid, long sinceNanos, Throwable e) {
    if (!CONN_TRACE) {
      LOG.error("[conn-trace] #{} 连接失败 tid={} 耗时={}ms", seq, tid, elapsedMs(sinceNanos), e);
      return;
    }
    LOG.error(
        "[conn-trace] #{} 连接失败 tid={} 耗时={}ms ex={}: {}",
        seq,
        tid,
        elapsedMs(sinceNanos),
        e.getClass().getName(),
        e.getMessage(),
        e);
    LOG.error(
        "[conn-trace] #{} 现场: 活线程={} fd={} fd上限={} 堆已用={}MB 堆上限={}MB",
        seq,
        Thread.activeCount(),
        openFdCount(),
        fdLimit(),
        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024,
        Runtime.getRuntime().maxMemory() / 1024 / 1024);
    rawProbe(seq);
  }

  /**
   * 失败当时、从同一个 JVM 再发一条裸 TCP 连接：用来区分「那一刻路是断的」还是「路是通的、只有这条连接没成」。
   * 只在失败路径触发，且有节流（每次 JVM 至多 {@link #RAW_PROBE_MAX} 次、间隔至少 1 秒）。
   */
  private void rawProbe(long seq) {
    if (!CONN_PROBE) {
      return;
    }
    String[] target = parseTarget();
    if (target == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (CONN_PROBE_COUNT.get() >= RAW_PROBE_MAX
        || now - CONN_PROBE_LAST_MS.get() < RAW_PROBE_MIN_GAP_MS) {
      return;
    }
    CONN_PROBE_LAST_MS.set(now);
    CONN_PROBE_COUNT.incrementAndGet();
    long t0 = System.nanoTime();
    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(target[0], Integer.parseInt(target[1])), RAW_PROBE_TIMEOUT_MS);
      LOG.error(
          "[conn-trace] #{} 同刻裸探针 {}:{} 成功 耗时={}ms 本地地址={}",
          seq, target[0], target[1], elapsedMs(t0), socket.getLocalSocketAddress());
    } catch (Exception probeEx) {
      LOG.error(
          "[conn-trace] #{} 同刻裸探针 {}:{} 失败 耗时={}ms ex={}",
          seq, target[0], target[1], elapsedMs(t0), probeEx.toString());
    }
  }

  /** jdbc:mysql://host:port/... → [host, port]；解析不出来返回 null。 */
  private String[] parseTarget() {
    String url = workConf.getUrl();
    if (url == null) {
      return null;
    }
    int i = url.indexOf("//");
    if (i < 0) {
      return null;
    }
    String rest = url.substring(i + 2);
    int end = rest.length();
    for (int k = 0; k < rest.length(); k++) {
      char c = rest.charAt(k);
      if (c == '/' || c == '?' || c == ';' || c == ',') {
        end = k;
        break;
      }
    }
    String hostPort = rest.substring(0, end);
    int colon = hostPort.indexOf(':');
    if (colon <= 0 || colon == hostPort.length() - 1) {
      return null;
    }
    return new String[] {hostPort.substring(0, colon), hostPort.substring(colon + 1)};
  }

  private static int openFdCount() {
    try (var fds = Files.list(Paths.get("/proc/self/fd"))) {
      return (int) fds.count();
    } catch (Exception e) {
      return -1;
    }
  }

  private static long fdLimit() {
    try {
      for (String line : Files.readAllLines(Paths.get("/proc/self/limits"))) {
        if (line.startsWith("Max open files")) {
          return Long.parseLong(line.trim().split("\\s+")[3]);
        }
      }
    } catch (Exception e) {
      // 拿不到就当未知
    }
    return -1;
  }

  private static Driver getOpenGaussDriver() throws SQLException {
    Driver cached = openGaussDriver;
    if (cached != null) {
      return cached;
    }
    synchronized (BenchmarkModule.class) {
      if (openGaussDriver != null) {
        return openGaussDriver;
      }
      String driverJar = locateDriverJar(OPENGAUSS_DRIVER_HINT);
      if (StringUtils.isBlank(driverJar)) {
        return null;
      }
      try {
        openGaussDriverLoader =
            new URLClassLoader(
                new URL[] {new File(driverJar).toURI().toURL()},
                ClassLoader.getPlatformClassLoader());
        Class<?> driverClass = Class.forName(OPENGAUSS_DRIVER_CLASS, true, openGaussDriverLoader);
        CodeSource codeSource = driverClass.getProtectionDomain().getCodeSource();
        LOG.info("openGauss 驱动类 {} 实际来自: {}", OPENGAUSS_DRIVER_CLASS,
            codeSource != null ? codeSource.getLocation().toString() : "unknown");
        openGaussDriver = (Driver) driverClass.getDeclaredConstructor().newInstance();
        return openGaussDriver;
      } catch (Exception e) {
        throw new SQLException("加载 openGauss JDBC 驱动失败: " + e.getMessage(), e);
      }
    }
  }

  private static String locateDriverJar(String jarHint) {
    String classPath = System.getProperty("java.class.path");
    if (StringUtils.isBlank(classPath)) {
      return null;
    }
    for (String entry : classPath.split(File.pathSeparator)) {
      if (entry.contains(jarHint) && entry.endsWith(".jar")) {
        return entry;
      }
    }
    return null;
  }

  private String afterLoadScriptPath = null;

  public final void setAfterLoadScriptPath(String scriptPath) {
    this.afterLoadScriptPath = scriptPath;
  }

  // --------------------------------------------------------------------------
  // IMPLEMENTING CLASS INTERFACE
  // --------------------------------------------------------------------------

  /**
   * @return
   * @throws IOException
   */
  protected abstract List<Worker<? extends BenchmarkModule>> makeWorkersImpl() throws IOException;

  /**
   * Each BenchmarkModule needs to implement this method to load a sample dataset into the database.
   * The Connection handle will already be configured for you, and the base class will commit+close
   * it once this method returns
   *
   * @return TODO
   */
  protected abstract Loader<? extends BenchmarkModule> makeLoaderImpl();

  protected abstract Package getProcedurePackageImpl();

  // --------------------------------------------------------------------------
  // PUBLIC INTERFACE
  // --------------------------------------------------------------------------

  /**
   * Return the Random generator that should be used by all this benchmark's components. We are
   * using ThreadLocal to make this support multiple threads better. This will set the seed if one
   * is specified in the workload config file
   */
  public Random rng() {
    Random ret = rng.get();
    if (ret == null) {
      if (this.workConf.getRandomSeed() != -1) {
        ret = new Random(this.workConf.getRandomSeed());
      } else {
        ret = new Random();
      }
      rng.set(ret);
    }
    return ret;
  }

  private String convertBenchmarkClassToBenchmarkName() {
    return convertBenchmarkClassToBenchmarkName(this.getClass());
  }

  protected static <T> String convertBenchmarkClassToBenchmarkName(Class<T> clazz) {
    assert (clazz != null);
    String name = clazz.getSimpleName().toLowerCase();
    // Special case for "CHBenCHmark"
    if (!name.equals("chbenchmark") && name.endsWith("benchmark")) {
      name = name.replace("benchmark", "");
    }
    return (name);
  }

  /**
   * Return the URL handle to the DDL used to load the benchmark's database schema.
   *
   * @param db_type
   */
  public String getDatabaseDDLPath(DatabaseType db_type) {

    // The order matters!
    List<String> names = new ArrayList<>();
    if (db_type != null) {
      DatabaseType ddl_db_type = db_type;
      // HACK: Use MySQL if we're given MariaDB
      if (ddl_db_type == DatabaseType.MARIADB) {
        ddl_db_type = DatabaseType.MYSQL;
      }
      names.add("ddl-" + ddl_db_type.name().toLowerCase() + ".sql");
    }
    names.add("ddl-generic.sql");

    for (String fileName : names) {
      final String benchmarkName = getBenchmarkName();
      final String path = "/benchmarks/" + benchmarkName + "/" + fileName;

      try (InputStream stream = this.getClass().getResourceAsStream(path)) {
        if (stream != null) {
          return path;
        }

      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
      }
    }

    return null;
  }

  public final List<Worker<? extends BenchmarkModule>> makeWorkers() throws IOException {
    LOG.info("make Workers implemented by actual workload");
    return (this.makeWorkersImpl());
  }

  public final void refreshCatalog() throws SQLException {
    if (this.catalog != null) {
      try {
        this.catalog.close();
      } catch (SQLException throwables) {
        LOG.error(throwables.getMessage(), throwables);
      }
    }
    try (Connection conn = this.makeConnection()) {
      this.catalog =
          SQLUtil.getCatalog(this, this.getWorkloadConfiguration().getDatabaseType(), conn);
    }
  }

  /**
   * Create the Benchmark Database This is the main method used to create all the database objects
   * (e.g., table, indexes, etc) needed for this benchmark
   */
  public final void createDatabase() throws SQLException, IOException {
    try (Connection conn = this.makeConnection()) {
      this.createDatabase(this.workConf.getDatabaseType(), conn);
    }
  }

  /**
   * Create the Benchmark Database This is the main method used to create all the database objects
   * (e.g., table, indexes, etc) needed for this benchmark
   */
  public final void createDatabase(DatabaseType dbType, Connection conn)
      throws SQLException, IOException {

    ScriptRunner runner = new ScriptRunner(conn, true, true);
    if (workConf.getDDLPath() != null) {
      String ddlPath = workConf.getDDLPath();
      LOG.warn("Overriding default DDL script path");
      LOG.debug("Executing script [{}] for database type [{}]", ddlPath, dbType);
      runner.runExternalScript(ddlPath);
    } else {
      String ddlPath = this.getDatabaseDDLPath(dbType);
      LOG.debug("Executing script [{}] for database type [{}]", ddlPath, dbType);
      runner.runScript(ddlPath);
    }
  }

  public final void runScript(String scriptPath) throws SQLException, IOException {
    try (Connection conn = this.makeConnection()) {
      DatabaseType dbType = this.workConf.getDatabaseType();
      ScriptRunner runner = new ScriptRunner(conn, true, true);
      LOG.debug("Executing script [{}] for database type [{}]", scriptPath, dbType);
      runner.runScript(scriptPath);
    }
  }

  public void faultInject() {
    // TODO
  }

  /** Invoke this benchmark's database loader */
  public final Loader<? extends BenchmarkModule> loadDatabase()
      throws IOException, SQLException, InterruptedException {
    Loader<? extends BenchmarkModule> loader;

    loader = this.makeLoaderImpl();
    if (loader != null) {

      try {
        List<LoaderThread> loaderThreads = loader.createLoaderThreads();
        int maxConcurrent = workConf.getLoaderThreads();

        ThreadUtil.runLoaderThreads(loaderThreads, maxConcurrent);

        if (!loader.getTableCounts().isEmpty()) {
          LOG.debug("Table Counts:\n{}", loader.getTableCounts());
        }
      } finally {
        if (LOG.isDebugEnabled()) {
          LOG.debug(
              String.format(
                  "Finished loading the %s database", this.getBenchmarkName().toUpperCase()));
        }
      }
    }

    if (this.afterLoadScriptPath != null) {
      LOG.debug(
          "Running script after load for {} benchmark...",
          this.workConf.getBenchmarkName().toUpperCase());
      runScript(this.afterLoadScriptPath);
      LOG.debug(
          "Finished running script after load for {} benchmark...",
          this.workConf.getBenchmarkName().toUpperCase());
    }

    return loader;
  }

  public final void clearDatabase() throws SQLException {

    try (Connection conn = this.makeConnection()) {
      Loader<? extends BenchmarkModule> loader = this.makeLoaderImpl();
      if (loader != null) {
        conn.setAutoCommit(false);
        loader.unload(conn, this.catalog);
        conn.commit();
      }
    }
  }

  // --------------------------------------------------------------------------
  // UTILITY METHODS
  // --------------------------------------------------------------------------

  /** Return the unique identifier for this benchmark */
  public final String getBenchmarkName() {
    String workConfName = this.workConf.getBenchmarkName();
    return workConfName != null ? workConfName : convertBenchmarkClassToBenchmarkName();
  }

  /** Return the database's catalog */
  public final AbstractCatalog getCatalog() {

    if (catalog == null) {
      throw new RuntimeException("getCatalog() has been called before refreshCatalog()");
    }

    return this.catalog;
  }

  /** Return the StatementDialects loaded for this benchmark */
  public final StatementDialects getStatementDialects() {
    return (this.dialects);
  }

  @Override
  public final String toString() {
    return getBenchmarkName();
  }

  /**
   * Initialize a TransactionType handle for the get procedure name and id This should only be
   * invoked a start-up time
   *
   * @param procName
   * @param id
   * @return
   */
  @SuppressWarnings("unchecked")
  public final TransactionType initTransactionType(
      String procName, int id, long preExecutionWait, long postExecutionWait) {
    if (id == TransactionType.INVALID_ID) {
      throw new RuntimeException(
          String.format(
              "Procedure %s.%s cannot use the reserved id '%d' for %s",
              getBenchmarkName(),
              procName,
              id,
              TransactionType.INVALID.getClass().getSimpleName()));
    }

    Package pkg = this.getProcedurePackageImpl();

    String fullName = pkg.getName() + "." + procName;
    Class<? extends Procedure> procClass =
        (Class<? extends Procedure>) ClassUtil.getClass(this.classLoader, fullName);

    return new TransactionType(procClass, id, false, preExecutionWait, postExecutionWait);
  }

  public final WorkloadConfiguration getWorkloadConfiguration() {
    return (this.workConf);
  }

  /**
   * Return a mapping from TransactionTypes to Procedure invocations
   *
   * @return
   */
  public Map<TransactionType, Procedure> getProcedures() {
    Map<TransactionType, Procedure> proc_xref = new HashMap<>();
    TransactionTypes txns = this.workConf.getTransTypes();

    if (txns != null) {
      for (Class<? extends Procedure> procClass : this.supplementalProcedures) {
        TransactionType txn = txns.getType(procClass);
        if (txn == null) {
          txn = new TransactionType(procClass, procClass.hashCode(), true, 0, 0);
          txns.add(txn);
        }
      }

      for (TransactionType txn : txns) {
        Procedure proc =
            ClassUtil.newInstance(txn.getProcedureClass(), new Object[0], new Class<?>[0]);
        proc.initialize(this.workConf.getDatabaseType());
        proc_xref.put(txn, proc);
        proc.loadSQLDialect(this.dialects);
        if (this.workConf.getAdvancedMonitoringEnabled()) {
          proc.enabledAdvancedMonitoring();
        }
      }
    }
    if (proc_xref.isEmpty()) {
      LOG.warn("No procedures defined for {}", this);
    }
    return (proc_xref);
  }

  /**
   * @param procClass
   */
  public final void registerSupplementalProcedure(Class<? extends Procedure> procClass) {
    this.supplementalProcedures.add(procClass);
  }
}
