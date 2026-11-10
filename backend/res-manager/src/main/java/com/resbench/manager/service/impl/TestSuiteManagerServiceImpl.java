package com.resbench.manager.service.impl;

import com.resbench.manager.config.properties.BenchMarkAddr;
import com.resbench.manager.global.GlobalResponse;
import com.resbench.manager.global.RetCode;
import com.resbench.manager.httpclient.HttpClient;
import com.resbench.manager.model.dto.FaultCaseInfo;
import com.resbench.manager.service.TestSuiteManagerService;
import com.resbench.manager.utils.JsonUtils;
import com.resbench.manager.utils.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TestSuiteManagerServiceImpl implements TestSuiteManagerService {

  private final BenchMarkAddr benchMarkAddr;

  private final HttpClient HttpClient;

  public TestSuiteManagerServiceImpl(BenchMarkAddr benchMarkAddr, HttpClient httpClient) {
    this.benchMarkAddr = benchMarkAddr;
    HttpClient = httpClient;
  }

  @Override
  public GlobalResponse<Map<String, List<FaultCaseInfo>>> getFaultCaseInfo() {
    String anyone = benchMarkAddr.getAnyone();
    ResponseEntity<String> result = HttpClient.get(anyone, "/web/testSuite/fault", null, null, String.class);
    if (result.getStatusCode() != HttpStatus.OK) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    String body = result.getBody();
    if (StringUtils.isEmpty(body)) {
      return GlobalResponse.error(RetCode.ERROR);
    }
    try {
      return GlobalResponse.success(JsonUtils.fromJson(body, new TypeReference<>() {}));
    } catch (RuntimeException e) {
      return GlobalResponse.error(RetCode.ERROR);
    }
  }
}
