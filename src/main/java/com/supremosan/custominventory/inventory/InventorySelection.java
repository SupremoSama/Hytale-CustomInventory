package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

/** A detached snapshot of a displayed source. Selecting never removes an item from its container. */
public final class InventorySelection {
    private final NativeInventorySection section;
    private final ItemContainer container;
    private final int slot;
    private final ItemStack expected;

    private InventorySelection(NativeInventorySection section, ItemContainer container, int slot, ItemStack stack) {
        this.section = section;
        this.container = container;
        this.slot = slot;
        this.expected = snapshot(stack);
    }

    public static InventorySelection capture(NativeInventorySection section, ItemContainer container, int slot) {
        if (section == null || !InventoryOperations.validSlot(container, slot)) return null;
        return fromDisplayed(section, container, slot, container.getItemStack((short) slot));
    }

    /** Uses the last displayed snapshot rather than substituting an item that appeared after rendering. */
    public static InventorySelection fromDisplayed(NativeInventorySection section, ItemContainer container,
                                                   int slot, ItemStack displayed) {
        if (section == null || !InventoryOperations.validSlot(container, slot) || ItemStack.isEmpty(displayed)) return null;
        return new InventorySelection(section, container, slot, displayed);
    }

    public NativeInventorySection section() { return section; }
    public ItemContainer container() { return container; }
    public int slot() { return slot; }
    public int quantity() { return expected.getQuantity(); }
    public String itemId() { return expected.getItemId(); }

    public boolean sameSource(InventorySelection other) {
        return other != null && section == other.section && container == other.container && slot == other.slot;
    }

    public boolean sameSnapshot(InventorySelection other) {
        return sameSource(other) && matches(other.expected);
    }

    public boolean matches(ItemStack current) {
        return !ItemStack.isEmpty(current) && expected.equals(current)
                && expected.getOverrideDroppedItemAnimation() == current.getOverrideDroppedItemAnimation();
    }

    /** Only rebase quantities removed by an already validated native move; preserve every other item property. */
    public InventorySelection afterRemoval(ItemStack current, int maximumRemoved) {
        if (ItemStack.isEmpty(current)) return null;
        int removed = expected.getQuantity() - current.getQuantity();
        if (removed <= 0 || removed > maximumRemoved
                || !expected.withQuantity(current.getQuantity()).equals(current)
                || expected.getOverrideDroppedItemAnimation() != current.getOverrideDroppedItemAnimation()) return null;
        return new InventorySelection(section, container, slot, current);
    }

    /** Snapshots include server metadata, durability and quality; display copies are a separate operation. */
    public static ItemStack snapshot(ItemStack stack) {
        if (ItemStack.isEmpty(stack)) return null;
        // The engine's getMetadata() returns a detached BSON clone, including nested values.
        ItemStack copy = stack.withMetadata(stack.getMetadata());
        copy.setOverrideDroppedItemAnimation(stack.getOverrideDroppedItemAnimation());
        return copy;
    }
}
