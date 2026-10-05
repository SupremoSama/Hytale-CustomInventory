package com.supremosan.custominventory.api;

import java.util.Objects;
import java.util.function.Function;

public record InventoryUiExtensionDefinition(String id, int order, Function<InventoryContext, InventoryUiExtension> factory) {
    public InventoryUiExtensionDefinition {
        InventoryRegistry.validateId(id);
        Objects.requireNonNull(factory, "factory");
    }
}
