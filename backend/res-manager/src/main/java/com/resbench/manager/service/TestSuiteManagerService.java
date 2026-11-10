package com.resbench.manager.service;

import com.resbench.manager.global.GlobalResponse;
import com.resbench.manager.model.dto.FaultCaseInfo;

import java.util.List;
import java.util.Map;

public interface TestSuiteManagerService {
  GlobalResponse<Map<String, List<FaultCaseInfo>>> getFaultCaseInfo();
}
