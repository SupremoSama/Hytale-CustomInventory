package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
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
        exerciseRelease(NativeInventorySection.STORAGE, false, true, "Left", 1, true);
        exerciseRelease(NativeInventorySection.STORAGE, false, false, "Left", 1, false);
        exerciseRelease(NativeInventorySection.STORAGE, false, true, "Right", 1, false);
        exerciseRelease(NativeInventorySection.STORAGE, false, true, "Left", 2, false);
        exerciseRelease(NativeInventorySection.UTILITY, true, true, "Left", 0, true);
        return assertions;
    }

    private static void exerciseRelease(NativeInventorySection section, boolean wheel, boolean shift,
                                        String button, int releasedSlot, boolean completes) {
        var controller = new NativeInventoryContent();
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
        try { field(name).set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String name) {
        try { return (T) field(name).get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        var field = NativeInventoryContent.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static final class DetachedTestStack extends ItemStack {
        DetachedTestStack(String id, int count, BsonDocument data) {
            itemId = id;
            quantity = count;
            metadata = data;
        }
        @Override public Item getItem() { return Item.UNKNOWN; }
        @Override public ItemStack withMetadata(BsonDocument data) {
            var copy = new DetachedTestStack(itemId, quantity, data);
            copy.setOverrideDroppedItemAnimation(getOverrideDroppedItemAnimation());
            return copy;
        }
    }
}
