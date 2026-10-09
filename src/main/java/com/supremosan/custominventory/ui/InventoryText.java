package com.supremosan.custominventory.ui;

import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Mod-owned UI text from Server/Languages/&lt;locale&gt;/server.lang ("custominventory.*" keys).
 * English is embedded so a missing locale or key never shows a raw key.
 */
public final class InventoryText {
    private static final String PREFIX = "server.custominventory.";
    private static final Map<String, String> ENGLISH = Map.ofEntries(
            Map.entry("status.locked", "Inventory access is currently locked."),
            Map.entry("status.pending", "The previous inventory move is still pending. Try again."),
            Map.entry("status.cannot_drop", "The selected item cannot be dropped."),
            Map.entry("status.invalid_drop", "Invalid inventory drop."),
            Map.entry("status.item_changed", "The selected item changed. Try again."),
            Map.entry("status.invalid_quantity", "Invalid item quantity."),
            Map.entry("status.inventory_changed", "The inventory changed. Try again."),
            Map.entry("status.cannot_change", "The inventory cannot be changed."),
            Map.entry("status.cannot_equip", "This item cannot be equipped in that slot."),
            Map.entry("status.drop_all_hover", "Hover an item to drop every stack of its type."),
            Map.entry("status.drop_all_failed", "Some stacks could not be dropped."),
            Map.entry("tooltip.drop_hover", "Hover an inventory item to select a stack to drop."),
            Map.entry("tooltip.drop_selected", "Drop the selected stack ({0})"),
            Map.entry("tooltip.drop_all", "Drop every stack of the hovered item"),
            Map.entry("action.place_all", "Place all"),
            Map.entry("action.distribute_one", "Place one per slot"),
            Map.entry("prompts.auto", "Prompts: Auto ({0})"),
            Map.entry("prompts.keyboard", "Prompts: Keyboard"),
            Map.entry("prompts.gamepad", "Prompts: Gamepad"),
            Map.entry("prompts.device.keyboard", "Keyboard"),
            Map.entry("prompts.device.gamepad", "Gamepad"),
            Map.entry("prompts.tooltip", "Switch between automatic, keyboard and controller prompts"),
            Map.entry("page.pocket_crafting", "Pocket crafting"),
            Map.entry("page.memories", "Collected memories"),
            Map.entry("page.backpack", "Backpack"),
            Map.entry("utility.unequip", "Unequip utility item"),
            Map.entry("utility.select", "Select utility item"),
            Map.entry("extraequipment.title", "Gear"),
            Map.entry("extraequipment.hide", "Hide extra equipment"),
            Map.entry("extraequipment.show", "Show extra equipment"));

    private InventoryText() { }

    public static String get(String language, String key, Object... args) {
        String template = null;
        var i18n = I18nModule.get();
        if (i18n != null) {
            try {
                String value = i18n.getMessage(language, PREFIX + key);
                if (value != null && !value.isBlank() && !value.equals(PREFIX + key)) template = value;
            } catch (RuntimeException ignored) { }
        }
        if (template == null) template = ENGLISH.getOrDefault(key, key);
        if (args == null || args.length == 0) return template;
        try {
            return MessageFormat.format(template, args);
        } catch (IllegalArgumentException invalid) {
            return template;
        }
    }
}
