package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 故障编排合理性校验请求（管理端）。故障结构与 startByForm 完全一致。
 */
@Data
public class OrchestrationCheckRequest {

  private String envInfo;

  private List<StartByFormRequest.FaultItem> faults = new ArrayList<>();
}
