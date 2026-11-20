package com.supremosan.custominventory.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.render.PlayerModelRenderer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public final class PlayerModel {
    private PlayerModel() { }

    public static void putAttachment(UUID player, String key, ModelAttachment attachment) {
        PlayerModelRenderer.put(Objects.requireNonNull(player, "player"), key(key), attachment);
    }

    public static void removeAttachment(UUID player, String key) {
        PlayerModelRenderer.remove(Objects.requireNonNull(player, "player"), key(key));
    }

    public static ModelAttachment getAttachment(UUID player, String key) {
        return PlayerModelRenderer.get(Objects.requireNonNull(player, "player"), key(key));
    }

    public static boolean hasAttachment(UUID player, String key) { return getAttachment(player, key) != null; }

    public static void requestRebuild(UUID player) {
        PlayerModelRenderer.requestRebuild(Objects.requireNonNull(player, "player"), PlayerModelRenderer.Mode.ATTACHMENTS);
    }

    public static void requestRebuild(Ref<EntityStore> ref, Store<EntityStore> store) {
        PlayerModelRenderer.requestRebuild(ref, store, PlayerModelRenderer.Mode.ATTACHMENTS);
    }

    public static InventoryRegistry.Registration registerAnimationSet(String id, ModelAsset.AnimationSet animations) {
        Objects.requireNonNull(id, "id");
        PlayerModelRenderer.registerAnimationSet(id, animations);
        var closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) PlayerModelRenderer.unregisterAnimationSet(id, animations); };
    }

    public static InventoryRegistry.Registration addRebuildListener(BiConsumer<Ref<EntityStore>, Store<EntityStore>> listener) {
        PlayerModelRenderer.addRebuildListener(listener);
        var closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) PlayerModelRenderer.removeRebuildListener(listener); };
    }

    public static boolean isRebuilding() { return PlayerModelRenderer.isRebuilding(); }

    private static String key(String key) {
        InventoryRegistry.validateId(key);
        if (key.startsWith("custominventory:")) throw new IllegalArgumentException("Reserved attachment key: " + key);
        return key;
    }
}
