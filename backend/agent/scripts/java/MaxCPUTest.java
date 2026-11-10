import java.io.InputStream;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.time.LocalDateTime;

/**
 * Tests the scenario where concurrent business volume exceeds the database's CPU capacity limit.
 * Simulates CPU bottlenecks through CPU-intensive operations like complex computational queries.
 */
public class MaxCPUTest {
    private static String url;
    private static String user;
    private static String password;
    private static String workMem;
    private static final int QUERY_TIMEOUT_SECONDS = 120; // Increased timeout for the very complex query

    private static final String SQL_CPU_HEAVY_CTE_TEMPLATE = "WITH\n" +
            "cpu_tb1 as (\n" +
            "    select gs as id,\n" +
            "    (gs*7+13)%99999 as a,\n" +
            "    (gs*17+23)%99999 as b,\n" +
            "    md5(md5(md5(repeat(gs::text, 20) || random()::text))) as hash_val,\n" +
            "    (\n" +
            "        round((power(random(), 2) * 10000)::numeric, 4) *\n" +
            "        (1 + sin(gs)) * (1 + cos(gs)) *\n" +
            "        (1 + tan(gs/1000.0)) * (1 + atan(gs/1000.0)) *\n" +
            "        log(2.0, gs + 2.0) * exp(gs % 10)\n" +
            "    ) as calc_val\n" +
            "    from generate_series(1, {data_size}) as gs\n" +
            "),\n" +
            "cpu_tb2 as (\n" +
            "    select\n" +
            "    (gs*11+19)%99999 as a,\n" +
            "    sum(ct1.calc_val) over (partition by gs%2000 order by ct1.a, ct1.b desc) as window_sum,\n" +
            "    cume_dist() over (order by ct1.a, ct1.calc_val) as cume_dist_val,\n" +
            "    percent_rank() over (order by ct1.b, ct1.calc_val desc) as pct_rank\n" +
            "    from generate_series(1, {data_size}) as gs\n" +
            "    cross join cpu_tb1 ct1 limit {data_size}\n" +
            "),\n" +
            "cpu_tb3 as (\n" +
            "    select\n" +
            "        (gs*13 + 29) % 9999 as a,\n" +
            "        regexp_replace(md5(repeat(gs::text, 10)),\n" +
            "            '^([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})$',\n" +
            "            'uuid_\\\\1_\\\\2_\\\\3_\\\\4_\\\\5') as formatted_hash,\n" +
            "        case\n" +
            "            when formatted_hash ~ '^[a-z0-9_-]{42,42}$' and substr(formatted_hash, 6, 1) > '5' then 1\n" +
            "            else 0\n" +
            "        end as hash_valid\n" +
            "        from generate_series(1, {data_size}) as gs\n" +
            "),\n" +
            "cpu_tb2_max as (\n" +
            "    select\n" +
            "    b_ref,\n" +
            "    max_a,\n" +
            "    avg(avg_window_sum) over (partition by b_ref % 100) as avg_window_sum\n" +
            "    from (\n" +
            "        select\n" +
            "            ct1.b as b_ref,\n" +
            "            max(ct2.a) as  max_a,\n" +
            "            avg(ct2.window_sum) as avg_window_sum\n" +
            "        from cpu_tb1 ct1\n" +
            "        left join cpu_tb2 ct2 on ct2.a = ct1.b\n" +
            "        group by ct1.b\n" +
            "      ) as sub\n" +
            "),\n" +
            "final_query as (\n" +
            "    select\n" +
            "        ct1.id,\n" +
            "        ct1.a,\n" +
            "        ct1.b,\n" +
            "        ct1.hash_val,\n" +
            "        ct1.calc_val * ct2_max.avg_window_sum as combined_calc,\n" +
            "        (select avg(ct3.a) from cpu_tb3 ct3 where ct3.a = ct1.a) as correlated_avg,\n" +
            "        case\n" +
            "            when ct1.a = ct2_max.max_a or exists (\n" +
            "                select 1 from cpu_tb3 ct3\n" +
            "                where ct3.a = ct1.b and ct3.hash_valid = 1\n" +
            "            ) then '匹配'\n" +
            "            else '不匹配'\n" +
            "        end as match_result,\n" +
            "        row_number() over (partition by match_result order by ct1.calc_val desc) as rn,\n" +
            "        round(avg(ct1.calc_val) over (partition by match_result), 6) as avg_calc_val\n" +
            "    from cpu_tb1 ct1\n" +
            "    left join cpu_tb2_max ct2_max on ct2_max.b_ref = ct1.b\n" +
            ")\n" +
            "select * from final_query order by match_result,rn limit 10;";

    static {
        try {
            Properties props = new Properties();
            InputStream in = MaxCPUTest.class.getResourceAsStream("/db.properties");
            props.load(in);
            url = props.getProperty("url");
            user = props.getProperty("user");
            password = props.getProperty("password");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error loading db.properties.");
            System.exit(1);
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

    /**
     * Executes a single, highly complex, CPU-intensive query.
     */
        public static void cpuHeavyQuery(Connection conn,String sql) {
        try (

            Statement stmt = conn.createStatement()) {
            stmt.execute("SET work_mem = '"+ workMem + "'");
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.execute(sql);
        } catch (SQLException e) {
            System.err.println("Query failed: " + e.getMessage());
        }
    }

    /**
     * Executes the CPU test - multiple threads concurrently running the heavy query.
     */
    public static void executeCPUTest(int threadNum,int data_size) {

        System.out.println(">>> CPU Test starting: " + threadNum + " threads will be launched incrementally to ramp up pressure.");
        ExecutorService executorService = Executors.newFixedThreadPool(threadNum);
        workMem = calculateWorkMem();
        String DynamicSql=SQL_CPU_HEAVY_CTE_TEMPLATE.replace("{data_size}",String.valueOf(data_size));

        for (int i = 0; i < threadNum; i++) {
            executorService.submit(() -> {
                try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    while (!Thread.currentThread().isInterrupted()) {
                        cpuHeavyQuery(connection,DynamicSql);
                        System.out.println("Thread " + Thread.currentThread().getId() + " finished a query cycle.");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            try {
                System.out.println(">>> Launched thread " + (i + 1) + "/" + threadNum);
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println(">>> All threads launched. The test will run indefinitely. Press Ctrl+C to stop.");
    }

    public static void main(String[] args) {
        int threadNum = 100;
        int data_size = 100000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-thread_num": threadNum = Integer.parseInt(args[++i]); break;
                case "-data_size": data_size = Integer.parseInt(args[++i]); break;
            }
        }
        LocalDateTime localtime = LocalDateTime.now();
        System.out.println(localtime + " ===== MaxCPU Test Starting... Threads: " + threadNum);

        executeCPUTest(threadNum,data_size);

        // The test runs indefinitely. Add a shutdown hook for graceful termination.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook initiated. Test is ending.");
        }));
    }
}