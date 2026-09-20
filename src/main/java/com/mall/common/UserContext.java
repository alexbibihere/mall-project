package com.mall.common;

/**
 * 当前登录用户上下文（ThreadLocal）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long get() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
