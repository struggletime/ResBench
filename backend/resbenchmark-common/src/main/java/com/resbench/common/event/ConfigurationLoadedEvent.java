package com.resbench.common.event;

import java.time.Instant;

/**
 * 配置加载完成事件
 */
public class ConfigurationLoadedEvent implements Event {

    private static final String EVENT_TYPE = "CONFIGURATION_LOADED";
    private final Instant timestamp;
    private final String configFile;

    public ConfigurationLoadedEvent(String configFile) {
        this.timestamp = Instant.now();
        this.configFile = configFile;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public String getConfigFile() {
        return configFile;
    }
}