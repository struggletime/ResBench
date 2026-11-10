package com.resbench.manager.model.dto;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/** 手动补验请求：转发给 benchmark POST /web/fault/verify */
@Getter
@Setter
public class FaultVerifyRequest {
  private String envInfo;
  private String nameKey;
  private String dbType;
  private String databaseIp;
  private String databasePort;
  private String agentPort;
  private String user;
  private String password;
  private Map<String, String> params;
}