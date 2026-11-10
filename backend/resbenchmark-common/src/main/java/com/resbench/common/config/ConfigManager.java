package com.resbench.common.config;

import com.resbench.common.util.XMLUtil;
import org.apache.commons.cli.*;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;

public class ConfigManager extends AbsConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigManager.class);

    private String[] workLoadNameList;

    private XMLConfiguration workLoadPluginConfig;

    public ConfigManager(String[] args) {
        super(args);
        try {
            // create the command line parser
            CommandLineParser parser = new DefaultParser();
            workLoadPluginConfig = XMLUtil.buildConfiguration("config/plugin.xml");
            workLoadNameList = parseWorkLoadNameList();
            initExtraDbConfig();
        } catch (ConfigurationException e) {
            LOG.error("初始化配置项失败: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void initExtraDbConfig() {
        dbConfig.setUserGroupNum(workLoadConfig.getInt("usergroup", 5));
        //当前只支持一个bencmark
        String targetBenchmark = getWorkLoadNameList()[0];
        String pluginTest = "[@bench='" + targetBenchmark + "']";
        dbConfig.setTerminals(parseTerminals(pluginTest));
        dbConfig.setLoaderThreads(workLoadConfig.getInt("loaderThreads", Math.max(1, Runtime.getRuntime().availableProcessors())));
        dbConfig.setIsolationMode(parseIsolationMode(workLoadConfig, pluginTest));
        dbConfig.setScaleFactor(workLoadConfig.getDouble("scalefactor", 1.0));
        dbConfig.setDataCheckType(workLoadConfig.getString("data_cktype", "ONLY_SPECIAL"));
        dbConfig.setDataDir(workLoadConfig.getString("datadir", "."));
        dbConfig.setDdlPath(workLoadConfig.getString("ddlpath", null));
        dbConfig.setSelectivity(parseSelectivity());
    }

    @Override
    public String getFaultCasesFileName() {
        return argsLine.getOptionValue("fault-config", "config/fault-cases-generic.xml");
    }

    public int parseTerminals(String pluginTest) {
        XMLConfiguration xmlConfig = this.getWorkLoadConfig();
        int terminals = xmlConfig.getInt("terminals[not(@bench)]", 0);
        terminals = xmlConfig.getInt("terminals" + pluginTest, terminals);
        return terminals;
    }

    private double parseSelectivity() {
        XMLConfiguration xmlConfig = this.getWorkLoadConfig();
        double selectivity = -1;
        try {
            selectivity = xmlConfig.getDouble("selectivity");
        } catch (NoSuchElementException nse) {
            // Nothing to do here !
        }
        return selectivity;
    }

    private String parseIsolationMode(XMLConfiguration xmlConfig, String pluginTest) {
        String isolationMode =
                xmlConfig.getString("isolation[not(@bench)]", "TRANSACTION_SERIALIZABLE");
        isolationMode = xmlConfig.getString("isolation" + pluginTest, isolationMode);
        return isolationMode;
    }

    @Override
    protected Options buildOptions() {
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
        options.addOption(
                null, "anonymize", true, "Anonymize specified datasets using differential privacy");
        options.addOption(null, "execute", true, "Execute the benchmark workload");
        options.addOption("h", "help", false, "Print this help");
        options.addOption("s", "sample", true, "Sampling window");
        options.addOption("im", "interval-monitor", true, "Monitoring Interval in milliseconds");
        options.addOption("mt", "monitor-type", true, "Type of Monitoring (throughput/advanced)");
        options.addOption(
                "d",
                "directory",
                true,
                "Base directory for the result files, default is current directory");
        options.addOption(null, "fault-config", true, "Generated fault schedule configuration file");
        options.addOption(null, "run-id", true, "Generated history name for this run");
        options.addOption(null, "tpcc-cache", true, "TPCC warehouse cache path and count (path:wh)");
        options.addOption(null, "dialects-export", true, "Export benchmark SQL to a dialects file");
        options.addOption("jh", "json-histograms", true, "Export histograms to JSON file");
        return options;
    }

    private String[] parseWorkLoadNameList() {
        String targetBenchmarks = argsLine.getOptionValue("b");
        return targetBenchmarks.split(",");
    }

    @Override
    String getWorkLoadConfigFileName(CommandLine args) {
        return args.getOptionValue("c");
    }

    @Override
    public boolean checkArgs() {
        if (argsLine.hasOption("h")) {
            return false;
        } else if (!argsLine.hasOption("c")) {
            LOG.error("Missing Configuration file");
            return false;
        } else if (!argsLine.hasOption("b")) {
            LOG.error("Missing Benchmark Class to load");
            return false;
        } else if (!argsLine.hasOption("im")) {
            LOG.warn("Missing interval value for monitor, suggest to set 1000ms by '-im 10000'\n");
        }
        return true;
    }

    public String[] getWorkLoadNameList() {
        return workLoadNameList;
    }

    public int getTerminals() {
        return dbConfig.getTerminals();
    }

    public double getSelectivity() {
        return dbConfig.getSelectivity();
    }

    public int getTransTypeNumByString(String pluginTest) {
        return workLoadConfig.configurationsAt("transactiontypes" + pluginTest + "/transactiontype").size();
    }

    @Override
    public MonitorInfo getMonitorInfo() {
        return monitorInfo;
    }

    @Override
    public CommandLine getArgsLine() {
        return argsLine;
    }

    public XMLConfiguration getWorkLoadPluginConfig() {
        return workLoadPluginConfig;
    }
}
