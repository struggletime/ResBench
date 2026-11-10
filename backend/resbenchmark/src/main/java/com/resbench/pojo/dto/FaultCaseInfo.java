package com.resbench.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FaultCaseInfo {
  private String suiteName;

  private String id;

  private String name;

  private String planning;

  private String during;
}
