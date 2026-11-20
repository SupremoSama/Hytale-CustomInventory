package com.supremosan.custominventory.api;

import java.util.Objects;

public record InventoryItemTooltipDefinition(String id, int order, Provider provider) {
    public InventoryItemTooltipDefinition {
        InventoryRegistry.validateId(id);
        Objects.requireNonNull(provider, "provider");
    }

    @FunctionalInterface
    public interface Provider {
        String describe(InventoryItemTooltipContext context);
    }
}
