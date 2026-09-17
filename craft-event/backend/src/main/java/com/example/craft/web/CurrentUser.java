package com.example.craft.web;

import com.example.craft.domain.Account;

/** 当前请求登录账号（由 AuthInterceptor 在拦截阶段写入）。 */
public class CurrentUser {

    private static final ThreadLocal<Account> HOLDER = new ThreadLocal<>();

    private CurrentUser() {}

    public static void set(Account account) {
        HOLDER.set(account);
    }

    public static Account get() {
        Account a = HOLDER.get();
        if (a == null) {
            throw new IllegalStateException("no authenticated account in request scope");
        }
        return a;
    }

    public static Long id() {
        return get().id();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
