package com.resbench.impl;

import com.resbench.WorkloadConfiguration;
import com.resbench.api.collectors.monitoring.Monitor;
import com.resbench.api.collectors.monitoring.MonitorGen;
import com.resbench.common.config.MonitorInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 监控服务实现类，负责管理监控配置和状态
 */
public class MonitoringManager  {

    private static final Logger LOG = LoggerFactory.getLogger(MonitoringManager.class);
    private static final int MONITOR_REJOIN_TIME = 60000;

    private MonitorInfo monitorInfo;
    private AtomicBoolean running = new AtomicBoolean(false);
    private Monitor monitor;


    /**
     * 监控模块初始化
     *
     * @param wrkld 工作负载配置
     * @param monitorInfo 监控配置
     */
    public MonitoringManager(WorkloadConfiguration wrkld, MonitorInfo monitorInfo) {
        this.monitorInfo = monitorInfo;
        // Initialize the Monitor
        LOG.info("Initialize the Monitor ...");
        if (this.monitorInfo.getMonitoringInterval() > 0) {
            this.monitor =
                    MonitorGen.getMonitor(
                            this.monitorInfo, wrkld.getTestState(), wrkld.getWorkers(), wrkld);
        } else {
            LOG.warn("Did not get monitor interval value, Monitor not start!!!");
        }
    }

    public void setMonitorInfo(MonitorInfo monitorInfo) {
        this.monitorInfo = monitorInfo;
    }


    public MonitorInfo getMonitorInfo() {
        return monitorInfo;
    }


    public void startMonitoring() {
        if (running.compareAndSet(false,true)) {
            LOG.info("monitor start ...");
            this.monitor.start();
        } else {
            LOG.warn("Monitoring is already running, skipping start");
        }

    }


    public void stopMonitoring() {
        if (!running.get()) {
            LOG.warn("Monitoring is not running, skipping stop");
            return;
        }
        // Stop the monitoring thread separately from cleanup all the workers so we can ignore errors
        // from these threads (including possible SQLExceptions), but not the others.
        try {
            if (this.monitor != null) {
                this.monitor.interrupt();
                this.monitor.join(MONITOR_REJOIN_TIME);
                this.monitor.tearDown();
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }

        this.running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }
}