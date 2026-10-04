package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.*;

import java.util.List;

/** The player's carried memories, matching MemoriesWindow rather than the world's deposited collection. */
public final class CollectedMemoriesContent implements InventoryContent {
    private int renderedCapacity;
    private List<Memory> renderedMemories = List.of();

    @Override public void build(InventoryContext context, UICommandBuilder commands, InventoryEventBindings bindings, String selector) {
        commands.append(selector, "Inventory/Memories/Memories.ui");
        var component = context.store().getComponent(context.ref(), PlayerMemories.getComponentType());
        renderedCapacity = component == null ? 0 : Math.max(0, component.getMemoriesCapacity());
        renderedMemories = component == null ? List.of() : List.copyOf(component.getRecordedMemories());
        String list = selector + " #IconList";
        for (int index = 0; index < Math.max(renderedCapacity, renderedMemories.size()); index++) {
            commands.append(list, "Inventory/Memories/Memory.ui");
            if (index >= renderedMemories.size()) continue;
            var memory = renderedMemories.get(index);
            String tile = list + "[" + index + "] #MemoryContent";
            commands.set(tile + " #TileDefaultBackground.Visible", true);
            commands.set(tile + " #TileEmptyBackground.Visible", false);
            commands.set(tile + ".TooltipTextSpans", memory.getTooltipText());
            if (memory.getIconPath() != null && !memory.getIconPath().isBlank()) {
                commands.set(tile + " #Icon.AssetPath", memory.getIconPath());
                commands.set(tile + " #Icon.Visible", true);
            }
            for (var category : MemoriesPlugin.get().getAllMemories().entrySet()) {
                if (!category.getValue().contains(memory)) continue;
                commands.set(tile + " #CategoryIcon.AssetPath", "UI/Custom/Pages/Memories/categories/" + category.getKey() + ".png");
                commands.set(tile + " #CategoryIconContainer.Visible", true);
                break;
            }
        }
    }

    public boolean needsRefresh(InventoryContext context) {
        var component = context.store().getComponent(context.ref(), PlayerMemories.getComponentType());
        return component == null ? renderedCapacity != 0 || !renderedMemories.isEmpty()
                : renderedCapacity != component.getMemoriesCapacity()
                || !renderedMemories.equals(List.copyOf(component.getRecordedMemories()));
    }

    @Override public void handleEvent(InventoryContext context, InventoryContentEvent event) { }
}
