package com.resbench.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故障目录模板渲染引擎（设计文档 §5.3）。
 *
 * <p>语法：
 * <ul>
 *   <li>{{var}}            —— 必填；优先取表单参数，其次取框架上下文变量；缺失报错</li>
 *   <li>{{var|default}}    —— 可空，缺省用 default</li>
 *   <li>{{var|default|mult}} —— 数值参数乘 mult（duration 秒→毫秒等）</li>
 * </ul>
 */
public final class FaultTemplateEngine {
  private static final Pattern TOKEN = Pattern.compile("\\{\\{([^{}]+)\\}\\}");

  private FaultTemplateEngine() {}

  /**
   * @param template 模板字符串（如 "java -jar {{DBCHAOS_JAR}} {{dbType}} ..."）
   * @param params 表单参数（nameKey 下的 params）
   * @param context 框架上下文变量（dbUrl/user/password/dbType/DBCHAOS_JAR/agentHost）
   * @return 渲染后的单行命令（按空白拆分后由调用方组装为 argv）
   */
  public static String render(String template, Map<String, String> params, Map<String, String> context) {
    if (template == null || template.trim().isEmpty()) {
      return "";
    }
    Matcher matcher = TOKEN.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String[] parts = matcher.group(1).split("\\|", -1);
      String name = parts[0].trim();
      String value = lookup(name, params, context);
      if (value == null || value.trim().isEmpty()) {
        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
          value = parts[1].trim();
        } else if (parts.length > 1) {
          value = ""; // 显式空默认值 {{x|}}
        } else {
          throw new IllegalArgumentException(
              "故障模板缺少必填参数: " + name + "（模板片段: " + matcher.group(0) + "）");
        }
      }
      if (parts.length > 2 && value != null && !value.trim().isEmpty()) {
        value = applyMultiplier(value, parts[2]);
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
    }
    matcher.appendTail(sb);
    return sb.toString().trim();
  }

  private static String lookup(String name, Map<String, String> params, Map<String, String> context) {
    if (params != null && params.containsKey(name)) {
      String v = params.get(name);
      return v == null ? null : v.trim();
    }
    if (context != null && context.containsKey(name)) {
      String v = context.get(name);
      return v == null ? null : v.trim();
    }
    return null;
  }

  private static String applyMultiplier(String value, String mult) {
    try {
      double factor = Double.parseDouble(mult.trim());
      double parsed = Double.parseDouble(value.trim());
      long result = (long) Math.round(parsed * factor);
      return String.valueOf(result);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("模板数值乘子无效: {{...|" + mult + "}} value=" + value, e);
    }
  }
}