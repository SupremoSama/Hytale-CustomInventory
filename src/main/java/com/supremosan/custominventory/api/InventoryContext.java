package com.supremosan.custominventory.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

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

    public void requestRefresh() {
        refreshRequest.run();
    }

    public void requestClose() { closeRequest.run(); }

    public boolean isActive() { return active.getAsBoolean(); }

    public void update(UICommandBuilder commands) { presentationUpdate.accept(Objects.requireNonNull(commands)); }
}
