package com.resbench.service.probe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * dbType → 探针。Spring 把所有 {@link FileFaultTargetProbe} bean 收进来，按各自的
 * {@link FileFaultTargetProbe#supportedDbTypes()} 展开。
 *
 * <p><b>接一个新库不需要动这里</b>：加一个 {@code @Component} 的探针实现就自动登记。
 */
@Component
public class FileFaultTargetProbeRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(FileFaultTargetProbeRegistry.class);

  private final Map<String, FileFaultTargetProbe> byDbType = new LinkedHashMap<>();

  public FileFaultTargetProbeRegistry(List<FileFaultTargetProbe> probes) {
    for (FileFaultTargetProbe probe : probes) {
      for (String dbType : probe.supportedDbTypes()) {
        String key = normalize(dbType);
        FileFaultTargetProbe previous = byDbType.putIfAbsent(key, probe);
        if (previous != null) {
          // 一个 dbType 两个探针 → 立刻炸，别等到运行期随机挑一个、故障打在别的库上
          throw new IllegalStateException(String.format(
              "dbType「%s」被两个探针同时声明：%s 与 %s。请让其中一个的 supportedDbTypes() 不要包含它",
              key, previous.getClass().getName(), probe.getClass().getName()));
        }
      }
    }
    LOG.info("file 故障目标探针已登记: {}", byDbType.keySet());
  }

  public Optional<FileFaultTargetProbe> find(String dbType) {
    return Optional.ofNullable(byDbType.get(normalize(dbType)));
  }

  /** 当前有探针的 dbType，用来在"没有探针"的报错里说清支持哪些。 */
  public Set<String> supportedDbTypes() {
    return new TreeSet<>(byDbType.keySet());
  }

  /** 与 {@code TpccDataVerifyService.normalizeDbType} 同语义：postgre 是 postgresql 的别名。 */
  static String normalize(String dbType) {
    String value = dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT);
    return "postgre".equals(value) ? "postgresql" : value;
  }
}
