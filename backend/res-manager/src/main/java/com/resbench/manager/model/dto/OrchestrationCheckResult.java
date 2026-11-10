package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 故障编排合理性校验结果（管理端），字段与引擎侧 DTO 保持一致。
 */
@Data
public class OrchestrationCheckResult {

  private boolean reasonable;

  private boolean checked = true;

  private String mode;

  private String message;

  private int checkedGroups;

  private List<Violation> violations = new ArrayList<>();

  @Data
  public static class Violation {

    private String type;

    private String groupKey;

    private List<String> nameKeys = new ArrayList<>();

    private String message;
  }
}
