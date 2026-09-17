package com.example.craft.domain;

import java.time.LocalDateTime;

public record Inventory(
        Long id,
        Long accountId,
        Long itemId,
        int totalQty,
        int heldQty,
        LocalDateTime updatedAt
) {
    public int available() {
        return totalQty - heldQty;
    }
}
