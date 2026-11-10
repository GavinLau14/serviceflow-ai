package com.serviceflow.common;

public class ApiException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiException forbidden() {
    return new ApiException(403, "FORBIDDEN", "没有执行此操作的权限");
  }

  public static ApiException missing() {
    return new ApiException(404, "NOT_FOUND", "工单不存在或不可访问");
  }

  public static ApiException conflict() {
    return new ApiException(409, "VERSION_CONFLICT", "工单已更新，请刷新后重试");
  }

  public static ApiException bad(String message) {
    return new ApiException(400, "INVALID_REQUEST", message);
  }
}
