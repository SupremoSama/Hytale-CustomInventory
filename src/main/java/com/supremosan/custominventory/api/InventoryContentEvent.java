package com.supremosan.custominventory.api;

/** Scoped content event, including optional native ItemGrid drag/drop metadata. */
public record InventoryContentEvent(String action, String payload, Integer slotIndex, InventoryDragData drag,
                                    String mouseButton) {
    public InventoryContentEvent(String action, String payload, Integer slotIndex, InventoryDragData drag) {
        this(action, payload, slotIndex, drag, null);
    }
    /** Retains the simple event constructor used by existing inventory extensions. */
    public InventoryContentEvent(String action, String payload, Integer slotIndex) {
        this(action, payload, slotIndex, null);
    }

    public boolean rightMouseButton() {
        return "Right".equalsIgnoreCase(mouseButton) || "2".equals(mouseButton);
    }
}
