package com.resbench.service;

import com.resbench.benchmarks.tpcc.TPCCConstants;
import com.resbench.pojo.dto.TestConnectionRequest;
import com.resbench.pojo.dto.TpccWarehouseVerifyRequest;
import com.resbench.pojo.dto.TpccWarehouseVerifyResponse;
import com.resbench.service.TpccDataVerifyEvaluator.Evaluation;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 检查目标库实际的 TPCC 仓数，与请求里配置的仓数比对。只做 tpcc。
 *
 * <p>要解决的是"看不见"：引擎决定建不建仓只看 {@code runOptions.reload} 这一个标志，
 * 从不问库里已经有多少仓 —— 表单填 200 仓而库里躺着上一轮 1000 仓时，用户无从知晓，
 * 只能靠再跑一次 {@code reload: true} 盲重建（实测 1000 仓要 4h08m）。
 *
 * <p>建连<b>复用</b> {@link TestConnectionService#openConnection}，保证"检查看的库"
 * 和"真跑压测用的库"是同一个（驱动选择 / URL 拼装 / 超时 / OceanBase 租户重试都同源）。
 */
@Service
public class TpccDataVerifyService {
  private static final Logger LOG = LoggerFactory.getLogger(TpccDataVerifyService.class);

  private static final Set<String> SUPPORTED_DB_TYPES =
      Set.of("postgresql", "opengauss", "gaussdb", "mysql", "oceanbase");

  private final TestConnectionService testConnectionService;

  public TpccDataVerifyService(TestConnectionService testConnectionService) {
    this.testConnectionService = testConnectionService;
  }

  public TpccWarehouseVerifyResponse handle(TpccWarehouseVerifyRequest request, boolean runInProgress) {
    long startedAt = System.currentTimeMillis();
    TpccWarehouseVerifyResponse response = new TpccWarehouseVerifyResponse();
    response.setRunInProgress(runInProgress);
    response.setCheckedAt(OffsetDateTime.now().toString());
    try {
      String dbType = normalizeDbType(request.getDbType());
      if (!SUPPORTED_DB_TYPES.contains(dbType)) {
        throw new IllegalArgumentException("暂不支持该数据库类型的仓数检查：" + request.getDbType());
      }
      response.setDbType(dbType);

      Integer configured = parseWarehouseCount(request.getParams());
      if (configured == null) {
        throw new IllegalArgumentException("配置仓数无效：请先在表单里填写正整数仓数（当前是 \""
            + (request.getParams() == null ? "" : StringUtils.defaultString(request.getParams().get("warehouses")))
            + "\"）。这里不会替你按默认值 1 去比对，那样只会报出一个假的「不一致」");
      }
      response.setConfiguredWarehouses(configured);

      try (Connection connection = testConnectionService.openConnection(toConnectionRequest(request, dbType))) {
        TableCount warehouses = count(connection, TPCCConstants.TABLENAME_WAREHOUSE);
        TableCount districts = warehouses.present()
            ? count(connection, TPCCConstants.TABLENAME_DISTRICT)
            : TableCount.missing();

        response.setWarehouseTablePresent(warehouses.present());
        response.setDistrictTablePresent(districts.present());
        response.setActualWarehouses(warehouses.present() ? warehouses.rows() : 0);
        response.setActualDistricts(districts.present() ? districts.rows() : 0);
        response.setExpectedDistricts(
            warehouses.present() ? TpccDataVerifyEvaluator.expectedDistricts(warehouses.rows()) : 0);

        Evaluation evaluation = TpccDataVerifyEvaluator.evaluate(
            configured, warehouses.present(), warehouses.rows(), districts.present(), districts.rows());
        response.setConclusion(evaluation.conclusion());
        response.setMessage(evaluation.message() + snapshotNote(runInProgress));
        response.setSuccess(true);
        LOG.info("TPCC 仓数检查: dbType={}, configured={}, actualWh={}, districts={}(期望 {}), conclusion={}",
            dbType, configured, response.getActualWarehouses(), response.getActualDistricts(),
            response.getExpectedDistricts(), evaluation.conclusion());
      }
    } catch (IllegalArgumentException e) {
      response.setSuccess(false);
      response.setConclusion(TpccDataVerifyEvaluator.UNKNOWN);
      response.setMessage(e.getMessage());
      LOG.warn("TPCC 仓数检查被拒绝: dbType={}, 原因: {}", request.getDbType(), e.getMessage());
    } catch (SQLException e) {
      response.setSuccess(false);
      response.setConclusion(TpccDataVerifyEvaluator.UNKNOWN);
      response.setMessage(translateSqlError(e));
      LOG.warn("TPCC 仓数检查 SQL 失败: dbType={}, state={}, code={}, 信息={}",
          request.getDbType(), e.getSQLState(), e.getErrorCode(), e.getMessage());
    } catch (Exception e) {
      response.setSuccess(false);
      response.setConclusion(TpccDataVerifyEvaluator.UNKNOWN);
      response.setMessage("无法判定：" + StringUtils.defaultString(e.getMessage(), e.getClass().getName()));
      LOG.error("TPCC 仓数检查失败: dbType={}", request.getDbType(), e);
    }
    response.setElapsedMs(System.currentTimeMillis() - startedAt);
    return response;
  }

  /** 表名只来自 TPCCConstants 的常量，不接受任何外部输入，没有注入面。 */
  private static TableCount count(Connection connection, String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      return new TableCount(true, resultSet.next() ? resultSet.getInt(1) : 0);
    } catch (SQLException e) {
      if (TpccDataVerifyEvaluator.isTableMissing(e, table)) {
        return TableCount.missing();
      }
      throw e; // 权限 / 超时 / 其它 → 交给上层报「无法判定」
    }
  }

  /** 失败原因要说清是"看不到"还是"连不上"，别让用户以为是"库是空的"。 */
  private static String translateSqlError(SQLException e) {
    if (TpccDataVerifyEvaluator.isPermissionDenied(e)) {
      return "无法判定：当前数据库账号没有读取 "
          + TPCCConstants.TABLENAME_WAREHOUSE + "/" + TPCCConstants.TABLENAME_DISTRICT
          + " 的权限，换有权限的账号再试：" + e.getMessage();
    }
    String message = StringUtils.defaultString(e.getMessage()).toLowerCase(Locale.ROOT);
    if (message.contains("timed out") || message.contains("timeout") || message.contains("socket closed")
        || message.contains("connection") || message.contains("link failure") || message.contains("refused")) {
      return "无法判定：连接数据库失败或读取超时，请确认库可达后重试（库正在压测时计数也可能超时）：" + e.getMessage();
    }
    return "无法判定：查询失败：" + e.getMessage();
  }

  private static String snapshotNote(boolean runInProgress) {
    return runInProgress
        ? "（这是点击瞬间的快照：检测到当前有压测在运行，数字可能仍在变化）"
        : "（这是点击瞬间的快照：若此刻正在建仓或压测，结果可能已经过时）";
  }

  /**
   * 配置仓数：只认正整数。
   *
   * <p>与 {@code StartByFormService.parseWarehouseCount}（非法值静默退化成 1）<b>刻意不同</b> ——
   * 检查场景里"没填/填错"必须说出来，否则会拿 1 去比对、报一个假的"不一致"。
   */
  static Integer parseWarehouseCount(Map<String, String> params) {
    if (params == null) {
      return null;
    }
    String text = StringUtils.trimToNull(params.get("warehouses"));
    if (text == null) {
      return null;
    }
    try {
      int value = Integer.parseInt(text);
      return value > 0 ? value : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  /** 与 {@code DbParamService.normalizeAlias} 同语义：postgre 是 postgresql 的别名，连接测试的 switch 只认后者。 */
  private static String normalizeDbType(String dbType) {
    String value = StringUtils.trimToEmpty(dbType);
    return "postgre".equalsIgnoreCase(value) ? "postgresql" : value;
  }

  /**
   * 与 {@code DbParamService.toConnectionRequest} 同源，但 {@code distributed} 也要拷 ——
   * 那边只拷了 standalone，OceanBase 的分布式部署照抄会连错目标。
   */
  private static TestConnectionRequest toConnectionRequest(TpccWarehouseVerifyRequest request, String dbType) {
    TestConnectionRequest connection = new TestConnectionRequest();
    connection.setEnvInfo(request.getEnvInfo());
    connection.setDbType(dbType); // 必须传归一化后的类型
    connection.setDeploymentType(request.getDeploymentType());

    TestConnectionRequest.StandaloneDatabase standalone = new TestConnectionRequest.StandaloneDatabase();
    if (request.getStandalone() != null) {
      standalone.setIp(request.getStandalone().getIp());
      standalone.setPort(request.getStandalone().getPort());
      standalone.setUsername(request.getStandalone().getUsername());
      standalone.setPassword(request.getStandalone().getPassword());
    }
    connection.setStandalone(standalone);

    TestConnectionRequest.DistributedDatabase distributed = new TestConnectionRequest.DistributedDatabase();
    if (request.getDistributed() != null) {
      distributed.setIp(request.getDistributed().getIp());
      distributed.setPort(request.getDistributed().getPort());
      distributed.setUsername(request.getDistributed().getUsername());
      distributed.setPassword(request.getDistributed().getPassword());
    }
    connection.setDistributed(distributed);
    return connection;
  }

  private record TableCount(boolean present, int rows) {
    static TableCount missing() {
      return new TableCount(false, 0);
    }
  }
}