package com.resbench.common.config;

import com.resbench.common.event.EventBus;
import com.resbench.common.event.SimpleEventBus;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.tree.ImmutableNode;

import java.nio.file.Path;
import java.nio.file.Paths;

public class AgentConfigManager extends AbsConfigManager {
    private EventBus eventBus = new SimpleEventBus();

    private long startTime;
    private int time;
    private int warmup;

    public AgentConfigManager(Path basePath, String[] args) {
        super(args);
        if (!workLoadConfig.configurationsAt("/works/work").isEmpty()) {
            final HierarchicalConfiguration<ImmutableNode> work = workLoadConfig.configurationAt("works/work[" + 1 + "]");
            time = work.getInt("/time", 0);
            warmup = work.getInt("/warmup", 0);
        }
        startTime = System.currentTimeMillis();
    }

    @Override
    public void initExtraDbConfig() {
    }

    @Override
    public String getFaultCasesFileName() {
        String configPath = argsLine.getOptionValue("c");
        return Paths.get(configPath).getParent().getParent().resolve("fault-cases-generic.xml").normalize().toString();
    }

    @Override
    protected Options buildOptions() {
        Options options = new Options();
        options.addOption("ac", null, true, " attach or detach");
        options.addOption("c", "config", true, "[required] Workload configuration file");
        options.addOption("im", "interval-monitor", true, "Monitoring Interval in milliseconds");
        options.addOption("h", "help", false, "Print this help");
        options.addOption("f", null, true, "故障注入配置文件地址");
        options.addOption("o", null, true, "目录输出地址");
        return options;
    }

    @Override
    String getWorkLoadConfigFileName(CommandLine argsLine) {
        return argsLine.getOptionValue("c");
    }

    @Override
    public boolean checkArgs() {
        return true;
    }

    @Override
    public MonitorInfo getMonitorInfo() {
        return null;
    }

    @Override
    public CommandLine getArgsLine() {
        return null;
    }

    public int getTime() {
        return time;
    }

    public int getWarmup() {
        return warmup;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
}
