package com.mall.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 统一 API 响应结构。
 */
@Data
@AllArgsConstructor
public class Result<T> {

    private int code;       // 0=成功，非0=业务错误码
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static Result<Void> ok() {
        return new Result<>(0, "ok", null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
