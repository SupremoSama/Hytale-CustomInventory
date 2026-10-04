package com.supremosan.custominventory.api;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** One player's tab session. Factories must return a fresh instance for every session. */
public interface InventoryContent {
    /** Append only beneath selector; bind events through bindings to preserve routing scope. */
    void build(InventoryContext context, UICommandBuilder commands,
               InventoryEventBindings bindings, String selector);

    void handleEvent(InventoryContext context, InventoryContentEvent event);

    /** Called on navigation, close, or plugin shutdown; release listeners and timers here. */
    default void onDismiss(InventoryContext context) {}
}
