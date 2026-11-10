package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class StartByFormRequest {
  private String envInfo;

  private String testName;

  private String dbType;

  private Workload workload = new Workload();

  private Database database = new Database();

  private List<FaultItem> faults = new ArrayList<>();

  /** 目标容器名：非空时 chaosblade 类故障通过 docker exec 在容器内注入（网络类故障除外，仍走宿主机） */
  private String containerName;

  private String autoTimingMode;

  /** 自动定时 CV 阈值（dynamic-threshold 模式用；此前 manager 缺这个字段导致经管理端转发时被丢掉） */
  private Double cvThreshold;

  private RunOptions runOptions = new RunOptions();

  /** 编排标识（自动化脚本/测试矩阵使用，仅用于归档与检索） */
  private String orchestrationId;

  /** 数据库配置标识（自动化脚本/测试矩阵使用，仅用于归档与检索） */
  private String dbKey;

  /** 发起来源：ui / script / matrix（仅用于归档与检索） */
  private String source;

  /** 标签，便于日后按标签检索归档 */
  private List<String> labels = new ArrayList<>();

  /** 备注说明 */
  private String note;

  @Data
  public static class Workload {
    private String name;

    private Map<String, String> params = new LinkedHashMap<>();
  }

  @Data
  public static class Database {
    private String deploymentType;

    private StandaloneDatabase standalone = new StandaloneDatabase();

    private PrimaryStandbyDatabase primaryStandby = new PrimaryStandbyDatabase();

    private Map<String, String> distributed = new LinkedHashMap<>();

    private Map<String, String> embedded = new LinkedHashMap<>();

    /**
     * 数据库配置参数（非连接信息），例如 shared_buffers / max_connections / work_mem。
     * 只用于归档与检索对比，不参与连接；连接信息不会被归档。
     */
    private Map<String, String> params = new LinkedHashMap<>();
  }

  @Data
  public static class StandaloneDatabase {
    private String ip;

    private String port;

    private String username;

    private String password;
  }

  @Data
  public static class PrimaryStandbyDatabase {
    private List<Map<String, String>> nodes = new ArrayList<>();
  }

  @Data
  public static class FaultItem {
    private String nameKey;

    private String groupKey;

    private Integer delaySeconds;

    /**
     * 绝对注入时刻（秒，相对故障运行器启动）。非空时直接作为 schedule.xml 的
     * {@code <planning>}，不再叠加组基准与组间 +30 间隔；为空时保持原有
     * {@code delaySeconds} 的相对偏移语义。
     */
    private Integer planningSeconds;

    private Map<String, String> params = new LinkedHashMap<>();
  }

  @Data
  public static class RunOptions {
    private Integer monitorIntervalSec = 1;

    private Boolean create = Boolean.TRUE;

    private Boolean load = Boolean.TRUE;

    private Boolean execute = Boolean.TRUE;
    private Boolean reload = Boolean.FALSE;
  }
}
