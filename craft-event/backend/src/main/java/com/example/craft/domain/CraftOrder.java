package com.example.craft.domain;

import java.time.LocalDateTime;

public record CraftOrder(
        Long id,
        String orderNo,
        Long accountId,
        Long recipeId,
        Long recipeVersionId,
        int snapshotVersion,
        Long outputItemId,
        int outputQty,
        int qty,
        String status,
        boolean autoComplete,
        LocalDateTime heldFrom,
        LocalDateTime completeAt,
        LocalDateTime expireAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt,
        String cancelReason,
        LocalDateTime revokedAt,
        String revokeOperator,
        String requestId,
        LocalDateTime createdAt
) {}
