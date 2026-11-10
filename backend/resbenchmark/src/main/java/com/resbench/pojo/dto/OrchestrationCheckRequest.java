package com.resbench.pojo.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 故障编排合理性校验请求。
 *
 * <p>与 {@code POST /web/bench/startByForm} 使用完全相同的故障列表结构，
 * 前端直接把「开始测试」里的 {@code faults} 数组传过来即可。
 */
@Data
public class OrchestrationCheckRequest {

  /** 部署环境标识（manager 转发时使用；引擎侧不参与判定）。 */
  private String envInfo;

  private List<StartByFormRequest.FaultItem> faults = new ArrayList<>();
}
