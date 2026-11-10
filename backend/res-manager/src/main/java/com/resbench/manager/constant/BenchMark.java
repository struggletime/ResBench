package com.resbench.manager.constant;

/**
 * @author
 * @description : 目前支持的后端名称
 * @since : 2025/7/11
 */
public enum BenchMark {
  OPEN_GAUSS("openGauss"),

  GAUSS("gauss"),

  OCEANBASE("oceanBase");

  private String name;

  BenchMark(String name) {
    this.name = name;
  }
}
