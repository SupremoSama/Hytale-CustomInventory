package com.supremosan.custominventory.api;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.Objects;

/** World-thread tooltip lookup for a detached item in one native inventory section. */
public record InventoryItemTooltipContext(InventoryContext inventory, String sectionId, int slot, ItemStack stack) {
    public InventoryItemTooltipContext {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(stack, "stack");
        if (ItemStack.isEmpty(stack)) throw new IllegalArgumentException("Tooltip items must not be empty");
        // Providers can inspect server metadata without mutating the authoritative item.
        ItemStack copy = stack.withMetadata(stack.getMetadata());
        copy.setOverrideDroppedItemAnimation(stack.getOverrideDroppedItemAnimation());
        stack = copy;
    }
}
