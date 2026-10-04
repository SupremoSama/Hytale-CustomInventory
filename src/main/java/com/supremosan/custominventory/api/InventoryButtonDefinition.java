package com.supremosan.custominventory.api;

import java.util.Objects;
import java.util.function.Consumer;

public record InventoryButtonDefinition(String id, String title, int order,
                                        Consumer<InventoryContext> action) {
    public InventoryButtonDefinition {
        InventoryRegistry.validateId(id);
        if (Objects.requireNonNull(title, "title").isBlank()) throw new IllegalArgumentException("title is blank");
        Objects.requireNonNull(action, "action");
    }
}
