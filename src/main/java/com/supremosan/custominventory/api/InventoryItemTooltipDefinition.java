package com.supremosan.custominventory.api;

import java.util.Objects;

/** A namespaced item-description provider; null delegates to the next provider or native metadata. */
public record InventoryItemTooltipDefinition(String id, int order, Provider provider) {
    public InventoryItemTooltipDefinition {
        InventoryRegistry.validateId(id);
        Objects.requireNonNull(provider, "provider");
    }

    @FunctionalInterface
    public interface Provider {
        /** Runs on the player's world thread. Return null for items this provider does not own. */
        String describe(InventoryItemTooltipContext context);
    }
}
