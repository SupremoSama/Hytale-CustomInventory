package com.supremosan.custominventory;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.supremosan.custominventory.api.*;
import com.supremosan.custominventory.inventory.NativeGestureRegression;
import com.supremosan.custominventory.ui.InventoryShellPage;
import com.supremosan.custominventory.ui.InventoryLifecycleRegression;
import com.supremosan.custominventory.ui.player.UtilityWheelHitRegions;
import org.bson.BsonDocument;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Behavioral verification with the installed engine; no server, assets or client are started. */
public final class RegressionSuite {
    private static int assertions;

    public static void main(String[] args) {
        try { verify(); }
        catch (Throwable failure) {
            // Hytale installs its own uncaught-exception handler during static initialization.
            // Print failures explicitly so Gradle reports the failed regression assertion.
            failure.printStackTrace(System.err);
            throw failure;
        }
    }

    private static void verify() {
        occupiedSlotsFollowContainerChanges();
        registrationHandlesOwnOnlyTheirRegistration();
        registrationChangesNotifyAndReleaseHosts();
        editorEditsStayWithinLifetimeAndOwnedHosts();
        registeredContributionsCannotClearOneAnother();
        eventEnvelopesStayWithinTheirSession();
        rebuiltPagesRejectOldEvents();
        utilityWheelSlicesFollowNativeGeometry();
        extraEquipmentFiltersAndCopies();
        assertions += NativeGestureRegression.verify();
        assertions += InventoryLifecycleRegression.verify();
        System.out.println("CustomInventory regression checks passed (" + assertions + " assertions).");
    }

    private static void extraEquipmentFiltersAndCopies() {
        ExtraEquipment.registerItems(ExtraEquipment.HAT, item -> item.getItemId().equals("TestHat"));
        ExtraEquipment.registerItems(ExtraEquipment.BACKPACK, item -> item.getItemId().equals("TestPack"));
        var equipment = new ExtraEquipment();
        var source = new SimpleItemContainer((short) 2);
        source.setItemStackForSlot((short) 0, stack("TestHat", 1));
        equal(false, source.moveItemStackFromSlotToSlot((short) 0, 1, equipment.getInventory(), ExtraEquipment.BACKPACK).succeeded(),
                "hat cannot occupy backpack slot");
        equal("TestHat", source.getItemStack((short) 0).getItemId(), "rejected equipment move preserves source");
        equal(true, source.moveItemStackFromSlotToSlot((short) 0, 1, equipment.getInventory(), ExtraEquipment.HAT).succeeded(),
                "hat equips in dedicated slot");
        equal(false, ExtraEquipment.accepts(ExtraEquipment.HAT, stack("TestHat", 2)), "equipment is limited to one item");
        equal(false, ExtraEquipment.accepts(ExtraEquipment.COLLAR, stack("TestHat", 1)), "unregistered collar type is rejected");
        equipment.markLegacyTrueBackpackMigrated();
        var loaded = ExtraEquipment.CODEC.decode(ExtraEquipment.CODEC.encode(equipment));
        equal("TestHat", loaded.getInventory().getItemStack(ExtraEquipment.HAT).getItemId(), "equipment survives codec round trip");
        equal(true, loaded.isLegacyTrueBackpackMigrated(), "migration only runs once across saves");
        equal(false, loaded.getInventory().setItemStackForSlot(ExtraEquipment.BELT, stack("TestHat", 1)).succeeded(),
                "slot filters are restored after decoding");
        var copy = equipment.clone();
        equal(true, copy.isLegacyTrueBackpackMigrated(), "migration marker survives cloning");
        equipment.getInventory().removeItemStackFromSlot(ExtraEquipment.HAT);
        equal("TestHat", copy.getInventory().getItemStack(ExtraEquipment.HAT).getItemId(), "saved equipment clone has independent contents");
        equal(true, copy.getInventory().moveItemStackFromSlotToSlot(ExtraEquipment.HAT, 1, source, (short) 1).succeeded(),
                "equipment can be returned to normal storage");
    }

    private static void utilityWheelSlicesFollowNativeGeometry() {
        equal(-2, UtilityWheelHitRegions.slotAt(155, 155), "wheel center remains a drop target");
        equal(-2, UtilityWheelHitRegions.slotAt(0, 0), "wheel corners do not select items");
        int[] slots = {2, 3, -1, 0, 1};
        for (int slice = 0; slice < slots.length; slice++) {
            for (int radius : new int[]{75, 108, 150}) {
                double angle = Math.toRadians(slice * 72 + 36);
                equal(slots[slice], UtilityWheelHitRegions.slotAt(
                        155 + Math.sin(angle) * radius, 155 - Math.cos(angle) * radius),
                        "hover covers inner edge, icon and outer edge of slice " + slice);
            }
        }
        equal(-2, UtilityWheelHitRegions.slotAt(155, 86), "inside center boundary");
        equal(-2, UtilityWheelHitRegions.slotAt(155, 310), "outside wheel boundary");
    }

