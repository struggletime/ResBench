package com.resbench.event;

import com.resbench.Phase;
import com.resbench.common.event.Event;
import com.resbench.common.fault.bdserver.DBService;

import java.time.Instant;

public class WorkloadWarmupCompletedEvent implements Event {

    private static final String EVENT_TYPE = "WORKLOAD_WARMUP_COMPLETED";
    private final Instant timestamp;
    private Phase phase;
    private DBService service;
    private String monitoringHistoryName;

    public WorkloadWarmupCompletedEvent(Phase phase, DBService service, String monitoringHistoryName) {
        this.timestamp = Instant.now();
        this.phase = phase;
        this.service = service;
        this.monitoringHistoryName = monitoringHistoryName;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public Phase getPhase() {
        return phase;
    }

    public DBService getService() {
        return service;
    }

    public String getMonitoringHistoryName() {
        return monitoringHistoryName;
    }
}
