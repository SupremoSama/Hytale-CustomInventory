package com.supremosan.custominventory.packet;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent;
import com.hypixel.hytale.protocol.packets.inventory.DropItemStack;
import com.hypixel.hytale.protocol.packets.inventory.SmartMoveItemStack;
import com.hypixel.hytale.protocol.packets.window.ClientOpenWindow;
import com.hypixel.hytale.protocol.packets.window.CloseWindow;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.IWorldPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.SubPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.io.handlers.game.InventoryPacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.inventory.InventoryOperations;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Transport-independent inbound hook. Inbound {@code PacketAdapters} only run on the Netty transport;
 * the Quiche transport used by player-hosted (singleplayer) worlds calls the packet handler directly.
 * Sub packet handlers register after the native game handlers on every connection, so these wrappers
 * see the inventory packets on either transport and fall back to the native consumer when not routed.
 */
final class InventoryPacketHooks implements SubPacketHandler {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    // ServerManager has no unregistration: a cleared route turns every installed hook into a native pass-through.
    private static volatile BiPredicate<PlayerRef, Packet> route;
    private static final AtomicBoolean DISPLACED_LOGGED = new AtomicBoolean();
    // Our installed wrappers per connection, so a later registration by another mod can be detected and chained.
    private static final Map<PacketHandler, Map<Integer, Consumer<ToServerPacket>>> HOOKED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Field HANDLERS = handlersField();

    private final IPacketHandler packetHandler;

    private InventoryPacketHooks(IPacketHandler packetHandler) { this.packetHandler = packetHandler; }

    /** Applies to connections created afterwards; earlier connections keep the packet-adapter fallback. */
    static void install(BiPredicate<PlayerRef, Packet> packetRoute) {
        route = packetRoute;
        if (INSTALLED.compareAndSet(false, true)) ServerManager.get().registerSubPacketHandlers(InventoryPacketHooks::new);
    }

    static void uninstall(BiPredicate<PlayerRef, Packet> packetRoute) {
        if (route == packetRoute) route = null;
    }

    /**
     * False when another mod replaced one of our wrappers; the packet-adapter fallback then routes this packet,
     * and the displacing handler is wrapped so later packets reach both mods.
     */
    static boolean isHooked(PacketHandler handler) {
        if (handler == null) return false;
        var wrappers = HOOKED.get(handler);
        if (wrappers == null) return false;
        if (HANDLERS == null || !(handler instanceof GamePacketHandler game)) return true;
        boolean intact = true;
        synchronized (wrappers) {
            for (var entry : Map.copyOf(wrappers).entrySet()) {
                var current = current(game, entry.getKey());
                if (current == null || current == entry.getValue()) continue;
                intact = false;
                if (DISPLACED_LOGGED.compareAndSet(false, true)) {
                    LOGGER.atWarning().log("CustomInventory packet hook %d was replaced by %s; chaining it",
                            entry.getKey(), current.getClass().getName());
                }
                hook(game, entry.getKey(), current, wrappers);
            }
        }
        return intact;
    }

    static boolean isHookedPacket(Packet packet) {
        return packet instanceof ClientOpenWindow || packet instanceof CloseWindow
                || packet instanceof CustomPageEvent || packet instanceof DropItemStack
                || packet instanceof SmartMoveItemStack;
    }

    @Override
    public void registerHandlers() {
        if (!(packetHandler instanceof GamePacketHandler game)) return;
        var wrappers = new HashMap<Integer, Consumer<ToServerPacket>>();
        var natives = new NativeHandlers(game);
        IWorldPacketHandler.<ClientOpenWindow>registerHandler(natives, ClientOpenWindow.PACKET_ID,
                (packet, playerRef, ref, world, store) -> openNativeWindow(game, packet, ref, store));
        IWorldPacketHandler.<CloseWindow>registerHandler(natives, CloseWindow.PACKET_ID, game::handleCloseWindow);

        hook(game, ClientOpenWindow.PACKET_ID, previous(game, ClientOpenWindow.PACKET_ID, natives.get(ClientOpenWindow.PACKET_ID)), wrappers);
        hook(game, CloseWindow.PACKET_ID, previous(game, CloseWindow.PACKET_ID, natives.get(CloseWindow.PACKET_ID)), wrappers);
        hook(game, CustomPageEvent.PACKET_ID, previous(game, CustomPageEvent.PACKET_ID, packet -> game.handle((CustomPageEvent) packet)), wrappers);
        var inventory = game.getSubPacketHandler(InventoryPacketHandler.class);
        if (inventory != null) hook(game, DropItemStack.PACKET_ID, previous(game, DropItemStack.PACKET_ID, packet -> inventory.handle((DropItemStack) packet)), wrappers);
        if (inventory != null) hook(game, SmartMoveItemStack.PACKET_ID, previous(game, SmartMoveItemStack.PACKET_ID, packet -> inventory.handle((SmartMoveItemStack) packet)), wrappers);
        HOOKED.put(game, wrappers);
    }

