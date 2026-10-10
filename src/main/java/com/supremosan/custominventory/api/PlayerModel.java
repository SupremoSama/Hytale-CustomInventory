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

/**
 * Shared player-model attachments. CustomInventory is the only writer of the player's model, so
 * mods must contribute here instead of replacing {@code ModelComponent} themselves.
 * <p>
 * Equipment in a Gear slot needs no calls here: return its attachment from
 * {@link EquipmentManager.Listener#attachment} and the slot's eye is applied automatically. Use
 * these methods for anything else, such as a quiver shown while arrows are carried.
 * Keys are namespaced ({@code mod:id}); {@code custominventory:} is reserved. Thread-safe.
 */
public final class PlayerModel {
    private PlayerModel() { }

    /** Adds or replaces this mod's attachment and re-renders the player. */
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

    /** Re-render after state an attachment depends on changed outside CustomInventory (e.g. an item's paint). */
    public static void requestRebuild(UUID player) {
        PlayerModelRenderer.requestRebuild(Objects.requireNonNull(player, "player"), PlayerModelRenderer.Mode.ATTACHMENTS);
    }

    /** Call on the player's world thread. */
    public static void requestRebuild(Ref<EntityStore> ref, Store<EntityStore> store) {
        PlayerModelRenderer.requestRebuild(ref, store, PlayerModelRenderer.Mode.ATTACHMENTS);
    }

    /** Animations merged into every rendered player model, e.g. for an equipment item's moving parts. */
    public static InventoryRegistry.Registration registerAnimationSet(String id, ModelAsset.AnimationSet animations) {
        Objects.requireNonNull(id, "id");
        PlayerModelRenderer.registerAnimationSet(id, animations);
        var closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) PlayerModelRenderer.unregisterAnimationSet(id, animations); };
    }

    /**
     * Runs on the world thread after each rebuild of a player's model, e.g. to restart an animation
     * that a model replacement interrupted.
     */
    public static InventoryRegistry.Registration addRebuildListener(BiConsumer<Ref<EntityStore>, Store<EntityStore>> listener) {
        PlayerModelRenderer.addRebuildListener(listener);
        var closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) PlayerModelRenderer.removeRebuildListener(listener); };
    }

    /** True while CustomInventory is replacing a model on this thread; changes seen then are its own. */
    public static boolean isRebuilding() { return PlayerModelRenderer.isRebuilding(); }

    private static String key(String key) {
        InventoryRegistry.validateId(key);
        if (key.startsWith("custominventory:")) throw new IllegalArgumentException("Reserved attachment key: " + key);
        return key;
    }
}
