import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Fault Injection Tool: Uncommitted Transaction Lock Contention
 * Compatible with GaussDB / openGauss / PostgreSQL
 */
public class UncommittedTransactionFaultInject {

    private static final Logger LOG =
            Logger.getLogger(UncommittedTransactionFaultInject.class.getName());

    private final String url;
    private final String user;
    private final String password;

    private final int lockHoldingSessions;
    private final int waitingSessions;
    private final int lockHoldDuration;
    private final int lockRowsCount;
    private final int statementTimeoutSeconds;

    private final List<Connection> lockHolderConnections =
            new CopyOnWriteArrayList<>();

    public UncommittedTransactionFaultInject(
            String url,
            String user,
            String password,
            int lockHoldingSessions,
            int waitingSessions,
            int lockHoldDuration,
            int lockRowsCount,
            int statementTimeoutSeconds) {

        this.url = url;
        this.user = user;
        this.password = password;
        this.lockHoldingSessions = lockHoldingSessions;
        this.waitingSessions = waitingSessions;
        this.lockHoldDuration = lockHoldDuration;
        this.lockRowsCount = lockRowsCount;
        this.statementTimeoutSeconds = statementTimeoutSeconds;
    }

    private static Properties loadDefaultProperties() {
        Properties props = new Properties();
        try (InputStream in =
                     UncommittedTransactionFaultInject.class
                             .getResourceAsStream("/db.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            LOG.fine("No db.properties found.");
        }
        return props;
    }

    public void run() throws Exception {

        try (Connection conn =
                     DriverManager.getConnection(url, user, password)) {
            DatabaseMetaData meta = conn.getMetaData();
            LOG.info("Database: " +
                    meta.getDatabaseProductName() + " " +
                    meta.getDatabaseProductVersion());
        }

        String scriptId =
                "uncommitted_txn_" + (System.currentTimeMillis() / 1000);
        String tableName =
                "fault_inject.uncommitted_txn_" + scriptId;

        LOG.info("Starting uncommitted transaction fault injection");

        try {
            ensureSchemaExists();
            createTestTable(tableName);
            prepareTestData(tableName);

            ExecutorService holderPool =
                    Executors.newFixedThreadPool(lockHoldingSessions);
            ExecutorService waiterPool =
                    Executors.newFixedThreadPool(
                            Math.min(waitingSessions, 100));

            for (int i = 0; i < lockHoldingSessions; i++) {
                final int id = i + 1;
                holderPool.submit(
                        () -> holdRowLocksWithoutCommit(tableName, id));
            }

            Thread.sleep(2000);

            for (int i = 0; i < waitingSessions; i++) {
                final int id = i + 1;
                waiterPool.submit(
                        () -> attemptUpdateLockedRows(tableName, id));
            }

            LOG.info("Holding locks for "
                    + lockHoldDuration + " seconds...");
            Thread.sleep(lockHoldDuration * 1000L);

            LOG.info("Releasing transactions...");
            releaseLockHolderTransactions();

            Thread.sleep(3000);

            holderPool.shutdownNow();
            waiterPool.shutdownNow();

        } finally {
            cleanup(tableName);
        }
    }

    /**
     * Check pg_namespace to determine whether schema exists.
     * This avoids using "CREATE SCHEMA IF NOT EXISTS"
     */
    private void ensureSchemaExists() throws SQLException {

        try (Connection conn =
                     DriverManager.getConnection(url, user, password)) {

            boolean exists;

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM pg_namespace WHERE nspname = ?")) {

                ps.setString(1, "fault_inject");

                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (!exists) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE SCHEMA fault_inject");
                    LOG.info("Schema created.");
                }
            }
        }
    }

    private void createTestTable(String tableName) throws SQLException {
        try (Connection conn =
                     DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            stmt.execute(
                    "CREATE TABLE " + tableName +
                            " (id SERIAL PRIMARY KEY, value INT)");
        }
    }

    private void prepareTestData(String tableName)
            throws SQLException {

        try (Connection conn =
                     DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            conn.setAutoCommit(false);

            for (int i = 1;
                 i <= Math.max(100, lockRowsCount);
                 i++) {
                stmt.addBatch(
                        "INSERT INTO " + tableName +
                                " (id, value) VALUES (" +
                                i + ", 0)");
            }

            stmt.executeBatch();
            conn.commit();
        }
    }

    private void holdRowLocksWithoutCommit(
            String tableName, int id) {

        try (Connection conn =
                     DriverManager.getConnection(url, user, password)) {

            conn.setAutoCommit(false);
            lockHolderConnections.add(conn);

            String sql =
                    "UPDATE " + tableName +
                            " SET value = value + 1 WHERE id <= ?";

            try (PreparedStatement ps =
                         conn.prepareStatement(sql)) {

                ps.setInt(1, lockRowsCount);
                int count = ps.executeUpdate();

                LOG.info("Holder[" + id +
                        "] locked " + count + " rows.");
            }

        } catch (Exception e) {
            LOG.warning("Holder[" + id + "] failed: "
                    + e.getMessage());
        }
    }

    private void attemptUpdateLockedRows(
            String tableName, int id) {

        try (Connection conn =
                     DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            conn.setAutoCommit(false);

            stmt.execute("SET statement_timeout = "
                    + (statementTimeoutSeconds * 1000));

            stmt.execute("UPDATE " + tableName +
                    " SET value = value + 1 WHERE id <= "
                    + lockRowsCount);

            conn.rollback();

        } catch (Exception e) {
            LOG.fine("Waiter[" + id +
                    "] blocked: " + e.getMessage());
        }
    }

    private void releaseLockHolderTransactions() {
        for (Connection conn : lockHolderConnections) {
            try {
                if (conn != null && !conn.isClosed())
                    conn.commit();
            } catch (SQLException ignored) {
            } finally {
                safeClose(conn);
            }
        }
        lockHolderConnections.clear();
    }

    private void cleanup(String tableName) {
        releaseLockHolderTransactions();
        try (Connection conn =
                     DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS " + tableName);

        } catch (SQLException e) {
            LOG.warning("Cleanup error: " + e.getMessage());
        }
    }

    private void safeClose(Connection conn) {
        if (conn != null) {
            try { conn.close(); }
            catch (SQLException ignored) {}
        }
    }

    public static void main(String[] args)
            throws Exception {

        Properties props = loadDefaultProperties();

        String url = props.getProperty("url", "");
        String user = props.getProperty("user", "");
        String password = props.getProperty("password", "");

        int holders = 1;
        int waiters = 100;
        int duration = 60;
        int rows = 10;
        int timeout = 30;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-url": url = args[++i]; break;
                case "-user": user = args[++i]; break;
                case "-password": password = args[++i]; break;
                case "-lockHoldingSessions": holders = Integer.parseInt(args[++i]); break;
                case "-waitingSessions": waiters = Integer.parseInt(args[++i]); break;
                case "-lockHoldDuration": duration = Integer.parseInt(args[++i]); break;
                case "-lockRowsCount": rows = Integer.parseInt(args[++i]); break;
                case "-statementTimeoutSeconds": timeout = Integer.parseInt(args[++i]); break;
            }
        }

        if (url.isEmpty() || user.isEmpty()) {
            System.err.println("Error: -url and -user are required.");
            System.exit(1);
        }

        new UncommittedTransactionFaultInject(
                url, user, password,
                holders, waiters,
                duration, rows, timeout
        ).run();
    }
}
