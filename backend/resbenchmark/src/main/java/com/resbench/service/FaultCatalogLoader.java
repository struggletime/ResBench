package com.resbench.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * 故障目录加载器（设计文档 §5）。
 *
 * <p>加载顺序：外部文件 {@code config/fault-cases.yaml} 优先，缺失时 fallback 到 classpath
 * 的 {@code /fault-cases.yaml}；启动时记录实际来源。目录为空时不影响既有硬编码逻辑
 * （StartByFormService 会回退旧 switch）。
 *
 * <p><b>模板变量权威清单</b>：{{var}} 的取值优先级 = 表单参数（yaml 里该故障声明的 params）
 * 优先，其次才是这里的框架上下文变量。凡使用下列上下文变量之外、又未在 params 中声明的
 * 必填变量，加载时会在日志里告警。
 */
@Component
public class FaultCatalogLoader {
  private static final Logger LOG = LoggerFactory.getLogger(FaultCatalogLoader.class);
  public static final String EXTERNAL_PATH = "config/fault-cases.yaml";

  /** 用于把 yaml 的 {zh,en} 多语言对象序列化为 JSON 字符串透传给前端（纯文本原样保留） */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 模板变量语法：{{name}} / {{name|default}} / {{name|default|mult}} */
  private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{([^{}]+)\\}\\}");

  // ------------------------------------------------------------------
  // 框架上下文变量（唯一权威清单，供模板使用；加载时校验未知必填变量）
  // ------------------------------------------------------------------
  public static final String CTX_DB_TYPE = "dbType";
  public static final String CTX_DB_URL = "dbUrl";
  public static final String CTX_USER = "user";
  public static final String CTX_PASSWORD = "password";
  public static final String CTX_DBCHAOS_JAR = "DBCHAOS_JAR";
  public static final String CTX_AGENT_HOST = "agentHost";
  /** 派生变量：由表单参数 rollback-rate（百分数）换算为小数，供 massive_rollback 的 -rate 使用 */
  public static final String CTX_ROLLBACK_RATE = "rollbackRate";

  /** 所有模板中可直接使用的框架上下文变量（StartByFormService / BenchmarkController 构建） */
  public static final Set<String> CONTEXT_VARIABLES =
      Set.of(CTX_DB_TYPE, CTX_DB_URL, CTX_USER, CTX_PASSWORD, CTX_DBCHAOS_JAR, CTX_AGENT_HOST, CTX_ROLLBACK_RATE);

  /** 单个参数定义（前端表单渲染 + 校验 + 默认值） */
  public record FaultParam(
      String paramName,
      String label,
      String description,
      String type,
      String unit,
      String defaultValue,
      boolean required) {}

  /** 单条故障定义（注入模板 + check 模板 + 参数 schema） */
  public record FaultDefinition(
      String nameKey,
      String name,
      String description,
      String category,
      int autoRecovery,
      int defaultDuration,
      List<FaultParam> params,
      String injectionTemplate,
      String recoveryTemplate,
      String checkTemplate,
      boolean asyncInjection) {

    public boolean hasCheck() {
      return checkTemplate != null && !checkTemplate.trim().isEmpty();
    }

    /** 单语名称（后端写盘/XML/徽章用）：多语言对象取 zh || en，纯文本原样 */
    public String plainName() {
      return plain(name);
    }
  }

  private final Map<String, FaultDefinition> catalog = new LinkedHashMap<>();
  private volatile boolean loaded = false;

  @PostConstruct
  public synchronized void load() {
    catalog.clear();
    Map<String, Object> data = null;
    String source = null;

    Path external = Path.of(EXTERNAL_PATH);
    boolean externalPresent = Files.exists(external);
    if (externalPresent) {
      // 外部文件存在即为唯一权威：内容被全注释/为空 → catalog 即为空，
      // 不再回退 classpath；这样编辑 config/fault-cases.yaml 重启即可增删故障。
      try (InputStream in = Files.newInputStream(external)) {
        data = toMap(new Yaml().load(in));
        source = external.toAbsolutePath().toString();
      } catch (IOException e) {
        LOG.warn("[FaultCatalog] failed to read external {}: {}", EXTERNAL_PATH, e.getMessage());
      }
    } else {
      // 外部文件不存在时才用 classpath 副本兜底（打包发布场景）
      try (InputStream in = getClass().getResourceAsStream("/fault-cases.yaml")) {
        if (in != null) {
          data = toMap(new Yaml().load(in));
          source = "classpath:/fault-cases.yaml";
        }
      } catch (IOException e) {
        LOG.warn("[FaultCatalog] failed to read classpath fault-cases.yaml: {}", e.getMessage());
      }
    }

    if (data == null) {
      // data==null 仅出现在：外部文件存在但解析为空（全注释/空白），或两边都没有
      if (externalPresent) {
        LOG.info("[FaultCatalog] external {} present but empty (all comments/blank) → fault catalog is empty; "
            + "DB faults absent from catalog will fall back to the legacy switch", EXTERNAL_PATH);
      } else {
        LOG.warn("[FaultCatalog] fault-cases.yaml not found (external {} nor classpath), catalog is empty; "
            + "StartByFormService falls back to legacy switch", EXTERNAL_PATH);
      }
      loaded = true;
      return;
    }

    for (Map.Entry<String, Object> entry : data.entrySet()) {
      String nameKey = entry.getKey();
      if (!(entry.getValue() instanceof Map)) {
        continue;
      }
      try {
        FaultDefinition def = parseDefinition(nameKey, toMap(entry.getValue()));
        catalog.put(nameKey, def);
        validateDefinition(def);
      } catch (RuntimeException e) {
        LOG.error("[FaultCatalog] failed to parse fault definition [{}]: {}", nameKey, e.getMessage());
      }
    }
    LOG.info("[FaultCatalog] loaded {} fault definitions from {}", catalog.size(), source);
    LOG.info("[FaultCatalog] available framework context template variables: {}", CONTEXT_VARIABLES);
    loaded = true;
  }

  /**
   * 模板变量校验：{{var}} 既未在 params 声明、又非框架上下文变量、且没有内联默认值时，
   * 运行时渲染必然抛"缺少必填参数"——加载期直接告警，便于加故障时一眼发现拼写错误。
   */
  private void validateDefinition(FaultDefinition def) {
    Set<String> params = new LinkedHashSet<>();
    for (FaultParam p : def.params()) {
      params.add(p.paramName());
    }
    List<String> unknown = new ArrayList<>();
    // 注意：不能用 List.of(...) —— 它遇 null 抛 NPE，而 recovery/check 模板缺省即为 null，
    // 会让本校验对每个故障都抛异常（被 load() catch 成 ERROR 日志），校验形同虚设。
    for (String template :
        new String[] {def.injectionTemplate(), def.recoveryTemplate(), def.checkTemplate()}) {
      if (template == null || template.trim().isEmpty()) {
        continue;
      }
      Matcher matcher = TOKEN_PATTERN.matcher(template);
      while (matcher.find()) {
        String[] parts = matcher.group(1).split("\\|", -1);
        String name = parts[0].trim();
        boolean hasDefault = parts.length > 1 && !parts[1].trim().isEmpty();
        if (!params.contains(name) && !CONTEXT_VARIABLES.contains(name) && !hasDefault) {
          unknown.add(matcher.group(0));
        }
      }
    }
    if (!unknown.isEmpty()) {
      LOG.warn("[FaultCatalog] fault [{}] template uses required variables that are neither declared"
              + " params nor framework context vars (will fail at render time): {}",
          def.nameKey(), unknown);
    }
  }

  private FaultDefinition parseDefinition(String nameKey, Map<String, Object> def) {
    String name = i18n(def.get("name"), nameKey);
    String description = i18n(def.get("description"), "");
    String category = str(def.get("category"), "database");
    int autoRecovery = intOf(def.get("autoRecovery"), 0);
    int defaultDuration = intOf(def.get("defaultDuration"), 60);

    List<FaultParam> params = new ArrayList<>();
    Object paramsObj = def.get("params");
    if (paramsObj instanceof Map) {
      for (Map.Entry<String, Object> p : toMap(paramsObj).entrySet()) {
        params.add(parseParam(p.getKey(), p.getValue()));
      }
    }

    String injectionTemplate = commandOf(def.get("injection"));
    String recoveryTemplate = commandOf(def.get("recovery"));
    String checkTemplate = commandOf(def.get("check"));
    // v2.2：DBChaos 持续型故障可声明 asyncInjection: true → agent /inject dispatch（启动即返回）
    boolean asyncInjection = Boolean.TRUE.equals(def.get("asyncInjection"));
    return new FaultDefinition(
        nameKey, name, description, category, autoRecovery, defaultDuration,
        params, injectionTemplate, recoveryTemplate, checkTemplate, asyncInjection);
  }

  private FaultParam parseParam(String paramName, Object raw) {
    Map<String, Object> p = raw instanceof Map ? toMap(raw) : Map.of();
    return new FaultParam(
        paramName,
        i18n(p.get("label"), paramName),
        i18n(p.get("description"), ""),
        str(p.get("type"), "string"),
        str(p.get("unit"), ""),
        str(p.get("default"), ""),
        Boolean.TRUE.equals(p.get("required")));
  }

  private String commandOf(Object raw) {
    if (!(raw instanceof Map)) {
      return null;
    }
    Map<String, Object> m = toMap(raw);
    Object cmd = m.get("command");
    return cmd == null ? null : cmd.toString().trim();
  }

  public Optional<FaultDefinition> get(String nameKey) {
    ensureLoaded();
    return Optional.ofNullable(catalog.get(nameKey));
  }

  public List<FaultDefinition> list() {
    ensureLoaded();
    return new ArrayList<>(catalog.values());
  }

  public boolean isLoaded() {
    return loaded;
  }

  private void ensureLoaded() {
    if (!loaded) {
      load();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toMap(Object raw) {
    if (raw instanceof Map) {
      return (Map<String, Object>) raw;
    }
    return Map.of();
  }

  private static String str(Object value, String defaultValue) {
    return value == null ? defaultValue : value.toString();
  }

  /**
   * yaml 多语言字段解析：支持两种写法
   * <ul>
   *   <li>纯字符串：原样保留（单语，前端直接显示）</li>
   *   <li>{zh: …, en: …} 对象：序列化为 JSON 字符串（{"zh":"…","en":"…"}）透传给前端，
   *       前端按当前语言取词（yaml 优先于 i18n 词典）</li>
   * </ul>
   */
  static String i18n(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Map<?, ?> m) {
      try {
        return MAPPER.writeValueAsString(m);
      } catch (Exception e) {
        LOG.warn("[FaultCatalog] failed to serialize i18n object, fallback to text: {}", e.getMessage());
        return value.toString();
      }
    }
    return value.toString();
  }

  /**
   * 后端单语消费（faultName / XML description / 手动补验等只需要一个确定文本）：
   * 多语言 JSON 取 zh || en；普通文本原样返回。
   */
  static String plain(String text) {
    if (text == null || !text.trim().startsWith("{")) {
      return text;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(text);
      if (node != null && node.isObject()) {
        if (node.hasNonNull("zh")) {
          return node.get("zh").asText();
        }
        if (node.hasNonNull("en")) {
          return node.get("en").asText();
        }
      }
    } catch (Exception e) {
      // 透传原文本，交由前端容错
    }
    return text;
  }

  private static int intOf(Object value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}