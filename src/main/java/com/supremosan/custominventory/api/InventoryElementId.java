package com.supremosan.custominventory.api;

/** Supported shell elements. Selectors are resolved by the host, never by hierarchy positions. */
public enum InventoryElementId {
    SHELL("#InventoryShell"), PLAYER_PANEL("#PlayerPanelHost"), INVENTORY_PANEL("#InventoryPanelHost"),
    STORAGE_GRID("#StorageGrid"), HOTBAR_GRID("#HotbarGrid"), ARMOR_GRID("#ArmorGrid"), UTILITY_GRID("#UtilityGrid"),
    SORT_BUTTON("#InventorySortButton"), TITLE("#PageTitle"), CONTENT_PANEL("#ContentPanel"),
    CONTENT_HOST("#ContentHost", true), HEADER_ACTIONS("#PageHeaderActions", true),
    NAVIGATION("#Navigation", true), EXTENSION_BUTTONS("#ExtensionButtons", true), AUXILIARY_HOST("#AuxiliaryHost", true),
    CLOSE_BUTTON("#CloseButton"), BACK_BUTTON("#InventoryBackButton"),
    INVENTORY_MENU("#NavigationInventoryButton"), MAP_MENU("#NavigationMapButton"), CREATIVE_MENU("#NavigationCreativeButton");

    private final String selector;
    private final boolean extensionHost;
    InventoryElementId(String selector) { this(selector, false); }
    InventoryElementId(String selector, boolean extensionHost) { this.selector = selector; this.extensionHost = extensionHost; }
    String selector() { return selector; }
    boolean extensionHost() { return extensionHost; }
}
