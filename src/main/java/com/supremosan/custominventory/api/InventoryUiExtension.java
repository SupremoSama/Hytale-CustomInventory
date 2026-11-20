package com.supremosan.custominventory.api;

public interface InventoryUiExtension {
    default void onCreated(InventoryContext context, InventoryUiEditor editor) {}

    default void onOpened(InventoryContext context) {}

    default void onUpdate(InventoryContext context, InventoryUiEditor editor) {}
    default void handleEvent(InventoryContext context, InventoryContentEvent event) {}

    default void onClosed(InventoryContext context) {}
}
