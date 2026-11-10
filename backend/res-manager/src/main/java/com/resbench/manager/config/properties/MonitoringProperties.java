package com.resbench.manager.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "res.monitoring")
public class MonitoringProperties {
  private boolean enabled = true;
  private String agentUrl = "http://127.0.0.1:19091";
  private String prometheusUrl = "http://127.0.0.1:9090";
  private String prometheusInstance = "127.0.0.1:19091";
  private String dataDir = "data/monitoring";
  private int maxMetrics = 32;
  private int maxPoints = 2000;
  private int sessionTtlSec = 900;
  private int archiveRetentionDays = 180;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getAgentUrl() {
    return agentUrl;
  }

  public void setAgentUrl(String agentUrl) {
    this.agentUrl = agentUrl;
  }

  public String getPrometheusUrl() {
    return prometheusUrl;
  }

  public void setPrometheusUrl(String prometheusUrl) {
    this.prometheusUrl = prometheusUrl;
  }

  public String getPrometheusInstance() {
    return prometheusInstance;
  }

  public void setPrometheusInstance(String prometheusInstance) {
    this.prometheusInstance = prometheusInstance;
  }

  public String getDataDir() {
    return dataDir;
  }

  public void setDataDir(String dataDir) {
    this.dataDir = dataDir;
  }

  public int getMaxMetrics() {
    return maxMetrics;
  }

  public void setMaxMetrics(int maxMetrics) {
    this.maxMetrics = maxMetrics;
  }

  public int getMaxPoints() {
    return maxPoints;
  }

  public void setMaxPoints(int maxPoints) {
    this.maxPoints = maxPoints;
  }

  public int getSessionTtlSec() {
    return sessionTtlSec;
  }

  public void setSessionTtlSec(int sessionTtlSec) {
    this.sessionTtlSec = sessionTtlSec;
  }

  public int getArchiveRetentionDays() {
    return archiveRetentionDays;
  }

  public void setArchiveRetentionDays(int archiveRetentionDays) {
    this.archiveRetentionDays = archiveRetentionDays;
  }
}
