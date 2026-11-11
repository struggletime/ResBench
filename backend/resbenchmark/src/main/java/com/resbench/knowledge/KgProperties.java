package com.resbench.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 故障编排校验脚本（kg_orchestration.py）的运行配置。
 *
 * <p>脚本默认与 Engine 同目录；Python 解释器可用 {@code resbench.kg.python}、
 * 环境变量 {@code RESBENCH_PYTHON} 指定，未指定时使用 python3/python。
 */
@Data
@Component
@ConfigurationProperties(prefix = "resbench.kg")
public class KgProperties {

  /** Python 解释器；为空时按 resbench.python → RESBENCH_PYTHON → python3 的顺序解析。 */
  private String python = "";

  /** 校验脚本路径；相对路径按 Engine 工作目录解析。 */
  private String script = "kg_orchestration.py";

  /** 知识图谱版本。 */
  private String graphVersion = "resbench-kg-v1";

  /** strict 只使用已校验规则；preview 额外使用候选规则（仅用于实验查看）。 */
  private String relationMode = "strict";

  /** 单次校验的超时时间（秒）。 */
  private long timeoutSeconds = 120;

  /** 密码文件；为空时依次尝试环境变量 NEO4J_PASSWORD 与部署目录下的 neo4j/.password。 */
  private String passwordFile = "";

  /** 保留生成的 XML 与报告，便于排查；默认校验后清理。 */
  private boolean keepArtifacts = false;
}
