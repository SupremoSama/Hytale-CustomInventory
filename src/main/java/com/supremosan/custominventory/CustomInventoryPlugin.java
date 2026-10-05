package com.supremosan.custominventory;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.api.InventoryPageDefinition;
import com.supremosan.custominventory.api.InventoryRegistry;
import com.supremosan.custominventory.api.InventoryUiExtension;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.supremosan.custominventory.command.CustomInventoryCommand;
import com.supremosan.custominventory.inventory.PocketCraftingContent;
import com.supremosan.custominventory.inventory.InventoryOperations;
import com.supremosan.custominventory.inventory.CollectedMemoriesContent;
import com.supremosan.custominventory.inventory.BackpackInventoryContent;
import com.supremosan.custominventory.packet.InventoryPacketBridge;
import com.supremosan.custominventory.ui.InventoryShellPage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CustomInventoryPlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static volatile CustomInventoryPlugin instance;
    private final InventoryRegistry registry = new InventoryRegistry();
    private final Set<InventoryShellPage> sessions = ConcurrentHashMap.newKeySet();
    private final InventoryPacketBridge packetBridge = new InventoryPacketBridge(this::open);
    private volatile boolean running;
    private InventoryRegistry.Registration registryListener;

    public CustomInventoryPlugin(JavaPluginInit init) { super(init); }

    public static CustomInventoryPlugin get() {
        var plugin = instance;
        if (plugin == null) throw new IllegalStateException("CustomInventory is not loaded");
        return plugin;
    }

    public InventoryRegistry getInventoryRegistry() { return registry; }

    /** Open a session-local view with shared inventory/input; attach native bench windows if supplied. */
    public boolean openView(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef,
                            InventoryPageDefinition view, InventoryUiExtension extension, Window... windows) {
        if (!running) throw new IllegalStateException("CustomInventory is not running");
        if (!store.getExternalData().getWorld().isInThread()) throw new IllegalStateException("Inventory views require the player's world thread");
        if (!ref.isValid() || ref.getStore() != store || playerRef.getReference() != ref
                || InventoryOperations.locked(ref, store)) return false;
        var player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return false;
        var page = new InventoryShellPage(playerRef, registry, sessions::remove, java.util.Objects.requireNonNull(view), extension);
        sessions.add(page);
        try {
            if (windows.length == 0) {
                player.getPageManager().openCustomPage(ref, store, page);
                return true;
            }
            if (player.getPageManager().openCustomPageWithWindows(ref, store, page, windows)) return true;
            page.onDismiss(ref, store);
            return false;
        } catch (RuntimeException | Error failure) {
            page.onDismiss(ref, store);
            throw failure;
        }
    }

    @Override
    protected void setup() {
        instance = this;
        running = true;
        registryListener = registry.onChange(() -> { for (var page : sessions) page.refreshRegistrations(); });
        registry.registerInventoryPage(new InventoryPageDefinition(InventoryShellPage.DEFAULT_PAGE,
                "Crafting", 0, context -> new PocketCraftingContent()));
        registry.registerInventoryPage(new InventoryPageDefinition(InventoryShellPage.MEMORIES_PAGE,
                "Collected memories", 10, context -> new CollectedMemoriesContent()));
        registry.registerInventoryPage(new InventoryPageDefinition(InventoryShellPage.BACKPACK_PAGE,
                "Backpack", 20, context -> new BackpackInventoryContent()));
        getCommandRegistry().registerCommand(new CustomInventoryCommand(this));
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            packetBridge.disconnect(event.getPlayerRef());
            for (var page : sessions) if (page.belongsTo(event.getPlayerRef())) page.closeForShutdown();
        });
        getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, event -> {
            var playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
            if (playerRef != null) {
                packetBridge.worldRemoval(playerRef);
                for (var page : sessions) if (page.belongsTo(playerRef)) page.closeForWorldRemoval(event.getWorld());
            }
        });
        LOGGER.atInfo().log("CustomInventory registered /custominventory and extensible native inventory content");
    }

    @Override
    protected void start() {
        packetBridge.register();
        // A plugin restart may occur after the players' initial SetGameMode packets were sent.
        for (var playerRef : Universe.get().getPlayers().toArray(new PlayerRef[0])) {
            packetBridge.synchronizeGameMode(playerRef);
        }
        LOGGER.atInfo().log("CustomInventory ready: %s pages, %s extension buttons", registry.pagesSnapshot().size(), registry.buttonsSnapshot().size());
        LOGGER.atInfo().log("Adventure PocketCrafting requests now toggle CustomInventory; other game modes and native window types keep their original handlers");
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        var ref = event.getPlayerRef();
        if (!running || !ref.isValid()) return;
        var store = ref.getStore();
        var world = store.getExternalData().getWorld();
        Runnable seed = () -> {
            if (!running || !ref.isValid() || ref.getStore() != store) return;
            var playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null && playerRef.getReference() == ref) packetBridge.synchronizeGameMode(playerRef);
        };
        if (world.isInThread()) seed.run();
        else {
            try { world.execute(seed); }
            catch (RuntimeException worldStopped) {
                // An unavailable world keeps the packet router on its native fail-open path.
            }
        }
    }

    /** Invoke on the player's world thread. */
    public void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef) {
        if (!running || !ref.isValid() || ref.getStore() != store || playerRef.getReference() != ref
                || InventoryOperations.locked(ref, store)) return;
        var player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getGameMode() != GameMode.Adventure) return;
        if (player.getPageManager().getCustomPage() instanceof InventoryShellPage active
                && active.belongsTo(playerRef) && active.canRefocus(ref, store)) {
            active.refocus(ref, store);
            return;
        }
        var page = new InventoryShellPage(playerRef, registry, sessions::remove);
        sessions.add(page);
        try {
            player.getPageManager().openCustomPage(ref, store, page);
        } catch (RuntimeException failure) {
            page.onDismiss(ref, store);
            throw failure;
        }
    }

    @Override
    protected void shutdown() {
        running = false;
        if (registryListener != null) { registryListener.close(); registryListener = null; }
        packetBridge.close();
        for (var page : sessions) page.closeForShutdown();
        sessions.clear();
        registry.clear();
        if (instance == this) instance = null;
    }
}
