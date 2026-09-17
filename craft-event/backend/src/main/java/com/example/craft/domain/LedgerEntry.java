package com.example.craft.domain;

import java.time.LocalDateTime;

public record LedgerEntry(
        Long id,
        Long accountId,
        Long itemId,
        String changeType,
        int qtyChange,
        int heldDelta,
        int balanceTotal,
        int balanceHeld,
        String refType,
        String refNo,
        Long reversalOf,
        String remark,
        LocalDateTime createdAt
) {}
