package com.resbench.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.exception.FaultInjectException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class FaultInjectUtil {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private FaultInjectUtil() {}

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class FaultInjectResult {
    private String stdout;
    private String stderr;
    private Integer returncode;
    private String error;
  }

  public static FaultInjectResult execFaultInjectCommand(String agentHost, String command)
      throws FaultInjectException {
    // 保持既有行为：默认客户端（读超时 10s）
    return post(agentHost, command, new OkHttpClient());
  }

  /**
   * 同 {@link #execFaultInjectCommand(String, String)}，但可指定等待上限（秒），用于重启数据库这类慢命令。
   *
   * <p>注意 agent 侧对单条命令的等待上限是 30 秒（{@code faults/command.py} 里 asyncio.wait_for），
   * 所以重启命令必须写成 detach（{@code nohup ... &}）让 agent 立刻返回；这里的放宽只是避免
   * 客户端比 agent 先超时，真正的成功判据是"能不能重新连上数据库"。
   */
  public static FaultInjectResult execFaultInjectCommand(
      String agentHost, String command, Integer timeoutSec) throws FaultInjectException {
    int wait = (timeoutSec == null || timeoutSec <= 0) ? 30 : timeoutSec;
    OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(wait + 30L, TimeUnit.SECONDS)
        .build();
    return post(agentHost, command, client);
  }

  private static FaultInjectResult post(String agentHost, String command, OkHttpClient client)
      throws FaultInjectException {
    String apiUrl = agentHost + "/command/exec";
    RequestBody requestBody =
        RequestBody.Companion.create(
            command, MediaType.parse("Content-Type: text/html; charset=utf-8"));
    Request request = new Request.Builder().url(apiUrl).post(requestBody).build();
    try (Response response = client.newCall(request).execute()) {
      if (response.isSuccessful() && Objects.nonNull(response.body())) {
        String body = response.body().string();
        FaultInjectResult result = objectMapper.readValue(body, FaultInjectResult.class);
        Integer code = result.getReturncode();
        if (code != null && code == 0) {
          log.info("Agent [{}] 命令执行成功 (returncode=0); 若注入后无效果，问题在故障实现而非命令下发", agentHost);
        } else {
          log.error(
              "Agent [{}] 命令执行失败: returncode={}, error={}, stderr={}, stdout={}",
              agentHost, code, result.getError(), result.getStderr(), result.getStdout());
        }
        return result;
      } else {
        String responseBodyStr = StringUtils.EMPTY;
        if (Objects.nonNull(response.body())) {
          responseBodyStr = response.body().string();
        }
        log.error("Response code {}, Response body {}", response.code(), responseBodyStr);
        throw new FaultInjectException("agent response not valid");
      }
    } catch (IOException exception) {
      log.error("fault inject ioexception", exception);
      throw new FaultInjectException("fault inject ioexception", exception);
    }
  }
}
