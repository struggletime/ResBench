package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** 归档动作：给一轮历史测试打标签 / 写备注。 */
@Data
public class HistoryLabelRequest {
  private String envInfo;

  private String historyName;

  private List<String> labels = new ArrayList<>();

  private String note;
}