package com.supremosan.custominventory.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Objects;

/** Current player context. Content callbacks execute on this player's world thread. */
public record InventoryContext(Ref<EntityStore> ref, Store<EntityStore> store,
                               PlayerRef playerRef, Runnable refreshRequest) {
    public InventoryContext {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(playerRef, "playerRef");
        Objects.requireNonNull(refreshRequest, "refreshRequest");
    }

    public InventoryContext(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        this(ref, store, playerRef, () -> {});
    }

    /** Thread-safe request; the host queues and coalesces updates and ignores dismissed pages. */
    public void requestRefresh() {
        refreshRequest.run();
    }
}
