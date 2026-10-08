package com.supremosan.custominventory.packet;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType;
import com.hypixel.hytale.protocol.packets.inventory.DropItemStack;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.inventory.InventoryOperations;
import com.supremosan.custominventory.ui.InventoryShellPage;

import java.util.Objects;

/** Public packet-adapter bridge. ECS components and windows/pages are accessed on the world thread. */
public final class InventoryPacketBridge implements AutoCloseable {
    @FunctionalInterface
    public interface InventoryOpener {
        /** Called on the validated player's owning world thread. */
        void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef);
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final InventoryOpener opener;
    private final InventoryPacketRouter<PlayerRef> router;
    private PacketFilter inboundRegistration;
    private PacketFilter outboundRegistration;
    private volatile boolean registered;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger gestureTraceBudget =
            new java.util.concurrent.atomic.AtomicInteger(Boolean.getBoolean("custominventory.traceGestures") ? 250 : 0);

    public InventoryPacketBridge(InventoryOpener opener) {
        this.opener = Objects.requireNonNull(opener);
        router = new InventoryPacketRouter<>(new InventoryPacketRouter.Actions<>() {
            @Override
            public boolean enqueue(PlayerRef playerRef, Runnable task) {
                return enqueueOnWorld(playerRef, task, () -> { });
            }

            @Override
            public boolean enqueue(PlayerRef playerRef, Runnable task, Runnable discarded) {
                return enqueueOnWorld(playerRef, task, discarded);
            }

            @Override
            public boolean isInventoryOpen(PlayerRef playerRef) {
                return ownedPage(currentContext(playerRef)) != null;
            }

            @Override
            public void openInventory(PlayerRef playerRef) {
                redirect(currentContext(playerRef));
            }

            @Override
            public void closeInventory(PlayerRef playerRef) {
                var context = currentContext(playerRef);
                var page = ownedPage(context);
                if (page != null && (!page.isHostedView() || context.player().getGameMode() == GameMode.Adventure)) {
                    context.player().getPageManager().setPage(context.ref(), context.store(), Page.None);
                }
            }

            @Override
            public void closeWindowZero(PlayerRef playerRef) {
                var context = currentContext(playerRef);
                if (context == null || context.player().getGameMode() != GameMode.Adventure) return;
                var windows = context.player().getWindowManager();
                if (windows.getWindow(0) != null) windows.closeWindow(context.ref(), 0, context.store());
            }

            @Override
            public void relinquishInventory(PlayerRef playerRef) {
                var context = currentContext(playerRef);
                if (ownedPage(context) != null) {
                    // The client is already transitioning to its requested native UI. A SetPage(None)
                    // here would race that transition; resetPages only detaches server-side ownership.
                    context.player().getPageManager().resetPages(playerRef);
                }
            }

            @Override
            public void dropHoveredInventoryItem(PlayerRef playerRef, DropItemStack dropPacket) {
                var context = currentContext(playerRef);
                var page = ownedPage(context);
                if (page != null) page.dropHoveredItem(context.ref(), context.store(), dropPacket);
            }

            @Override
            public void handleCustomPageInput(PlayerRef playerRef, CustomPageEvent event) {
                var context = currentContext(playerRef);
                var page = ownedPage(context);
                if (context == null) return;
                if (event.type == CustomPageEventType.Data && page != null && router.canDispatchInventoryInput(playerRef)
                        && page.tryHandleInventoryInput(context.ref(), context.store(), event.data)) return;
                context.player().getPageManager().handleLegacyCustomPageEvent(context.ref(), context.store(), event);
            }
        }, InventoryShellPage.class.getName());
    }

