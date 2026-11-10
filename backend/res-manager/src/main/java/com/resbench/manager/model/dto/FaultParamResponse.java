package com.resbench.manager.model.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FaultParamResponse {
  private String paramName;
  private String label;
  private String description;
  private String type;
  private String unit;
  private String defaultValue;
  private boolean required;
}