    private static void occupiedSlotsFollowContainerChanges() {
        var backpack = new SimpleItemContainer((short) 8);
        equal(0, InventorySlotUsage.occupiedSlots(null), "missing backpack");
        equal(0, InventorySlotUsage.occupiedSlots(backpack), "empty backpack");
        backpack.setItemStackForSlot((short) 0, stack("A", 64));
        equal(1, InventorySlotUsage.occupiedSlots(backpack), "64 items consume one slot");
        backpack.setItemStackForSlot((short) 0, stack("A", 32));
        equal(1, InventorySlotUsage.occupiedSlots(backpack), "quantity decrease keeps occupied count");
        backpack.setItemStackForSlot((short) 1, stack("A", 32));
        equal(2, InventorySlotUsage.occupiedSlots(backpack), "split stack consumes another slot");
        backpack.setItemStackForSlot((short) 0, stack("A", 64));
        backpack.removeItemStackFromSlot((short) 1);
        equal(1, InventorySlotUsage.occupiedSlots(backpack), "merge releases its former slot");
        for (short slot = 1; slot < 5; slot++) backpack.setItemStackForSlot(slot, stack("Type_" + slot, slot));
        equal(5, InventorySlotUsage.occupiedSlots(backpack), "five occupied slots with differing quantities");
        var rebuilt = new SimpleItemContainer(backpack);
        equal(5, InventorySlotUsage.occupiedSlots(rebuilt), "container rebuild retains occupied count");
        backpack.setItemStackForSlot((short) 0, ItemStack.EMPTY);
        equal(4, InventorySlotUsage.occupiedSlots(backpack), "engine empty sentinel consumes no slot");
        for (short slot = 1; slot < 5; slot++) backpack.removeItemStackFromSlot(slot);
        equal(0, InventorySlotUsage.occupiedSlots(backpack), "removing every stack empties the backpack");
        equal(5, InventorySlotUsage.occupiedSlots(rebuilt), "separate backpack state stays independent");
    }

    private static void registrationHandlesOwnOnlyTheirRegistration() {
        var registry = new InventoryRegistry();
        var definition = extension("tests:bench", 10);
        var first = registry.registerUiExtension(definition);
        var firstEntry = registry.getExtensionRegistration(definition.id());
        expect(IllegalArgumentException.class, () -> registry.registerUiExtension(definition), "duplicate extension");
        registry.registerUiExtension(extension("tests:z", 20));
        registry.registerUiExtension(extension("tests:b", 10));
        registry.registerUiExtension(extension("tests:a", -1));
        equal(List.of("tests:a", "tests:b", "tests:bench", "tests:z"),
                registry.extensionsSnapshot().stream().map(e -> e.definition().id()).toList(), "deterministic order");
        expect(UnsupportedOperationException.class, () -> registry.extensionsSnapshot().clear(), "immutable snapshot");
        registry.clear();
        var replacement = registry.registerUiExtension(definition);
        var replacementEntry = registry.getExtensionRegistration(definition.id());
        check(firstEntry != replacementEntry, "re-register creates a new ownership token");
        first.close();
        first.close();
        check(replacementEntry == registry.getExtensionRegistration(definition.id()), "old cleanup cannot delete new registration");
        replacement.close();
        replacement.close();
        equal(null, registry.getExtensionRegistration(definition.id()), "idempotent unregister");

        var page = new InventoryPageDefinition("tests:page", "Page", 0, ignored -> null);
        var pageHandle = registry.registerInventoryPage(page);
        registry.clear();
        registry.registerInventoryPage(page);
        pageHandle.close();
        check(registry.getPage(page.id()) == page, "old page handle preserves replacement");
        var button = new InventoryButtonDefinition("tests:button", "Button", 0, ignored -> {});
        var buttonHandle = registry.registerInventoryButton(button);
        registry.clear();
        registry.registerInventoryButton(button);
        buttonHandle.close();
        check(registry.getButton(button.id()) == button, "old button handle preserves replacement");
        expect(IllegalArgumentException.class, () -> extension("unqualified", 0), "extension IDs must be namespaced");
    }

