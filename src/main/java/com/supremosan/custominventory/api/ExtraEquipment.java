package com.supremosan.custominventory.api;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventorySystems;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Saved accessory inventory. Plugins register item eligibility for each equipment slot.
 * Equip/unequip notifications and per-slot visibility are managed through {@link EquipmentManager}.
 */
public final class ExtraEquipment extends InventoryComponent {
    public static final short HAT = 0, BACKPACK = 1, COLLAR = 2, BELT = 3;
    public static final short SLOT_COUNT = 4;
    public static final int SECTION_ID = -1060;
    public static ComponentType<EntityStore, ExtraEquipment> TYPE;
    private static final ConcurrentHashMap<Short, Predicate<ItemStack>> ACCEPTS = new ConcurrentHashMap<>();
    public static final BuilderCodec<ExtraEquipment> CODEC = BuilderCodec
            .builder(ExtraEquipment.class, ExtraEquipment::new, InventoryComponent.CODEC)
            .append(new com.hypixel.hytale.codec.KeyedCodec<>("LegacyTrueBackpackMigrated", com.hypixel.hytale.codec.Codec.BOOLEAN),
                    (o, value) -> o.legacyTrueBackpackMigrated = value, o -> o.legacyTrueBackpackMigrated).add()
            // Bit per slot; absent in older saves, so every slot starts visible.
            .append(new com.hypixel.hytale.codec.KeyedCodec<>("HiddenSlots", com.hypixel.hytale.codec.Codec.INTEGER),
                    (o, value) -> o.hiddenSlots = value == null ? 0 : value, o -> o.hiddenSlots).add()
            .afterDecode(ExtraEquipment::configure).build();
    private boolean legacyTrueBackpackMigrated;
    private int hiddenSlots;
    /** Last item announced to equipment listeners per slot. Runtime only; empty after loading. */
    private final ItemStack[] announced = new ItemStack[SLOT_COUNT];
    public boolean isLegacyTrueBackpackMigrated() { return legacyTrueBackpackMigrated; }
    public void markLegacyTrueBackpackMigrated() { legacyTrueBackpackMigrated = true; markChanged(); }

    public ExtraEquipment() { super(SLOT_COUNT); configure(); }
    public static ComponentType<EntityStore, ExtraEquipment> getComponentType() { return TYPE; }
    public static void registerItems(short slot, Predicate<ItemStack> accepts) {
        if (!validSlot(slot)) throw new IllegalArgumentException("Invalid equipment slot");
        ACCEPTS.put(slot, java.util.Objects.requireNonNull(accepts));
    }
    public static boolean accepts(short slot, ItemStack item) {
        return ItemStack.isEmpty(item) || item.getQuantity() == 1
                && ACCEPTS.getOrDefault(slot, ignored -> slot == COLLAR || slot == BELT).test(item);
    }
    public static boolean validSlot(short slot) { return slot >= 0 && slot < SLOT_COUNT; }

    /** Saved player preference for the slot; independent of whether an item is equipped. */
    public boolean isSlotVisible(short slot) { return !validSlot(slot) || (hiddenSlots & (1 << slot)) == 0; }

    /** Use {@link EquipmentManager#setVisible} so listeners can apply the change. */
    boolean setSlotVisible(short slot, boolean visible) {
        int next = visible ? hiddenSlots & ~(1 << slot) : hiddenSlots | (1 << slot);
        if (next == hiddenSlots) return false;
        hiddenSlots = next;
        markChanged();
        return true;
    }

    ItemStack announced(short slot) { return announced[slot]; }
    void announce(short slot, ItemStack item) { announced[slot] = ItemStack.isEmpty(item) ? null : item; }

    private void configure() {
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            inventory.setSlotFilter(FilterActionType.ADD, slot, (action, container, index, item) -> accepts(index, item));
        }
    }
    public static ExtraEquipment ensure(Ref<EntityStore> ref, Store<EntityStore> store) {
        var equipment = store.getComponent(ref, TYPE);
        if (equipment == null) { equipment = new ExtraEquipment(); store.putComponent(ref, TYPE, equipment); }
        return equipment;
    }
    @Override public ExtraEquipment clone() {
        var copy = new ExtraEquipment();
        copy.unregisterChangeEvent();
        copy.inventory = new SimpleItemContainer((SimpleItemContainer) inventory);
        copy.configure();
        copy.legacyTrueBackpackMigrated = legacyTrueBackpackMigrated;
        copy.hiddenSlots = hiddenSlots;
        System.arraycopy(announced, 0, copy.announced, 0, SLOT_COUNT);
        copy.registerChangeEvent();
        return copy;
    }
    public static final class Changes extends InventorySystems.InventoryChangeEventSystem<ExtraEquipment> {
        public Changes() { super(TYPE); }
    }
}
