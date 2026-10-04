package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;

/** Maps the single visible utility slot to the selected slot in the real utility container. */
public final class UtilitySlotProjection {
    // These are CustomUI drag identities, never native inventory section IDs.
    // Every one-slot projection needs its own identity because its visible slot is always zero.
    public static final int CENTER_GRID_ID = -1050;
    private static final int FIRST_WHEEL_GRID_ID = -1051;
    private static final int WHEEL_SLOTS = 4;

    private UtilitySlotProjection() { }

    public static int activeIndex(int capacity, int activeSlot) {
        return activeSlot >= 0 && activeSlot < capacity ? activeSlot : -1;
    }

    public static ItemGridSlot[] slots(ItemStack[] snapshot, int activeSlot) {
        int index = activeIndex(snapshot.length, activeSlot);
        return new ItemGridSlot[]{InventoryDisplay.slot(index < 0 ? null : snapshot[index])};
    }

    public static int sourceIndex(Integer visibleSlot, int activeSlot, int capacity, InventorySelection captured) {
        if (visibleSlot == null || visibleSlot != 0) return -1;
        // A selection made before the active utility changed still refers to its original item.
        int index = captured != null && captured.section() == NativeInventorySection.UTILITY
                ? captured.slot() : activeSlot;
        return activeIndex(capacity, index);
    }

    public static int destinationIndex(Integer visibleSlot, ItemStack[] snapshot, int activeSlot) {
        if (visibleSlot == null || visibleSlot != 0) return -1;
        int selected = activeIndex(snapshot.length, activeSlot);
        if (selected >= 0) return selected;
        for (int index = 0; index < snapshot.length; index++) {
            if (ItemStack.isEmpty(snapshot[index])) return index;
        }
        return -1;
    }

    /** Each wheel grid has one visible slot, but its bound payload names a real utility index. */
    public static int wheelIndex(String payload, Integer visibleSlot, int capacity) {
        if (visibleSlot == null || visibleSlot != 0 || payload == null || !payload.matches("[0-3]")) return -1;
        return activeIndex(capacity, Integer.parseInt(payload));
    }

    public static int wheelGridId(int index) {
        if (index < 0 || index >= WHEEL_SLOTS) throw new IllegalArgumentException("Invalid utility wheel slot");
        return FIRST_WHEEL_GRID_ID - index;
    }

    public static boolean isProjectedGrid(Integer sectionId) {
        return sectionId != null && (sectionId == CENTER_GRID_ID
                || sectionId <= FIRST_WHEEL_GRID_ID && sectionId > FIRST_WHEEL_GRID_ID - WHEEL_SLOTS);
    }

    /** Resolves the grid identity even after hiding the wheel cancels its client drag-start event. */
    public static int dragSourceIndex(int sectionId, Integer visibleSlot, int activeSlot,
                                      int capacity, InventorySelection captured) {
        if (visibleSlot == null) return -1;
        if (sectionId == CENTER_GRID_ID) {
            int actual = sourceIndex(0, activeSlot, capacity, captured);
            return visibleSlot == 0 || visibleSlot == actual ? actual : -1;
        }
        int index = FIRST_WHEEL_GRID_ID - sectionId;
        return index >= 0 && index < WHEEL_SLOTS && (visibleSlot == 0 || visibleSlot == index) ? activeIndex(capacity, index) : -1;
    }
}
