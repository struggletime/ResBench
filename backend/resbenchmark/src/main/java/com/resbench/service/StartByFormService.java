package com.resbench.service;

import com.resbench.common.fault.AutoTimingConfig;
import com.resbench.common.fault.FaultInjector;
import com.resbench.common.util.FileUtil;
import com.resbench.pojo.dto.StartByFormRequest;
import com.resbench.pojo.dto.StartByFormResponse;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
public class StartByFormService {
  private static final Logger LOG = LoggerFactory.getLogger(StartByFormService.class);
  private static final String GENERATED_ROOT = "config/generated";
  private static final String SMALLBANK_TEMPLATE = "config/opengauss/opengauss_smallbank_config_chaosblade.xml";
  private static final String TPCC_TEMPLATE = "config/opengauss/opengauss_tpcc_config_chaosblade.xml";
  private static final String OCEANBASE_SMALLBANK_TEMPLATE = "config/oceanbase/oceanbase_smallbank_config_chaosblade.xml";
  private static final String GAUSSDB_SMALLBANK_TEMPLATE = "config/gaussdb/gaussdb_smallbank_config_chaosblade.xml";
  private static final String GAUSSDB_TPCC_TEMPLATE = "config/gaussdb/gaussdb_tpcc_config_chaosblade.xml";
  private static final String GENERATED_SUITE_NAME = "base-test-suite1";
  private static final int DEFAULT_AGENT_PORT = 8000;
  private static final String XI_NAMESPACE = "http://www.w3.org/2001/XInclude";
  private static final DateTimeFormatter RUN_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
  private static final String DBCHAOS_JAR = "/opt/DBChaos/DBChaos.jar";
  /** 宿主机（或容器内直接可用）的 chaosblade CLI。 */
  private static final String BLADE_CMD = "/opt/chaosblade/blade";
  /** 容器内的 chaosblade 包装脚本，负责容器 cgroup 口径的 --cgroup-root 修正。 */
  private static final String BLADE_CONTAINER_CMD = "/opt/chaosblade/blade-cg";

  /**
   * 压测连接的 JDBC 超时。<b>单位随驱动而不同</b>，写错不会报错，只会静默放大成分钟级：
   *
   * <ul>
   *   <li>pgjdbc 系（PostgreSQL / GaussDB）：单位是<b>秒</b>。驱动里就是
   *       {@code connectTimeout = CONNECT_TIMEOUT.getInt(info) * 1000}、
   *       {@code setNetworkTimeout(socketTimeout * 1000)}。此前这里写的 {@code 5000}
   *       因此是 5000 秒 ≈ 83 分钟：丢包把 socket 打成黑洞（没有 RST）后，worker 会一直
   *       阻塞在 socket 读上，直到 83 分钟后才报 08006，期间引擎「同时只收一个任务」的
   *       闸门一直被占着（2026-10-03 那轮 e03-net-loss-high 实测卡了 81 分钟）。</li>
   *   <li>MySQL Connector/J：单位是<b>毫秒</b>，且默认 0 = 永不超时。</li>
   * </ul>
   *
   * socketTimeout 的判据是「大于想观测到的合法最长事务」（实测确有 274 秒跑完的合法事务），
   * 而不是负载的运行时长；调得比合法慢事务还小，就会把「慢但成功」算成失败。
   */
  private static final int PG_CONNECT_TIMEOUT_SECONDS = 10;
  private static final int PG_SOCKET_TIMEOUT_SECONDS = 300;
  private static final int MYSQL_CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int MYSQL_SOCKET_TIMEOUT_MILLIS = 300_000;

  private final FaultCatalogLoader catalogLoader;
  private final FileFaultTargetService fileFaultTargetService;

  public StartByFormService(FaultCatalogLoader catalogLoader,
      FileFaultTargetService fileFaultTargetService) {
    this.catalogLoader = catalogLoader;
    this.fileFaultTargetService = fileFaultTargetService;
  }

  private record TpccCacheData(int warehouses, String status) {}

  private static String tpccWhCachePath(String dbType) {
    return "config/generated/.tpcc-wh-count-" + dbType;
  }

  public GeneratedStart prepare(StartByFormRequest request) throws Exception {
    validateRequest(request);

    String workloadName = normalizeWorkloadName(request.getWorkload().getName());
    String dbType = normalizeLower(request.getDbType());
    boolean isTpcc = "tpcc".equals(workloadName);
    boolean isMysql = "mysql".equals(dbType);
    boolean isPostgres = "postgresql".equals(dbType);
    boolean isOceanBase = "oceanbase".equals(dbType);
    boolean isGaussdb = "gaussdb".equals(dbType);
    String templatePath = isOceanBase ? OCEANBASE_SMALLBANK_TEMPLATE
        : isGaussdb ? (isTpcc ? GAUSSDB_TPCC_TEMPLATE : GAUSSDB_SMALLBANK_TEMPLATE)
        : isTpcc ? TPCC_TEMPLATE : SMALLBANK_TEMPLATE;
    String workerPrefix = isTpcc ? "tpcc-worker-" : "smallbank-worker-";
    String runId = buildRunId(workloadName, request.getTestName());

    Path generatedRoot = Path.of(GENERATED_ROOT);
    Path dbDirectory = generatedRoot.resolve(dbType);
    Files.createDirectories(generatedRoot);
    Files.createDirectories(dbDirectory);

    String workerFileName = workerPrefix + runId + ".xml";
    String faultFileName = "general-fault-case-" + runId + ".xml";
    String configFileName = runId + ".xml";

    Path workerPath = generatedRoot.resolve(workerFileName);
    Path faultPath = generatedRoot.resolve(faultFileName);
    Path configPath = dbDirectory.resolve(configFileName);

    List<FaultScheduleItem> scheduledFaults = buildScheduledFaults(request);

    String autoTimingMode = request.getAutoTimingMode();
    Double cvThreshold = request.getCvThreshold();
    writeWorkerXml(workerPath, request, isTpcc, autoTimingMode, cvThreshold);
    writeFaultScheduleXml(faultPath, scheduledFaults, autoTimingMode);
    writeConfigXml(configPath, request, scheduledFaults, "../" + workerFileName, templatePath, isTpcc, isMysql, isPostgres, isOceanBase, isGaussdb, autoTimingMode);

    // TPCC：根据 reload checkbox 决定是否建表加载
    if (isTpcc) {
      if (!Boolean.TRUE.equals(request.getRunOptions().getReload())) {
        request.getRunOptions().setCreate(false);
        request.getRunOptions().setLoad(false);
        LOG.info("[TPCC] reload not requested, skip create/load");
      } else {
        LOG.info("[TPCC] reload requested, will recreate");
      }
    }

    List<String> args = new ArrayList<>();
    args.add("-b");
    args.add(workloadName);
    args.add("-c");
    args.add(toConfigPath(configPath));
    args.add("--fault-config");
    args.add(toConfigPath(faultPath));
    args.add("--run-id");
    args.add(runId);
    // TPCC 缓存信息传给 job 进程，由 DBWorkload 在建表前后写入
    if (isTpcc) {
      args.add("--tpcc-cache");
      args.add(tpccWhCachePath(dbType) + ":" + parseWarehouseCount(request));
    }
    args.add("--create=" + booleanValue(request.getRunOptions().getCreate()));
    args.add("--load=" + booleanValue(request.getRunOptions().getLoad()));
    args.add("--execute=" + booleanValue(request.getRunOptions().getExecute()));
    args.add("-im");
    args.add(String.valueOf(Math.max(1, intValue(request.getRunOptions().getMonitorIntervalSec(), 1)) * 1000));

    return new GeneratedStart(
        args.toArray(new String[0]),
        new StartByFormResponse(true, runId, runId, toConfigPath(configPath), "started"));
  }

  /**
   * 只生成编排校验需要的两份 XML（故障编排 + 主配置），不启动测试。
   *
   * <p>复用 {@link #prepare} 的模板选择与 XML 写入逻辑，输出到
   * {@code config/generated/check/<runId>/}，由调用方在校验结束后
   * {@link #cleanup(PreparedCheck)}。worker XML 也一并生成，保证主配置里的
   * {@code xi:include} 在需要展开时可见。
   */
  public PreparedCheck prepareForCheck(StartByFormRequest request) throws Exception {
    validateRequest(request);

    String workloadName = normalizeWorkloadName(request.getWorkload().getName());
    String dbType = normalizeLower(request.getDbType());
    boolean isTpcc = "tpcc".equals(workloadName);
    boolean isMysql = "mysql".equals(dbType);
    boolean isPostgres = "postgresql".equals(dbType);
    boolean isOceanBase = "oceanbase".equals(dbType);
    boolean isGaussdb = "gaussdb".equals(dbType);
    String templatePath = isOceanBase ? OCEANBASE_SMALLBANK_TEMPLATE
        : isGaussdb ? (isTpcc ? GAUSSDB_TPCC_TEMPLATE : GAUSSDB_SMALLBANK_TEMPLATE)
        : isTpcc ? TPCC_TEMPLATE : SMALLBANK_TEMPLATE;
    String workerPrefix = isTpcc ? "tpcc-worker-" : "smallbank-worker-";
    String runId = "check-" + LocalDateTime.now().format(RUN_ID_FORMATTER) + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    Path checkRoot = Path.of(GENERATED_ROOT).resolve("check").resolve(runId);
    Path dbDirectory = checkRoot.resolve(dbType);
    Files.createDirectories(dbDirectory);

    String workerFileName = workerPrefix + runId + ".xml";
    String faultFileName = "general-fault-case-" + runId + ".xml";
    String configFileName = runId + ".xml";

    Path workerPath = checkRoot.resolve(workerFileName);
    Path faultPath = checkRoot.resolve(faultFileName);
    Path configPath = dbDirectory.resolve(configFileName);

    List<FaultScheduleItem> scheduledFaults = buildScheduledFaults(request);

    // 校验针对「已声明的时刻」做静态判定；auto-timing 会在运行时移动区间，
    // 且校验脚本的 native 结构要求 testSuite 不带 mode 属性。临时 XML 固定用 fixed-time。
    String autoTimingMode = "fixed-time";
    Double cvThreshold = request.getCvThreshold();
    writeWorkerXml(workerPath, request, isTpcc, autoTimingMode, cvThreshold);
    writeFaultScheduleXml(faultPath, scheduledFaults, autoTimingMode);
    writeConfigXml(configPath, request, scheduledFaults, "../" + workerFileName, templatePath,
        isTpcc, isMysql, isPostgres, isOceanBase, isGaussdb, autoTimingMode);

    return new PreparedCheck(checkRoot, faultPath, configPath, runId);
  }

