package com.resbench.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.constant.PathConstant;
import com.resbench.common.util.FileUtil;
import com.resbench.distributions.ZipfianGenerator;
import com.resbench.util.DbTypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TestSuiteUtils {

  private static final Logger LOG = LoggerFactory.getLogger(TestSuiteUtils.class);

  public static List<String> getResultPath(String testSuiteName) {
    List<String> resultPath = FileUtil.listSubDirectoryNames(PathConstant.RESULTS_PATH);
    return resultPath.stream()
        .filter(s -> s.startsWith(testSuiteName))
        .sorted()
        .collect(Collectors.toList());
  }

  public static List<String> filterDbms(List<String> directoryList, String dbType) {
    List<String> filteredList = new ArrayList<>();
    ObjectMapper mapper = new ObjectMapper();
    for (String dirName : directoryList) {
      File dir = new File(PathConstant.RESULTS_PATH,dirName);
      if (!dir.isDirectory()) {
        continue;
      }
      File summaryFile = findSummaryJson(dir);
      if (summaryFile == null) {
        continue;
      }
      try {
        JsonNode root = mapper.readTree(summaryFile);

        JsonNode dbmsTypeNode = root.get("DBMS Type");
        if (dbmsTypeNode != null) {
          String dbmsType = dbmsTypeNode.asText();

          // 摘要里写的是 POSTGRES，筛选值可能是 postgresql —— 按数据库族匹配
          if (DbTypeUtils.sameFamily(dbmsType, dbType)) {
            filteredList.add(dirName);
          }
        }
      }catch (IOException e) {
        LOG.warn("read summary file err: {}", e.getLocalizedMessage());
      }
    }
    return filteredList;
  }

  private static File findSummaryJson(File directory) {
    File[] files = directory.listFiles((dir,name) -> name.endsWith("summary.json"));

    if (files != null && files.length >0) {
      return files[0];
    }else {
      return null;
    }

  }
}
