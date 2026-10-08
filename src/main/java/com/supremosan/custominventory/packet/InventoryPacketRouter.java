package com.supremosan.custominventory.packet;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.protocol.packets.interface_.SetPage;
import com.hypixel.hytale.protocol.packets.player.SetGameMode;
import com.hypixel.hytale.protocol.packets.inventory.DropItemStack;
import com.hypixel.hytale.protocol.packets.window.ClientOpenWindow;
import com.hypixel.hytale.protocol.packets.window.CloseWindow;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.WindowType;

import java.util.Objects;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes native inventory requests without accessing game state from the packet thread.
 * Connection keys use object identity, so reconnecting with the same UUID cannot reuse queued work.
 * One orphan-window marker is retained per participating connection until disconnect or shutdown.
 */
public final class InventoryPacketRouter<K> implements AutoCloseable {
    public static final String INVENTORY_PAGE_KEY = "com.supremosan.custominventory.ui.InventoryShellPage";
    private static final Set<String> STABLE_INVENTORY_SLOT_SELECTORS = Set.of(
            "#InventoryShell #InventoryPanelHost #StorageGrid.Slots",
            "#InventoryShell #InventoryPanelHost #HotbarGrid.Slots",
            "#InventoryShell #PlayerPanelHost #ArmorGrid.Slots",
            "#InventoryShell #ExtraEquipmentHost #ExtraEquipmentGrid.Slots",
            "#InventoryShell #PlayerPanelHost #UtilityGrid.Slots",
            "#InventoryShell #PlayerPanelHost #UtilityWheelCenterGrid.Slots",
            "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid0.Slots",
            "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid1.Slots",
            "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid2.Slots",
            "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid3.Slots",
            "#InventoryShell #ContentHost #BackpackGrid.Slots");

    /** All callbacks except enqueue run inside the accepted queued task. */
    public interface Actions<K> {
        /** Queue on the owning world, validating the captured entity/store again before running. */
        boolean enqueue(K connection, Runnable task);

        /** Invoke discarded when accepted work cannot run because its captured context became invalid. */
        default boolean enqueue(K connection, Runnable task, Runnable discarded) {
            return enqueue(connection, task);
        }

        boolean isInventoryOpen(K connection);

        void openInventory(K connection);

        /** Dismiss only this plugin's current shell, balancing the page lifecycle. */
        void closeInventory(K connection);

        /** Close a real native window 0, or absorb an orphan; never dismiss the custom page here. */
        void closeWindowZero(K connection);

        /** Detach only this plugin's active page without sending a page change over the native UI. */
        void relinquishInventory(K connection);

        /** Inventory Data validates its mounted session; other events use the native PageManager. */
        void handleCustomPageInput(K connection, CustomPageEvent event);

        void dropHoveredInventoryItem(K connection, DropItemStack packet);
        default void dropHoveredInventoryItem(K connection) {
            dropHoveredInventoryItem(connection, null);
        }
    }

    private final Actions<K> actions;
    private final String inventoryPageKey;
    private final ConcurrentMap<IdentityKey<K>, ConnectionState> connections = new ConcurrentHashMap<>();
    // Published by authoritative server packets/world-thread snapshots, never by packet-thread ECS reads.
    private final ConcurrentMap<IdentityKey<K>, GameMode> gameModes = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    public InventoryPacketRouter(Actions<K> actions) {
        this(actions, INVENTORY_PAGE_KEY);
    }

    public InventoryPacketRouter(Actions<K> actions, String inventoryPageKey) {
        this.actions = Objects.requireNonNull(actions);
        this.inventoryPageKey = Objects.requireNonNull(inventoryPageKey);
    }

