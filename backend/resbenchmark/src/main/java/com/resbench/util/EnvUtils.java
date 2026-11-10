package com.resbench.util;

import java.util.Collections;
import java.util.Map;

public class EnvUtils {
  /** 获取环境变量 */
  public static String getEnv(String key) {
    return System.getenv(key);
  }

  /** 获取环境变量，如果不存在则返回默认值 */
  public static String getEnv(String key, String defaultValue) {
    String value = System.getenv(key);
    return value != null ? value : defaultValue;
  }

  /** 获取系统属性 */
  public static String getProperty(String key) {
    return System.getProperty(key);
  }

  /** 获取系统属性，如果不存在则返回默认值 */
  public static String getProperty(String key, String defaultValue) {
    return System.getProperty(key, defaultValue);
  }

  /** 获取所有环境变量（不可修改） */
  public static Map<String, String> getAllEnv() {
    return Collections.unmodifiableMap(System.getenv());
  }
}
