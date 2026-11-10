package com.resbench.pojo.dto;

import java.util.List;

/**
 * 探针从目标库里读出来的<b>中性数据</b>：只说"这个库的文件长什么样"，不做判断、不拼文案。
 *
 * <p>这么分层是为了让"换一个数据库"只写一个探针类：清单分组、目录/文件标签、说明文字、推荐规则、
 * {@code @auto:} 哨兵语义全都在上层 {@code FileFaultTargetService} 里、所有库共用。
 * 新库只需要回答三件事 —— 数据总目录、这个库自己的目录、每个关系的文件路径/大小/热度/分段
 * （表与索引都要）。见 {@code service/probe/FileFaultTargetProbe}。
 *
 * <p><b>不直接对前端</b>：前端看的是 {@link FileFaultTargetResponse}（已经分好组、带好文案）。
 *
 * @param databaseName 库名，如 postgres
 * @param databaseId 库标识，PG 用 oid
 * @param dataDirectory 数据总目录。<b>必须是容器内路径</b> —— 故障是 docker exec 在容器里执行的
 * @param databaseDirectory 这个库自己的目录，如 PG 的 {@code <PGDATA>/base/<oid>}
 * @param moveTargetDirectory move 故障的落点目录。必须与源在<b>同一个文件系统</b>上，否则 mv 退化成
 *     "先拷再删"：实测拿 {@code /tmp} 当落点时，260 GB 拷到第 60 秒被 agent 的超时杀掉，
 *     半成品把宿主机根分区撑到 100%
 * @param statsFresh 热度统计可不可信。false = 计数刚被清零（库重启过），此时 {@code rankBasis} 是
 *     {@code bytes} 而不是 {@code scans}
 * @param rankBasis {@code "scans"}（按热度）或 {@code "bytes"}（按体量，仅在统计不可信时）
 * @param relations 每个关系一条，表与索引都在里面
 * @param notes 探针自己想说的话（统计是否可信、跳过了哪些空表……），会被原样带到响应里
 */
public record FileFaultTargetReport(
    String databaseName,
    String databaseId,
    String dataDirectory,
    String databaseDirectory,
    String moveTargetDirectory,
    boolean statsFresh,
    String rankBasis,
    List<RelationTarget> relations,
    List<String> notes) {

  /**
   * 一个关系的文件信息。
   *
   * <p>分段：PG 每 1 GiB 一段，{@code <relpath>}、{@code <relpath>.1}、{@code <relpath>.2}……
   * 不分段的库（如 MySQL 的 {@code .ibd}）让 {@code segments=1}、{@code lastSegment == firstSegment}、
   * {@code midSegment = null} 即可，上层已有规则会自动少出"最后一段/非最后一段"两项，探针不用写特判。
   */
  public record RelationTarget(
      String name,
      /** {@code "table"} 或 {@code "index"} */
      String kind,
      /** 索引属于哪张表（{@code kind=="index"} 时非空）；上层靠它把索引挂到表下面 */
      String ownerTable,
      /** 第 0 段的绝对路径（容器内） */
      String firstSegment,
      /** 最后一段的绝对路径；单段时与 firstSegment 相同 */
      String lastSegment,
      /** 一个非最后段的绝对路径；{@code segments < 2} 时为 null */
      String midSegment,
      long bytes,
      long scans,
      /** 分段数；不分段的库恒为 1 */
      int segments) {

    public boolean isIndex() {
      return "index".equals(kind);
    }
  }
}
