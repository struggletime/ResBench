package com.resbench.common.config;

public class MonitorInfo {
    public enum MonitoringType {
        ADVANCED,
        THROUGHPUT;
    }

    private final int monitoringInterval;
    private final MonitoringType monitoringType;
    private final String monitoringWsURL;

    // 私有构造函数
    private MonitorInfo(Builder builder) {
        this.monitoringInterval = builder.monitoringInterval;
        this.monitoringType = builder.monitoringType;
        this.monitoringWsURL = builder.monitoringWsURL;
    }

    // 获取默认实例
    public static MonitorInfo createDefault() {
        return builder().build();
    }

    // Builder模式
    public static Builder builder() {
        return new Builder();
    }

    /** Monitoring interval. */
    public int getMonitoringInterval() {
        return monitoringInterval;
    }

    /** Monitoring type. */
    public MonitoringType getMonitoringType() {
        return monitoringType;
    }

    /** Monitoring Web socket URL. */
    public String getMonitoringWsURL() {
        return monitoringWsURL;
    }

    // Builder类
    public static class Builder {
        private int monitoringInterval = 0;
        private MonitoringType monitoringType = MonitoringType.THROUGHPUT;
        private String monitoringWsURL = "NOT_SET";

        private Builder() {}

        public Builder monitoringInterval(int monitoringInterval) {
            this.monitoringInterval = monitoringInterval;
            return this;
        }

        public Builder monitoringType(MonitoringType monitoringType) {
            this.monitoringType = monitoringType;
            return this;
        }

        public Builder monitoringWsURL(String monitoringWsURL) {
            this.monitoringWsURL = monitoringWsURL;
            return this;
        }

        public MonitorInfo build() {
            return new MonitorInfo(this);
        }
    }

    // equals, hashCode, toString 方法
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MonitorInfo that = (MonitorInfo) o;

        if (monitoringInterval != that.monitoringInterval) return false;
        if (monitoringType != that.monitoringType) return false;
        return monitoringWsURL.equals(that.monitoringWsURL);
    }

    @Override
    public int hashCode() {
        int result = monitoringInterval;
        result = 31 * result + monitoringType.hashCode();
        result = 31 * result + monitoringWsURL.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "MonitorInfo{" +
                "monitoringInterval=" + monitoringInterval +
                ", monitoringType=" + monitoringType +
                ", monitoringWsURL='" + monitoringWsURL + '\'' +
                '}';
    }
}