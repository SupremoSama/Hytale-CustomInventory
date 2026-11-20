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
import com.hypixel.hytale.protocol.packets.inventory.SmartMoveItemStack;
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

    public interface Actions<K> {
        boolean enqueue(K connection, Runnable task);

        default boolean enqueue(K connection, Runnable task, Runnable discarded) {
            return enqueue(connection, task);
        }

        boolean isInventoryOpen(K connection);

        void openInventory(K connection);

        void closeInventory(K connection);

        void closeWindowZero(K connection);

        void relinquishInventory(K connection);

        void handleCustomPageInput(K connection, CustomPageEvent event);

        void dropHoveredInventoryItem(K connection, DropItemStack packet);
        default void dropHoveredInventoryItem(K connection) {
            dropHoveredInventoryItem(connection, null);
        }

        void smartMoveInventoryItem(K connection, SmartMoveItemStack packet);
    }

    private final Actions<K> actions;
    private final String inventoryPageKey;
    private final ConcurrentMap<IdentityKey<K>, ConnectionState> connections = new ConcurrentHashMap<>();

    private final ConcurrentMap<IdentityKey<K>, GameMode> gameModes = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    public InventoryPacketRouter(Actions<K> actions) {
        this(actions, INVENTORY_PAGE_KEY);
    }

    public InventoryPacketRouter(Actions<K> actions, String inventoryPageKey) {
        this.actions = Objects.requireNonNull(actions);
        this.inventoryPageKey = Objects.requireNonNull(inventoryPageKey);
    }

    public boolean route(K connection, Packet packet) {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(packet);
        if (!active) return false;

        var key = new IdentityKey<>(connection);
        if (packet instanceof DropItemStack dropPacket) {
            var state = connections.get(key);
            if (state != null && state.inventoryPageVisible && gameModes.get(key) == GameMode.Adventure) {
                return enqueue(connection, key, state, () -> actions.dropHoveredInventoryItem(connection, dropPacket));
            }
        }
        if (packet instanceof SmartMoveItemStack smartPacket) {
            var state = connections.get(key);
            if (state != null && state.inventoryPageVisible && gameModes.get(key) == GameMode.Adventure) {
                return enqueue(connection, key, state, () -> actions.smartMoveInventoryItem(connection, smartPacket));
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

                if (!active) {
                    connections.remove(key, state);
                    return false;
                }
                return enqueueRedirect(connection, key, state);
            }

            var state = connections.get(key);
            if (state != null) {
                state.epoch.incrementAndGet();
                enqueue(connection, key, state, () -> actions.relinquishInventory(connection));
            }
            return false;
        }

        if (packet instanceof CloseWindow close && close.id == 0 && gameModes.get(key) == GameMode.Adventure) {
            var state = connections.get(key);
            if (state != null && state.redirectAccepted) {
                return enqueue(connection, key, state, () -> actions.closeWindowZero(connection));
            }
        }

        return false;
    }

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