  /** 删除一次编排校验生成的临时目录；删除失败只记日志。 */
  public void cleanup(PreparedCheck prepared) {
    if (prepared == null || prepared.directory() == null) {
      return;
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(prepared.directory())) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          LOG.warn("清理编排校验临时文件失败：{}", path, e);
        }
      });
    } catch (IOException e) {
      LOG.warn("遍历编排校验临时目录失败：{}", prepared.directory(), e);
    }
  }

  /**
   * 组合运行名（= historyName）。前缀必须保持 workload 名不变，否则
   * /bench/result、/bench/event、history/list 的 startsWith(testSuiteName) 过滤会查不到这一轮。
   * 表单给了 testName（完整测试名，例如 og-local_combo-A）时追加在前缀之后，便于检索归档。
   * 目录名里保留中文（便于阅读），其余非法字符替换为 -。
   */
  private static String buildRunId(String workloadName, String testName) {
    String prefix = workloadName;
    if (StringUtils.isNotBlank(testName)) {
      String candidate = testName.trim().replaceAll("[^A-Za-z0-9_.+\\-\\u4e00-\\u9fa5]", "-");
      prefix = candidate.startsWith(workloadName) ? candidate : workloadName + "_" + candidate;
    }
    return prefix + "_" + LocalDateTime.now().format(RUN_ID_FORMATTER);
  }

  private void validateRequest(StartByFormRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    String workload = normalizeWorkloadName(request.getWorkload().getName());
    if (!"smallbank".equals(workload) && !"tpcc".equals(workload)) {
      throw new IllegalArgumentException("当前 startByForm 仅支持 smallbank 和 tpcc 工作负载");
    }
    String dbType = normalizeLower(request.getDbType());
    String deploymentType = normalizeLower(request.getDatabase().getDeploymentType());
    if (!"standalone".equals(deploymentType) && !("distributed".equals(deploymentType) && ("oceanbase".equals(dbType) || "gaussdb".equals(dbType)))) {
      throw new IllegalArgumentException("当前 startByForm 支持 standalone，或 OceanBase/GaussDB 分布式部署类型");
    }
    if (!"opengauss".equals(dbType) && !"mysql".equals(dbType) && !"postgresql".equals(dbType) && !"oceanbase".equals(dbType) && !"gaussdb".equals(dbType)) {
      throw new IllegalArgumentException("当前 startByForm 仅支持 opengauss、mysql、postgresql、oceanbase 和 gaussdb 数据库类型");
    }
    if ("oceanbase".equals(dbType) && "tpcc".equals(workload)) {
      throw new IllegalArgumentException("当前 OceanBase 表单启动暂只支持 smallbank 工作负载");
    }

    DatabaseEndpoint endpoint = activeDatabaseEndpoint(request);
    if (StringUtils.isBlank(endpoint.ip())
        || StringUtils.isBlank(endpoint.port())
        || StringUtils.isBlank(endpoint.username())
        || StringUtils.isBlank(endpoint.password())) {
      throw new IllegalArgumentException("数据库连接信息不完整");
    }
  }

  private List<FaultScheduleItem> buildScheduledFaults(StartByFormRequest request) {
    List<StartByFormRequest.FaultItem> faults = request.getFaults();
    if (faults == null || faults.isEmpty()) {
      return List.of();
    }

    // @auto: 哨兵的解析器：惰性连库，一次运行只连一次（多个故障共用同一份结果）。
    // 没有任何故障写 @auto: 时它一次库都不会连。
    FileFaultTargetService.SentinelResolver sentinelResolver = fileFaultTargetService.resolverFor(request);

    Map<String, List<StartByFormRequest.FaultItem>> groups = new LinkedHashMap<>();
    int index = 0;
    for (StartByFormRequest.FaultItem fault : faults) {
      String groupKey = StringUtils.defaultIfBlank(fault.getGroupKey(), "fault-" + index);
      groups.computeIfAbsent(groupKey, key -> new ArrayList<>()).add(fault);
      index++;
    }

    List<FaultScheduleItem> scheduledFaults = new ArrayList<>();
    int currentPlanning = 60;
    int caseId = 1;
    for (Map.Entry<String, List<StartByFormRequest.FaultItem>> entry : groups.entrySet()) {
      String groupKey = entry.getKey();
      List<StartByFormRequest.FaultItem> groupFaults = entry.getValue();
      // 整组都没有 planningSeconds 时完全走原来的分支（groupEnd 从 currentPlanning 起算、
      // 下一组 = groupEnd + 30），保证历史编排的时刻逐字节不变。只有绝对时刻组才需要
      // 从 0 起算并让下一组取 max，避免早于当前基准的绝对时刻被抬高。
      boolean absoluteGroup = groupFaults.stream()
          .anyMatch(fault -> fault.getPlanningSeconds() != null);
      int groupEnd = absoluteGroup ? 0 : currentPlanning;
      for (StartByFormRequest.FaultItem fault : groupFaults) {
        int delaySeconds = Math.max(0, intValue(fault.getDelaySeconds(), 0));
        FaultTemplate template = buildFaultTemplate(request, fault, caseId, sentinelResolver);
        // planningSeconds 是绝对时刻：写了就直接用，不再叠加组基准，也不受前一组 +30 的影响。
        // 没写时保持原有 fixed 编排的组内相对偏移语义。
        Integer absolutePlanning = fault.getPlanningSeconds();
        int planning = absolutePlanning != null
            ? Math.max(0, absolutePlanning)
            : currentPlanning + delaySeconds;
        groupEnd = Math.max(groupEnd, planning + template.durationSeconds());
        scheduledFaults.add(
            new FaultScheduleItem(
                caseId,
                template.description(),
                planning,
                template.durationSeconds(),
                template.injectionCommand(),
                template.recoveryCommand(),
                template.autoRecovery(),
                template.checkCommand(),
                template.asyncInjection(),
                groupKey));
        caseId++;
      }
      currentPlanning = absoluteGroup
          ? Math.max(currentPlanning, groupEnd + 30)
          : groupEnd + 30;
    }
    return scheduledFaults;
  }

  private FaultTemplate buildFaultTemplate(StartByFormRequest request, StartByFormRequest.FaultItem fault,
      int caseId, FileFaultTargetService.SentinelResolver sentinelResolver) {
    String nameKey = normalizeLower(fault.getNameKey());
    // @auto: 哨兵在这里换成真实路径（查库，所以重灌数据也不会过期）；
    // 不以 @auto: 开头的值原样透传，所以现有手写死路径的编排行为完全不变。
    // 例外：这一轮会 create / load / reload 时**故意不换** —— 文件号要等 job 建完表、灌完数据
    // 才生成，此刻解析出来的必然过期。哨兵原样写进配置，由 job 在建完之后补
    // （DBWorkload#resolveDeferredFaultTargets）。
    // 解析不了就抛 IllegalArgumentException，由 controller 变成一条人话原因、拒绝这一轮。
    Map<String, String> params = sentinelResolver.resolveAll(
        fault.getParams() == null ? Map.of() : fault.getParams());
    DatabaseEndpoint endpoint = activeDatabaseEndpoint(request);
    String dbType = normalizeLower(request.getDbType());
    boolean isMysql = "mysql".equals(dbType);
    boolean isPostgres = "postgresql".equals(dbType);
    boolean isOceanBase = "oceanbase".equals(dbType);
    boolean isGaussdb = "gaussdb".equals(dbType);
    String dbUrl = buildStandaloneUrl(endpoint.ip(), endpoint.port(), "smallbank", isMysql, isPostgres, isOceanBase, isGaussdb);
    String dbChaosType = (isMysql || isOceanBase) ? "mysql" : isPostgres ? "postgresql" : "opengauss";
    String dbChaosUrl;
    if (isMysql || isOceanBase) {
      dbChaosUrl = "jdbc:mysql://" + endpoint.ip() + ":" + endpoint.port() + (isOceanBase ? "/oceanbase" : "/resbench_demo");
    } else {
      // opengauss/gaussdb/postgresql 都通过 postgresql 协议连接 DBChaos
      dbChaosUrl = "jdbc:postgresql://" + endpoint.ip() + ":" + endpoint.port() + "/postgres";
    }

    // 配置化优先（fault-cases.yaml）：加新故障只改 yaml，不再硬编码 switch
    FaultCatalogLoader.FaultDefinition catalogDef = catalogLoader.get(nameKey).orElse(null);
    if (catalogDef != null
        && catalogDef.injectionTemplate() != null
        && !catalogDef.injectionTemplate().trim().isEmpty()) {
      return buildFromCatalog(catalogDef, params, request, endpoint, dbChaosType, dbChaosUrl);
    }

    FaultTemplate template = switch (nameKey) {
      case "faultinject.fault.cpu.load" -> new FaultTemplate(
          "CPU Load",
          durationFromParams(params, 60),
          command("/opt/chaosblade/blade", "create", "cpu", "fullload"),
          command("/opt/chaosblade/blade", "destroy", "cpu", "fullload"),
          0);
      case "faultinject.fault.memory.load" -> memLoadTemplate(params, request.getContainerName());
      // 网络类故障一律 autoRecovery=1：只靠 chaosblade 自己的 --timeout 到点自毁（见下方 withTimeout），
      // 引擎不再另排一次 destroy。原因是注入命令很可能卡在链路上几十秒（netem 就打在承载控制通道的
      // 网卡上，HTTP 应答被丢/被拖），而引擎的 destroy 是按"注入开始时刻 + 时长"的绝对时刻排的，
      // 于是出现"故障刚建好 1 秒就被拆"，同时日志里留下一对毫无意义的 create/destroy。
      case "faultinject.fault.network.package" -> new FaultTemplate(
          "Network Packet Loss",
          durationFromParams(params, 60),
          withOptional(
              command("/opt/chaosblade/blade", "create", "network", "loss",
                  "--percent", valueOrDefault(params, "percent", "10"),
                  "--interface", valueOrDefault(params, "interface", "ens17f0")),
              "--destination-ip", params.get("destination-ip")),
          withOptional(
              command("/opt/chaosblade/blade", "destroy", "network", "loss",
                  "--percent", valueOrDefault(params, "percent", "10"),
                  "--interface", valueOrDefault(params, "interface", "ens17f0")),
              "--destination-ip", params.get("destination-ip")),
          1);
      case "faultinject.fault.network.delay" -> new FaultTemplate(
          "Network Delay",
          durationFromParams(params, 60),
          command("/opt/chaosblade/blade", "create", "network", "delay",
              "--time", valueOrDefault(params, "time", "200"),
              "--interface", valueOrDefault(params, "interface", "ens17f0")),
          command("/opt/chaosblade/blade", "destroy", "network", "delay",
              "--time", valueOrDefault(params, "time", "200"),
              "--interface", valueOrDefault(params, "interface", "ens17f0")),
          1);
      case "faultinject.fault.network.unorder" -> new FaultTemplate(
          "Network Reorder",
          durationFromParams(params, 60),
          buildReorderCommand(params, "create"),
          buildReorderCommand(params, "destroy"),
          1,
          buildNetworkReorderCheck(
              valueOrDefault(params, "interface", "ens17f0"),
              valueOrDefault(params, "percent", "50")));
      case "faultinject.fault.network.duplicate" -> new FaultTemplate(
          "Network Duplicate",
          durationFromParams(params, 60),
          buildDuplicateCommand(params, "create"),
          buildDuplicateCommand(params, "destroy"),
          1);
      // 包损坏（tc netem corrupt）：按比例翻转报文比特，接收端校验失败后丢弃，
      // 表现和丢包同源（重传/RTO 上升），但 netem 不统计 corrupt，
      // qdisc 的 dropped 恒为 0，所以判据只能用 TCP 后果（见 resbench_batch.py）。
      case "faultinject.fault.network.corrupt" -> new FaultTemplate(
          "Network Packet Corrupt",
          durationFromParams(params, 60),
          withOptional(
              command("/opt/chaosblade/blade", "create", "network", "corrupt",
                  "--percent", valueOrDefault(params, "percent", "10"),
                  "--interface", valueOrDefault(params, "interface", "ens17f0")),
              "--destination-ip", params.get("destination-ip")),
          withOptional(
              command("/opt/chaosblade/blade", "destroy", "network", "corrupt",
                  "--percent", valueOrDefault(params, "percent", "10"),
                  "--interface", valueOrDefault(params, "interface", "ens17f0")),
              "--destination-ip", params.get("destination-ip")),
          1);
      case "faultinject.fault.disk.fill" -> new FaultTemplate(
          "Disk Fill",
          durationFromParams(params, 60),
          command("/opt/chaosblade/blade", "create", "disk", "fill",
              "--path", valueOrDefault(params, "path", "/tmp"),
              "--percent", valueOrDefault(params, "percent", "90")),
          // 撤销必须按 uid，不能写 "destroy disk fill"：chaosblade 的 disk fill 撤销
          // （exec-os stopFill）会 GetPidsByProcessName("disk fill") 后 kill -9，
          // 而 "blade destroy disk fill ..." 这条命令行本身就命中该子串 ——
          // 与 --timeout 到点拉起的那条撤销互相残杀，agent 只拿到 Error 137、回执为空，
          // 空间其实已释放却被记成恢复失败（tpcc_20261024_125818 及 22 次归档同此）。
          // uid 由注入返回，执行时 FaultInjector 把 {{uid}} 换成各端点自己的 uid。
          command("/opt/chaosblade/blade", "destroy", FaultInjector.UID_PLACEHOLDER),
          0);
      case "faultinject.fault.disk.filemove" -> buildFileMoveTemplate(params);
      case "faultinject.fault.disk.fileremoved" -> new FaultTemplate(
          "File Delete",
          durationFromParams(params, 60),
          command("/opt/chaosblade/blade", "create", "file", "delete",
              "--filepath", valueOrDefault(params, "file-path", "/tmp/target.log")),
          command("/opt/chaosblade/blade", "destroy", "file", "delete",
              "--filepath", valueOrDefault(params, "file-path", "/tmp/target.log")),
          0);
      case "faultinject.fault.disk.fileappend" -> new FaultTemplate(
          "File Append",
          durationFromParams(params, 60),
          command("/opt/chaosblade/blade", "create", "file", "append",
              "--filepath", valueOrDefault(params, "filepath", "/tmp/append.log"),
              "--content", valueOrDefault(params, "content", "chaosblade append test")),
          command("/opt/chaosblade/blade", "destroy", "file", "append",
              "--filepath", valueOrDefault(params, "filepath", "/tmp/append.log")),
          0);
      case "faultinject.fault.disk.filechmod" -> buildFileChmodTemplate(params);
      case "faultinject.fault.process.stop" -> {
        String processCmd = valueOrDefault(params, "process-cmd", defaultProcessCmd(request));
        yield new FaultTemplate(
            "Process Stop",
            durationFromParams(params, 30),
            command("/opt/chaosblade/blade", "create", "process", "stop",
                "--process-cmd", processCmd),
            command("/opt/chaosblade/blade", "destroy", "process", "stop",
                "--process-cmd", processCmd),
            0,
            buildProcessStopCheck(processCmd));
      }
      case "faultinject.fault.process.kill" -> processKillTemplate(params, request);
      case "faultinject.fault.connection.surge" -> new FaultTemplate(
          "Database Connection Storm",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "max_connection",
              "-mode", "conn_storm",
              "-count", valueOrDefault(params, "concurrent-connections", "400"),
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.connection.limit.exceeded" -> new FaultTemplate(
          "Database Connection Exhaustion",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "max_connection",
              "-mode", "conn_exhaustion",
              "-count", valueOrDefault(params, "attempted-connections", "400"),
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.transaction.stack.overflow" -> new FaultTemplate(
          "Transaction Stack Overflow",
          durationFromParams(params, 30),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "stack_overflow",
              "-mode", mapStackOverflowType(valueOrDefault(params, "transaction-type", "trans_recurse")),
              "-duration", String.valueOf(durationFromParams(params, 30) * 1000),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.transaction.massive.rollback" -> new FaultTemplate(
          "Massive Transaction Rollback Storm",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "massive_rollback",
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-threads", valueOrDefault(params, "threads", "16"),
              "-rate", rollbackRate(valueOrDefault(params, "rollback-rate", "80")),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      
      case "faultinject.fault.connection.thread_saturation" -> new FaultTemplate(
          "Database Connection Thread Saturation",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "max_connection",
              "-mode", "thread_saturation",
              "-count", valueOrDefault(params, "concurrent-connections", "32"),
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.sql.plan_flip" -> new FaultTemplate(
          "SQL Plan Flip",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "sql", "plan_flip",
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-threads", valueOrDefault(params, "threads", "16"),
              "-count", valueOrDefault(params, "count", "1000000"),
              "-interval", valueOrDefault(params, "interval", "60000"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.sql.stack_overflow.sql_depth" -> new FaultTemplate(
          "SQL Expression Stack Overflow",
          durationFromParams(params, 30),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "sql", "stack_overflow",
              "-mode", "sql_depth",
              "-duration", String.valueOf(durationFromParams(params, 30) * 1000),
              "-interval", valueOrDefault(params, "interval", "1000"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.sql.stack_overflow.view_nest" -> new FaultTemplate(
          "SQL View Nest Stack Overflow",
          durationFromParams(params, 30),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "sql", "stack_overflow",
              "-mode", "view_nest",
              "-duration", String.valueOf(durationFromParams(params, 30) * 1000),
              "-interval", valueOrDefault(params, "interval", "1000"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.sql.stack_overflow.join_bomb" -> new FaultTemplate(
          "SQL Join Bomb Stack Overflow",
          durationFromParams(params, 30),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "sql", "stack_overflow",
              "-mode", "join_bomb",
              "-duration", String.valueOf(durationFromParams(params, 30) * 1000),
              "-interval", valueOrDefault(params, "interval", "1000"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.exec.stack_overflow.func_recurse" -> new FaultTemplate(
          "Exec Function Stack Overflow",
          durationFromParams(params, 30),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "exec", "stack_overflow",
              "-mode", "func_recurse",
              "-duration", String.valueOf(durationFromParams(params, 30) * 1000),
              "-interval", valueOrDefault(params, "interval", "1000"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.exec.stack_overflow.proc_recurse" -> new FaultTemplate(
          "Exec Procedure Stack Overflow",
          durationFromParams(params, 30),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "exec", "stack_overflow",
              "-mode", "proc_recurse",
              "-duration", String.valueOf(durationFromParams(params, 30) * 1000),
              "-interval", valueOrDefault(params, "interval", "1000"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.exec.read_amp_trap" -> new FaultTemplate(
          "Exec Read Amplification Trap",
          durationFromParams(params, 120),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "exec", "read_amp_trap",
              "-duration", String.valueOf(durationFromParams(params, 120) * 1000),
              "-warmup", valueOrDefault(params, "warmup", "30000"),
              "-mutators", valueOrDefault(params, "mutators", "8"),
              "-scanners", valueOrDefault(params, "scanners", "4"),
              "-rows", valueOrDefault(params, "rows", "30000"),
              "-payload-kb", valueOrDefault(params, "payload-kb", "2"),
              "-scan-mode", valueOrDefault(params, "scan-mode", "mixed"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.txn.uncommitted_txn" -> new FaultTemplate(
          "Uncommitted Transaction Lock",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "txn", "uncommitted_txn",
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-table", valueOrDefault(params, "table", "bmsql_stock"),
              "-holders", valueOrDefault(params, "holders", "2"),
              "-rows", valueOrDefault(params, "rows", "500"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.txn.duplicate_txn" -> new FaultTemplate(
          "Duplicate Transaction Conflict",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "txn", "duplicate_txn",
              "-sessions", valueOrDefault(params, "sessions", "50"),
              "-duration", String.valueOf(durationFromParams(params, 60)),
              "-mode", valueOrDefault(params, "mode", "UPDATE"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.txn.deadlock_storm" -> new FaultTemplate(
          "Deadlock Storm",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "txn", "deadlock_storm",
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-table", valueOrDefault(params, "table", "bmsql_stock"),
              "-anchors", valueOrDefault(params, "anchors", "2"),
              "-anchor-rows", valueOrDefault(params, "anchor-rows", "20"),
              "-waiters", valueOrDefault(params, "waiters", "16"),
              "-hot-rows", valueOrDefault(params, "hot-rows", "32"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.txn.max_prepared" -> new FaultTemplate(
          "Max Prepared Transaction",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "txn", "max_prepared",
              "-count", valueOrDefault(params, "count", "201"),
              "-duration", String.valueOf(durationFromParams(params, 60)),
              "-concurrency", valueOrDefault(params, "concurrency", "50"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.storage.mvcc_bloat" -> new FaultTemplate(
          "MVCC Bloat",
          durationFromParams(params, 120),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "storage", "mvcc_bloat",
              "-duration", String.valueOf(durationFromParams(params, 120) * 1000),
              "-anchors", valueOrDefault(params, "anchors", "1"),
              "-mutators", valueOrDefault(params, "mutators", "8"),
              "-rows", valueOrDefault(params, "rows", "20000"),
              "-payload-kb", valueOrDefault(params, "payload-kb", "2"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);
      case "faultinject.fault.buffer.memory_pressure" -> new FaultTemplate(
          "Buffer Memory Pressure",
          durationFromParams(params, 60),
          command("java", "-jar", DBCHAOS_JAR, dbChaosType, "buffer", "memory_pressure",
              "-duration", String.valueOf(durationFromParams(params, 60) * 1000),
              "-batch", valueOrDefault(params, "batch", "100"),
              "-threads", valueOrDefault(params, "threads", "4"),
              "-url", dbChaosUrl,
              "-user", endpoint.username(),
              "-password", endpoint.password()),
          null,
          1);

      default -> throw new IllegalArgumentException("暂不支持的故障类型: " + fault.getNameKey() + " (caseId=" + caseId + ")");
    };

    // 容器内注入的目标容器（见 StartByFormRequest.containerName）。
    // 网络类故障不打进容器：容器网卡名与宿主机不同（宿主 ens17f0 vs 容器 eth0），
    // 且网络命名空间隔离，在容器内注入网络故障不达意，故网络故障一律走宿主机 agent。
    //
    // 磁盘写满同样走宿主机：disk fill 只是往 --path 里写填充文件，datadir 是 bind mount，
    // 写宿主路径和在容器内写是同一块盘，效果等价。而一旦容器被这组故障拖死，容器内的
    // chaos_os 和实验 uid 记录会一起消失，`--timeout` 与 `blade destroy` 都执行不了，
    // 填充文件就再也删不掉（2026-11-04 plan-000060：destroy 回 Error 137、stdout/stderr
    // 全空，容器连着重启 15 分钟）。改由宿主机 blade 注入后，注入、`--timeout`、撤销
    // 全在宿主进程里，不随容器生死。
    // 因此 disk fill 的 path 参数必须写宿主机路径（计划 YAML 已按此改写）：
    //   /var/lib/mysql         → /mnt/nvme4n1/mysql_data/data     (mysql8)
    //   /var/lib/postgresql/data → /mnt/nvme4n1/docker_pg10_data  (pg_tpcc32)
    String containerName = request.getContainerName();
    boolean isHostOnlyFault = nameKey.startsWith("faultinject.fault.network.")
        || "faultinject.fault.disk.fill".equals(nameKey);

    // 为 chaosblade 类型的故障注入添加 --timeout 参数，到期自动恢复
    // （容器内 mem load 走 /opt/chaosblade/blade-cg 包装脚本，参数与原版 blade 一致）
    if (isBladeCommand(template.injectionCommand().cmd())) {
      int dur = template.durationSeconds();
      template = new FaultTemplate(
          template.description(), dur,
          withTimeout(template.injectionCommand(), dur),
          template.recoveryCommand(),
          template.autoRecovery(),
          // 必须带上 checkCommand：这里用的是 6 参构造。写成 5 参会走「没有自检」那个
          // 重载，把 File Move / File Chmod / Network Reorder 等模板里已经建好的 <check>
          // 悄悄丢成 null —— 表现就是生成的 XML 里没有 check 元素、归档里写
          // 「未配置自检命令（无 check 模板）」，却在代码里明明能看到 buildXxxCheck。
          template.checkCommand());
      // 指定了容器名就在容器内执行（注入与恢复都套 docker exec）。
      // DBChaos（java -jar ...）不在此列：它通过 JDBC 连库，不需要在容器内跑。
      if (StringUtils.isNotBlank(containerName) && !isHostOnlyFault) {
        template = withDockerExec(template, containerName.trim());
      } else if (StringUtils.isNotBlank(containerName)) {
        LOG.warn(
            "故障 {} 不走容器内注入，已在宿主机执行（containerName={} 被忽略）",
            nameKey,
            containerName);
      }
    }
    // v2.2：DBChaos（java -jar ...）持续型注入异步下发（agent /inject dispatch，启动即返回）；
    // chaosblade 保持同步（wait，返回即断言）。yaml 路径用 asyncInjection 显式控制。
    if ("java".equals(template.injectionCommand().cmd())) {
      template = withAsync(template, true);
    }
    return template;
  }

  private FaultTemplate withAsync(FaultTemplate t, boolean async) {
    return new FaultTemplate(
        t.description(), t.durationSeconds(), t.injectionCommand(),
        t.recoveryCommand(), t.autoRecovery(), t.checkCommand(), async);
  }

  /**
   * 内存负载故障。只有一个参数 {@code memory-percent}。
   *
   * <p>chaosblade 的 {@code --mem-percent} 默认读 /proc/meminfo 的 total / available，
   * 算的是「整台机器」（{@code reserve = total*(100-p)/100}，{@code alloc = available - reserve}）。
   * Docker 默认不隔离 /proc/meminfo，所以容器内执行时也是宿主机口径：40 GiB 上限的容器里
   * 配 90，算出来要分配几百 GB，注入进程撞上 cgroup 上限被 OOM，数据库基本不受影响。
   *
   * <p>容器里要按 cgroup 口径计算，需要 {@code --channel nsexec --ns_target <pid>}
   * （exec-os ≥1.6.0）+ 容器内 chaosblade ≥1.7.4：{@code --ns_target} 是 chaos_os 的
   * 隐藏 flag，1.7.4 的 yaml 规格里也没有，必须在容器内
   * {@code yaml/chaosblade-os-spec-<ver>.yaml} 的 mem load 里手工补一条
   * （见 scripts/chaosblade/README.md），否则 CLI 会先报 {@code unknown flag: --ns_target}。
   * 另外容器用宿主 cgroup 命名空间时路径是 {@code /sys/fs/cgroup/memory/docker/<id>}，
   * 与容器内实际挂载不符，要靠 {@code /opt/chaosblade/blade-cg} 包装脚本现取 id、建符号链接
   * 并追加 {@code --cgroup-root}。
   *
   * <p>恢复：chaosblade 的 {@code blade destroy} 只接受 uid，{@code destroy mem load ...}
   * 这类按 target/action 的写法会把 "mem" 当 uid 查库而失败，所以这里不排恢复命令，
   * 改为 autoRecovery=1，靠注入时自动追加的 {@code --timeout} 到点自毁。
   */
  private static FaultTemplate memLoadTemplate(Map<String, String> params, String containerName) {
    int duration = durationFromParams(params, 60);
    String percent = valueOrDefault(params, "memory-percent", "80");
    boolean inContainer = StringUtils.isNotBlank(containerName);
    List<String> args = new ArrayList<>(List.of(
        "create", "mem", "load", "--mode", "ram", "--mem-percent", percent));
    if (inContainer) {
      // 读取容器 cgroup（容器口径）而不是宿主机 /proc/meminfo（整机口径）
      args.addAll(List.of("--channel", "nsexec", "--ns_target", "1"));
    }
    return new FaultTemplate(
        "Memory Load",
        duration,
        new FaultCommand(inContainer ? BLADE_CONTAINER_CMD : BLADE_CMD, args),
        null,
        1);
  }

  /** 从 yaml 目录渲染故障命令（注入/恢复/自检），设计文档 §5.2。 */
  private FaultTemplate buildFromCatalog(
      FaultCatalogLoader.FaultDefinition def,
      Map<String, String> params,
      StartByFormRequest request,
      DatabaseEndpoint endpoint,
      String dbChaosType,
      String dbChaosUrl) {
    Map<String, String> context = new LinkedHashMap<>();
    // 上下文变量清单见 FaultCatalogLoader.CONTEXT_VARIABLES
    context.put(FaultCatalogLoader.CTX_DB_TYPE, dbChaosType);
    context.put(FaultCatalogLoader.CTX_DB_URL, dbChaosUrl);
    context.put(FaultCatalogLoader.CTX_USER, endpoint.username());
    context.put(FaultCatalogLoader.CTX_PASSWORD, endpoint.password());
    context.put(FaultCatalogLoader.CTX_DBCHAOS_JAR, DBCHAOS_JAR);
    context.put(FaultCatalogLoader.CTX_AGENT_HOST, resolveAgentEndpoint(request));
    // 派生变量：massive_rollback 的 -rate 需要百分数 → 小数
    context.put(FaultCatalogLoader.CTX_ROLLBACK_RATE, rollbackRate(valueOrDefault(params, "rollback-rate", "80")));

    int duration = Math.max(1, intValue(params.get("duration"), def.defaultDuration()));
    FaultCommand injection = renderCommand(def.injectionTemplate(), params, context);
    FaultCommand recovery =
        def.recoveryTemplate() == null || def.recoveryTemplate().trim().isEmpty()
            ? null
            : renderCommand(def.recoveryTemplate(), params, context);
    FaultCommand check =
        def.checkTemplate() == null || def.checkTemplate().trim().isEmpty()
            ? null
            : renderCommand(def.checkTemplate(), params, context);
    return new FaultTemplate(def.plainName(), duration, injection, recovery, def.autoRecovery(), check,
        def.asyncInjection());
  }

  /** 渲染单条命令模板为 {@link FaultCommand}（按空白拆分为 argv）。 */
  private static FaultCommand renderCommand(
      String template, Map<String, String> params, Map<String, String> context) {
    String rendered = FaultTemplateEngine.render(template, params, context);
    List<String> tokens =
        java.util.Arrays.stream(rendered.split("\\s+")).filter(t -> !t.isEmpty()).collect(java.util.stream.Collectors.toList());
    if (tokens.isEmpty()) {
      throw new IllegalArgumentException("渲染后的命令为空，模板: " + template);
    }
    return new FaultCommand(tokens.get(0), tokens.subList(1, tokens.size()));
  }

  /** 是否为 chaosblade 命令（原版 blade 或容器内包装脚本 blade-cg，两者参数一致）。 */
  private static boolean isBladeCommand(String cmd) {
    return BLADE_CMD.equals(cmd) || BLADE_CONTAINER_CMD.equals(cmd);
  }

  private static FaultCommand withTimeout(FaultCommand command, int durationSeconds) {
    List<String> args = new ArrayList<>(command.args());
    args.add("--timeout");
    args.add(String.valueOf(durationSeconds));
    return new FaultCommand(command.cmd(), args);
  }

  /** 把注入/恢复/自检命令整体套一层 {@code docker exec <容器> ...}，在容器内执行。 */
  private static FaultTemplate withDockerExec(FaultTemplate template, String containerName) {
    return new FaultTemplate(
        template.description(),
        template.durationSeconds(),
        dockerExec(template.injectionCommand(), containerName),
        dockerExec(template.recoveryCommand(), containerName),
        template.autoRecovery(),
        dockerExec(template.checkCommand(), containerName),
        template.asyncInjection());
  }

  private static FaultCommand dockerExec(FaultCommand command, String containerName) {
    if (command == null) {
      return null;
    }
    // 保持 argv 以独立元素传递，不拼成单个带引号的字符串：agent 端是
    // subprocess.Popen(list) 直接 exec、不经过 shell，docker exec 也不做 shell 拆分，
    // 拼单字符串会导致容器内找不到可执行文件。
    List<String> args = new ArrayList<>();
    args.add("exec");
    args.add(containerName);
    args.add(command.cmd());
    args.addAll(command.args());
    return new FaultCommand("docker", args);
  }

  private void writeWorkerXml(Path workerPath, StartByFormRequest request, boolean isTpcc, String autoTimingMode, Double cvThreshold) throws Exception {
    Document document = newDocument();
    Element works = document.createElement("works");
    document.appendChild(works);

    Element work = document.createElement("work");
    work.setAttribute("arrival", "REGULAR");
    appendTextElement(document, work, "time", valueOrDefault(request.getWorkload().getParams(), "duration", "120"));
    appendTextElement(document, work, "rate", "100000");
    String weights = isTpcc
        ? "45,43,4,4,4"
        : buildSmallbankWeights(request.getWorkload().getParams());
    appendTextElement(document, work, "weights", weights);
    appendTextElement(document, work, "testSuite", GENERATED_SUITE_NAME);
    if (!"fixed-time".equals(autoTimingMode)) {
      boolean dynamicEnabled = "dynamic-threshold".equals(autoTimingMode);
      Element autoTiming = document.createElement("autoTiming");
      appendTextElement(document, autoTiming, "dynamicThresholdEnabled", String.valueOf(dynamicEnabled));
      appendTextElement(document, autoTiming, "dynamicBaselineChecks", "30");
      appendTextElement(document, autoTiming, "dynamicThresholdScaleK", "1.0");
      appendTextElement(document, autoTiming, "dynamicThresholdLowerBound", "0.08");
      appendTextElement(document, autoTiming, "dynamicThresholdUpperBound", "0.35");
      appendTextElement(document, autoTiming, "windowSize", "31");
      String cvThreshStr = (cvThreshold != null && cvThreshold > 0)
          ? String.format("%.2f", cvThreshold / 100.0)
          : "0.08";
      appendTextElement(document, autoTiming, "cvThreshold", cvThreshStr);
      appendTextElement(document, autoTiming, "checkIntervalMs", "5001");
      appendTextElement(document, autoTiming, "maxWaitMs", "300000");
      // 与 FaultInjector.DEFAULT_ROUND_TRIP_TIMEOUT_MS 同一个数：请求发出后这么久没收到
      // agent 回复就判「未应答」。写死字面量的话，改默认值时会漏掉这条路径。
      appendTextElement(
          document, autoTiming, "injectTimeoutMs",
          String.valueOf(AutoTimingConfig.DEFAULT_INJECT_TIMEOUT_MS));
      appendTextElement(
          document, autoTiming, "recoverTimeoutMs",
          String.valueOf(AutoTimingConfig.DEFAULT_RECOVER_TIMEOUT_MS));
      work.appendChild(autoTiming);
    }
    works.appendChild(work);

    writeDocument(document, workerPath);
  }

  private void writeFaultScheduleXml(Path faultPath, List<FaultScheduleItem> scheduledFaults, String autoTimingMode) throws Exception {
    Document document = newDocument();
    Element testSuites = document.createElement("testSuites");
    document.appendChild(testSuites);

    Element testSuite = document.createElement("testSuite");
    testSuite.setAttribute("name", GENERATED_SUITE_NAME);
    if (!"fixed-time".equals(autoTimingMode)) {
      testSuite.setAttribute("mode", "auto-timing");
    }
    testSuites.appendChild(testSuite);

    for (FaultScheduleItem item : scheduledFaults) {
      Element caseElement = document.createElement("case");
      caseElement.setAttribute("id", String.valueOf(item.id()));
      appendTextElement(document, caseElement, "description", item.description());
      appendTextElement(document, caseElement, "planning", String.valueOf(item.planningSeconds()));
      appendTextElement(document, caseElement, "during", String.valueOf(item.durationSeconds()));
      if (item.groupKey() != null) {
        appendTextElement(document, caseElement, "groupKey", item.groupKey());
      }
      testSuite.appendChild(caseElement);
    }

    writeDocument(document, faultPath);
  }

  private void writeConfigXml(Path configPath, StartByFormRequest request, List<FaultScheduleItem> scheduledFaults, String workerHref, String templatePath, boolean isTpcc, boolean isMysql, boolean isPostgres, boolean isOceanBase, boolean isGaussdb, String autoTimingMode)
      throws Exception {
    Document document = parseDocument(Path.of(templatePath));
    Element root = document.getDocumentElement();
    DatabaseEndpoint endpoint = activeDatabaseEndpoint(request);
    Map<String, String> params = request.getWorkload().getParams();
    String appName = isTpcc ? "tpcc" : "smallbank";

    replaceTextContent(root, "type", isOceanBase ? "OCEANBASE" : isMysql ? "MYSQL" : isPostgres ? "POSTGRES" : isGaussdb ? "GAUSSDB" : "OPENGAUSS");
    replaceTextContent(root, "driver", (isMysql || isOceanBase) ? "com.mysql.cj.jdbc.Driver" : isGaussdb ? "com.huawei.gaussdb.jdbc.Driver" : "org.postgresql.Driver");
    replaceTextContent(root, "url", buildStandaloneUrl(endpoint.ip(), endpoint.port(), appName, isMysql, isPostgres, isOceanBase, isGaussdb));
    replaceNodes(root, endpoint.ip(), endpoint.port(), isMysql, isOceanBase, isGaussdb);
    replaceTextContent(root, "username", endpoint.username());
    replaceTextContent(root, "password", endpoint.password());
    if (isTpcc) {
      int wh = intValue(params.get("warehouses"), 1);
      replaceTextContent(root, "terminals", valueOrDefault(params, "concurrency", "10"));
      replaceTextContent(root, "scalefactor", String.valueOf(wh));
    } else {
      replaceTextContent(root, "terminals", valueOrDefault(params, "terminals", "20"));
      replaceTextContent(root, "scalefactor", calculateScaleFactor(valueOrDefault(params, "accountsNum", "1000")));
    }

    replaceFaultCases(document, root, scheduledFaults, resolveAgentEndpoint(request), autoTimingMode);
    replaceInclude(document, root, workerHref);
    writeDocument(document, configPath);
  }

  private void replaceFaultCases(
      Document document, Element root, List<FaultScheduleItem> scheduledFaults, String agentEndpoint, String autoTimingMode) {
    Element faultCases = firstChild(root, "faultCases");
    if (faultCases == null) {
      faultCases = document.createElement("faultCases");
      root.appendChild(faultCases);
    }
    if (!"fixed-time".equals(autoTimingMode)) {
      faultCases.setAttribute("mode", "auto-timing");
    }
    clearChildren(faultCases);

    for (FaultScheduleItem item : scheduledFaults) {
      Element caseElement = document.createElement("case");
      appendTextElement(document, caseElement, "id", String.valueOf(item.id()));
      appendTextElement(document, caseElement, "description", item.description());

      Element agents = document.createElement("agents");
      appendTextElement(document, agents, "agent", agentEndpoint);
      caseElement.appendChild(agents);

      Element injectionEl = buildCommandElement(document, "injection", item.injectionCommand());
      if (item.asyncInjection()) {
        injectionEl.setAttribute("async", "true"); // v2.2：DBChaos → agent /inject dispatch
      }
      caseElement.appendChild(injectionEl);
      if (item.recoveryCommand() != null) {
        caseElement.appendChild(buildCommandElement(document, "recovery", item.recoveryCommand()));
      }
      if (item.checkCommand() != null) {
        caseElement.appendChild(buildCommandElement(document, "check", item.checkCommand()));
      }
      appendTextElement(document, caseElement, "autoRecovery", String.valueOf(item.autoRecovery()));
      faultCases.appendChild(caseElement);
    }
  }

  private String resolveAgentEndpoint(StartByFormRequest request) {
    DatabaseEndpoint endpoint = activeDatabaseEndpoint(request);
    if (StringUtils.isBlank(endpoint.ip())) {
      throw new IllegalArgumentException("无法确定故障注入 Agent 地址，缺少数据库 IP");
    }
    return endpoint.ip().trim() + ":" + DEFAULT_AGENT_PORT;
  }

  private Element buildCommandElement(Document document, String name, FaultCommand command) {
    Element element = document.createElement(name);
    appendTextElement(document, element, "cmd", command.cmd());
    for (String arg : command.args()) {
      appendTextElement(document, element, "arg", arg);
    }
    return element;
  }

  private void replaceInclude(Document document, Element root, String workerHref) {
    Element includeElement = null;
    NodeList nodes = root.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node instanceof Element element && "xi:include".equals(element.getNodeName())) {
        includeElement = element;
        break;
      }
    }
    if (includeElement == null) {
      includeElement = document.createElementNS(XI_NAMESPACE, "xi:include");
      Element transactionTypes = firstChild(root, "transactiontypes");
      if (transactionTypes != null) {
        root.insertBefore(includeElement, transactionTypes);
      } else {
        root.appendChild(includeElement);
      }
    }
    includeElement.setAttribute("href", workerHref);
    includeElement.setAttribute("parse", "xml");
  }

  private void replaceNodes(Element root, String ip, String port, boolean isMysql, boolean isOceanBase, boolean isGaussdb) {
    Element nodes = firstChild(root, "nodes");
    if (nodes == null) {
      nodes = root.getOwnerDocument().createElement("nodes");
      root.appendChild(nodes);
    }
    clearChildren(nodes);
    String jdbcUrl;
    if (isMysql || isOceanBase) {
      jdbcUrl = "jdbc:mysql://" + ip + ":" + port + (isOceanBase ? "/oceanbase" : "/resbench_demo");
    } else if (isGaussdb) {
      jdbcUrl = "jdbc:gaussdb://" + ip + ":" + port + "/postgres";
    } else {
      jdbcUrl = "jdbc:postgresql://" + ip + ":" + port + "/postgres";
    }
    appendTextElement(root.getOwnerDocument(), nodes, "node", jdbcUrl);
  }

  private static void clearChildren(Element element) {
    while (element.hasChildNodes()) {
      element.removeChild(element.getFirstChild());
    }
  }

  private Element firstChild(Element root, String tagName) {
    NodeList nodeList = root.getElementsByTagName(tagName);
    if (nodeList.getLength() == 0) {
      return null;
    }
    return (Element) nodeList.item(0);
  }

  private void replaceTextContent(Element root, String tagName, String value) {
    Element element = firstChild(root, tagName);
    if (element == null) {
      element = root.getOwnerDocument().createElement(tagName);
      root.appendChild(element);
    }
    element.setTextContent(value);
  }

  private String buildStandaloneUrl(String ip, String port, String appName, boolean isMysql, boolean isPostgres, boolean isOceanBase, boolean isGaussdb) {
    if (isMysql || isOceanBase) {
      String dbName = isOceanBase ? "oceanbase" : "resbench_demo";
      // rewriteBatchedStatements 必须开：不开时 Connector/J 的 executeBatch() 走 executeBatchSerially，
      // 批内每一行单独发送并等待回包（一行一次网络往返）——TPCC 装载单个 warehouse 的 stock 十万行
      // 就要几百秒，日志长时间不动、看起来像挂住。开了之后一批只往返一次（PG 驱动本来就是整批一次）。
      return "jdbc:mysql://" + ip + ":" + port
          + "/" + dbName + "?useSSL=false&useUnicode=true&characterEncoding=utf-8"
          + "&rewriteBatchedStatements=true"
          + "&connectTimeout=" + MYSQL_CONNECT_TIMEOUT_MILLIS
          + "&socketTimeout=" + MYSQL_SOCKET_TIMEOUT_MILLIS;
    }
    if (isGaussdb) {
      // GaussDB 单节点使用 gaussdb 协议和华为驱动（超时同样按秒，见上方常量注释）
      return "jdbc:gaussdb://" + ip + ":" + port + "/postgres?sslmode=disable&ApplicationName=" + appName + "&reWriteBatchedInserts=false&targetServerType=any"
          + "&connectTimeout=" + PG_CONNECT_TIMEOUT_SECONDS
          + "&socketTimeout=" + PG_SOCKET_TIMEOUT_SECONDS;
    }
    String dbName = isPostgres ? "postgres" : "postgres";
    String params = isPostgres
        ? "?sslmode=disable&connectTimeout=" + PG_CONNECT_TIMEOUT_SECONDS + "&socketTimeout=" + PG_SOCKET_TIMEOUT_SECONDS
        : "?sslmode=disable&ApplicationName=" + appName + "&reWriteBatchedInserts=false&targetServerType=any";
    return "jdbc:postgresql://" + ip + ":" + port + "/" + dbName + params;
  }

  private String buildSmallbankWeights(Map<String, String> params) {
    return String.join(",",
        valueOrDefault(params, "amalgamateWeight", "15"),
        valueOrDefault(params, "balanceWeight", "15"),
        valueOrDefault(params, "depositCheckingWeight", "15"),
        valueOrDefault(params, "sendPaymentWeight", "25"),
        valueOrDefault(params, "transactSavingsWeight", "15"),
        valueOrDefault(params, "writeCheckWeight", "15"));
  }

  private String calculateScaleFactor(String accountsNum) {
    BigDecimal value = new BigDecimal(accountsNum);
    BigDecimal scaleFactor = value.divide(BigDecimal.valueOf(10000), 4, RoundingMode.HALF_UP);
    return scaleFactor.stripTrailingZeros().toPlainString();
  }

  private static FaultCommand command(String cmd, String... args) {
    return new FaultCommand(cmd, List.of(args));
  }

  private static FaultCommand command(List<String> prefix, String cmd, String... args) {
    List<String> allArgs = new ArrayList<>();
    allArgs.addAll(prefix);
    allArgs.add(cmd);
    allArgs.addAll(List.of(args));
    return new FaultCommand(allArgs.get(0), allArgs.subList(1, allArgs.size()));
  }

  private static FaultCommand withOptional(FaultCommand command, String flag, String value) {
    if (StringUtils.isBlank(value)) {
      return command;
    }
    List<String> args = new ArrayList<>(command.args());
    args.add(flag);
    args.add(value);
    return new FaultCommand(command.cmd(), args);
  }

  private static FaultCommand buildReorderCommand(Map<String, String> params, String action) {
    FaultCommand cmd = command("/opt/chaosblade/blade", action, "network", "reorder",
        "--correlation", valueOrDefault(params, "correlation", "80"),
        "--percent", valueOrDefault(params, "percent", "50"),
        "--interface", valueOrDefault(params, "interface", "ens17f0"));
    cmd = withOptional(cmd, "--time", params.get("time"));
    cmd = withOptional(cmd, "--gap", params.get("gap"));
    cmd = withOptional(cmd, "--destination-ip", params.get("destination-ip"));
    return cmd;
  }

  /**
   * network reorder 的自检命令：直接读网卡上的 netem qdisc，而不是看 blade 的返回码。
   *
   * <p><b>为什么不能只看注入回执</b>：chaosblade 1.7.4 的 reorder 实际执行的是
   * {@code tc qdisc add dev <iface> root netem reorder 50% 80% delay 10ms}。网卡 root 位置
   * 已经有<b>非默认</b> qdisc 时这条命令会失败（{@code RTNETLINK answers: File exists}，
   * 退出码 2），但 blade 进程自己仍然 exit 0、只在 stdout 里打
   * {@code {"code":63063,"success":false}} —— agent 的 exec 只看进程退出码，注入因此被记成
   * “Executed: Successful”。2026-10-25 在隔离 veth 上复现过：先手工
   * {@code tc qdisc add dev <iface> root mq}（不论带不带 handle），同一条 blade 命令就报
   * File exists；全程不挂 qdisc 时同一命令成功，网卡上出现
   * {@code qdisc netem ... delay 10.0ms reorder 50% 80% gap 1}。
   *
   * <p>反过来，内核<b>默认</b>的 root（网卡上显示的 {@code qdisc mq 0:}，handle 0）可以被
   * tc add 直接替换：同一天 22:41 在 ens17f0 上注入成功，自检读到 4 秒 4811 个报文、
   * 1612 次重排。所以这条检查针对的主要是「上一轮故障/手工 tc 留下一个非默认 qdisc 把
   * add 挡住」这类情况，而不是网卡本身有默认 mq。
   *
   * <p><b>判据</b>（两次采样，间隔 4 秒，全部看 netem 自己的计数器）：
   * <ol>
   *   <li>输出里出现 {@code qdisc netem} —— 规则真的挂在这张网卡上；</li>
   *   <li>两次采样间 netem 的 Sent 报文数在涨 —— 规则挂在真有流量的网卡上
   *       （interface 配错时这里是 0，与「根本没注入」区分开）；</li>
   *   <li>两次采样间 requeues 在涨 —— 报文被判成需要重排
   *       （隔离 veth 实测 percent=50：3016 pkt 里 requeues 1451）；</li>
   *   <li>tc 里印的 reorder 比例等于下发值 —— 排除上一轮残留规则冒充本轮；</li>
   *   <li>delay &gt; 0 —— 没有时延就没有可打乱的顺序（blade 默认 10ms）。</li>
   * </ol>
   * 只统计 netem 块自己的计数器：mq/prio 的聚合 requeues 与重排无关，混进来会假阳性。
   *
   * <p>percent ≥ 80 时 netem 把绝大多数报文标成「立即发送」、delay 被打塌，对端几乎观察不到
   * 真乱序，而 requeues 照样涨。这种情况仍判 enforced（规则确实生效了），但 summary 里写明，
   * 免得把它当成「有乱序的流量」。
   */
  static FaultCommand buildNetworkReorderCheck(String iface, String percent) {
    String script = """
        dev=__DEV__
        want=__PERCENT__
        tc=/usr/sbin/tc
        [ -x "$tc" ] || tc=tc

        stat_of() {
          printf '%s\\n' "$1" | awk '
            /^qdisc / { netem = ($0 ~ /netem/); next }
            netem && $1 == "Sent" {
              if (match($0, /[0-9]+ pkt/)) pkt += substr($0, RSTART, RLENGTH - 4)
              if (match($0, /requeues [0-9]+[)]/)) hits += substr($0, RSTART + 9, RLENGTH - 10)
            }
            END { printf "%d %d\\n", pkt, hits }'
        }

        emit() {
          printf '{"enforced": %s, "summary": "%s", "checks": [{"name": "netem_present", "observed": "%s", "expected": "netem qdisc on %s", "matched": %s}, {"name": "traffic_through_netem", "observed": "%s", "expected": "> 0 pkt in 4s", "matched": %s}, {"name": "reorder_hits", "observed": "%s", "expected": "> 0 in 4s", "matched": %s}, {"name": "reorder_percent", "observed": "%s%%", "expected": "%s%%", "matched": %s}, {"name": "delay_positive", "observed": "%s", "expected": "> 0", "matched": %s}]}\\n' \\
            "$enforced" "$summary" "$obs_present" "$dev" "$present_m" "$dp" "$traffic_m" "$dh" "$hits_m" "${got_pct:-unknown}" "$want" "$pct_m" "${delay:-unknown}" "$delay_m"
        }

        s1=$("$tc" -s qdisc show dev "$dev" 2>/dev/null)
        if [ -z "$s1" ]; then
          echo "self-check cannot read qdisc of $dev (tc failed: device missing or no permission)" >&2
          exit 2
        fi

        st=$(stat_of "$s1"); p1=${st% *}; h1=${st#* }
        present=false
        case "$s1" in *"qdisc netem"*) present=true ;; esac
        got_pct=; delay=; p2=$p1; h2=$h1; present2=false; reset=false

        if [ "$present" = true ]; then
          line=$(printf '%s\\n' "$s1" | grep -m1 'qdisc netem')
          got_pct=$(printf '%s\\n' "$line" | awk 'match($0, /reorder [0-9.]+%/) { print substr($0, RSTART + 8, RLENGTH - 9); exit }')
          delay=$(printf '%s\\n' "$line" | awk 'match($0, /delay [0-9.]+(us|ms|s)/) { print substr($0, RSTART + 6, RLENGTH - 6); exit }')
          sleep 4
          s2=$("$tc" -s qdisc show dev "$dev" 2>/dev/null)
          case "$s2" in *"qdisc netem"*) present2=true ;; esac
          st=$(stat_of "$s2"); p2=${st% *}; h2=${st#* }
        fi

        dp=$((p2 - p1)); dh=$((h2 - h1))
        if [ "$dp" -lt 0 ] || [ "$dh" -lt 0 ]; then dp=0; dh=0; reset=true; fi

        dnum=$(printf '%s' "$delay" | sed 's/[a-z]*$//')
        delay_m=false
        if [ -n "$dnum" ] && awk -v d="$dnum" 'BEGIN { exit !(d > 0) }'; then delay_m=true; fi
        pct_m=false
        if [ -n "$got_pct" ] && awk -v a="$got_pct" -v b="$want" 'BEGIN { exit !(a == b) }'; then pct_m=true; fi
        traffic_m=false; [ "$dp" -gt 0 ] && traffic_m=true
        hits_m=false; [ "$dh" -gt 0 ] && hits_m=true
        obs_present=absent; [ "$present2" = true ] && obs_present=present
        present_m=$present2

        enforced=false
        if [ "$present2" = true ] && [ "$traffic_m" = true ] && [ "$hits_m" = true ] && [ "$delay_m" = true ] && [ "$reset" = false ]; then
          enforced=true
        fi

        root_qdisc=$(printf '%s\\n' "$s1" | awk 'NR == 1 { print $2 }')
        if [ "$present" = false ]; then
          summary="网卡 $dev 上没有 netem 规则（root qdisc 仍是 $root_qdisc）：chaosblade 的 tc qdisc add 被已存在的非默认 qdisc 挡住（File exists），注入没有挂上去"
        elif [ "$present2" = false ]; then
          summary="netem 规则在采样期间消失，注入没有持续生效"
        elif [ "$reset" = true ]; then
          summary="采样期间 netem 计数被重置（规则被重建），无法确认重排命中"
        elif [ "$dp" -le 0 ]; then
          summary="netem 已挂在 $dev 上，但 4 秒内没有报文经过（interface 可能配错，流量走的是别的网卡）"
        elif [ "$delay_m" = false ]; then
          summary="netem 的 delay 是 ${delay:-缺失}，没有时延就没有可打乱的顺序"
        elif [ "$dh" -le 0 ]; then
          summary="4 秒内有 $dp 个报文经过 netem，但没有报文被判为重排（percent 可能为 0）"
        else
          summary="netem 在 $dev 上生效：4 秒内 $dp 个报文经过，其中 $dh 个被判为重排（配置 reorder ${got_pct:-?}%）"
          if awk -v p="$want" 'BEGIN { exit !(p >= 80) }'; then
            summary="$summary；percent>=80 时对端几乎观察不到真乱序，判读请看 net.qdisc.delay"
          fi
        fi

        emit
        """
        .replace("__DEV__", shellQuote(iface))
        .replace("__PERCENT__", shellQuote(percent));
    return command("/bin/sh", "-c", script);
  }

  private static FaultCommand buildDuplicateCommand(Map<String, String> params, String action) {
    FaultCommand cmd = command("/opt/chaosblade/blade", action, "network", "duplicate",
        "--percent", valueOrDefault(params, "percent", "50"),
        "--interface", valueOrDefault(params, "interface", "ens17f0"));
    cmd = withOptional(cmd, "--destination-ip", params.get("destination-ip"));
    return cmd;
  }

  private static FaultTemplate buildFileMoveTemplate(Map<String, String> params) {
    String srcPath = valueOrDefault(params, "src-path", "/tmp/source.log");
    // dst-dir 是目标目录，文件会被移入此目录并保持原文件名
    String dstDir = valueOrDefault(params, "dst-dir", "/tmp/");
    // 兼容旧参数名 dst-path
    if (StringUtils.isBlank(dstDir) || "/tmp/".equals(dstDir)) {
      dstDir = valueOrDefault(params, "dst-path", "/tmp/");
    }
    // 恢复只靠 chaosblade 的 --timeout（由 buildFaultTemplate 统一追加）：到点由目标机
    // 本地的 chaos_os 进程执行撤销，不经过 agent/HTTP。因此即使恢复时刻链路正好被别的
    // 网络故障打坏（例如本计划里 78s 起的 network loss），文件也会在本地被移回，不会把
    // "没恢复"的状态带进后续注入。
    //
    // 不设 recovery 命令（autoRecovery=1）：
    //   * 避免"引擎恢复 + --timeout"两个恢复者互相踩。2026-11-03 plan-000010 就是
    //     --timeout 先把文件移回、引擎的恢复脚本随后 rm -rf 源路径，把真身删掉了；
    //   * 归档里 recoveryResult 为空是预期值，HistoryQueryService 会把
    //     "autoRecovery=true 且无恢复记录"判为靠 --timeout 自愈成功。
    //
    // 代价：失去源位置残留的 rm -rf 清理。它只对"移动目录"必要（PG 会在原路径重建
    // 目录，里面的 _fsm/_vm 让 mv 报 Directory not empty）；移动单个文件时 mv 会直接
    // 覆盖重建出来的同名文件。当前 file move 用例移动的都是文件。
    //
    // 也不能再用一次 create 反向搬（原先的写法）：那会在 chaosblade 里登记第二条实验，
    // 而那条实验的撤销方向恰好是"再把文件移走一次"，并且它没有 --timeout 兜底 ——
    // 一旦被 destroy（例如 blade destroy --all），文件就被永久移走，没有任何东西
    // 会把它挪回来。2026-10-07 库目录 base/12994 被移到 data/12994 后一直没回来，
    // 就是这个原因。
    return new FaultTemplate(
        "File Move",
        durationFromParams(params, 60),
        command("/opt/chaosblade/blade", "create", "file", "move",
            "--filepath", srcPath,
            "--target", dstDir),
        null,
        1,
        buildFileMoveCheck(srcPath, dstDir));
  }

  private static FaultTemplate buildFileChmodTemplate(Map<String, String> params) {
    String filepath = valueOrDefault(params, "filepath", "/tmp/target.log");
    String mark = valueOrDefault(params, "mark", "777");
    return new FaultTemplate(
        "File Chmod",
        durationFromParams(params, 60),
        command("/opt/chaosblade/blade", "create", "file", "chmod",
            "--filepath", filepath,
            "--mark", mark),
        command("/opt/chaosblade/blade", "destroy", "file", "chmod",
            "--filepath", filepath,
            "--mark", mark),
        0,
        buildFileChmodCheck(filepath, mark));
  }

  /**
   * file move 的自检命令（agent POST /check 执行，输出契约见 {@code FaultVerifier#parseCheckResponse}）。
   *
   * <p>判据是「目标目录里出现同名对象」+「源位置的 inode 与它不是同一个」，两条都成立才算
   * enforced。**不能**用「源路径不存在」当判据：移的是 PG 的 base 目录时，PostgreSQL 会在原路径
   * 上把目录重建出来（里面只有 autovacuum 的 {@code _fsm}/{@code _vm}），源路径一直在，那样判会
   * 全部误判成未生效。
   *
   * <p>目标路径按 chaosblade 的口径拼：{@code mv <src> <target>} → {@code <target>/<basename(src)>}。
   *
   * <p><b>basename 必须在脚本里、对解析后的路径求</b>：{@code src-path} / {@code dst-dir} 可能
   * 是 {@code @auto:hottest-file} / {@code @auto:pgdata} 这样的哨兵，提交时还没解析，命令里
   * 留着字面量。如果在 Java 里先拼 dst，会拼出
   * {@code @auto:pgdata/@auto:hottest-file}，事后文本替换得到
   * {@code /var/lib/postgresql/data//var/lib/postgresql/data/base/...} —— 永远 absent，
   * 注入明明成功也会判 NOT_ENFORCED（2026-11-02 16:47 那一轮）。脚本在两种参数都替换完
   * 之后才运行，此时 {@code $src} 已是真实路径，{@code $(basename "$src")} 才是正确落点。
   */
  static FaultCommand buildFileMoveCheck(String srcPath, String dstDir) {
    String script = """
        probe() {
          out=$(stat -c '%d:%i' "$1" 2>&1)
          if [ $? -eq 0 ]; then printf '%s' "$out"; return; fi
          case "$out" in
            *ermission?denied*) printf 'unreadable' ;;
            *) printf 'absent' ;;
          esac
        }
        src=__SRC__
        dst_dir=__DST_DIR__
        dst="${dst_dir%/}/$(basename "$src")"
        s=$(probe "$src")
        d=$(probe "$dst")
        if [ "$s" = unreadable ] || [ "$d" = unreadable ]; then
          echo "self-check cannot read path (permission denied): src=$s dst=$d" >&2
          exit 2
        fi
        dest=false; [ "$d" != absent ] && dest=true
        moved=false; if [ "$d" != absent ] && [ "$s" != "$d" ]; then moved=true; fi
        if [ "$moved" = true ]; then
          summary='源位置已不是原对象，目标目录里存在移动后的对象'
        elif [ "$d" = absent ]; then
          summary='目标目录里没有该对象，移动没有发生'
        else
          summary='目标目录里没有该对象，源位置仍是原对象'
        fi
        printf '{"enforced": %s, "summary": "%s", "checks": [{"name": "dest_exists", "observed": "%s", "expected": "present", "matched": %s}, {"name": "src_is_not_the_moved_object", "observed": "%s", "expected": "absent or different inode", "matched": %s}]}\\n' \\
          "$moved" "$summary" "$d" "$dest" "$s" "$moved"
        """
        .replace("__SRC__", shellQuote(srcPath))
        .replace("__DST_DIR__", shellQuote(dstDir));
    return command("/bin/sh", "-c", script);
  }

  /**
   * file chmod 的自检命令：{@code stat -c %a} 必须等于下发时的 {@code mark}。
   *
   * <p>另外读 chaosblade 自己记的原权限（{@code /tmp/chaos-file-chmod.tmp} 里的
   * {@code <path>:<origin mode>}，见 exec-os {@code file_chmod.go}）：如果原权限本来就等于
   * mark，这次 chmod 是空转（文件早就 777 了），不能算「真的改动了」，否则一条历史遗留的
   * 777 会让每一轮都误判成 enforced。读不到这个文件（版本差异/权限）就退回只看 mode。
   *
   * <p>比较前两边都去掉前导 0：{@code stat -c '%a'} 把 000 打印成 {@code "0"}，而下发的
   * mark 必须是三位 {@code "000"}（chaosblade 不收 {@code "0"}）—— 直接按字符串比，
   * 每一轮 000 都会误判成 NOT_ENFORCED（2026-11-02 16:38 / 17:05 两轮）。
   */
  static FaultCommand buildFileChmodCheck(String filepath, String mark) {
    String script = """
        p=__PATH__
        want=__MARK__
        got=$(stat -c '%a' "$p" 2>&1)
        if [ $? -ne 0 ]; then
          case "$got" in
            *ermission?denied*) echo "self-check cannot read $p (permission denied)" >&2; exit 2 ;;
          esac
          got=absent
        fi
        origin=$(awk -F: -v k="$p" '$1 == k { print $2 }' /tmp/chaos-file-chmod.tmp 2>/dev/null | tail -n 1)
        # 按八进制数值比，不按字符串比：stat -c '%a' 对 000 只输出 "0"，而 chaosblade 的
        # --mark 必须写成 "000"（写 "0" 会报 illegal mark）；chaosblade 自己记的原始权限
        # 也可能是 "0"/"00"。统一去掉前导 0（"000"→"0"，"0755"→"755"）再比。
        norm_mode() {
          v=$1
          while [ "${v#0}" != "$v" ]; do v=${v#0}; done
          [ -n "$v" ] || v=0
          printf '%s' "$v"
        }
        got_n=$(norm_mode "$got")
        want_n=$(norm_mode "$want")
        origin_n=$(norm_mode "$origin")
        mode_ok=false; [ "$got_n" = "$want_n" ] && mode_ok=true
        not_noop=true
        if [ -n "$origin" ] && [ "$origin_n" = "$want_n" ]; then not_noop=false; fi
        enforced=false
        if [ "$mode_ok" = true ] && [ "$not_noop" = true ]; then enforced=true; fi
        if [ "$enforced" = true ]; then
          summary='权限已改成下发值'
        elif [ "$not_noop" = false ]; then
          summary='注入前权限本来就是该值，chmod 空转'
        else
          summary='权限仍是原值，chmod 没有生效'
        fi
        printf '{"enforced": %s, "summary": "%s", "checks": [{"name": "mode_equals_mark", "observed": "%s", "expected": "%s", "matched": %s}, {"name": "not_a_noop", "observed": "origin=%s", "expected": "origin differs from mark", "matched": %s}]}\\n' \\
          "$enforced" "$summary" "$got" "$want" "$mode_ok" "${origin:-unknown}" "$not_noop"
        """
        .replace("__PATH__", shellQuote(filepath))
        .replace("__MARK__", shellQuote(mark));
    return command("/bin/sh", "-c", script);
  }

  /**
   * process stop 的自检命令：回读 /proc，统计 comm 恰好等于 {@code process-cmd}、且状态为 T 的进程数。
   *
   * <p>口径必须与 chaosblade 的 {@code --process-cmd} 一致：它是 {@code /proc/<pid>/comm}
   * 的<b>完全匹配</b>（既不是命令行子串，也不是路径或正则），所以这里也只比 comm。
   * comm 一个都找不到 → 目标写错（例如 MySQL 写成 {@code /usr/sbin/mysqld}，而进程的 comm
   * 是 {@code mysqld}），判 NOT_ENFORCED —— 这正是进程故障最常见的静默失败。
   *
   * <p>不拿 {@code host.process.stopped} 当判据：它是 30s 抓取、滞后 40~60s。
   * 2026-10-23 e11-proc-stop-high 同一轮三批被判成 +0 / +208 / -208（PARTIAL），
   * 而三批 create/destroy 回执全部 rc=0，指标也确实 0 → 208 → 0 回落。直接读 /proc 没有滞后。
   *
   * <p>命令由 agent 的 POST /check 执行；配了 container 的库（PG）会被引擎套上
   * {@code docker exec}，读到的是容器内的 /proc，正是注入发生的命名空间。
   * 脚本内重试 5 秒吸收 SIGSTOP 的落地抖动，总耗时仍低于 agent 的 12s 上限。
   */
  static FaultCommand buildProcessStopCheck(String processCmd) {
    String script = """
        want=__NAME__
        total=0; stopped=0; i=0
        while [ "$i" -lt 5 ]; do
          total=0; stopped=0
          for d in /proc/[0-9]*; do
            c=$(cat "$d/comm" 2>/dev/null) || continue
            [ "$c" = "$want" ] || continue
            total=$((total + 1))
            st=$(sed -n 's/^State:[[:space:]]*//p' "$d/status" 2>/dev/null | cut -c1)
            case "$st" in T|t) stopped=$((stopped + 1)) ;; esac
          done
          # 已经有 T 状态进程 → 注入生效；一个匹配进程都没有 → 目标写错，重试也没用
          if [ "$stopped" -gt 0 ] || [ "$total" -eq 0 ]; then break; fi
          i=$((i + 1)); sleep 1
        done
        enforced=false; [ "$stopped" -gt 0 ] && enforced=true
        if [ "$total" -eq 0 ]; then
          summary='没有找到 comm 等于目标名的进程（process-cmd 写错或进程已退出）'
        elif [ "$enforced" = true ]; then
          summary='目标进程已进入 T 状态（SIGSTOP 生效）'
        else
          summary='找到了目标进程，但没有一个处于 T 状态'
        fi
        printf '{"enforced": %s, "summary": "%s", "checks": [{"name": "target_process_in_T_state", "observed": "%s/%s", "expected": ">=1 stopped of matched", "matched": %s}]}\\n' \\
          "$enforced" "$summary" "$stopped" "$total" "$enforced"
        """
        .replace("__NAME__", shellQuote(processCmd));
    return command("/bin/sh", "-c", script);
  }

  /** POSIX 单引号包裹，内部的单引号按 {@code '\''} 转义 —— 路径里万一有引号也不会跑出脚本。 */
  private static String shellQuote(String value) {
    return "'" + StringUtils.defaultString(value).replace("'", "'\\''") + "'";
  }

  private static String toConfigPath(Path path) {
    return path.toString().replace(File.separatorChar, '/');
  }

  private static int durationFromParams(Map<String, String> params, int defaultValue) {
    return Math.max(1, intValue(params.get("duration"), defaultValue));
  }

  private static String rollbackRate(String rollbackRatePercent) {
    BigDecimal rate = new BigDecimal(rollbackRatePercent).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    return rate.stripTrailingZeros().toPlainString();
  }

  private static String mapStackOverflowType(String transactionType) {
    return switch (normalizeLower(transactionType)) {
      case "nested-transfer" -> "trans_recurse";
      case "recursive-proc" -> "proc_recurse";
      default -> transactionType;
    };
  }

  private static String valueOrDefault(Map<String, String> params, String key, String defaultValue) {
    if (params == null) {
      return defaultValue;
    }
    String value = params.get(key);
    return StringUtils.isBlank(value) ? defaultValue : value.trim();
  }

  private static int intValue(Integer value, int defaultValue) {
    return value == null ? defaultValue : value;
  }

  private static int intValue(String value, int defaultValue) {
    if (StringUtils.isBlank(value)) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  private static String booleanValue(Boolean value) {
    return Boolean.TRUE.equals(value) ? "true" : "false";
  }

  private static String normalizeLower(String value) {
    return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 进程杀死（SIGKILL）。与 {@code process.stop} 不同，kill 没有可撤销的动作：
   * 被杀的 mysqld/postgres 通常是容器的 PID 1，进程一死容器整体退出，容器内的
   * chaosblade 与它记的 uid 一并消失，{@code --timeout} 也没有机会执行。
   *
   * <p>因此恢复命令必须是**宿主机**上的 {@code docker start <容器>}，由引擎在 duration
   * 到点时执行（本模板 autoRecovery=0，引擎才会真正跑 recovery，见 FaultInjector 的
   * 时序循环）。命令刻意写成 {@code docker} 前缀而不是 blade 前缀：buildFaultTemplate
   * 的后处理只对 blade 命令追加 {@code --timeout} 并套 {@code docker exec}，若用 blade
   * 前缀，恢复命令会被包成 {@code docker exec mysql8 docker start mysql8} —— 容器里没有
   * docker CLI，也不能从容器内部拉起自己，必然失败。{@code docker exec …} 由这里写全。
   *
   * <p>恢复用 {@code start} 而不是 {@code restart}：宿主机 restart policy 为
   * always/unless-stopped 时容器会先自己起来，此时 {@code docker restart} 会把刚恢复的库
   * 再停一次；{@code docker start} 对运行中的容器是 no-op，两种策略下都正确。
   *
   * <p>没有配置容器名时（宿主机上的库进程）保持旧行为：宿主机 blade 注入、不排恢复，
   * 交给 restart policy 或人工拉起。
   */
  private FaultTemplate processKillTemplate(Map<String, String> params, StartByFormRequest request) {
    int duration = durationFromParams(params, 30);
    String processCmd = valueOrDefault(params, "process-cmd", defaultProcessCmd(request));
    String signal = valueOrDefault(params, "signal", "9");
    String container = StringUtils.trimToNull(request.getContainerName());
    if (container == null) {
      return new FaultTemplate(
          "Process Kill",
          duration,
          command("/opt/chaosblade/blade", "create", "process", "kill",
              "--process-cmd", processCmd,
              "--signal", signal),
          null,
          1);
    }
    return new FaultTemplate(
        "Process Kill",
        duration,
        command("docker", "exec", container, "/opt/chaosblade/blade", "create", "process", "kill",
            "--process-cmd", processCmd,
            "--signal", signal),
        command("docker", "start", container),
        0);
  }

  private String defaultProcessCmd(StartByFormRequest request) {
    String dbType = normalizeLower(request.getDbType());
    if ("postgresql".equals(dbType)) return "postgres";
    if ("mysql".equals(dbType)) return "mysqld";
    if ("oceanbase".equals(dbType)) return "observer";
    return "gaussdb";
  }

  private DatabaseEndpoint activeDatabaseEndpoint(StartByFormRequest request) {
    String deploymentType = normalizeLower(request.getDatabase().getDeploymentType());
    if ("distributed".equals(deploymentType)) {
      Map<String, String> distributed = request.getDatabase().getDistributed();
      if (distributed == null) {
        return new DatabaseEndpoint(null, null, null, null);
      }
      return new DatabaseEndpoint(
          StringUtils.trimToNull(distributed.get("ip")),
          StringUtils.trimToNull(distributed.get("port")),
          StringUtils.trimToNull(distributed.get("username")),
          StringUtils.trimToNull(distributed.get("password")));
    }
    StartByFormRequest.StandaloneDatabase standalone = request.getDatabase().getStandalone();
    if (standalone == null) {
      return new DatabaseEndpoint(null, null, null, null);
    }
    return new DatabaseEndpoint(
        StringUtils.trimToNull(standalone.getIp()),
        StringUtils.trimToNull(standalone.getPort()),
        StringUtils.trimToNull(standalone.getUsername()),
        StringUtils.trimToNull(standalone.getPassword()));
  }

  /**
   * 把请求侧的负载名翻译成引擎内部的 benchmark 名。
   *
   * <p>包内可见是给 {@code FileFaultTargetService} 用的（它要从 {@code benchmarks/<名>/} 下找 DDL）——
   * 别在那边另抄一份规则，否则以后再加别名就会分叉。
   */
  static String normalizeWorkloadName(String name) {
    String v = normalizeLower(name);
    if ("benchmarksql".equals(v)) return "tpcc";
    return v;
  }

  private static int parseWarehouseCount(StartByFormRequest request) {
    Map<String, String> params = request.getWorkload().getParams();
    return intValue(params == null ? null : params.get("warehouses"), 1);
  }

  private static TpccCacheData readTpccCache(String dbType) {
    try {
      File f = new File(tpccWhCachePath(dbType));
      if (!f.exists()) return null;
      String content = FileUtil.readFileAsString(f.getPath());
      if (content == null) return null;
      // 兼容旧格式（纯数字）
      content = content.trim();
      if (content.matches("\\d+")) {
        return new TpccCacheData(Integer.parseInt(content), "completed");
      }
      // JSON 格式：{"wh":10,"status":"completed"}
      com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(content);
      int wh = node.has("wh") ? node.get("wh").asInt() : -1;
      String status = node.has("status") ? node.get("status").asText() : "unknown";
      return new TpccCacheData(wh, status);
    } catch (Exception e) {
      LOG.warn("[TPCC_WH_CACHE][{}] Failed to read cache: {}", dbType, e.getMessage());
      return null;
    }
  }

  private static void saveLastWarehouseCount(int count, String dbType, String status) {
    try {
      File f = new File(tpccWhCachePath(dbType));
      String json = "{\"wh\":" + count + ",\"status\":\"" + status + "\"}";
      FileUtil.writeStringToFile(f, json);
    } catch (IOException e) {
      LOG.warn("[TPCC_WH_CACHE][{}] Failed to write cache: {}", dbType, e.getMessage());
    }
  }

  private static Document newDocument() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.newDocument();
  }

  private static Document parseDocument(Path path) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(path.toFile());
  }

  private static void appendTextElement(Document document, Element parent, String tagName, String value) {
    Element element = document.createElement(tagName);
    element.setTextContent(Objects.toString(value, ""));
    parent.appendChild(element);
  }

  private static void writeDocument(Document document, Path targetPath) throws Exception {
    Files.createDirectories(targetPath.getParent());
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
    transformer.transform(new DOMSource(document), new StreamResult(targetPath.toFile()));
  }

  public record GeneratedStart(String[] args, StartByFormResponse response) {}

  /** 编排校验临时产物：目录、故障编排 XML、主配置 XML。 */
  public record PreparedCheck(Path directory, Path planPath, Path configPath, String runId) {}

  private record FaultTemplate(
      String description,
      int durationSeconds,
      FaultCommand injectionCommand,
      FaultCommand recoveryCommand,
      int autoRecovery,
      FaultCommand checkCommand,
      boolean asyncInjection) {
    FaultTemplate(
        String description,
        int durationSeconds,
        FaultCommand injectionCommand,
        FaultCommand recoveryCommand,
        int autoRecovery) {
      this(description, durationSeconds, injectionCommand, recoveryCommand, autoRecovery, null, false);
    }
    FaultTemplate(
        String description,
        int durationSeconds,
        FaultCommand injectionCommand,
        FaultCommand recoveryCommand,
        int autoRecovery,
        FaultCommand checkCommand) {
      this(description, durationSeconds, injectionCommand, recoveryCommand, autoRecovery, checkCommand, false);
    }
  }

  private record FaultScheduleItem(
      int id,
      String description,
      int planningSeconds,
      int durationSeconds,
      FaultCommand injectionCommand,
      FaultCommand recoveryCommand,
      int autoRecovery,
      FaultCommand checkCommand,
      boolean asyncInjection,
      String groupKey) {}

  private record FaultCommand(String cmd, List<String> args) {}

  private record DatabaseEndpoint(String ip, String port, String username, String password) {}
}
