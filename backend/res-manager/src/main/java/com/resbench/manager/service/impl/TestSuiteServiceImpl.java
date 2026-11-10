package com.resbench.manager.service.impl;

import com.resbench.manager.config.properties.BenchMarkAddr;
import com.resbench.manager.global.GlobalResponse;
import com.resbench.manager.global.RetCode;
import com.resbench.manager.httpclient.HttpClient;
import com.resbench.manager.model.dto.FaultCatalogItemResponse;
import com.resbench.manager.model.dto.DbParamRequest;
import com.resbench.manager.model.dto.DbParamResponse;
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
import com.resbench.manager.model.dto.FileFaultTargetRequest;
import com.resbench.manager.model.dto.FileFaultTargetResponse;
import com.resbench.manager.model.dto.TpccWarehouseVerifyRequest;
import com.resbench.manager.model.dto.TpccWarehouseVerifyResponse;
import com.resbench.manager.model.external.benchmark.BenchMarkStatusResponse;
import com.resbench.manager.service.TestSuiteService;
import com.resbench.manager.utils.JsonUtils;
import com.resbench.manager.utils.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

@Slf4j
@Service
public class TestSuiteServiceImpl implements TestSuiteService {
  private static final Map<String, List<String>> DEPLOYMENT_DB_TYPE_MAPPING = Map.of(
      "standalone", List.of("mysql", "postgresql", "opengauss", "gaussdb"),
      "primary-standby", List.of("opengauss"),
      "distributed", List.of("oceanbase", "gaussdb")
  );
  private static final Set<String> DEFAULT_WORKLOADS = new LinkedHashSet<>(List.of("benchmarkSQL", "smallbank"));

  private final BenchMarkAddr benchMarkAddr;

  private final HttpClient HttpClient;

  public TestSuiteServiceImpl(BenchMarkAddr benchMarkAddr, HttpClient httpClient) {
    this.benchMarkAddr = benchMarkAddr;
    HttpClient = httpClient;
  }

