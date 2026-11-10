package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** 归档动作返回：更新后的标签与备注。 */
@Data
public class HistoryLabelResponse {
  private String historyName;

  private List<String> labels = new ArrayList<>();

  private String note;
}