    /** Another mod's handler registered before ours stays the fallback instead of being bypassed. */
    private static Consumer<ToServerPacket> previous(GamePacketHandler game, int packetId, Consumer<ToServerPacket> nativeHandler) {
        var current = current(game, packetId);
        return current != null && !isGameLambda(current) ? current : nativeHandler;
    }

    private static boolean isGameLambda(Consumer<ToServerPacket> handler) {
        return handler.getClass().getName().startsWith("com.hypixel.hytale.");
    }

    private static void hook(GamePacketHandler game, int packetId, Consumer<ToServerPacket> fallback,
                             Map<Integer, Consumer<ToServerPacket>> wrappers) {
        Consumer<ToServerPacket> wrapper = packet -> {
            if (!routed(game.getPlayerRef(), packet)) fallback.accept(packet);
        };
        game.registerHandler(packetId, wrapper);
        wrappers.put(packetId, wrapper);
    }

    @SuppressWarnings("unchecked")
    private static Consumer<ToServerPacket> current(GamePacketHandler game, int packetId) {
        if (HANDLERS == null) return null;
        try {
            var handlers = (Consumer<ToServerPacket>[]) HANDLERS.get(game);
            return handlers != null && packetId >= 0 && packetId < handlers.length ? handlers[packetId] : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Field handlersField() {
        try {
            var field = GenericPacketHandler.class.getDeclaredField("handlers");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOGGER.atWarning().withCause(failure).log("CustomInventory cannot inspect packet handlers; other mods' handlers may be bypassed");
            return null;
        }
    }

    private static boolean routed(PlayerRef playerRef, ToServerPacket packet) {
        var current = route;
        if (current == null || playerRef == null) return false;
        try {
            return current.test(playerRef, packet);
        } catch (RuntimeException failure) {
            // Same contract as PacketAdapters: a failing filter leaves the packet to the native handler.
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                LOGGER.atWarning().withCause(failure).log("CustomInventory packet route failed; using the native handler");
            }
            return false;
        }
    }

    /** GamePacketHandler.handleClientOpenWindow is protected; this is its body through public API. */
    private static void openNativeWindow(GamePacketHandler game, ClientOpenWindow packet,
                                         Ref<EntityStore> ref, Store<EntityStore> store) {
        if (InventoryOperations.locked(ref, store)) return;
        var supplier = Window.CLIENT_REQUESTABLE_WINDOW_TYPES.get(packet.type);
        if (supplier == null) throw new RuntimeException("Unable to process ClientOpenWindow packet. Window type is not supported!");
        var player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;
        var update = player.getWindowManager().clientOpenWindow(ref, supplier.get(), store);
        if (update != null) game.writeNoCache(update);
    }

    /** Captures the consumers IWorldPacketHandler builds, so world-thread validation stays native. */
    private static final class NativeHandlers implements IPacketHandler {
        private final GamePacketHandler game;
        private final Map<Integer, Consumer<ToServerPacket>> handlers = new HashMap<>();

        private NativeHandlers(GamePacketHandler game) { this.game = game; }

        Consumer<ToServerPacket> get(int packetId) { return Objects.requireNonNull(handlers.get(packetId)); }

        @Override
        public void registerHandler(int packetId, Consumer<ToServerPacket> handler) { handlers.put(packetId, handler); }

        @Override
        public void registerNoOpHandlers(int... packetIds) {
            for (int packetId : packetIds) handlers.put(packetId, packet -> { });
        }

        @Override
        public PlayerRef getPlayerRef() { return game.getPlayerRef(); }

        @Override
        public String getIdentifier() { return game.getIdentifier(); }
    }
}
