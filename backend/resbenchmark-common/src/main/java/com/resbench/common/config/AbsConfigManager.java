package com.resbench.common.config;


import com.resbench.common.util.XMLUtil;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public abstract class AbsConfigManager {
    private static final Logger log = LoggerFactory.getLogger(AbsConfigManager.class);

    protected XMLConfiguration testSuiteConfig;
    protected XMLConfiguration workLoadConfig;

    protected DBConfig dbConfig;
    protected MonitorInfo monitorInfo;
    protected Options options;
    protected CommandLine argsLine;
    protected String configFile;

    public AbsConfigManager(String[] args) {
        try {
            CommandLineParser parser = new DefaultParser();
            options = buildOptions();
            argsLine = parser.parse(options, args);
            testSuiteConfig = XMLUtil.buildConfiguration(getFaultCasesFileName());
            configFile = getWorkLoadConfigFileName(argsLine);
            workLoadConfig = XMLUtil.buildConfiguration(configFile);
            initDbConfig(workLoadConfig);
            monitorInfo = this.buildMonitorInfo();
        } catch (ConfigurationException | ParseException e) {
            log.error("初始化配置项失败: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    protected void initDbConfig(XMLConfiguration workloadConfig) {
        dbConfig = new DBConfig();

        Element nodes = (Element) workloadConfig.getDocument().getElementsByTagName("nodes").item(0);
        NodeList nodeList = nodes.getElementsByTagName("node");
        for (int k = 0; k < nodeList.getLength(); k++) {
            dbConfig.addNode(nodeList.item(k).getTextContent());
        }
        dbConfig.setType(workloadConfig.getString("type"));
        dbConfig.setDriverClass(workloadConfig.getString("driver"));
        dbConfig.setUrl(workloadConfig.getString("url"));
        dbConfig.setUsername(workloadConfig.getString("username"));
        dbConfig.setPassword(workloadConfig.getString("password"));
        dbConfig.setRandomSeed(workloadConfig.getInt("randomSeed", -1));
        dbConfig.setBatchSize(workloadConfig.getInt("batchsize", 128));
        dbConfig.setMaxRetries(workloadConfig.getInt("retries", 1));
        dbConfig.setNewConnectionPerTxn(workloadConfig.getBoolean("newConnectionPerTxn", false));
        dbConfig.setReconnectOnConnectionFailure(
                workloadConfig.getBoolean("reconnectOnConnectionFailure", false));
    }

    /**
     * 公共配置类外子类的特殊配置
     */
    public abstract void initExtraDbConfig();

    public abstract String getFaultCasesFileName();

    protected abstract Options buildOptions();

    protected MonitorInfo buildMonitorInfo()
            throws ParseException {
        MonitorInfo.Builder builder = MonitorInfo.builder();
        if (argsLine.hasOption("im")) {
            builder.monitoringInterval(Integer.parseInt(argsLine.getOptionValue("im")));
        }
        if (argsLine.hasOption("mt")) {
            switch (argsLine.getOptionValue("mt")) {
                case "advanced":
                    builder.monitoringType(MonitorInfo.MonitoringType.ADVANCED);
                    break;
                case "throughput":
                    builder.monitoringType(MonitorInfo.MonitoringType.THROUGHPUT);
                    break;
                default:
                    throw new ParseException(
                            "Monitoring type '"
                                    + argsLine.getOptionValue("mt")
                                    + "' is undefined, allowed values are: advanced/throughput");
            }
        }
        return builder.build();
    }

    abstract String getWorkLoadConfigFileName(CommandLine argsLine);

    /**
     * 配置项检查
     *
     * @return true or false
     */
    abstract public boolean checkArgs();


    public Options getOptions() {
        return options;
    }

    public abstract MonitorInfo getMonitorInfo();

    public abstract CommandLine getArgsLine();

    public XMLConfiguration getWorkLoadConfig() {
        return workLoadConfig;
    }

    public XMLConfiguration getTestSuiteConfig() {
        return testSuiteConfig;
    }

    public String getConfigFile() {
        return configFile;
    }

    public DBConfig getDbConfig() {
        return dbConfig;
    }
}
