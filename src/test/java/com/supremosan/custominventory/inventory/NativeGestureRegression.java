package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.InventoryContentEvent;
import org.bson.BsonDocument;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/** Exercises the release callback itself, including its retained session state. */
public final class NativeGestureRegression {
    private static int assertions;

    public static int verify() {
        assertions = 0;
        check(NativeInventoryContent.completesShiftSourceRelease(true, true, false, true), "stationary Shift + left release completes");
        check(NativeInventoryContent.completesShiftSourceRelease(true, false, false, true), "Shift released before mouse still completes captured gesture");
        check(NativeInventoryContent.completesShiftSourceRelease(true, null, false, true), "captured Shift supports clients omitting release modifier");
        check(NativeInventoryContent.completesShiftSourceRelease(false, true, false, true), "Shift held at release completes");
        check(!NativeInventoryContent.completesShiftSourceRelease(false, false, false, true), "ordinary click retains its carried item");
        check(!NativeInventoryContent.completesShiftSourceRelease(false, null, false, true), "activation without mouse modifier keeps native selection");
        check(!NativeInventoryContent.completesShiftSourceRelease(true, true, true, true), "right-button split remains owned by native drag");
        check(!NativeInventoryContent.completesShiftSourceRelease(true, true, false, false), "destination release remains owned by native Dropped event");
        check(new InventoryContentEvent("Drop", "STORAGE", 1, null, "2").rightMouseButton(),
                "protocol mouse button Right with enum value two is recognized");
        check(!new InventoryContentEvent("Drop", "STORAGE", 1, null, "3").rightMouseButton(),
                "protocol mouse button X1 with enum value three is not treated as Right");
        exerciseRelease(NativeInventorySection.STORAGE, false, true, "Left", 1, true);
        exerciseRelease(NativeInventorySection.STORAGE, false, false, "Left", 1, false);
        exerciseRelease(NativeInventorySection.EXTRA, false, false, "Left", 0, false);
        exerciseRelease(NativeInventorySection.STORAGE, false, true, "Right", 1, false);
        exerciseRelease(NativeInventorySection.STORAGE, false, true, "Left", 2, false);
        exerciseRelease(NativeInventorySection.UTILITY, true, true, "Left", 0, true);
        exerciseTooltipDisplay();
        exerciseShiftTransferWithBackpackOpen();
        exerciseSameSlotDropClearsSelection();
        exerciseCombineItemStacks();
        exerciseRapidSameSlotClickGroupsItems();
        exerciseRightClickSplit();
        exerciseNormalRightPickupTakesOne();
        exerciseExplicitRightPickupQuantity();
        exerciseDecodedCurrentClickPlacement();
        exerciseDecodedButtonlessDropRetainsRemainder();
        exerciseDecodedRightClickHoldingAndDragSweep();
        exerciseLiveClientDragWithoutPriorDragSource();
        exerciseLiveClientSplitDragConservation();
        exerciseLiveClientSplitDragGhostItemFix();
        exerciseDestinationReleaseTransfersItem();
        exerciseRightClickSingleDeposit();
        exerciseRightClickDepositSameSlot();
        exerciseRightClickSweepMultiSlot();
        exerciseRightClickSplitAndDeposit();
        exerciseRightClickSweepReentry();
        exerciseInteractionHintsVisibility();
        exerciseRightClickDropTransfersOne();
        exerciseRightClickPressAndDropDoesNotDuplicate();
        exerciseRightClickSweepAndDropDoesNotDuplicate();
        exerciseLeftHoverPlacesAll();
        exerciseReleaseBeforeDropDoesNotDuplicate();
        exerciseRepeatedCallbacksInSameVisit();
        exerciseHoverAfterRightRelease();
        exerciseLastUnitTerminalCallbacks();
        exerciseSplitSourceQuantityConservation();
        exerciseCrossSectionSweepReentry();
        exerciseFullAndIncompatibleSweepTargets();
        exerciseFilteredTargetRetainsHeld();
        exerciseAdditionalGestureRegressions();
        return assertions;
    }

    private static void exerciseRelease(NativeInventorySection section, boolean wheel, boolean shift,
                                        String button, int releasedSlot, boolean completes) {
        var controller = newController();
        var source = new SimpleItemContainer((short) 4);
        var original = new DetachedTestStack("Regression_Item", 64, null);
        source.setItemStackForSlot((short) 1, original);
        var selected = InventorySelection.capture(section, source, 1);
        set(controller, "selection", selected);
        set(controller, "dragOrigin", selected);
        set(controller, "hoveredSelection", selected);
        set(controller, "dropButtonSelection", selected);
        set(controller, "shiftSourceGesture", shift);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(section, new ItemStack[] { null, original, null, null });
        Map<Object, InventorySelection> retained = get(controller, "dragOrigins");
        retained.put(new Object(), selected);
        String action = wheel ? "CompleteUtilitySourceRelease" : "CompleteSourceRelease";
        String payload = wheel ? "1" : section.name();

        // A terminal source release needs no world/store: it reconciles presentation only.
        // A move/drop attempted here would fail this test because there is no context.
        controller.handleEvent(null, new InventoryContentEvent(action, payload, releasedSlot, null, button, Map.of(), shift));
        check(get(controller, "dragOrigin") == (completes ? null : selected), "release updates held origin only when terminal");
        check(get(controller, "selection") == (completes ? null : selected), "release updates logical selection only when terminal");
        Set<NativeInventorySection> resync = get(controller, "releasedSources");
        check(resync.contains(section) == completes, "terminal release schedules source grid reconciliation");
        check(retained.isEmpty() == completes, "terminal release discards captured drag origins");
        check(source.getItemStack((short) 1) == original, "release neither removes nor duplicates authoritative items");
        check(source.getItemStack((short) 1).getQuantity() == 64, "release preserves authoritative stack quantity");
    }

