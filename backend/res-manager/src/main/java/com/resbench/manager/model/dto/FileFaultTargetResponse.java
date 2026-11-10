package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * file 故障候选文件清单（引擎 /web/bench/faults/file-targets 的镜像）。
 *
 * <p>结构：{@code groups} = 一个「目录」组 + 每张表一个组；每张表的组里，{@code items} 是它的
 * 堆文件（第 0 段 / 最后一段 / 第 1 段），{@code children} 是它的各个索引。
 *
 * <p>{@code success=false} 表示拿不到清单（连不上库 / 这一轮要重建数据 / 这个库类型还没有探针），
 * 这时 {@code groups} 为空、{@code message} 是原因，前端要显示出来。
 */
@Data
public class FileFaultTargetResponse {
  private boolean success;

  private String message;

  private String dbType;

  private Database database;

  private String dataDirectory;

  private boolean statsFresh;

  private String rankBasis;

  private List<String> notes = new ArrayList<>();

  private List<Group> groups = new ArrayList<>();

  private String checkedAt;

  private long elapsedMs;

  @Data
  public static class Database {
    private String name;

    private String id;
  }

  @Data
  public static class Group {
    private String title;

    /** {@code directories} | {@code table} | {@code index} */
    private String kind;

    private String subtitle;

    private List<Item> items = new ArrayList<>();

    /** 只有 table 组会有：它下面的各个索引 */
    private List<Group> children = new ArrayList<>();
  }

  @Data
  public static class Item {
    private String path;

    /** <b>只有两个取值</b>：{@code directory} | {@code file} —— 前端据此打 [目录]/[文件] 硬标签 */
    private String kind;

    private String label;

    private String belongsTo;

    private String role;

    private Long bytes;

    private Integer segments;

    private Long scans;

    private List<String> suggestFor = new ArrayList<>();

    private String note;

    private boolean recommended;
  }
}
