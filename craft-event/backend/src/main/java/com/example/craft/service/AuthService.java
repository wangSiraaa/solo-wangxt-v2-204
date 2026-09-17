package com.example.craft.service;

import com.example.craft.config.CraftProperties;
import com.example.craft.domain.Account;
import com.example.craft.repo.AccountRepo;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuthService {

    private final AccountRepo accountRepo;
    private final BCryptPasswordEncoder encoder;
    private final CraftProperties props;

    public AuthService(AccountRepo accountRepo, BCryptPasswordEncoder encoder, CraftProperties props) {
        this.accountRepo = accountRepo;
        this.encoder = encoder;
        this.props = props;
    }

    @Transactional
    public Map<String, Object> login(String username, String password) {
        Account account = accountRepo.findByUsername(username)
                .orElseThrow(() -> ApiException.unauthorized("用户名或密码错误"));
        if (!account.enabled() || !encoder.matches(password, account.passwordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(props.tokenTtlHours());
        String token = accountRepo.createToken(account.id(), expiresAt);
        return Map.of(
                "token", token,
                "username", account.username(),
                "displayName", account.displayName(),
                "role", account.role(),
                "expiresAt", expiresAt
        );
    }
}
