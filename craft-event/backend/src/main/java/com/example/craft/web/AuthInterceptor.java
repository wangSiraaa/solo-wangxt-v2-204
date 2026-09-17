package com.example.craft.web;

import com.example.craft.domain.Account;
import com.example.craft.repo.AccountRepo;
import com.example.craft.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 令牌鉴权拦截器。规则：
 *  - /api/auth/** 放行；
 *  - /api/operator/** 仅 OPERATOR；
 *  - 其余 /api/** 仅登录玩家；
 * 按钮是否可点只影响体验，真正的权限判定永远在这里（服务端）执行。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AccountRepo accountRepo;

    public AuthInterceptor(AccountRepo accountRepo) {
        this.accountRepo = accountRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || path.startsWith("/api/auth/")) {
            return true;
        }
        String token = extractToken(request);
        if (token == null) {
            throw ApiException.unauthorized("未登录");
        }
        Account account = accountRepo.resolveToken(token).orElseThrow(() -> ApiException.unauthorized("登录已过期"));

        if (path.startsWith("/api/operator/") && !"OPERATOR".equals(account.role())) {
            throw ApiException.forbidden("需要运营角色");
        }
        if (path.startsWith("/api/player/") && !"PLAYER".equals(account.role())) {
            throw ApiException.forbidden("需要玩家角色");
        }
        CurrentUser.set(account);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUser.clear();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        String param = request.getParameter("token");
        return param == null || param.isBlank() ? null : param.trim();
    }
}