    private static void set(Object target, String name, Object value) {
        try { field(target.getClass(), name).set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String name) {
        try { return (T) field(target.getClass(), name).get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Field field(Class<?> clazz, String name) throws ReflectiveOperationException {
        var field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static NativeInventoryContent newController() {
        return new NativeInventoryContent(null, (context, selected, section, slot, quantity, source, target) -> {
            var result = InventoryOperations.validate(selected, source, target, slot, quantity,
                    context != null && InventoryOperations.locked(context.ref(), context.store()));
            if (result != InventoryOperations.Result.SUBMITTED) return result;
            // Execute the engine container transaction without starting a World. This
            // preserves its stack limits, metadata checks and native slot filters.
            var transaction = source.moveItemStackFromSlotToSlot((short) selected.slot(), quantity, target, (short) slot);
            return transaction.succeeded() ? InventoryOperations.Result.SUBMITTED : InventoryOperations.Result.DENIED;
        });
    }

    private static void exerciseTooltipDisplay() {
        var registry = new com.supremosan.custominventory.api.InventoryRegistry();
        registry.registerItemTooltip(new com.supremosan.custominventory.api.InventoryItemTooltipDefinition(
                "tests:tooltip", 0, ctx -> "Custom Backpack Contents (4/16)"
        ));
        var stack = new DetachedTestStack("Backpack_Item", 1, null);
        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(new com.hypixel.hytale.component.Ref<>(store), store, player, () -> {});

        var slot = InventoryDisplay.slot(stack, context, registry, "STORAGE", 0);
        String desc = get(slot, "description");
        ItemStack copy = get(slot, "itemStack");
        check("Custom Backpack Contents (4/16)".equals(desc), "slot has resolved tooltip description");
        check(copy != null && copy.getMetadata() == null,
                "slot item copy strips metadata to avoid CustomUI deserialization failure");
    }

    private static void exerciseShiftTransferWithBackpackOpen() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var backpack = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Wood", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        var hotbar = new SimpleItemContainer((short) 4);
        containers.put(NativeInventorySection.STORAGE, storage);
        containers.put(NativeInventorySection.HOTBAR, hotbar);
        containers.put(NativeInventorySection.BACKPACK, backpack);

        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });
        displayed.put(NativeInventorySection.HOTBAR, new ItemStack[]{ null, null, null, null });
        displayed.put(NativeInventorySection.BACKPACK, new ItemStack[]{ null, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Shift click on storage slot 0:
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), true));
        check(get(controller, "dragOrigin") != null, "dragOrigin captured on Shift-press");

        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 0, null, "Left", Map.of(), true));
        check(storage.getItemStack((short) 0) == null, "item transferred out of storage slot 0");
        check(backpack.getItemStack((short) 0) != null && backpack.getItemStack((short) 0).getQuantity() == 10,
                "item transferred into backpack slot 0");
        check(refreshed.get(), "refresh requested after transfer into backpack");

        // 2. Transfer back: Shift click on backpack slot 0:
        refreshed.set(false);
        displayed.put(NativeInventorySection.BACKPACK, new ItemStack[]{ backpack.getItemStack((short) 0), null, null, null });
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ null, null, null, null });

        controller.handleEvent(context, new InventoryContentEvent("DragSource", "BACKPACK", 0, null, "Left", Map.of(), true));
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "BACKPACK", 0, null, "Left", Map.of(), true));

        check(backpack.getItemStack((short) 0) == null, "item transferred out of backpack slot 0");
        check(storage.getItemStack((short) 0) != null && storage.getItemStack((short) 0).getQuantity() == 10,
                "item transferred back into storage slot 0");
        check(refreshed.get(), "refresh requested after transfer back to storage");
    }

    private static void exerciseSameSlotDropClearsSelection() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Iron", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);

        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Pick up item from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));
        check(get(controller, "dragOrigin") != null, "dragOrigin captured on click");

        // 2. Wait to avoid double-click interval
        try { Thread.sleep(450); } catch (InterruptedException ignored) {}

        // 3. Drop back into slot 0:
        var dragData = new com.supremosan.custominventory.api.InventoryDragData(
                NativeInventorySection.STORAGE.id(), 0, null, null, "Test_Iron", 10, null, null);
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 0, dragData, "Left", Map.of(), false));

        check(get(controller, "dragOrigin") == null, "dragOrigin cleared after dropping back on same slot");
        check(get(controller, "selection") == null, "selection cleared after dropping back on same slot");
        Set<NativeInventorySection> released = get(controller, "releasedSources");
        check(released.contains(NativeInventorySection.STORAGE), "storage marked in releasedSources so client clears cursor");
        check(refreshed.get(), "refresh requested on same-slot drop");
        check(storage.getItemStack((short) 0) != null && storage.getItemStack((short) 0).getQuantity() == 10,
                "item still intact in original slot");
    }

    private static void exerciseCombineItemStacks() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var hotbar = new SimpleItemContainer((short) 4);
        var item1 = new DetachedTestStack("Test_Stone", 5, null);
        var item2 = new DetachedTestStack("Test_Stone", 10, null);
        var item3 = new DetachedTestStack("Test_Stone", 15, null);
        storage.setItemStackForSlot((short) 0, item1);
        storage.setItemStackForSlot((short) 1, item2);
        hotbar.setItemStackForSlot((short) 0, item3);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        containers.put(NativeInventorySection.HOTBAR, hotbar);

        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item1, item2, null, null });
        displayed.put(NativeInventorySection.HOTBAR, new ItemStack[]{ item3, null, null, null });

        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // Double click on storage slot 0: combines matching items (5 + 10 + 15 = 30) into slot 0
        controller.handleEvent(context, new InventoryContentEvent("DoubleClickSlot", "STORAGE", 0));
        check(storage.getItemStack((short) 0) != null && storage.getItemStack((short) 0).getQuantity() == 30,
                "DoubleClickSlot grouped matching items into target slot");
        check(storage.getItemStack((short) 1) == null, "merged item removed from storage slot 1");
        check(hotbar.getItemStack((short) 0) == null, "merged item removed from hotbar slot 0");
        check(refreshed.get(), "refresh requested after double-click combine");
    }

    private static void exerciseRapidSameSlotClickGroupsItems() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item1 = new DetachedTestStack("Test_Wood", 5, null);
        var item2 = new DetachedTestStack("Test_Wood", 20, null);
        storage.setItemStackForSlot((short) 0, item1);
        storage.setItemStackForSlot((short) 1, item2);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);

        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item1, item2, null, null });

        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Pick up item from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));

        // 2. Click same slot immediately (within 400ms): triggers grouping
        var dragData = new com.supremosan.custominventory.api.InventoryDragData(
                NativeInventorySection.STORAGE.id(), 0, null, null, "Test_Wood", 5, null, null);
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 0, dragData, "Left", Map.of(), false));

        check(storage.getItemStack((short) 0) != null && storage.getItemStack((short) 0).getQuantity() == 25,
                "rapid same-slot click grouped matching items into slot 0");
        check(storage.getItemStack((short) 1) == null, "merged item removed from slot 1");
        check(get(controller, "dragOrigin") == null, "dragOrigin cleared after grouping");
    }

    private static void exerciseRightClickSplit() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Arrows", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        // Shift + right-click without explicit client drag metadata splits half (5).
        controller.handleEvent(null, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Right", Map.of(), true));
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 5, "Shift + right-click split captured half the stack (5 of 10)");
    }

    private static void exerciseNormalRightPickupTakesOne() {
        var fixture = new GestureFixture(10, false);
        fixture.event("CancelDrag", NativeInventorySection.STORAGE, 0, null);
        fixture.jsonEvent("DragSource", 0, "\"PressedMouseButton\":3,\"ShiftHeld\":false");
        check(fixture.held() == 1, "normal Noesis right pickup takes exactly one item from a stack");
        check(fixture.quantity(fixture.storage, 0) == 10, "normal right pickup leaves authoritative source intact until placement");
        fixture.event("Drop", NativeInventorySection.STORAGE, 1, "Left");
        check(fixture.quantity(fixture.storage, 0) == 9 && fixture.quantity(fixture.storage, 1) == 1,
                "placing a normal right pickup moves one and preserves nine unheld source items");
        check(fixture.held() == 0, "placing the one-item right pickup ends holding");
    }

    private static void exerciseExplicitRightPickupQuantity() {
        var fixture = new GestureFixture(10, false);
        fixture.event("CancelDrag", NativeInventorySection.STORAGE, 0, null);
        fixture.controller.handleEvent(fixture.context, new InventoryContentEvent("DragSource", "STORAGE", 0,
                fixture.drag(1), "Right", Map.of(), true));
        check(fixture.held() == 1, "explicit client pickup quantity one overrides Shift + right half-stack fallback");
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        check(fixture.quantity(fixture.storage, 0) == 9 && fixture.quantity(fixture.storage, 1) == 1,
                "explicit one-item pickup is distributed without consuming the unheld source remainder");
        check(fixture.held() == 0, "explicit one-item pickup ends holding after one right placement");

        var regular = new GestureFixture(10, false);
        regular.event("CancelDrag", NativeInventorySection.STORAGE, 0, null);
        regular.controller.handleEvent(regular.context, new InventoryContentEvent("DragSource", "STORAGE", 0,
                regular.drag(7), "Right", Map.of(), false));
        check(regular.held() == 1, "oversized source metadata cannot turn normal right pickup into a multi-item pickup");
    }

    private static void exerciseDecodedCurrentClickPlacement() {
        var fixture = new GestureFixture(8, false);
        String currentRight = "\"PressedMouseButton\":1,\"DragPressedMouseButton\":1,"
                + "\"MouseButton\":\"Left\",\"ClickMouseButton\":3";
        fixture.jsonEvent("DragPress", 1, currentRight);
        check(fixture.quantity(fixture.storage, 0) == 7 && fixture.quantity(fixture.storage, 1) == 1,
                "decoded right click places one despite left drag-origin metadata");
        check(fixture.held() == 7, "decoded right click retains the other seven items on the cursor");
        fixture.jsonEvent("CompleteSourceRelease", 1, currentRight);
        fixture.jsonEvent("Drop", 1, currentRight);
        check(fixture.quantity(fixture.storage, 1) == 1 && fixture.held() == 7,
                "decoded right terminal callbacks neither dump nor duplicate the held remainder");

        fixture.jsonEvent("Drop", 2, "\"PressedMouseButton\":3,\"DragPressedMouseButton\":3,"
                + "\"MouseButton\":2,\"ClickMouseButton\":1");
        check(fixture.quantity(fixture.storage, 0) == 0 && fixture.quantity(fixture.storage, 2) == 7,
                "decoded current left click places all despite right drag-origin metadata");
        check(fixture.held() == 0, "decoded left click ends holding after placing the full remainder");

        var middle = new GestureFixture(8, false);
        String currentMiddle = "\"PressedMouseButton\":1,\"DragPressedMouseButton\":1,\"ClickMouseButton\":2";
        middle.jsonEvent("DragPress", 1, currentMiddle);
        middle.jsonEvent("CompleteSourceRelease", 1, currentMiddle);
        middle.jsonEvent("Drop", 1, currentMiddle);
        check(middle.quantity(middle.storage, 0) == 8 && middle.quantity(middle.storage, 1) == 0 && middle.held() == 8,
                "Noesis middle press, release and drop neither distribute one item nor place the entire stack");
    }

    private static void exerciseDecodedButtonlessDropRetainsRemainder() {
        String[] dropPayloads = {
                "\"DragPressedMouseButton\":1",
                "\"PressedMouseButton\":1,\"SourceInventorySectionId\":" + NativeInventorySection.STORAGE.id()
                        + ",\"SourceSlotId\":0,\"ItemStackQuantity\":8"
        };
        for (String dropPayload : dropPayloads) {
            for (boolean releaseFirst : new boolean[]{false, true}) {
                var fixture = new GestureFixture(8, false);
                String currentRight = "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3";
                fixture.jsonEvent("DragPress", 1, currentRight);
                if (releaseFirst) fixture.jsonEvent("CompleteSourceRelease", 1, currentRight);
                // Native Dropped reports the original press, source slot and
                // source quantity without a current ClickMouseButton field.
                fixture.jsonEvent("Drop", 1, dropPayload);
                if (!releaseFirst) fixture.jsonEvent("CompleteSourceRelease", 1, currentRight);
                check(fixture.quantity(fixture.storage, 0) == 7 && fixture.quantity(fixture.storage, 1) == 1,
                        "native Dropped left-origin metadata preserves a right-click remainder in either callback order: " + dropPayload);
                check(fixture.held() == 7,
                        "buttonless native Dropped retains seven held items with releaseFirst=" + releaseFirst);
            }
        }
    }

    private static void exerciseDecodedRightClickHoldingAndDragSweep() {
        var fixture = new GestureFixture(10, false);
        // Holding 10 items from slot 0 on cursor (picked up with Left click: DragPressedMouseButton 1)
        // 1. Right click on slot 1: DragPress with ClickMouseButton 3
        String rightClickSlot1 = "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3,"
                + "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,\"DragItemStackQuantity\":10";
        fixture.jsonEvent("DragPress", 1, rightClickSlot1);
        check(fixture.quantity(fixture.storage, 0) == 9 && fixture.quantity(fixture.storage, 1) == 1,
                "right click holding an item deposits one unit into target slot");
        check(fixture.held() == 9, "cursor holds remaining 9 units");
        fixture.jsonEvent("CompleteSourceRelease", 1, rightClickSlot1);
        check(fixture.quantity(fixture.storage, 1) == 1 && fixture.held() == 9,
                "completing right release retains single deposited item");

        // 2. Drag across slots 2, 3 and 4 while holding right button
        String rightDragSlot2 = "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3,"
                + "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,\"DragItemStackQuantity\":9";
        fixture.jsonEvent("DragPress", 2, rightDragSlot2);
        check(fixture.quantity(fixture.storage, 2) == 1 && fixture.held() == 8,
                "right drag press deposits one into slot 2");

        // Hover across slot 3 without button fields (standard HoverSource)
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 3, null);
        check(fixture.quantity(fixture.storage, 3) == 1 && fixture.held() == 7,
                "right drag sweep deposits one into swept slot 3");

        // Hover across slot 4
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 4, null);
        check(fixture.quantity(fixture.storage, 4) == 1 && fixture.held() == 6,
                "right drag sweep deposits one into swept slot 4");

        // Release right button on slot 4
        fixture.jsonEvent("CompleteSourceRelease", 4, "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3");
        check(fixture.held() == 6, "cursor retains 6 items after right sweep ends");

        // Hovering next slot without right press does NOT deposit
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 1, null);
        check(fixture.quantity(fixture.storage, 1) == 1 && fixture.held() == 6,
                "hover after right release does not deposit further items");
    }

    private static void exerciseLiveClientDragWithoutPriorDragSource() {
        var fixture = new GestureFixture(92, false, false);
        check(fixture.held() == 0, "live client gesture starts without prior DragSource");

        // 1. Single right click deposit into slot 1 (first click: DragPress + CompleteSourceRelease)
        String rightClickSlot1 = "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Test_Gesture\",\"DragItemStackQuantity\":92,"
                + "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3,\"ClickCount\":1";
        fixture.jsonEvent("DragPress", 1, rightClickSlot1);
        check(fixture.quantity(fixture.storage, 0) == 91 && fixture.quantity(fixture.storage, 1) == 1,
                "first right click deposit without DragSource moves 1 unit to slot 1");
        check(fixture.held() == 91, "held origin initialized and decremented to 91 units");

        fixture.jsonEvent("CompleteSourceRelease", 1, rightClickSlot1);
        check(fixture.quantity(fixture.storage, 1) == 1 && fixture.held() == 91,
                "release retains deposited item and carried remainder");

        // 2. Repeated right click on slot 1 (second click)
        String rightClickSlot1Second = "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Test_Gesture\",\"DragItemStackQuantity\":92,"
                + "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3,\"ClickCount\":2";
        fixture.jsonEvent("DragPress", 1, rightClickSlot1Second);
        check(fixture.quantity(fixture.storage, 0) == 90 && fixture.quantity(fixture.storage, 1) == 2,
                "second right click deposit into same slot increases slot 1 to 2 units");
        check(fixture.held() == 90, "held remainder decremented to 90 units");

        fixture.jsonEvent("CompleteSourceRelease", 1, rightClickSlot1Second);

        // 3. Repeated right click on slot 1 (third click)
        String rightClickSlot1Third = "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Test_Gesture\",\"DragItemStackQuantity\":92,"
                + "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3,\"ClickCount\":3";
        fixture.jsonEvent("DragPress", 1, rightClickSlot1Third);
        check(fixture.quantity(fixture.storage, 0) == 89 && fixture.quantity(fixture.storage, 1) == 3,
                "third right click deposit into same slot increases slot 1 to 3 units");
        check(fixture.held() == 89, "held remainder decremented to 89 units");

        fixture.jsonEvent("CompleteSourceRelease", 1, rightClickSlot1Third);

        // 4. Right click sweep across slots 2, 3 and 4
        String rightSweepStartSlot2 = "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Test_Gesture\",\"DragItemStackQuantity\":89,"
                + "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3";
        fixture.jsonEvent("DragPress", 2, rightSweepStartSlot2);
        check(fixture.quantity(fixture.storage, 2) == 1 && fixture.held() == 88,
                "right drag press on slot 2 deposits 1 unit and begins sweep");

        // Sweeping hover over slot 3
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 3, null);
        check(fixture.quantity(fixture.storage, 3) == 1 && fixture.held() == 87,
                "sweep hover over slot 3 deposits 1 unit");

        // Sweeping hover over slot 4
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 4, null);
        check(fixture.quantity(fixture.storage, 4) == 1 && fixture.held() == 86,
                "sweep hover over slot 4 deposits 1 unit");

        // Release right button on slot 4
        fixture.jsonEvent("CompleteSourceRelease", 4, "\"DragPressedMouseButton\":1,\"ClickMouseButton\":3");
        check(fixture.held() == 86, "releasing right button ends sweep and retains 86 held units");

        // Hovering after right release does not deposit
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 1, null);
        check(fixture.quantity(fixture.storage, 1) == 3 && fixture.held() == 86,
                "hover after right release does not deposit additional units");
    }

    private static void exerciseLiveClientSplitDragConservation() {
        var fixture = new GestureFixture(91, false, false);
        check(fixture.held() == 0, "starts with no held origin");

        // Client right-click split picked up 45 items from slot 0 (leaving 46).
        // First deposit into slot 1 via DragPress:
        String rightSplitPressSlot1 = "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Test_Gesture\",\"DragItemStackQuantity\":45,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":3,\"ClickCount\":1";
        fixture.jsonEvent("DragPress", 1, rightSplitPressSlot1);
        check(fixture.quantity(fixture.storage, 0) == 90 && fixture.quantity(fixture.storage, 1) == 1,
                "split right click deposits 1 unit to slot 1 and decrements slot 0");
        check(fixture.held() == 44, "held origin initialized from split quantity 45 and decremented to 44");

        // Sweep to slot 2 while right button held
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 2, null);
        check(fixture.quantity(fixture.storage, 0) == 89 && fixture.quantity(fixture.storage, 2) == 1,
                "sweep hover deposits 1 unit to slot 2 and decrements source");
        check(fixture.held() == 43, "held remainder is 43 units");

        // Release right button on slot 2
        fixture.jsonEvent("CompleteSourceRelease", 2, "\"DragPressedMouseButton\":3,\"ClickMouseButton\":3");
        check(fixture.held() == 43, "held remainder of 43 units retained after right release");

        // Drop remaining 43 units into slot 3 with Left click
        String leftDropPressSlot3 = "\"DragSourceSlotId\":0,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Test_Gesture\",\"DragItemStackQuantity\":43,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":1";
        fixture.jsonEvent("DragPress", 3, leftDropPressSlot3);
        String dropPayloadSlot3 = "\"SourceSlotId\":0,\"SourceInventorySectionId\":-2,"
                + "\"ItemStackId\":\"Test_Gesture\",\"ItemStackQuantity\":43,"
                + "\"PressedMouseButton\":1";
        fixture.jsonEvent("Drop", 3, dropPayloadSlot3);
        check(fixture.quantity(fixture.storage, 3) == 43, "left drop places full 43 units into slot 3");
        check(fixture.held() == 0, "holding state ended after placing remainder");

        // Total item conservation check across all slots:
        // Slot 0: 46 (91 - 1 - 1 - 43)
        // Slot 1: 1
        // Slot 2: 1
        // Slot 3: 43
        check(fixture.quantity(fixture.storage, 0) == 46, "unheld source slot 0 retains exactly 46 items");
        int totalItems = fixture.quantity(fixture.storage, 0)
                + fixture.quantity(fixture.storage, 1)
                + fixture.quantity(fixture.storage, 2)
                + fixture.quantity(fixture.storage, 3);
        check(totalItems == 91, "exact inventory conservation: 46 + 1 + 1 + 43 = 91 items, zero duplicated or lost");
    }

    private static void exerciseLiveClientSplitDragGhostItemFix() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 16);
        var hotbar = new SimpleItemContainer((short) 5);
        var item = new DetachedTestStack("Wood_Oak_Trunk", 92, null);
        storage.setItemStackForSlot((short) 2, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        containers.put(NativeInventorySection.HOTBAR, hotbar);

        var displayedStorage = new ItemStack[16];
        displayedStorage[2] = item;
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, displayedStorage);
        displayed.put(NativeInventorySection.HOTBAR, new ItemStack[5]);

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> {});

        java.util.function.BiConsumer<String, String> send = (contentAction, fields) -> {
            String json = "{\"Action\":\"Content\",\"PageId\":\"@inventory:panels\",\"ContentAction\":\"" + contentAction
                    + "\",\"Payload\":\"STORAGE\"," + fields + "}";
            try {
                var decoded = com.supremosan.custominventory.ui.InventoryShellPage.Event.CODEC.decodeJson(
                        new com.hypixel.hytale.codec.util.RawJsonReader(json.toCharArray()),
                        com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get());
                controller.handleEvent(context, new InventoryContentEvent(decoded.contentAction, decoded.payload,
                        decoded.slotIndex, decoded.dragData(), decoded.resolvedMouseButton(),
                        decoded.formValues(), decoded.shiftHeld));
            } catch (Exception failure) {
                throw new AssertionError("Failed: " + json, failure);
            }
        };

        // 1. Right-click split on slot 2 produces live Noesis packets over slot 3:
        // DragPress on slot 3 with DragPressedMouseButton 3, ClickMouseButton 1
        String dragPressSlot3 = "\"SlotIndex\":3,\"DragSourceSlotId\":2,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Wood_Oak_Trunk\",\"DragItemStackQuantity\":46,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":1,\"ClickCount\":1";
        send.accept("DragPress", dragPressSlot3);
        // Followed by CompleteSourceRelease on slot 3 with DragPressedMouseButton 3, ClickMouseButton 1
        send.accept("CompleteSourceRelease", dragPressSlot3);

        // Slot 3 must remain empty; CompleteSourceRelease must NOT dump held split stack
        check(storage.getItemStack((short) 3) == null, "slot 3 remains empty; CompleteSourceRelease does not dump held split stack");
        check(storage.getItemStack((short) 2).getQuantity() == 92, "source slot 2 retains all 92 items");
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 46, "cursor maintains 46 held items");

        // 2. Right-click deposit into slot 12:
        // Noesis emits DragPress (ClickMouseButton 1) then CompleteSourceRelease (ClickMouseButton 1)
        String dragPressSlot12Left = "\"SlotIndex\":12,\"DragSourceSlotId\":2,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Wood_Oak_Trunk\",\"DragItemStackQuantity\":46,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":1,\"ClickCount\":1";
        send.accept("DragPress", dragPressSlot12Left);
        send.accept("CompleteSourceRelease", dragPressSlot12Left);
        // Followed by actual right click: DragPress (ClickMouseButton 3) and CompleteSourceRelease (ClickMouseButton 3)
        String dragPressSlot12Right = "\"SlotIndex\":12,\"DragSourceSlotId\":2,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Wood_Oak_Trunk\",\"DragItemStackQuantity\":46,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":3,\"ClickCount\":1";
        send.accept("DragPress", dragPressSlot12Right);
        send.accept("CompleteSourceRelease", dragPressSlot12Right);

        check(storage.getItemStack((short) 12) != null && storage.getItemStack((short) 12).getQuantity() == 1,
                "right click on slot 12 deposits exactly 1 item");
        check(storage.getItemStack((short) 2).getQuantity() == 91, "source slot 2 decremented to 91");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 45, "cursor holds 45 remaining items");

        // 3. Right-click deposit into slot 13:
        String dragPressSlot13Right = "\"SlotIndex\":13,\"DragSourceSlotId\":2,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Wood_Oak_Trunk\",\"DragItemStackQuantity\":46,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":3,\"ClickCount\":1";
        send.accept("DragPress", dragPressSlot13Right);
        send.accept("CompleteSourceRelease", dragPressSlot13Right);

        check(storage.getItemStack((short) 13) != null && storage.getItemStack((short) 13).getQuantity() == 1,
                "right click on slot 13 deposits exactly 1 item");
        check(storage.getItemStack((short) 2).getQuantity() == 90, "source slot 2 decremented to 90");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 44, "cursor holds 44 remaining items");

        // 4. Right-click sweep across slot 4:
        String dragPressSlot4Right = "\"SlotIndex\":4,\"DragSourceSlotId\":2,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Wood_Oak_Trunk\",\"DragItemStackQuantity\":46,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":3,\"ClickCount\":1";
        send.accept("DragPress", dragPressSlot4Right);
        send.accept("CompleteSourceRelease", dragPressSlot4Right);

        check(storage.getItemStack((short) 4) != null && storage.getItemStack((short) 4).getQuantity() == 1,
                "right click on slot 4 deposits exactly 1 item");
        check(storage.getItemStack((short) 2).getQuantity() == 89, "source slot 2 decremented to 89");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 43, "cursor holds 43 remaining items");

        // 5. Holding right-click and sweeping across slot 5 and slot 6:
        String dragPressSlot5Start = "\"SlotIndex\":5,\"DragSourceSlotId\":2,\"DragSourceInventorySectionId\":-2,"
                + "\"DragItemStackId\":\"Wood_Oak_Trunk\",\"DragItemStackQuantity\":43,"
                + "\"DragPressedMouseButton\":3,\"ClickMouseButton\":3,\"ClickCount\":1";
        send.accept("DragPress", dragPressSlot5Start);
        check(storage.getItemStack((short) 5) != null && storage.getItemStack((short) 5).getQuantity() == 1,
                "right drag press on slot 5 deposits 1 item and starts sweep");
        check(storage.getItemStack((short) 2).getQuantity() == 88, "source slot 2 decremented to 88");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 42, "cursor holds 42 remaining items");

        // Hover across slot 6 with standard HoverSource without button fields:
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 6, null));
        check(storage.getItemStack((short) 6) != null && storage.getItemStack((short) 6).getQuantity() == 1,
                "holding right click and sweeping across slot 6 deposits 1 item");
        check(storage.getItemStack((short) 2).getQuantity() == 87, "source slot 2 decremented to 87");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 41, "cursor holds 41 remaining items");

        // Release right button on slot 6:
        send.accept("CompleteSourceRelease", "\"SlotIndex\":6,\"DragPressedMouseButton\":3,\"ClickMouseButton\":3");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 41, "releasing right button ends sweep and retains 41 items on cursor");

        // Moving mouse to slot 7 after release without holding right button does NOT deposit:
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 7, null));
        check(storage.getItemStack((short) 7) == null, "hovering slot 7 after right release does not deposit items");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 41, "cursor still holds 41 items");

        // Total inventory conservation check:
        // Slot 2: 87, Slot 12: 1, Slot 13: 1, Slot 4: 1, Slot 5: 1, Slot 6: 1. Sum in container = 92 items.
        int totalInContainer = storage.getItemStack((short) 2).getQuantity()
                + storage.getItemStack((short) 12).getQuantity()
                + storage.getItemStack((short) 13).getQuantity()
                + storage.getItemStack((short) 4).getQuantity()
                + storage.getItemStack((short) 5).getQuantity()
                + storage.getItemStack((short) 6).getQuantity();
        check(totalInContainer == 92, "exact total container item conservation: 92 items");
    }

    private static void exerciseDestinationReleaseTransfersItem() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Stone", 8, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Pick up item with left click on slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));
        check(get(controller, "dragOrigin") != null, "dragOrigin captured on pick up");

        // 2. Release over destination slot 1
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 1, null, "Left", Map.of(), false));
        check(storage.getItemStack((short) 0) == null, "item removed from source slot 0 on destination release");
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 8,
                "item placed into destination slot 1 on release");
        check(get(controller, "dragOrigin") == null, "dragOrigin cleared after destination release");
        check(refreshed.get(), "refresh requested after destination release move");
    }

    private static void exerciseRightClickSingleDeposit() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Iron", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Pick up stack from slot 0 with left click
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 10, "picked up 10 items on cursor");

        // 2. Right click press on empty slot 1
        refreshed.set(false);
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 0) != null && storage.getItemStack((short) 0).getQuantity() == 9, "source slot 0 decremented to 9");
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 1, "target slot 1 received 1 item");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 9, "cursor holds 9 remaining items after first right click");
        check(refreshed.get(), "refresh requested after right click deposit");

        // 3. Right click release on slot 1
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 1, null, "Right", Map.of(), false));
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 9, "cursor still holds 9 items after right release");
        check(storage.getItemStack((short) 1).getQuantity() == 1, "target slot 1 still has 1 item after release");

        // 4. Right click press on slot 1 again (depositing 2nd item into same slot)
        refreshed.set(false);
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 0).getQuantity() == 8, "source slot 0 decremented to 8");
        check(storage.getItemStack((short) 1).getQuantity() == 2, "target slot 1 increased to 2 items");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 8, "cursor holds 8 remaining items after second right click");
        check(refreshed.get(), "refresh requested after second right click deposit");

        // 5. Right click release
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 1, null, "Right", Map.of(), false));
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 8, "cursor still holds 8 items");
    }

    private static void exerciseRightClickDepositSameSlot() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Gold", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Pick up 10 items from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));

        // 2. Right click on slot 0 (putting 1 item back into the source slot)
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 0, null, "Right", Map.of(), false));
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 9, "cursor holds 9 items after depositing 1 back to slot 0");
        check(refreshed.get(), "refresh requested on same slot deposit");

        // Release right click
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 0, null, "Right", Map.of(), false));

        // 3. Drop remaining 9 items into slot 1
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 1, null, "Left", Map.of(), false));
        check(storage.getItemStack((short) 0) != null && storage.getItemStack((short) 0).getQuantity() == 1,
                "exactly 1 item remained in slot 0");
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 9,
                "slot 1 received 9 dropped items");
        check(get(controller, "dragOrigin") == null, "cursor empty after dropping remainder");
    }

    private static void exerciseRightClickSweepMultiSlot() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 5);
        var item = new DetachedTestStack("Test_Diamond", 5, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Pick up stack of 5 from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));

        // 2. Right click press on slot 1: deposits 1 into slot 1, begins rightSweep
        refreshed.set(false);
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 1, "slot 1 received 1 item on press");
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 4, "cursor holds 4 items after press");
        check(refreshed.get(), "refresh requested on press");

        // 3. Hover sweep over slot 2 (without releasing right button)
        refreshed.set(false);
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 2, null, null, Map.of(), false));
        check(storage.getItemStack((short) 2) != null && storage.getItemStack((short) 2).getQuantity() == 1, "slot 2 received 1 item on hover sweep");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 3, "cursor holds 3 items after slot 2");
        check(refreshed.get(), "refresh requested on slot 2 hover");

        // 4. Hover sweep over slot 3
        refreshed.set(false);
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 3, null, null, Map.of(), false));
        check(storage.getItemStack((short) 3) != null && storage.getItemStack((short) 3).getQuantity() == 1, "slot 3 received 1 item on hover sweep");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 2, "cursor holds 2 items after slot 3");
        check(refreshed.get(), "refresh requested on slot 3 hover");

        // 5. Hover sweep back over slot 3 again: should NOT deposit a second time in same sweep
        refreshed.set(false);
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 3, null, null, Map.of(), false));
        check(storage.getItemStack((short) 3).getQuantity() == 1, "slot 3 not duplicated on re-entry during same sweep");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 2, "cursor still holds 2 items");

        // 6. Release right click
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 3, null, "Right", Map.of(), false));
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 2, "cursor retains 2 items on release");

        // 7. Left click drop remaining 2 items into slot 4
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 4, null, "Left", Map.of(), false));
        check(storage.getItemStack((short) 0) == null, "source slot 0 is completely empty");
        check(storage.getItemStack((short) 1).getQuantity() == 1, "slot 1 has 1 item");
        check(storage.getItemStack((short) 2).getQuantity() == 1, "slot 2 has 1 item");
        check(storage.getItemStack((short) 3).getQuantity() == 1, "slot 3 has 1 item");
        check(storage.getItemStack((short) 4).getQuantity() == 2, "slot 4 has 2 remaining items");
        check(get(controller, "dragOrigin") == null, "cursor cleared after final drop");
    }

    private static void exerciseRightClickSplitAndDeposit() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Coal", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Shift + right click on slot 0 splits half (5) into cursor.
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Right", Map.of(), true));
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 5, "cursor holds split 5 of 10 items");

        // 2. Right click on empty slot 1 deposits 1 of the split items
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 0) != null && storage.getItemStack((short) 0).getQuantity() == 9,
                "slot 0 has 9 items left in container");
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 1,
                "slot 1 received 1 item");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 4, "cursor holds 4 split items");
    }

    private static void exerciseRightClickSweepReentry() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 5);
        var item = new DetachedTestStack("Test_Iron", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var refreshed = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> refreshed.set(true));

        // 1. Pick up stack of 10 from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));

        // 2. Right click press on slot 1: deposits 1 into slot 1, begins rightSweep
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 1, "slot 1 received 1 item on press");
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 9, "cursor holds 9 items");

        // Mouse leaves slot 1
        controller.handleEvent(context, new InventoryContentEvent("UnhoverSource", "STORAGE", 1, null, null, Map.of(), false));

        // 3. Hover over slot 2: deposits 1 into slot 2
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 2, null, null, Map.of(), false));
        check(storage.getItemStack((short) 2) != null && storage.getItemStack((short) 2).getQuantity() == 1, "slot 2 received 1 item");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 8, "cursor holds 8 items");

        // 4. Hover over slot 2 again without leaving: must NOT deposit second item
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 2, null, null, Map.of(), false));
        check(storage.getItemStack((short) 2).getQuantity() == 1, "slot 2 unchanged without exiting");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 8, "cursor still holds 8 items");

        // 5. Unhover slot 2 (mouse exits slot 2)
        controller.handleEvent(context, new InventoryContentEvent("UnhoverSource", "STORAGE", 2, null, null, Map.of(), false));

        // 6. Hover back onto slot 2: deposits another item because cursor left and re-entered
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 2, null, null, Map.of(), false));
        check(storage.getItemStack((short) 2).getQuantity() == 2, "slot 2 received second item after re-entry");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 7, "cursor holds 7 items after re-entry deposit");

        // 7. Unhover slot 2, then enter slot 1: deposits second item into slot 1 after re-entry
        controller.handleEvent(context, new InventoryContentEvent("UnhoverSource", "STORAGE", 2, null, null, Map.of(), false));
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 1, null, null, Map.of(), false));
        check(storage.getItemStack((short) 1).getQuantity() == 2, "slot 1 received second item after re-entry");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 6, "cursor holds 6 items");

        // 8. Release right click
        controller.handleEvent(context, new InventoryContentEvent("CompleteSourceRelease", "STORAGE", 1, null, "Right", Map.of(), false));
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 6, "cursor retains 6 items on release");
    }

    private static void exerciseInteractionHintsVisibility() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Gold", 2, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> {});

        // 1. Initial idle state: InactiveKeybinds visible, ActiveKeybinds hidden
        var commands = new UICommandBuilder();
        controller.refreshDropAction(context, commands);
        boolean sawInactiveTrue = false;
        boolean sawScopedInactiveTrue = false;
        boolean sawActiveFalse = false;
        boolean sawScopedActiveFalse = false;
        for (var cmd : commands.getCommands()) {
            if ("#InactiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("true")) sawInactiveTrue = true;
            if ("#InventoryHelpHints #InactiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("true")) sawScopedInactiveTrue = true;
            if ("#ActiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawActiveFalse = true;
            if ("#InventoryHelpHints #ActiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawScopedActiveFalse = true;
        }
        check(sawInactiveTrue, "idle hints show InactiveKeybinds");
        check(sawScopedInactiveTrue, "idle hints show scoped InactiveKeybinds");
        check(sawActiveFalse, "idle hints hide ActiveKeybinds");
        check(sawScopedActiveFalse, "idle hints hide scoped ActiveKeybinds");

        // 2. Pick up item stack (DragSource): InactiveKeybinds hidden, ActiveKeybinds visible
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));
        commands = new UICommandBuilder();
        controller.refreshDropAction(context, commands);
        boolean sawInactiveFalse = false;
        boolean sawScopedInactiveFalse = false;
        boolean sawActiveTrue = false;
        boolean sawScopedActiveTrue = false;
        boolean sawPlaceAllText = false;
        boolean sawDistributeOneText = false;
        boolean sawDropActiveEnabled = false;
        for (var cmd : commands.getCommands()) {
            if ("#InactiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawInactiveFalse = true;
            if ("#InventoryHelpHints #InactiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawScopedInactiveFalse = true;
            if ("#ActiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("true")) sawActiveTrue = true;
            if ("#InventoryHelpHints #ActiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("true")) sawScopedActiveTrue = true;
            if ("#InventoryHelpHints #InventoryHelpPlaceAll #Name.Text".equals(cmd.selector)) sawPlaceAllText = true;
            if ("#InventoryHelpHints #InventoryHelpDistributeOne #Name.Text".equals(cmd.selector)) sawDistributeOneText = true;
            if ("#InventoryHelpHints #InventoryDropButtonActive.Disabled".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawDropActiveEnabled = true;
        }
        check(sawInactiveFalse, "held item hints hide InactiveKeybinds");
        check(sawScopedInactiveFalse, "held item hints hide scoped InactiveKeybinds");
        check(sawActiveTrue, "held item hints show ActiveKeybinds");
        check(sawScopedActiveTrue, "held item hints show scoped ActiveKeybinds");
        check(sawPlaceAllText, "held item hints emit scoped PlaceAll text");
        check(sawDistributeOneText, "held item hints emit scoped DistributeOne text");
        check(sawDropActiveEnabled, "held item enables active drop button");

        // 3. Deposit 1 of 2: still holding 1 item -> hints remain active (no state change emitted)
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 1, "holding 1 remaining item");
        commands = new UICommandBuilder();
        controller.refreshDropAction(context, commands);
        boolean hintsEmitted = false;
        for (var cmd : commands.getCommands()) {
            if (cmd.selector != null && (cmd.selector.contains("InactiveKeybinds") || cmd.selector.contains("ActiveKeybinds"))) {
                hintsEmitted = true;
            }
        }
        check(!hintsEmitted, "no hint toggle emitted while remaining in active state");

        // 4. Deposit final item (DragPress on slot 2): held stack reaches 0 -> ends drag, restores inactive hints
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 2, null, "Right", Map.of(), false));
        origin = get(controller, "dragOrigin");
        check(origin == null, "held stack empty after depositing last item");
        commands = new UICommandBuilder();
        controller.refreshDropAction(context, commands);
        sawInactiveTrue = false;
        sawScopedInactiveTrue = false;
        sawActiveFalse = false;
        sawScopedActiveFalse = false;
        for (var cmd : commands.getCommands()) {
            if ("#InactiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("true")) sawInactiveTrue = true;
            if ("#InventoryHelpHints #InactiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("true")) sawScopedInactiveTrue = true;
            if ("#ActiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawActiveFalse = true;
            if ("#InventoryHelpHints #ActiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawScopedActiveFalse = true;
        }
        check(sawInactiveTrue, "hints revert InactiveKeybinds to true after emptying held stack");
        check(sawScopedInactiveTrue, "hints revert scoped InactiveKeybinds to true after emptying held stack");
        check(sawActiveFalse, "hints revert ActiveKeybinds to false after emptying held stack");
        check(sawScopedActiveFalse, "hints revert scoped ActiveKeybinds to false after emptying held stack");

        // 5. Holding item when source slot was emptied into cursor (dragOrigin reconstructed via DragPress)
        storage.setItemStackForSlot((short) 0, null);
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ null, null, null, null });
        var emptySlotDrag = new com.supremosan.custominventory.api.InventoryDragData(
                NativeInventorySection.STORAGE.id(), 0, null, null, "Test_Gold", 5, null, null);
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, emptySlotDrag, "1", Map.of(), false));
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 5 && "Test_Gold".equals(origin.itemId()),
                "dragOrigin reconstructed from drag metadata when container slot is empty");
        commands = new UICommandBuilder();
        controller.refreshDropAction(context, commands);
        boolean sawEmptySlotActiveTrue = false;
        boolean sawEmptySlotDropActiveEnabled = false;
        for (var cmd : commands.getCommands()) {
            if ("#InventoryHelpHints #ActiveKeybinds.Visible".equals(cmd.selector) && cmd.data != null && cmd.data.contains("true")) sawEmptySlotActiveTrue = true;
            if ("#InventoryHelpHints #InventoryDropButtonActive.Disabled".equals(cmd.selector) && cmd.data != null && cmd.data.contains("false")) sawEmptySlotDropActiveEnabled = true;
        }
        check(sawEmptySlotActiveTrue, "hints switch to active when holding stack even if source slot was emptied");
        check(sawEmptySlotDropActiveEnabled, "holding cursor stack enables drop button even when source container slot is empty");
    }

    private static void exerciseRightClickDropTransfersOne() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Iron", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> {});

        // 1. Pick up stack of 10 from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 10, "held stack of 10 items");

        // 2. Right-click Drop onto slot 1: deposits exactly 1 item (leaving 9 on cursor)
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 1,
                "right-click Drop deposited exactly 1 item in slot 1");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 9, "cursor holds 9 items after right-click Drop");

        // 3. Right-click Drop again onto slot 1 with enum integer "2": deposits another 1 item
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 1, null, "2", Map.of(), false));
        check(storage.getItemStack((short) 1).getQuantity() == 2,
                "right-click Drop with button '2' deposited second item in slot 1");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 8, "cursor holds 8 items");

        // 4. Right-click Drop onto empty slot 2 with "RightButton": deposits 1 item
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 2, null, "RightButton", Map.of(), false));
        check(storage.getItemStack((short) 2) != null && storage.getItemStack((short) 2).getQuantity() == 1,
                "right-click Drop with 'RightButton' deposited 1 item in slot 2");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 7, "cursor holds 7 items");

        // 5. Left-click Drop onto slot 2: deposits ALL remaining 7 items (total 8 in slot 2)
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 2, null, "Left", Map.of(), false));
        check(storage.getItemStack((short) 2).getQuantity() == 8,
                "left-click Drop deposited all remaining items into slot 2");
        check(get(controller, "dragOrigin") == null, "cursor cleared after left-click Drop");
    }

    private static void exerciseRightClickPressAndDropDoesNotDuplicate() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Copper", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> {});

        // 1. Pick up stack of 10 from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));

        // 2. DragPress on slot 1 with Right mouse button (mouse down)
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 1) != null && storage.getItemStack((short) 1).getQuantity() == 1,
                "DragPress deposited 1 item on press");
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 9, "cursor holds 9 items");

        // 3. Drop event fires on slot 1 on mouse release
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 1).getQuantity() == 1,
                "Drop on release does NOT deposit second item nor dump remaining stack");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 9, "cursor still holds 9 items after release");
    }

    private static void exerciseRightClickSweepAndDropDoesNotDuplicate() {
        var controller = newController();
        var storage = new SimpleItemContainer((short) 4);
        var item = new DetachedTestStack("Test_Silver", 10, null);
        storage.setItemStackForSlot((short) 0, item);

        Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
        containers.put(NativeInventorySection.STORAGE, storage);
        Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
        displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{ item, null, null, null });

        var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
        var store = components.addStore(null, null);
        var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
        var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
        var context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> {});

        // 1. Pick up stack of 10 from slot 0
        controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0, null, "Left", Map.of(), false));

        // 2. DragPress on slot 1
        controller.handleEvent(context, new InventoryContentEvent("DragPress", "STORAGE", 1, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 1).getQuantity() == 1, "slot 1 has 1 item");

        // 3. Drag across to slot 2
        controller.handleEvent(context, new InventoryContentEvent("UnhoverSource", "STORAGE", 1, null, null, Map.of(), false));
        controller.handleEvent(context, new InventoryContentEvent("HoverSource", "STORAGE", 2, null, null, Map.of(), false));
        check(storage.getItemStack((short) 2).getQuantity() == 1, "slot 2 has 1 item from hover sweep");
        InventorySelection origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 8, "cursor holds 8 items");

        // 4. Release mouse over slot 2: Drop event fires
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 2, null, "Right", Map.of(), false));
        check(storage.getItemStack((short) 2).getQuantity() == 1, "slot 2 still has 1 item after drop release");
        origin = get(controller, "dragOrigin");
        check(origin != null && origin.quantity() == 8, "cursor still holds 8 items after sweep drop release");

        // 5. Left click Drop onto slot 3: deposits remaining 8 items
        controller.handleEvent(context, new InventoryContentEvent("Drop", "STORAGE", 3, null, "Left", Map.of(), false));
        check(storage.getItemStack((short) 3).getQuantity() == 8, "slot 3 received all 8 remaining items on left drop");
        check(get(controller, "dragOrigin") == null, "cursor cleared after left drop");
    }

    private static void exerciseLeftHoverPlacesAll() {
        var fixture = new GestureFixture(8, false);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 1, null);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 2, null);
        fixture.event("Drop", NativeInventorySection.STORAGE, 2, "Left");
        check(fixture.quantity(fixture.storage, 0) == 0, "left placement removes the entire remaining held stack");
        check(fixture.quantity(fixture.storage, 1) == 0, "hovering another slot before left placement does not distribute items");
        check(fixture.quantity(fixture.storage, 2) == 8, "left placement puts all held items into the selected slot");
        check(fixture.held() == 0, "left placement ends holding after placing all items");
    }

    private static void exerciseReleaseBeforeDropDoesNotDuplicate() {
        var fixture = new GestureFixture(10, false);
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("CompleteSourceRelease", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("Drop", NativeInventorySection.STORAGE, 1, "Right", fixture.drag(10));
        check(fixture.quantity(fixture.storage, 1) == 1, "release followed by Dropped does not place a second item");
        check(fixture.held() == 9, "release-before-Dropped retains the remaining held quantity");
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        check(fixture.quantity(fixture.storage, 1) == 2, "a new click after release may place another item into the same slot");
        check(fixture.held() == 8, "new right click consumes exactly one held item");
    }

    private static void exerciseRepeatedCallbacksInSameVisit() {
        var fixture = new GestureFixture(10, false);
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 1, "Right");
        check(fixture.quantity(fixture.storage, 1) == 1, "repeated press and hover callbacks in one slot visit place only one item");
        check(fixture.held() == 9, "duplicate callbacks do not consume additional held items");
        fixture.event("UnhoverSource", NativeInventorySection.STORAGE, 1, null);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 1, null);
        check(fixture.quantity(fixture.storage, 1) == 2, "leaving and reentering during the right hold permits one additional placement");
        check(fixture.held() == 8, "reentry consumes exactly one held item");
    }

    private static void exerciseHoverAfterRightRelease() {
        var fixture = new GestureFixture(5, false);
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("CompleteSourceRelease", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("UnhoverSource", NativeInventorySection.STORAGE, 1, null);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 2, null);
        check(fixture.quantity(fixture.storage, 2) == 0, "ordinary hover after releasing right button does not place an item");
        check(fixture.held() == 4, "ordinary hover preserves all remaining held items");
        fixture.event("Drop", NativeInventorySection.STORAGE, 3, "Left");
        check(fixture.quantity(fixture.storage, 2) == 0 && fixture.quantity(fixture.storage, 3) == 4,
                "left placement after an ordinary hover still places the entire held remainder into one slot");
    }

    private static void exerciseLastUnitTerminalCallbacks() {
        for (boolean split : new boolean[]{false, true}) {
            var fixture = new GestureFixture(split ? 2 : 1, split);
            check(fixture.held() == 1, "last-unit fixture starts with exactly one item held");
            fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
            check(fixture.held() == 0, "placing the last unit ends the holding state");
            fixture.event("CompleteSourceRelease", NativeInventorySection.STORAGE, 1, "Right", fixture.drag(1));
            fixture.event("Drop", NativeInventorySection.STORAGE, 1, "Right", fixture.drag(1));
            fixture.event("Drop", NativeInventorySection.STORAGE, 1, null, fixture.drag(1));
            fixture.event("HoverSource", NativeInventorySection.STORAGE, 2, "Right", fixture.drag(1));
            check(fixture.quantity(fixture.storage, 0) == (split ? 1 : 0),
                    "late last-unit callbacks preserve the unheld source remainder");
            check(fixture.quantity(fixture.storage, 1) == 1 && fixture.quantity(fixture.storage, 2) == 0,
                    "late last-unit callbacks neither duplicate nor redistribute the final placed item");
            check(fixture.held() == 0 && !(Boolean) get(fixture.controller, "rightSweep"),
                    "late callbacks leave holding and right sweep ended");
        }
    }

    private static void exerciseSplitSourceQuantityConservation() {
        var fixture = new GestureFixture(11, true);
        check(fixture.held() == 6, "Shift + right pickup rounds an odd source stack up to six held items");
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("CompleteSourceRelease", NativeInventorySection.STORAGE, 1, "Right");
        check(fixture.quantity(fixture.storage, 0) == 10 && fixture.held() == 5,
                "one split deposit removes one authoritative item and one held item");
        fixture.event("DragPress", NativeInventorySection.STORAGE, 0, "Right");
        fixture.event("CompleteSourceRelease", NativeInventorySection.STORAGE, 0, "Right");
        check(fixture.quantity(fixture.storage, 0) == 10 && fixture.held() == 4,
                "returning one held split item to its origin preserves the authoritative source total");
        fixture.event("Drop", NativeInventorySection.STORAGE, 2, "Left", fixture.drag(6));
        check(fixture.quantity(fixture.storage, 0) == 6 && fixture.quantity(fixture.storage, 1) == 1
                        && fixture.quantity(fixture.storage, 2) == 4,
                "left placement consumes only four held split items and conserves all eleven source items");
        check(fixture.held() == 0, "left placement clears the remaining split holding state");
    }

    private static void exerciseCrossSectionSweepReentry() {
        var fixture = new GestureFixture(10, false);
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("UnhoverSource", NativeInventorySection.STORAGE, 1, null);
        fixture.event("HoverSource", NativeInventorySection.HOTBAR, 1, null);
        fixture.event("HoverSource", NativeInventorySection.HOTBAR, 1, null);
        check(fixture.quantity(fixture.storage, 1) == 1 && fixture.quantity(fixture.hotbar, 1) == 1,
                "same-numbered slots in separate inventory sections each receive one item");
        fixture.event("UnhoverSource", NativeInventorySection.HOTBAR, 1, null);
        fixture.event("HoverSource", NativeInventorySection.HOTBAR, 1, null);
        fixture.event("UnhoverSource", NativeInventorySection.HOTBAR, 1, null);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 1, null);
        check(fixture.quantity(fixture.storage, 1) == 2 && fixture.quantity(fixture.hotbar, 1) == 2,
                "right sweep permits one additional unit per section after leaving and reentering its slot");
        check(fixture.held() == 6 && fixture.quantity(fixture.storage, 0) == 6,
                "cross-section reentry consumes exactly four units from the held source");
    }

    private static void exerciseFullAndIncompatibleSweepTargets() {
        var fixture = new GestureFixture(5, false);
        fixture.storage.setItemStackForSlot((short) 1, new DetachedTestStack("Test_Gesture", Item.UNKNOWN.getMaxStack(), null));
        fixture.storage.setItemStackForSlot((short) 2, new DetachedTestStack("Other_Item", 7, null));
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("UnhoverSource", NativeInventorySection.STORAGE, 1, null);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 2, null);
        check(fixture.quantity(fixture.storage, 1) == Item.UNKNOWN.getMaxStack() && fixture.quantity(fixture.storage, 2) == 7,
                "right sweep does not overfill a full stack or replace an incompatible item");
        check(fixture.held() == 5 && fixture.quantity(fixture.storage, 0) == 5,
                "rejected right sweep targets do not consume held source items");
        fixture.event("UnhoverSource", NativeInventorySection.STORAGE, 2, null);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 3, null);
        check(fixture.quantity(fixture.storage, 3) == 1 && fixture.held() == 4,
                "right hold can continue to a compatible slot after passing rejected targets");
    }

    private static void exerciseFilteredTargetRetainsHeld() {
        var fixture = new GestureFixture(5, false);
        fixture.storage.setSlotFilter(
                com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType.ADD, (short) 1,
                com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter.DENY);
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        check(fixture.quantity(fixture.storage, 0) == 5 && fixture.quantity(fixture.storage, 1) == 0,
                "a native slot filter rejection leaves both authoritative slots intact");
        check(fixture.held() == 5, "a native slot filter rejection preserves the full held quantity");
        fixture.event("UnhoverSource", NativeInventorySection.STORAGE, 1, null);
        fixture.event("HoverSource", NativeInventorySection.STORAGE, 2, null);
        check(fixture.quantity(fixture.storage, 2) == 1 && fixture.held() == 4,
                "the right hold continues after a native slot filter rejects a target");
    }

    private static void exerciseAdditionalGestureRegressions() {
        Runnable[] cases = {
                NativeGestureRegression::exerciseSplitLeftReleaseAndDrop,
                NativeGestureRegression::exerciseCapacityLimitedLeftPlacement,
                NativeGestureRegression::exerciseDeniedLeftPlacement,
                NativeGestureRegression::exerciseUnconfirmedRightPlacement,
                NativeGestureRegression::exerciseUtilitySweepProjectionReentry
        };
        AssertionError failures = null;
        for (var test : cases) {
            try { test.run(); }
            catch (AssertionError failure) {
                if (failures == null) failures = new AssertionError("Additional held-stack gesture regressions failed");
                failures.addSuppressed(failure);
            }
        }
        if (failures != null) throw failures;
    }

    private static void exerciseSplitLeftReleaseAndDrop() {
        var fixture = new GestureFixture(10, true);
        fixture.event("CompleteSourceRelease", NativeInventorySection.STORAGE, 1, "Left", fixture.drag(5));
        check(fixture.quantity(fixture.storage, 0) == 5 && fixture.quantity(fixture.storage, 1) == 5,
                "left release places only the held half and preserves the unheld source half");
        fixture.event("Drop", NativeInventorySection.STORAGE, 1, "Left", fixture.drag(5));
        check(fixture.quantity(fixture.storage, 0) == 5 && fixture.quantity(fixture.storage, 1) == 5,
                "Dropped after left release cannot place the unheld split remainder a second time");
        check(fixture.held() == 0, "duplicate left terminal callbacks keep holding ended");
    }

    private static void exerciseCapacityLimitedLeftPlacement() {
        var fixture = new GestureFixture(8, false);
        fixture.storage.setItemStackForSlot((short) 1,
                new DetachedTestStack("Test_Gesture", Item.UNKNOWN.getMaxStack() - 1, null));
        fixture.event("Drop", NativeInventorySection.STORAGE, 1, "Left");
        check(fixture.quantity(fixture.storage, 0) == 7 && fixture.quantity(fixture.storage, 1) == Item.UNKNOWN.getMaxStack(),
                "left placement into a nearly full slot moves only its available capacity");
        check(fixture.held() == 7, "capacity-limited left placement keeps seven remaining items held");
        fixture.event("Drop", NativeInventorySection.STORAGE, 2, "Left");
        check(fixture.quantity(fixture.storage, 0) == 0 && fixture.quantity(fixture.storage, 2) == 7,
                "a subsequent left placement can move the entire capacity-limited remainder");
        check(fixture.held() == 0, "placing the capacity-limited remainder ends holding normally");
    }

    private static void exerciseDeniedLeftPlacement() {
        var fixture = new GestureFixture(5, false);
        fixture.storage.setSlotFilter(
                com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType.ADD, (short) 1,
                com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter.DENY);
        fixture.event("Drop", NativeInventorySection.STORAGE, 1, "Left");
        check(fixture.quantity(fixture.storage, 0) == 5 && fixture.quantity(fixture.storage, 1) == 0,
                "a denied left placement preserves both authoritative inventory slots");
        check(fixture.held() == 5, "a denied left placement preserves all held items");
        fixture.event("Drop", NativeInventorySection.STORAGE, 2, "Left");
        check(fixture.quantity(fixture.storage, 0) == 0 && fixture.quantity(fixture.storage, 2) == 5,
                "the held stack remains available for another placement after a denied left target");
    }

    private static void exerciseUnconfirmedRightPlacement() {
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var controller = new NativeInventoryContent(null, (context, selected, section, slot, quantity, source, target) -> {
            submitted.incrementAndGet();
            return InventoryOperations.Result.SUBMITTED;
        });
        var fixture = new GestureFixture(5, false, controller);
        fixture.event("DragPress", NativeInventorySection.STORAGE, 1, "Right");
        check(submitted.get() == 1, "unconfirmed right placement submits one move request");
        check(fixture.quantity(fixture.storage, 0) == 5 && fixture.quantity(fixture.storage, 1) == 0,
                "a submitted move without source mutation leaves authoritative slots intact");
        check(fixture.held() == 5, "a submitted move without source mutation does not consume a held item");
        fixture.event("CompleteSourceRelease", NativeInventorySection.STORAGE, 1, "Right");
        fixture.event("Drop", NativeInventorySection.STORAGE, 1, "Right", fixture.drag(5));
        check(submitted.get() == 1 && fixture.held() == 5,
                "terminal callbacks do not resubmit an unconfirmed placement or reduce held quantity");
    }

    private static void exerciseUtilitySweepProjectionReentry() {
        var fixture = new GestureFixture(10, false);
        var utility = new SimpleItemContainer((short) 4);
        Map<NativeInventorySection, ItemContainer> containers = get(fixture.controller, "displayedContainers");
        containers.put(NativeInventorySection.UTILITY, utility);
        Map<NativeInventorySection, ItemStack[]> displayed = get(fixture.controller, "displayed");
        displayed.put(NativeInventorySection.UTILITY, new ItemStack[4]);
        set(fixture.controller, "displayedUtilitySlot", 2);

        // Both center and wheel grids expose visible slot zero. Their payload and
        // selected utility index must distinguish the underlying authoritative slots.
        fixture.event("DragPress", NativeInventorySection.UTILITY, 0, "Right");
        check(fixture.quantity(utility, 2) == 1 && fixture.quantity(utility, 0) == 0,
                "right placement in utility center targets selected actual slot two");
        fixture.event("UnhoverSource", NativeInventorySection.UTILITY, 0, null);
        fixture.wheelEvent("UtilityWheelHover", 1, null);
        fixture.wheelEvent("UtilityWheelHover", 1, null);
        check(fixture.quantity(utility, 1) == 1,
                "wheel slot one receives exactly one unit despite sharing visible slot zero with center");
        fixture.wheelEvent("UtilityWheelUnhover", 1, null);
        fixture.wheelEvent("UtilityWheelHover", 2, null);
        check(fixture.quantity(utility, 2) == 2,
                "leaving center permits another deposit when its actual slot is reentered through the wheel");
        fixture.wheelEvent("UtilityWheelUnhover", 2, null);
        fixture.wheelEvent("UtilityWheelHover", 1, null);
        check(fixture.quantity(utility, 1) == 2 && fixture.held() == 6,
                "utility wheel reentry consumes exactly one held unit per actual slot visit");
        fixture.wheelEvent("CompleteUtilitySourceRelease", 1, "Right");
        fixture.wheelEvent("UtilityWheelUnhover", 1, null);
        fixture.wheelEvent("UtilityWheelHover", 3, null);
        check(fixture.quantity(utility, 3) == 0 && fixture.held() == 6,
                "utility wheel hover after releasing right button retains the held remainder");
    }

    private static final class GestureFixture {
        final NativeInventoryContent controller;
        final SimpleItemContainer storage = new SimpleItemContainer((short) 5);
        final SimpleItemContainer hotbar = new SimpleItemContainer((short) 5);
        final com.supremosan.custominventory.api.InventoryContext context;

        GestureFixture(int quantity, boolean split) {
            this(quantity, split, newController(), true);
        }

        GestureFixture(int quantity, boolean split, boolean emitDragSource) {
            this(quantity, split, newController(), emitDragSource);
        }

        GestureFixture(int quantity, boolean split, NativeInventoryContent controller) {
            this(quantity, split, controller, true);
        }

        GestureFixture(int quantity, boolean split, NativeInventoryContent controller, boolean emitDragSource) {
            this.controller = controller;
            var item = new DetachedTestStack("Test_Gesture", quantity, null);
            storage.setItemStackForSlot((short) 0, item);
            Map<NativeInventorySection, ItemContainer> containers = get(controller, "displayedContainers");
            containers.put(NativeInventorySection.STORAGE, storage);
            containers.put(NativeInventorySection.HOTBAR, hotbar);
            Map<NativeInventorySection, ItemStack[]> displayed = get(controller, "displayed");
            displayed.put(NativeInventorySection.STORAGE, new ItemStack[]{item, null, null, null, null});
            displayed.put(NativeInventorySection.HOTBAR, new ItemStack[5]);
            var components = new com.hypixel.hytale.component.ComponentRegistry<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>();
            var store = components.addStore(null, null);
            var ref = store.addEntity(components.newHolder(), com.hypixel.hytale.component.AddReason.LOAD);
            var player = new com.hypixel.hytale.server.core.universe.PlayerRef(components.newHolder(), java.util.UUID.randomUUID(), "Tester", "en-US", null, null);
            context = new com.supremosan.custominventory.api.InventoryContext(ref, store, player, () -> {});
            if (emitDragSource) {
                controller.handleEvent(context, new InventoryContentEvent("DragSource", "STORAGE", 0,
                        null, split ? "Right" : "Left", Map.of(), split));
            }
        }

        void event(String action, NativeInventorySection section, int slot, String button) {
            event(action, section, slot, button, null);
        }

        void event(String action, NativeInventorySection section, int slot, String button,
                   com.supremosan.custominventory.api.InventoryDragData drag) {
            controller.handleEvent(context, new InventoryContentEvent(action, section.name(), slot, drag, button, Map.of(), false));
        }

        void wheelEvent(String action, int actualSlot, String button) {
            controller.handleEvent(context, new InventoryContentEvent(action, Integer.toString(actualSlot), 0,
                    null, button, Map.of(), false));
        }

        void jsonEvent(String action, int slot, String inputFields) {
            String json = "{\"Action\":\"Content\",\"PageId\":\"@panels\",\"ContentAction\":\"" + action
                    + "\",\"Payload\":\"STORAGE\",\"SlotIndex\":" + slot + "," + inputFields + "}";
            try {
                var decoded = com.supremosan.custominventory.ui.InventoryShellPage.Event.CODEC.decodeJson(
                        new com.hypixel.hytale.codec.util.RawJsonReader(json.toCharArray()),
                        com.hypixel.hytale.codec.ExtraInfo.THREAD_LOCAL.get());
                controller.handleEvent(context, new InventoryContentEvent(decoded.contentAction, decoded.payload,
                        decoded.slotIndex, decoded.dragData(), decoded.resolvedMouseButton(),
                        decoded.formValues(), decoded.shiftHeld));
            } catch (Exception failure) {
                throw new AssertionError("Failed to execute decoded ItemGrid payload: " + json, failure);
            }
        }

        com.supremosan.custominventory.api.InventoryDragData drag(int quantity) {
            return new com.supremosan.custominventory.api.InventoryDragData(
                    NativeInventorySection.STORAGE.id(), 0, null, null, "Test_Gesture", quantity, null, null);
        }

        int held() {
            InventorySelection origin = get(controller, "dragOrigin");
            return origin == null ? 0 : origin.quantity();
        }

        int quantity(ItemContainer container, int slot) {
            var stack = container.getItemStack((short) slot);
            return ItemStack.isEmpty(stack) ? 0 : stack.getQuantity();
        }
    }

    private static final class DetachedTestStack extends ItemStack {
        DetachedTestStack(String id, int count, BsonDocument data) {
            itemId = id;
            quantity = count;
            metadata = data;
        }
        @Override public Item getItem() { return Item.UNKNOWN; }
        @Override public ItemStack withQuantity(int count) {
            if (count <= 0) return null;
            if (count == this.quantity) return this;
            var copy = new DetachedTestStack(itemId, count, metadata);
            copy.setOverrideDroppedItemAnimation(getOverrideDroppedItemAnimation());
            return copy;
        }
        @Override public ItemStack withMetadata(BsonDocument data) {
            var copy = new DetachedTestStack(itemId, quantity, data);
            copy.setOverrideDroppedItemAnimation(getOverrideDroppedItemAnimation());
            return copy;
        }
    }
}
