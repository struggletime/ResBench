import java.io.InputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.*;

public class MaxMemoryTest {

    private static String url;
    private static String user;
    private static String password;
    private static String workMem;
    private static final Random random = new Random();

    static {
        try {
            Properties props = new Properties();
            InputStream in = MaxMemoryTest.class.getResourceAsStream("/db.properties");
            props.load(in);

            url = props.getProperty("url");
            user = props.getProperty("user");
            password = props.getProperty("password");


        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String calculateWorkMem() {
        String workMem = "128GB";
        Connection connection = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            connection = DriverManager.getConnection(url, user, password);
            stmt = connection.createStatement();
            stmt.execute("show max_process_memory;");
            rs = stmt.getResultSet();
            double maxProcessMemory = 0;
            if (rs.next()) {
                String maxProcessMemoryStr = rs.getString(1);
                maxProcessMemory = parseMemoryValues(maxProcessMemoryStr);
            }
            rs.close();
            stmt.execute("show shared_buffers;");
            rs = stmt.getResultSet();
            double SharedBuffers = 0;
            if (rs.next()) {
                String SharedBuffersStr = rs.getString(1);
                SharedBuffers = parseMemoryValues(SharedBuffersStr);
            }
            double workMemGb = (maxProcessMemory - SharedBuffers) * 0.7;
            long roundWorkMemGb = Math.round(workMemGb);
            workMem = roundWorkMemGb + "GB";
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
                if (stmt != null) {
                    stmt.close();
                }
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return workMem;
    }

    private static double parseMemoryValues(String memoryValues) {
        try
        {
            String numericPart = memoryValues.replaceAll("[^0-9.]", "");
            return Double.parseDouble(numericPart);
        }  catch(NumberFormatException e)
        {
            System.err.println("Failed to parse memory values:" + memoryValues);
            return 0.0;
        }
    }
    /* ======================= 初始化大数据表 ======================= */

    public static void initTables() {
        System.out.println("Table will be created as a TEMP table in each thread.");
    }

    /**
     * Creates and populates a temporary table for the current thread's connection.
     */
    public static void createAndPopulateTempTable(Connection conn,int data_size) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            long threadId = Thread.currentThread().getId();
            System.out.println("Thread " + threadId + ": Creating and populating TEMP table...");
            String createTableSql = "CREATE TEMP TABLE test_large_table (" +
                "id integer primary key, " +
                "random_data text, " +
                "num_col numeric" +
            ")";
            stmt.execute(createTableSql);

            stmt.setQueryTimeout(300); // 5 minutes timeout for insert
            String insertSql = "INSERT INTO test_large_table (id, random_data, num_col) " +
                "SELECT " +
                "    gs, " +
                "    repeat(md5(random()::text),100), " +
                "    random()*1000000 " +
                "FROM generate_series(1,{data_size}) AS gs";
            String excsql =  insertSql.replace("{data_size}",String.valueOf(data_size));
            stmt.execute(excsql);

            System.out.println("Thread " + threadId + ": TEMP table setup finished.");
        }
    }


    /* ======================= 核心内存压测 SQL ======================= */

    public static void memoryHeavyQuery(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET work_mem = '"+ workMem + "'");
            stmt.setQueryTimeout(120); // Set timeout to infinite for the heavy query

            String sql = "SELECT a.*,b.*,c.* " +
                         "FROM test_large_table a " +
                         "CROSS JOIN test_large_table b " +
                         "CROSS JOIN test_large_table c " +
                         "ORDER BY " +
                         "a.id,a.num_col, " +
                         "b.id,b.num_col, " +
                         "c.id,c.num_col";

            stmt.execute(sql);

            // ⚠️ 关键：强制持有执行态内存
            Thread.sleep(100);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* ======================= 并发执行（真实同时起跑） ======================= */

    public static void executeMemoryTest(int threadNum,int data_size) {
        ExecutorService pool = Executors.newFixedThreadPool(threadNum);
        CountDownLatch startLatch = new CountDownLatch(1);
        workMem = calculateWorkMem();
        for (int i = 0; i < threadNum; i++) {
            pool.submit(() -> {
                try (Connection conn = DriverManager.getConnection(url, user, password)) {

                    // Create and populate the temp table for this thread's session
                    createAndPopulateTempTable(conn,data_size);

                    startLatch.await();
                    System.out.println("Thread " + Thread.currentThread().getId() + " started pressure.");

                    long count = 0;
                    while (true) {
                        memoryHeavyQuery(conn);
                        count++;
                        if (count <= 5 || count % 10 == 0) {
                            System.out.println("Thread " + Thread.currentThread().getId() + " executed " + count + " queries.");
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        System.out.println("All threads ready, starting pressure...");
        startLatch.countDown();

        pool.shutdown();
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /* ======================= 清理 ======================= */

    public static void destroyTables() {
        System.out.println("TEMP tables are automatically dropped when connections close.");
    }

    /* ======================= main ======================= */

    public static void main(String[] args) {
        int threadNum = 100;
        int data_size = 100000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-thread_num": threadNum = Integer.parseInt(args[++i]); break;
                case "-data_size": data_size = Integer.parseInt(args[++i]); break;
            }
        }

        System.out.println(LocalDateTime.now() +
                " ===== MaxMemory Test START =====");
        System.out.println("Thread num = " + threadNum);

        initTables();
        executeMemoryTest(threadNum,data_size);
    }
}
