package com.resbench.manager.model.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 数据库参数应用结果（管理端镜像）。
 *
 * <p>字段必须与引擎的 DbParamResponse 一一对应 —— 管理端在这里只做透传，
 * 少一个字段就会让前端拿到 null。
 */
@Data
public class DbParamResponse {
  private boolean success;

  private String message;

  private String dbType;

  private Map<String, String> actualNames = new LinkedHashMap<>();

  private Map<String, String> targets = new LinkedHashMap<>();

  private Map<String, String> before = new LinkedHashMap<>();

  private Map<String, String> after = new LinkedHashMap<>();

  private List<String> statements = new ArrayList<>();

  private boolean alreadyEffective;

  private boolean requiresRestart;

  private boolean applied;

  private boolean restarted;

  private int waitedSec;

  private List<String> mismatches = new ArrayList<>();

  private List<String> ignored = new ArrayList<>();

  private String restartCommand;

  private String restartOutput;

  private long elapsedMs;
}