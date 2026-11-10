package com.resbench.manager.controller;

import static com.resbench.manager.constant.URL.Suite;

import com.resbench.manager.model.dto.FaultEventInfoResponse;
import com.resbench.manager.model.dto.DbParamRequest;
import com.resbench.manager.model.dto.DbParamResponse;
import com.resbench.manager.model.dto.FaultCatalogItemResponse;
import com.resbench.manager.model.dto.FaultVerifyRequest;
import com.resbench.manager.model.dto.FaultVerifyResponse;
import com.resbench.manager.model.dto.FaultInfoResponse;
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
import com.resbench.manager.global.GlobalResponse;
import com.resbench.manager.model.dto.TestSuitRequest;
import com.resbench.manager.model.dto.TestSuiteHistoryResponse;
import com.resbench.manager.model.dto.FileFaultTargetRequest;
import com.resbench.manager.model.dto.FileFaultTargetResponse;
import com.resbench.manager.model.dto.TpccWarehouseVerifyRequest;
import com.resbench.manager.model.dto.TpccWarehouseVerifyResponse;
import com.resbench.manager.model.external.benchmark.BenchMarkStatusResponse;
import com.resbench.manager.service.TestSuiteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController()
public class TestSuiteController {

  private final TestSuiteService testSuiteService;

  public TestSuiteController(TestSuiteService testSuiteService) {
    this.testSuiteService = testSuiteService;
  }

  @Operation(summary = "获取后端部署环境列表", description = "获取后端部署环境列表")
  @RequestMapping(path = Suite.ENV, method = RequestMethod.GET)
  public GlobalResponse<Set<String>> getEnvInfo() {
    return testSuiteService.getEnvInfo();
  }

  @Operation(summary = "启动测试套名称", description = "根据部署环境以及测试套名称启动测试套")
  @RequestMapping(path = Suite.START, method = RequestMethod.POST)
  @ApiResponses( {
    @ApiResponse(responseCode = "200", description = "成功"),
    @ApiResponse(responseCode = "400", description = "无法启动")
  })
  public GlobalResponse<String> startTestSuite(@RequestBody() @Valid TestSuitRequest testSuitRequest) {
    return testSuiteService.startTestSuite(testSuitRequest.getEnvInfo(), testSuitRequest.getArgs());
  }

  @Operation(summary = "根据表单启动测试", description = "管理端接收表单并转发给 benchmark，由 benchmark 生成 XML 并启动测试")
  @RequestMapping(path = Suite.START_BY_FORM, method = RequestMethod.POST)
  public GlobalResponse<StartByFormResponse> startByForm(@RequestBody @Valid StartByFormRequest request) {
    return testSuiteService.startByForm(request);
  }

  @Operation(summary = "校验故障编排合理性",
      description = "基于 Neo4j 知识图谱检查同一组合中同时注入的故障之间是否存在互斥/重复关系")
  @RequestMapping(path = Suite.ORCHESTRATION_CHECK, method = RequestMethod.POST)
  public GlobalResponse<OrchestrationCheckResult> checkOrchestration(
      @RequestBody OrchestrationCheckRequest request) {
    return testSuiteService.checkOrchestration(request);
  }

  @Operation(summary = "测试数据库连接", description = "根据当前表单配置测试数据库连通性")
  @RequestMapping(path = Suite.CONNECTION_TEST, method = RequestMethod.POST)
  public GlobalResponse<TestConnectionResponse> testConnection(@RequestBody @Valid TestConnectionRequest request) {
    return testSuiteService.testConnection(request);
  }

  @Operation(summary = "应用数据库参数", description = "修改单机数据库的缓冲池大小与最大连接数上限；需要重启的库必须带重启命令")
  @RequestMapping(path = Suite.DB_PARAM, method = RequestMethod.POST)
  public GlobalResponse<DbParamResponse> applyDbParams(@RequestBody DbParamRequest request) {
    return testSuiteService.applyDbParams(request);
  }

  @Operation(summary = "检查 TPCC 实际仓数", description = "连上目标库数出实际仓数，与表单里配置的仓数比对；不一致时把实际仓数告诉用户")
  @RequestMapping(path = Suite.TPCC_WAREHOUSE_VERIFY, method = RequestMethod.POST)
  public GlobalResponse<TpccWarehouseVerifyResponse> verifyTpccWarehouses(
      @RequestBody TpccWarehouseVerifyRequest request) {
    return testSuiteService.verifyTpccWarehouses(request);
  }

