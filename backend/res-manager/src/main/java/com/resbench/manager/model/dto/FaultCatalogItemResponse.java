package com.resbench.manager.model.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FaultCatalogItemResponse {
  private String nameKey;
  private String name;
  private String description;
  private String category;
  private int autoRecovery;
  private int defaultDuration;
  private boolean hasCheck;
  private List<FaultParamResponse> params;
}