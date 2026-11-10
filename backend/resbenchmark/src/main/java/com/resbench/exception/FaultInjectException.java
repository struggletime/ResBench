package com.resbench.exception;

import java.io.Serial;

/**
 * 故障注入异常
 *
 * @since 2025-03-29
 */
public class FaultInjectException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * constructor
   *
   * @param message 异常信息
   */
  public FaultInjectException(String message) {
    super(message);
  }

  /**
   * constructor
   *
   * @param message 异常信息
   * @param cause 原因
   */
  public FaultInjectException(String message, Throwable cause) {
    super(message, cause);
  }
}
