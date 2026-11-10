package com.resbench.controller;

import com.resbench.pojo.dto.FaultCaseInfo;
import com.resbench.common.util.XMLUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/web")
public class TestSuiteController {

  @GetMapping("/testSuite/fault")
  public Map<String, List<FaultCaseInfo>> getNewestBenchResult() {
    return getFaultCaseInfo();
  }

  public Map<String, List<FaultCaseInfo>> getFaultCaseInfo() {
    HashMap<String, List<FaultCaseInfo>> result = new HashMap<>();
    try {
      XMLConfiguration configuration = XMLUtil.buildConfiguration("config/fault-cases-generic.xml");
      List<HierarchicalConfiguration<ImmutableNode>> suites =
          configuration.configurationsAt("testSuite");
      if (!suites.isEmpty()) {
        for (HierarchicalConfiguration<ImmutableNode> suite : suites) {
          String suiteName = suite.getString("@name");
          List<FaultCaseInfo> faultCaseInfos =
              result.computeIfAbsent(suiteName, k -> new ArrayList<>());
          List<HierarchicalConfiguration<ImmutableNode>> cases = suite.configurationsAt("case");
          if (!cases.isEmpty()) {
            for (HierarchicalConfiguration<ImmutableNode> testCase : cases) {
              String id = testCase.getString("@id");
              String description = testCase.getString("description");
              String planning = testCase.getString("planning");
              String during = testCase.getString("during");
              faultCaseInfos.add(new FaultCaseInfo(suiteName, id, description, planning, during));
            }
          }
        }
      }
    } catch (ConfigurationException e) {
      log.error("Failed to obtain the test suite. msg {}", e.getMessage());
    }
    return result;
  }
}
