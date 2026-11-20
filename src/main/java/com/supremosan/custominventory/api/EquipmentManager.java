package com.supremosan.custominventory.api;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.render.PlayerModelRenderer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EquipmentManager {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final ConcurrentHashMap<Short, CopyOnWriteArrayList<Listener>> LISTENERS = new ConcurrentHashMap<>();

    private EquipmentManager() { }

    public interface Listener {
        default void onEquipmentChanged(Change change) { }

        default ModelAttachment attachment(Ref<EntityStore> ref, Store<EntityStore> store, ItemStack equipped) { return null; }

        default void onVisibilityChanged(VisibilityChange change) { }

        default boolean canHide(ItemStack equipped) { return true; }
    }

    public record Change(Ref<EntityStore> ref, Store<EntityStore> store, CommandBuffer<EntityStore> commands,
                         short slot, ItemStack previous, ItemStack current) {
        public boolean equipped() { return !ItemStack.isEmpty(current); }
        public boolean unequipped() { return ItemStack.isEmpty(current) && !ItemStack.isEmpty(previous); }
    }

    public record VisibilityChange(Ref<EntityStore> ref, Store<EntityStore> store, short slot,
                                   boolean visible, ItemStack equipped) { }

    public static InventoryRegistry.Registration addListener(short slot, Listener listener) {
        requireSlot(slot);
        Objects.requireNonNull(listener, "listener");
        var listeners = LISTENERS.computeIfAbsent(slot, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        var closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) listeners.remove(listener); };
    }

    public static ItemStack getEquipped(Ref<EntityStore> ref, Store<EntityStore> store, short slot) {
        requireSlot(slot);
        var equipment = store.getComponent(ref, ExtraEquipment.TYPE);
        if (equipment == null) return null;
        var item = equipment.getInventory().getItemStack(slot);
        return ItemStack.isEmpty(item) ? null : item;
    }

    public static boolean isVisible(Ref<EntityStore> ref, Store<EntityStore> store, short slot) {
        requireSlot(slot);
        var equipment = store.getComponent(ref, ExtraEquipment.TYPE);
        return equipment == null || equipment.isSlotVisible(slot);
    }

    public static boolean canHide(Ref<EntityStore> ref, Store<EntityStore> store, short slot) {
        var equipped = getEquipped(ref, store, slot);
        if (equipped == null) return true;
        for (var listener : listeners(slot)) {
            try {
                if (!listener.canHide(equipped)) return false;
            } catch (RuntimeException failure) {
                LOGGER.atWarning().withCause(failure).log("Equipment visibility policy failed for slot %s", slot);
            }
        }
        return true;
    }

    public static ModelAttachment renderedAttachment(Ref<EntityStore> ref, Store<EntityStore> store, short slot) {
        var equipped = getEquipped(ref, store, slot);
        if (equipped == null || !isVisible(ref, store, slot) && canHide(ref, store, slot)) return null;
        for (var listener : listeners(slot)) {
            try {
                var attachment = listener.attachment(ref, store, equipped);
                if (attachment != null) return attachment;
            } catch (RuntimeException failure) {
                LOGGER.atWarning().withCause(failure).log("Equipment attachment failed for slot %s", slot);
            }
        }
        return null;
    }

    public static boolean setVisible(Ref<EntityStore> ref, Store<EntityStore> store, short slot, boolean visible) {
        requireSlot(slot);
        if (!ref.isValid()) return false;
        var equipment = ExtraEquipment.ensure(ref, store);
        if (!equipment.setSlotVisible(slot, visible)) return false;
        var change = new VisibilityChange(ref, store, slot, visible, getEquipped(ref, store, slot));
        for (var listener : listeners(slot)) {
            try {
                listener.onVisibilityChanged(change);
            } catch (RuntimeException failure) {
                LOGGER.atWarning().withCause(failure).log("Equipment visibility listener failed for slot %s", slot);
            }
        }
        PlayerModelRenderer.requestRebuild(ref, store, PlayerModelRenderer.Mode.ATTACHMENTS);
        return true;
    }

    public static boolean toggleVisible(Ref<EntityStore> ref, Store<EntityStore> store, short slot) {
        boolean next = !isVisible(ref, store, slot);
        setVisible(ref, store, slot, next);
        return isVisible(ref, store, slot);
    }

    public static boolean equip(Ref<EntityStore> ref, Store<EntityStore> store, short slot,
                                ItemContainer source, short sourceSlot) {
        requireSlot(slot);
        Objects.requireNonNull(source, "source");
        if (sourceSlot < 0 || sourceSlot >= source.getCapacity()) return false;
        var item = source.getItemStack(sourceSlot);
        if (ItemStack.isEmpty(item) || !ExtraEquipment.accepts(slot, item)) return false;
        var target = ExtraEquipment.ensure(ref, store).getInventory();
        return source.moveItemStackFromSlotToSlot(sourceSlot, item.getQuantity(), target, slot).succeeded();
    }

    public static boolean unequip(Ref<EntityStore> ref, Store<EntityStore> store, short slot, ItemContainer target) {
        requireSlot(slot);
        Objects.requireNonNull(target, "target");
        var equipment = store.getComponent(ref, ExtraEquipment.TYPE);
        if (equipment == null || ItemStack.isEmpty(equipment.getInventory().getItemStack(slot))) return false;
        return equipment.getInventory().moveItemStackFromSlot(slot, target).succeeded();
    }

    public static void announce(Ref<EntityStore> ref, Store<EntityStore> store) {
        var equipment = ExtraEquipment.ensure(ref, store);
        var inventory = equipment.getInventory();
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            var item = inventory.getItemStack(slot);
            if (ItemStack.isEmpty(item)) continue;
            equipment.announce(slot, null);
            inventory.setItemStackForSlot(slot, item);
        }
    }

    private static List<Listener> listeners(short slot) {
        var listeners = LISTENERS.get(slot);
        return listeners == null ? List.of() : listeners;
    }

    private static void requireSlot(short slot) {
        if (!ExtraEquipment.validSlot(slot)) throw new IllegalArgumentException("Invalid equipment slot: " + slot);
    }

    public static final class Dispatcher extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
        public Dispatcher() { super(InventoryChangeEvent.class); }

        @Override public Query<EntityStore> getQuery() { return ExtraEquipment.TYPE; }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commands, InventoryChangeEvent event) {
            if (event.getComponentType() != ExtraEquipment.TYPE) return;
            var equipment = chunk.getComponent(index, ExtraEquipment.TYPE);
            if (equipment == null) return;
            var ref = chunk.getReferenceTo(index);
            var inventory = equipment.getInventory();
            for (short slot = 0; slot < inventory.getCapacity() && slot < ExtraEquipment.SLOT_COUNT; slot++) {
                if (!event.getTransaction().wasSlotModified(slot)) continue;

                var current = inventory.getItemStack(slot);
                if (ItemStack.isEmpty(current)) current = null;
                var previous = equipment.announced(slot);
                if (Objects.equals(previous, current)) continue;
                equipment.announce(slot, current);
                var change = new Change(ref, store, commands, slot, previous, current);
                for (var listener : listeners(slot)) {
                    try {
                        listener.onEquipmentChanged(change);
                    } catch (RuntimeException failure) {
                        LOGGER.atWarning().withCause(failure).log("Equipment listener failed for slot %s", slot);
                    }
                }
                PlayerModelRenderer.requestRebuild(ref, store, PlayerModelRenderer.Mode.ATTACHMENTS);
            }
        }
    }
}
