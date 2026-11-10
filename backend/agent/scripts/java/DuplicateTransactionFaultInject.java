import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Fault Injection Tool: Duplicate Transaction / Concurrent Contention.
 * This tool simulates heavy contention on a single row (Hot Row) 
 * and Primary Key conflicts.
 */
public class DuplicateTransactionFaultInject {

    private static final Logger LOG = Logger.getLogger(DuplicateTransactionFaultInject.class.getName());
    
    private String url;
    private String user;
    private String password;
    
    private int concurrentSessions = 100;
    private int lockHoldDuration = 100; 
    private static final String TEST_SCENARIO_UPDATE = "UPDATE";
    private static final String TEST_SCENARIO_INSERT = "INSERT";
    
    private String currentScenario = TEST_SCENARIO_UPDATE;

    public DuplicateTransactionFaultInject(String url, String user, String password, int concurrentSessions, int lockHoldDuration) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.concurrentSessions = concurrentSessions;
        this.lockHoldDuration = lockHoldDuration;
    }

    private static Properties loadDefaultProperties() {
        Properties props = new Properties();
        try (InputStream in = DuplicateTransactionFaultInject.class.getResourceAsStream("/db.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            LOG.fine("No db.properties found, using command line arguments.");
        }
        return props;
    }

    public void run() throws Exception {
        // Driver auto-detection via JDBC SPI (No Class.forName needed)
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            LOG.info("DB Detected: " + dbMeta.getDatabaseProductName() + " " + dbMeta.getDatabaseProductVersion());
        }

        String scriptId = "dup_txn_" + (System.currentTimeMillis() / 1000);
        String tableName = "fault_inject.dup_txn_" + scriptId;
        
        LOG.info(">>> Starting Scenario: " + currentScenario);
        LOG.info(">>> Sessions: " + concurrentSessions + ", Duration: " + lockHoldDuration + "s");
        
        try {
            ensureFaultInjectSchemaExists();
            createTestTable(tableName);
            
            if (TEST_SCENARIO_UPDATE.equals(currentScenario)) {
                prepareTestDataForUpdate(tableName);
            }
            
            List<Future<String>> futures = new ArrayList<>();
            // Limit thread pool size to prevent local OOM while maintaining high DB pressure
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrentSessions, 200));
            
            for (int i = 0; i < concurrentSessions; i++) {
                final int sessionId = i + 1;
                futures.add(executor.submit(() -> {
                    if (TEST_SCENARIO_UPDATE.equals(currentScenario)) {
                        return executeUpdateOperation(tableName, sessionId);
                    } else {
                        return executeInsertOperation(tableName, sessionId);
                    }
                }));
            }
            
            executor.shutdown();
            if (!executor.awaitTermination(lockHoldDuration + 10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            
            LOG.info("Scenario active period ended. Processing results...");
            processResults(futures);
            printLockStatistics(tableName);
            
        } finally {
            cleanupAllResources(tableName);
        }
    }

    private void processResults(List<Future<String>> futures) {
        int success = 0;
        int conflict = 0;
        int timeout = 0;
        int deadlock = 0;
        int error = 0;
        
        for (Future<String> future : futures) {
            try {
                String result = future.get(100, TimeUnit.MILLISECONDS);
                if (result == null) continue;
                if (result.startsWith("SUCCESS")) success++;
                else if (result.startsWith("CONFLICT")) conflict++;
                else if (result.startsWith("TIMEOUT")) timeout++;
                else if (result.startsWith("DEADLOCK")) deadlock++;
                else error++;
            } catch (Exception e) {
                error++;
            }
        }
        LOG.info(String.format("Final Results: Success=%d, Conflict=%d, Timeout=%d, Deadlock=%d, Error=%d", 
                 success, conflict, timeout, deadlock, error));
    }

    private void ensureFaultInjectSchemaExists() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS fault_inject");
        }
    }

    private void createTestTable(String tableName) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            // Using UNLOGGED to maximize IOPS for the fault test
            stmt.execute(String.format("CREATE UNLOGGED TABLE %s (id INT PRIMARY KEY, value INT, description TEXT)", tableName));
        }
    }

    private void prepareTestDataForUpdate(String tableName) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(String.format("INSERT INTO %s (id, value, description) VALUES (1, 100, 'hot_row')", tableName));
            }
        }
    }

    private String executeUpdateOperation(String tableName, int sessionId) {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET statement_timeout = '15s'");
                stmt.executeUpdate(String.format("UPDATE %s SET value = value + 1 WHERE id = 1", tableName));
                conn.commit();
                return "SUCCESS";
            }
        } catch (SQLException e) {
            return handleSqlException(conn, e, sessionId);
        } finally { safeClose(conn); }
    }

    private String executeInsertOperation(String tableName, int sessionId) {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET statement_timeout = '15s'");
                // Always try to insert ID=1 to trigger duplicate key conflict
                stmt.execute(String.format("INSERT INTO %s (id, value) VALUES (1, %d)", tableName, sessionId));
                conn.commit();
                return "SUCCESS";
            }
        } catch (SQLException e) {
            return handleSqlException(conn, e, sessionId);
        } finally { safeClose(conn); }
    }

    private String handleSqlException(Connection conn, SQLException e, int sessionId) {
        try { if (conn != null && !conn.getAutoCommit()) conn.rollback(); } catch (SQLException ex) {}
        String state = e.getSQLState();
        String msg = e.getMessage().toLowerCase();
        if ("57014".equals(state)) return "TIMEOUT_" + sessionId;
        if (msg.contains("deadlock")) return "DEADLOCK_" + sessionId;
        if (msg.contains("duplicate") || msg.contains("unique") || msg.contains("primary key")) return "CONFLICT_" + sessionId;
        return "ERROR_" + sessionId;
    }

    private void printLockStatistics(String tableName) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            String sql = "SELECT mode, COUNT(*) as count FROM pg_locks " +
                         "WHERE relation::regclass::text LIKE '%" + tableName + "%' GROUP BY mode";
            ResultSet rs = stmt.executeQuery(sql);
            LOG.info("Live Lock Status:");
            while (rs.next()) { LOG.info("  - " + rs.getString("mode") + ": " + rs.getInt("count")); }
        } catch (Exception e) {
            LOG.fine("Could not fetch lock stats: " + e.getMessage());
        }
    }

    private void cleanupAllResources(String tableName) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute(String.format("DROP TABLE IF EXISTS %s CASCADE", tableName));
        } catch (SQLException e) {
            LOG.warning("Cleanup warning: " + e.getMessage());
        }
    }

    private void safeClose(Connection conn) {
        if (conn != null) { try { if (!conn.isClosed()) conn.close(); } catch (SQLException e) {} }
    }

    public static void main(String[] args) throws Exception {
        Properties props = loadDefaultProperties();
        String url = props.getProperty("url", "");
        String user = props.getProperty("user", "");
        String password = props.getProperty("password", "");
        int sessions = 100;
        int duration = 60;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-url": url = args[++i]; break;
                case "-user": user = args[++i]; break;
                case "-password": password = args[++i]; break;
                case "-concurrentSessions": sessions = Integer.parseInt(args[++i]); break;
                case "-lockHoldDuration": duration = Integer.parseInt(args[++i]); break;
            }
        }

        if (url.isEmpty() || user.isEmpty()) {
            System.err.println("Usage: java DuplicateTransactionFaultInject -url <url> -user <user> -password <pass>");
            System.exit(1);
        }

        DuplicateTransactionFaultInject injector = new DuplicateTransactionFaultInject(url, user, password, sessions, duration);
        
        // Scenario 1: Row Contention (Update)
        injector.currentScenario = TEST_SCENARIO_UPDATE;
        injector.run();
        
        Thread.sleep(2000);
        
        // Scenario 2: Key Conflict (Insert)
        injector.currentScenario = TEST_SCENARIO_INSERT;
        injector.run();
    }
}