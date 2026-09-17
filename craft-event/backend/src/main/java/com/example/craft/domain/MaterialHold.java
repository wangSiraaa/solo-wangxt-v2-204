package com.example.craft.domain;

import java.time.LocalDateTime;

public record MaterialHold(
        Long id,
        String orderNo,
        Long accountId,
        Long itemId,
        int qty,
        String status,
        LocalDateTime createdAt,
        LocalDateTime consumedAt,
        LocalDateTime releasedAt,
        LocalDateTime reversedAt
) {}
