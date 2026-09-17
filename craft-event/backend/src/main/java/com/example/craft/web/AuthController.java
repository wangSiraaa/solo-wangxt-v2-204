package com.example.craft.web;

import com.example.craft.repo.AccountRepo;
import com.example.craft.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AccountRepo accountRepo;

    public AuthController(AuthService authService, AccountRepo accountRepo) {
        this.authService = authService;
        this.accountRepo = accountRepo;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) {
            throw com.example.craft.service.ApiException.badRequest("用户名和密码必填");
        }
        return authService.login(username, password);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            accountRepo.deleteToken(header.substring(7).trim());
        }
        return Map.of("ok", true);
    }
}
