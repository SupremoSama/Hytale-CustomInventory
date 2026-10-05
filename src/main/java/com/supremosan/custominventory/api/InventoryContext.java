package com.supremosan.custominventory.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Current player context. Content callbacks execute on this player's world thread. */
public record InventoryContext(Ref<EntityStore> ref, Store<EntityStore> store,
                               PlayerRef playerRef, Runnable refreshRequest, String viewId,
                               Runnable closeRequest, BooleanSupplier active,
                               Consumer<UICommandBuilder> presentationUpdate) {
    public InventoryContext {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(playerRef, "playerRef");
        Objects.requireNonNull(refreshRequest, "refreshRequest");
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(closeRequest, "closeRequest");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(presentationUpdate, "presentationUpdate");
    }

    public InventoryContext(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef, Runnable refreshRequest) {
        this(ref, store, playerRef, refreshRequest, "inventory:native", () -> {}, () -> false, commands -> {});
    }

    public InventoryContext(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        this(ref, store, playerRef, () -> {});
    }

    /** Thread-safe request; the host queues and coalesces updates and ignores dismissed pages. */
    public void requestRefresh() {
        refreshRequest.run();
    }

    /** Queued close of this session only; never closes a subsequently opened page. */
    public void requestClose() { closeRequest.run(); }

    /** Check on the owning world thread before touching game state in delayed callbacks. */
    public boolean isActive() { return active.getAsBoolean(); }

    /** Incremental update of owned contribution elements on the world thread; discarded after detachment. */
    public void update(UICommandBuilder commands) { presentationUpdate.accept(Objects.requireNonNull(commands)); }
}
