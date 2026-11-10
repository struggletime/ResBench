import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.util.Properties;

/**
 * 测试业务并发量超过数据库IO容量上限的故障场景
 * 通过大量并发的INSERT/UPDATE操作和大数据量的SELECT查询来模拟IO瓶颈
 */
public class MaxIOTest {
	private static String url;
	private static String user;
	private static String password;
	private static final Random random = new Random();

	static {
		try {
			// 读取db.properties文件
			Properties props = new Properties();
			InputStream in = MaxIOTest.class.getResourceAsStream("/db.properties");
			// 加载文件
			props.load(in);
			// 读取信息
			url = props.getProperty("url");
			user = props.getProperty("user");
			password = props.getProperty("password");
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("驱动程序注册出错");
		}
	}

	/**
	 * 初始化表结构（临时表）
	 */
	public static void initTables() {
		System.out.println(">>> IO Test Temp Table setup ready (will be created per-thread).");
	}

	/**
	 * 在单个连接中创建临时表
	 */
	public static void createTempTableForThread(Connection connection) throws SQLException {
		Statement stmt = connection.createStatement();

		// IO测试表 - 大量数据行（临时表）
		stmt.execute(
			"CREATE TEMP TABLE temp_io_test (" +
			"id BIGINT, " +
			"data_value VARCHAR(4096), " +
			"create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
			"update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
			")"
		);

		// 初始化一些测试数据
		int initRows = 5000; // 减少到 5000 行避免初始化过慢
		for (int i = 1; i <= initRows; i++) {
			stmt.execute(
				"INSERT INTO temp_io_test(id, data_value) VALUES (" + i + ", '" + generateLargeData(2048) + "')"
			);
			if (i % 1000 == 0) {
				System.out.println("Thread " + Thread.currentThread().getId() + " inserted rows: " + i);
			}
		}

		stmt.close();
	}

	/**
	 * 大数据量写入测试 - 模拟IO写入瓶颈
	 */
	public static void largeDataInsertTest(Connection connection) {
		PreparedStatement stmt = null;
		try {
			connection.setAutoCommit(false);

			String sql = "INSERT INTO temp_io_test(id, data_value) VALUES (?, ?)";
			stmt = connection.prepareStatement(sql);

			// 生成大量数据进行插入
			for (int i = 0; i < 10; i++) {
				stmt.setLong(1, System.nanoTime() % 1000000);
				stmt.setString(2, generateLargeData(2048)); // 2KB数据
				stmt.addBatch();
			}

			stmt.executeBatch();
			connection.commit();

		} catch (SQLException e) {
			try {
				connection.rollback();
			} catch (SQLException ex) {
				ex.printStackTrace();
			}
		} finally {
			try {
				if (stmt != null) stmt.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 大数据量全表扫描测试 - 模拟IO读取瓶颈
	 */
	public static void largeDataSelectTest(Connection connection) {
		PreparedStatement stmt = null;
		ResultSet rs = null;

		try {
			// 全表扫描 - 产生大量IO
			String sql = "SELECT * FROM temp_io_test WHERE id > 0 ORDER BY id DESC LIMIT 1000";
			stmt = connection.prepareStatement(sql);
			stmt.setQueryTimeout(30);

			rs = stmt.executeQuery();

			int count = 0;
			while (rs.next()) {
				String data = rs.getString("data_value");
				count++;
			}

		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if (rs != null) rs.close();
				if (stmt != null) stmt.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}


	/**
	 * 高频更新测试 - 模拟IO更新瓶颈
	 */
	public static void frequentUpdateTest(Connection connection) {
		PreparedStatement stmt = null;
		try {
			String sql = "UPDATE temp_io_test SET data_value = ? WHERE id = ?";
			stmt = connection.prepareStatement(sql);

			// 随机更新多条记录
			for (int i = 0; i < 10; i++) {
				long randomId = random.nextLong() % 5000 + 1;
				if (randomId <= 0) randomId = 1;

				stmt.setString(1, generateLargeData(1024));
				stmt.setLong(2, randomId);
				stmt.executeUpdate();
			}

		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			try {
				if (stmt != null) stmt.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * 执行IO测试 - 多线程并发执行
	 */
	public static void executeIOTest(int threadNum) {
		ExecutorService executorService = Executors.newFixedThreadPool(threadNum);

		for (int i = 0; i < threadNum; i++) {
			executorService.execute(new Runnable() {
				@Override
				public void run() {
					Connection connection = null;
					try {
						connection = DriverManager.getConnection(url, user, password);

						// 每个线程创建自己的临时表
						createTempTableForThread(connection);

						// 每个线程执行大量IO操作
						long endTime = System.currentTimeMillis() + 60_000; // 压 60 秒

						while (System.currentTimeMillis() < endTime) {
							largeDataSelectTest(connection);
							frequentUpdateTest(connection);
							largeDataInsertTest(connection);

							try {
								Thread.sleep(1); // 短暂延迟
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
						}
					} catch (SQLException e) {
						e.printStackTrace();
					} finally {
						try {
							if (connection != null) connection.close();
						} catch (SQLException e) {
							e.printStackTrace();
						}
					}
				}
			});
		}

		executorService.shutdown();
		try {
			executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 清理表（临时表在连接关闭时自动删除）
	 */
	public static void destroyTables() {
		System.out.println(">>> IO Test Temp Table will be auto-dropped when connections close.");
	}

	/**
	 * 生成指定大小的随机字符串数据
	 */
	private static String generateLargeData(int size) {
		StringBuilder sb = new StringBuilder(size);
		String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		for (int i = 0; i < size; i++) {
			sb.append(chars.charAt(random.nextInt(chars.length())));
		}
		return sb.toString();
	}

	public static void main(String[] args) {
        int threadNum = 100;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-thread_num": threadNum = Integer.parseInt(args[++i]); break;
            }
        }
		LocalDateTime localtime = LocalDateTime.now();

		System.out.println(localtime + " ===== MaxIO Test Starting..... thread_num:" + threadNum);

		// 初始化表结构
		initTables();

		long start = System.nanoTime();
		executeIOTest(threadNum);
		long end = System.nanoTime();

		System.out.println(String.format("Transaction Total Time cost %d ms", (end - start) / 1000000));

		// 清理
		destroyTables();

		localtime = LocalDateTime.now();
		System.out.println(localtime + " ===== MaxIO Test Ending.....");
	}
}
