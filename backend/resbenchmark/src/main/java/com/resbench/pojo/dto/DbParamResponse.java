package com.resbench.pojo.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** 数据库参数应用结果。失败时 {@code success=false} 且 {@code message} 必须是给人看的说明。 */
@Data
public class DbParamResponse {
  private boolean success;

  private String message;

  private String dbType;

  /** 语义化键 → 实际参数名，便于排查（如 bufferPool → shared_buffers） */
  private Map<String, String> actualNames = new LinkedHashMap<>();

  /** 语义化键 → 目标值 */
  private Map<String, String> targets = new LinkedHashMap<>();

  /** 改动前（语义化键 → 人类可读值，如 "1GB" / "100"） */
  private Map<String, String> before = new LinkedHashMap<>();

  /** 改动后 */
  private Map<String, String> after = new LinkedHashMap<>();

  /** 实际执行的 SQL（排查用） */
  private List<String> statements = new ArrayList<>();

  /** 目标值本来就已生效 → 没写库、没重启 */
  private boolean alreadyEffective;

  /** 是否有 postmaster 上下文的参数（必须重启才生效） */
  private boolean requiresRestart;

  private boolean applied;

  private boolean restarted;

  private int waitedSec;

  /** 读回核对不一致的项，形如 "缓冲池（shared_buffers）：期望 2GB，实际读回 1GB" */
  private List<String> mismatches = new ArrayList<>();

  /** 被跳过的输入（没填或不认识的键） */
  private List<String> ignored = new ArrayList<>();

  private String restartCommand;

  private String restartOutput;

  private long elapsedMs;
}