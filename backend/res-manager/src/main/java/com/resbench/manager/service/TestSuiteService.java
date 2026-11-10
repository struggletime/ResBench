package com.resbench.manager.service;

import com.resbench.manager.global.GlobalResponse;
import com.resbench.manager.model.dto.DbParamRequest;
import com.resbench.manager.model.dto.DbParamResponse;
import com.resbench.manager.model.dto.FileFaultTargetRequest;
import com.resbench.manager.model.dto.FileFaultTargetResponse;
import com.resbench.manager.model.dto.TpccWarehouseVerifyRequest;
import com.resbench.manager.model.dto.TpccWarehouseVerifyResponse;
import com.resbench.manager.model.dto.FaultCatalogItemResponse;
import com.resbench.manager.model.dto.FaultEventInfoResponse;
import com.resbench.manager.model.dto.FaultInfoResponse;
import com.resbench.manager.model.dto.FaultVerifyRequest;
import com.resbench.manager.model.dto.FaultVerifyResponse;
import com.resbench.manager.model.dto.HistoryLabelRequest;
import com.resbench.manager.model.dto.HistoryLabelResponse;
import com.resbench.manager.model.dto.HistoryQueryResponse;
import com.resbench.manager.model.dto.MonitorResponse;
import com.resbench.manager.model.dto.OrchestrationCheckRequest;
import com.resbench.manager.model.dto.OrchestrationCheckResult;
import com.resbench.manager.model.dto.PageResultResponse;
import com.resbench.manager.model.dto.SuiteMeterResponse;
import com.resbench.manager.model.dto.StartByFormRequest;
import com.resbench.manager.model.dto.StartByFormResponse;
import com.resbench.manager.model.dto.TestConnectionRequest;
import com.resbench.manager.model.dto.TestConnectionResponse;
import com.resbench.manager.model.dto.TestSuiteHistoryResponse;
import com.resbench.manager.model.external.benchmark.BenchMarkStatusResponse;

import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface TestSuiteService {
  GlobalResponse<String> startTestSuite(String envInfo, List<String> args);

  GlobalResponse<StartByFormResponse> startByForm(StartByFormRequest request);

  GlobalResponse<TestConnectionResponse> testConnection(TestConnectionRequest request);

  GlobalResponse<DbParamResponse> applyDbParams(DbParamRequest request);

  /** 检查目标库实际的 TPCC 仓数，与请求里的配置仓数比对（透传引擎 /web/bench/tpcc/warehouse/verify）。 */
  GlobalResponse<TpccWarehouseVerifyResponse> verifyTpccWarehouses(TpccWarehouseVerifyRequest request);

  /**
   * 列出 file 类故障可以打的目标文件：按「目录 → 每张表（表下挂它的堆文件和它的各个索引）」分组，
   * 每项标明是目录还是文件、属于哪张表/哪个索引，供参数框旁的「选择文件」按钮展示并挑选。
   */
  GlobalResponse<FileFaultTargetResponse> fileFaultTargets(FileFaultTargetRequest request);

  GlobalResponse<String> stopTestSuite(String envInfo, List<String> args);

  GlobalResponse<BenchMarkStatusResponse> getTestSuiteStatus(String envInfo, String historyName);

  GlobalResponse<SuiteMeterResponse> getTestSuiteResult(String envInfo, String testSuiteName, String historyName);

  GlobalResponse<List<MonitorResponse>> getTestSuiteMonitor(String envInfo, String historyName, String startTimestamp, String endTimestamp);

  GlobalResponse<PageResultResponse<MonitorResponse>> getTestSuiteMonitorByPage(String envInfo, String historyName, Integer pageSize, Integer pageNum);

  GlobalResponse<TestSuiteHistoryResponse> getHistoryList(String envInfo, String dbType, String testSuiteName);

  GlobalResponse<HistoryQueryResponse> queryHistory(Map<String, String> params);

  GlobalResponse<HistoryLabelResponse> labelHistory(HistoryLabelRequest request);

  ResponseEntity<byte[]> exportHistory(String envInfo, String historyName, String include);

  GlobalResponse<List<FaultEventInfoResponse>> getEvent(String envInfo, String testSuiteName, String historyName);

  GlobalResponse<List<FaultInfoResponse>> getInjectInfo(String envInfo, String historyName);

  GlobalResponse<List<FaultCatalogItemResponse>> getFaultCatalog(String envInfo);

  GlobalResponse<FaultVerifyResponse> verifyFault(String envInfo, FaultVerifyRequest request);

  GlobalResponse<OrchestrationCheckResult> checkOrchestration(OrchestrationCheckRequest request);

  GlobalResponse<Set<String>> getEnvInfo();

  GlobalResponse<List<String>> getDBType(String envInfo, String deploymentType);

  GlobalResponse<Set<String>> getAllDBType();

  GlobalResponse<Set<String>> getWorkLoad(String envInfo,String dbType);

  GlobalResponse<Set<String>> getAllWorkLoad();
}
