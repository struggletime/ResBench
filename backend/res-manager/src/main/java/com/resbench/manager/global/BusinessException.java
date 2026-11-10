package com.resbench.manager.global;

public class BusinessException extends RuntimeException {
  private final int code;

  public BusinessException(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}
