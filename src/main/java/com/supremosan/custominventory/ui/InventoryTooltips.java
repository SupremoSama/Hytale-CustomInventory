package com.supremosan.custominventory.ui;

import com.hypixel.hytale.server.core.Message;

/**
 * Tooltips for click-only controls. The client never reports its key bindings to the server, so these
 * name the action without the native "(key not assigned)" variants, which would be wrong for a player
 * who did bind a key.
 */
public final class InventoryTooltips {
    private InventoryTooltips() { }

    public static Message sort() {
        return Message.translation("server.custominventory.tooltip.sort");
    }

    public static Message containerAction(String action) {
        return switch (action) {
            case "TakeAll" -> Message.translation("server.custominventory.tooltip.take_all");
            case "PutAll" -> Message.translation("server.custominventory.tooltip.put_all");
            case "QuickStack" -> Message.translation("server.custominventory.tooltip.quick_stack");
            case "SortBackpack" -> sort();
            default -> throw new IllegalArgumentException("Unknown container action: " + action);
        };
    }
}