    /** @return true only when this router takes responsibility for the packet. */
    public boolean route(K connection, Packet packet) {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(packet);
        if (!active) return false;

        var key = new IdentityKey<>(connection);
        if (packet instanceof DropItemStack dropPacket) {
            var state = connections.get(key);
            if (state != null && state.inventoryPageVisible && gameModes.get(key) == GameMode.Adventure) {
                // Do not let a native G request accidentally drop the active hotbar
                // while the custom inventory displays a different hovered source.
                return enqueue(connection, key, state, () -> actions.dropHoveredInventoryItem(connection, dropPacket));
            }
        }
        if (packet instanceof CustomPageEvent event && event.type == CustomPageEventType.Acknowledge) {
            var state = connections.get(key);
            if (state != null) synchronized (state) { state.pageAcknowledgments.pollFirst(); }
            return false;
        }
        if (packet instanceof CustomPageEvent event && event.type == CustomPageEventType.Dismiss) {
            var state = connections.get(key);
            if (state != null) {
                synchronized (state) {
                    state.inventoryPageVisible = false;
                    state.epoch.incrementAndGet();
                }
                // Execute the native dismissal before changing the authoritative
                // legacy-page flag. An earlier world task may still send SetPage.
                return enqueue(connection, key, state, () -> {
                    actions.handleCustomPageInput(connection, event);
                    synchronized (state) { state.legacyPageActive = false; }
                });
            }
            return false;
        }
        if (packet instanceof CustomPageEvent event && event.type == CustomPageEventType.Data) {
            var state = connections.get(key);
            if (state != null && canDispatchInventoryInput(connection)) {
                return enqueue(connection, key, state, () -> actions.handleCustomPageInput(connection, event));
            }
        }
        if (packet instanceof ClientOpenWindow open) {
            if (open.type == WindowType.PocketCrafting && gameModes.get(key) == GameMode.Adventure) {
                var state = connections.computeIfAbsent(key, ignored -> new ConnectionState());
                // Shutdown can race the map insertion. Do not retain or cancel after it wins.
                if (!active) {
                    connections.remove(key, state);
                    return false;
                }
                return enqueueRedirect(connection, key, state);
            }

            var state = connections.get(key);
            if (state != null) {
                // A newer native UI request supersedes a still-queued inventory redirect.
                state.epoch.incrementAndGet();
                enqueue(connection, key, state, () -> actions.relinquishInventory(connection));
            }
            return false;
        }

        if (packet instanceof CloseWindow close && close.id == 0 && gameModes.get(key) == GameMode.Adventure) {
            var state = connections.get(key);
            if (state != null && state.redirectAccepted) {
                // The client can close its locally opened native window after CustomPage replaces it.
                // Its packet has no reason/generation, so it must not be used to dismiss our page.
                return enqueue(connection, key, state, () -> actions.closeWindowZero(connection));
            }
        }
        // Acknowledgements, dismissal and positive window IDs stay native-owned.
        return false;
    }

    /** Observe server UI transitions without swallowing packets or touching pages/windows. */
    public void observeServerPacket(K connection, Packet packet) {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(packet);
        if (!active) return;
        if (packet instanceof SetGameMode mode) {
            observeGameMode(connection, mode.gameMode);
            return;
        }
        var key = new IdentityKey<>(connection);
        if (packet instanceof CustomPage custom) {
            var state = connections.computeIfAbsent(key, ignored -> new ConnectionState());
            synchronized (state) {
                state.pageAcknowledgments.addLast(blocksInventoryInput(custom));
                state.legacyPageActive = true;
                if (custom.isInitial) {
                    state.epoch.incrementAndGet();
                    state.inventoryPageVisible = Objects.equals(custom.key, inventoryPageKey);
                }
            }
            if (!active) connections.remove(key, state);
            return;
        }
        boolean transition = packet instanceof OpenWindow open && open.id > 0 || packet instanceof SetPage;
        if (transition) {
            var state = connections.get(key);
            if (state != null) {
                synchronized (state) {
                    if (packet instanceof SetPage && state.legacyPageActive) {
                        state.pageAcknowledgments.addLast(true);
                        state.legacyPageActive = false;
                    }
                    state.inventoryPageVisible = false;
                    state.epoch.incrementAndGet();
                }
            }
        }
    }

    /** Rechecked on the world thread before bypassing presentation or stable core-slot acknowledgements. */
    public boolean canDispatchInventoryInput(K connection) {
        var state = connections.get(new IdentityKey<>(connection));
        if (state == null || !active) return false;
        synchronized (state) {
            return state.inventoryPageVisible && !state.pageAcknowledgments.contains(true);
        }
    }

    private static boolean isStableSlotSelector(String selector) {
        if (selector == null) return false;
        if (STABLE_INVENTORY_SLOT_SELECTORS.contains(selector)) return true;
        if (selector.contains("#PocketCrafting")) return true;
        if (selector.endsWith("#BackpackGrid.Slots")) return true;
        return false;
    }

    private static boolean blocksInventoryInput(CustomPage page) {
        if (page.isInitial || page.clear || page.eventBindings != null && page.eventBindings.length > 0) return true;
        if (page.commands == null) return false;
        for (var command : page.commands) {
            if (command.type != CustomUICommandType.Set || command.selector == null
                    || command.selector.endsWith(".InventorySectionId")
                    || command.selector.endsWith(".AreItemsDraggable")
                    || command.selector.endsWith(".Slots") && !isStableSlotSelector(command.selector)) return true;
        }
        return false;
    }

