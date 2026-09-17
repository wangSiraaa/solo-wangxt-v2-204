package com.example.craft.domain;

import java.time.LocalDateTime;

public record RevokeException(
        Long id,
        String exceptionNo,
        String orderNo,
        Long accountId,
        String reason,
        Long missingItemId,
        Integer missingQty,
        String status,
        String remark,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt,
        String resolvedBy
) {}
