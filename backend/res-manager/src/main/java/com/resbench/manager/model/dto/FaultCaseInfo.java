package com.resbench.manager.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FaultCaseInfo {
  private String suiteName;

  private String id;

  private String name;

  private String planning;

  private String during;
}
