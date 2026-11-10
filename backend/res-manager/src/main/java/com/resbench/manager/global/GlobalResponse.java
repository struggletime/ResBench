package com.resbench.manager.global;

import static com.resbench.manager.global.RetCode.SUCCESS;

public class GlobalResponse<T> {
  private int code;

  private String msg;

  private T data;

  public GlobalResponse(int code, String msg, T data) {
    this.code = code;
    this.msg = msg;
    this.data = data;
  }

  public GlobalResponse(RetCode retCode, T data) {
    this.code = retCode.getCode();
    this.msg = retCode.getMessage();
    this.data = data;
  }

  public GlobalResponse(RetCode retCode) {
    this(retCode, null);
  }

  public static <T> GlobalResponse<T> response(RetCode retCode, T data) {
    return new GlobalResponse<>(retCode, data);
  }

  public static <T> GlobalResponse<T> response(int code, String msg, T data) {
    return new GlobalResponse<>(code, msg, data);
  }

  public static <T> GlobalResponse<T> success() {
    return new GlobalResponse<>(SUCCESS);
  }

  public static <T> GlobalResponse<T> success(T data) {
    return new GlobalResponse<>(SUCCESS, data);
  }

  public static <T> GlobalResponse<T> error(RetCode retCode) {
    return new GlobalResponse<>(retCode.getCode(), retCode.getMessage(), null);
  }

  public static <T> GlobalResponse<T> error(int code, String msg) {
    return new GlobalResponse<>(code, msg, null);
  }

  public static <T> GlobalResponse<T> error(int code, String msg, T data) {
    return new GlobalResponse<>(code, msg, data);
  }

  public int getCode() {
    return code;
  }

  public void setCode(int code) {
    this.code = code;
  }

  public String getMsg() {
    return msg;
  }

  public void setMsg(String msg) {
    this.msg = msg;
  }

  public T getData() {
    return data;
  }

  public void setData(T data) {
    this.data = data;
  }
}
