package com.resbench.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resbench.common.remote.RestfulCaller;
import java.util.HashMap;
import java.util.Map;

import org.junit.Ignore;
import org.junit.Test;

public class TestFaultInjection {

  @Ignore("该测试用例不通用，跳过次测试用例")
  @Test
  public void testWithUrl() {
    String ip = "127.0.0.1";
    String port = "8000";
    String host = "http://" + ip + ":" + port;

    String cmd = "/opt/chaosblade/blade";
    String[] args = new String[] {"destroy", "disk", "fill", "--path", "/home", "--percent", "100"};
    RestfulCaller caller = new RestfulCaller(host);
    Map<String, Object> command = new HashMap<>();
    command.put("cmd", cmd);
    command.put("args", args);
    ObjectMapper objectMapper = new ObjectMapper();
    String param = null;
    try {
      param = objectMapper.writeValueAsString(command);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    String result = caller.restCallerPost("/inject", param);
    System.out.println(result);
  }
}
