package com.supremosan.custominventory.api;

import java.util.Map;

/** Scoped content event, including optional native ItemGrid drag/drop metadata. */
public record InventoryContentEvent(String action, String payload, Integer slotIndex, InventoryDragData drag,
                                    String mouseButton, Map<String, String> values, Boolean shiftHeld) {
    public InventoryContentEvent {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
    public InventoryContentEvent(String action, String payload, Integer slotIndex, InventoryDragData drag,
                                 String mouseButton, Map<String, String> values) {
        this(action, payload, slotIndex, drag, mouseButton, values, null);
    }
    public InventoryContentEvent(String action, String payload, Integer slotIndex, InventoryDragData drag, String mouseButton) {
        this(action, payload, slotIndex, drag, mouseButton, Map.of());
    }
    public InventoryContentEvent(String action, String payload, Integer slotIndex, InventoryDragData drag) {
        this(action, payload, slotIndex, drag, null);
    }
    /** Retains the simple event constructor used by existing inventory extensions. */
    public InventoryContentEvent(String action, String payload, Integer slotIndex) {
        this(action, payload, slotIndex, null);
    }

    public boolean rightMouseButton() {
        if (mouseButton == null) return false;
        return "Right".equalsIgnoreCase(mouseButton)
                || "2".equals(mouseButton)
                || "RightButton".equalsIgnoreCase(mouseButton)
                || "RightMouseButton".equalsIgnoreCase(mouseButton);
    }
}