    /** Mode is known before interception; unknown and non-Adventure clients keep native handlers. */
    public void observeGameMode(K connection, GameMode gameMode) {
        Objects.requireNonNull(connection);
        if (!active) return;
        var key = new IdentityKey<>(connection);
        var previous = gameMode == null ? gameModes.remove(key) : gameModes.put(key, gameMode);
        if (!active) { gameModes.remove(key); return; }
        if (previous == gameMode) return;
        var state = connections.get(key);
        if (state == null) return;
        state.epoch.incrementAndGet();
        if (gameMode != GameMode.Adventure) {
            enqueue(connection, key, state, () -> actions.closeInventory(connection));
        }
    }

    private boolean enqueue(K connection, IdentityKey<K> key, ConnectionState state, Runnable task) {
        long epoch = state.epoch.get();
        return actions.enqueue(connection, () -> {
            if (active && connections.get(key) == state && state.epoch.get() == epoch) task.run();
        }, () -> { });
    }

    private boolean enqueueRedirect(K connection, IdentityKey<K> key, ConnectionState state) {
        // Queue submission is non-blocking. Serializing it per connection lets a repeated request
        // coalesce only with work whose submission is known to have succeeded.
        synchronized (state) {
            return submitRedirect(connection, key, state);
        }
    }

    private boolean submitRedirect(K connection, IdentityKey<K> key, ConnectionState state) {
        if (!active || gameModes.get(key) != GameMode.Adventure) {
            if (!state.redirectAccepted) connections.remove(key, state);
            return false;
        }
        long epoch = state.epoch.get();
        PendingRedirect request;
        while (true) {
            var pending = state.pendingRedirect.get();
            if (pending != null && pending.epoch == epoch) return active && gameModes.get(key) == GameMode.Adventure;
            request = new PendingRedirect(epoch);
            if (state.pendingRedirect.compareAndSet(pending, request)) break;
        }
        var queuedRequest = request;
        boolean queued;
        try {
            queued = actions.enqueue(connection, () -> {
                try {
                    if (!active || connections.get(key) != state || state.epoch.get() != epoch
                            || state.pendingRedirect.get() != queuedRequest
                            || gameModes.get(key) != GameMode.Adventure) return;
                    if (actions.isInventoryOpen(connection)) actions.closeInventory(connection);
                    else actions.openInventory(connection);
                } finally {
                    // An obsolete request must not clear a newer world's/native transition's request.
                    state.pendingRedirect.compareAndSet(queuedRequest, null);
                }
            }, () -> state.pendingRedirect.compareAndSet(queuedRequest, null));
        } catch (RuntimeException | Error failure) {
            rollbackRejectedRedirect(key, state, queuedRequest);
            throw failure;
        }
        if (queued) state.redirectAccepted = true;
        else rollbackRejectedRedirect(key, state, queuedRequest);
        return queued && active && gameModes.get(key) == GameMode.Adventure;
    }

    private void rollbackRejectedRedirect(IdentityKey<K> key, ConnectionState state, PendingRedirect request) {
        state.pendingRedirect.compareAndSet(request, null);
        if (!state.redirectAccepted) connections.remove(key, state);
    }

    public void disconnect(K connection) {
        var key = new IdentityKey<>(Objects.requireNonNull(connection));
        connections.remove(key);
        gameModes.remove(key);
    }

    /** Invalidate old-world work, retaining protection against late orphan window-0 closes. */
    public void worldRemoval(K connection) {
        var state = connections.get(new IdentityKey<>(Objects.requireNonNull(connection)));
        if (state != null) synchronized (state) {
            state.epoch.incrementAndGet();
            state.inventoryPageVisible = false;
            state.legacyPageActive = false;
            state.pageAcknowledgments.clear();
        }
    }

    public int trackedConnections() {
        return connections.size();
    }

    @Override
    public void close() {
        active = false;
        connections.clear();
        gameModes.clear();
    }

    private static final class ConnectionState {
        private final AtomicLong epoch = new AtomicLong();
        private final AtomicReference<PendingRedirect> pendingRedirect = new AtomicReference<>();
        private volatile boolean redirectAccepted;
        private volatile boolean inventoryPageVisible;
        // Packet order matches the native acknowledgement counter; true means
        // identities, dynamic slots, bindings or structure changed, so the normal gate remains.
        private final Deque<Boolean> pageAcknowledgments = new ArrayDeque<>();
        private boolean legacyPageActive;
    }

    private record PendingRedirect(long epoch) { }

    private static final class IdentityKey<K> {
        private final K value;

        private IdentityKey(K value) { this.value = value; }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey<?> key && value == key.value;
        }

        @Override
        public int hashCode() { return System.identityHashCode(value); }
    }
}
