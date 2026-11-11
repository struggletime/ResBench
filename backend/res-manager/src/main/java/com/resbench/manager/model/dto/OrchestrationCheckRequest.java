package com.resbench.manager.model.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 故障编排合理性校验请求（管理端）。
 *
 * <p>与 startByForm 使用同一套表单结构；管理端只做转发，
 * 字段必须完整保留，否则引擎侧无法重新生成两份 XML。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class OrchestrationCheckRequest extends StartByFormRequest {
}
