package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

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

    public InventorySelection withQuantity(int newQuantity) {
        if (newQuantity <= 0 || expected == null) return null;
        return new InventorySelection(section, container, slot, expected.withQuantity(newQuantity));
    }

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

    public boolean canTakeFrom(ItemStack current, int quantity) {
        if (ItemStack.isEmpty(current) || quantity <= 0 || current.getQuantity() < quantity) return false;
        if (!expected.getItemId().equals(current.getItemId())) return false;
        return expected.isStackableWith(current)
                && expected.getOverrideDroppedItemAnimation() == current.getOverrideDroppedItemAnimation();
    }

    public InventorySelection afterRemoval(ItemStack current, int maximumRemoved) {
        if (ItemStack.isEmpty(current)) return null;
        int removed = expected.getQuantity() - current.getQuantity();
        if (removed <= 0 || removed > maximumRemoved
                || !expected.withQuantity(current.getQuantity()).equals(current)
                || expected.getOverrideDroppedItemAnimation() != current.getOverrideDroppedItemAnimation()) return null;
        return new InventorySelection(section, container, slot, current);
    }

    public static ItemStack snapshot(ItemStack stack) {
        if (ItemStack.isEmpty(stack)) return null;

        ItemStack copy = stack.withMetadata(stack.getMetadata());
        copy.setOverrideDroppedItemAnimation(stack.getOverrideDroppedItemAnimation());
        return copy;
    }
}
