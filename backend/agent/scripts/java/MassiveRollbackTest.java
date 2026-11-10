import java.io.InputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MassiveRollbackTest {

    private static String url;
    private static String user;
    private static String password;
    private static String targetDbName = "";

    private static final AtomicLong clientCommits = new AtomicLong(0);
    private static final AtomicLong clientRollbacks = new AtomicLong(0);

    static {
        InputStream in = null;
        try {
            Properties props = new Properties();
            in = MassiveRollbackTest.class.getResourceAsStream("/db.properties");
            if (in != null) {
                props.load(in);
                url = props.getProperty("url");
                user = props.getProperty("user");
                password = props.getProperty("password");
                System.out.println("【配置加载】成功从 db.properties 获取基础配置");
            }
        } catch (IOException e) {
            System.err.println("【配置错误】读取 db.properties 失败: " + e.getMessage());
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static void main(String[] args) {
        int durationSec = 60; 
        int threads = 16;     
        double rollbackRate = 0.7;

        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            String value = (i + 1 < args.length) ? args[i + 1] : "";
            switch (key) {
                case "-url": url = value; i++; break;
                case "-user": user = value; i++; break;
                case "-password": password = value; i++; break;
                case "-duration": durationSec = Integer.parseInt(value); i++; break;
                case "-threads": threads = Integer.parseInt(value); i++; break;
                case "-rate": rollbackRate = Double.parseDouble(value); i++; break;
            }
        }

        if (url == null || user == null || password == null) {
            System.err.println("用法提示: java MassiveRollbackTest [-duration <sec>] [-threads <n>] [-rate <0.x>]");
            System.exit(1);
        }

        detectDbName();
        long[] initialStats = getDbStats();
        long endLimitMs = System.currentTimeMillis() + (durationSec * 1000L);

        System.out.println(">>> 开始注入: 持续 " + durationSec + " 秒 | 线程: " + threads + " | 回滚率: " + (rollbackRate*100) + "%");
        runLoad(endLimitMs, threads, rollbackRate);
        
        System.out.println(">>> 注入结束，汇总数据库统计信息...");
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        long[] finalStats = getDbStats();

        // 计算总量
        long clientTotal = clientCommits.get() + clientRollbacks.get();
        long dbTotal = (finalStats[0] - initialStats[0]) + (finalStats[1] - initialStats[1]);

        System.out.println("\n=================== 压测最终统计=====================");
        System.out.println(String.format("【客户端】 总事务: %-6d | 提交: %-6d | 回滚: %-6d", 
                        clientTotal, clientCommits.get(), clientRollbacks.get()));
        System.out.println(String.format("【数据库】 总事务: %-6d | 提交: %-6d | 回滚: %-6d", 
                        dbTotal, (finalStats[0] - initialStats[0]), (finalStats[1] - initialStats[1])));
        System.out.println("======================================================");
    }

    private static void detectDbName() {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_database()")) {
            if (rs.next()) targetDbName = rs.getString(1);
        } catch (SQLException e) {
            System.err.println("数据库连接失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runLoad(final long endLimitMs, int threads, final double rollbackRate) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try (Connection conn = DriverManager.getConnection(url, user, password)) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("CREATE TEMPORARY TABLE temp_rollback_test (id BIGINT, val FLOAT)");
                        }
                        conn.setAutoCommit(false);
                        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO temp_rollback_test VALUES (?, ?)")) {
                            long k = 0;
                            while (System.currentTimeMillis() < endLimitMs) {
                                ps.setLong(1, k++);
                                ps.setDouble(2, Math.random());
                                ps.executeUpdate(); 
                                if (Math.random() < rollbackRate) {
                                    conn.rollback(); 
                                    clientRollbacks.incrementAndGet();
                                } else {
                                    conn.commit();
                                    clientCommits.incrementAndGet();
                                }
                            }
                        }
                    } catch (SQLException e) { System.err.println("线程报错: " + e.getMessage()); }
                }
            });
        }
        executor.shutdown();
        try { executor.awaitTermination(1, TimeUnit.HOURS); } catch (InterruptedException ignored) {}
    }

    private static long[] getDbStats() {
        String sql = "SELECT xact_commit, xact_rollback FROM pg_stat_database WHERE datname = ?";
        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetDbName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return new long[]{0, 0};
    }
}