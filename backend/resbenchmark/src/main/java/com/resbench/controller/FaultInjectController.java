package com.resbench.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.exception.FaultInjectException;
import com.resbench.service.FaultInjectUtil;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/web")
public class FaultInjectController {
  private static final int DEFAULT_AGENT_PORT = 8000;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @PostMapping("/fault/inject")
  public FaultInjectUtil.FaultInjectResult faultInject(@RequestBody String requestBody) {
    try {
      FaultInjectRequest request = parseRequest(requestBody);
      String agentHost = resolveAgentHost(request);
      return FaultInjectUtil.execFaultInjectCommand(agentHost, request.getCommand());
    } catch (FaultInjectException exception) {
      FaultInjectUtil.FaultInjectResult result = new FaultInjectUtil.FaultInjectResult();
      result.setReturncode(-1);
      result.setError(exception.getMessage());
      return result;
    } catch (IllegalArgumentException exception) {
      FaultInjectUtil.FaultInjectResult result = new FaultInjectUtil.FaultInjectResult();
      result.setReturncode(-1);
      result.setError(exception.getMessage());
      return result;
    } catch (Exception exception) {
      FaultInjectUtil.FaultInjectResult result = new FaultInjectUtil.FaultInjectResult();
      result.setReturncode(-1);
      result.setError("fault inject request is invalid");
      return result;
    }
  }

  private FaultInjectRequest parseRequest(String requestBody) throws Exception {
    if (StringUtils.isBlank(requestBody)) {
      throw new IllegalArgumentException("fault inject command is required");
    }

    String trimmed = requestBody.trim();
    if (!trimmed.startsWith("{")) {
      FaultInjectRequest request = new FaultInjectRequest();
      request.setCommand(trimmed);
      return request;
    }

    FaultInjectRequest request = OBJECT_MAPPER.readValue(trimmed, FaultInjectRequest.class);
    if (StringUtils.isBlank(request.getCommand())) {
      throw new IllegalArgumentException("fault inject command is required");
    }
    return request;
  }

  private String resolveAgentHost(FaultInjectRequest request) {
    if (StringUtils.isNotBlank(request.getAgentHost())) {
      return request.getAgentHost().trim();
    }
    if (StringUtils.isBlank(request.getDatabaseIp())) {
      throw new IllegalArgumentException("databaseIp or agentHost is required");
    }
    int port = request.getAgentPort() == null ? DEFAULT_AGENT_PORT : request.getAgentPort();
    return "http://" + request.getDatabaseIp().trim() + ":" + port;
  }

  @Data
  private static class FaultInjectRequest {
    private String command;
    private String agentHost;
    private String databaseIp;
    private Integer agentPort;
  }
}
