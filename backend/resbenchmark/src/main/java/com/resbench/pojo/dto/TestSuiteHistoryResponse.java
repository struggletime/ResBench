package com.resbench.pojo.dto;

import com.resbench.pojo.BenchMarkStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.Data;

@Data
public class TestSuiteHistoryResponse {
  // key 测试套， value 测试套历史全名
  private Map<String, List<String>> historyMap = new HashMap<>();

  // key 历史名称， value 该次测试状态
  private Map<String, BenchMarkStatus> statusMap = new HashMap<>();

  public static TestSuiteHistoryResponse create(List<String> TestSuiteNameList) {
    TestSuiteHistoryResponse testSuiteHistoryResponse = new TestSuiteHistoryResponse();
    Map<String, List<String>> historyMapTmp = testSuiteHistoryResponse.getHistoryMap();
    Pattern pattern = Pattern.compile("_");
    TestSuiteNameList.sort(Comparator.reverseOrder());
    TestSuiteNameList.forEach(
        name -> {
          String[] split = pattern.split(name, 2);
          List<String> historyList =
              historyMapTmp.computeIfAbsent(split[0], k -> new ArrayList<>());
          historyList.add(name);
        });
    return testSuiteHistoryResponse;
  }
}
