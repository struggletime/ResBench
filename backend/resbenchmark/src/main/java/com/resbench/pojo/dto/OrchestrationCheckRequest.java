package com.resbench.pojo.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 故障编排合理性校验请求。
 *
 * <p>与 {@code POST /web/bench/startByForm} 使用完全相同的表单结构：
 * 校验需要按 startByForm 的方式生成故障编排 XML 与主配置 XML，
 * 因此前端直接把「开始测试」的整个请求体传过来。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class OrchestrationCheckRequest extends StartByFormRequest {
}
