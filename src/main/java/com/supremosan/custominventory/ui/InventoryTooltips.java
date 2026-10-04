package com.supremosan.custominventory.ui;

import com.hypixel.hytale.server.core.Message;

/** Native translations for controls that have a click action but no registered keyboard shortcut. */
public final class InventoryTooltips {
    private InventoryTooltips() { }

    public static Message sort() {
        return Message.translation("client.inventory.button.autoSort.unassignedTooltip");
    }

    public static Message containerAction(String action) {
        return switch (action) {
            case "TakeAll" -> Message.translation("client.inventory.chest.tooltip.takeAllUnassigned");
            case "PutAll" -> Message.translation("client.inventory.chest.tooltip.putAllUnassigned");
            case "QuickStack" -> Message.translation("client.inventory.chest.tooltip.quickStackUnassigned");
            case "SortBackpack" -> sort();
            default -> throw new IllegalArgumentException("Unknown container action: " + action);
        };
    }
}
