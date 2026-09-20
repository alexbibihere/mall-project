package com.mall.common;

import lombok.Getter;

/**
 * 业务异常：携带错误码，由全局异常处理器统一转为响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException of(String message) {
        return new BizException(400, message);
    }

    public static BizException of(int code, String message) {
        return new BizException(code, message);
    }
}
