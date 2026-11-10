/*
 * Copyright 2020 by OLTPBenchmark Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.resbench.common.fault;

import static com.resbench.common.util.XMLUtil.getTextContent;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class Fault {
  public enum FaultPoint {
    MASTER,
    SLAVE,
    ALL,
    IP
  }

  public final Command INJECTION;

  public final Command RECOVERY;

  /** 可选：自检命令（设计文档 §7.1，XML <check>），老 XML 无此节点时为 null */
  public final Command CHECK;

  /**
   * 注入是否异步下发（v2.2：DBChaos dispatch 模式）。true → agent /inject 启动进程即返回
   * （"Executed: Dispatched"），DBChaos 在后台跑满 duration；false → 同步等待进程结束（默认）。
   */
  public final boolean ASYNC;

  /** 可选：per-fault 验证窗口覆盖（XML <check timeoutMs/pollIntervalMs/warmupMs>），null=用系统属性/默认 */
  public final Long CHECK_TIMEOUT_MS;
  public final Long CHECK_POLL_INTERVAL_MS;
  public final Long CHECK_WARMUP_MS;

  public final boolean AUTO_RECOVERY;

  public final List<FaultAgent> faultAgents;

  public final int ID;

  private final HashSet<String> inject = new HashSet<>();

  /**
   * chaosblade 注入成功时返回的实验 uid，按 agent 端点记录。
   *
   * <p>有些故障不能按 target/action 撤销，只能 {@code blade destroy <uid>}（disk fill：
   * 它的撤销会 kill 命令行含 "disk fill" 的进程，而 target/action 形式的 destroy 命令
   * 自己就命中这个子串）。恢复命令里写 {@code {{uid}}} 占位，执行时按端点取这里的值。
   */
  private final Map<String, String> injectionUids = new ConcurrentHashMap<>();

  private Fault(
      Command injection,
      Command recovery,
      Command check,
      String description,
      Boolean autoRecovery,
      List<FaultAgent> faultAgents,
      int id,
      boolean async,
      Long checkTimeoutMs,
      Long checkPollIntervalMs,
      Long checkWarmupMs) {
    INJECTION = injection;
    RECOVERY = recovery;
    CHECK = check;
    ASYNC = async;
    CHECK_TIMEOUT_MS = checkTimeoutMs;
    CHECK_POLL_INTERVAL_MS = checkPollIntervalMs;
    CHECK_WARMUP_MS = checkWarmupMs;
    AUTO_RECOVERY = autoRecovery;
    this.faultAgents = faultAgents;
    ID = id;
    this.faultDescription = description;
  }

  public HashSet<String> getInject() {
    return inject;
  }

  /** 记录某端点注入成功后返回的 chaosblade uid（空白值忽略） */
  public void putInjectionUid(String endpoint, String uid) {
    if (endpoint == null || uid == null || uid.trim().isEmpty()) {
      return;
    }
    injectionUids.put(endpoint, uid.trim());
  }

  /** 该端点注入返回的 uid；没拿到时为 {@code null} */
  public String getInjectionUid(String endpoint) {
    return endpoint == null ? null : injectionUids.get(endpoint);
  }

  /** 所有端点的 uid（只读，用于归档里展示实际下发的撤销命令） */
  public Map<String, String> getInjectionUids() {
    return Collections.unmodifiableMap(injectionUids);
  }

  public static List<Fault> loadFaultFromXML(Document conf) {
    NodeList caseNodes = conf.getElementsByTagName("case");
    List<Fault> faults = new ArrayList<>();
    for (int i = 0; i < caseNodes.getLength(); i++) {
      Element caseElem = (Element) caseNodes.item(i);
      Element injectionElem = (Element) caseElem.getElementsByTagName("injection").item(0);
      String injectionCmd = getTextContent(injectionElem, "cmd");
      NodeList argNodes1 = injectionElem.getElementsByTagName("arg");
      List<String> injectionArgs = new ArrayList<>();
      for (int j = 0; j < argNodes1.getLength(); j++) {
        injectionArgs.add(((Element) argNodes1.item(j)).getTextContent());
      }
      Command injection = new Command(injectionCmd, injectionArgs.toArray(new String[0]));
      Element recoveryElem = (Element) caseElem.getElementsByTagName("recovery").item(0);
      Command recovery = null;
      if (recoveryElem != null && !getTextContent(recoveryElem, "cmd").isEmpty()) {
        String recoveryCmd = getTextContent(recoveryElem, "cmd");
        List<String> recoveryArgs = new ArrayList<>();
        NodeList argNodes2 = recoveryElem.getElementsByTagName("arg");
        for (int j = 0; j < argNodes2.getLength(); j++) {
          recoveryArgs.add(((Element) argNodes2.item(j)).getTextContent());
        }
        recovery = new Command(recoveryCmd, recoveryArgs.toArray(new String[0]));
      }
      // 可选 <check> 节点：结构同 <injection>（<cmd> + <arg>）；老 XML 无此节点 → CHECK=null
      // v2.2：<check timeoutMs="..." pollIntervalMs="..." warmupMs="..."> 逐故障覆盖验证窗口
      Element checkElem = (Element) caseElem.getElementsByTagName("check").item(0);
      Command check = null;
      if (checkElem != null) {
        String checkCmd = getTextContent(checkElem, "cmd");
        if (checkCmd != null && !checkCmd.isEmpty()) {
          List<String> checkArgs = new ArrayList<>();
          NodeList argNodesCheck = checkElem.getElementsByTagName("arg");
          for (int j = 0; j < argNodesCheck.getLength(); j++) {
            checkArgs.add(((Element) argNodesCheck.item(j)).getTextContent());
          }
          check = new Command(checkCmd, checkArgs.toArray(new String[0]));
        }
      }
      // v2.2：<injection async="true"> → DBChaos dispatch（agent /inject 启动即返回）
      boolean async = "true".equalsIgnoreCase(injectionElem.getAttribute("async"));
      Long checkTimeoutMs = parseLongAttr(checkElem, "timeoutMs");
      Long checkPollIntervalMs = parseLongAttr(checkElem, "pollIntervalMs");
      Long checkWarmupMs = parseLongAttr(checkElem, "warmupMs");
      String type = caseElem.getElementsByTagName("description").item(0).getTextContent();
      int id = Integer.parseInt(caseElem.getElementsByTagName("id").item(0).getTextContent());
      Boolean autoRecovery =
          caseElem.getElementsByTagName("autoRecovery").item(0).getTextContent().equals("1");
      Element agentsElem = (Element) caseElem.getElementsByTagName("agents").item(0);
      NodeList argNodes3 = agentsElem.getElementsByTagName("agent");
      List<FaultAgent> agents = new ArrayList<>();
      for (int k = 0; k < argNodes3.getLength(); k++) {
        String agent = ((Element) argNodes3.item(k)).getTextContent();
        String point = agent.split(":")[0];
        String port = agent.split(":")[1];
        FaultPoint faultPoint;
        switch (point.toLowerCase()) {
          case "master":
            faultPoint = FaultPoint.MASTER;
            break;
          case "slave":
            faultPoint = FaultPoint.SLAVE;
            break;
          case "all":
            faultPoint = FaultPoint.ALL;
            break;
          default:
            faultPoint = FaultPoint.IP;
            break;
        }
        FaultAgent faultAgent = new FaultAgent(faultPoint, port);
        if (faultPoint == FaultPoint.IP) {
          faultAgent.setIp(point);
        }
        agents.add(faultAgent);
      }
      faults.add(new Fault(injection, recovery, check, type, autoRecovery, agents, id,
          async, checkTimeoutMs, checkPollIntervalMs, checkWarmupMs));
    }
    return faults;
  }

  private static Long parseLongAttr(Element element, String attr) {
    if (element == null || attr == null) {
      return null;
    }
    String raw = element.getAttribute(attr);
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public final String faultDescription;

  public void run() throws SQLException {}

  @Override
  public String toString() {
    return "FaultCase{"
        + faultDescription
        + '\''
        + ", id='"
        + ID
        + '\''
        + '\''
        + ", injection='"
        + INJECTION
        + '\''
        + ", recovery='"
        + RECOVERY
        + '\''
        + ", autoRecovery="
        + AUTO_RECOVERY
        + ", async="
        + ASYNC
        + '}';
  }

  /**
   * Thrown from a Procedure to indicate to the Worker that the procedure should be aborted and
   * rolled back.
   */
  public static class UserAbortException extends RuntimeException {
    private static final long serialVersionUID = -1L;

    /**
     * Default Constructor
     *
     * @param msg
     * @param ex
     */
    public UserAbortException(String msg, Throwable ex) {
      super(msg, ex);
    }

    /** Constructs a new UserAbortException with the specified detail message. */
    public UserAbortException(String msg) {
      this(msg, null);
    }
  }

  public static class FaultAgent {
    final FaultPoint faultPoint;

    final String port;

    String ip;

    public FaultAgent(FaultPoint faultPoint, String port) {
      this.faultPoint = faultPoint;
      this.port = port;
    }

    public FaultPoint getFaultPoint() {
      return faultPoint;
    }

    public String getPort() {
      return port;
    }

    public String getIp() {
      return ip;
    }

    public void setIp(String ip) {
      this.ip = ip;
    }
  }

  @Override
  public final boolean equals(Object object) {
    if (!(object instanceof Fault)) {
      return false;
    }
    Fault fault = (Fault) object;
    return ID == fault.ID && Objects.equals(faultDescription, fault.faultDescription);
  }

  @Override
  public int hashCode() {
    int result = ID;
    result = 31 * result + Objects.hashCode(faultDescription);
    return result;
  }
}
