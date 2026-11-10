package com.resbench.controller;

import com.resbench.api.collectors.monitoring.MonitorResponse;
import com.resbench.buffer.BenchResultBuffer;
import com.resbench.common.constant.PathConstant;
import com.resbench.common.job.JobState;
import com.resbench.pojo.BenchMarkStatus;
import com.resbench.common.fault.FaultInjectInfo;
import com.resbench.pojo.dto.BenchResult;
import com.resbench.pojo.dto.DbParamRequest;
import com.resbench.pojo.dto.DbParamResponse;
import com.resbench.pojo.dto.FileFaultTargetRequest;
import com.resbench.pojo.dto.FileFaultTargetResponse;
import com.resbench.pojo.dto.PageResult;
import com.resbench.pojo.dto.RunLabelRequest;
import com.resbench.pojo.dto.StartByFormRequest;
import com.resbench.pojo.dto.StartByFormResponse;
import com.resbench.pojo.dto.TestConnectionRequest;
import com.resbench.pojo.dto.TestConnectionResponse;
import com.resbench.pojo.dto.TestSuiteHistoryResponse;
import com.resbench.pojo.dto.TpccWarehouseVerifyRequest;
import com.resbench.pojo.dto.TpccWarehouseVerifyResponse;
import com.resbench.result.MetricUtils;
import com.resbench.result.TestSuiteUtils;
import com.resbench.service.StartByFormService;
import com.resbench.service.TestConnectionService;
import com.resbench.service.DbParamService;
import com.resbench.service.FaultCatalogLoader;
import com.resbench.service.HistoryQueryService;
import com.resbench.service.RunArchiveService;
import com.resbench.service.TpccDataVerifyService;
import com.resbench.service.FaultTemplateEngine;
import com.resbench.service.FileFaultTargetService;
import com.resbench.common.fault.Command;
import com.resbench.common.fault.FaultVerificationResult;
import com.resbench.common.fault.FaultVerificationStatus;
import com.resbench.common.fault.FaultVerifier;
import com.resbench.common.util.CollectionUtil;
import com.resbench.common.util.CommandExecutor;
import com.resbench.util.CsvUtile;
import com.resbench.common.util.FileUtil;
import com.resbench.common.util.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

@RestController
@RequestMapping("/web")
public class BenchmarkController {
  private static final Logger LOG = LoggerFactory.getLogger(BenchmarkController.class);

  private static BenchMarkStatus result = new BenchMarkStatus();
  private static volatile String currentRunId = "";

  /** 常驻命令通道开关（{@code FaultInjector} / {@code ChannelRegistry} 读同名系统属性） */
  private static final String FAULT_CHANNEL_ENABLED_PROPERTY = "fault.channel.enabled";

  private static final Map<String, String> FAULT_DESCRIPTION_TO_NAME_KEY = Map.ofEntries(
      Map.entry("CPU Load", "faultinject.fault.cpu.load"),
      Map.entry("Memory Load", "faultinject.fault.memory.load"),
      Map.entry("Network Packet Loss", "faultinject.fault.network.package"),
      Map.entry("Network Delay", "faultinject.fault.network.delay"),
      Map.entry("Network Reorder", "faultinject.fault.network.unorder"),
      Map.entry("Network Duplicate", "faultinject.fault.network.duplicate"),
      Map.entry("Network Packet Corrupt", "faultinject.fault.network.corrupt"),
      Map.entry("Disk Fill", "faultinject.fault.disk.fill"),
      Map.entry("File Move", "faultinject.fault.disk.filemove"),
      Map.entry("File Delete", "faultinject.fault.disk.fileremoved"),
      Map.entry("File Append", "faultinject.fault.disk.fileappend"),
      Map.entry("File Chmod", "faultinject.fault.disk.filechmod"),
      Map.entry("Process Stop", "faultinject.fault.process.stop"),
      Map.entry("Process Kill", "faultinject.fault.process.kill"),
      Map.entry("Database Connection Storm", "faultinject.fault.connection.surge"),
      Map.entry("Database Connection Exhaustion", "faultinject.fault.connection.limit.exceeded"),
      Map.entry("Transaction Stack Overflow", "faultinject.fault.transaction.stack.overflow"),
      Map.entry("Massive Transaction Rollback Storm", "faultinject.fault.transaction.massive.rollback")
  );

  private final StartByFormService startByFormService;
  private final TestConnectionService testConnectionService;
  private final DbParamService dbParamService;
  private final FaultCatalogLoader faultCatalogLoader;
  private final RunArchiveService runArchiveService;
  private final HistoryQueryService historyQueryService;
  private final TpccDataVerifyService tpccDataVerifyService;
  private final FileFaultTargetService fileFaultTargetService;

  public BenchmarkController(StartByFormService startByFormService, TestConnectionService testConnectionService,
      DbParamService dbParamService, FaultCatalogLoader faultCatalogLoader, RunArchiveService runArchiveService,
      HistoryQueryService historyQueryService, TpccDataVerifyService tpccDataVerifyService,
      FileFaultTargetService fileFaultTargetService) {
    this.startByFormService = startByFormService;
    this.testConnectionService = testConnectionService;
    this.dbParamService = dbParamService;
    this.faultCatalogLoader = faultCatalogLoader;
    this.runArchiveService = runArchiveService;
    this.historyQueryService = historyQueryService;
    this.tpccDataVerifyService = tpccDataVerifyService;
    this.fileFaultTargetService = fileFaultTargetService;
  }

  private String quoteCommandArg(String value) {
    if (value == null) {
      return "\"\"";
    }
    return "\"" + value.replace("\"", "\\\"") + "\"";
  }

