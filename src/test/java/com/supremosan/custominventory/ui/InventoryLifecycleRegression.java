package com.supremosan.custominventory.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.inventory.DropItemStack;
import com.supremosan.custominventory.packet.InventoryPacketRouter;
import com.supremosan.custominventory.api.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the shell's lifecycle dispatch without starting a world or rendering client UI. */
public final class InventoryLifecycleRegression {
    private static int assertions;

    public static int verify() {
        assertions = 0;
        var registry = new InventoryRegistry();
        var sessions = new ArrayList<Observer>();
        var definition = new InventoryUiExtensionDefinition("tests:lifecycle", 0, ignored -> {
            var observer = new Observer();
            sessions.add(observer);
            return observer;
        });
        var firstRegistration = registry.registerUiExtension(definition);
        var dismissed = new AtomicInteger();
        var firstPage = fixturePage(registry, ignored -> dismissed.incrementAndGet(), null, null);
        var secondPage = fixturePage(registry, ignored -> dismissed.incrementAndGet(), null, null);
        renderExtensions(firstPage, true);
        renderExtensions(secondPage, true);
        check(sessions.size() == 2 && sessions.get(0) != sessions.get(1), "two inventory sessions receive independent extensions");
        var first = sessions.get(0);
        var second = sessions.get(1);
        check(first.created == 1 && first.opened == 1 && first.updated == 1, "initial lifecycle creates/updates/opens exactly once");
        try {
            first.context.update(new UICommandBuilder().set("#ContentExtensions #CIContributionOther #Name.Text", "Cross-mod edit"));
            throw new AssertionError("expected contribution ownership rejection");
        } catch (IllegalArgumentException expected) {
            check(true, "registered extension cannot update another contribution's elements");
        }
        renderExtensions(firstPage, false);
        check(first.created == 1 && first.opened == 1 && first.updated == 2, "refresh updates existing extension without duplicate opening");
        check(second.updated == 1, "refresh does not touch another inventory session");
        firstRegistration.close();
        check(!first.context.isActive(), "unregistered extension context becomes inactive immediately");
        first.context.update(new UICommandBuilder().set("#StorageGrid.Text", "Stale edit"));
        check(true, "unregistered context silently discards delayed updates");
        renderExtensions(firstPage, false);
        check(first.closed == 1, "unregistered extension closes on next refresh");
        var replacement = registry.registerUiExtension(definition);
        renderExtensions(firstPage, false);
        check(sessions.size() == 3, "re-register receives a fresh session instance");
        check(sessions.get(2).created == 1 && sessions.get(2).opened == 1, "replacement starts its own lifecycle");
        dismissWithoutWorld(firstPage);
        dismissWithoutWorld(firstPage);
        check(first.closed == 1 && sessions.get(2).closed == 1, "dismiss does not close prior registrations twice");
        check(second.closed == 0, "closing one page preserves the other session");
        dismissWithoutWorld(secondPage);
        check(second.closed == 1 && dismissed.get() == 2, "each independent page disposes once");
        replacement.close();

        var failedView = new Observer() {
            @Override public void onCreated(InventoryContext context, InventoryUiEditor editor) {
                super.onCreated(context, editor);
                throw new IllegalStateException("simulated first-render failure");
            }
        };
        var view = new InventoryPageDefinition("tests:failed-view", "Failed view", 0, ignored -> null);
        var failedPage = fixturePage(registry, ignored -> {}, view, failedView);
        try {
            renderExtensions(failedPage, true);
            throw new AssertionError("expected first-render failure");
        } catch (IllegalStateException expected) {
            check(failedView.created == 1 && failedView.opened == 0, "failure occurs before opening");
        }
        dismissWithoutWorld(failedPage);
        dismissWithoutWorld(failedPage);
        check(failedView.closed == 1, "failed initial view still releases its extension once");
        exerciseEventCodecMouseButtonDecoding();
        exerciseEventCodecCurrentClickWins();
        exerciseInventoryInputDuringSlotAcknowledgments();
        return assertions;
    }

