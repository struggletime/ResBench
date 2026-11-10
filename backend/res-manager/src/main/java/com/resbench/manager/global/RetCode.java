package com.resbench.manager.global;

public enum RetCode {
  SUCCESS(200, "成功"),
  ERROR(400, "失败"),

  NOT_FOUND_ENVINFO(4001, "不支持该环境"),

  NOT_FOUND_DBTYPE(4002, "不支持该数据库类型");

  private String message;

  private int code;

  RetCode(int code, String message) {
    this.code = code;
    this.message = message;
  }

  /**
   * getMessage
   *
   * @return the message
   */
  public String getMessage() {
    return message;
  }

  /**
   * getCode
   *
   * @return the code
   */
  public int getCode() {
    return code;
  }

  /**
   * getInfo
   *
   * @return the code
   */
  public String getInfo() {
    return code + "::" + message;
  }
}
