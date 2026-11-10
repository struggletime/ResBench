package com.resbench.manager.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "res.benchmark")
public class BenchMarkAddr {
  /* key: benchMark后端名称  */
  private Map<String, String> addr;

  public Map<String, String> getAddr() {
    return addr;
  }

  public void setAddr(Map<String, String> addr) {
    this.addr = addr;
  }

  public Optional<String> getBenchMarkAddr(String benchMarkName) {
    return Optional.ofNullable(addr.get(benchMarkName));
  }

  public Set<String> getEnvInfo() {
    return addr.keySet();
  }

  public String getAnyone() {
    return addr.values().iterator().next();
  }
}
