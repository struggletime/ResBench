package com.resbench.manager.model.dto;

import com.resbench.manager.model.external.benchmark.BenchMarkStatusResponse;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class TestSuiteHistoryResponse {
  // key 测试套， value 测试套历史全名
  private Map<String, List<String>> historyMap = new HashMap<>();

  // key 历史名称，value 状态
  private Map<String, BenchMarkStatusResponse> statusMap = new HashMap<>();
}
