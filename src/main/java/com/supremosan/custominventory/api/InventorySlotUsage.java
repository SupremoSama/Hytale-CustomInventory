package com.supremosan.custominventory.api;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

/** Occupied slots, independent of the quantity stored in each stack. */
public final class InventorySlotUsage {
    private InventorySlotUsage() {}
    public static int occupiedSlots(ItemContainer container) {
        if (container == null) return 0;
        int occupied = 0;
        for (short slot = 0; slot < container.getCapacity(); slot++)
            if (!ItemStack.isEmpty(container.getItemStack(slot))) occupied++;
        return occupied;
    }
}
