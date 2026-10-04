package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.container.SortType;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.*;

/** Native backpack storage and native bulk actions. Backpack upgrade/appearance rules remain extensions. */
public final class BackpackInventoryContent implements InventoryContent {
    @Override public void build(InventoryContext context, UICommandBuilder commands, InventoryEventBindings bindings, String selector) {
        commands.append(selector, "Inventory/Backpack.ui");
    }

    @Override public void handleEvent(InventoryContext context, InventoryContentEvent event) {
        if (InventoryOperations.locked(context.ref(), context.store())) return;
        var backpack = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.BACKPACK);
        if (backpack == null || backpack.getCapacity() == 0) return;
        switch (event.action() == null ? "" : event.action()) {
            case "TakeAll" -> {
                var settings = context.store().getComponent(context.ref(), PlayerSettings.getComponentType());
                InventoryUtils.takeAll(context.ref(), InventoryComponent.BACKPACK_SECTION_ID,
                        settings == null ? PlayerSettings.defaults() : settings, context.store());
            }
            case "PutAll" -> InventoryUtils.putAll(context.ref(), InventoryComponent.BACKPACK_SECTION_ID, context.store());
            case "QuickStack" -> InventoryUtils.quickStack(context.ref(), InventoryComponent.BACKPACK_SECTION_ID, context.store());
            case "SortBackpack" -> backpack.sortItems(SortType.TYPE);
            default -> { return; }
        }
        context.requestRefresh();
    }
}
