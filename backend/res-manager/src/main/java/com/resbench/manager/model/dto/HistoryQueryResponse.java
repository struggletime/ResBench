package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 历史测试检索结果（归档检索）。row 保持 Map 结构，避免两侧字段漂移；
 * 字段含义见引擎 /web/bench/history/query。
 */
@Data
public class HistoryQueryResponse {
  private int total;

  private int page = 1;

  private int size = 20;

  private int pageCount = 1;

  private List<Map<String, Object>> rows = new ArrayList<>();

  private Map<String, Object> filters = new LinkedHashMap<>();
}