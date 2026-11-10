package com.resbench.pojo.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 故障编排合理性校验结果。
 *
 * <p>{@code checked=false} 只表示知识图谱不可用（无法判定），
 * 不代表编排不合理；前端必须按 {@code checked} 分支展示。
 */
@Data
public class OrchestrationCheckResult {

  /** 编排是否合理；checked=false 时该值无意义。 */
  private boolean reasonable;

  /** 是否真正完成了检查（false = 图谱不可用等异常）。 */
  private boolean checked = true;

  /** 使用的图谱模式：neo4j / in-memory。 */
  private String mode;

  private String message;

  /** 实际参与同时性检查的组数（成员数 >= 2 的组）。 */
  private int checkedGroups;

  private List<Violation> violations = new ArrayList<>();

  public static OrchestrationCheckResult pass(String mode, String message) {
    OrchestrationCheckResult result = new OrchestrationCheckResult();
    result.setReasonable(true);
    result.setChecked(true);
    result.setMode(mode);
    result.setMessage(message);
    return result;
  }

  public static OrchestrationCheckResult fail(
      String mode, String message, List<Violation> violations) {
    OrchestrationCheckResult result = new OrchestrationCheckResult();
    result.setReasonable(false);
    result.setChecked(true);
    result.setMode(mode);
    result.setMessage(message);
    result.setViolations(violations == null ? new ArrayList<>() : violations);
    return result;
  }

  public static OrchestrationCheckResult error(String mode, String message) {
    OrchestrationCheckResult result = new OrchestrationCheckResult();
    result.setReasonable(false);
    result.setChecked(false);
    result.setMode(mode);
    result.setMessage(message);
    return result;
  }

  /** 一条冲突/重复记录。 */
  @Data
  public static class Violation {

    /** MUTEX / DUPLICATE。 */
    private String type;

    /** 冲突所在的组合（groupKey）；空 groupKey 不会产生冲突。 */
    private String groupKey;

    /** 涉及的故障 nameKey（重复 nameKey 会出现两次）。 */
    private List<String> nameKeys = new ArrayList<>();

    /** 可直接展示给用户的中文说明。 */
    private String message;

    public Violation() {
    }

    public Violation(String type, String groupKey, List<String> nameKeys, String message) {
      this.type = type;
      this.groupKey = groupKey;
      this.nameKeys = nameKeys;
      this.message = message;
    }
  }
}
