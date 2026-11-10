import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

public class StackOverflowTest {

    private static String url;
    private static String user;
    private static String password;
    private static String type;        // 故障类型字符串
    private static long duration = 0;
    private static long interval = 1000;

    // 加载配置
    static {
        InputStream in = null;
        try {
            Properties props = new Properties();
            in = StackOverflowTest.class.getResourceAsStream("/db.properties");
            if (in != null) {
                props.load(in);
                url = props.getProperty("url");
                user = props.getProperty("user");
                password = props.getProperty("password");
                
                System.out.println("【配置加载】成功从 db.properties 加载配置");
            } else {
                System.err.println("【配置错误】未找到 db.properties 文件");
            }
        } catch (IOException e) {
            System.err.println("【配置错误】读取配置文件失败: " + e.getMessage());
        } catch (NumberFormatException e) {
            System.err.println("【配置错误】配置文件中的数值格式错误: " + e.getMessage());
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static void main(String[] args) {
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
                case "-type":
                    type = args[++i].toLowerCase();
                    break;
                case "-duration":
                    duration = Long.parseLong(args[++i]);
                    break;
                case "-interval":
                    interval = Long.parseLong(args[++i]);
                    break;
                default:
                    break;
            }
        }

        if (url == null || user == null || password == null || type == null || duration <= 0) {
            printUsage();
            System.exit(1);
        }

        System.out.println("【故障设置】类型: " + type + " | 总时长: " + duration + "ms | 间隔: " + interval + "ms");
        runFaultInjection();
    }

    private static void printUsage() {
        System.err.println("用法提示:");
        System.err.println("java StackOverflowTest -url <url> -user <user> -password <pass> -type <mode> -duration <ms> [-interval <ms>]");
        System.err.println("\n可选模式 (-type):");
        System.err.println("  func_recurse  : 函数递归爆栈");
        System.err.println("  proc_recurse  : 存储过程递归爆栈");
        System.err.println("  trans_recurse : 事务中触发函数递归");
        System.err.println("  sql_depth     : 深度 SQL 解析爆栈 (超长 OR 条件)");
        System.err.println("  view_nest     : 视图深度嵌套爆栈");
        System.err.println("  join_bomb     : 多表连接执行计划爆栈");
    }

    private static void runFaultInjection() {
        long startTime = System.currentTimeMillis();
        long endTime = startTime + duration;
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        int count = 0;

        while (System.currentTimeMillis() < endTime) {
            count++;
            String currentTime = sdf.format(new Date());
            System.out.print("[" + currentTime + "] 第 " + count + " 次尝试注入 (" + type + ")... ");

            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                executeFault(conn, type);
                System.out.println("成功发送指令");
            } catch (SQLException e) {
                System.out.println("触发异常 -> " + e.getSQLState() + ": " + e.getMessage().split("\n")[0]);
            } catch (Exception e) {
                System.out.println("错误: " + e.getMessage());
            }

            // 检查剩余时间是否足够进行下一次间隔
            if (System.currentTimeMillis() + interval < endTime) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
            } else {
                break;
            }
        }
        System.out.println(">>> 注入任务执行结束。");
    }

    private static void executeFault(Connection conn, String type) throws SQLException {
        switch (type) {
            case "func_recurse":
                testFunctionStackOverflow(conn);
                break;
            case "proc_recurse":
                testProcedureStackOverflow(conn);
                break;
            case "trans_recurse":
                testTransactionStackOverflow(conn);
                break;
            case "sql_depth":
                testNestedExpressionStackOverflow(conn);
                break;
            case "view_nest":
                testViewBombStackOverflow(conn);
                break;
            case "join_bomb":
                testJoinBombStackOverflow(conn);
                break;
            default:
                throw new IllegalArgumentException("未知的故障类型: " + type);
        }
    }

    private static void testFunctionStackOverflow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE FUNCTION java_recurse_func(n INTEGER) RETURNS INT AS $$ BEGIN RETURN java_recurse_func(n + 1); END; $$ LANGUAGE plpgsql;");
            stmt.execute("SELECT java_recurse_func(1)");
        }
    }

    private static void testProcedureStackOverflow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE PROCEDURE open_recurse_proc(n INTEGER) AS " +
                         "BEGIN CALL open_recurse_proc(n + 1); END;");
            stmt.execute("CALL open_recurse_proc(1)");
        }
    }

    private static void testTransactionStackOverflow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE FUNCTION java_recurse_func(n INTEGER) RETURNS INT AS $$ BEGIN RETURN java_recurse_func(n + 1); END; $$ LANGUAGE plpgsql;");
        }
        conn.setAutoCommit(false);
        try (Statement transStmt = conn.createStatement()) {
            transStmt.execute("SELECT java_recurse_func(1)");
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static void testNestedExpressionStackOverflow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try { stmt.execute("SET max_stack_depth = '2MB'"); } catch (SQLException ignored) {}
            StringBuilder sb = new StringBuilder("SELECT 1 WHERE 1=1");
            for (int i = 0; i < 50000; i++) sb.append(" OR 1=1");
            stmt.execute(sb.toString());
        }
    }

    private static void testViewBombStackOverflow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try { stmt.execute("SET max_stack_depth = '2MB'"); } catch (SQLException ignored) {}
            stmt.execute("CREATE TEMPORARY TABLE IF NOT EXISTS base_table (id int)");
            String prevRelation = "base_table";
            for (int i = 600; i >= 0; i--) {
                String currView = "v_" + i;
                stmt.execute("CREATE OR REPLACE TEMPORARY VIEW " + currView + " AS SELECT id FROM " + prevRelation);
                prevRelation = currView;
            }
            stmt.execute("SELECT * FROM v_0");
        }
    }

    private static void testJoinBombStackOverflow(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            int tableCount = 15;
            stmt.execute("SET max_stack_depth = '1MB'");
            stmt.execute("SET geqo = off");
            stmt.execute("SET join_collapse_limit = " + (tableCount + 1));
            stmt.execute("SET from_collapse_limit = " + (tableCount + 1));
            StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM ");
            for (int i = 0; i < tableCount; i++) {
                stmt.execute("CREATE TEMPORARY TABLE IF NOT EXISTS t_" + i + " (id int)");
                if (i == 0) sqlBuilder.append("t_0");
                else sqlBuilder.append(" JOIN t_").append(i).append(" ON t_").append(i-1).append(".id = t_").append(i).append(".id");
            }
            stmt.execute(sqlBuilder.toString());
        }
    }
}