    private static void editorEditsStayWithinLifetimeAndOwnedHosts() {
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        var editor = new InventoryUiEditor(commands, new InventoryEventBindings(events, "", "tests:bench", "session"));
        editor.visible(InventoryElementId.INVENTORY_PANEL, true);
        editor.text(InventoryElementId.TITLE, "Backpack workbench");
        editor.append(InventoryElementId.CONTENT_HOST, "Tests/Bench.ui");
        var retained = new AtomicReference<UICommandBuilder>();
        editor.edit(InventoryElementId.CONTENT_HOST, scoped -> {
            retained.set(scoped);
            scoped.set("#Name.Text", "Bench");
            scoped.appendInline("#Extra", "Group #New {}");
        });
        var edits = commands.getCommands();
        equal("#ContentHost #Name.Text", edits[3].selector, "owned content selector");
        equal("#ContentHost #Extra", edits[4].selector, "added descendant selector");
        int beforeRejected = edits.length;
        expect(IllegalArgumentException.class, () -> editor.clear(InventoryElementId.STORAGE_GRID), "native grid cannot be cleared");
        expect(IllegalArgumentException.class, () -> editor.remove(InventoryElementId.STORAGE_GRID, "#Slot"), "native grid cannot be removed");
        expect(IllegalArgumentException.class, () -> editor.edit(InventoryElementId.SHELL, scoped -> scoped.clear("#StorageGrid")), "shell is not an extension host");
        expect(IllegalArgumentException.class, () -> editor.remove(InventoryElementId.CONTENT_HOST, "#Owned, #StorageGrid"), "selector list cannot escape host");
        equal(beforeRejected, commands.getCommands().length, "denied edits emit no commands");
        editor.bind(CustomUIEventBindingType.Activating, InventoryElementId.CONTENT_HOST, "#Paint", "Paint", "Blue", true);
        equal("#ContentHost #Paint", events.getEvents()[0].selector, "extension event target");
        editor.close();
        editor.close();
        expect(IllegalStateException.class, () -> editor.visible(InventoryElementId.SHELL, false), "closed editor");
        expect(IllegalStateException.class, () -> retained.get().set("#Name.Text", "Late"), "captured builder expires with editor");
        expect(IllegalStateException.class, () -> retained.get().append("Tests/Late.ui"), "captured append expires with editor");
        equal(beforeRejected, commands.getCommands().length, "expired edits emit no commands");
    }

    private static void registrationChangesNotifyAndReleaseHosts() {
        var registry = new InventoryRegistry();
        var notifications = new AtomicInteger();
        var observer = registry.onChange(notifications::incrementAndGet);
        var definition = extension("tests:changing", 0);
        var old = registry.registerUiExtension(definition);
        equal(1, notifications.get(), "open hosts notified when extension registers");
        expect(IllegalArgumentException.class, () -> registry.registerUiExtension(definition), "duplicate change rejected");
        equal(1, notifications.get(), "failed registration does not notify hosts");
        registry.clear();
        var replacement = registry.registerUiExtension(definition);
        equal(3, notifications.get(), "clear and replacement each notify hosts");
        old.close();
        equal(3, notifications.get(), "stale handle emits no false change");
        replacement.close();
        replacement.close();
        equal(4, notifications.get(), "extension unregister notifies only once");
        observer.close();
        observer.close();
        registry.registerUiExtension(definition);
        equal(4, notifications.get(), "disposed host listener receives no later changes");

        var isolated = new InventoryRegistry();
        var laterNotifications = new AtomicInteger();
        var throwing = isolated.onChange(() -> { throw new IllegalStateException("simulated closed host"); });
        var healthy = isolated.onChange(laterNotifications::incrementAndGet);
        var handle = isolated.registerUiExtension(definition);
        check(isolated.getExtensionRegistration(definition.id()) != null, "observer failure does not strand registration without a handle");
        equal(1, laterNotifications.get(), "later hosts notified despite prior observer failure");
        handle.close();
        equal(null, isolated.getExtensionRegistration(definition.id()), "registration handle remains usable after observer failure");
        equal(2, laterNotifications.get(), "later hosts notified on unregister despite prior observer failure");
        throwing.close();
        throwing.close();
        healthy.close();
        healthy.close();
        isolated.registerUiExtension(definition);
        equal(2, laterNotifications.get(), "observer handles dispose idempotently");
    }

