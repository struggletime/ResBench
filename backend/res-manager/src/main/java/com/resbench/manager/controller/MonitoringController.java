package com.resbench.manager.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.resbench.manager.global.GlobalResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.CapabilityResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.CatalogResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.CreateSessionRequest;
import com.resbench.manager.model.monitoring.MonitoringModels.SeriesResponse;
import com.resbench.manager.model.monitoring.MonitoringModels.Session;
import com.resbench.manager.model.monitoring.MonitoringModels.Target;
import com.resbench.manager.service.monitoring.MonitoringService;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/monitoring")
public class MonitoringController {
  private final MonitoringService monitoringService;

  public MonitoringController(MonitoringService monitoringService) {
    this.monitoringService = monitoringService;
  }

  @GetMapping("/capabilities")
  public GlobalResponse<CapabilityResponse> capabilities() {
    return GlobalResponse.success(monitoringService.capabilities());
  }

  @GetMapping("/catalog")
  public GlobalResponse<CatalogResponse> catalog(
      @RequestParam(required = false, defaultValue = "opengauss") String dbType) {
    return GlobalResponse.success(monitoringService.catalog());
  }

  @PostMapping("/targets/discover")
  public GlobalResponse<List<Target>> discoverTargets(
      @RequestBody(required = false) Map<String, Object> ignored) {
    return GlobalResponse.success(monitoringService.discoverTargets());
  }

  @PostMapping("/sessions")
  public GlobalResponse<Session> createSession(@RequestBody CreateSessionRequest request) {
    return GlobalResponse.success(monitoringService.createSession(request));
  }

  @GetMapping("/sessions/{runId}")
  public GlobalResponse<Session> getSession(@PathVariable String runId) {
    return GlobalResponse.success(monitoringService.getSession(runId));
  }

  @PostMapping("/sessions/{runId}/complete")
  public GlobalResponse<Session> completeSession(@PathVariable String runId) {
    return GlobalResponse.success(monitoringService.completeSession(runId));
  }

  @GetMapping("/sessions/{runId}/series")
  public GlobalResponse<SeriesResponse> series(
      @PathVariable String runId,
      @RequestParam(required = false) String metricIds,
      @RequestParam(required = false) Long start,
      @RequestParam(required = false) Long end,
      @RequestParam(required = false) Integer stepSec) {
    List<String> requested = metricIds == null || metricIds.isBlank()
        ? List.of()
        : Arrays.stream(metricIds.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    return GlobalResponse.success(monitoringService.series(runId, requested, start, end, stepSec));
  }

  @GetMapping("/history")
  public GlobalResponse<List<Session>> history() {
    return GlobalResponse.success(monitoringService.history());
  }

  @GetMapping("/history/{runId}")
  public GlobalResponse<Session> history(@PathVariable String runId) {
    return GlobalResponse.success(monitoringService.history(runId));
  }

  @GetMapping("/history/{runId}/series")
  public GlobalResponse<SeriesResponse> historySeries(@PathVariable String runId) {
    return GlobalResponse.success(monitoringService.historySeries(runId));
  }

  @DeleteMapping("/history/{runId}")
  public GlobalResponse<Map<String, Object>> deleteHistory(@PathVariable String runId) {
    monitoringService.deleteHistory(runId);
    return GlobalResponse.success(Map.of("runId", runId, "deleted", true));
  }
}
