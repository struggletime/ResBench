package com.resbench.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.pojo.dto.StartByFormRequest;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 采集测试库所在机器的 CPU / 内存，以及（配置了容器时）容器的基本信息。
 *
 * <p>实现方式：通过数据库机器上的注入 Agent 的 {@code POST /command/exec} 执行一段只读 shell
 * （{@code nproc}、{@code /proc/meminfo}、{@code docker inspect}）。之所以复用 Agent，是因为
 * 引擎本身不保证能直接访问数据库宿主机，而 Agent 与数据库同机部署。
 *
 * <p>约束：
 * <ul>
 *   <li>只读命令，不 exec 进容器、不改任何配置；</li>
 *   <li>整体有严格超时（默认 3.5s），失败只把 {@code available=false} 和错误原因写进归档，
 *       绝不影响测试启动；</li>
 *   <li>采集不到就写"采集不到"，不猜、不用本地机器资源冒充数据库机器。</li>
 * </ul>
 */
@Service
public class DbResourceCollector {
  private static final Logger LOG = LoggerFactory.getLogger(DbResourceCollector.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DEFAULT_AGENT_PORT = 8000;
  /** 只允许普通容器名/短 ID，避免把任意内容拼进 shell。 */
  private static final Pattern CONTAINER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

  private static final String CPU_MARK = "RB_CPU=";
  private static final String MEM_MARK = "RB_MEM_KB=";
  private static final String DOCKER_MARK = "RB_DOCKER_B64=";
  private static final String END_MARK = "RB_DONE=1";

  public Map<String, Object> collect(StartByFormRequest request) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("collectedAt", System.currentTimeMillis());
    result.put("source", "agent");

    String dbHost = resolveDatabaseHost(request);
    if (StringUtils.isBlank(dbHost)) {
      result.put("available", false);
      result.put("error", "无法确定数据库 IP，未采集资源信息");
      return result;
    }

    String containerName = StringUtils.trimToNull(request == null ? null : request.getContainerName());
    String safeContainer = null;
    String containerError = null;
    if (containerName != null) {
      if (CONTAINER_NAME.matcher(containerName).matches()) {
        safeContainer = containerName;
      } else {
        containerError = "容器名包含不安全字符，跳过容器信息采集";
      }
    }

    try {
      String output = execOnAgent(dbHost, buildCommand(safeContainer));
      Map<String, Object> host = parseHost(output);
      if (!host.isEmpty()) {
        result.put("host", host);
      }

      if (safeContainer != null) {
        Map<String, Object> container = parseContainer(output);
        if (containerError != null) {
          container.put("available", false);
          container.put("error", containerError);
        }
        result.put("container", container);
      } else if (containerError != null) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("available", false);
        container.put("error", containerError);
        result.put("container", container);
      }

      Object containerNode = result.get("container");
      boolean containerOk = containerNode instanceof Map<?, ?> map
          && Boolean.TRUE.equals(map.get("available"));
      result.put("available", !host.isEmpty() || containerOk);
      if (host.isEmpty() && !containerOk) {
        result.put("error", "Agent 未返回 CPU/内存信息");
      }
    } catch (Exception e) {
      LOG.warn("采集数据库机器资源失败（{}）：{}", dbHost, e.getMessage());
      result.put("available", false);
      result.put("error", "资源采集失败：" + e.getMessage());
    }
    return result;
  }

  /** 与 StartByFormService 的 activeDatabaseEndpoint 保持一致：分布式取 distributed.ip，其余取 standalone.ip。 */
  private String resolveDatabaseHost(StartByFormRequest request) {
    if (request == null || request.getDatabase() == null) {
      return null;
    }
    StartByFormRequest.Database database = request.getDatabase();
    String deploymentType = StringUtils.defaultString(database.getDeploymentType()).trim().toLowerCase();
    if ("distributed".equals(deploymentType)) {
      Map<String, String> distributed = database.getDistributed();
      return distributed == null ? null : StringUtils.trimToNull(distributed.get("ip"));
    }
    StartByFormRequest.StandaloneDatabase standalone = database.getStandalone();
    return standalone == null ? null : StringUtils.trimToNull(standalone.getIp());
  }

  private String buildCommand(String containerName) {
    StringBuilder command = new StringBuilder();
    command.append("printf '").append(CPU_MARK).append("'; ")
        .append("nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || printf '0'; ")
        .append("printf '\\n").append(MEM_MARK).append("'; ")
        .append("awk '/^MemTotal:/{printf \"%s\", $2}' /proc/meminfo 2>/dev/null || printf '0'; ");
    if (containerName != null) {
      command.append("printf '\\n").append(DOCKER_MARK).append("'; ")
          .append("docker inspect '").append(containerName).append("' 2>/dev/null | base64 | tr -d '\\n'; ");
    }
    command.append("printf '\\n").append(END_MARK).append("\\n'");
    return command.toString();
  }

  private String execOnAgent(String dbHost, String command) throws IOException {
    OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .writeTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .build();
    String url = "http://" + dbHost + ":" + DEFAULT_AGENT_PORT + "/command/exec";
    RequestBody body = RequestBody.Companion.create(
        command, MediaType.parse("text/plain; charset=utf-8"));
    Request request = new Request.Builder().url(url).post(body).build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        throw new IOException("HTTP " + response.code());
      }
      JsonNode node = MAPPER.readTree(response.body().string());
      String stdout = node.path("stdout").asText("");
      String error = node.path("error").asText("");
      if (StringUtils.isNotBlank(error)) {
        throw new IOException(error.trim());
      }
      if (StringUtils.isBlank(stdout)) {
        String stderr = node.path("stderr").asText("");
        throw new IOException(StringUtils.isBlank(stderr) ? "Agent 返回空输出" : stderr.trim());
      }
      return stdout;
    }
  }

  private Map<String, Object> parseHost(String output) {
    Map<String, Object> host = new LinkedHashMap<>();
    String cpu = readValue(output, CPU_MARK, '\n');
    String memKb = readValue(output, MEM_MARK, '\n');

    Long cpuCount = parseLong(cpu);
    if (cpuCount != null && cpuCount > 0) {
      host.put("cpuCount", cpuCount);
    }
    Long memoryKb = parseLong(memKb);
    if (memoryKb != null && memoryKb > 0) {
      host.put("memoryBytes", memoryKb * 1024L);
    }
    return host;
  }

  private Map<String, Object> parseContainer(String output) {
    Map<String, Object> container = new LinkedHashMap<>();
    String encoded = readValue(output, DOCKER_MARK, '\n');
    if (StringUtils.isBlank(encoded)) {
      container.put("available", false);
      container.put("error", "docker inspect 无输出（未安装 Docker 或容器不存在）");
      return container;
    }
    try {
      String json = new String(Base64.getDecoder().decode(encoded.trim()), java.nio.charset.StandardCharsets.UTF_8);
      JsonNode array = MAPPER.readTree(json);
      JsonNode node = array.isArray() && array.size() > 0 ? array.get(0) : array;
      if (node == null || node.isMissingNode() || node.isNull()) {
        container.put("available", false);
        container.put("error", "docker inspect 返回空结果");
        return container;
      }

      container.put("available", true);
      container.put("name", StringUtils.removeStart(node.path("Name").asText(""), "/"));
      container.put("image", node.path("Config").path("Image").asText(null));
      container.put("status", node.path("State").path("Status").asText(null));

      JsonNode hostConfig = node.path("HostConfig");
      Long nanoCpus = parseLong(hostConfig.path("NanoCpus").asText(null));
      Long cpuQuota = parseLong(hostConfig.path("CpuQuota").asText(null));
      Long cpuPeriod = parseLong(hostConfig.path("CpuPeriod").asText(null));
      Double cpuLimit = null;
      if (nanoCpus != null && nanoCpus > 0) {
        cpuLimit = nanoCpus / 1_000_000_000d;
      } else if (cpuQuota != null && cpuQuota > 0 && cpuPeriod != null && cpuPeriod > 0) {
        cpuLimit = cpuQuota / (double) cpuPeriod;
      }
      if (cpuLimit != null) {
        container.put("cpuLimit", Math.round(cpuLimit * 100d) / 100d);
      } else {
        container.put("cpuLimit", null);
      }

      Long memory = parseLong(hostConfig.path("Memory").asText(null));
      container.put("memoryLimitBytes", memory != null && memory > 0 ? memory : null);

      Long cpuShares = parseLong(hostConfig.path("CpuShares").asText(null));
      container.put("cpuShares", cpuShares != null && cpuShares > 0 ? cpuShares : null);
      container.put("pidsLimit", parseLong(hostConfig.path("PidsLimit").asText(null)));
      return container;
    } catch (Exception e) {
      container.put("available", false);
      container.put("error", "docker inspect 结果解析失败：" + e.getMessage());
      return container;
    }
  }

  private static String readValue(String output, String mark, char terminator) {
    if (output == null) {
      return null;
    }
    int start = output.indexOf(mark);
    if (start < 0) {
      return null;
    }
    int from = start + mark.length();
    int end = output.indexOf(terminator, from);
    String value = end < 0 ? output.substring(from) : output.substring(from, end);
    return value.trim();
  }

  private static Long parseLong(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
