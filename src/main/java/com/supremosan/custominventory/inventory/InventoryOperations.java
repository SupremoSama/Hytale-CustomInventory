package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.inventory.DropItemStack;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.game.InventoryPacketHandler;
import com.hypixel.hytale.server.core.modules.entity.component.PreventInventoryAccess;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Validates UI requests and delegates all actual movement to the engine. Call on the owning world thread. */
public final class InventoryOperations {
    public enum Result {
        SUBMITTED, LOCKED, INVALID_SOURCE, INVALID_TARGET, INVALID_QUANTITY, STALE_SELECTION, SAME_SLOT,
        DENIED, DROP_FAILED
    }

    private InventoryOperations() {}

    public static ItemContainer resolveContainer(Ref<EntityStore> ref, Store<EntityStore> store,
                                                NativeInventorySection section) {
        if (ref == null || !ref.isValid() || store == null || section == null) return null;
        return InventoryUtils.getSectionById(ref, section.id(), store);
    }

    public static boolean locked(Ref<EntityStore> ref, Store<EntityStore> store) {
        return ref == null || !ref.isValid() || store == null
                || store.getArchetype(ref).contains(PreventInventoryAccess.getComponentType());
    }

    public static boolean validSlot(ItemContainer container, int slot) {
        return container != null && slot >= 0 && slot < container.getCapacity();
    }

    /** Validates requests without changing either container. */
    public static Result validate(InventorySelection selection, ItemContainer currentSource,
                                  ItemContainer target, int targetSlot, int quantity, boolean locked) {
        if (locked) return Result.LOCKED;
        if (selection == null || !validSlot(currentSource, selection.slot())) return Result.INVALID_SOURCE;
        if (currentSource != selection.container()
                || !selection.matches(currentSource.getItemStack((short) selection.slot()))) {
            return Result.STALE_SELECTION;
        }
        if (!validSlot(target, targetSlot)) return Result.INVALID_TARGET;
        if (quantity <= 0 || quantity > selection.quantity()) return Result.INVALID_QUANTITY;
        if (currentSource == target && selection.slot() == targetSlot) return Result.SAME_SLOT;
        return Result.SUBMITTED;
    }

    public static Result move(Ref<EntityStore> ref, Store<EntityStore> store,
                              InventorySelection selection, NativeInventorySection targetSection, int targetSlot) {
        return move(ref, store, selection, targetSection, targetSlot, selection == null ? 0 : selection.quantity());
    }

    public static Result move(Ref<EntityStore> ref, Store<EntityStore> store,
                              InventorySelection selection, NativeInventorySection targetSection, int targetSlot,
                              int quantity) {
        if (locked(ref, store)) return Result.LOCKED;
        if (targetSection == null) return Result.INVALID_TARGET;
        ItemContainer source = selection == null ? null : resolveContainer(ref, store, selection.section());
        ItemContainer target = resolveContainer(ref, store, targetSection);
        Result result = validate(selection, source, target, targetSlot, quantity, false);
        if (result != Result.SUBMITTED) return result;

        // Native filters, stack rules, ability consistency, active-hotbar interactions, save/change
        // events and synchronization remain the engine's responsibility. The engine may defer or
        // reject a move, so SUBMITTED means a validated request, not a confirmed transfer.
        InventoryUtils.moveItem(ref, selection.section().id(), selection.slot(), quantity,
                targetSection.id(), targetSlot, store);
        return Result.SUBMITTED;
    }

    /** Submits a displayed stack to the engine's ordinary player-drop handler. */
    public static Result drop(Ref<EntityStore> ref, Store<EntityStore> store,
                              InventorySelection selection, int quantity) {
        if (locked(ref, store)) return Result.LOCKED;
        ItemContainer source = selection == null ? null : resolveContainer(ref, store, selection.section());
        Result validation = validateSource(selection, source, quantity);
        if (validation != Result.SUBMITTED) return validation;

        var playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null || playerRef.getReference() != ref
                || !(playerRef.getPacketHandler() instanceof IPacketHandler packetHandler)) {
            return Result.INVALID_SOURCE;
        }
        // The public handler only captures this packet handler; no registration or network send is
        // needed. It queues the usual world task, which owns mode/lock checks, both drop events,
        // ability removal, container filters, item spawning and synchronization. A submitted
        // request is not a confirmed drop, and inventory change listeners report its result.
        new InventoryPacketHandler(packetHandler).handle(
                new DropItemStack(selection.section().id(), selection.slot(), quantity));
        return Result.SUBMITTED;
    }

    private static Result validateSource(InventorySelection selection, ItemContainer currentSource, int quantity) {
        if (selection == null || !validSlot(currentSource, selection.slot())) return Result.INVALID_SOURCE;
        if (currentSource != selection.container()
                || !selection.matches(currentSource.getItemStack((short) selection.slot()))) {
            return Result.STALE_SELECTION;
        }
        if (quantity <= 0 || quantity > selection.quantity()) return Result.INVALID_QUANTITY;
        return Result.SUBMITTED;
    }
}
