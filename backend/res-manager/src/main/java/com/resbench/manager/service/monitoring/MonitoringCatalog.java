package com.resbench.manager.service.monitoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.resbench.manager.model.monitoring.MonitoringModels.MetricDefinition;

import org.springframework.stereotype.Component;

@Component
public class MonitoringCatalog {
  /**
   * 伪文件系统挂载点（proc/sysfs/cgroup/tmpfs/overlay…）不是真实磁盘占用：
   * node_exporter 对每个挂载点各产出一条序列，size 为 0 的还会被下面的公式算成 100%，
   * 一张图能画出上百条平线。这里统一排除，只保留块设备与网络文件系统。
   * 容器可写层 overlay 与宿主根分区同源，排除后看根分区即可。
   */
  private static final String REAL_FS_FILTER = ",fstype!~\"tmpfs|devtmpfs|overlay|proc|sysfs|cgroup|cgroup2|debugfs|tracefs|securityfs|pstore|configfs|fusectl|mqueue|hugetlbfs|devpts|rpc_pipefs|binfmt_misc|autofs|squashfs|ramfs|nsfs\"";

  private final Map<String, MetricDefinition> definitions = new LinkedHashMap<>();

  public MonitoringCatalog() {
    // 主机/网络指标一律用 [15s] 窗口：agent 的主机采集器是 1s 桶，
    // Prometheus 也按 1s 抓取（deploy/prometheus/openinsight-agent.yaml）。
    // 用 rate(...[1m]) 会把 30s 的故障峰值抹平并把峰顶推后 30~60s。
    // 数据库与容器指标仍用 [1m]：它们的采集周期本来就是 10~60s。
    register(new MetricDefinition("host.cpu.usage", "CPU 使用率", "host", "主机", "cpu", "CPU 与负载", "%", "node_cpu", "", "clamp_min(clamp_max((1 - avg by(instance) (rate(node_cpu_seconds_total{instance=\"{{instance}}\",mode=\"idle\",cpu!=\"total\"}[15s]))) * 100, 100), 0)", "low", true, "非空闲 CPU 时间占比"));
    register(new MetricDefinition("host.load.ratio", "系统 Load 比率", "host", "主机", "cpu", "CPU 与负载", "%", "node_load", "", "node_system_load_ratio{instance=\"{{instance}}\"} * 100", "low", true, "Load1 与 CPU 核数的比率"));
    register(new MetricDefinition("host.memory.usage", "内存使用率", "host", "主机", "memory", "内存", "%", "node_memory", "", "(1 - node_memory_memavailable_bytes{instance=\"{{instance}}\"} / node_memory_memtotal_bytes{instance=\"{{instance}}\"}) * 100", "low", true, "主机内存使用率"));
    register(new MetricDefinition("host.disk.read", "磁盘读取吞吐", "host", "主机", "disk", "磁盘与文件系统", "B/s", "node_disk", "", "rate(node_disk_read_bytes_total{instance=\"{{instance}}\"}[15s])", "low", false, "按设备展示读取吞吐"));
    register(new MetricDefinition("host.disk.write", "磁盘写入吞吐", "host", "主机", "disk", "磁盘与文件系统", "B/s", "node_disk", "", "rate(node_disk_written_bytes_total{instance=\"{{instance}}\"}[15s])", "low", false, "按设备展示写入吞吐"));
    register(new MetricDefinition("host.disk.usage", "文件系统使用率", "host", "主机", "disk", "磁盘与文件系统", "%", "node_filesystem", "", "(1 - node_filesystem_avail_bytes{instance=\"{{instance}}\"" + REAL_FS_FILTER + "} / clamp_min(node_filesystem_size_bytes{instance=\"{{instance}}\"" + REAL_FS_FILTER + "}, 1)) * 100", "low", false, "按挂载点展示真实文件系统使用率（已排除 proc/sysfs/cgroup/tmpfs/overlay 等伪文件系统）；磁盘填充注入时目标挂载点上升"));
    register(new MetricDefinition("host.network.rx", "网络接收吞吐", "host", "主机", "network", "网络", "B/s", "node_network", "", "rate(node_network_receive_bytes_total{instance=\"{{instance}}\"}[15s])", "low", false, "按网卡展示接收吞吐"));
    register(new MetricDefinition("host.network.tx", "网络发送吞吐", "host", "主机", "network", "网络", "B/s", "node_network", "", "rate(node_network_transmit_bytes_total{instance=\"{{instance}}\"}[15s])", "low", false, "按网卡展示发送吞吐"));
    register(new MetricDefinition("host.netstat.retrans", "TCP 重传速率", "host", "主机", "network", "网络", "segs/s", "node_network_stack", "", "rate(node_netstat_tcp_retranssegs{instance=\"{{instance}}\"}[15s])", "low", false, "TCP 重传报文速率；丢包注入时上升（/proc/net/snmp）"));
    register(new MetricDefinition("host.netstat.reorder", "TCP 重排/快速重传速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "rate(node_netstat_tcpext_tcpfastretrans{instance=\"{{instance}}\"}[15s]) + rate(node_netstat_tcpext_tcplostretransmit{instance=\"{{instance}}\"}[15s]) + rate(node_netstat_tcpext_tcprenoreorder{instance=\"{{instance}}\"}[15s]) + rate(node_netstat_tcpext_tcpsackreorder{instance=\"{{instance}}\"}[15s]) + rate(node_netstat_tcpext_tcptsreorder{instance=\"{{instance}}\"}[15s])", "low", false, "快速重传/丢包重传/乱序重排计数之和；乱序与重复包注入时上升"));
    // ---- 网络故障专用信号 ----
    // 下面这一组是「按故障种类可区分」的计数，不是通用吞吐：
    //   丢包   → retrans.ratio / rto.rate / lost_retrans / synretrans（重传侧全面上升）
    //   乱序   → oo.rate（乱序入队）
    //   重复   → dsack.rate（DSACK = 收到重复/伪重传）
    //   延迟   → 没有宿主机侧 RTT 计数器（需 eBPF tcprtt），只能看「吞吐/连接下降 + 重传率不升」
    // 指标名均已在本环境 Prometheus（instance=127.0.0.1:19091）确认有序列。
    register(new MetricDefinition("net.tcp.retrans.ratio", "TCP 重传率", "host", "主机", "network", "网络", "%", "node_network_stack", "", "sum(rate(node_netstat_tcp_retranssegs{instance=\"{{instance}}\"}[15s])) / clamp_min(sum(rate(node_netstat_tcp_outsegs{instance=\"{{instance}}\"}[15s])), 1) * 100", "low", true, "重传段数占发送段数的比例：除以流量后不受负载漂移影响，丢包注入时显著上升"));
    register(new MetricDefinition("net.tcp.rto.rate", "TCP RTO 超时速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "sum(rate(node_netstat_tcpext_tcptimeouts{instance=\"{{instance}}\"}[15s]))", "low", false, "重传超时（RTO）触发速率；严重丢包时上升，是丢包最专一的信号之一"));
    register(new MetricDefinition("net.tcp.lost_retrans.rate", "SACK 丢包重传速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "sum(rate(node_netstat_tcpext_tcplostretransmit{instance=\"{{instance}}\"}[15s]))", "low", false, "SACK 判定为丢失后再次重传的速率；丢包注入时上升"));
    register(new MetricDefinition("net.tcp.synretrans.rate", "SYN 重传速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "sum(rate(node_netstat_tcpext_tcpsynretrans{instance=\"{{instance}}\"}[15s]))", "low", false, "建连握手阶段的重传速率；丢包打到新连接上时上升（对应 agent 回传的 Connection reset）"));
    register(new MetricDefinition("net.tcp.oo.rate", "TCP 乱序入队速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "sum(rate(node_netstat_tcpext_tcpofoqueue{instance=\"{{instance}}\"}[15s])) + sum(rate(node_netstat_tcpext_tcpofodrop{instance=\"{{instance}}\"}[15s]))", "low", true, "乱序队列入队/溢出丢弃速率：乱序注入的直接信号，不受重传类计数污染"));
    register(new MetricDefinition("net.tcp.reorder.rate", "TCP 乱序重排检测速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "sum(rate(node_netstat_tcpext_tcpsackreorder{instance=\"{{instance}}\"}[15s])) + sum(rate(node_netstat_tcpext_tcptsreorder{instance=\"{{instance}}\"}[15s])) + sum(rate(node_netstat_tcpext_tcprenoreorder{instance=\"{{instance}}\"}[15s]))", "low", false, "TCP 三种乱序检测算法（SACK/时间戳/Reno）的命中速率；比 host.netstat.reorder 纯粹——后者混入了快速重传与丢包重传"));
    register(new MetricDefinition("net.tcp.dsack.rate", "DSACK 重复包速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "sum(rate(node_netstat_tcpext_tcpdsackrecv{instance=\"{{instance}}\"}[15s])) + sum(rate(node_netstat_tcpext_tcpdsackofosent{instance=\"{{instance}}\"}[15s]))", "low", true, "DSACK（重复/伪重传）命中速率：收到重复包时的直接信号，重复包注入时上升"));
    register(new MetricDefinition("net.tcp.conn.fail.rate", "TCP 建连失败速率", "host", "主机", "network", "网络", "events/s", "node_network_stack", "", "sum(rate(node_netstat_tcpext_embryonicrsts{instance=\"{{instance}}\"}[15s])) + sum(rate(node_netstat_tcpext_tcpabortontimeout{instance=\"{{instance}}\"}[15s]))", "low", false, "半开连接被 RST + 超时放弃连接的总速率；丢包/延迟把建连打崩时上升"));
    register(new MetricDefinition("net.packet.rx", "网络收包速率", "host", "主机", "network", "网络", "pps", "node_network", "", "rate(node_network_receive_packets_total{instance=\"{{instance}}\",device!~\"lo|docker.*|br-.*|veth.*\"}[15s])", "low", false, "按网卡展示收包速率（已排除 lo 与容器网桥）；丢包注入时重传会让收包结构变化"));
    register(new MetricDefinition("net.packet.tx", "网络发包速率", "host", "主机", "network", "网络", "pps", "node_network", "", "rate(node_network_transmit_packets_total{instance=\"{{instance}}\",device!~\"lo|docker.*|br-.*|veth.*\"}[15s])", "low", false, "按网卡展示发包速率（已排除 lo 与容器网桥）；配合重传率判断是「流量变化」还是「重传变化」"));

    // ---- netem qdisc 自身计数（tc -s qdisc），由 scripts/monitoring/qdisc_exporter.py 暴露 ----
    // 这几条不依赖 agent 的采集器：序列由我们自己的导出器（或 agent 的 node_qdisc 采集器）
    // 写进 Prometheus（textfile 或独立 /metrics）。
    // 为什么必须看 qdisc 而不是 net.tcp.*：tcpofoqueue 是接收侧的，乱序注入打在**发送侧**网卡上；
    // tcpsackreorder 只在丢包恢复路径上才累加，结构性地常常是 0。netem 的 requeues 才是「被判重排的包数」；
    // netem 的 dropped 才是「真正被丢掉的包数」——丢包率只能从这里算，RTO/重传只是丢包的后果。
    register(new MetricDefinition("net.qdisc.reorder.rate", "网卡乱序率", "host", "主机", "network", "网络", "events/s", "", "", "sum(rate(netem_requeues_total{instance=\"{{instance}}\"}[15s]))", "low", true, "netem 判定需要重排的报文速率（tc -s qdisc 的 requeues，乱序注入期间升高）。必须与 net.qdisc.delay 一起看：percent≥90 时 netem 会把几乎所有包标成立即发送、delay 被打塌成 0，此值涨到满格却不代表真的乱序"));
    register(new MetricDefinition("net.qdisc.present", "netem 规则在位", "host", "主机", "network", "网络", "0/1", "", "", "max by(instance, dev) (netem_present{instance=\"{{instance}}\"})", "low", false, "该网卡当前是否挂着 netem qdisc（1=规则在位）。丢包/重复故障没有可读的 qdisc 计数，这条用来证明规则确实挂到了目标网卡上"));
    register(new MetricDefinition("net.qdisc.drop.rate", "网卡丢包速率", "host", "主机", "network", "网络", "pps", "", "", "sum by(instance, dev) (rate(netem_dropped_total{instance=\"{{instance}}\"}[15s]))", "low", false, "netem qdisc 每秒丢弃的报文数（tc -s qdisc 的 dropped）。丢包注入时直接反映真实丢弃量；rto/重传是丢包之后的后果，不能替代这条"));
    register(new MetricDefinition("net.qdisc.drop.percent", "网卡丢包率", "host", "主机", "network", "网络", "%", "", "", "sum by(instance, dev) (rate(netem_dropped_total{instance=\"{{instance}}\"}[15s])) / clamp_min(sum by(instance, dev) (rate(netem_dropped_total{instance=\"{{instance}}\"}[15s])) + sum by(instance, dev) (rate(netem_sent_packets_total{instance=\"{{instance}}\"}[15s])), 1) * 100", "low", true, "netem qdisc 实际丢弃比例 = dropped / (dropped + sent)。80% 丢包注入应稳定在 80% 附近，用来核对「注入档位真的落到了这张网卡上」，也是 net loss 唯一的直接证据"));
    register(new MetricDefinition("net.qdisc.loss.percent", "netem 丢包档位", "host", "主机", "network", "网络", "%", "", "", "max by(instance, dev) (netem_loss_percent{instance=\"{{instance}}\"})", "low", false, "netem 规则里配置的丢包百分比（下发档位，不是测量值）。与 net.qdisc.drop.percent 对照：档位 80% 而实测是 0，说明规则没落到有流量的网卡上"));
    register(new MetricDefinition("net.qdisc.duplicate.percent", "netem 重复档位", "host", "主机", "network", "网络", "%", "", "", "max by(instance, dev) (netem_duplicate_percent{instance=\"{{instance}}\"})", "low", false, "netem 规则里配置的重复百分比（下发档位）。tc 不提供 duplicate 计数器，重复效果的实测证据是接收侧 net.tcp.dsack.rate（DSACK 命中）"));
    register(new MetricDefinition("net.qdisc.delay", "netem 配置时延", "host", "主机", "network", "网络", "ms", "", "", "max(netem_delay_seconds{instance=\"{{instance}}\"}) * 1000", "low", false, "故障规则里实际下发到网卡的 delay。为 0 时 reorder 不会真正发生（tc 拒绝无 delay 的 reorder），用来识别「乱序率高但没乱序」的空操作"));
    register(new MetricDefinition("net.qdisc.reorder.percent", "netem 重排比例", "host", "主机", "network", "网络", "%", "", "", "max(netem_reorder_percent{instance=\"{{instance}}\"})", "low", false, "故障规则里实际下发到网卡的 reorder percent；确认档位与编排一致，也用来对齐「为什么高percent反而没影响」"));

    // ---- ICMP 主动探测（agent 的 node_ping 采集器） ----
    // 上面这些信号都是「故障的后果」（重传、RTO、乱序入队）；ICMP 探测是唯一直接量到
    // 出口链路本身的方法：agent 在数据库主机上按 ~10pps ping 默认路由的下一跳，
    // 报文照样经过被 netem 整形的网卡，所以丢包/时延故障会原样出现在这里。
    // 目标默认自动发现（/proc/net/route 的默认路由），不需要配置；切换网络环境后
    // 要重启 agent 才会重新解析。探针本身不回复时 net_ping_up=0，此时前两条不可信。
    register(new MetricDefinition("net.ping.loss", "ICMP 丢包率", "host", "主机", "network", "网络", "%", "node_ping", "", "max by(instance, target) (net_ping_loss_percent{instance=\"{{instance}}\"})", "low", true, "从数据库主机 ping 默认网关（下一跳）的丢包率，15s 滑动窗口约 150 个探测包；丢包/断网类故障注入期间上升。探针由 agent 在主机侧发出，报文经过被 netem 整形的出口网卡"));
    register(new MetricDefinition("net.ping.rtt", "ICMP 时延", "host", "主机", "network", "网络", "ms", "node_ping", "", "max by(instance, target) (net_ping_rtt_ms{instance=\"{{instance}}\"})", "low", true, "从数据库主机 ping 默认网关的往返时延（15s 窗口均值）；delay 类故障注入期间上升。丢包时窗口内没有回包，该值为 0，需要结合 net.ping.loss 一起看"));
    register(new MetricDefinition("net.ping.up", "ICMP 探针存活", "host", "主机", "network", "网络", "0/1", "node_ping", "", "max by(instance, target) (net_ping_up{instance=\"{{instance}}\"})", "low", false, "ICMP 探针是否在正常发出报文（1=最近 5s 有完整批次，0=ping 没跑起来或全部超时）。为 0 时 net.ping.loss / net.ping.rtt 不可信"));

    register(new MetricDefinition("host.process.stopped", "被暂停进程数", "host", "主机", "process", "进程", "count", "node_process", "", "sum(node_processes_state{instance=\"{{instance}}\",state=\"T\"}) or vector(0)", "low", false, "处于 T（SIGSTOP/被跟踪）状态的进程数；进程暂停注入时由 0 变为正数"));
    register(new MetricDefinition("host.pressure.cpu", "CPU PSI", "host", "主机", "pressure", "系统压力", "%", "node_pressure", "", "rate(node_pressure_cpu_some_seconds_total{instance=\"{{instance}}\"}[15s]) * 100", "low", false, "CPU pressure stall 占比"));
    register(new MetricDefinition("host.pressure.io", "IO PSI", "host", "主机", "pressure", "系统压力", "%", "node_pressure", "", "rate(node_pressure_io_some_seconds_total{instance=\"{{instance}}\"}[15s]) * 100", "low", false, "IO pressure stall 占比"));

    // ---- 数据库指标：同一 metricId 同时覆盖 openGauss 与 MySQL ----
    // 两个产品的采集器各暴露一套序列（og_* / mysql_*），PromQL 用 `or` 连接：
    // 哪个产品的序列存在就返回哪个，不存在的分支为空向量、不产生额外序列。
    // collectorId 写两个候选、逗号分隔；MonitoringService.createSession 只把 agent
    // /api/v1/capabilities 里声明过的采集器下发过去（agent 会拒绝未知采集器）。
    // 这样 scripts/ 里按 metricId（db.tps.agent 等）取的证据在两种库上都成立。
    register(new MetricDefinition("db.tps.agent", "数据库统计 TPS", "database", "数据库", "throughput", "吞吐与事务", "tx/s", "og_tps,mysql_tps", "", "og_tps{instance=\"{{instance}}\"} or mysql_tps{instance=\"{{instance}}\"}", "low", true, "由数据库统计视图计算的 TPS（openGauss 取 og_tps，MySQL 取 performance_schema 事务计数）"));
    register(new MetricDefinition("db.rollback.rate", "事务回滚速率", "database", "数据库", "throughput", "吞吐与事务", "tx/s", "og_database_stats,mysql_database_stats", "", "sum(rate(og_database_xact_rollback_total{instance=\"{{instance}}\"}[1m])) or sum(rate(mysql_database_xact_rollback_total{instance=\"{{instance}}\"}[1m]))", "low", false, "每秒回滚事务数；海量事务回滚注入时上升（openGauss 取 og_database_stats，MySQL 取 Com_rollback）"));
    register(new MetricDefinition("db.connections.usage", "连接使用率", "database", "数据库", "connections", "连接与会话", "%", "og_connections,mysql_connections", "", "(og_connections_usage_ratio{instance=\"{{instance}}\"} or mysql_connections_usage_ratio{instance=\"{{instance}}\"}) * 100", "low", true, "当前连接数占最大连接数比例"));
    // conn_storm 是短连接风暴：瞬时连接数 gauge 抓不到（连接存活只有毫秒级），必须看建连速率。
    // node_netstat_tcp_activeopens 是 client 侧 connect 计数，由 openinsight-agent 在数据库主机上暴露。
    register(new MetricDefinition("db.connections.open_rate", "新建连接速率", "database", "数据库", "connections", "连接与会话", "conn/s", "node_network_stack", "", "rate(node_netstat_tcp_activeopens{instance=\"{{instance}}\"}[15s])", "low", true, "TCP 主动建连（connect）速率（client 侧，/proc/net/snmp）；短连接风暴 conn_storm 的直接信号：基线≈0/s，900 线程风暴实测约 1400–2100 conn/s"));
    register(new MetricDefinition("db.cache.hit", "缓存命中率", "database", "数据库", "cache", "缓存与 IO", "%", "og_database_stats,mysql_database_stats", "", "sum by(instance,database) (rate(og_database_blks_hit_total{instance=\"{{instance}}\"}[1m])) / clamp_min(sum by(instance,database) (rate(og_database_blks_hit_total{instance=\"{{instance}}\"}[1m]) + rate(og_database_blks_read_total{instance=\"{{instance}}\"}[1m])), 1) * 100 or (1 - sum(rate(mysql_innodb_buffer_pool_reads_total{instance=\"{{instance}}\"}[1m])) / clamp_min(sum(rate(mysql_innodb_buffer_pool_read_requests_total{instance=\"{{instance}}\"}[1m])), 1)) * 100", "low", false, "数据库 buffer/cache 命中率（MySQL 为 1 - 物理读请求/逻辑读请求）"));
    register(new MetricDefinition("db.lock.wait.max", "最大锁等待", "database", "数据库", "locks", "锁与等待", "s", "og_locks,mysql_locks", "", "max by(instance,database) (og_lock_wait_seconds_max{instance=\"{{instance}}\"}) or max(mysql_lock_wait_seconds_max{instance=\"{{instance}}\"})", "low", false, "最长锁等待时间"));
    register(new MetricDefinition("db.wait.time", "等待事件耗时", "database", "数据库", "locks", "锁与等待", "s", "og_wait_events", "", "og_wait_event_total_wait_time{instance=\"{{instance}}\"}", "medium", false, "按等待事件聚合的耗时（openGauss 专有：MySQL 没有等价的等待事件视图）"));
    register(new MetricDefinition("db.sessions", "Session 数", "database", "数据库", "connections", "连接与会话", "count", "og_sessions,mysql_sessions", "", "og_sessions{instance=\"{{instance}}\"} or mysql_sessions{instance=\"{{instance}}\"}", "low", false, "按数据库和状态展示 Session 数（MySQL 按 COMMAND 状态）"));
    register(new MetricDefinition("db.slow_sql.max", "慢 SQL 最大耗时", "database", "数据库", "sql", "慢 SQL 与资源管理", "s", "og_slow_sql,mysql_slow_sql", "", "max by(instance,database) (og_slow_sql_max_elapsed_seconds{instance=\"{{instance}}\"}) or max(mysql_slow_sql_max_elapsed_seconds{instance=\"{{instance}}\"})", "high", false, "低基数慢 SQL 聚合最大耗时（MySQL 来自 events_statements_summary_by_digest）"));
    register(new MetricDefinition("db.queue.depth", "内核/WLM 队列深度", "database", "数据库", "resource", "慢 SQL 与资源管理", "count", "og_kernel_resource", "", "max({instance=\"{{instance}}\",__name__=~\"og_dbe_perf_.*_(queue_size|queue_length)\"})", "medium", false, "openGauss 线程池或 WLM 队列深度"));

    // ---- MySQL 专有信号（openGauss 没有同口径计数器，单独成项，MySQL agent 才可用） ----
    register(new MetricDefinition("db.threads.running", "运行中线程数", "database", "数据库", "connections", "连接与会话", "count", "mysql_connections", "", "mysql_threads_running{instance=\"{{instance}}\"}", "low", false, "MySQL 非 Sleep 状态的线程数；连接打满或锁阻塞时升高，比连接使用率更早反映饱和"));
    register(new MetricDefinition("db.blocked.sessions", "被阻塞会话数", "database", "数据库", "locks", "锁与等待", "count", "mysql_locks", "", "max(mysql_blocked_sessions{instance=\"{{instance}}\"})", "low", false, "MySQL 正在等待行锁的会话数；行锁冲突注入时上升"));
    register(new MetricDefinition("db.replication.lag", "复制延迟", "database", "数据库", "replication", "复制", "s", "mysql_replication", "", "max(mysql_replication_lag_seconds{instance=\"{{instance}}\"})", "low", false, "MySQL 备库延迟秒数（Seconds_Behind_Source）；单机主库时该序列不存在"));

    // CPU 用 irate 而不是 rate[1m]：cAdvisor 默认 --housekeeping_interval=10s，配上 3s 抓取，
    // container_cpu_usage_seconds_total 实际 11~18s 才变一次。1 分钟滑动平均会把短故障整个摊平
    // （2026-10-25 smallbank_20261025_214202：30s CPU 打满 32 核配额，rate[1m] 峰值只有 2360%，
    // 真实 3203.6%）。irate 取窗口内最后两个采样点，45s 窗口保证计数器至少变过 2~4 次，不漏点。
    register(new MetricDefinition("container.cpu.usage", "容器 CPU 使用率（100%=1核）", "container", "容器", "cpu", "CPU 与负载", "%", "", "", "sum(irate(container_cpu_usage_seconds_total{job=\"cadvisor\",name=\"{{container}}\"}[45s])) * 100", "low", true, "容器 CPU 使用率，100% = 占满 1 个核，可超过 100%（与 docker stats 口径一致，cAdvisor；瞬时值，不做 1 分钟平均）"));
    register(new MetricDefinition("container.cpu.quota.usage", "容器 CPU 配额使用率", "container", "容器", "cpu", "CPU 与负载", "%", "", "", "sum(irate(container_cpu_usage_seconds_total{job=\"cadvisor\",name=\"{{container}}\"}[45s])) / clamp_min((max(container_spec_cpu_quota{job=\"cadvisor\",name=\"{{container}}\"} / container_spec_cpu_period{job=\"cadvisor\",name=\"{{container}}\"}) or vector(1)), 0.001) * 100", "low", false, "容器 CPU 使用量占 Docker CPU 配额的比例，0–100%（cAdvisor）"));
    register(new MetricDefinition("container.cpu.cores", "容器 CPU 使用量", "container", "容器", "cpu", "CPU 与负载", "cores", "", "", "sum(irate(container_cpu_usage_seconds_total{job=\"cadvisor\",name=\"{{container}}\"}[45s]))", "low", false, "容器实际使用的 CPU 核数（cAdvisor）"));
    register(new MetricDefinition("container.memory.usage", "容器内存使用率", "container", "容器", "memory", "内存", "%", "", "", "container_memory_working_set_bytes{job=\"cadvisor\",name=\"{{container}}\"} / clamp_min(container_spec_memory_limit_bytes{job=\"cadvisor\",name=\"{{container}}\"}, 1) * 100", "low", true, "容器内存工作集占 Docker 内存上限的比例（cAdvisor）"));
    register(new MetricDefinition("container.memory.working_set", "容器内存工作集", "container", "容器", "memory", "内存", "B", "", "", "container_memory_working_set_bytes{job=\"cadvisor\",name=\"{{container}}\"}", "low", false, "容器当前内存工作集（cAdvisor）"));
    register(new MetricDefinition("container.disk.read", "容器磁盘读取吞吐", "container", "容器", "disk", "磁盘与文件系统", "B/s", "", "", "rate(container_fs_reads_bytes_total{job=\"cadvisor\",name=\"{{container}}\"}[1m])", "low", false, "按设备展示容器读取吞吐（cAdvisor）"));
    register(new MetricDefinition("container.disk.write", "容器磁盘写入吞吐", "container", "容器", "disk", "磁盘与文件系统", "B/s", "", "", "rate(container_fs_writes_bytes_total{job=\"cadvisor\",name=\"{{container}}\"}[1m])", "low", false, "按设备展示容器写入吞吐（cAdvisor）"));
    register(new MetricDefinition("container.network.rx", "容器网络接收吞吐", "container", "容器", "network", "网络", "B/s", "", "", "rate(container_network_receive_bytes_total{job=\"cadvisor\",name=\"{{container}}\"}[1m])", "low", false, "按网卡展示容器接收吞吐；host 网络容器为整机网卡口径"));
    register(new MetricDefinition("container.network.tx", "容器网络发送吞吐", "container", "容器", "network", "网络", "B/s", "", "", "rate(container_network_transmit_bytes_total{job=\"cadvisor\",name=\"{{container}}\"}[1m])", "low", false, "按网卡展示容器发送吞吐；host 网络容器为整机网卡口径"));

    register(new MetricDefinition("ebpf.runq.avg", "Run queue 平均延迟", "ebpf", "eBPF", "scheduler", "CPU 调度", "μs", "", "runqlat", "sum(rate(ebpf_cpu_runq_latency_seconds_sum{instance=\"{{instance}}\"}[1m])) / clamp_min(sum(rate(ebpf_cpu_runq_latency_seconds_count{instance=\"{{instance}}\"}[1m])), 0.000001) * 1000000", "medium", false, "CPU run queue 平均延迟"));
    register(new MetricDefinition("ebpf.runq.p99", "Run queue p99", "ebpf", "eBPF", "scheduler", "CPU 调度", "μs", "", "runqlat", "histogram_quantile(0.99, sum by(instance,le) (rate(ebpf_cpu_runq_latency_seconds_bucket{instance=\"{{instance}}\"}[1m]))) * 1000000", "medium", false, "CPU run queue p99 延迟"));
    register(new MetricDefinition("ebpf.runq.slow.rate", "慢调度事件速率", "ebpf", "eBPF", "scheduler", "CPU 调度", "events/s", "", "runqslower", "rate(ebpf_cpu_runq_slow_latency_seconds_count{instance=\"{{instance}}\"}[1m])", "medium", false, "超过阈值的慢调度事件速率"));
    register(new MetricDefinition("ebpf.disk.avg", "磁盘平均延迟", "ebpf", "eBPF", "block_io", "块设备 IO", "μs", "", "biolatency", "sum by(instance,device) (rate(ebpf_disk_latency_seconds_sum{instance=\"{{instance}}\"}[1m])) / clamp_min(sum by(instance,device) (rate(ebpf_disk_latency_seconds_count{instance=\"{{instance}}\"}[1m])), 0.000001) * 1000000", "medium", false, "按块设备展示平均 IO 延迟"));
    register(new MetricDefinition("ebpf.disk.p99", "磁盘 p99 延迟", "ebpf", "eBPF", "block_io", "块设备 IO", "μs", "", "biolatency", "histogram_quantile(0.99, sum by(instance,device,le) (rate(ebpf_disk_latency_seconds_bucket{instance=\"{{instance}}\"}[1m]))) * 1000000", "medium", false, "按块设备展示 p99 IO 延迟"));
    register(new MetricDefinition("ebpf.block.throughput", "块 IO 吞吐", "ebpf", "eBPF", "block_io", "块设备 IO", "B/s", "", "biosnoop", "sum by(instance,device,operation) (rate(ebpf_block_io_bytes_total{instance=\"{{instance}}\"}[1m]))", "medium", false, "按设备和读写操作展示吞吐"));
    register(new MetricDefinition("ebpf.tcp.connect.p99", "TCP 建连 p99", "ebpf", "eBPF", "network", "网络", "ms", "", "tcpconnect", "histogram_quantile(0.99, sum by(instance,le) (rate(ebpf_tcp_connect_latency_seconds_bucket{instance=\"{{instance}}\"}[1m]))) * 1000", "medium", false, "TCP connect p99 延迟"));
    register(new MetricDefinition("ebpf.tcp.rtt.p99", "TCP RTT p99", "ebpf", "eBPF", "network", "网络", "ms", "", "tcprtt", "histogram_quantile(0.99, sum by(instance,le) (rate(ebpf_tcp_rtt_seconds_bucket{instance=\"{{instance}}\"}[1m]))) * 1000", "medium", false, "TCP RTT p99"));
    register(new MetricDefinition("ebpf.vfs.rate", "VFS 操作速率", "ebpf", "eBPF", "filesystem", "文件系统", "ops/s", "", "vfsstat", "rate(ebpf_vfs_operations_total{instance=\"{{instance}}\"}[1m])", "low", false, "按 VFS 操作类型展示速率"));
  }

  private void register(MetricDefinition definition) {
    definitions.put(definition.metricId(), definition);
  }

  public List<MetricDefinition> all() {
    return List.copyOf(definitions.values());
  }

  public Optional<MetricDefinition> find(String metricId) {
    return Optional.ofNullable(definitions.get(metricId));
  }
}
