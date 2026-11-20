package com.supremosan.custominventory.api;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

public interface InventoryContent {
    void build(InventoryContext context, UICommandBuilder commands,
               InventoryEventBindings bindings, String selector);

    void handleEvent(InventoryContext context, InventoryContentEvent event);

    default void refresh(InventoryContext context, UICommandBuilder commands,
                         InventoryEventBindings bindings, String selector) {
        commands.clear(selector);
        build(context, commands, bindings, selector);
    }

    default void onDismiss(InventoryContext context) {}
}