  @Override
  public GlobalResponse<String> startTestSuite(String envInfo, List<String> args) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    ResponseEntity<String> result = HttpClient.post(addrOptional.get(), "/web/bench", args, null, String.class);
    if (result.getStatusCode() == HttpStatus.OK) {
      return GlobalResponse.success(result.getBody());
    } else {
      return GlobalResponse.error(400, result.getBody());
    }
  }

  @Override
  public GlobalResponse<StartByFormResponse> startByForm(StartByFormRequest request) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(request.getEnvInfo());
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    ResponseEntity<StartByFormResponse> result =
        HttpClient.post(addrOptional.get(), "/web/bench/startByForm", request, null, StartByFormResponse.class);
    if (result.getStatusCode() == HttpStatus.OK && result.getBody() != null) {
      return GlobalResponse.success(result.getBody());
    }
    return GlobalResponse.error(400, "启动失败");
  }

  @Override
  public GlobalResponse<TestConnectionResponse> testConnection(TestConnectionRequest request) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(request.getEnvInfo());
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    ResponseEntity<TestConnectionResponse> result =
        HttpClient.post(addrOptional.get(), "/web/bench/connection/test", request, null, TestConnectionResponse.class);
    if (result.getStatusCode() == HttpStatus.OK && result.getBody() != null) {
      return GlobalResponse.success(result.getBody());
    }
    return GlobalResponse.error(400, "连接测试失败");
  }

  @Override
  public GlobalResponse<DbParamResponse> applyDbParams(DbParamRequest request) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(request.getEnvInfo());
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    // 与连接测试同构：引擎那边失败也返回 200 + body.success=false，这里照原样透传给前端。
    // 但引擎整个不可达时 HttpClient 会抛 RestClientException，默认会被全局异常处理器变成
    // 500 + "系统异常：I/O error on POST..."（英文），用户看不懂，所以在这里兜一层。
    ResponseEntity<DbParamResponse> result;
    try {
      result = HttpClient.post(addrOptional.get(), "/web/bench/db/params", request, null, DbParamResponse.class);
    } catch (Exception e) {
      log.error("调用引擎应用数据库参数失败: addr={}, envInfo={}", addrOptional.get(), request.getEnvInfo(), e);
      return GlobalResponse.error(400, "无法连接压测引擎（" + addrOptional.get() + "），请确认引擎已启动：" + e.getMessage());
    }
    if (result.getStatusCode() == HttpStatus.OK && result.getBody() != null) {
      return GlobalResponse.success(result.getBody());
    }
    return GlobalResponse.error(400, "数据库参数应用失败");
  }

  @Override
  public GlobalResponse<TpccWarehouseVerifyResponse> verifyTpccWarehouses(TpccWarehouseVerifyRequest request) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(request.getEnvInfo());
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    // 与连接测试同构：引擎那边失败也返回 200 + body.success=false，这里照原样透传给前端。
    // 引擎整个不可达时 HttpClient 会抛 RestClientException，默认会变成 500 + 英文的
    // "系统异常：I/O error on POST..."，用户看不懂，所以兜一层。
    ResponseEntity<TpccWarehouseVerifyResponse> result;
    try {
      result = HttpClient.post(addrOptional.get(), "/web/bench/tpcc/warehouse/verify", request, null,
          TpccWarehouseVerifyResponse.class);
    } catch (Exception e) {
      log.error("调用引擎检查 TPCC 仓数失败: addr={}, envInfo={}", addrOptional.get(), request.getEnvInfo(), e);
      return GlobalResponse.error(400, "无法连接压测引擎（" + addrOptional.get() + "），请确认引擎已启动：" + e.getMessage());
    }
    if (result.getStatusCode() == HttpStatus.OK && result.getBody() != null) {
      return GlobalResponse.success(result.getBody());
    }
    return GlobalResponse.error(400, "检查 TPCC 仓数失败");
  }

  @Override
  public GlobalResponse<FileFaultTargetResponse> fileFaultTargets(FileFaultTargetRequest request) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(request.getEnvInfo());
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    // 与连接测试同构：引擎那边失败也返回 200 + body.success=false，这里照原样透传给前端。
    ResponseEntity<FileFaultTargetResponse> result;
    try {
      result = HttpClient.post(addrOptional.get(), "/web/bench/faults/file-targets", request, null,
          FileFaultTargetResponse.class);
    } catch (Exception e) {
      log.error("调用引擎列出 file 故障目标失败: addr={}, envInfo={}", addrOptional.get(), request.getEnvInfo(), e);
      return GlobalResponse.error(400, "无法连接压测引擎（" + addrOptional.get() + "），请确认引擎已启动：" + e.getMessage());
    }
    if (result.getStatusCode() == HttpStatus.OK && result.getBody() != null) {
      return GlobalResponse.success(result.getBody());
    }
    return GlobalResponse.error(400, "列出 file 故障目标失败");
  }

  @Override
  public GlobalResponse<String> stopTestSuite(String envInfo, List<String> args) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    ResponseEntity<String> result = HttpClient.post(addrOptional.get(), "/web/bench/stop", args, null, String.class);
    if (result.getStatusCode() == HttpStatus.OK) {
      return GlobalResponse.success(result.getBody());
    } else {
      return GlobalResponse.error(400, result.getBody());
    }
  }

  @Override
  public GlobalResponse<BenchMarkStatusResponse> getTestSuiteStatus(String envInfo, String historyName) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> queryMap = new HashMap<>();
    if (historyName != null && !historyName.isEmpty()) {
      queryMap.put("historyName", historyName);
    }
    ResponseEntity<BenchMarkStatusResponse> entity = HttpClient.get(addrOptional.get(), "/web/bench/status", queryMap, null, BenchMarkStatusResponse.class);
    return GlobalResponse.success(entity.getBody());
  }

  @Override
  public GlobalResponse<SuiteMeterResponse> getTestSuiteResult(String dbType, String testSuiteName, String historyName) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(dbType);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> queryMap = new HashMap<>();
    queryMap.put("testSuitName", testSuiteName);
    if (historyName != null && !historyName.isEmpty()) {
      queryMap.put("historyName", historyName);
    }
    ResponseEntity<SuiteMeterResponse> entity = HttpClient.get(addrOptional.get(), "/web/bench/newest_result", queryMap, null, SuiteMeterResponse.class);
    return GlobalResponse.success(entity.getBody());
  }

  @Override
  public GlobalResponse<List<MonitorResponse>> getTestSuiteMonitor(String envInfo, String historyName, String startTimestamp, String endTimestamp) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> queryMap = new HashMap<>();
    if (!StringUtils.isEmpty(historyName)) {
      queryMap.put("historyName", historyName);
    }
    if (!StringUtils.isEmpty(startTimestamp)) {
      queryMap.put("startTimestamp", startTimestamp);
    }
    if (!StringUtils.isEmpty(endTimestamp)) {
      queryMap.put("endTimestamp", endTimestamp);
    }
    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/monitor", queryMap, null, String.class);

    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJsonToList(body, MonitorResponse.class));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public GlobalResponse<PageResultResponse<MonitorResponse>> getTestSuiteMonitorByPage(String envInfo, String historyName, Integer pageSize, Integer pageNum) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> queryMap = new HashMap<>();
    if (!StringUtils.isEmpty(historyName)) {
      queryMap.put("historyName", historyName);
    }
    queryMap.put("pageSize", String.valueOf(pageSize));
    queryMap.put("pageNum", String.valueOf(pageNum));
    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/monitorByPage", queryMap, null, String.class);

    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJson(body, new TypeReference<>() {}));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public GlobalResponse<TestSuiteHistoryResponse> getHistoryList(String envInfo, String dbType, String testSuiteName) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> params = new HashMap<>();
    if (!StringUtils.isEmpty(testSuiteName)) {
      params.put("testSuiteName", testSuiteName);
    }

    if (!StringUtils.isEmpty(dbType)) {
      params.put("dbType",dbType);
    }

    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/history/list", params, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJson(body, TestSuiteHistoryResponse.class));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public GlobalResponse<HistoryQueryResponse> queryHistory(Map<String, String> params) {
    Map<String, String> query = params == null ? new HashMap<>() : new HashMap<>(params);
    String envInfo = query.remove("envInfo");
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> forwarded = new HashMap<>();
    for (Map.Entry<String, String> entry : query.entrySet()) {
      if (!StringUtils.isEmpty(entry.getValue())) {
        forwarded.put(entry.getKey(), entry.getValue());
      }
    }
    ResponseEntity<String> entity =
        HttpClient.get(addrOptional.get(), "/web/bench/history/query", forwarded, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJson(body, HistoryQueryResponse.class));
    } catch (RuntimeException e) {
      log.error("history query response convert error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public GlobalResponse<HistoryLabelResponse> labelHistory(HistoryLabelRequest request) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(request.getEnvInfo());
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    // 直接传 DTO（不能传 Map：HttpClient 会把 Map 当成表单编码，引擎侧 @RequestBody 需要 JSON）
    ResponseEntity<String> entity =
        HttpClient.post(addrOptional.get(), "/web/bench/history/label", request, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJson(body, HistoryLabelResponse.class));
    } catch (RuntimeException e) {
      log.error("history label response convert error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public ResponseEntity<byte[]> exportHistory(String envInfo, String historyName, String include) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    HashMap<String, String> params = new HashMap<>();
    params.put("historyName", historyName);
    if (!StringUtils.isEmpty(include)) {
      params.put("include", include);
    }
    ResponseEntity<byte[]> entity;
    try {
      entity = HttpClient.get(addrOptional.get(), "/web/bench/history/export", params, null, byte[].class);
    } catch (RestClientResponseException e) {
      log.warn("archive export failed: status={}, msg={}", e.getStatusCode(), e.getMessage());
      return ResponseEntity.status(e.getStatusCode()).build();
    }
    if (entity.getStatusCode() != HttpStatus.OK || entity.getBody() == null) {
      return ResponseEntity.status(entity.getStatusCode()).build();
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + historyName + ".zip\"")
        .contentType(MediaType.parseMediaType("application/zip"))
        .body(entity.getBody());
  }

  @Override
  public GlobalResponse<List<FaultEventInfoResponse>> getEvent(String envInfo, String testSuiteName, String historyName) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> queryMap = new HashMap<>();
    if (historyName != null && !historyName.isEmpty()) {
      queryMap.put("historyName", historyName);
    }

    if (!StringUtils.isEmpty(testSuiteName)) {
      queryMap.put("testSuiteName", testSuiteName);
    }
    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/event", queryMap, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJsonToList(body, FaultEventInfoResponse.class));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public GlobalResponse<List<FaultInfoResponse>> getInjectInfo(String envInfo, String historyName) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    HashMap<String, String> queryMap = new HashMap<>();
    if (historyName != null && !historyName.isEmpty()) {
      queryMap.put("historyName", historyName);
    }
    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/inject/info", queryMap, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJsonToList(body, FaultInfoResponse.class));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public GlobalResponse<List<FaultCatalogItemResponse>> getFaultCatalog(String envInfo) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/faults/catalog", null, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJsonToList(body, FaultCatalogItemResponse.class));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  @Override
  public GlobalResponse<FaultVerifyResponse> verifyFault(String envInfo, FaultVerifyRequest request) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    ResponseEntity<FaultVerifyResponse> entity =
        HttpClient.post(addrOptional.get(), "/web/fault/verify", request, null, FaultVerifyResponse.class);
    if (entity.getStatusCode() != HttpStatus.OK || entity.getBody() == null) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    return GlobalResponse.success(entity.getBody());
  }

  @Override
  public GlobalResponse<OrchestrationCheckResult> checkOrchestration(OrchestrationCheckRequest request) {
    if (request == null || StringUtils.isEmpty(request.getEnvInfo())) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(request.getEnvInfo());
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }
    try {
      ResponseEntity<OrchestrationCheckResult> entity = HttpClient.post(
          addrOptional.get(), "/web/fault/orchestration/check", request, null,
          OrchestrationCheckResult.class);
      if (entity.getStatusCode() == HttpStatus.OK && entity.getBody() != null) {
        return GlobalResponse.success(entity.getBody());
      }
      return GlobalResponse.error(400, "编排校验失败");
    } catch (RuntimeException e) {
      log.error("orchestration check failed: {}", e.getMessage(), e);
      return GlobalResponse.error(500, "编排校验失败：" + e.getMessage());
    }
  }

  @Override
  public GlobalResponse<Set<String>> getEnvInfo() {
    return GlobalResponse.success(benchMarkAddr.getEnvInfo());
  }

  @Override
  public GlobalResponse<List<String>> getDBType(String envInfo, String deploymentType) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }

    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/dbtype", null, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      List<String> dbTypes = JsonUtils.fromJsonToList(body, String.class);
      return GlobalResponse.success(filterDBTypesByDeploymentType(dbTypes, deploymentType));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  private List<String> filterDBTypesByDeploymentType(List<String> dbTypes, String deploymentType) {
    if (StringUtils.isEmpty(deploymentType)) {
      return dbTypes;
    }
    List<String> allowedTypes = DEPLOYMENT_DB_TYPE_MAPPING.get(deploymentType);
    if (allowedTypes == null || allowedTypes.isEmpty()) {
      return List.of();
    }
    Set<String> allowedTypeSet = new LinkedHashSet<>(allowedTypes);
    List<String> filteredTypes = new ArrayList<>();
    for (String dbType : dbTypes) {
      if (allowedTypeSet.contains(dbType)) {
        filteredTypes.add(dbType);
      }
    }
    if (!filteredTypes.isEmpty()) {
      return filteredTypes;
    }
    // Some local environments only expose a subset of dbtype directories.
    // Fall back to the deployment-level whitelist so the frontend still gets
    // the correct option set from the backend instead of using client-side defaults.
    return new ArrayList<>(allowedTypes);
  }

  @Override
  public GlobalResponse<Set<String>> getAllDBType() {

    Set<String> dbTypes = new HashSet<>();

    Map<String,String> address = benchMarkAddr.getAddr();

    for (String key : address.keySet()) {
      Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(key);
      if (addrOptional.isEmpty()) {
        continue;
      }
      ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/dbtype", null, null, String.class);
      if (entity.getStatusCode() != HttpStatus.OK) {
        continue;
      }
      String body = entity.getBody();
      if (StringUtils.isEmpty(body)) {
        continue;
      }
      dbTypes.addAll(JsonUtils.fromJsonToList(body,String.class));
    }
    return GlobalResponse.success(dbTypes);
  }

  @Override
  public GlobalResponse<Set<String>> getWorkLoad(String envInfo, String dbType) {
    Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(envInfo);
    if (addrOptional.isEmpty()) {
      return GlobalResponse.error(RetCode.NOT_FOUND_ENVINFO);
    }

    HashMap<String, String> queryMap = new HashMap<>();
    if (dbType != null && !dbType.isEmpty()) {
      queryMap.put("dbtype", dbType);
    }

    ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/workload", queryMap, null, String.class);
    if (entity.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = entity.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      Set<String> workloads = JsonUtils.fromJsonToSet(body, String.class);
      return GlobalResponse.success(normalizeWorkloads(workloads, dbType));
    } catch (RuntimeException e) {
      log.error("response Json String convert to Class is error! err msg: {}", e.getMessage());
      return GlobalResponse.error(RetCode.ERROR);
    }
  }

  private Set<String> normalizeWorkloads(Set<String> workloads, String dbType) {
    Set<String> normalized = new LinkedHashSet<>();
    for (String workload : workloads) {
      if (StringUtils.isEmpty(workload)) {
        continue;
      }
      if ("tpcc".equalsIgnoreCase(workload) || "benchmarksql".equalsIgnoreCase(workload)) {
        normalized.add("benchmarkSQL");
        continue;
      }
      if ("smallbank".equalsIgnoreCase(workload)) {
        normalized.add("smallbank");
      }
    }
    if (!normalized.isEmpty()) {
      return normalized;
    }
    if (!StringUtils.isEmpty(dbType)) {
      return new LinkedHashSet<>(DEFAULT_WORKLOADS);
    }
    return normalized;
  }

  @Override
  public GlobalResponse<Set<String>> getAllWorkLoad() {
    Set<String> workLoads = new HashSet<>();

    Map<String,String> address = benchMarkAddr.getAddr();

    for (String key : address.keySet()) {
      Optional<String> addrOptional = benchMarkAddr.getBenchMarkAddr(key);
      if (addrOptional.isEmpty()) {
        continue;
      }
      ResponseEntity<String> entity = HttpClient.get(addrOptional.get(), "/web/bench/workload", null, null, String.class);
      if (entity.getStatusCode() != HttpStatus.OK) {
        continue;
      }
      String body = entity.getBody();
      if (StringUtils.isEmpty(body)) {
        continue;
      }
      workLoads.addAll(JsonUtils.fromJsonToList(body,String.class));
    }
    return GlobalResponse.success(workLoads);
  }
}
