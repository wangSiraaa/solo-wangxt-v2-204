package com.example.craft.domain;

import java.time.LocalDateTime;

public record RecipeVersion(
        Long id,
        Long recipeId,
        int versionNo,
        Long outputItemId,
        int outputQty,
        LocalDateTime eventStartsAt,
        LocalDateTime eventEndsAt,
        boolean autoComplete,
        int craftSeconds,
        int timeoutSeconds,
        String status,
        String changelog,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime publishedAt
) {}
