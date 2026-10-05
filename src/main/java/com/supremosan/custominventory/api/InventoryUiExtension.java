package com.supremosan.custominventory.api;

/** A fresh extension per inventory session; all hooks execute on the player's world thread. */
public interface InventoryUiExtension {
    /** Called after the supported shell elements exist, before the UI is sent to the client. */
    default void onCreated(InventoryContext context, InventoryUiEditor editor) {}
    /** Mount notification after creation; this does not mean the client has acknowledged rendering. */
    default void onOpened(InventoryContext context) {}
    /** Reapply presentation after the framework/content updates. */
    default void onUpdate(InventoryContext context, InventoryUiEditor editor) {}
    default void handleEvent(InventoryContext context, InventoryContentEvent event) {}
    /** Also invoked on unregister, world removal, failed build and shutdown. */
    default void onClosed(InventoryContext context) {}
}
