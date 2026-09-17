package com.example.craft.domain;

import java.time.LocalDateTime;

public record Account(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String role,
        boolean enabled,
        LocalDateTime createdAt
) {}
