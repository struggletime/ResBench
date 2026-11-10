package com.resbench.service;

import com.resbench.pojo.dto.TestConnectionRequest;
import com.resbench.pojo.dto.TestConnectionResponse;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Driver;
import java.sql.Statement;
import java.security.CodeSource;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TestConnectionService {
  private static final Logger LOG = LoggerFactory.getLogger(TestConnectionService.class);
  public TestConnectionResponse testConnection(TestConnectionRequest request) {
    try {
      validateRequest(request);
      ConnectionConfig config = activeConfig(request);
      JdbcTarget target = buildJdbcTarget(request);
      LOG.info("测试连接开始: dbType={}, deploymentType={}, host={}, port={}, username={}, driver={}, url={}, jarHint={}",
          request.getDbType(), request.getDeploymentType(),
          config.ip(), config.port(), config.username(),
          target.driverClass(), target.url(), target.driverJarHint());
      if (StringUtils.isNotBlank(target.driverJarHint())) {
        try {
          testConnectionWithIsolatedDriver(target, config);
          LOG.info("测试连接成功(隔离驱动): dbType={}, url={}", request.getDbType(), target.url());
          return new TestConnectionResponse(true, "连接成功");
        } catch (Exception e) {
          LOG.warn("隔离驱动测试连接失败, 回退classpath驱动: dbType={}, 原因: {}", request.getDbType(), e.getMessage());
          // Fall through to classpath driver loading.
        }
      }
      try {
        testConnectionWithClasspathDriver(target, config);
      } catch (Exception e) {
        if (!shouldRetryOceanBaseSysTenant(request, config, e)) {
          throw e;
        }
        LOG.warn("OceanBase root@sys 连接失败, 重试 root@sys: {}", e.getMessage());
        testConnectionWithClasspathDriver(target, new ConnectionConfig(
            config.ip(),
            config.port(),
            "root@sys",
            config.password()));
      }
      LOG.info("测试连接成功(classpath驱动): dbType={}, url={}", request.getDbType(), target.url());
      return new TestConnectionResponse(true, "连接成功");
    } catch (IllegalArgumentException e) {
      LOG.warn("测试连接被拒绝(参数校验或数据库类型不支持): dbType={}, 原因: {}", request.getDbType(), e.getMessage());
      return new TestConnectionResponse(false, e.getMessage());
    } catch (Exception e) {
      LOG.error("测试连接失败: dbType={}, deploymentType={}, 异常={}, 信息={}",
          request.getDbType(), request.getDeploymentType(),
          e.getClass().getName(), e.getMessage(), e);
      return new TestConnectionResponse(false, normalizeErrorMessage(e));
    }
  }

  /**
   * 供其它服务复用：按请求建立连接（参数校验 / 驱动选择 / URL 拼装 / OceanBase 租户重试与连接测试完全同源）。
   *
   * <p>与 {@link #testConnection(TestConnectionRequest)} 的区别是这里<b>不跑</b> validationSql ——
   * 调用方要用这条连接执行自己的 SQL。调用方负责关闭返回的连接。
   */
  public Connection openConnection(TestConnectionRequest request) throws Exception {
    validateRequest(request);
    ConnectionConfig config = activeConfig(request);
    JdbcTarget target = buildJdbcTarget(request);
    if (StringUtils.isNotBlank(target.driverJarHint())) {
      try {
        return openWithIsolatedDriver(target, config);
      } catch (Exception e) {
        LOG.warn("隔离驱动建连失败, 回退classpath驱动: dbType={}, 原因: {}", request.getDbType(), e.getMessage());
      }
    }
    try {
      return openWithClasspathDriver(target, config);
    } catch (Exception e) {
      if (!shouldRetryOceanBaseSysTenant(request, config, e)) {
        throw e;
      }
      LOG.warn("OceanBase root@sys 连接失败, 重试 root@sys: {}", e.getMessage());
      return openWithClasspathDriver(
          target, new ConnectionConfig(config.ip(), config.port(), "root@sys", config.password()));
    }
  }

  private void testConnectionWithClasspathDriver(JdbcTarget target, ConnectionConfig config) throws Exception {
    try (Connection connection = openWithClasspathDriver(target, config);
         Statement statement = connection.createStatement()) {
      statement.execute(target.validationSql());
    }
  }

  private Connection openWithClasspathDriver(JdbcTarget target, ConnectionConfig config) throws Exception {
    LOG.info("使用classpath驱动加载: driverClass={}, url={}", target.driverClass(), target.url());
    Properties properties = baseProperties(config);
    DriverManager.setLoginTimeout(5);
    Class<?> driverClass = Class.forName(target.driverClass());
    logDriverClassOrigin("classpath", target.driverClass(), driverClass);
    Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
    Connection connection = driver.connect(target.url(), properties);
    if (connection == null) {
      throw new IllegalStateException(target.driverClass() + " 驱动未接受该连接串");
    }
    return connection;
  }

  private void testConnectionWithIsolatedDriver(JdbcTarget target, ConnectionConfig config) throws Exception {
    try (Connection connection = openWithIsolatedDriver(target, config);
         Statement statement = connection.createStatement()) {
      statement.execute(target.validationSql());
    }
  }

  /** 驱动包的类加载器缓存：同一个 jar 进程内只建一个。 */
  private static final Map<String, URLClassLoader> DRIVER_LOADERS = new ConcurrentHashMap<>();

  /**
   * 隔离驱动建连。URLClassLoader <b>按 jar 路径缓存</b>，不能每次建连都 new：
   * 调用方拿到连接后驱动类仍会被惰性加载，所以 loader 不能随连接一起关（否则
   * NoClassDefFoundError），但一次参数应用至少要开 3 次连接、重启轮询还每 2 秒开一次，
   * 每次都 new 就是几十个 loader / jar 文件句柄泄漏。按 jar 缓存 = 进程内真·一个。
   */
  private Connection openWithIsolatedDriver(JdbcTarget target, ConnectionConfig config) throws Exception {
    String driverJar = locateDriverJar(target.driverJarHint());
    if (StringUtils.isBlank(driverJar)) {
      LOG.warn("未在classpath中找到驱动包({}), 将回退到classpath驱动", target.driverJarHint());
      throw new IllegalStateException("未找到 openGauss JDBC 驱动包");
    }
    LOG.info("使用隔离驱动加载: jar={}, driverClass={}, url={}", driverJar, target.driverClass(), target.url());
    Properties properties = baseProperties(config);
    URLClassLoader loader = DRIVER_LOADERS.computeIfAbsent(driverJar, jar -> {
      try {
        return new URLClassLoader(
            new URL[] {new File(jar).toURI().toURL()}, ClassLoader.getPlatformClassLoader());
      } catch (MalformedURLException e) {
        throw new IllegalStateException("驱动包路径无法解析: " + jar, e);
      }
    });
    Class<?> driverClass = Class.forName(target.driverClass(), true, loader);
    logDriverClassOrigin("isolated", target.driverClass(), driverClass);
    Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
    Connection connection = driver.connect(target.url(), properties);
    if (connection == null) {
      throw new IllegalStateException("openGauss 驱动未接受该连接串");
    }
    return connection;
  }

  private static Properties baseProperties(ConnectionConfig config) {
    Properties properties = new Properties();
    properties.setProperty("user", config.username().trim());
    properties.setProperty("password", config.password().trim());
    properties.setProperty("loginTimeout", "5");
    properties.setProperty("connectTimeout", "5000");
    properties.setProperty("socketTimeout", "5000");
    return properties;
  }

  private void logDriverClassOrigin(String mode, String driverClassName, Class<?> driverClass) {
    CodeSource codeSource = driverClass.getProtectionDomain().getCodeSource();
    String origin = codeSource != null ? codeSource.getLocation().toString() : "unknown";
    LOG.info("驱动类 {} 实际来自({}): {}", driverClassName, mode, origin);
  }

  private void validateRequest(TestConnectionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    String deploymentType = normalize(request.getDeploymentType());
    if (!"standalone".equals(deploymentType) && !"distributed".equals(deploymentType)) {
      throw new IllegalArgumentException("当前仅支持单机和分布式数据库连接测试");
    }
    if ("standalone".equals(deploymentType) && request.getStandalone() == null) {
      throw new IllegalArgumentException("缺少单机数据库配置");
    }
    if ("distributed".equals(deploymentType) && request.getDistributed() == null) {
      throw new IllegalArgumentException("缺少分布式数据库配置");
    }
    if (StringUtils.isBlank(request.getDbType())) {
      throw new IllegalArgumentException("缺少数据库类型");
    }
    ConnectionConfig config = activeConfig(request);
    if (StringUtils.isBlank(config.ip())
        || StringUtils.isBlank(config.port())
        || StringUtils.isBlank(config.username())
        || StringUtils.isBlank(config.password())) {
      throw new IllegalArgumentException("数据库连接信息不完整");
    }
    if ("distributed".equals(deploymentType) && !"oceanbase".equals(normalize(request.getDbType()))) {
      throw new IllegalArgumentException("当前分布式数据库连接测试仅支持 OceanBase");
    }
  }

  private JdbcTarget buildJdbcTarget(TestConnectionRequest request) {
    String dbType = normalize(request.getDbType());
    ConnectionConfig config = activeConfig(request);
    Endpoint endpoint = normalizeEndpoint(config.ip(), config.port());
    String ip = endpoint.host();
    String port = endpoint.port();

    return switch (dbType) {
      case "mysql" -> new JdbcTarget(
          "com.mysql.cj.jdbc.Driver",
          "jdbc:mysql://" + ip + ":" + port + "/resbench_demo?useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=5000",
          "SELECT 1",
          null);
      case "postgresql" -> new JdbcTarget(
          "org.postgresql.Driver",
          "jdbc:postgresql://" + ip + ":" + port + "/postgres?connectTimeout=5&socketTimeout=5",
          "SELECT 1",
          null);
      // openGauss 5.x 的认证握手不是标准 PostgreSQL SCRAM，原版 pgjdbc 会报
      // "Invalid SCRAM client initialization"，必须用 openGauss/GaussDB 原生驱动。
      // 注意：不要改用 org.opengauss:opengauss-jdbc:6.0.0 —— 它的驱动类名就是
      // org.postgresql.Driver，与主依赖里的原版驱动重名，classpath 上原版胜出，仍然连不上。
      case "opengauss" -> new JdbcTarget(
          "com.huawei.gaussdb.jdbc.Driver",
          "jdbc:gaussdb://" + ip + ":" + port + "/postgres?sslmode=disable&connectTimeout=5&socketTimeout=5",
          "SELECT 1",
          "gaussdbjdbc");
      case "gaussdb" -> new JdbcTarget(
          "com.huawei.gaussdb.jdbc.Driver",
          "jdbc:gaussdb://" + ip + ":" + port + "/postgres?sslmode=disable&connectTimeout=5&socketTimeout=5",
          "SELECT 1",
          "gaussdbjdbc");
      case "oceanbase" -> new JdbcTarget(
          "com.mysql.cj.jdbc.Driver",
          "jdbc:mysql://" + ip + ":" + port + "/?useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=5000",
          "SELECT 1",
          null);
      case "sqlserver" -> new JdbcTarget(
          "com.microsoft.sqlserver.jdbc.SQLServerDriver",
          "jdbc:sqlserver://" + ip + ":" + port + ";databaseName=master;encrypt=false;loginTimeout=5",
          "SELECT 1",
          null);
      case "oracle" -> throw new IllegalArgumentException("Oracle 连接测试还缺少 service name/SID 信息，当前表单暂不支持");
      default -> throw new IllegalArgumentException("暂不支持该数据库类型的连接测试: " + request.getDbType());
    };
  }

  private String locateDriverJar(String jarHint) {
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

  private String normalizeErrorMessage(Exception e) {
    String message = StringUtils.defaultString(e.getMessage());
    String lower = message.toLowerCase(Locale.ROOT);

    // 0. 乱码检测：如果消息全是问号，说明驱动返回了编码错误的乱码
    if (StringUtils.isNotBlank(message) && message.replace("?", "").trim().isEmpty()) {
      return "连接失败，驱动返回了乱码信息，请检查数据库服务是否正常、IP/端口/用户名密码是否正确";
    }

    // 1. 驱动缺失
    if (e instanceof ClassNotFoundException || lower.contains("no suitable driver")) {
      return "未找到数据库驱动，请确认 lib 目录下已放置对应驱动包";
    }

    // 2. 网络/连接问题（关键词放宽，适配 PostgreSQL / openGauss 等驱动的不同表述）
    if (lower.contains("communications link failure")
        || lower.contains("connection refused")
        || lower.contains("connection timed out")
        || lower.contains("connect timed out")
        || lower.contains("sockettimeoutexception")
        || lower.contains("socket closed")
        || lower.contains("refused")
        || lower.contains("timed out")
        || lower.contains("unreachable")
        || lower.contains("connection attempt failed")
        || lower.contains("尝试连线已失败")
        || lower.contains("network is unreachable")
        || e instanceof java.net.UnknownHostException) {
      return "无法连接到数据库服务器，请检查 IP、端口是否正确，以及数据库服务是否已启动、网络是否通畅";
    }

    // 3. 认证失败
    if (lower.contains("access denied")
        || lower.contains("authentication failed")
        || lower.contains("login failed")
        || lower.contains("invalid username/password")
        || lower.contains("password authentication failed")
        || lower.contains("fatal: password authentication")
        || lower.contains("invalid password")) {
      return "用户名或密码错误，请检查连接凭据";
    }

    // 4. 数据库不存在
    if (lower.contains("unknown database")
        || lower.contains("does not exist")) {
      return "目标数据库不存在，请检查数据库名称";
    }

    // 5. SSL 错误
    if (lower.contains("ssl connection is required")
        || lower.contains("unable to find valid certification path")) {
      return "SSL 连接失败，请检查数据库 SSL 配置或在连接参数中关闭 SSL";
    }

    // 6. SCRAM 错误
    if (lower.contains("invalid scram client initialization")) {
      return "认证初始化失败（SCRAM），请确认数据库类型选择正确、用户名/密码正确，以及目标库是否允许当前驱动认证方式";
    }

    // 7. 驱动未接受连接串
    if (message.contains("驱动未接受该连接串")) {
      return "驱动未接受该连接串，请检查连接参数格式";
    }

    // 8. 其他：截断显示，避免过长英文堆栈
    if (message.length() > 200) {
      return message.substring(0, 200);
    }
    return message;
  }

  private boolean shouldRetryOceanBaseSysTenant(TestConnectionRequest request, ConnectionConfig config, Exception e) {
    String message = StringUtils.defaultString(e.getMessage());
    return "oceanbase".equals(normalize(request.getDbType()))
        && "root".equals(config.username())
        && message.contains("Access denied");
  }

  private String normalize(String value) {
    return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
  }

  private ConnectionConfig activeConfig(TestConnectionRequest request) {
    String deploymentType = normalize(request.getDeploymentType());
    if ("distributed".equals(deploymentType)) {
      TestConnectionRequest.DistributedDatabase distributed = request.getDistributed();
      if (distributed == null) {
        return new ConnectionConfig(null, null, null, null);
      }
      return new ConnectionConfig(
          distributed.getIp(),
          distributed.getPort(),
          distributed.getUsername(),
          distributed.getPassword());
    }

    TestConnectionRequest.StandaloneDatabase standalone = request.getStandalone();
    if (standalone == null) {
      return new ConnectionConfig(null, null, null, null);
    }
    return new ConnectionConfig(
        standalone.getIp(),
        standalone.getPort(),
        standalone.getUsername(),
        standalone.getPassword());
  }

  private Endpoint normalizeEndpoint(String ip, String port) {
    String host = StringUtils.defaultString(ip).trim();
    String resolvedPort = StringUtils.defaultString(port).trim();
    if (host.contains("://")) {
      try {
        URI uri = URI.create(host);
        if (StringUtils.isNotBlank(uri.getHost())) {
          host = uri.getHost();
        }
        if (uri.getPort() > 0) {
          resolvedPort = String.valueOf(uri.getPort());
        }
      } catch (IllegalArgumentException ignored) {
        // Keep the raw host so the JDBC driver returns a clear connection error.
      }
    }
    return new Endpoint(host, resolvedPort);
  }

  private record ConnectionConfig(String ip, String port, String username, String password) {}

  private record Endpoint(String host, String port) {}

  private record JdbcTarget(String driverClass, String url, String validationSql, String driverJarHint) {}
}
