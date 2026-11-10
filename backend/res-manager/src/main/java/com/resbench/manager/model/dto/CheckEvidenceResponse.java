package com.resbench.manager.model.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CheckEvidenceResponse {
  private String name;
  private String observed;
  private String expected;
  private boolean matched;
}