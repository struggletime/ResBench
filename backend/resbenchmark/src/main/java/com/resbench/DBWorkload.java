/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.resbench;

import com.resbench.common.fault.Fault;
import com.resbench.common.config.MonitorInfo;
import com.resbench.api.BenchmarkModule;
import com.resbench.api.TransactionType;
import com.resbench.api.TransactionTypes;
import com.resbench.benchmarks.smallbank.SmallBankConstants;
import com.resbench.common.config.ConfigManager;
import com.resbench.common.fault.Case;
import com.resbench.common.fault.AutoTimingConfig;
import com.resbench.common.fault.Command;
import com.resbench.service.FileFaultTargetService;
import com.resbench.common.fault.TestSuite;
import com.resbench.common.constant.PathConstant;
import com.resbench.impl.MonitoringManager;
import com.resbench.types.DatabaseType;
import com.resbench.types.State;
import com.resbench.common.util.ClassUtil;
import com.resbench.common.util.CommandExecutor;
import com.resbench.common.util.FileUtil;
import com.resbench.common.util.JSONSerializable;
import com.resbench.common.util.JSONUtil;
import com.resbench.util.ResultWriter;
import com.resbench.util.StringUtil;
import com.resbench.common.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.collections4.map.ListOrderedMap;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DBWorkload {
  private static final Logger LOG = LoggerFactory.getLogger(DBWorkload.class);

  private static final String SINGLE_LINE = StringUtil.repeat("=", 70);

  private static final String RATE_DISABLED = "disabled"; // tps will be 0

  private static final String RATE_UNLIMITED = "unlimited"; // run as quickly as possible

  public static long result_begin;

  /**
   * @param args
   * @throws Exception
   */
  public static void exec(String[] args) throws Exception {

    // step1: 配置解析
    ConfigManager  configManager = new ConfigManager(args);
    if (!configManager.checkArgs()) {
      printUsage(configManager.getOptions());
      return;
    }

    // Monitoring setup.
    MonitorInfo monitorInfo = configManager.getMonitorInfo();

    CommandLine argsLine = configManager.getArgsLine();

    // -------------------------------------------------------------------
    // GET PLUGIN LIST
    // -------------------------------------------------------------------

    //当前只支持一个bencmark
    String targetBenchmark = configManager.getWorkLoadNameList()[0];

    // Use this list for filtering of the output
    List<TransactionType> activeTXTypes = new ArrayList<>();

    // Load the configuration for each benchmark
      String pluginTest = "[@bench='" + targetBenchmark + "']";
      int terminals = configManager.getTerminals();
      double selectivity = configManager.getSelectivity();
      // ----------------------------------------------------------------
      // BEGIN LOADING WORKLOAD CONFIGURATION
      // ----------------------------------------------------------------

      XMLConfiguration workLoadConfig = configManager.getWorkLoadConfig();

      WorkloadConfiguration wrkld = new WorkloadConfiguration();
      wrkld.setBenchmarkName(targetBenchmark);
      wrkld.setXmlConfig(workLoadConfig);

      // Init faults
      wrkld.setFaults(Fault.loadFaultFromXML(workLoadConfig.getDocument()));
      initWorkloadConfiguration(wrkld,configManager,monitorInfo);

      // ----------------------------------------------------------------
      // CREATE BENCHMARK MODULE
      // ----------------------------------------------------------------
      BenchmarkModule bench =
          createBenchmarkModule(targetBenchmark, wrkld, configManager);
      wrkld.setBenchmark(bench);
      // ----------------------------------------------------------------
      // LOAD TRANSACTION DESCRIPTIONS
      // ----------------------------------------------------------------
      int numTxnTypes = configManager.getTransTypeNumByString(pluginTest);

      if (numTxnTypes == 0) {
        // if it is a single workload run, <transactiontypes /> w/o attribute is used
        pluginTest = "[not(@bench)]";
        numTxnTypes = configManager.getTransTypeNumByString(pluginTest);
      }

      TransactionTypes tt =
          buildTransactionTypes(numTxnTypes, pluginTest, workLoadConfig, bench, activeTXTypes);
      wrkld.setTransTypes(tt);
      LOG.debug("Using the following transaction types: {}", tt);

      // Read in the groupings of transactions (if any) defined for this
      // benchmark
      readGroupings(workLoadConfig, pluginTest, numTxnTypes);

      // ----------------------------------------------------------------
      // WORKLOAD CONFIGURATION
      // ----------------------------------------------------------------
      int size = workLoadConfig.configurationsAt("/works/work").size();
      for (int i = 1; i < size + 1; i++) {
        final HierarchicalConfiguration<ImmutableNode> work =
            workLoadConfig.configurationAt("works/work[" + i + "]");

        Phase phase =
            buildPhase(
                targetBenchmark,
                wrkld.getFaults(),
                configManager.getTestSuiteConfig(),
                work,
                pluginTest,
                i,
                terminals,
                1);

        wrkld.addPhase(phase);
      }

      // CHECKING INPUT PHASES
      checkInputPhases(wrkld, numTxnTypes);

      // Generate the dialect map
      wrkld.initDriver();

    // Export StatementDialects
    exportStatementDialects(argsLine, bench);

    // Create the Benchmark's Database
    createBenchmarkDatabase(argsLine, bench);

    // Refresh the catalog.
    refreshCatalog(bench);

    // Clear the Benchmark's Database
    clearBenchmarkDatabase(argsLine, bench);

    // Execute Loader
    executeLoader(argsLine, bench);

    // @auto: 哨兵的延后解析：这一轮要建表/灌数据的话，文件号是刚刚才生成的（提交那一刻还不存在，
    // 所以 StartByFormService 故意把哨兵原样写进了配置）。赶在负载与故障注入开始之前换成真实路径 ——
    // 顺序就是「先 reload 完成 → 再确认文件故障的参数 → 再从第一个测试开始跑」。
    resolveDeferredFaultTargets(argsLine, bench, wrkld, targetBenchmark);

    // Anonymize Datasets
    // Currently, the system only parses the config but does not run any anonymization!
    // Will be added in the future
    anonymizeDatasets(argsLine, configManager.getWorkLoadConfig(), configManager.getConfigFile());

    wrkld.initWorkersAndState();

    //故障注入初始化，并订阅事件
    wrkld.faultInit();

    // 如果指定了 run-id，用它作为 monitoringHistoryName，确保故障注入和监控模块使用与结果目录一致的名称
    String runId = argsLine.getOptionValue("run-id");
    if (runId != null && !runId.isEmpty()) {
      wrkld.setMonitoringHistoryName(runId);
    }

    //监控模块初始化
    MonitoringManager monitoringManager = wrkld.monitorInit(monitorInfo);

    // Execute Workload
    executeWorkload(argsLine, bench, monitoringManager, activeTXTypes, configManager.getWorkLoadConfig());
  }

    private static WorkloadConfiguration initWorkloadConfiguration(
            WorkloadConfiguration wrkld, ConfigManager configManager, MonitorInfo monitorInfo) {

         wrkld.setNodes(configManager.getDbConfig().getNodes());

        // Pull in database configuration
        String type = configManager.getDbConfig().getType();
        wrkld.setDatabaseType(DatabaseType.get(type));
        wrkld.setDriverClass(configManager.getDbConfig().getDriverClass());
        wrkld.setType(type);
        wrkld.setUrl(configManager.getDbConfig().getUrl());
        wrkld.setUsername(configManager.getDbConfig().getUsername());
        wrkld.setPassword(configManager.getDbConfig().getPassword());
        wrkld.setRandomSeed(configManager.getDbConfig().getRandomSeed());
        wrkld.setBatchSize(configManager.getDbConfig().getBatchSize());
        wrkld.setMaxRetries(configManager.getDbConfig().getMaxRetries());
        wrkld.setNewConnectionPerTxn(configManager.getDbConfig().isNewConnectionPerTxn());
        wrkld.setReconnectOnConnectionFailure(
                configManager.getDbConfig().getReconnectOnConnectionFailure());

        wrkld.setUserGroupNum(configManager.getDbConfig().getUserGroupNum());

        wrkld.setTerminals(configManager.getTerminals());
        wrkld.setLoaderThreads(configManager.getDbConfig().getLoaderThreads());

        wrkld.setIsolationMode(configManager.getDbConfig().getIsolationMode());
        wrkld.setScaleFactor(configManager.getDbConfig().getScaleFactor());
        wrkld.setDataCheckType(configManager.getDbConfig().getDataCheckType());
        wrkld.setDataDir(configManager.getDbConfig().getDataDir());
        wrkld.setDDLPath(configManager.getDbConfig().getDdlPath());
        wrkld.setSelectivity(configManager.getDbConfig().getSelectivity());

        // Set monitoring enabled, if all requirements are met.
        if (monitorInfo.getMonitoringInterval() > 0
                && monitorInfo.getMonitoringType() == MonitorInfo.MonitoringType.ADVANCED
                && DatabaseType.get(type).shouldCreateMonitoringPrefix()) {
            LOG.info("Advanced monitoring enabled, prefix will be added to queries.");
            wrkld.setAdvancedMonitoringEnabled(true);
        }
        return wrkld;
    }

  private static WorkloadConfiguration initWorkloadConfiguration(
      String targetBenchmark,
      XMLConfiguration xmlConfig,
      int terminals,
      String pluginTest,
      double selectivity,
      MonitorInfo monitorInfo) {
    WorkloadConfiguration wrkld = new WorkloadConfiguration();
    wrkld.setBenchmarkName(targetBenchmark);
    wrkld.setXmlConfig(xmlConfig);

    // Init faults
    wrkld.setFaults(Fault.loadFaultFromXML(xmlConfig.getDocument()));
    Element nodes = (Element) xmlConfig.getDocument().getElementsByTagName("nodes").item(0);
    NodeList nodeList = nodes.getElementsByTagName("node");
    for (int k = 0; k < nodeList.getLength(); k++) {
      wrkld.addNode(((Element) nodeList.item(k)).getTextContent());
    }
    // Pull in database configuration
    wrkld.setDatabaseType(DatabaseType.get(xmlConfig.getString("type")));
    wrkld.setDriverClass(xmlConfig.getString("driver"));
    wrkld.setType(xmlConfig.getString("type"));
    wrkld.setUrl(xmlConfig.getString("url"));
    wrkld.setUsername(xmlConfig.getString("username"));
    wrkld.setPassword(xmlConfig.getString("password"));
    wrkld.setRandomSeed(xmlConfig.getInt("randomSeed", -1));
    wrkld.setBatchSize(xmlConfig.getInt("batchsize", 128));
    wrkld.setMaxRetries(xmlConfig.getInt("retries", 1));
    wrkld.setNewConnectionPerTxn(xmlConfig.getBoolean("newConnectionPerTxn", false));
    wrkld.setReconnectOnConnectionFailure(
        xmlConfig.getBoolean("reconnectOnConnectionFailure", false));

    wrkld.setUserGroupNum(xmlConfig.getInt("usergroup", 5));

    wrkld.setTerminals(terminals);

    if (xmlConfig.containsKey("loaderThreads")) {
      int loaderThreads = xmlConfig.getInt("loaderThreads");
      wrkld.setLoaderThreads(loaderThreads);
    }

    String isolationMode = getIsolationMode(xmlConfig, pluginTest);
    wrkld.setIsolationMode(isolationMode);
    wrkld.setScaleFactor(xmlConfig.getDouble("scalefactor", 1.0));
    wrkld.setDataCheckType(
        xmlConfig.getString("data_cktype", SmallBankConstants.DATA_CK_TYPE.ONLY_SPECIAL.name()));
    wrkld.setDataDir(xmlConfig.getString("datadir", "."));
    wrkld.setDDLPath(xmlConfig.getString("ddlpath", null));

    wrkld.setSelectivity(selectivity);

    // Set monitoring enabled, if all requirements are met.
    if (monitorInfo.getMonitoringInterval() > 0
        && monitorInfo.getMonitoringType() == MonitorInfo.MonitoringType.ADVANCED
        && DatabaseType.get(xmlConfig.getString("type")).shouldCreateMonitoringPrefix()) {
      LOG.info("Advanced monitoring enabled, prefix will be added to queries.");
      wrkld.setAdvancedMonitoringEnabled(true);
    }
    return wrkld;
  }

  private static TransactionTypes buildTransactionTypes(
      int numTxnTypes,
      String pluginTest,
      XMLConfiguration xmlConfig,
      BenchmarkModule bench,
      List<TransactionType> activeTXTypes) {
    List<TransactionType> ttypes = new ArrayList<>();
    ttypes.add(TransactionType.INVALID);
    int txnIdOffset = 0;
    for (int i = 1; i <= numTxnTypes; i++) {
      String key = "transactiontypes" + pluginTest + "/transactiontype[" + i + "]";
      String txnName = xmlConfig.getString(key + "/name");

      // Get ID if specified; else increment from last one.
      int txnId = i;
      if (xmlConfig.containsKey(key + "/id")) {
        txnId = xmlConfig.getInt(key + "/id");
      }

      long preExecutionWait = 0;
      if (xmlConfig.containsKey(key + "/preExecutionWait")) {
        preExecutionWait = xmlConfig.getLong(key + "/preExecutionWait");
      }

      long postExecutionWait = 0;
      if (xmlConfig.containsKey(key + "/postExecutionWait")) {
        postExecutionWait = xmlConfig.getLong(key + "/postExecutionWait");
      }

      // After load
      if (xmlConfig.containsKey("afterload")) {
        bench.setAfterLoadScriptPath(xmlConfig.getString("afterload"));
      }

      TransactionType tmpType =
          bench.initTransactionType(
              txnName, txnId + txnIdOffset, preExecutionWait, postExecutionWait);

      // Keep a reference for filtering
      activeTXTypes.add(tmpType);

      // Add a ref for the active TTypes in this benchmark
      ttypes.add(tmpType);
    }

    // Wrap the list of transactions and save them
    return new TransactionTypes(ttypes);
  }

  private static void executeWorkload(
      CommandLine argsLine,
      BenchmarkModule bench,
      MonitoringManager monitoringManager,
      List<TransactionType> activeTXTypes,
      XMLConfiguration xmlConfig) {

    if (isBooleanOptionSet(argsLine, "execute")) {
      // Bombs away!
      try {
        Results r = runWorkload(bench, monitoringManager);
        LOG.error(
            "error:"
                + bench
                    .getWorkloadConfiguration()
                    .getPhases()
                    .get(0)
                    .getTestSuite()
                    .getName());
        writeOutputs(
            r,
            activeTXTypes,
            argsLine,
            xmlConfig,
            bench
                .getWorkloadConfiguration()
                .getPhases()
                .get(0)
                .getTestSuite()
                .getName(),
            bench.getWorkloadConfiguration().getMonitoringHistoryName());
        writeHistograms(r);

        if (argsLine.hasOption("json-histograms")) {
          String histogram_json = writeJSONHistograms(r);
          String fileName = argsLine.getOptionValue("json-histograms");
          FileUtil.writeStringToFile(new File(fileName), histogram_json);
          LOG.info("Histograms JSON Data: " + fileName);
        }

        if (r.getState() == State.ERROR) {
          throw new RuntimeException(
              "Errors encountered during benchmark execution. See output above for details.");
        }
      } catch (Throwable ex) {
        LOG.error("Unexpected error when executing benchmarks.", ex);
        System.err.printf("Unexpected error when executing benchmarks. err: %s %n", ex.getMessage());
        System.exit(1);
      }

    } else {
      LOG.info("Skipping benchmark workload execution");
    }
  }

  private static void anonymizeDatasets(
      CommandLine argsLine, XMLConfiguration xmlConfig, String configFile) {
    if (isBooleanOptionSet(argsLine, "anonymize")) {
      try {
        if (xmlConfig.configurationsAt("/anonymization/table").size() > 0) {
          applyAnonymization(xmlConfig, configFile);
        }
      } catch (Throwable ex) {
        LOG.error("Unexpected error when anonymizing datasets", ex);
        System.err.println("Unexpected error when anonymizing datasets, msg:" + ex.getMessage());
        System.exit(1);
      }
    }
  }

  private static void executeLoader(CommandLine argsLine, BenchmarkModule benchmark) {
    if (isBooleanOptionSet(argsLine, "load")) {
      try {

          LOG.info("Loading data into {} database...", benchmark.getBenchmarkName().toUpperCase());
          runLoader(benchmark);
          LOG.info(
              "Finished loading data into {} database...",
              benchmark.getBenchmarkName().toUpperCase());
      } catch (Throwable ex) {
        LOG.error("Unexpected error when loading benchmark database records.", ex);
        System.err.println("Unexpected error when loading benchmark database records. msg:" + ex.getMessage());
        System.exit(1);
      }

    } else {
      LOG.debug("Skipping loading benchmark database records");
    }
  }

  /**
   * 把故障命令里残留的 {@code @auto:<名>} 哨兵换成<b>建完库之后</b>的真实路径。
   *
   * <p>提交时（{@code StartByFormService}）如果这一轮会 create / load / reload，哨兵是<b>故意不解析</b>的：
   * 那一刻文件号还不存在。这里在 create + load 之后、负载与故障注入开始之前补上，跑的正是
   * "先 reload 完成 → 再确认文件故障的参数 → 再从第一个测试开始跑"。
   *
   * <p>注入 / 恢复 / 自检三份命令是同一时刻用同一份路径生成的，必须<b>一起换</b> —— 只换注入的话，
   * 恢复会拿着另一个文件的路径去收，收不回来。
   *
   * <p>没有哨兵就一次库都不连；这一轮不跑负载（execute=false）也直接跳过。
   */
  private static void resolveDeferredFaultTargets(
      CommandLine argsLine, BenchmarkModule bench, WorkloadConfiguration wrkld, String workload) {
    List<Fault> faults = wrkld.getFaults();
    if (faults == null || faults.isEmpty()) {
      return;
    }
    boolean pending = false;
    for (Fault fault : faults) {
      if (hasSentinel(fault.INJECTION) || hasSentinel(fault.RECOVERY) || hasSentinel(fault.CHECK)) {
        pending = true;
        break;
      }
    }
    if (!pending) {
      return;
    }
    if (!isBooleanOptionSet(argsLine, "execute")) {
      // 这一轮不跑负载 → 故障根本不会注入，别为了一个用不上的路径去连库
      LOG.info("Skipping @auto: sentinel resolution (execute=false): {}", workload);
      return;
    }

    Map<String, String> values;
    String dbType = "";
    try (Connection connection = bench.makeConnection()) {
      dbType = bench.getWorkloadConfiguration().getDatabaseType().name().toLowerCase(Locale.ROOT);
      values = FileFaultTargetService.resolveSentinels(connection, dbType, workload);
    } catch (Exception e) {
      // 绝不带着 @auto: 字面量往下跑：那样注入出去的是一个不存在的路径，跑出来是"注入成功但指标毫无
      // 变化"的假故障，比直接失败难查得多。这里让这一轮明确失败。
      String message = e.getMessage() == null || e.getMessage().isEmpty()
          ? e.getClass().getName() : e.getMessage();
      LOG.error("解析 @auto: 哨兵失败（建表/灌数据之后）: {}", message, e);
      throw new RuntimeException("@auto: 哨兵解析失败（建表/灌数据之后）：" + message, e);
    }
    LOG.info(
        "@auto: 哨兵已按刚建好的数据解析: dbType={}, workload={}, db-dir={}, hottest-file={},"
            + " last-segment={}, mid-segment={}, datadir={}",
        dbType, workload, values.get("db-dir"), values.get("hottest-file"),
        values.get("hottest-last-segment"), values.get("hottest-mid-segment"),
        values.get("datadir"));
    for (Fault fault : faults) {
      substituteSentinels(fault.INJECTION, values);
      substituteSentinels(fault.RECOVERY, values);
      substituteSentinels(fault.CHECK, values);
    }
  }

  private static boolean hasSentinel(Command command) {
    if (command == null) {
      return false;
    }
    if (FileFaultTargetService.hasSentinel(command.getCmd())) {
      return true;
    }
    for (String arg : command.getArgs()) {
      if (FileFaultTargetService.hasSentinel(arg)) {
        return true;
      }
    }
    return false;
  }

  private static void substituteSentinels(Command command, Map<String, String> values) {
    if (command == null) {
      return;
    }
    command.setCmd(FileFaultTargetService.substituteSentinels(command.getCmd(), values));
    String[] args = command.getArgs();
    String[] replaced = new String[args.length];
    for (int i = 0; i < args.length; i++) {
      replaced[i] = FileFaultTargetService.substituteSentinels(args[i], values);
    }
    command.setArgs(replaced);
  }

  private static void clearBenchmarkDatabase(
      CommandLine argsLine, BenchmarkModule benchmark) {
    if (isBooleanOptionSet(argsLine, "clear")) {
      try {

          LOG.info("Clearing {} database...", benchmark.getBenchmarkName().toUpperCase());
          benchmark.refreshCatalog();
          benchmark.clearDatabase();
          benchmark.refreshCatalog();
          LOG.info("Finished clearing {} database...", benchmark.getBenchmarkName().toUpperCase());
      } catch (Throwable ex) {
        LOG.error("Unexpected error when clearing benchmark database tables.", ex);
        System.err.println("Unexpected error when clearing benchmark database tables. msg:" + ex.getMessage());
        System.exit(1);
      }
    } else {
      LOG.debug("Skipping clearing benchmark database tables");
    }
  }

  private static void refreshCatalog(BenchmarkModule benchmark) throws SQLException {
      benchmark.refreshCatalog();

  }

  private static void createBenchmarkDatabase(
      CommandLine argsLine, BenchmarkModule benchmark) {
    if (isBooleanOptionSet(argsLine, "create")) {
      try {

          LOG.info("Creating new {} database...", benchmark.getBenchmarkName().toUpperCase());
          runCreator(benchmark);
          LOG.info(
              "Finished creating new {} database...", benchmark.getBenchmarkName().toUpperCase());
      } catch (Throwable ex) {
        LOG.error("Unexpected error when creating benchmark database tables.", ex);
        System.err.println("Unexpected error when creating benchmark database tables. msg:" + ex.getMessage());
        System.exit(1);
      }
    } else {
      LOG.debug("Skipping creating benchmark database tables");
    }
  }

  private static void exportStatementDialects(
      CommandLine argsLine, BenchmarkModule bench) {
    if (isBooleanOptionSet(argsLine, "dialects-export")) {
      if (bench.getStatementDialects() != null) {
        LOG.info("Exporting StatementDialects for {}", bench);
        String xml =
            bench
                .getStatementDialects()
                .export(
                    bench.getWorkloadConfiguration().getDatabaseType(),
                    bench.getProcedures().values());
        LOG.debug(xml);
        System.exit(0);
      }
      throw new RuntimeException("No StatementDialects is available for " + bench);
    }
  }

  private static String getIsolationMode(XMLConfiguration xmlConfig, String pluginTest) {
    String isolationMode =
        xmlConfig.getString("isolation[not(@bench)]", "TRANSACTION_SERIALIZABLE");
    isolationMode = xmlConfig.getString("isolation" + pluginTest, isolationMode);
    return isolationMode;
  }



  private static void readGroupings(
      XMLConfiguration xmlConfig, String pluginTest, int numTxnTypes) {
    int numGroupings =
        xmlConfig.configurationsAt("transactiontypes" + pluginTest + "/groupings/grouping").size();
    LOG.debug("Num groupings: {}", numGroupings);
    for (int i = 1; i < numGroupings + 1; i++) {
      String key = "transactiontypes" + pluginTest + "/groupings/grouping[" + i + "]";

      // Get the name for the grouping and make sure it's valid.
      String groupingName = xmlConfig.getString(key + "/name").toLowerCase();
      if (!groupingName.matches("^[a-z]\\w*$")) {
        LOG.error(
            String.format(
                "Grouping name \"%s\" is invalid."
                    + " Must begin with a letter and contain only"
                    + " alphanumeric characters.",
                groupingName));
        System.exit(-1);
      } else if (groupingName.equals("all")) {
        LOG.error("Grouping name \"all\" is reserved." + " Please pick a different name.");
        System.exit(-1);
      }

      // Get the weights for this grouping and make sure that there
      // is an appropriate number of them.
      List<String> groupingWeights =
          Arrays.asList(xmlConfig.getString(key + "/weights").split("\\s*,\\s*"));
      if (groupingWeights.size() != numTxnTypes) {
        LOG.error(
            String.format(
                "Grouping \"%s\" has %d weights,"
                    + " but there are %d transactions in this"
                    + " benchmark.",
                groupingName, groupingWeights.size(), numTxnTypes));
        System.exit(-1);
      }

      LOG.debug("Creating grouping with name, weights: {}, {}", groupingName, groupingWeights);
    }
  }

  private static BenchmarkModule createBenchmarkModule(
      String targetBenchmark,
      WorkloadConfiguration wrkld,
      ConfigManager configManager)
      throws ParseException {

    XMLConfiguration pluginConfig = configManager.getWorkLoadPluginConfig();

    String classname = pluginConfig.getString("/plugin[@name='" + targetBenchmark + "']");

    if (classname == null) {
      throw new ParseException("Plugin " + targetBenchmark + " is undefined in config/plugin.xml");
    }

    BenchmarkModule bench =
        ClassUtil.newInstance(
            classname, new Object[] {wrkld}, new Class<?>[] {WorkloadConfiguration.class});

    printDebugInfo(targetBenchmark, classname, configManager.getConfigFile(), wrkld, configManager.getSelectivity());
    return bench;
  }

  private static Phase buildPhase(
      String targetBenchmark,
      List<Fault> faults,
      XMLConfiguration faultConfig,
      HierarchicalConfiguration<ImmutableNode> work,
      String pluginTest,
      int i,
      int terminals,
      int targetBenchmarkLength) {
    double rate = 1;
    boolean rateLimited = true;
    boolean disabled = false;
    String testSuitName = work.getString("testSuite");
    TestSuite testSuite = new TestSuite();
    List<HierarchicalConfiguration<ImmutableNode>> suites =
        faultConfig.configurationsAt("testSuite");
    for (HierarchicalConfiguration<ImmutableNode> suite : suites) {
      String name = suite.getString("@name");
      if (testSuitName.equals(name)) {
        testSuite.setName(name);

        // 读取 mode 属性，默认为 fixed-time
        String mode = suite.getString("@mode", TestSuite.MODE_FIXED_TIME);
        testSuite.setMode(mode);
        LOG.info("TestSuite '{}' using mode: {}", name, mode);

        boolean autoTiming = testSuite.isAutoTimingMode();
        if (autoTiming) {
          logWorkStructureIfEnabled(work, i, name);
          testSuite.setAutoTimingConfig(parseAutoTimingConfig(work));
          LOG.info("TestSuite '{}' auto-timing config: {}", name, testSuite.getAutoTimingConfig());
          LOG.info(
              "TestSuite '{}' auto-timing dynamic params => enabled: {}, baselineChecks: {}, k: {}, lower: {}, upper: {}",
              name,
              testSuite.getAutoTimingConfig().isDynamicThresholdEnabled(),
              testSuite.getAutoTimingConfig().getDynamicBaselineChecks(),
              testSuite.getAutoTimingConfig().getDynamicThresholdScaleK(),
              testSuite.getAutoTimingConfig().getDynamicThresholdLowerBound(),
              testSuite.getAutoTimingConfig().getDynamicThresholdUpperBound());
        }

        List<HierarchicalConfiguration<ImmutableNode>> cases = suite.configurationsAt("case");
        System.out.printf(String.valueOf(cases.size()));
        for (HierarchicalConfiguration<ImmutableNode> c : cases) {
          String id = c.getString("@id");
          Fault fault =
              faults.stream().filter(x -> x.ID == Integer.parseInt(id)).findFirst().orElse(null);
          if (fault == null) {
            throw new RuntimeException("No fault found for id " + id);
          }
          // 根据模式创建 Case
          Case faultCase = new Case(c, fault, autoTiming);
          testSuite.addWork(faultCase);
        }
      }
    }

    // can be "disabled", "unlimited" or a number
    String rate_string;
    rate_string = work.getString("rate[not(@bench)]", "");
    rate_string = work.getString("rate" + pluginTest, rate_string);
    if (rate_string.equals(RATE_DISABLED)) {
      disabled = true;
    } else if (rate_string.equals(RATE_UNLIMITED)) {
      rateLimited = false;
    } else if (rate_string.isEmpty()) {
      LOG.error(
          String.format(
              "Please specify the rate for phase %d and workload %s", i, targetBenchmark));
      System.exit(-1);
    } else {
      try {
        rate = Double.parseDouble(rate_string);
        if (rate <= 0) {
          LOG.error("Rate limit must be at least 0. Use unlimited or disabled values instead.");
          System.exit(-1);
        }
      } catch (NumberFormatException e) {
        LOG.error(
            String.format(
                "Rate string must be '%s', '%s' or a number", RATE_DISABLED, RATE_UNLIMITED));
        System.exit(-1);
      }
    }

    Phase.Arrival arrival = getArrival(work);

    // We now have the option to run all queries exactly once in
    // a serial (rather than random) order.
    boolean serial = Boolean.parseBoolean(work.getString("serial", Boolean.FALSE.toString()));

    int activeTerminals = getActiveTerminals(work, terminals, pluginTest, serial, i);

    int time = work.getInt("/time", 0);

    boolean timed = isTimed(time, serial);

    int warmup = getWarmup(work);

    List<String> weight_strings = getWeightStrings(targetBenchmarkLength, work, pluginTest);
    ArrayList<Double> weights = getWeights(weight_strings);

    return new Phase(
        targetBenchmark,
        i,
        time,
        warmup,
        rate,
        weights,
        rateLimited,
        disabled,
        serial,
        timed,
        activeTerminals,
        arrival,
        testSuite);
  }

  private static void logWorkStructureIfEnabled(
      HierarchicalConfiguration<ImmutableNode> work, int phaseId, String testSuiteName) {
    Map<String, Object> workSnapshot = new LinkedHashMap<>();
    Iterator<String> keys = work.getKeys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = work.getProperty(key);
      workSnapshot.put(key, value);
    }

    LOG.info(
        "[work-debug] phase={}, testSuite='{}', work keys snapshot: {}",
        phaseId,
        testSuiteName,
        workSnapshot);
//         2 ate=1000000, weights=5,25,20,15,15,20, testSuite=auto-timing-basic-test, autoTiming/checkIntervalMs=5001, autoTiming/maxWaitMs=300000, autoTiming/windowSize=31, autoTiming/cvThreshold=0.08, autoTiming/
//   1 null, windowSize=null, cvThreshold=null, injectTimeoutMs=null, recoverTimeoutMs=null, dynamicThresholdEnabled=null, dynamicBaselineChecks=null, dynamicThresholdScaleK=null, dynamicThresholdLowerBound=n
// 563 30, cvThreshold=0.05, injectTimeoutMs=30000, recoverTimeoutMs=30000, dynamicThresholdEnabled=false, dynamicBaselineChecks=30, dynamicThresholdScaleK=1.0, dynamicThresholdLowerBound=0.08, dynamicthresho
    LOG.info(
        "[work-debug] phase={}, testSuite='{}', autoTiming raw => checkIntervalMs={}, maxWaitMs={}, windowSize={}, cvThreshold={}, injectTimeoutMs={}, recoverTimeoutMs={}, dynamicThresholdEnabled={}, dynamicBaselineChecks={}, dynamicThresholdScaleK={}, dynamicThresholdLowerBound={}, dynamicThresholdUpperBound={}",
        phaseId,
        testSuiteName,
        getWorkValueBySuffix(workSnapshot, "autoTiming/checkIntervalMs"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/maxWaitMs"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/windowSize"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/cvThreshold"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/injectTimeoutMs"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/recoverTimeoutMs"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/dynamicThresholdEnabled"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/dynamicBaselineChecks"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/dynamicThresholdScaleK"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/dynamicThresholdLowerBound"),
        getWorkValueBySuffix(workSnapshot, "autoTiming/dynamicThresholdUpperBound"));
  }

  private static String getWorkValueBySuffix(Map<String, Object> workSnapshot, String suffixKey) {
    for (Map.Entry<String, Object> entry : workSnapshot.entrySet()) {
      String key = entry.getKey();
      if (key.equals(suffixKey) || key.endsWith("/" + suffixKey) || key.endsWith(suffixKey)) {
        Object value = entry.getValue();
        return value == null ? "null" : String.valueOf(value);
      }
    }
    return "null";
  }

  private static AutoTimingConfig parseAutoTimingConfig(HierarchicalConfiguration<ImmutableNode> work) {
    AutoTimingConfig autoTimingConfig = AutoTimingConfig.createDefault();
    autoTimingConfig.setCheckIntervalMs(
        parseLongBySuffix(
            work,
            "autoTiming/checkIntervalMs",
            AutoTimingConfig.DEFAULT_CHECK_INTERVAL_MS));
    autoTimingConfig.setMaxWaitMs(
        parseLongBySuffix(
            work,
            "autoTiming/maxWaitMs",
            AutoTimingConfig.DEFAULT_MAX_WAIT_MS));
    autoTimingConfig.setWindowSize(
        parseIntBySuffix(
            work,
            "autoTiming/windowSize",
            AutoTimingConfig.DEFAULT_WINDOW_SIZE));
    autoTimingConfig.setCvThreshold(
        parseDoubleBySuffix(
            work,
            "autoTiming/cvThreshold",
            AutoTimingConfig.DEFAULT_CV_THRESHOLD));
    autoTimingConfig.setInjectTimeoutMs(
        parseIntBySuffix(
            work,
            "autoTiming/injectTimeoutMs",
            AutoTimingConfig.DEFAULT_INJECT_TIMEOUT_MS));
    autoTimingConfig.setRecoverTimeoutMs(
        parseIntBySuffix(
            work,
            "autoTiming/recoverTimeoutMs",
            AutoTimingConfig.DEFAULT_RECOVER_TIMEOUT_MS));
    autoTimingConfig.setDynamicThresholdEnabled(
        parseBooleanBySuffix(
            work,
            "autoTiming/dynamicThresholdEnabled",
            AutoTimingConfig.DEFAULT_DYNAMIC_THRESHOLD_ENABLED));
    autoTimingConfig.setDynamicBaselineChecks(
        parseIntBySuffix(
            work,
            "autoTiming/dynamicBaselineChecks",
            AutoTimingConfig.DEFAULT_DYNAMIC_BASELINE_CHECKS));
    autoTimingConfig.setDynamicThresholdScaleK(
        parseDoubleBySuffix(
            work,
            "autoTiming/dynamicThresholdScaleK",
            AutoTimingConfig.DEFAULT_DYNAMIC_THRESHOLD_SCALE_K));
    autoTimingConfig.setDynamicThresholdLowerBound(
        parseDoubleBySuffix(
            work,
            "autoTiming/dynamicThresholdLowerBound",
            AutoTimingConfig.DEFAULT_DYNAMIC_THRESHOLD_LOWER_BOUND));
    autoTimingConfig.setDynamicThresholdUpperBound(
        parseDoubleBySuffix(
            work,
            "autoTiming/dynamicThresholdUpperBound",
            AutoTimingConfig.DEFAULT_DYNAMIC_THRESHOLD_UPPER_BOUND));
    autoTimingConfig.sanitize();
    return autoTimingConfig;
  }

  private static String getWorkRawValueBySuffix(
      HierarchicalConfiguration<ImmutableNode> work, String suffixKey) {
    Iterator<String> keys = work.getKeys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (key.equals(suffixKey) || key.endsWith("/" + suffixKey) || key.endsWith(suffixKey)) {
        Object value = work.getProperty(key);
        return value == null ? null : String.valueOf(value);
      }
    }
    return null;
  }

  private static long parseLongBySuffix(
      HierarchicalConfiguration<ImmutableNode> work, String suffixKey, long defaultValue) {
    String raw = getWorkRawValueBySuffix(work, suffixKey);
    if (raw == null) {
      return defaultValue;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static int parseIntBySuffix(
      HierarchicalConfiguration<ImmutableNode> work, String suffixKey, int defaultValue) {
    String raw = getWorkRawValueBySuffix(work, suffixKey);
    if (raw == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static double parseDoubleBySuffix(
      HierarchicalConfiguration<ImmutableNode> work, String suffixKey, double defaultValue) {
    String raw = getWorkRawValueBySuffix(work, suffixKey);
    if (raw == null) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static boolean parseBooleanBySuffix(
      HierarchicalConfiguration<ImmutableNode> work, String suffixKey, boolean defaultValue) {
    String raw = getWorkRawValueBySuffix(work, suffixKey);
    if (raw == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(raw.trim());
  }

  private static Phase.Arrival getArrival(HierarchicalConfiguration<ImmutableNode> work) {
    Phase.Arrival arrival = Phase.Arrival.REGULAR;
    String arrive = work.getString("@arrival", "regular");
    if (arrive.equalsIgnoreCase("POISSON")) {
      arrival = Phase.Arrival.POISSON;
    }
    return arrival;
  }


  private static int getActiveTerminals(
      HierarchicalConfiguration<ImmutableNode> work,
      int terminals,
      String pluginTest,
      boolean serial,
      int i) {
    int activeTerminals;
    activeTerminals = work.getInt("active_terminals[not(@bench)]", terminals);
    activeTerminals = work.getInt("active_terminals" + pluginTest, activeTerminals);
    // If using serial, we should have only one terminal
    if (serial && activeTerminals != 1) {
      LOG.warn("Serial ordering is enabled, so # of active terminals is clamped to 1.");
      activeTerminals = 1;
    }
    if (activeTerminals > terminals) {
      LOG.error(
          String.format(
              "Configuration error in work %d: "
                  + "Number of active terminals is bigger than the total number of terminals",
              i));
      System.exit(-1);
    }
    return activeTerminals;
  }

  private static boolean isTimed(int time, boolean serial) {
    boolean timed = (time > 0);
    if (!timed) {
      if (serial) {
        LOG.info("Timer disabled for serial run; will execute" + " all queries exactly once.");
      } else {
        LOG.error(
            "Must provide positive time bound for"
                + " non-serial executions. Either provide"
                + " a valid time or enable serial mode.");
        System.exit(-1);
      }
    } else if (serial) {
      LOG.info(
          "Timer enabled for serial run; will run queries"
              + " serially in a loop until the timer expires.");
    }
    return timed;
  }





  private static void printDebugInfo(
      String plugin,
      String classname,
      String configFile,
      WorkloadConfiguration wrkld,
      double selectivity) {
    Map<String, Object> initDebug = new ListOrderedMap<>();
    initDebug.put("Benchmark", String.format("%s {%s}", plugin.toUpperCase(), classname));
    initDebug.put("Configuration", configFile);
    initDebug.put("Type", wrkld.getDatabaseType());
    initDebug.put("Driver", wrkld.getDriverClass());
    initDebug.put("URL", wrkld.getUrl());
    initDebug.put("Isolation", wrkld.getIsolationString());
    initDebug.put("Batch Size", wrkld.getBatchSize());
    initDebug.put("Scale Factor", wrkld.getScaleFactor());
    initDebug.put("Terminals", wrkld.getTerminals());
    initDebug.put("New Connection Per Txn", wrkld.getNewConnectionPerTxn());
    initDebug.put("Reconnect on Connection Failure", wrkld.getReconnectOnConnectionFailure());

    if (selectivity != -1) {
      initDebug.put("Selectivity", selectivity);
    }

    LOG.info("{}\n\n{}", SINGLE_LINE, StringUtil.formatMaps(initDebug));
    LOG.info(SINGLE_LINE);
  }

  private static int getWarmup(HierarchicalConfiguration<ImmutableNode> work) {
    int warmup = work.getInt("/warmup", 0);
    if (warmup < 0) {
      LOG.error("Must provide non-negative time bound for" + " warmup.");
      System.exit(-1);
    }
    return warmup;
  }

  private static List<String> getWeightStrings(
      int targetBenchmarkLength, HierarchicalConfiguration<ImmutableNode> work, String pluginTest) {
    List<String> weight_strings;

    // use a workaround if there are multiple workloads or single
    // attributed workload
    if (targetBenchmarkLength > 1 || work.containsKey("weights[@bench]")) {
      weight_strings = Arrays.asList(work.getString("weights" + pluginTest).split("\\s*,\\s*"));
    } else {
      weight_strings = Arrays.asList(work.getString("weights[not(@bench)]").split("\\s*,\\s*"));
    }
    return weight_strings;
  }

  private static ArrayList<Double> getWeights(List<String> weight_strings) {
    ArrayList<Double> weights = new ArrayList<>();

    double totalWeight = 0;

    for (String weightString : weight_strings) {
      double weight = Double.parseDouble(weightString);
      totalWeight += weight;
      weights.add(weight);
    }

    long roundedWeight = Math.round(totalWeight);

    if (roundedWeight != 100) {
      LOG.warn(
          "rounded weight [{}] does not equal 100.  Original weight is [{}]",
          roundedWeight,
          totalWeight);
    }
    return weights;
  }

  private static void checkInputPhases(WorkloadConfiguration wrkld, int numTxnTypes) {
    int j = 0;
    for (Phase p : wrkld.getPhases()) {
      j++;
      if (p.getWeightCount() != numTxnTypes) {
        LOG.error(
            String.format(
                "Configuration files is inconsistent, phase %d contains %d weights but you defined %d transaction types",
                j, p.getWeightCount(), numTxnTypes));
        if (p.isSerial()) {
          LOG.error(
              "However, note that since this a serial phase, the weights are irrelevant (but still must be included---sorry).");
        }
        System.exit(-1);
      }
    }
  }


  private static void writeHistograms(Results r) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n");

    sb.append(StringUtil.bold("Completed Transactions:"))
        .append("\n")
        .append(r.getSuccess())
        .append("\n\n");

    sb.append(StringUtil.bold("Aborted Transactions:"))
        .append("\n")
        .append(r.getAbort())
        .append("\n\n");

    sb.append(StringUtil.bold("Rejected Transactions (Server Retry):"))
        .append("\n")
        .append(r.getRetry())
        .append("\n\n");

    sb.append(StringUtil.bold("Rejected Transactions (Retry Different):"))
        .append("\n")
        .append(r.getRetryDifferent())
        .append("\n\n");

    sb.append(StringUtil.bold("Unexpected SQL Errors:"))
        .append("\n")
        .append(r.getError())
        .append("\n\n");

    sb.append(StringUtil.bold("Unknown Status Transactions:"))
        .append("\n")
        .append(r.getUnknown())
        .append("\n\n");

    if (!r.getAbortMessages().isEmpty()) {
      sb.append("\n\n")
          .append(StringUtil.bold("User Aborts:"))
          .append("\n")
          .append(r.getAbortMessages());
    }

    LOG.info(SINGLE_LINE);
    LOG.info("Workload Histograms:\n{}", sb);
    LOG.info(SINGLE_LINE);
  }

  private static String writeJSONHistograms(Results r) {
    Map<String, JSONSerializable> map = new HashMap<>();
    map.put("completed", r.getSuccess());
    map.put("aborted", r.getAbort());
    map.put("rejected", r.getRetry());
    map.put("unexpected", r.getError());
    return JSONUtil.toJSONString(map);
  }

  /**
   * Write out the results for a benchmark run to a bunch of files
   *
   * @param r
   * @param activeTXTypes
   * @param argsLine
   * @param xmlConfig
   * @throws Exception
   */
  private static void writeOutputs(
      Results r,
      List<TransactionType> activeTXTypes,
      CommandLine argsLine,
      XMLConfiguration xmlConfig,
      String testSuitName,
      String monitoringHistoryName)
      throws Exception {

    // If an output directory is used, store the information
    String outputDirectory = "results";

    if (argsLine.hasOption("d")) {
      outputDirectory = argsLine.getOptionValue("d");
    }

    FileUtil.makeDirIfNotExists(outputDirectory);
    ResultWriter rw = new ResultWriter(r, xmlConfig, argsLine, testSuitName);
    String timeString = TimeUtil.getCurrentTimeString();
    String subDirectory = argsLine.getOptionValue("run-id");
    if (StringUtils.isBlank(subDirectory)) {
      subDirectory = StringUtils.isBlank(monitoringHistoryName)
          ? rw.getTestSuite() + "_" + rw.getBenchType() + "_" + timeString
          : monitoringHistoryName;
    }
    outputDirectory = outputDirectory + File.separator + subDirectory;
    LOG.info("Output directory: " + outputDirectory);
    FileUtil.makeDirIfNotExists(outputDirectory);

    String name = StringUtils.join(StringUtils.split(argsLine.getOptionValue("b"), ','), '-');

    String historyPrefix = rw.getTestSuite() + "_" + rw.getBenchType() + "_";
    String effectiveTimeString =
        subDirectory.startsWith(historyPrefix) ? subDirectory.substring(historyPrefix.length()) : timeString;
    String baseFileName = name + "_" + effectiveTimeString;

    int windowSize = Integer.parseInt(argsLine.getOptionValue("s", "5"));

    String rawFileName = baseFileName + ".raw.csv";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, rawFileName))) {
      LOG.info("Output Raw data into file: {}", rawFileName);
      rw.writeRaw(activeTXTypes, ps);
      ps.flush();
    }

    String sampleFileName = baseFileName + ".samples.csv";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, sampleFileName))) {
      LOG.info("Output samples into file: {}", sampleFileName);
      rw.writeSamples(ps);
      ps.flush();
    }

    String summaryFileName = baseFileName + ".summary.json";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, summaryFileName))) {
      LOG.info("Output summary data into file: {}", summaryFileName);
      rw.writeSummary(ps);
      ps.flush();
    }

    String paramsFileName = baseFileName + ".params.json";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, paramsFileName))) {
      LOG.info("Output DBMS parameters into file: {}", paramsFileName);
      rw.writeParams(ps);
      ps.flush();
    }

    if (rw.hasMetrics()) {
      String metricsFileName = baseFileName + ".metrics.json";
      try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, metricsFileName))) {
        LOG.info("Output DBMS metrics into file: {}", metricsFileName);
        rw.writeMetrics(ps);
        ps.flush();
      }
    }

    String configFileName = baseFileName + ".config.xml";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, configFileName))) {
      LOG.info("Output benchmark config into file: {}", configFileName);
      rw.writeConfig(ps);
      ps.flush();
    }

    String resultsFileName = baseFileName + ".results.csv";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, resultsFileName))) {
      LOG.info("Output results into file: {} with window size {}", resultsFileName, windowSize);
      rw.writeResults(windowSize, ps);
      ps.flush();
    }

    for (TransactionType t : activeTXTypes) {
      String fileName = baseFileName + ".results." + t.getName() + ".csv";
      try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, fileName))) {
        rw.writeResults(windowSize, ps, t);
        ps.flush();
      }
    }

    String faultInfoFileName = baseFileName + ".faults.csv";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, faultInfoFileName))) {
      LOG.info("Output faults infomation into file: {}", faultInfoFileName);
      rw.writeFaultInfos(ps);
      ps.flush();
    }

    String dataLossFileName = baseFileName + ".data_loss.csv";
    try (PrintStream ps = new PrintStream(FileUtil.joinPath(outputDirectory, dataLossFileName))) {
      LOG.info("Output data loss into file: {}", dataLossFileName);
      rw.writeDataLoss(ps);
      ps.flush();
    }

    /** Copy monitor and fault files into output directory */
    String monitorTargetFilePath = outputDirectory + File.separator + PathConstant.MONITOR_NAME;
    String monitorHistoryPath =
        PathConstant.RESULTS_PATH + File.separator + subDirectory + File.separator + PathConstant.MONITOR_NAME;
    String monitorTmpPath = "tmp" + File.separator + PathConstant.MONITOR_NAME;
    try {
      File monitorTargetFile = new File(monitorTargetFilePath);
      if (!monitorTargetFile.exists()) {
        File monitorHistoryFile = new File(monitorHistoryPath);
        File monitorTmpFile = new File(monitorTmpPath);
        if (monitorHistoryFile.exists() && !monitorHistoryPath.equals(monitorTargetFilePath)) {
          FileUtils.copyFile(monitorHistoryFile, monitorTargetFile);
        } else if (monitorTmpFile.exists()) {
          FileUtils.copyFile(monitorTmpFile, monitorTargetFile);
        } else {
          LOG.warn("No monitor file found for output directory: {}", outputDirectory);
        }
      }
    } catch (IOException e) {
      LOG.error(
          "Error while copying monitor file into {}, msg {}",
          monitorTargetFilePath,
          e.getMessage());
    }

    String faultsPathTargetFilePath = outputDirectory + File.separator + PathConstant.FAULT_INJECT_NAME;
    String faultsHistoryPath = PathConstant.getFaultInjectPath(subDirectory);
    String faultsTmpPath = PathConstant.getTmpFaultInjectPath();
    try {
      File faultTargetFile = new File(faultsPathTargetFilePath);
      if (!faultTargetFile.exists()) {
        File faultsHistoryFile = new File(faultsHistoryPath);
        File faultsTmpFile = new File(faultsTmpPath);
        if (faultsHistoryFile.exists() && !faultsHistoryPath.equals(faultsPathTargetFilePath)) {
          FileUtils.copyFile(faultsHistoryFile, faultTargetFile);
        } else if (faultsTmpFile.exists()) {
          FileUtils.copyFile(faultsTmpFile, faultTargetFile);
        } else {
          LOG.warn("No faultInject file found for output directory: {}", outputDirectory);
        }
      }
    } catch (IOException e) {
      LOG.error(
          "Error while copying faultInject file into {}, msg {}",
          faultsPathTargetFilePath,
          e.getMessage());
    }

    String jobStatePath = PathConstant.getTmpJobPath();
    try {
      String stateContent = FileUtil.readFileAsString(jobStatePath);
      if (stateContent != null) {
        stateContent = stateContent.replace("\"curState\":\"RUNNING\"", "\"curState\":\"GRACEFUL\"");
        FileUtil.writeStringToFile(new File(jobStatePath), stateContent);
      }
    } catch (Exception e) {
      LOG.error("Error updating early graceful state: {}", e.getMessage());
    }
    String jobStateTargetFilePath = outputDirectory + File.separator + PathConstant.getJobStateName();
    try {
      FileUtils.copyFile(new File(jobStatePath), new File(jobStateTargetFilePath));
    } catch (IOException e) {
      LOG.error(
          "Error while copying {} into file: {}, msg {}",
          jobStatePath,
          jobStateTargetFilePath,
          e.getMessage());
    }

    // run metric report shell synchronously to ensure metric.json is ready
    final File absOutputDir = new File(outputDirectory).getAbsoluteFile();
    final String userDir = System.getProperty("user.dir", ".");
    final File scriptFile = findScriptFile(userDir, "ResilienceMetricReport.py");
    if (scriptFile == null) {
      LOG.error("Cannot find ResilienceMetricReport.py. user.dir={}, outputDir={}",
          userDir, absOutputDir.getAbsolutePath());
    } else {
      final String scriptPath = scriptFile.getAbsolutePath();
      final File workingDir = scriptFile.getParentFile();
      final String pythonCmd = resolvePython();
      final String outputPath = absOutputDir.getAbsolutePath();
      LOG.info("Running {} {} -r {} (cwd={})",
          pythonCmd, scriptPath, outputPath, workingDir.getAbsolutePath());

      List<String> command = List.of(pythonCmd, scriptPath, "-r", outputPath);
      LOG.info("Full command: {}", String.join(" ", command));
      try {
        java.util.logging.Logger.getLogger("").log(java.util.logging.Level.INFO, "Python version check...");
        ProcessBuilder pb = new ProcessBuilder(pythonCmd, "--version");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String pyVer = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream())).readLine();
        LOG.info("Python interpreter: {}", pyVer);
      } catch (Exception e) {
        LOG.warn("Failed to check python version: {}", e.getMessage());
      }
      CommandExecutor.CommandResult result =
          CommandExecutor.run(command, workingDir, null, -1);
      LOG.info("Python script stdout (first run): {}", result.getStdout());
      LOG.info("Python script stderr (first run): {}", result.getStderr());
      if (!result.isSuccess()) {
        LOG.error("{} first run failed. exitCode={}", command, result.getExitCode());
      }
      // 第二次运行，确保跨故障依赖的目录已就绪
      CommandExecutor.CommandResult result2 =
          CommandExecutor.run(command, workingDir, null, -1);
      LOG.info("Python script stdout (second run): {}", result2.getStdout());
      LOG.info("Python script stderr (second run): {}", result2.getStderr());
      if (!result2.isSuccess()) {
        LOG.error("{} second run failed. exitCode={}", command, result2.getExitCode());
        LOG.error("{} execution failed. exitCode={} stderr={}",
            command, result2.getExitCode(), result2.getStderr());
        String altPython = "python3".equals(pythonCmd) ? "python" : "python3";
        List<String> commandRetry = List.of(altPython, scriptPath, "-r", outputPath);
        CommandExecutor.CommandResult resultRetry =
            CommandExecutor.run(commandRetry, workingDir, null, -1);
        if (!resultRetry.isSuccess()) {
          LOG.error("{} execution also failed. exitCode={} stderr={}",
              commandRetry, resultRetry.getExitCode(), resultRetry.getStderr());
        } else {
          LOG.info("{} succeeded on retry.", commandRetry);
        }
      } else {
        LOG.info("{} completed successfully.", command);
      }
    }
    LOG.info("Benchmark completed, report generation finished.");
  }

  private static File findScriptFile(String userDir, String scriptName) {
    File direct = new File(userDir, scriptName);
    if (direct.exists()) return direct;
    File inResbenchmark = new File(userDir, "resbenchmark/" + scriptName);
    if (inResbenchmark.exists()) return inResbenchmark;
    File inResbenchmark2 = new File(userDir, "resbenchmark/resbenchmark/" + scriptName);
    if (inResbenchmark2.exists()) return inResbenchmark2;
    File inParent = new File(userDir, "../" + scriptName);
    if (inParent.exists()) return inParent;
    File inResbenchmarkParent = new File(userDir, "../resbenchmark/" + scriptName);
    if (inResbenchmarkParent.exists()) return inResbenchmarkParent;
    LOG.warn("findScriptFile: tried userDir={}, direct={}, resbenchmark/, resbenchmark/resbenchmark/, ../, ../resbenchmark/ — none found for {}",
        userDir, scriptName);
    return null;
  }

  /**
   * 生成 metric.json 用的解释器。
   *
   * <p>ResilienceMetricReport.py 依赖 sklearn / ruptures / scipy，宿主机上排在 PATH 前面的
   * python3 不一定装了它们（2026-11-02 那批就是 miniconda base 的 3.8，缺 sklearn，
   * 脚本直接 ModuleNotFoundError，metric.json 没生成，整轮被判「产物不齐」）。这里允许
   * 用系统属性 {@code -Dresbench.python=<path>} 或环境变量 {@code RESBENCH_PYTHON} 指定，
   * 未指定时保持原来的 python3 / python。
   */
  private static String resolvePython() {
    String configured = System.getProperty("resbench.python");
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("RESBENCH_PYTHON");
    }
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("win")) {
      return "python";
    }
    return "python3";
  }

  private static void runCreator(BenchmarkModule bench) throws SQLException, IOException {
    LOG.debug(String.format("Creating %s Database", bench));
    bench.createDatabase();
  }

  private static void runLoader(BenchmarkModule bench)
      throws IOException, SQLException, InterruptedException {
    LOG.debug(String.format("Loading %s Database", bench));
    bench.loadDatabase();
  }

  private static Results runWorkload(BenchmarkModule bench, MonitoringManager monitoringManager)
      throws IOException {
      int num_phases = bench.getWorkloadConfiguration().getNumberOfPhases();
      LOG.info(
          String.format(
              "Launching the %s Benchmark with %s Phase%s...",
              bench.getBenchmarkName().toUpperCase(), num_phases, (num_phases > 1 ? "s" : "")));
    Results r = ThreadBench.runRateLimitedBenchmark(bench.getWorkloadConfiguration().getWorkers(), bench.getWorkloadConfiguration(), monitoringManager, bench.getWorkloadConfiguration().getEventBus());
    LOG.info(SINGLE_LINE);
    LOG.info("Rate limited reqs/s: {}", r);
    return r;
  }

  private static void writeTpccCache(CommandLine argsLine, String status) {
    if (!argsLine.hasOption("tpcc-cache")) return;
    String cacheArg = argsLine.getOptionValue("tpcc-cache");
    if (cacheArg == null) return;
    String[] parts = cacheArg.split(":");
    if (parts.length < 2) return;
    String cachePath = parts[0];
    String whStr = parts[1];
    java.io.File cacheFile = new java.io.File(cachePath);
    try {
      String json = "{\"wh\":" + whStr + ",\"status\":\"" + status + "\"}";
      java.nio.file.Files.writeString(cacheFile.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);
      LOG.info("[TPCC_WH_CACHE] wrote cache: {} status={}", cachePath, status);
    } catch (Exception e) {
      LOG.error("[TPCC_WH_CACHE] Failed to write cache: {}", e.getMessage());
    }
  }

  private static void printUsage(Options options) {
    HelpFormatter hlpfrmt = new HelpFormatter();
    hlpfrmt.printHelp("benchbase", options);
  }

  /**
   * Returns true if the given key is in the CommandLine object and is set to true.
   *
   * @param argsLine
   * @param key
   * @return
   */
  private static boolean isBooleanOptionSet(CommandLine argsLine, String key) {
    if (argsLine.hasOption(key)) {
      LOG.debug("CommandLine has option '{}'. Checking whether set to true", key);
      String val = argsLine.getOptionValue(key);
      LOG.debug(String.format("CommandLine %s => %s", key, val));
      return (val != null && val.equalsIgnoreCase("true"));
    }
    return (false);
  }

  /**
   * Handles the anonymization of specified tables with differential privacy and automatically
   * creates an anonymized copy of the table. Adapts templated query file if sensitive values are
   * present
   *
   * @param xmlConfig
   * @param configFile
   */
  private static void applyAnonymization(XMLConfiguration xmlConfig, String configFile) {

    String templatesPath = "";
    if (xmlConfig.containsKey("query_templates_file")) {
      templatesPath = xmlConfig.getString("query_templates_file");
    }

    LOG.info("Starting the Anonymization process");
    LOG.info(SINGLE_LINE);
    String osCommand = System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
    ProcessBuilder processBuilder =
        new ProcessBuilder(
            osCommand, "scripts/anonymization/src/anonymizer.py", configFile, templatesPath);
    try {
      // Redirect Output stream of the script to get live feedback
      processBuilder.inheritIO();
      Process process = processBuilder.start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new Exception("Anonymization program exited with a non-zero status code");
      }
      LOG.info("Finished the Anonymization process for all tables");
      LOG.info(SINGLE_LINE);
    } catch (Exception e) {
      LOG.error(e.getMessage());
      return;
    }
  }
}
