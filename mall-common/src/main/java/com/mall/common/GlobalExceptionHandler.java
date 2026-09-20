package com.mall.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常返回错误码；未知异常统一 500 并记日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * M2.2：Seata 2.0 的 AdapterInvocationWrapper 会把 @GlobalTransactional
     * 方法内抛出的业务异常包成 RuntimeException("try to proceed invocation error")，
     * 这里解包还原真实业务异常（如 409 库存不足），避免误报 500。
     */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleWrapped(RuntimeException e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        if (cur instanceof BizException biz) {
            return Result.error(biz.getCode(), biz.getMessage());
        }
        log.error("unhandled runtime exception", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数错误");
        return Result.error(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("unhandled exception", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
