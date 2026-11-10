package com.serviceflow.common;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  public static Map<String, String> body(String code, String message) {
    return Map.of("code", code, "message", message);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status).body(body(e.code, e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HandlerMethodValidationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请检查字段格式、长度及必填项"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> data(DataAccessException e) {
    LoggerFactory.getLogger(getClass())
        .error("Storage operation failed: {}", e.getClass().getSimpleName());
    return ResponseEntity.status(503).body(body("STORAGE_UNAVAILABLE", "存储服务暂不可用，请稍后重试"));
  }
}
