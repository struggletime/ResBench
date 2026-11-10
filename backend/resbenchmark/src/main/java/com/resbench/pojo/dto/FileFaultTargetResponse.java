package com.resbench.pojo.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * file 类故障的候选文件清单：按"目录 → 每张表（表下挂它的堆文件和它的各个索引）"分组。
 *
 * <p>{@code success=false} 表示<b>拿不到清单</b>（连不上库 / 要重建数据 / 这个库类型没有探针），
 * 这时 {@code groups} 为空、{@code message} 说明原因 —— 前端要把它显示出来，别让按钮点了没反应。
 *
 * <p>清单是"点这一下"的快照：中间重灌了数据就会过期。要长期有效就用 {@code @auto:} 哨兵，
 * 由引擎在开跑那一刻再解析一次。
 */
@Data
public class FileFaultTargetResponse {
  private boolean success;

  /** 给人看的说明；失败时是失败原因 */
  private String message;

  private String dbType;

  private Database database;

  /** 数据总目录（容器内路径） */
  private String dataDirectory;

  /** 热度统计可不可信：false = 库重启过、计数被清零，清单是按体量排的 */
  private boolean statsFresh;

  /** {@code scans}（按热度）或 {@code bytes}（按体量） */
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

  /** 一个分组：目录组、某张表的组，或（作为 table 组的 children）某个索引的组。 */
  @Data
  public static class Group {
    private String title;

    /** {@code directories} | {@code table} | {@code index} */
    private String kind;

    /** 副标题，如"表 · 最热 · 759,371,242 次扫描 · 38 GB · 35 段" */
    private String subtitle;

    private List<Item> items = new ArrayList<>();

    /** 只有 table 组会有：它下面的各个索引 */
    private List<Group> children = new ArrayList<>();
  }

  /** 一个可选的路径。 */
  @Data
  public static class Item {
    private String path;

    /** <b>只有两个取值</b>：{@code directory} | {@code file} —— 前端据此打 [目录]/[文件] 硬标签 */
    private String kind;

    /** 人话标签，如"堆文件 · 第 0 段" */
    private String label;

    /** 归属，如"stock（表）"、"stock 的索引 stock_pkey" */
    private String belongsTo;

    /** {@code db-dir} | {@code datadir} | {@code first-segment} | {@code last-segment} | {@code mid-segment} | {@code only-segment} */
    private String role;

    private Long bytes;

    private Integer segments;

    private Long scans;

    /** 适配哪些故障与档位，如 ["filechmod:low", "filemove:low"] */
    private List<String> suggestFor = new ArrayList<>();

    private String note;

    /** 命中请求里带的 nameKey + level 时为 true，前端打「推荐」 */
    private boolean recommended;
  }
}
