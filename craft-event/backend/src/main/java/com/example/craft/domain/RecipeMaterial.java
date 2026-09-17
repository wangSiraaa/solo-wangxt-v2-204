package com.example.craft.domain;

public record RecipeMaterial(
        Long id,
        Long recipeVersionId,
        Long itemId,
        int qty,
        int seqNo
) {}
