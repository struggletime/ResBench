package com.resbench.pojo.dto;

import lombok.Data;

/**
 * TPCC 仓数检查结果。
 *
 * <p>{@code success=false} 表示<b>无法判定</b>（连不上库 / 没权限 / 读超时），
 * 与"尚未建仓"（{@code success=true} + {@code conclusion=ABSENT}）是两回事，调用方不要混用。
 */
@Data
public class TpccWarehouseVerifyResponse {
  /** 查询本身成没成。false = 无法判定，不代表库是空的 */
  private boolean success;

  /** 给人看的说明（含快照声明） */
  private String message;

  /** MATCH / MISMATCH / INCOMPLETE / ABSENT / UNKNOWN，前端据此上色 */
  private String conclusion;

  private String dbType;

  /** 请求里配置的仓数 */
  private int configuredWarehouses;

  /** 库里实际的仓数（表不存在时为 0） */
  private Integer actualWarehouses;

  /** 库里实际的 district 行数 */
  private Integer actualDistricts;

  /** 完整性对照基准：实际仓数 × 每仓 district 数 */
  private Integer expectedDistricts;

  private boolean warehouseTablePresent;

  private boolean districtTablePresent;

  /** 恒为 true：这个结果只是点击瞬间的快照 */
  private boolean snapshot = true;

  /** 点这一下的时候引擎上有没有压测在跑 */
  private boolean runInProgress;

  private String checkedAt;

  private long elapsedMs;
}