  private String toArgFileToken(String value) {
    if (value == null) {
      return "\"\"";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  // ---------------------------------------------------------------------
  // 故障目录与手动补验（设计文档 §5.4 / §7.3）
  // ---------------------------------------------------------------------

  @GetMapping("/faults/catalog")
  public ResponseEntity<List<Map<String, Object>>> getFaultCatalog() {
    List<Map<String, Object>> items = new ArrayList<>();
    for (FaultCatalogLoader.FaultDefinition def : faultCatalogLoader.list()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("nameKey", def.nameKey());
      item.put("name", def.name());
      item.put("description", def.description());
      item.put("category", def.category());
      item.put("autoRecovery", def.autoRecovery());
      item.put("defaultDuration", def.defaultDuration());
      item.put("hasCheck", def.hasCheck());
      List<Map<String, Object>> params = new ArrayList<>();
      for (FaultCatalogLoader.FaultParam p : def.params()) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("paramName", p.paramName());
        pm.put("label", p.label());
        pm.put("description", p.description());
        pm.put("type", p.type());
        pm.put("unit", p.unit());
        pm.put("defaultValue", p.defaultValue());
        pm.put("required", p.required());
        params.add(pm);
      }
      item.put("params", params);
      items.add(item);
    }
    return ResponseEntity.ok(items);
  }

  /**
   * 手动补验：即时执行一次该故障的 check 命令并返回结果（不做轮询）。
   * body: {island dbType, databaseIp, databasePort, agentPort, nameKey, params, user, password}
   */
  @PostMapping("/fault/verify")
  public ResponseEntity<FaultVerificationResult> verifyFault(@RequestBody Map<String, Object> body) {
    String nameKey = strValue(body.get("nameKey"));
    FaultVerificationResult result = new FaultVerificationResult();
    result.setNameKey(nameKey);
    result.setPhase("INJECT");
    result.setVerifiedAt(System.currentTimeMillis());

    FaultCatalogLoader.FaultDefinition def =
        nameKey == null ? null : faultCatalogLoader.get(nameKey).orElse(null);
    if (def == null || !def.hasCheck()) {
      result.setFaultName(nameKey == null ? "" : def == null ? nameKey : def.plainName());
      result.setStatus(FaultVerificationStatus.UNKNOWN);
      result.setError("未配置自检命令（无 check 模板）");
      return ResponseEntity.ok(result);
    }

    String dbType = strValue(body.get("dbType"));
    if (dbType == null || dbType.isBlank()) {
      dbType = "opengauss";
    }
    String databaseIp = strValue(body.get("databaseIp"));
    String databasePort = strValue(body.get("databasePort"));
    if (databasePort == null || databasePort.isBlank()) {
      databasePort = "mysql".equals(dbType) ? "3306" : "5432";
    }
    String agentPort = strValue(body.get("agentPort"));
    if (agentPort == null || agentPort.isBlank()) {
      agentPort = "8000";
    }
    String user = strValue(body.get("user"));
    String password = strValue(body.get("password"));

    Map<String, String> context = new LinkedHashMap<>();
    // 上下文变量清单见 FaultCatalogLoader.CONTEXT_VARIABLES
    context.put(FaultCatalogLoader.CTX_DB_TYPE, dbType);
    context.put(FaultCatalogLoader.CTX_DB_URL, buildVerifyDbUrl(dbType, databaseIp, databasePort));
    context.put(FaultCatalogLoader.CTX_USER, user == null ? "" : user);
    context.put(FaultCatalogLoader.CTX_PASSWORD, password == null ? "" : password);
    context.put(FaultCatalogLoader.CTX_DBCHAOS_JAR, "/opt/DBChaos/DBChaos.jar");
    context.put(FaultCatalogLoader.CTX_AGENT_HOST, databaseIp + ":" + agentPort);

    Map<String, String> params = toStringMap(body.get("params"));
    String rendered = FaultTemplateEngine.render(def.checkTemplate(), params, context);
    List<String> tokens =
        java.util.Arrays.stream(rendered.split("\\s+")).filter(t -> !t.isEmpty()).toList();
    if (tokens.isEmpty()) {
      result.setStatus(FaultVerificationStatus.UNKNOWN);
      result.setError("check 模板渲染为空");
      return ResponseEntity.ok(result);
    }
    Command checkCommand = new Command(tokens.get(0), tokens.subList(1, tokens.size()).toArray(new String[0]));
    FaultVerificationResult verify = FaultVerifier.verifyOnce(
        nameKey, def.plainName(), nameKey, checkCommand, databaseIp + ":" + agentPort);
    return ResponseEntity.ok(verify);
  }

  private static String buildVerifyDbUrl(String dbType, String ip, String port) {
    if ("mysql".equals(dbType) || "oceanbase".equals(dbType)) {
      return "jdbc:mysql://" + ip + ":" + port + ("oceanbase".equals(dbType) ? "/oceanbase" : "/resbench_demo");
    }
    return "jdbc:postgresql://" + ip + ":" + port + "/postgres";
  }

  private static String strValue(Object value) {
    return value == null ? null : value.toString().trim();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> toStringMap(Object raw) {
    if (!(raw instanceof Map)) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    ((Map<Object, Object>) raw).forEach((k, v) -> result.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
    return result;
  }

  private String appendQuotedArgs(String[] args) {
    if (args == null || args.length == 0) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (String arg : args) {
      builder.append(" ").append(quoteCommandArg(arg));
    }
    return builder.toString();
  }

  private String buildJobCommand(String[] args) {
    File jarFile = new File("./resbenchmark.jar");
    String javaExec =
        Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    if (jarFile.exists()) {
      return quoteCommandArg(javaExec)
          + " -jar "
          + quoteCommandArg(jarFile.getAbsolutePath())
          + " job"
          + appendQuotedArgs(args);
    }

    String classPath = System.getProperty("java.class.path", "");
    String runtimeClassPath = resolveRuntimeClassPath(classPath);
    String compiledClasses = new File("./target/classes").getAbsolutePath();
    String testClasses = new File("./target/test-classes").getAbsolutePath();
    StringBuilder classPathBuilder = new StringBuilder(compiledClasses);
    if (new File(testClasses).exists()) {
      classPathBuilder.append(File.pathSeparator).append(testClasses);
    }
    if (StringUtils.isNotBlank(runtimeClassPath)) {
      classPathBuilder.append(File.pathSeparator).append(runtimeClassPath);
    }

    // 服务端 JVM 的 -D 不会自动传给 fork 出去的 job JVM，只能显式带过去。
    // 不带的话 job 侧永远读默认值（默认 true），"关掉通道做对照"就做不到。
    String channelEnabled = System.getProperty(FAULT_CHANNEL_ENABLED_PROPERTY, "true");
    String channelEnabledArg = "-D" + FAULT_CHANNEL_ENABLED_PROPERTY + "=" + channelEnabled;

    try {
      Path argFile = Paths.get("./target", "job-launch.args");
      Files.createDirectories(argFile.getParent());
      List<String> argLines = new ArrayList<>();
      argLines.add(channelEnabledArg);
      argLines.add("-cp");
      argLines.add(toArgFileToken(classPathBuilder.toString()));
      argLines.add("com.resbench.Application");
      argLines.add("job");
      if (args != null && args.length > 0) {
        for (String arg : args) {
          argLines.add(toArgFileToken(arg));
        }
      }
      Files.write(argFile, argLines, StandardCharsets.UTF_8);
      return quoteCommandArg(javaExec) + " @" + quoteCommandArg(argFile.toAbsolutePath().toString());
    } catch (IOException e) {
      LOG.warn("Failed to write Java arg file, fallback to direct command: {}", e.getMessage());
      return quoteCommandArg(javaExec)
          + " "
          + channelEnabledArg
          + " -cp "
          + quoteCommandArg(classPathBuilder.toString())
          + " com.resbench.Application job"
          + appendQuotedArgs(args);
    }
  }

  private String resolveRuntimeClassPath(String fallbackClassPath) {
    File runtimeClassPathFile = new File("./target/runtime-classpath.txt");
    if (runtimeClassPathFile.exists()) {
      try {
        String runtimeClassPath = Files.readString(runtimeClassPathFile.toPath()).trim();
        if (StringUtils.isNotBlank(runtimeClassPath)) {
          return runtimeClassPath;
        }
      } catch (IOException e) {
        LOG.warn("Failed to read runtime classpath file, fallback to java.class.path: {}", e.getMessage());
      }
    }
    return fallbackClassPath;
  }

  private Options buildOptions() {
    Options options = new Options();
    options.addOption(
            "b",
            "bench",
            true,
            "[required] Benchmark class. Currently supported");
    options.addOption("c", "config", true, "[required] Workload configuration file");
    options.addOption(null, "create", true, "Initialize the database for this benchmark");
    options.addOption(null, "clear", true, "Clear all records in the database for this benchmark");
    options.addOption(null, "load", true, "Load data using the benchmark's data loader");
    options.addOption(null, "execute", true, "Execute the benchmark workload");
    options.addOption("im", "interval-monitor", true, "Monitoring Interval in milliseconds");
    options.addOption("p", "pid", true, "workload process ID");
    options.addOption("ac", "action", true, "attach or start");
    options.addOption(null, "fault-config", true, "Generated fault schedule configuration file");
    options.addOption(null, "run-id", true, "Generated history name for this run");
    options.addOption(null, "tpcc-cache", true, "TPCC warehouse cache path and count (path:wh)");
    return options;
  }

  private static void updateArgs(String configPath, String newArgsValue) throws IOException {
    Yaml yaml = new Yaml();
    try (InputStream inputStream = new FileInputStream(configPath)) {
      @SuppressWarnings("unchecked")
      Map<String, Object> config = yaml.load(inputStream);
      if (config != null && config.containsKey("resbenchmark.agent")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> resbenchmarkAgent = (Map<String, Object>) config.get("resbenchmark.agent");
        if (resbenchmarkAgent != null) {
          resbenchmarkAgent.put("args", newArgsValue);
          try (OutputStream outputStream = new FileOutputStream(configPath);
            Writer writer = new OutputStreamWriter(outputStream, "UTF-8")) {
            yaml.dump(config, writer);
          }
        }
      }
    }
  }

  private static File findAgentJar() {
    // 在当前目录或target目录查找
    String[] possiblePaths = {
            "./sermant-agent/sermant-agent.jar"
    };

    for (String path : possiblePaths) {
      File file = new File(path);
      if (file.exists()) {
        return file;
      }
    }
    return null;
  }

  private static boolean loadAgent(String pid, String agentArgs) throws Exception {
    VirtualMachine vm = null;
    boolean result = false;
    try {
      LOG.info("正在加载Agent到进程: {}", pid);
      vm = VirtualMachine.attach(pid);

      File agentJar = findAgentJar();
      if (agentJar == null) {
        throw new RuntimeException("找不到Agent JAR文件");
      }
      // 加载Agent，传递自定义参数
      updateArgs("./sermant-agent/pluginPackage/resbenchmark/config/config.yaml", agentArgs);
      vm.loadAgent(agentJar.getAbsolutePath());
      LOG.info("Agent加载成功");
      result = true;
    } finally {
      if (vm != null) {
        vm.detach();
      }
    }
    return result;
  }

  private static boolean unloadAgent(String pid) throws Exception {
    boolean result = false;
    VirtualMachine vm = null;
    try {
      LOG.info("正在从进程卸载Agent: {}", pid);
      vm = VirtualMachine.attach(pid);

      File agentJar = findAgentJar();
      if (agentJar == null) {
        throw new RuntimeException("找不到Agent JAR文件");
      }

      // 传递"unload"参数触发卸载
      vm.loadAgent(agentJar.getAbsolutePath(), "command=UNINSTALL-AGENT");
      LOG.info("Agent卸载成功");
      result = true;
    } catch (Exception e) {
      LOG.error("get error when unload Agent :{}",e.getLocalizedMessage());
    } finally {
      if (vm != null) {
        vm.detach();
      }
    }
    return result;
  }

  /**
   * [ "-b", "smallbank", "-im", "1000", "-c", "config/sqlite/sample_smallbank_config.xml",
   * "--create=true", "--load=true", "--execute=true" ]
   */
  @PostMapping("/bench")
  public ResponseEntity<String> startTask(@RequestBody String[] args) throws Exception {
    StartTaskResult startTaskResult = doStartTask(args);
    return new ResponseEntity<>(startTaskResult.message(), HttpStatusCode.valueOf(200));
  }

  @PostMapping("/bench/startByForm")
  public ResponseEntity<StartByFormResponse> startByForm(@RequestBody StartByFormRequest request) throws Exception {
    if (!result.isEnd()) {
      return new ResponseEntity<>(
          new StartByFormResponse(false, currentRunId, currentRunId, null, "执行中，请稍后"),
          HttpStatusCode.valueOf(200));
    }
    try {
      StartByFormService.GeneratedStart generatedStart = startByFormService.prepare(request);
      StartTaskResult startTaskResult = doStartTask(generatedStart.args());
      StartByFormResponse response = generatedStart.response();
      response.setAccepted(startTaskResult.accepted());
      response.setMessage(startTaskResult.message());
      if (!startTaskResult.accepted()) {
        response.setRunId(currentRunId);
        response.setHistoryName(currentRunId);
      } else {
        // 归档这一轮的输入快照（脱敏，不含连接信息），供 /web/bench/history/query 检索
        runArchiveService.archiveRunMeta(request, response.getHistoryName(), response.getRunId());
      }
      return new ResponseEntity<>(response, HttpStatusCode.valueOf(200));
    } catch (IllegalArgumentException e) {
      return new ResponseEntity<>(
          new StartByFormResponse(false, null, null, null, e.getMessage()),
          HttpStatusCode.valueOf(200));
    }
  }

  @PostMapping("/bench/connection/test")
  public ResponseEntity<TestConnectionResponse> testConnection(@RequestBody TestConnectionRequest request) {
    return new ResponseEntity<>(testConnectionService.testConnection(request), HttpStatusCode.valueOf(200));
  }

  /**
   * 应用数据库参数（缓冲池大小 / 最大连接数上限），只支持单机 openGauss / PostgreSQL / MySQL。
   *
   * <p>失败也返回 200：前端统一读 body 里的 success / message 来提示（与连接测试同构）。
   * 需要重启的库（og/pg 的这两个参数都是 postmaster 上下文）必须显式带 restart.command。
   */
  @PostMapping("/bench/db/params")
  public ResponseEntity<DbParamResponse> applyDbParams(@RequestBody DbParamRequest request) {
    return new ResponseEntity<>(dbParamService.handle(request), HttpStatusCode.valueOf(200));
  }

  /**
   * 检查目标库里实际的 TPCC 仓数，与请求里的配置仓数比对。只做 tpcc。
   *
   * <p>用途：引擎决定建不建仓只看 runOptions.reload（从不问库里已有多少仓），
   * 所以"表单填 200 仓、库里躺着上一轮 1000 仓"这种情形此前完全看不见。
   *
   * <p>失败也返回 200：调用方统一读 body 里的 success / conclusion / message（与连接测试同构）。
   * success=false 表示<b>无法判定</b>（连不上 / 没权限 / 超时），不等于"尚未建仓"（那是 ABSENT）。
   */
  @PostMapping("/bench/tpcc/warehouse/verify")
  public ResponseEntity<TpccWarehouseVerifyResponse> verifyTpccWarehouses(
      @RequestBody TpccWarehouseVerifyRequest request) {
    return new ResponseEntity<>(
        tpccDataVerifyService.handle(request, !result.isEnd()), HttpStatusCode.valueOf(200));
  }

  /**
   * 列出 file 类故障可以打的目标文件：按「目录 → 每张表（表下挂它的堆文件和它的各个索引）」分组。
   *
   * <p>用途：file 类故障的路径参数此前全靠人记 —— 重灌数据会换文件号；库里还可能躺着别的负载留下的
   * 同名前缀表（实测有 96 GB、scans=0 的遗留表），按体量挑就会挑到它们，跑出来是「假故障」。
   *
   * <p>失败也返回 200：调用方统一读 body 里的 success / message（与连接测试同构）。
   * success=false 表示<b>拿不到清单</b>（连不上库 / 这一轮要重建数据 / 这个库类型还没有探针）。
   */
  @PostMapping("/bench/faults/file-targets")
  public ResponseEntity<FileFaultTargetResponse> fileFaultTargets(
      @RequestBody FileFaultTargetRequest request) {
    return new ResponseEntity<>(
        fileFaultTargetService.handle(request), HttpStatusCode.valueOf(200));
  }

  private StartTaskResult doStartTask(String[] args) throws Exception {
    synchronized (this.getClass()) {
      if (!result.isEnd()) {
        return new StartTaskResult(false, "执行中，请稍后");
      }
      result.reset();

      try {
        FileUtils.forceDelete(new File(PathConstant.getTmpPath()));
      } catch (Exception e) {
        LOG.error("Failed to delete the .tmp file. msg {}", e.getMessage());
      }

      CommandLineParser parser = new DefaultParser();
      CommandLine argsLine = parser.parse(buildOptions(), args);
      currentRunId = argsLine.getOptionValue("run-id", currentRunId);

      String startTimestamp = String.valueOf(System.currentTimeMillis());
      result.setStartTimestamp(startTimestamp);
      result.setEnd(false);

      if (!argsLine.hasOption("ac")) {
        // Write initial job state before launching so status is retrievable even if the job crashes
        JobState initialState = new JobState(JobState.State.RUNNING, startTimestamp, false);
        try {
          File tmpDir = new File(PathConstant.getTmpPath());
          FileUtil.makeDirIfNotExists(tmpDir.getAbsolutePath());
          File tmpJobFile = new File(PathConstant.getTmpJobPath());
          FileUtil.writeStringToFile(tmpJobFile, JSONUtil.writeValueAsString(initialState));
        } catch (Exception e) {
          LOG.error("Failed to write initial job state: {}", e.getMessage());
        }
      }

      if (argsLine.hasOption("ac")) {
        try {
          String action = argsLine.getOptionValue("ac");
          if ("attach".equals(action)) {
            String pid = argsLine.getOptionValue("p");
            String configFile = argsLine.getOptionValue("c");
            String intervalMonitor = argsLine.getOptionValue("im");
            loadAgent(pid, " -im " + intervalMonitor + " -c " + new File(configFile).getAbsolutePath());
          }
        } catch (Exception e) {
          result.setEnd(true);
          result.setSuccess(false);
          result.setMessage(e.getLocalizedMessage());
          LOG.error("get error when load Agent: {}",e.getLocalizedMessage());
        }
      } else {
        CompletableFuture.runAsync(
                () -> {
                  CommandExecutor.CommandResult commandResult = null;
                  try {
                    result.setStartTimestamp(String.valueOf(System.currentTimeMillis()));
                    result.setEnd(false);
                    result.setSuccess(false);
                    // 阻塞执行命令直至成功 或失败
                    commandResult = CommandExecutor.run(buildJobCommand(args), -1);
                    if (commandResult.isSuccess()) {
                      result.setSuccess(true);
                    } else {
                      result.setSuccess(false);
                      result.setMessage(commandResult.getStderr());
                    }
                  } finally {
                    if (commandResult != null) {
                      LOG.error(commandResult.getStderr());
                    }
                    // 执行完毕
                    String endTimestamp = String.valueOf(System.currentTimeMillis());
                    result.setEnd(true);
                    result.setEndTimestamp(endTimestamp);
                    // Write final job state and copy to results directory
                    writeFinalJobState(endTimestamp);
                    currentRunId = "";
                  }
                },
                Executors.newVirtualThreadPerTaskExecutor());
      }
    }

    return new StartTaskResult(true, "started");
  }

  private record StartTaskResult(boolean accepted, String message) {}

  @PostMapping("/bench/stop")
  public ResponseEntity<String> stopTask(@RequestBody String[] args) throws Exception {
    try {
      boolean success = false;
      CommandLineParser parser = new DefaultParser();
      CommandLine argsLine = parser.parse(buildOptions(), args);
      if (argsLine.hasOption("p")) {
        String pid = argsLine.getOptionValue("p");
        success = unloadAgent(pid);
        result = this.getStatus(null);
      } else {
        success = CommandExecutor.stopCurrentProcess();
        if (success) {
          result.setEnd(true);
          result.setEndTimestamp(String.valueOf(System.currentTimeMillis()));
          result.setSuccess(false);
          result.setMessage("任务已被手工停止");
        }
      }

      if (success) {
        return new ResponseEntity<>("停止成功", HttpStatusCode.valueOf(200));
      } else {
        return new ResponseEntity<>("停止失败：无法停止进程", HttpStatusCode.valueOf(400));
      }
    } catch (Exception e) {
      LOG.error("Failed to stop task: {}", e.getMessage());
      return new ResponseEntity<>("停止失败：" + e.getMessage(), HttpStatusCode.valueOf(400));
    }
  }

  @GetMapping("/bench/result")
  public List<BenchResult> getBenchResult() {
    return BenchResultBuffer.getInstance().getAllElements();
  }

  @GetMapping("/bench/newest_result")
  public String getNewestBenchResult(
    @RequestParam(name = "testSuitName") String testSuitName,
    @RequestParam(name = "historyName", required = false) String historyName) {
    if (historyName != null && !historyName.startsWith(testSuitName)) {
      return null;
    }

    if (StringUtils.isBlank(historyName)) {
      List<String> resultPath = TestSuiteUtils.getResultPath(testSuitName);
      return MetricUtils.getHistoryMetric(resultPath.getFirst());
    }
    return MetricUtils.getHistoryMetric(historyName);
  }

  private void writeFinalJobState(String endTimestamp) {
    try {
      JobState.State finalState = result.isSuccess() ? JobState.State.GRACEFUL : JobState.State.GRACEFUL;
      // Check if message indicates manual stop
      if (result.getMessage() != null
          && (result.getMessage().contains("手工停止") || result.getMessage().contains("stopped"))) {
        finalState = JobState.State.MANUAL;
      }
      JobState jobState = new JobState();
      jobState.setStartTimeStamp(result.getStartTimestamp());
      jobState.setStopTimeStamp(endTimestamp);
      jobState.setCurState(finalState);
      jobState.setSuccess(result.isSuccess());

      // Write to tmp for current status
      File tmpJobFile = new File(PathConstant.getTmpJobPath());
      FileUtil.writeStringToFile(tmpJobFile, JSONUtil.writeValueAsString(jobState));

      // Copy to results directory for historical record
      if (StringUtils.isNotBlank(currentRunId)) {
        String historyJobPath = PathConstant.RESULTS_PATH + "/" + currentRunId + "/" + PathConstant.getJobStateName();
        FileUtil.makeDirIfNotExists(Paths.get(PathConstant.RESULTS_PATH, currentRunId).toString());
        FileUtils.copyFile(tmpJobFile, new File(historyJobPath));
      }
    } catch (Exception e) {
      LOG.error("Failed to write final job state: {}", e.getMessage());
    }
  }

  private String getJobState(String historyName){
    String result = "";

    String jobFile = PathConstant.getTmpJobPath();
    if (StringUtils.isNotBlank(historyName)) {
      String historyJobFile =
          PathConstant.RESULTS_PATH + "/" + historyName + "/" + PathConstant.getJobStateName();
      if (FileUtil.exists(historyJobFile)) {
        jobFile = historyJobFile;
      }
    }

    if (FileUtil.exists(jobFile)) {
      result = FileUtil.readFileAsString(jobFile);
    }
    return result;
  }

  private List<String> loadFaultNameKeys(String historyName) {
    try {
      String faultInjectPath = PathConstant.getFaultInjectPath(historyName);
      List<String> lines = FileUtil.readWithLock(faultInjectPath);
      if (lines.isEmpty()) {
        return List.of();
      }
      List<FaultInjectInfo> faultInfos = JSONUtil.fromJsonToList(lines.getFirst(), FaultInjectInfo.class);
      return faultInfos.stream()
          .map(f -> FAULT_DESCRIPTION_TO_NAME_KEY.getOrDefault(f.getFaultName(), f.getFaultName()))
          .collect(Collectors.toList());
    } catch (Exception e) {
      LOG.warn("Failed to load fault info for {}, err: {}", historyName, e.getMessage());
      return List.of();
    }
  }

  private BenchMarkStatus getHistoryStatus(String historyName) {
    if (StringUtils.isBlank(historyName)) {
      return null;
    }

    String dbType = loadDbTypeFromSummary(historyName);
    String workloadType = null;
    String workloadConfig = null;
    try {
      File historyDir = new File(PathConstant.RESULTS_PATH + "/" + historyName);
      File[] summaryFiles = historyDir.listFiles((dir, name) -> name.endsWith("summary.json"));
      if (summaryFiles != null && summaryFiles.length > 0) {
        JsonNode root = new ObjectMapper().readTree(summaryFiles[0]);
        JsonNode benchType = root.get("Benchmark Type");
        if (benchType != null) {
          workloadType = benchType.asText();
          Map<String, String> configMap = new HashMap<>();
          for (String key : new String[]{"terminals", "scalefactor", "isolation", "batchsize"}) {
            JsonNode node = root.get(key);
            if (node != null) {
              configMap.put(key, node.asText());
            }
          }
          if (!configMap.isEmpty()) {
            workloadConfig = new ObjectMapper().writeValueAsString(configMap);
          }
        }
      }
    } catch (IOException e) {
      LOG.warn("Failed to load workload from summary for {}, err: {}", historyName, e.getMessage());
    }

    List<String> faults = loadFaultNameKeys(historyName);

    String historyJobFile =
        PathConstant.RESULTS_PATH + "/" + historyName + "/" + PathConstant.getJobStateName();
    if (!FileUtil.exists(historyJobFile)) {
      if (dbType != null) {
        BenchMarkStatus status = new BenchMarkStatus();
        status.setDbType(dbType);
        status.setWorkloadType(workloadType);
        status.setWorkloadConfig(workloadConfig);
        status.setFaults(faults);
        return status;
      }
      return null;
    }

    try {
      String jobInfo = FileUtil.readFileAsString(historyJobFile);
      if (StringUtils.isBlank(jobInfo)) {
        if (dbType != null) {
          BenchMarkStatus status = new BenchMarkStatus();
          status.setDbType(dbType);
          status.setWorkloadType(workloadType);
          status.setWorkloadConfig(workloadConfig);
          status.setFaults(faults);
          return status;
        }
        return null;
      }
      JobState jobState = JSONUtil.fromJson(jobInfo, JobState.class);
      BenchMarkStatus status = new BenchMarkStatus().reset(jobState);
      status.setDbType(dbType);
      status.setWorkloadType(workloadType);
      status.setWorkloadConfig(workloadConfig);
      status.setFaults(faults);
      return status;
    } catch (RuntimeException e) {
      LOG.warn("Failed to parse history status for {}, err: {}", historyName, e.getMessage());
      if (dbType != null) {
        BenchMarkStatus status = new BenchMarkStatus();
        status.setDbType(dbType);
        status.setWorkloadType(workloadType);
        status.setWorkloadConfig(workloadConfig);
        status.setFaults(faults);
        return status;
      }
      return null;
    }
  }

  private String loadDbTypeFromSummary(String historyName) {
    try {
      File historyDir = new File(PathConstant.RESULTS_PATH + "/" + historyName);
      File[] summaryFiles = historyDir.listFiles((dir, name) -> name.endsWith("summary.json"));
      if (summaryFiles == null || summaryFiles.length == 0) {
        return null;
      }
      File summaryFile = summaryFiles[0];
      JsonNode root = new ObjectMapper().readTree(summaryFile);
      JsonNode dbTypeNode = root.get("DBMS Type");
      return dbTypeNode != null ? dbTypeNode.asText() : null;
    } catch (IOException e) {
      LOG.warn("Failed to load DB type from summary for {}, err: {}", historyName, e.getMessage());
      return null;
    }
  }

  @GetMapping("/bench/status")
  public BenchMarkStatus getStatus(@RequestParam(name = "historyName", required = false) String historyName) {
    String jobInfo = this.getJobState(historyName);
    JobState jobState = null;
    if (!StringUtils.isEmpty(jobInfo)) {
      jobState = JSONUtil.fromJson(jobInfo,JobState.class);
      result.reset(jobState);
    }

    return result;
  }

  @GetMapping("/bench/monitor")
  public List<MonitorResponse> getMonitor(
    @RequestParam(name = "historyName", required = false) String historyName,
    @RequestParam(name = "startTimestamp", required = false) String startTimestamp,
    @RequestParam(name = "endTimestamp", required = false) String endTimestamp) {
    String tmpFile = resolveMonitorFile(historyName);
    List<Map<String, String>> parse = CsvUtile.parse(tmpFile, ",");
    List<MonitorResponse> monitorResponses = new ArrayList<>();
    if (parse.isEmpty()) {
      LOG.info("No monitor data available for historyName: {}", historyName);
      return monitorResponses;
    }

    // 未传入开始时间结束时间， 返回最近60条数据
    if (StringUtils.isEmpty(startTimestamp) && StringUtils.isEmpty(endTimestamp)) {
      List<Map<String, String>> mapList = CollectionUtil.getLastN(parse, 60);
      for (Map<String, String> map : mapList) {
        buildMonitorResponse(map, monitorResponses);
      }
      return monitorResponses;
    }

    for (Map<String, String> map : parse) {
      String timestamp = map.get("timestamp");
      // 过滤时间区间
      if (StringUtils.isNotEmpty(startTimestamp)) {
        if (Long.parseLong(timestamp) < Long.parseLong(startTimestamp)) {
          continue;
        }
      }
      if (StringUtils.isNotEmpty(endTimestamp)) {
        if (Long.parseLong(timestamp) > Long.parseLong(endTimestamp)) {
          break;
        }
      }
      buildMonitorResponse(map, monitorResponses);
    }
    return monitorResponses;
  }

  @GetMapping("/bench/monitorByPage")
  public PageResult<MonitorResponse> getMonitorByPage(
    @RequestParam(name = "historyName", required = false) String historyName,
    @RequestParam(name = "pageSize") Integer pageSize,
    @RequestParam(name = "pageNum") Integer pageNum) {
    String tmpFile = resolveMonitorFile(historyName);
    List<Map<String, String>> parse = CsvUtile.parse(tmpFile, ",");
    PageResult<MonitorResponse> pageResult = new PageResult<>();
    List<MonitorResponse> monitorResponses = new ArrayList<>();
    pageResult.setData(monitorResponses);
    pageResult.setPageSize(pageSize);
    pageResult.setPageNum(pageNum);

    if (parse.isEmpty()) {
      pageResult.setTotal(0);
      pageResult.setTotalPages(0);
      LOG.info("No monitor data available for historyName: {}", historyName);
      return pageResult;
    }

    int totalSize = parse.size();
    pageResult.setTotal(totalSize);
    pageResult.setTotalPages((totalSize / pageNum) + 1);
    int startIndex = (pageNum - 1) * pageSize;
    List<Map<String, String>> slice = CollectionUtil.slice(parse, startIndex, pageSize);

    for (Map<String, String> map : slice) {
      if (map != null && !map.isEmpty()) {
        buildMonitorResponse(map, monitorResponses);
      }
    }
    return pageResult;
  }

  private void buildMonitorResponse(
    Map<String, String> map, List<MonitorResponse> monitorResponses) {
    MonitorResponse.MonitorResponseBuilder monitorResponseBuilder = MonitorResponse.builder();

    String tps = map.get("tps");
    monitorResponseBuilder.timestamp(Long.parseLong(map.get("timestamp")));
    monitorResponseBuilder.tps(Double.valueOf(tps));
    monitorResponseBuilder.avgLatencyMs(Double.parseDouble(map.getOrDefault("avgLatencyMs", "-1")));
    monitorResponseBuilder.maxLatencyMs(Double.parseDouble(map.getOrDefault("maxLatencyMs", "-1")));
    monitorResponseBuilder.minLatencyMs(Double.parseDouble(map.getOrDefault("minLatencyMs", "-1")));
    monitorResponseBuilder.latencyMs95th(Double.parseDouble(map.getOrDefault("latencyMs95th", "-1")));
    monitorResponseBuilder.latencyMs5th(Double.parseDouble(map.getOrDefault("latencyMs5th", "-1")));
    monitorResponses.add(monitorResponseBuilder.build());
  }

  private String resolveMonitorFile(String historyName) {
    String tmpMonitorFile = PathConstant.getTmpPath() + "/" + PathConstant.getMonitorName();
    if (StringUtils.isBlank(historyName)) {
      return FileUtil.exists(tmpMonitorFile) ? tmpMonitorFile : null;
    }

    String historyMonitorFile =
        PathConstant.RESULTS_PATH + "/" + historyName + "/" + PathConstant.getMonitorName();
    if (FileUtil.exists(historyMonitorFile)) {
      return historyMonitorFile;
    }

    if (FileUtil.exists(tmpMonitorFile)) {
      return tmpMonitorFile;
    }
    return null;
  }

  /**
   * 历史测试检索（归档检索）：扫描 results/&lt;historyName&gt;/ 的元信息与产物，支持
   * keyword / dbType / workload / orchestration / dbKey / label / fault / source /
   * success / complete / since / until + 分页排序。
   * 只读结果目录，不搬运原始数据；曲线与韧性三角所需数据仍按 historyName 取用。
   */
  @GetMapping("/bench/history/query")
  public Map<String, Object> queryHistory(@RequestParam Map<String, String> params) {
    return historyQueryService.query(params);
  }

  /** 归档动作：打标签 / 写备注（旧运行无 run-meta.json 时自动补建）。 */
  @PostMapping("/bench/history/label")
  public ResponseEntity<Map<String, Object>> labelHistory(@RequestBody RunLabelRequest request) {
    try {
      return ResponseEntity.ok(runArchiveService.updateLabels(
          request.getHistoryName(), request.getLabels(), request.getNote()));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    } catch (IOException e) {
      LOG.error("Failed to update archive labels for {}: {}", request.getHistoryName(), e.getMessage());
      return ResponseEntity.internalServerError().body(Map.of("error", "写入归档元信息失败"));
    }
  }

  /**
   * 归档动作：把一轮运行的产物导出为 zip。默认排除 raw.csv（单轮 200 MB+，其余合计仅几百 KB）；
   * include=summary,monitor,metric,fault,config,charts,detail,raw / all 可自选。
   */
  @GetMapping("/bench/history/export")
  public ResponseEntity<Resource> exportHistory(@RequestParam(name = "historyName") String historyName,
      @RequestParam(name = "include", required = false) String include) {
    try {
      Path zip = runArchiveService.exportZip(historyName, include);
      FileSystemResource resource = new FileSystemResource(zip.toFile());
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zip.getFileName() + "\"")
          .contentType(MediaType.parseMediaType("application/zip"))
          .contentLength(resource.contentLength())
          .body(resource);
    } catch (IllegalArgumentException e) {
      LOG.warn("Archive export rejected: {}", e.getMessage());
      return ResponseEntity.notFound().build();
    } catch (IOException e) {
      LOG.error("Failed to export archive zip for {}: {}", historyName, e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  @GetMapping("/bench/history/list")
  public TestSuiteHistoryResponse getHistoryList(
    @RequestParam(name = "testSuiteName", required = false) String testSuiteName,
    @RequestParam(name = "dbType", required = false) String dbType) {
    List<String> history;
    if (testSuiteName != null && !testSuiteName.isEmpty()) {
      history = TestSuiteUtils.getResultPath(testSuiteName);
    } else {
      history = FileUtil.listSubDirectoryNames(PathConstant.RESULTS_PATH);
    }
    if (dbType != null && !dbType.isEmpty()) {
      history = TestSuiteUtils.filterDbms(history,dbType);
    }

    TestSuiteHistoryResponse response = TestSuiteHistoryResponse.create(history);
    Map<String, BenchMarkStatus> statusMap = new HashMap<>();
    history.forEach(
        historyName -> {
          BenchMarkStatus historyStatus = getHistoryStatus(historyName);
          if (historyStatus != null) {
            statusMap.put(historyName, historyStatus);
          }
        });
    response.setStatusMap(statusMap);

    return response;
  }

  @GetMapping("/bench/event")
  public String getEvent(
    @RequestParam(name = "testSuiteName") String testSuiteName,
    @RequestParam(name = "historyName", required = false) String historyName) {
    if (historyName != null && !historyName.startsWith(testSuiteName)) {
      return null;

    }
    if (StringUtils.isBlank(historyName)) {
      List<String> resultPath = TestSuiteUtils.getResultPath(testSuiteName);
      if (resultPath.isEmpty()) {
        return null;
      }
      return MetricUtils.getEvent(resultPath.getFirst());
    }
    return MetricUtils.getEvent(historyName);
  }

  @GetMapping("/bench/inject/info")
  public List<FaultInjectInfo> getFaultInjectionInfo(
    @RequestParam(name = "historyName", required = false) String historyName) {
    String faultInjectPath = PathConstant.getTmpFaultInjectPath();
    if (historyName != null && !historyName.isEmpty()) {
      faultInjectPath = PathConstant.getFaultInjectPath(historyName);
    }
    if (!FileUtil.exists(faultInjectPath)) {
      // 文件不存在属于预期场景：尚未进行故障注入，或 tmp 目录被新一轮压测重建
      LOG.info("No fault injection file at {}, skip", faultInjectPath);
      return List.of();
    }
    String faultJson = null;
    try {
      List<String> list = FileUtil.readWithLock(faultInjectPath);
      if (!list.isEmpty()) {
        faultJson = list.getFirst();
      }
    } catch (IOException e) {
      LOG.error(
        "Error reading fault information from {}, err: {}", faultInjectPath, e.getMessage());
      return List.of();
    }
    if (StringUtils.isBlank(faultJson)) {
      return List.of();
    }
    return JSONUtil.fromJsonToList(faultJson, FaultInjectInfo.class);
  }

  @GetMapping("/bench/dbtype")
  public List<String> getDBType(){

    return FileUtil.listSubDirectoryNames(PathConstant.CONFIG_PATH);

  }

  @GetMapping("/bench/workload")
  public Set<String> getWorkload(@RequestParam(name = "dbtype", required = false) String dbtype){
    // 如果没有指定，列出所有工作负载
    if (StringUtils.isEmpty(dbtype)) {
      Set<String> resulsts = new HashSet<>();
      List<String> types = this.getDBType();
      for (String type : types) {
        try {
          String dbTypePath = PathConstant.getDBTypePath(type);
          resulsts.addAll(FileUtil.getWorkLoads(dbTypePath));
        } catch (IOException e) {
          LOG.error(
                  "ocurr error reading when get workload form dir {}, err: {}", type, e.getMessage());
        }
      }
      return resulsts;
    } else {
      String dbTypePath = PathConstant.getDBTypePath(dbtype);
      try {
        return FileUtil.getWorkLoads(dbTypePath);
      } catch (IOException e) {
        LOG.error(
                "ocurr error reading when get workload form dir {}, err: {}", dbTypePath, e.getMessage());
        return Set.of();
      }

    }

  }
}