    /** Register once during plugin start. Calling this again while registered is harmless. */
    public synchronized void register() {
        if (closed) throw new IllegalStateException("Inventory packet bridge is closed");
        if (registered) return;
        registered = true;
        outboundRegistration = PacketAdapters.registerOutbound((PlayerPacketWatcher) router::observeServerPacket);
        inboundRegistration = PacketAdapters.registerInbound((PlayerPacketFilter) (playerRef, packet) -> {
            if (packet instanceof CustomPageEvent event && event.type == CustomPageEventType.Data
                    && event.data != null && event.data.contains("ContentAction")
                    && gestureTraceBudget.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                LOGGER.atInfo().log("Inventory gesture input (dispatch=%s): %s",
                        router.canDispatchInventoryInput(playerRef), event.data);
            }
            return registered && !closed && router.route(playerRef, packet);
        });
    }

    /** Stop accepting packets and invalidate all queued work; this bridge cannot be restarted. */
    public synchronized void unregister() {
        if (closed) return;
        registered = false;
        closed = true;
        router.close();
        try {
            if (inboundRegistration != null) {
                PacketAdapters.deregisterInbound(inboundRegistration);
                inboundRegistration = null;
            }
        } finally {
            if (outboundRegistration != null) {
                PacketAdapters.deregisterOutbound(outboundRegistration);
                outboundRegistration = null;
            }
        }
    }

    public void disconnect(PlayerRef playerRef) { router.disconnect(playerRef); }

    /** Seed the snapshot from PlayerReadyEvent or an already connected player's owning world. */
    public void synchronizeGameMode(PlayerRef playerRef) {
        enqueueOnWorld(playerRef, () -> {
            var context = currentContext(playerRef);
            if (context != null) router.observeGameMode(playerRef, context.player().getGameMode());
        }, () -> { });
    }

    /** Page/listener disposal remains the plugin's RemovedPlayerFromWorldEvent responsibility. */
    public void worldRemoval(PlayerRef playerRef) { router.worldRemoval(playerRef); }

    public int trackedConnections() { return router.trackedConnections(); }

    @Override
    public void close() { unregister(); }

    private boolean enqueueOnWorld(PlayerRef playerRef, Runnable task, Runnable discarded) {
        if (!registered || closed) return false;
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return false;
        var store = ref.getStore();
        var world = store.getExternalData().getWorld();
        try {
            world.execute(() -> {
                if (!registered || closed || playerRef.getReference() != ref || !ref.isValid()
                        || ref.getStore() != store) {
                    discarded.run();
                    return;
                }
                task.run();
            });
            return true;
        } catch (RuntimeException worldStopped) {
            // Let the original native handler perform its usual invalid-reference/shutdown checks.
            return false;
        }
    }

    /** Only call from an already validated owning-world task. */
    private static Context currentContext(PlayerRef playerRef) {
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return null;
        var store = ref.getStore();
        // A cross-world migration may publish a new reference after the queued task's first guard.
        // Never follow that reference into another world's ECS from the previous world thread.
        if (!store.getExternalData().getWorld().isInThread()) return null;
        var player = store.getComponent(ref, Player.getComponentType());
        return player == null ? null : new Context(ref, store, playerRef, player);
    }

    private static InventoryShellPage ownedPage(Context context) {
        if (context == null) return null;
        var current = context.player().getPageManager().getCustomPage();
        return current instanceof InventoryShellPage shell && shell.belongsTo(context.playerRef())
                && shell.canRefocus(context.ref(), context.store()) ? shell : null;
    }

    private void redirect(Context context) {
        if (context == null || context.player().getGameMode() != GameMode.Adventure
                || InventoryOperations.locked(context.ref(), context.store())) return;
        try {
            var windows = context.player().getWindowManager();
            // This is the backend teardown the native PocketCrafting open would perform on window 0.
            if (windows.getWindow(0) != null) windows.closeWindow(context.ref(), 0, context.store());
            opener.open(context.ref(), context.store(), context.playerRef());
        } catch (RuntimeException failure) {
            LOGGER.atWarning().withCause(failure).log("Unable to redirect PocketCrafting to CustomInventory for %s",
                    context.playerRef().getUuid());
            // The native request was cancelled: close its local page on failure using PageManager so
            // any existing custom-page acknowledgements remain balanced.
            context.player().getPageManager().setPage(context.ref(), context.store(), Page.None);
        }
    }

    private record Context(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef, Player player) { }
}