    private static InventoryShellPage fixturePage(InventoryRegistry registry, Consumer<InventoryShellPage> dismissed,
                                                   InventoryPageDefinition view, InventoryUiExtension extension) {
        // A real empty ECS store provides context identities without a server World.
        // Dispatch observers never read components or submit inventory operations.
        var components = new ComponentRegistry<EntityStore>();
        var store = components.addStore(null, null);
        var player = new PlayerRef(components.newHolder(), UUID.randomUUID(), "Regression", "en-US", null, null);
        var page = new InventoryShellPage(player, registry, dismissed, view, extension);
        setContext(page, new InventoryContext(new Ref<>(store), store, player, () -> {}, "tests:lifecycle",
                () -> {}, () -> false, ignored -> {}));
        return page;
    }

    private static void dismissWithoutWorld(InventoryShellPage page) {
        // World-thread teardown is a runtime concern; this fixture verifies lifecycle dispatch.
        setContext(page, null);
        page.onDismiss(null, null);
    }

    private static void setContext(InventoryShellPage page, InventoryContext context) {
        try {
            var field = InventoryShellPage.class.getDeclaredField("activeContext");
            field.setAccessible(true);
            field.set(page, context);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void renderExtensions(InventoryShellPage page, boolean initial) {
        try {
            var dispatch = InventoryShellPage.class.getDeclaredMethod("renderExtensions", UICommandBuilder.class, UIEventBuilder.class, boolean.class);
            dispatch.setAccessible(true);
            dispatch.invoke(page, new UICommandBuilder(), new UIEventBuilder(), initial);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static class Observer implements InventoryUiExtension {
        int created, opened, updated, closed;
        InventoryContext context;
        @Override public void onCreated(InventoryContext context, InventoryUiEditor editor) { this.context = context; created++; }
        @Override public void onOpened(InventoryContext context) { opened++; }
        @Override public void onUpdate(InventoryContext context, InventoryUiEditor editor) { updated++; }
        @Override public void onClosed(InventoryContext context) { closed++; }
    }

    private static void exerciseEventCodecMouseButtonDecoding() {
        String[] samples = {
            "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"Drop\",\"SlotIndex\":1,\"MouseButton\":2}",
            "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"Drop\",\"SlotIndex\":1,\"MouseButton\":\"Right\"}",
            "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"Drop\",\"SlotIndex\":1,\"ClickMouseButton\":3}",
            "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"Drop\",\"SlotIndex\":1,\"Button\":2}",
            "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"DragSource\",\"SlotIndex\":1,\"PressedMouseButton\":3}",
            "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"DragSource\",\"SlotIndex\":1,\"DragPressedMouseButton\":3}",
            "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"DragSource\",\"SlotIndex\":1,\"DragPressedMouseButton\":\"Right\"}"
        };
        for (String sample : samples) {
            try {
                var extraInfo = ExtraInfo.THREAD_LOCAL.get();
                var event = InventoryShellPage.Event.CODEC.decodeJson(new RawJsonReader(sample.toCharArray()), extraInfo);
                String resolved = event.resolvedMouseButton();
                check(resolved != null, "resolved mouse button is non-null for: " + sample);
                var contentEvent = new InventoryContentEvent(event.contentAction, event.payload, event.slotIndex,
                        event.dragData(), resolved, event.formValues(), event.shiftHeld);
                check(contentEvent.rightMouseButton(), "contentEvent identifies right mouse button for: " + sample);
            } catch (Exception e) {
                throw new AssertionError("Failed to decode: " + sample, e);
            }
        }
    }

    private static void exerciseEventCodecCurrentClickWins() {
        String[][] samples = {
                {"\"PressedMouseButton\":1,\"DragPressedMouseButton\":1,\"ClickMouseButton\":3", "Right"},
                {"\"PressedMouseButton\":3,\"DragPressedMouseButton\":3,\"ClickMouseButton\":1", "Left"},
                {"\"PressedMouseButton\":3,\"DragPressedMouseButton\":3,\"ClickMouseButton\":0", "Left"},
                {"\"DragPressedMouseButton\":1,\"MouseButton\":\"Left\",\"ClickMouseButton\":3", "Right"},
                {"\"DragPressedMouseButton\":3,\"MouseButton\":2,\"ClickMouseButton\":1", "Left"},
                {"\"DragPressedMouseButton\":3,\"MouseButton\":2,\"ClickMouseButton\":2", "Middle"},
                {"\"PressedMouseButton\":1,\"DragPressedMouseButton\":1,\"MouseButton\":2", "2"},
                {"\"PressedMouseButton\":1,\"DragPressedMouseButton\":1,\"Button\":2", "2"}
        };
        for (var sample : samples) {
            String json = "{\"Action\":\"Content\",\"ContentAction\":\"Drop\",\"SlotIndex\":1," + sample[0] + "}";
            try {
                var event = InventoryShellPage.Event.CODEC.decodeJson(new RawJsonReader(json.toCharArray()), ExtraInfo.THREAD_LOCAL.get());
                check(sample[1].equals(event.resolvedMouseButton()),
                        "current click wins over drag-origin button metadata and retains its enum: " + sample[0]);
                var content = new InventoryContentEvent(event.contentAction, event.payload, event.slotIndex,
                        event.dragData(), event.resolvedMouseButton());
                check(content.rightMouseButton() == ("Right".equals(sample[1]) || "2".equals(sample[1])),
                        "Middle two does not become protocol Right two: " + sample[0]);
            } catch (Exception failure) {
                throw new AssertionError("Failed to decode mixed mouse-button payload: " + json, failure);
            }
        }
        for (String action : List.of("DragPress", "HoverSource", "Drop", "CompleteSourceRelease", "UtilityWheelHover", "UtilityWheelDrop")) {
            String json = "{\"Action\":\"Content\",\"ContentAction\":\"" + action
                    + "\",\"PressedMouseButton\":3,\"DragPressedMouseButton\":3}";
            try {
                var event = InventoryShellPage.Event.CODEC.decodeJson(new RawJsonReader(json.toCharArray()), ExtraInfo.THREAD_LOCAL.get());
                check(event.resolvedMouseButton() == null,
                        "drag-origin Right metadata cannot pretend the current placement or hover is right: " + action);
            } catch (Exception failure) {
                throw new AssertionError("Failed to decode origin-only mouse payload: " + json, failure);
            }
        }
    }

    private static void exerciseInventoryInputDuringSlotAcknowledgments() {
        var fixture = new InputRoutingFixture();
        var initial = fixture.page(new UICommandBuilder(), new UIEventBuilder());
        initial.isInitial = true;
        fixture.router.observeServerPacket(fixture.connection, initial);
        check(!fixture.input("DragPress", "inventory-session"), "initial inventory mount gates right-button input until acknowledged");
        fixture.acknowledge();

        String[] coreSlots = {
                "#InventoryShell #InventoryPanelHost #StorageGrid.Slots",
                "#InventoryShell #InventoryPanelHost #HotbarGrid.Slots",
                "#InventoryShell #PlayerPanelHost #ArmorGrid.Slots",
                "#InventoryShell #PlayerPanelHost #UtilityGrid.Slots",
                "#InventoryShell #PlayerPanelHost #UtilityWheelCenterGrid.Slots",
                "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid0.Slots",
                "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid1.Slots",
                "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid2.Slots",
                "#InventoryShell #PlayerPanelHost #UtilityChoiceGrid3.Slots",
                "#InventoryShell #ContentHost #BackpackGrid.Slots"
        };
        for (String selector : coreSlots) {
            fixture.router.observeServerPacket(fixture.connection, fixture.page(
                    new UICommandBuilder().set(selector, new ItemGridSlot[0]), new UIEventBuilder()));
            check(fixture.router.canDispatchInventoryInput(fixture.connection), "stable core slot refresh permits input before acknowledgment: " + selector);
        }
        var gestures = List.of("DragPress", "UnhoverSource", "HoverSource", "CompleteSourceRelease", "Drop");
        for (String gesture : gestures) {
            check(fixture.input(gesture, "inventory-session"), "rapid right gesture is queued while core Slots await acknowledgment: " + gesture);
        }
        fixture.drain();
        check(fixture.received.equals(gestures), "right press, exits, entries, release and drop arrive in order before slot acknowledgments");
        check(fixture.input("HoverSource", "old-session"), "stale input reaches the core mounted-session validator");
        fixture.drain();
        check(fixture.received.equals(gestures), "core session validation rejects stale input while Slots await acknowledgment");
        for (String ignored : coreSlots) fixture.acknowledge();

        var rebound = new UIEventBuilder().addEventBinding(CustomUIEventBindingType.SlotMouseEntered,
                "#InventoryShell #InventoryPanelHost #StorageGrid", false);
        fixture.router.observeServerPacket(fixture.connection, fixture.page(new UICommandBuilder(), rebound));
        fixture.router.observeServerPacket(fixture.connection, fixture.page(
                new UICommandBuilder().set(coreSlots[0], new ItemGridSlot[0]), new UIEventBuilder()));
        check(!fixture.input("HoverSource", "inventory-session"), "a pending rebind still gates input despite a later core Slots refresh");
        fixture.acknowledge();
        check(fixture.input("CompleteSourceRelease", "inventory-session"), "acknowledging the rebind permits release despite pending core Slots acknowledgment");
        fixture.drain();
        check(fixture.received.size() == gestures.size() + 1, "accepted release is delivered after the rebind acknowledgment");
        fixture.acknowledge();

        UICommandBuilder[] gated = {
                new UICommandBuilder().set("#InventoryShell #InventoryPanelHost #StorageGrid.InventorySectionId", -2),
                new UICommandBuilder().set("#InventoryShell #InventoryPanelHost #StorageGrid.AreItemsDraggable", false),
                new UICommandBuilder().set("#ContentExtensions #DynamicGrid.Slots", new ItemGridSlot[0]),
                new UICommandBuilder().set("#ContentExtensions #StorageGrid.Slots", new ItemGridSlot[0]),
                new UICommandBuilder().appendInline("#ContentHost", "Group #Replacement {}"),
                new UICommandBuilder().clear("#ContentHost")
        };
        for (UICommandBuilder commands : gated) {
            fixture.router.observeServerPacket(fixture.connection, fixture.page(commands, new UIEventBuilder()));
            check(!fixture.input("DragPress", "inventory-session"), "identity, dragging, dynamic-slot and structural updates retain acknowledgment gating");
            fixture.acknowledge();
        }
        var cleared = fixture.page(new UICommandBuilder(), new UIEventBuilder());
        cleared.clear = true;
        fixture.router.observeServerPacket(fixture.connection, cleared);
        check(!fixture.input("DragPress", "inventory-session"), "whole-page clear retains acknowledgment gating");
        fixture.acknowledge();
        fixture.router.close();
    }

    /** Exercises packet-to-world dispatch and the same decoded core session envelope used by the bridge. */
    private static final class InputRoutingFixture implements InventoryPacketRouter.Actions<Object> {
        final Object connection = new Object();
        final InventoryPacketRouter<Object> router = new InventoryPacketRouter<>(this);
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        final ArrayList<String> received = new ArrayList<>();

        CustomPage page(UICommandBuilder commands, UIEventBuilder events) {
            return new CustomPage(InventoryPacketRouter.INVENTORY_PAGE_KEY, false, false,
                    CustomPageLifetime.CanDismiss, commands.getCommands(), events.getEvents());
        }

        boolean input(String action, String session) {
            var event = new CustomPageEvent();
            event.type = CustomPageEventType.Data;
            event.data = "{\"Action\":\"Content\",\"PageId\":\"@inventory:panels\",\"SessionId\":\"" + session
                    + "\",\"ContentAction\":\"" + action + "\",\"Payload\":\"STORAGE\",\"SlotIndex\":1,\"PressedMouseButton\":\"Right\"}";
            return router.route(connection, event);
        }

        void acknowledge() {
            var event = new CustomPageEvent();
            event.type = CustomPageEventType.Acknowledge;
            check(!router.route(connection, event), "native PageManager continues to own acknowledgments");
        }

        void drain() { while (!queued.isEmpty()) queued.removeFirst().run(); }
        @Override public boolean enqueue(Object connection, Runnable task) { queued.addLast(task); return true; }
        @Override public boolean isInventoryOpen(Object connection) { return true; }
        @Override public void openInventory(Object connection) { }
        @Override public void closeInventory(Object connection) { }
        @Override public void closeWindowZero(Object connection) { }
        @Override public void relinquishInventory(Object connection) { }
        @Override public void dropHoveredInventoryItem(Object connection, DropItemStack packet) { }
        @Override public void handleCustomPageInput(Object connection, CustomPageEvent packet) {
            if (!router.canDispatchInventoryInput(connection)) return;
            try {
                var event = InventoryShellPage.Event.CODEC.decodeJson(new RawJsonReader(packet.data.toCharArray()), ExtraInfo.THREAD_LOCAL.get());
                if ("Content".equals(event.action) && "@inventory:panels".equals(event.pageId)
                        && InventoryShellPage.acceptsEvent("inventory-session", event.sessionId)) received.add(event.contentAction);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
    }
}