  @Operation(summary = "列出 file 故障可用的目标文件",
      description = "连上目标库查它的文件结构，按「目录 → 每张表（表下挂堆文件和各个索引）」分组返回，"
          + "每项标明是目录还是文件、属于哪张表/哪个索引，供参数框旁的「选择文件」按钮展示并挑选")
  @RequestMapping(path = Suite.FILE_FAULT_TARGETS, method = RequestMethod.POST)
  public GlobalResponse<FileFaultTargetResponse> fileFaultTargets(
      @RequestBody FileFaultTargetRequest request) {
    return testSuiteService.fileFaultTargets(request);
  }

  @Operation(summary = "停止测试套名称", description = "根据部署环境以及测试套名称停止测试套")
  @RequestMapping(path = Suite.STOP, method = RequestMethod.POST)
  @ApiResponses( {
    @ApiResponse(responseCode = "200", description = "成功"),
    @ApiResponse(responseCode = "400", description = "无法停止")
  })
  public GlobalResponse<String> stopTestSuite(@RequestBody() @Valid TestSuitRequest testSuitRequest) {
    return testSuiteService.stopTestSuite(testSuitRequest.getEnvInfo(), testSuitRequest.getArgs());
  }

  @Operation(summary = "获取测试情况", description = "根据部署环境与historyName 获取测试情况, historyName为空默认获取最新测试情况")
  @RequestMapping(path = Suite.STATUS, method = RequestMethod.GET)
  public GlobalResponse<BenchMarkStatusResponse> getTestSuiteStatus(@RequestParam String envInfo, @RequestParam(required = false) String historyName) {
    return testSuiteService.getTestSuiteStatus(envInfo, historyName);
  }

  @Operation(summary = "获取测试数据", description = "根据部署环境与historyName 获取聚合数据, historyName为空默认获取最新测试情况")
  @RequestMapping(path = Suite.RESULT, method = RequestMethod.GET)
  public GlobalResponse<SuiteMeterResponse> getTestSuiteResult(@RequestParam String envInfo,
                                                               @RequestParam String testSuiteName,
                                                               @RequestParam(required = false) String historyName) {
    return testSuiteService.getTestSuiteResult(envInfo, testSuiteName, historyName);
  }


  @Operation(summary = "获取测试过程中数据", description = "根据部署环境与historyName 获取测试过程中数据, historyName为空默认获取最新测试情况")
  @RequestMapping(path = Suite.MONITOR, method = RequestMethod.GET)
  public GlobalResponse<List<MonitorResponse>> getTestSuiteMonitor(@RequestParam String envInfo,
                                                                   @RequestParam(required = false) String historyName,
                                                                   @RequestParam(required = false) String startTimestamp,
                                                                   @RequestParam(required = false) String endTimestamp) {
    return testSuiteService.getTestSuiteMonitor(envInfo, historyName, startTimestamp, endTimestamp);
  }

  @Operation(summary = "获取测试过程中数据", description = "根据部署环境与historyName 获取测试过程中数据, historyName为空默认获取最新测试情况")
  @RequestMapping(path = Suite.MONITOR_PAGE, method = RequestMethod.GET)
  public GlobalResponse<PageResultResponse<MonitorResponse>> getTestSuiteMonitorByPage(@RequestParam String envInfo,
                                                                                       @RequestParam(required = false) String historyName,
                                                                                       Integer pageSize,
                                                                                       Integer pageNum) {
    return testSuiteService.getTestSuiteMonitorByPage(envInfo, historyName, pageSize, pageNum);
  }

  @Operation(summary = "检索历史测试（归档检索）", description = "按关键字/时间/数据库/负载/编排/故障/状态/完整性筛选历史运行，供前端检索与归档使用")
  @RequestMapping(path = Suite.HISTORY_QUERY, method = RequestMethod.GET)
  public GlobalResponse<HistoryQueryResponse> queryHistory(@RequestParam Map<String, String> params) {
    return testSuiteService.queryHistory(params);
  }

  @Operation(summary = "归档：打标签/写备注", description = "给一轮历史测试打标签、写备注（旧运行无元信息时自动补建）")
  @RequestMapping(path = Suite.HISTORY_LABEL, method = RequestMethod.POST)
  public GlobalResponse<HistoryLabelResponse> labelHistory(@RequestBody HistoryLabelRequest request) {
    return testSuiteService.labelHistory(request);
  }

