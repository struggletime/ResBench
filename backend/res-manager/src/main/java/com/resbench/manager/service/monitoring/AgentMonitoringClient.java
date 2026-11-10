package com.resbench.manager.service.monitoring;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.resbench.manager.config.properties.MonitoringProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class AgentMonitoringClient {
  private final RestTemplate restTemplate;
  private final MonitoringProperties properties;

  public AgentMonitoringClient(
      @Qualifier("monitoringRestTemplate") RestTemplate restTemplate,
      MonitoringProperties properties) {
    this.restTemplate = restTemplate;
    this.properties = properties;
  }

  public boolean isHealthy() {
    try {
      String result = restTemplate.getForObject(properties.getAgentUrl() + "/healthz", String.class);
      return "ok".equalsIgnoreCase(result == null ? "" : result.trim());
    } catch (RestClientException ex) {
      return false;
    }
  }

  public JsonNode capabilities() {
    return restTemplate.getForObject(properties.getAgentUrl() + "/api/v1/capabilities", JsonNode.class);
  }

  public JsonNode createSession(Map<String, Object> request) {
    return restTemplate.postForObject(
        properties.getAgentUrl() + "/api/v1/collection-sessions", request, JsonNode.class);
  }

  public JsonNode heartbeat(String runId) {
    return restTemplate.postForObject(
        properties.getAgentUrl() + "/api/v1/collection-sessions/" + runId + "/heartbeat",
        Map.of(),
        JsonNode.class);
  }

  public void deleteSession(String runId) {
    restTemplate.exchange(
        properties.getAgentUrl() + "/api/v1/collection-sessions/" + runId,
        HttpMethod.DELETE,
        HttpEntity.EMPTY,
        Void.class);
  }
}
