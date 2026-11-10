package com.resbench.service.probe;

import com.resbench.pojo.dto.FileFaultTargetReport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * 一个数据库的"file 故障目标"探针：回答"你这个库的文件长什么样"。
 *
 * <h2>怎么接一个新库</h2>
 *
 * <ol>
 *   <li>写一个类实现本接口，加 {@code @Component}；</li>
 *   <li>在 {@link #supportedDbTypes()} 里写上 dbType（小写，与 {@code TestConnectionService} 的取值一致）——
 *       {@link FileFaultTargetProbeRegistry} 会自动登记，<b>不用改任何调度代码</b>；</li>
 *   <li>实现 {@link #probe}，只需回答三件事：
 *       <ul>
 *         <li>数据总目录（{@code dataDirectory}，容器内路径）；</li>
 *         <li>这个库自己的目录（{@code databaseDirectory}）；</li>
 *         <li>每个关系的文件路径 / 大小 / 热度 / 分段 —— <b>表和索引都要</b>，索引要填 {@code ownerTable}。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * 清单分组、{@code [目录]/[文件]} 标签、说明文字、{@code suggestFor} 推荐规则、{@code @auto:} 哨兵语义
 * 全部由上层 {@code FileFaultTargetService} 负责，<b>探针里不要写文案、不要做判断</b>：这样接了新库，
 * 清单的样子和说法自动一致。
 *
 * <p>连库不归探针管：上层用 {@code TestConnectionService.openConnection} 开好连接再递进来，
 * 保证"看这个库的文件"和"往这个库打故障"是同一个目标。
 *
 * <p><b>现成实现</b>：{@link PostgresqlFileFaultTargetProbe}（{@code postgresql}）与
 * {@link MysqlFileFaultTargetProbe}（{@code mysql}）。openGauss / GaussDB 与 PG 同源（同样有
 * {@code pg_relation_filepath} / {@code pg_stat_user_tables} / {@code SHOW data_directory}），
 * 补测通过后把这两个字符串加进 PG 探针的 {@code supportedDbTypes()} 即可，不改任何别的代码 ——
 * 这就是这个接口存在的意义。
 *
 * <p><b>接一个新库时唯一容易漏的地方</b>：探针有<b>两批登记</b> —— Spring 侧靠 {@code @Component}
 * 自动收集，job 侧（另一个 JVM，没有 Spring）靠 {@code FileFaultTargetService.STANDALONE_PROBES}
 * 里那份手写清单。漏了后者，{@code @auto:} 会一路跑到 job 建完库、正要开跑负载时才报"没有探针"，
 * 代价是一整轮。{@code FileFaultTargetGroupingTest} 里有断言把两批钉在一起。
 */
public interface FileFaultTargetProbe {

  /** 这个探针认哪些 dbType（小写）。同一个 dbType 只能有一个探针，重复会被注册表拒绝。 */
  Set<String> supportedDbTypes();

  /**
   * 查这个库的文件结构。
   *
   * @param connection 已经开好的连接，探针只读、不要事务、不要改任何东西
   * @param workload 负载名（tpcc / smallbank……），探针一般用不到，留给按负载区分的库
   * @param tableWhitelist 这个负载<b>真正会碰的表</b>，由上层从 benchmark 的 DDL 资源里解析出来。
   *     <b>只查这些表</b> —— 库里可能躺着别的负载留下的同名前缀表（实测有 96 GB、{@code scans=0}
   *     的遗留表），按体量排序会优先选中它们，跑出来就是"假故障"
   * @return 中性数据；查不动就抛 {@link SQLException}，<b>不要吞掉、不要给默认值</b>
   */
  FileFaultTargetReport probe(Connection connection, String workload, List<String> tableWhitelist)
      throws SQLException;
}