  @Operation(summary = "归档：导出 zip", description = "把一轮历史测试的产物导出为 zip；默认排除 raw.csv，可用 include 指定类别")
  @RequestMapping(path = Suite.HISTORY_EXPORT, method = RequestMethod.GET)
  public ResponseEntity<byte[]> exportHistory(@RequestParam String envInfo,
                                              @RequestParam String historyName,
                                              @RequestParam(required = false) String include) {
    return testSuiteService.exportHistory(envInfo, historyName, include);
  }

  @Operation(summary = "获取测试历史数据名称列表", description = "根据部署环境获取测试历史数据名称列表")
  @RequestMapping(path = Suite.HISTORY_LIST, method = RequestMethod.GET)
  public GlobalResponse<TestSuiteHistoryResponse> getHistoryList(@RequestParam String envInfo,
                                                                 @RequestParam(required = false) String dbType,
                                                                 @RequestParam(required = false) String testSuiteName) {
    return testSuiteService.getHistoryList(envInfo, dbType,testSuiteName);
  }

  @Operation(summary = "获取测试事件", description = "根据部署环境获取测试事件")
  @RequestMapping(path = Suite.EVENT, method = RequestMethod.GET)
  public GlobalResponse<List<FaultEventInfoResponse>> getEvent(@RequestParam String envInfo,
                                                               @RequestParam String testSuiteName,
                                                               @RequestParam(name = "historyName", required = false) String historyName) {
    return testSuiteService.getEvent(envInfo, testSuiteName, historyName);
  }

  @Operation(summary = "获取测试历史数据名称列表", description = "根据部署环境获取测试历史数据名称列表")
  @RequestMapping(path = Suite.INJECT_INFO, method = RequestMethod.GET)
  public GlobalResponse<List<FaultInfoResponse>> getInjectInfo(@RequestParam String envInfo,
                                                               @RequestParam(name = "historyName", required = false) String historyName) {
    return testSuiteService.getInjectInfo(envInfo, historyName);
  }

  @Operation(summary = "获取所有测试数据库类型列表", description = "获取环境中所有数据库类型列表")
  @RequestMapping(path = Suite.DB_TYPE_ALL, method = RequestMethod.GET)
  public GlobalResponse<Set<String>> getDBTypeAll() {
    return testSuiteService.getAllDBType();
  }

  @Operation(summary = "获取测试数据库类型列表", description = "根据部署环境获取数据库类型列表")
  @RequestMapping(path = Suite.DB_TYPE, method = RequestMethod.GET)
  public GlobalResponse<List<String>> getDBType(@RequestParam String envInfo,
                                                @RequestParam(required = false) String deploymentType) {
    return testSuiteService.getDBType(envInfo, deploymentType);
  }

  @Operation(summary = "获取工作负载列表", description = "根据部署环境获取工作负载列表")
  @RequestMapping(path = Suite.WORKLOAD, method = RequestMethod.GET)
  public GlobalResponse<Set<String>> getWorkLoad(@RequestParam String envInfo, @RequestParam String dbType) {
    return testSuiteService.getWorkLoad(envInfo,dbType);
  }

  @Operation(summary = "获取所有工作负载列表", description = "获取环境中所有工作负载列表")
  @RequestMapping(path = Suite.WORKLOADS_ALL, method = RequestMethod.GET)
  public GlobalResponse<Set<String>> getWorkLoadAll() {
    return testSuiteService.getAllWorkLoad();
  }

  @Operation(summary = "获取故障目录", description = "获取 yaml 驱动的故障目录（含 check 支持情况）")
  @RequestMapping(path = Suite.FAULT_CATALOG, method = RequestMethod.GET)
  public GlobalResponse<List<FaultCatalogItemResponse>> getFaultCatalog(@RequestParam String envInfo) {
    return testSuiteService.getFaultCatalog(envInfo);
  }

  @Operation(summary = "手动补验故障效果", description = "即时执行一次指定故障的 check 命令并返回结果")
  @RequestMapping(path = Suite.FAULT_VERIFY, method = RequestMethod.POST)
  public GlobalResponse<FaultVerifyResponse> verifyFault(@RequestBody FaultVerifyRequest request) {
    return testSuiteService.verifyFault(request.getEnvInfo(), request);
  }
}
