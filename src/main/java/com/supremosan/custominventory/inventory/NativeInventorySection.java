package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.inventory.InventoryComponent;

/** The explicit native sections supported by the first inventory prototype. */
public enum NativeInventorySection {
    STORAGE(InventoryComponent.STORAGE_SECTION_ID, "StorageGrid"),
    HOTBAR(InventoryComponent.HOTBAR_SECTION_ID, "HotbarGrid"),
    ARMOR(InventoryComponent.ARMOR_SECTION_ID, "ArmorGrid"),
    UTILITY(InventoryComponent.UTILITY_SECTION_ID, "UtilityGrid"),
    BACKPACK(InventoryComponent.BACKPACK_SECTION_ID, "BackpackGrid"),
    EXTRA(com.supremosan.custominventory.api.ExtraEquipment.SECTION_ID, "ExtraEquipmentGrid");

    private final int id;
    private final String gridId;

    NativeInventorySection(int id, String gridId) {
        this.id = id;
        this.gridId = gridId;
    }

    public int id() { return id; }
    public String gridId() { return gridId; }

    public static NativeInventorySection parse(String name) {
        if (name == null) return null;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static NativeInventorySection fromId(int id) {
        for (var section : values()) if (section.id == id) return section;
        return null;
    }
}
