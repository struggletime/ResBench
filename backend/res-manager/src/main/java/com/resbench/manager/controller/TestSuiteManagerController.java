package com.resbench.manager.controller;

import com.resbench.manager.constant.URL;
import com.resbench.manager.global.GlobalResponse;
import com.resbench.manager.model.dto.FaultCaseInfo;
import com.resbench.manager.service.TestSuiteManagerService;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class TestSuiteManagerController {

  private TestSuiteManagerService testSuiteManagerService;

  public TestSuiteManagerController(TestSuiteManagerService testSuiteManagerService) {
    this.testSuiteManagerService = testSuiteManagerService;
  }

  @RequestMapping(path = URL.Fault.FAULT_INFO, method = RequestMethod.GET)
  public GlobalResponse<Map<String, List<FaultCaseInfo>>> getFaultCaseInfo() {
    return testSuiteManagerService.getFaultCaseInfo();
  }
}
