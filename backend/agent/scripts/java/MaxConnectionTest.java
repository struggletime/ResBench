import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MaxConnectionTest {
private static String url;
    private static String user;
    private static String password;

    private static int dbMaxConnections = 0;
    private static int dbMaxThreads = 0;
    
    private static long durationMillis = 0;

    static {
        InputStream in = null;
        try {
            Properties props = new Properties();
            in = MaxConnectionTest.class.getResourceAsStream("/db.properties");
            if (in != null) {
                props.load(in);
                url = props.getProperty("url");
                user = props.getProperty("user");
                password = props.getProperty("password");
                
                System.out.println("【配置加载】成功从 db.properties 获取基础配置");

                System.out.println("    -> JDBC URL: " + url);
                System.out.println("    -> DB User : " + user);
                
                // 连通性测试
                java.util.logging.Logger.getLogger("org.opengauss").setLevel(java.util.logging.Level.OFF);
                java.util.logging.Logger.getLogger("org.postgresql").setLevel(java.util.logging.Level.OFF);
                
                try (Connection testConn = DriverManager.getConnection(url, user, password)) {
                    System.out.println("【连通性测试】成功！数据库已连接。");
                } catch (SQLException e) {
                    System.err.println("【连通性测试】失败！请检查数据库状态或 db.properties 配置。");
                    System.err.println("    错误详情: " + e.getMessage());
                }
            } else {
                System.err.println("【配置提醒】未找到 db.properties 文件");
            }
        } catch (IOException e) {
            System.err.println("【配置错误】读取 db.properties 失败: " + e.getMessage());
        } catch (NumberFormatException e) {
            System.err.println("【配置错误】db.properties 中的数值格式错误: " + e.getMessage());
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static void main(String[] args) {
        Logger.getLogger("org.opengauss").setLevel(Level.OFF);
        Logger.getLogger("org.postgresql").setLevel(Level.OFF);

        String mode = null;
        int count = 0;

        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-url":
                    url = args[++i];
                    break;
                case "-user":
                    user = args[++i];
                    break;
                case "-password":
                    password = args[++i];
                    break;
                case "-mode":
                    mode = args[++i];
                    break;
                case "-count":
                    count = Integer.parseInt(args[++i]);
                    break;
                case "-duration":
                    durationMillis = Long.parseLong(args[++i]);
                    break;
                default:
                    break;
            }
        }

        if (url == null || user == null || password == null || mode == null || durationMillis <= 0) {
            System.err.println("用法提示:");
            System.err.println("java MaxConnectionTest -url <url> -user <user> -password <pass> -mode <mode> -duration <ms> [-count <n>]");
            System.exit(1);
        }

        detectDatabaseConfig();

        System.out.println("【环境探测】 最大连接数: " + dbMaxConnections + " | 线程池上限: " + dbMaxThreads);
        System.out.println("【故障设置】 持续时长: " + durationMillis + "ms");

        switch (mode.toLowerCase()) {
            case "thread_saturation":
                int overflow = (count > 0) ? count : 32;
                System.out.println(">>> 模式: 活跃线程饱和 (thread_saturation) | 溢出数: " + overflow);
                injectThreadPoolSaturation(overflow, durationMillis);
                break;
            case "conn_exhaustion":
                int target = (dbMaxConnections > 0) ? dbMaxConnections : 200;
                System.out.println(">>> 模式: 连接数耗尽 (conn_exhaustion) | 目标连接: " + target);
                injectConnectionExhaustion(target, durationMillis);
                break;
            case "conn_storm":
                int stormThreads = (count > 0) ? count : (dbMaxConnections > 0 ? dbMaxConnections : 200);
                System.out.println(">>> 模式: 连接风暴 (conn_storm) | 并发线程: " + stormThreads);
                injectConnectionStorm(stormThreads, durationMillis);
                break;
            default:
                System.err.println("错误: 未知模式 '" + mode + "'。请使用: thread_saturation, conn_exhaustion, 或 conn_storm");
                break;
        }

        System.out.println("\n======================================================");
        System.out.println("【完成】 故障注入任务已执行结束。");
        System.out.println("运行模式: " + mode);
        System.out.println("持续总时长: " + durationMillis + " ms");
        System.out.println("======================================================");

    }

    private static void detectDatabaseConfig() {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SHOW max_connections");
            if (rs.next()) dbMaxConnections = rs.getInt(1);
            
            rs = stmt.executeQuery("SHOW thread_pool_attr");
            if (rs.next()) {
                String attr = rs.getString(1).replace("\"", "").trim();
                int group = 0, perGroup = 0;
                if (attr.contains("group=")) {
                    for (String part : attr.split(",")) {
                        if (part.trim().startsWith("group")) group = Integer.parseInt(part.split("=")[1].trim());
                        else if (part.trim().startsWith("per_group")) perGroup = Integer.parseInt(part.split("=")[1].trim());
                    }
                } else {
                    String[] parts = attr.split(",");
                    if (parts.length >= 2) {
                        group = Integer.parseInt(parts[0].trim());
                        perGroup = Integer.parseInt(parts[1].trim());
                    }
                }
                dbMaxThreads = group * perGroup;
            }
        } catch (SQLException e) { System.err.println("配置探测失败: " + e.getMessage()); }
    }

    private static void injectThreadPoolSaturation(int overflowAmount, long durationMs) {
        int safeMaxThreads = (dbMaxThreads > 0) ? dbMaxThreads : 64;
        int targetThreads = Math.min(safeMaxThreads + overflowAmount, dbMaxConnections - 2);
        
        // pg_sleep 单位为秒，将 ms 转为 s
        int sleepSec = (int) (durationMs / 1000); 

        ExecutorService executor = Executors.newFixedThreadPool(targetThreads);
        for (int i = 0; i < targetThreads; i++) {
            executor.execute(() -> {
                try (Connection conn = DriverManager.getConnection(url, user, password);
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT pg_sleep(" + sleepSec + ")");
                } catch (SQLException e) {}
            });
        }
        executor.shutdown();
        try { executor.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS); } catch (InterruptedException e) {}
    }

    private static void injectConnectionExhaustion(int targetConnections, long durationMs) {
        List<Connection> connectionPool = Collections.synchronizedList(new ArrayList<>());

        // 临时线程池
        int connectorThreads = 100; 
        ExecutorService connectorExecutor = Executors.newFixedThreadPool(connectorThreads);
        
        System.out.println("正在【并发】建立连接，目标数: " + targetConnections);

        // 并行处理
        for (int i = 0; i < targetConnections; i++) {
            connectorExecutor.execute(() -> {
                try {
                    Connection conn = DriverManager.getConnection(url, user, password);
                    conn.setAutoCommit(true);
                    connectionPool.add(conn);
                } catch (SQLException e) {
                }
            });
        }

        connectorExecutor.shutdown();
        try {
            if (!connectorExecutor.awaitTermination(20, TimeUnit.SECONDS)) {
                connectorExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {}

        System.out.println("连接建立完毕，实际建立: " + connectionPool.size());

        int actualSize = connectionPool.size();
        if (actualSize == 0) return;

        ExecutorService executor = Executors.newFixedThreadPool(actualSize);
        long endTime = System.currentTimeMillis() + durationMs;

        System.out.println("执行查询，持续: " + durationMs + "ms");

        for (Connection conn : connectionPool) {
            executor.execute(() -> {
                try {
                    while (System.currentTimeMillis() < endTime) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("SELECT 1;"); 
                        } catch (SQLException ignored) {
                            Thread.sleep(100);
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("正在释放连接...");
        for (Connection conn : connectionPool) {
            try {
                if (conn != null && !conn.isClosed()) conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private static void injectConnectionStorm(int threadNum, long durationMs) {
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadNum);
        long endTime = System.currentTimeMillis() + durationMs;

        for (int i = 0; i < threadNum; i++) {
            executor.execute(() -> {
                try {
                    latch.await(); 
                    while (System.currentTimeMillis() < endTime) {
                        try (Connection conn = DriverManager.getConnection(url, user, password)) {
                            try (Statement stmt = conn.createStatement()) { 
                                stmt.execute("SELECT 1"); 
                            }
                        } catch (SQLException ignored) {
                            Thread.sleep(10);
                        }
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown();

        executor.shutdown();
        try { 
            executor.awaitTermination(durationMs + 5000, TimeUnit.MILLISECONDS); 
        } catch (InterruptedException e) {}
    }
}