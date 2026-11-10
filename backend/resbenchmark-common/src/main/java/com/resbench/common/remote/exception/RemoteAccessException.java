package com.resbench.common.remote.exception;

public class RemoteAccessException extends RuntimeException {
  private static final long serialVersionUID = -7034897190745766939L;

  public RemoteAccessException() {}

  public RemoteAccessException(String message) {
    super(message);
  }

  public RemoteAccessException(String message, Throwable cause) {
    super(message, cause);
  }

  public RemoteAccessException(Throwable cause) {
    super(cause);
  }

  public RemoteAccessException(
      String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
