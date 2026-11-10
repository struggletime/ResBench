import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * 兼容 GaussDB / openGauss 的最大准备事务故障注入工具
 */
public class MaxPreparedTransactionFaultInject {

    private static final Logger LOG = Logger.getLogger(MaxPreparedTransactionFaultInject.class.getName());
    private static final String DEFAULT_PREFIX = "fault_inject";

    private static String url;
    private static String user;
    private static String password;

    /**
     * 加载配置逻辑：支持命令行参数和 db.properties
     */
    private static void loadConfiguration(Properties cmdProps) {
        url = cmdProps.getProperty("url");
        user = cmdProps.getProperty("user");
        password = cmdProps.getProperty("password");

        if (url == null || user == null || password == null) {
            try (InputStream in = MaxPreparedTransactionFaultInject.class.getResourceAsStream("/db.properties")) {
                if (in != null) {
                    Properties fileProps = new Properties();
                    fileProps.load(in);
                    if (url == null) url = fileProps.getProperty("url");
                    if (user == null) user = fileProps.getProperty("user");
                    if (password == null) password = fileProps.getProperty("password");
                }
            } catch (Exception ignored) {}
        }

        if (url == null || user == null || password == null) {
            throw new RuntimeException("Missing configuration! Use -url, -user, and -password.");
        }
    }

    public void run(Integer targetCount, int holdDuration, int concurrency) throws Exception {
        // 1. 自动识别环境信息
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            LOG.info("Connected to : " + dbMeta.getDatabaseProductName() + " " + dbMeta.getDatabaseProductVersion());
            LOG.info("Driver Used  : " + dbMeta.getDriverName() + " " + dbMeta.getDriverVersion());
        }

        // 2. 获取数据库当前的限制
        int sysMaxPrepared = getMaxPreparedTransactionsLimit();
        int finalTarget = (targetCount != null && targetCount > 0) ? targetCount : sysMaxPrepared + 1;

        LOG.info("======= Fault Injection Config =======");
        LOG.info("URL                   : " + url);
        LOG.info("Target Prepared Count : " + finalTarget + " (sys_limit: " + sysMaxPrepared + ")");
        LOG.info("Client Concurrency    : " + concurrency);
        LOG.info("======================================");

        String batchId = DEFAULT_PREFIX + "_" + (System.currentTimeMillis() / 1000);
        List<String> successGids = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        // 3. 执行注入
        for (int i = 1; i <= finalTarget; i++) {
            final int idx = i;
            final String gid = batchId + "_" + String.format("%06d", idx);
            executor.submit(() -> {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {
                    conn.setAutoCommit(false);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("SELECT 1"); 
                        stmt.execute("PREPARE TRANSACTION '" + gid + "'");
                        successGids.add(gid);
                    } catch (SQLException e) {
                        LOG.warning("❌ PREPARE Failed [" + gid + "]: " + e.getMessage());
                    }
                } catch (SQLException e) {
                    LOG.warning("❌ Connection Failed at task #" + idx + ": " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(600, TimeUnit.SECONDS);

        LOG.info(">>> Injection Summary: Prepared " + successGids.size() + " transactions.");
        
        // 4. 保持状态
        if (!successGids.isEmpty()) {
            LOG.info("⏳ Holding for " + holdDuration + " seconds...");
            Thread.sleep(holdDuration * 1000L);
        }

        // 5. 自动清理
        LOG.info(">>> Starting automatic cleanup...");
        for (String gid : successGids) {
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ROLLBACK PREPARED '" + gid + "'");
            } catch (SQLException e) {
                LOG.warning("⚠️ Cleanup failed for GID: " + gid);
            }
        }
        LOG.info("✅ Finished.");
    }

    private int getMaxPreparedTransactionsLimit() {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW max_prepared_transactions")) {
            return rs.next() ? Integer.parseInt(rs.getString(1).trim()) : 0;
        } catch (Exception e) {
            LOG.warning("Could not fetch max_prepared_transactions, using default 0.");
            return 0;
        }
    }

    public static void main(String[] args) throws Exception {
        Properties cmdProps = new Properties();
        Integer targetCount = null;
        int holdDuration = 30;
        int concurrency = 50;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-url": cmdProps.setProperty("url", args[++i]); break;
                case "-user": cmdProps.setProperty("user", args[++i]); break;
                case "-password": cmdProps.setProperty("password", args[++i]); break;
                case "-target-prepared-count": targetCount = Integer.parseInt(args[++i]); break;
                case "-hold-duration-sec": holdDuration = Integer.parseInt(args[++i]); break;
                case "-client-concurrency": concurrency = Integer.parseInt(args[++i]); break;
                case "-h": case "-help": printUsage(); return;
            }
        }

        try {
            loadConfiguration(cmdProps);
            new MaxPreparedTransactionFaultInject().run(targetCount, holdDuration, concurrency);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("\nUsage Example (GaussDB/openGauss):");
        System.out.println("  java -cp .:your-driver.jar MaxPreparedTransactionFaultInject \\");
        System.out.println("    -url \"jdbc:gaussdb://127.0.0.1:5432/postgres\" \\");
        System.out.println("    -user \"omm\" -password \"Gauss@123\"");
    }
}