    private static void eventEnvelopesStayWithinTheirSession() {
        var events = new UIEventBuilder();
        var bindings = new InventoryEventBindings(events, "#ContentHost", "tests:bench", "instance-1");
        bindings.addEventBinding(CustomUIEventBindingType.Activating, "#Save", new EventData()
                .append("Action", "SaveName").append("Target", "backpack-id").append("@Text", "#Name.Value"), false);
        var binding = events.getEvents()[0];
        equal("#ContentHost #Save", binding.selector, "bound control is scoped");
        check(!binding.locksInterface, "caller controls interface lock");
        var envelope = BsonDocument.parse(binding.data);
        equal("Content", envelope.getString("Action").getValue(), "outer action belongs to host");
        equal("SaveName", envelope.getString("ContentAction").getValue(), "local action is carried inside host envelope");
        equal("backpack-id", envelope.getString("Payload").getValue(), "local target becomes payload");
        equal("tests:bench", envelope.getString("PageId").getValue(), "page ownership retained");
        equal("instance-1", envelope.getString("SessionId").getValue(), "session ownership retained");
        equal("#ContentHost #Name.Value", envelope.getString("@Text").getValue(), "form lookup is scoped");
        expect(IllegalArgumentException.class, () -> bindings.addEventBinding(CustomUIEventBindingType.Activating, "#Bad",
                new EventData().append("Action", "Save").append("SessionId", "other-player")), "local data cannot replace session ownership");
        expect(IllegalArgumentException.class, () -> bindings.selector("Button"), "selectors require a local ID");
        equal(1, events.getEvents().length, "rejected event emits no binding");
        equal("", bindings.data("Cancel", null).events().get("Payload"), "null payload remains safe");
    }

    private static void registeredContributionsCannotClearOneAnother() {
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        InventoryUiEditor.mountContribution(commands, "First");
        InventoryUiEditor.mountContribution(commands, "Second");
        var bindings = new InventoryEventBindings(events, "", "@extension:tests:first", "instance");
        try (var first = new InventoryUiEditor(commands, bindings, "First");
             var second = new InventoryUiEditor(commands, bindings, "Second")) {
            String firstLocal = first.selector(InventoryElementId.CONTENT_HOST, "#SharedName");
            String secondLocal = second.selector(InventoryElementId.CONTENT_HOST, "#SharedName");
            check(!firstLocal.equals(secondLocal), "same local IDs in different mods remain independent");
            first.clear(InventoryElementId.CONTENT_HOST);
            equal("#ContentExtensions #CIContributionFirst", commands.getCommands()[10].selector, "clear targets only the first contribution");
            check(!commands.getCommands()[10].selector.contains("Second"), "first contribution cannot clear second");
            equal(first.selector(InventoryElementId.STORAGE_GRID), second.selector(InventoryElementId.STORAGE_GRID), "supported core IDs resolve consistently");
        }
        InventoryUiEditor.removeContribution(commands, "First");
        var edits = commands.getCommands();
        equal(16, edits.length, "removing one contribution removes all five of its hosts");
        for (int index = 11; index < edits.length; index++) {
            check(edits[index].selector.endsWith("#CIContributionFirst"), "unregister targets its own contribution child");
        }
        int beforeInvalid = commands.getCommands().length;
        expect(IllegalArgumentException.class, () -> InventoryUiEditor.mountContribution(commands, "bad:token"), "token cannot contain selector punctuation");
        expect(IllegalArgumentException.class, () -> InventoryUiEditor.removeContribution(commands, "First #Other"), "token cannot name another selector");
        equal(beforeInvalid, commands.getCommands().length, "invalid tokens emit no mutations");
    }

    private static void rebuiltPagesRejectOldEvents() {
        check(InventoryShellPage.acceptsEvent("player-a-open-2", "player-a-open-2"), "mounted session accepts its events");
        check(!InventoryShellPage.acceptsEvent("player-a-open-2", "player-a-open-1"), "previously rendered session rejected after reopen");
        check(!InventoryShellPage.acceptsEvent("player-a-open-2", "player-b-open-2"), "different player's event rejected");
        check(!InventoryShellPage.acceptsEvent("player-a-open-2", null), "missing session rejected");
        check(!InventoryShellPage.acceptsEvent(null, null), "unmounted session rejects events");
    }

    private static InventoryUiExtensionDefinition extension(String id, int order) {
        return new InventoryUiExtensionDefinition(id, order, ignored -> new InventoryUiExtension() {});
    }

    // The game's own InventoryComponentFilterTest uses this protected-constructor fixture.
    // It avoids asset loading while retaining the actual ItemStack/container representation.
    private static ItemStack stack(String id, int count) {
        return new ItemStack() {
            { itemId = id; quantity = count; }
            @Override public Item getItem() { return Item.UNKNOWN; }
        };
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static void equal(Object expected, Object actual, String description) {
        check(Objects.equals(expected, actual), description + ": expected " + expected + ", got " + actual);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action, String description) {
        assertions++;
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError(description + ": unexpected " + error, error);
        }
        throw new AssertionError(description + ": expected " + type.getSimpleName());
    }
}
