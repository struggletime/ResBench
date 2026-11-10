package com.resbench.manager.model.dto;

import lombok.Data;

/**
 * TPCC 仓数检查结果（管理端镜像）。
 *
 * <p>字段必须与引擎的 TpccWarehouseVerifyResponse 一一对应 —— 管理端只做透传，
 * 少一个字段就会让前端拿到 null。
 *
 * <p>{@code success=false} 表示<b>无法判定</b>（连不上 / 没权限 / 超时），
 * 与"尚未建仓"（{@code conclusion=ABSENT}）是两回事。
 */
@Data
public class TpccWarehouseVerifyResponse {
  private boolean success;

  private String message;

  /** MATCH / MISMATCH / INCOMPLETE / ABSENT / UNKNOWN */
  private String conclusion;

  private String dbType;

  private int configuredWarehouses;

  private Integer actualWarehouses;

  private Integer actualDistricts;

  private Integer expectedDistricts;

  private boolean warehouseTablePresent;

  private boolean districtTablePresent;

  private boolean snapshot = true;

  private boolean runInProgress;

  private String checkedAt;

  private long elapsedMs;
}