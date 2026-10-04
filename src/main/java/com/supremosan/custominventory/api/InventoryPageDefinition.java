package com.supremosan.custominventory.api;

import java.util.Objects;
import java.util.function.Function;

public record InventoryPageDefinition(String id, String title, int order,
                                      Function<InventoryContext, InventoryContent> factory) {
    public InventoryPageDefinition {
        InventoryRegistry.validateId(id);
        if (Objects.requireNonNull(title, "title").isBlank()) throw new IllegalArgumentException("title is blank");
        Objects.requireNonNull(factory, "factory");
    }
}
