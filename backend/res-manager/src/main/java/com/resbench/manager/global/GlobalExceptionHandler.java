package com.resbench.manager.global;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
  /**
   * 业务异常处理（你抛 BusinessException）
   */
  @ExceptionHandler(BusinessException.class)
  public GlobalResponse<?> handleBusinessException(BusinessException ex) {
    return GlobalResponse.error(ex.getCode(), ex.getMessage());
  }

  /**
   * 空指针异常处理（或其他常见异常）
   */
  @ExceptionHandler(NullPointerException.class)
  public GlobalResponse<?> handleNullPointerException(NullPointerException ex) {
    return GlobalResponse.error(1002, "空指针异常：" + ex.getMessage());
  }

  /**
   * 请求入参校验异常处理
   *
   * @param ex 校验异常
   * @return ResponseEntity
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public GlobalResponse<?> handleValidation(MethodArgumentNotValidException ex) {
    List<String> errors = ex.getBindingResult()
      .getFieldErrors()
      .stream()
      .map(error -> error.getField() + ": " + error.getDefaultMessage())
      .toList();

    return GlobalResponse.error(400, "请求校验异常", errors);
  }

  /**
   * 所有未处理异常
   */
  @ExceptionHandler(Exception.class)
  public GlobalResponse<?> handleGeneralException(Exception ex) {
    return GlobalResponse.error(500, "系统异常：" + ex.getMessage());
  }
}
