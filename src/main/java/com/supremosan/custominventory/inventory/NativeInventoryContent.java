package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.InventoryContent;
import com.supremosan.custominventory.api.InventoryContentEvent;
import com.supremosan.custominventory.api.InventoryContext;
import com.supremosan.custominventory.api.InventoryEventBindings;
import com.supremosan.custominventory.ui.player.UtilitySlotSelector;
import com.supremosan.custominventory.ui.InventoryTooltips;

import java.util.Collections;
import java.util.Arrays;
import java.util.Locale;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Persistent native grids with detached drag snapshots shared by every inventory section. */
public final class NativeInventoryContent implements InventoryContent {
    private static final List<NativeInventorySection> PERSISTENT_SECTIONS = List.of(
            NativeInventorySection.STORAGE, NativeInventorySection.HOTBAR,
            NativeInventorySection.ARMOR, NativeInventorySection.UTILITY);
    private final Map<NativeInventorySection, ItemStack[]> displayed = new EnumMap<>(NativeInventorySection.class);
    private final Map<NativeInventorySection, ItemContainer> displayedContainers = new EnumMap<>(NativeInventorySection.class);
    private final Map<ItemContainer, EventRegistration<Void, ItemContainer.ItemContainerChangeEvent>> listeners = new IdentityHashMap<>();
    private InventorySelection selection;
    private InventorySelection dragOrigin;
    private boolean shiftSourceGesture;
    private final Set<NativeInventorySection> releasedSources = java.util.EnumSet.noneOf(NativeInventorySection.class);
    private int dragGridId;
    private record DragKey(int grid, int slot) { }
    private record PendingRemoval(InventorySelection origin, int quantity, long submittedAt) { }
    private record ResolvedSource(DragKey key, InventorySelection origin) { }
    private static final long PENDING_MOVE_TIMEOUT = TimeUnit.SECONDS.toNanos(2);
    private final Map<DragKey, InventorySelection> dragOrigins = new HashMap<>();
    private final Map<DragKey, PendingRemoval> pendingRemovals = new HashMap<>();
    private InventorySelection hoveredSelection;
    private InventorySelection dropButtonSelection;
    private Boolean dropDisabled;
    private String dropTooltip;
    private String status = "";
    private int activeHotbarSlot = Integer.MIN_VALUE;
    private int displayedUtilitySlot = -1;

    /** The shell mounts both panels before invoking this controller beneath #InventoryShell. */
    @Override
    public void build(InventoryContext context, UICommandBuilder commands,
                      InventoryEventBindings events, String selector) {
        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        releasedSources.clear();
        hoveredSelection = null;
        dragOrigins.clear();
        activeHotbarSlot = Integer.MIN_VALUE;
        dropDisabled = null;
        dropTooltip = null;
        render(context, commands, events, selector);
    }

    /** Updates mounted slots without accumulating a second set of drag bindings. */
    public void refresh(InventoryContext context, UICommandBuilder commands, String selector) {
        render(context, commands, null, selector);
    }

    private void render(InventoryContext context, UICommandBuilder commands,
                        InventoryEventBindings events, String selector) {
        Set<ItemContainer> current = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var section : PERSISTENT_SECTIONS) {
            boolean sourceReleased = releasedSources.remove(section);
            ItemContainer container = InventoryOperations.resolveContainer(context.ref(), context.store(), section);
            var previous = displayed.get(section);
            boolean containerChanged = container != displayedContainers.get(section);
            int capacity = container == null ? 0 : container.getCapacity();
            ItemGridSlot[] slots = new ItemGridSlot[capacity];
            ItemStack[] snapshot = new ItemStack[capacity];
            for (short slot = 0; slot < capacity; slot++) {
                ItemStack stack = container.getItemStack(slot);
                slots[slot] = InventoryDisplay.slot(stack);
                if (section == NativeInventorySection.ARMOR && ItemStack.isEmpty(stack) && slot < 4) {
                    String icon = switch (slot) { case 0 -> "Head"; case 1 -> "Chest"; case 2 -> "Hands"; default -> "Legs"; };
                    slots[slot].setIcon(Value.ref("Inventory/InventoryGridStyle.ui", "EmptyArmor" + icon));
                }
                snapshot[slot] = InventorySelection.snapshot(stack);
            }
            boolean slotsChanged = events != null || sourceReleased || containerChanged || !Arrays.equals(previous, snapshot);
            boolean centerChanged = slotsChanged;
            displayed.put(section, snapshot);
            if (section == NativeInventorySection.UTILITY) {
                var utility = context.store().getComponent(context.ref(), InventoryComponent.Utility.getComponentType());
                int previousActiveSlot = displayedUtilitySlot;
                displayedUtilitySlot = UtilitySlotProjection.activeIndex(capacity, utility == null ? -1 : utility.getActiveSlot());
                centerChanged = events != null || sourceReleased || containerChanged || previousActiveSlot != displayedUtilitySlot
                        || !java.util.Objects.equals(itemAt(previous, previousActiveSlot), itemAt(snapshot, displayedUtilitySlot));
                slots = UtilitySlotProjection.slots(snapshot, displayedUtilitySlot);
                if (events != null) {
                    commands.set(selector + " #PlayerPanelHost #UtilityGrid.InventorySectionId", UtilitySlotProjection.CENTER_GRID_ID);
                    commands.set(selector + " #PlayerPanelHost #UtilityWheelCenterGrid.InventorySectionId", UtilitySlotProjection.CENTER_GRID_ID);
                }
                if (centerChanged) commands.set(selector + " #PlayerPanelHost #UtilityWheelCenterGrid.Slots", slots);
                for (int index = 0; index < UtilitySlotSelector.DISPLAYED_SLOTS; index++) {
                    if (events != null) commands.set(selector + " #PlayerPanelHost #UtilityChoiceGrid" + index + ".InventorySectionId",
                            UtilitySlotProjection.wheelGridId(index));
                    if (events != null || sourceReleased || containerChanged || !java.util.Objects.equals(itemAt(previous, index), itemAt(snapshot, index))) {
                        var choice = new ItemGridSlot[]{InventoryDisplay.slot(itemAt(snapshot, index))};
                        commands.set(selector + " #PlayerPanelHost #UtilityChoiceGrid" + index + ".Slots", choice);
                        var stack = itemAt(snapshot, index);
                        String display = selector + " #PlayerPanelHost #UtilityChoiceDisplay" + index;
                        boolean hasItem = !ItemStack.isEmpty(stack);
                        commands.set(display + " #ItemVisual.Visible", hasItem);
                        commands.set(display + " #Quantity.Visible", hasItem && stack.getQuantity() > 1);
                        if (hasItem) {
                            commands.set(display + " #ItemVisual.ItemId", stack.getItemId());
                            commands.set(display + " #Quantity.Text", Integer.toString(stack.getQuantity()));
                        }
                    }
                }
            }
            if (container == null) displayedContainers.remove(section);
            else {
                displayedContainers.put(section, container);
                current.add(container);
                listeners.computeIfAbsent(container, c -> c.registerChangeEvent(change -> context.requestRefresh()));
            }
            String grid = grid(section);
            if (section == NativeInventorySection.UTILITY ? centerChanged : slotsChanged)
                commands.set(selector + " " + grid + ".Slots", slots);
            if (events != null) {
                events.bind(CustomUIEventBindingType.SlotClicking, grid, "DragSource", section.name(), false);
                events.bind(CustomUIEventBindingType.SlotClickReleaseWhileDragging, grid, "CompleteSourceRelease", section.name(), false);
                events.bind(CustomUIEventBindingType.Dropped, grid, "Drop", section.name(), false);
                events.bind(CustomUIEventBindingType.DragCancelled, grid, "CancelDrag", "", false);
                if (section != NativeInventorySection.UTILITY) {
                    events.bind(CustomUIEventBindingType.SlotMouseEntered, grid, "HoverSource", section.name(), false);
                    events.bind(CustomUIEventBindingType.SlotMouseExited, grid, "UnhoverSource", section.name(), false);
                }
            }
        }
        listeners.entrySet().removeIf(entry -> {
            if (current.contains(entry.getKey())) return false;
            entry.getValue().unregister();
            return true;
        });
        commands.set(selector + " #InventoryPanelHost #InventoryStatus.Text", status);
        commands.set(selector + " #InventoryPanelHost #InventoryStatus.Visible", !status.isBlank());
        commands.set(selector + " #InventoryPanelHost #InventorySortButton.TooltipText", InventoryTooltips.sort());
        if (events != null) events.bind(CustomUIEventBindingType.Activating, "#InventoryPanelHost #InventorySortButton", "Sort", "", false);
        if (events != null) UtilitySlotSelector.bindInventoryEvents(events);
        reconcileRemovals();
        refreshDropAction(context, commands);
        refreshHotbar(context, commands, selector);
    }

    /** The backpack body is remounted when switching tabs; its drag session stays shared with the player grids. */
    public void mountBackpack(InventoryContext context, UICommandBuilder commands, InventoryEventBindings events, String selector) {
        mountBackpack(context, commands, events, selector, true);
    }

    public void mountBackpack(InventoryContext context, UICommandBuilder commands, InventoryEventBindings events,
                              String selector, boolean bindEvents) {
        var section = NativeInventorySection.BACKPACK;
        boolean sourceReleased = releasedSources.remove(section);
        var container = InventoryOperations.resolveContainer(context.ref(), context.store(), section);
        var previous = displayed.get(section);
        boolean containerChanged = container != displayedContainers.get(section);
        int capacity = container == null ? 0 : container.getCapacity();
        var slots = new ItemGridSlot[capacity];
        var snapshot = new ItemStack[capacity];
        for (short slot = 0; slot < capacity; slot++) {
            var stack = container.getItemStack(slot);
            slots[slot] = InventoryDisplay.slot(stack);
            snapshot[slot] = InventorySelection.snapshot(stack);
        }
        displayed.put(section, snapshot);
        if (container != null) {
            displayedContainers.put(section, container);
            listeners.computeIfAbsent(container, c -> c.registerChangeEvent(change -> context.requestRefresh()));
        } else displayedContainers.remove(section);
        String grid = grid(section);
        if (bindEvents || sourceReleased || containerChanged || !Arrays.equals(previous, snapshot))
            commands.set(selector + " " + grid + ".Slots", slots);
        if (bindEvents) {
            events.bind(CustomUIEventBindingType.SlotClicking, grid, "DragSource", section.name(), false);
            events.bind(CustomUIEventBindingType.SlotClickReleaseWhileDragging, grid, "CompleteSourceRelease", section.name(), false);
            events.bind(CustomUIEventBindingType.Dropped, grid, "Drop", section.name(), false);
            events.bind(CustomUIEventBindingType.DragCancelled, grid, "CancelDrag", "", false);
            events.bind(CustomUIEventBindingType.SlotMouseEntered, grid, "HoverSource", section.name(), false);
            events.bind(CustomUIEventBindingType.SlotMouseExited, grid, "UnhoverSource", section.name(), false);
        }
    }

    public void unmountBackpack() {
        displayed.remove(NativeInventorySection.BACKPACK);
        displayedContainers.remove(NativeInventorySection.BACKPACK);
        if (selection != null && selection.section() == NativeInventorySection.BACKPACK) selection = null;
        if (dragOrigin != null && dragOrigin.section() == NativeInventorySection.BACKPACK) {
            dragOrigin = null;
            shiftSourceGesture = false;
        }
        releasedSources.remove(NativeInventorySection.BACKPACK);
        dragOrigins.entrySet().removeIf(entry -> entry.getValue().section() == NativeInventorySection.BACKPACK);
        pendingRemovals.entrySet().removeIf(entry -> entry.getValue().origin().section() == NativeInventorySection.BACKPACK);
        if (hoveredSelection != null && hoveredSelection.section() == NativeInventorySection.BACKPACK) hoveredSelection = null;
    }

    public void refreshHotbar(InventoryContext context, UICommandBuilder commands, String selector) {
        var hotbar = context.store().getComponent(context.ref(), InventoryComponent.Hotbar.getComponentType());
        int slot = hotbar == null ? -1 : hotbar.getActiveSlot();
        if (slot == activeHotbarSlot) return;
        activeHotbarSlot = slot;
        String outline = selector + " #InventoryPanelHost #ActiveHotbarSlot";
        boolean visible = hotbar != null && slot >= 0 && slot < hotbar.getInventory().getCapacity();
        commands.set(outline + ".Visible", visible);
        if (visible) {
            Anchor anchor = new Anchor();
            // Native slot center: grid padding 2 + (slot size 74 - sprite 112) / 2.
            anchor.setLeft(Value.of(slot * 76 - 17));
            anchor.setTop(Value.of(-17));
            anchor.setWidth(Value.of(112));
            anchor.setHeight(Value.of(112));
            commands.setObject(outline + ".Anchor", anchor);
        }
    }

    /** Native active-slot changes do not always emit an item-container change. */
    public void refreshUtility(InventoryContext context, UICommandBuilder commands, String selector) {
        var section = NativeInventorySection.UTILITY;
        var container = InventoryOperations.resolveContainer(context.ref(), context.store(), section);
        if (container != displayedContainers.get(section)) {
            context.requestRefresh();
            return;
        }
        var utility = context.store().getComponent(context.ref(), InventoryComponent.Utility.getComponentType());
        int capacity = container == null ? 0 : container.getCapacity();
        int active = UtilitySlotProjection.activeIndex(capacity, utility == null ? -1 : utility.getActiveSlot());
        if (active == displayedUtilitySlot) return;
        var snapshot = new ItemStack[capacity];
        for (short index = 0; index < capacity; index++) snapshot[index] = InventorySelection.snapshot(container.getItemStack(index));
        if (!Arrays.equals(displayed.get(section), snapshot)) {
            // A center-only update must not advance snapshots for wheel icons it
            // did not send. Let the normal render synchronize every changed grid.
            context.requestRefresh();
            return;
        }
        displayedUtilitySlot = active;
        var slots = UtilitySlotProjection.slots(snapshot, active);
        commands.set(selector + " " + grid(section) + ".Slots", slots);
        commands.set(selector + " #PlayerPanelHost #UtilityWheelCenterGrid.Slots", slots);
    }

    @Override
    public void handleEvent(InventoryContext context, InventoryContentEvent event) {
        if ("UnhoverSource".equals(event.action())) {
            hoveredSelection = null;
            return;
        }
        if ("CancelDrag".equals(event.action())) {
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            // Hiding a wheel source can emit this while the client still holds its stack.
            // Keep its keyed snapshot, but do not use it as the next gesture's default.
            return;
        }
        if ("CompleteSourceRelease".equals(event.action()) || "CompleteUtilitySourceRelease".equals(event.action())) {
            completeSourceRelease(event);
            return;
        }
        if (InventoryOperations.locked(context.ref(), context.store())) {
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            status = "Inventory access is currently locked.";
            return;
        }
        if ("DropOutside".equals(event.action())) {
            dropOutside(context, event);
            return;
        }
        if ("DropSelected".equals(event.action()) || "DropHovered".equals(event.action())) {
            reconcileRemovals();
            var source = hoveredSelection != null ? hoveredSelection : dropButtonSelection;
            if (source == null) source = dragOrigin;
            if (source == null) source = selection;
            if (source == null && event.payload() != null && !event.payload().isBlank()) {
                try {
                    String[] parts = event.payload().split(":");
                    if (parts.length >= 2) {
                        int secId = Integer.parseInt(parts[0]);
                        int slot = Integer.parseInt(parts[1]);
                        var sec = NativeInventorySection.fromId(secId);
                        if (sec != null && displayedContainers.containsKey(sec)) {
                            var container = displayedContainers.get(sec);
                            if (InventoryOperations.validSlot(container, slot)) {
                                var stack = container.getItemStack((short) slot);
                                if (!ItemStack.isEmpty(stack)) {
                                    source = InventorySelection.fromDisplayed(sec, container, slot, stack);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (source == null) return;
            if (hasPendingMove(source)) {
                status = "The previous inventory move is still pending. Try again.";
                return;
            }
            int dropQty = source.quantity();
            if (event.drag() != null && event.drag().quantity() != null && event.drag().quantity() > 0) {
                dropQty = Math.min(event.drag().quantity(), dropQty);
            }
            var result = InventoryOperations.drop(context.ref(), context.store(), source, dropQty);
            if (result == InventoryOperations.Result.SUBMITTED) {
                pendingRemovals.put(new DragKey(source.section().id(), source.slot()),
                        new PendingRemoval(source, dropQty, System.nanoTime()));
            }
            status = result == InventoryOperations.Result.SUBMITTED ? "" : "The selected item cannot be dropped.";
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            hoveredSelection = null;
            dropButtonSelection = null;
            dragOrigins.clear();
            return;
        }
        if ("Sort".equals(event.action())) {
            reconcileRemovals();
            if (!pendingRemovals.isEmpty()) {
                status = "The previous inventory move is still pending. Try again.";
                return;
            }
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            dragOrigins.clear();
            pendingRemovals.clear();
            InventoryUtils.sortStorage(context.ref(), context.store());
            status = "";
            return;
        }
        boolean press = "DragPress".equals(event.action()) || "UtilityWheelDragPress".equals(event.action());
        boolean release = "DragRelease".equals(event.action()) || "UtilityWheelDragRelease".equals(event.action());
        // Right placement happens on press; its release must not place a second time.
        if (press && !event.rightMouseButton() || release && event.rightMouseButton()) return;
        boolean wheel = "UtilityWheelDragSource".equals(event.action()) || "UtilityWheelDrop".equals(event.action())
                || "UtilityWheelDropOne".equals(event.action()) || "UtilityWheelDragPress".equals(event.action())
                || "UtilityWheelDragRelease".equals(event.action());
        String action = "UtilityWheelDragSource".equals(event.action()) ? "DragSource"
                : "UtilityWheelDrop".equals(event.action()) || release ? "Drop"
                : "UtilityWheelDropOne".equals(event.action()) || press ? "DropOne" : event.action();
        NativeInventorySection target = wheel ? NativeInventorySection.UTILITY : NativeInventorySection.parse(event.payload());
        if (target == null || !displayed.containsKey(target)) return;
        if ("HoverSource".equals(action)) {
            observeHover(target, event.slotIndex(), false, null);
            return;
        }
        if ("DragSource".equals(action)) {
            reconcileRemovals();
            var slots = displayed.get(target);
            Integer slot = wheel ? UtilitySlotProjection.wheelIndex(event.payload(), event.slotIndex(), slots.length)
                    : target == NativeInventorySection.UTILITY
                    ? UtilitySlotProjection.sourceIndex(event.slotIndex(), displayedUtilitySlot, slots.length, null) : event.slotIndex();
            var captured = slot == null || slot < 0 || slot >= slots.length ? null
                    : InventorySelection.fromDisplayed(target, displayedContainers.get(target), slot, slots[slot]);
            // The same click callback can accompany placement onto an empty slot.
            // An empty destination must not erase the source carried by the client.
            if (captured == null) return;
            int sourceGrid = target == NativeInventorySection.UTILITY
                    ? wheel ? UtilitySlotProjection.wheelGridId(captured.slot()) : UtilitySlotProjection.CENTER_GRID_ID : target.id();
            var key = new DragKey(sourceGrid, event.slotIndex());
            dragOrigins.put(key, captured);
            var metadata = event.drag();
            Integer sourceId = metadata == null ? null : metadata.sectionId();
            Integer sourceSlot = metadata == null ? null : metadata.slotId();
            boolean capturedSource = sourceId != null && sourceSlot != null
                    && ((sourceId == sourceGrid && (sourceSlot.equals(event.slotIndex()) || sourceSlot == captured.slot()))
                        || (sourceId == target.id() && sourceSlot == captured.slot()));
            if (dragOrigin == null || sourceId == null || capturedSource) {
                selection = captured;
                dragOrigin = captured;
                dragGridId = sourceGrid;
                shiftSourceGesture = Boolean.TRUE.equals(event.shiftHeld()) && !event.rightMouseButton();
            }
            // Leave the mounted grids/cursor untouched until Drop or DragCancelled.
            return;
        }
        if (!"Drop".equals(action) && !"DropOne".equals(action)) return;
        reconcileRemovals();
        try {
            var targets = displayed.get(target);
            Integer targetSlot = wheel ? UtilitySlotProjection.wheelIndex(event.payload(), event.slotIndex(), targets.length)
                    : target == NativeInventorySection.UTILITY
                    ? UtilitySlotProjection.destinationIndex(event.slotIndex(), targets, displayedUtilitySlot) : event.slotIndex();
            if (targetSlot == null || targetSlot < 0 || targetSlot >= targets.length) {
                status = "Invalid inventory drop.";
                return;
            }
            var drag = event.drag();
            Integer sourceId = drag == null ? null : drag.sectionId();
            Integer sourceSlot = drag == null ? null : drag.slotId();
            boolean projectedSource = UtilitySlotProjection.isProjectedGrid(sourceId);
            var sourceKey = sourceId == null || sourceSlot == null ? null : new DragKey(sourceId, sourceSlot);
            var retainedOrigin = sourceKey == null ? null : dragOrigins.get(sourceKey);
            if (retainedOrigin == null && sourceId != null && sourceId == dragGridId) retainedOrigin = dragOrigin;
            if (retainedOrigin == null && dragOrigin != null && (sourceId == null || sourceId == dragGridId
                    || (drag != null && (drag.itemId() == null || drag.itemId().equals(dragOrigin.itemId()))))) {
                retainedOrigin = dragOrigin;
            }
            NativeInventorySection source = projectedSource ? NativeInventorySection.UTILITY
                    : retainedOrigin != null ? retainedOrigin.section()
                    : NativeInventorySection.fromId(sourceId == null ? Integer.MAX_VALUE : sourceId);
            if (projectedSource) {
                if (retainedOrigin == null) retainedOrigin = dragOrigins.get(new DragKey(sourceId, 0));
                var utilities = displayed.get(source);
                sourceSlot = utilities == null ? -1 : UtilitySlotProjection.dragSourceIndex(sourceId, sourceSlot,
                        displayedUtilitySlot, utilities.length, retainedOrigin);
                sourceKey = new DragKey(sourceId, 0);
            } else if (retainedOrigin != null) {
                source = retainedOrigin.section();
                sourceSlot = retainedOrigin.slot();
                if (sourceKey == null) sourceKey = new DragKey(dragGridId, sourceSlot);
                projectedSource = source == NativeInventorySection.UTILITY;
            } else if (sourceId == null || source == NativeInventorySection.UTILITY) {
                // Null split metadata and native IDs from a one-slot utility projection
                // cannot name a unique visual origin. Accept only an unambiguous captured
                // source; never substitute another same-ID stack or a live replacement.
                var resolved = resolveCapturedSource(context, source,
                        source == NativeInventorySection.UTILITY && sourceSlot != null && sourceSlot != 0 ? sourceSlot : null,
                        drag == null ? null : drag.itemId(), drag == null ? null : drag.quantity());
                if (resolved == null) {
                    status = "The selected item changed. Try again.";
                    return;
                }
                retainedOrigin = resolved.origin();
                source = retainedOrigin.section();
                sourceSlot = retainedOrigin.slot();
                sourceKey = resolved.key();
                projectedSource = source == NativeInventorySection.UTILITY;
            }
            var sourceSlots = source == null ? null : displayed.get(source);
            if (sourceSlots == null || sourceSlot == null || sourceSlot < 0 || sourceSlot >= sourceSlots.length
                    || event.slotIndex() == null) {
                status = "Invalid inventory drop.";
                return;
            }
            var expected = retainedOrigin != null && retainedOrigin.section() == source && retainedOrigin.slot() == sourceSlot
                    ? retainedOrigin : projectedSource ? null
                    : InventorySelection.fromDisplayed(source, displayedContainers.get(source), sourceSlot, sourceSlots[sourceSlot]);
            String id = drag == null ? null : drag.itemId();
            if (expected == null || (id != null && !id.equals(expected.itemId()))) {
                status = "The selected item changed. Try again.";
                return;
            }
            Integer requested = drag == null ? null : drag.quantity();
            int quantity;
            if ("DropOne".equals(action)) {
                quantity = 1;
            } else if (requested != null && requested > 0) {
                quantity = Math.min(requested, expected.quantity());
            } else {
                quantity = expected.quantity();
            }
            if (hasPendingMove(expected)) {
                status = "The previous inventory move is still pending. Try again.";
                return;
            }
            var result = InventoryOperations.move(context.ref(), context.store(), expected, target, targetSlot, quantity);
            if (result == InventoryOperations.Result.SUBMITTED && sourceKey != null) {
                pendingRemovals.put(sourceKey, new PendingRemoval(expected, quantity, System.nanoTime()));
                reconcileRemovals();
            }
            if (result == InventoryOperations.Result.SUBMITTED && target == NativeInventorySection.UTILITY
                    && displayedUtilitySlot < 0) {
                var container = InventoryOperations.resolveContainer(context.ref(), context.store(), target);
                // A submitted native move can be deferred; only activate a slot already populated by it.
                if (container != null && container == displayedContainers.get(target) && !ItemStack.isEmpty(container.getItemStack(targetSlot.shortValue()))) {
                    UtilitySlotSelector.selectActiveSlot(context, targetSlot);
                }
            }
            status = switch (result) {
                case SUBMITTED, SAME_SLOT -> "";
                case LOCKED -> "Inventory access is currently locked.";
                case STALE_SELECTION -> "The selected item changed. Try again.";
                case INVALID_QUANTITY -> "Invalid item quantity.";
                case INVALID_SOURCE, INVALID_TARGET -> "The inventory changed. Try again.";
                case DENIED, DROP_FAILED -> "The inventory cannot be changed.";
            };
        } finally {
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            dragOrigins.clear();
            hoveredSelection = null;
        }
    }

    /**
     * A stationary shift gesture has no destination-enter/drop callback. Complete
     * it on the native ItemGrid mouse-release event, and re-deliver the source's
     * authoritative slots so its detached client drag is reconciled immediately.
     * Releases over other slots remain owned by ItemGrid's normal Dropped event.
     */
    private void completeSourceRelease(InventoryContentEvent event) {
        var origin = dragOrigin;
        if (origin == null) return;
        boolean wheel = "CompleteUtilitySourceRelease".equals(event.action());
        var section = wheel ? NativeInventorySection.UTILITY : NativeInventorySection.parse(event.payload());
        var slots = section == null ? null : displayed.get(section);
        if (slots == null) return;
        Integer slot = wheel ? UtilitySlotProjection.wheelIndex(event.payload(), event.slotIndex(), slots.length)
                : section == NativeInventorySection.UTILITY
                ? UtilitySlotProjection.sourceIndex(event.slotIndex(), displayedUtilitySlot, slots.length, origin) : event.slotIndex();
        if (!completesShiftSourceRelease(shiftSourceGesture, event.shiftHeld(), event.rightMouseButton(),
                origin.section() == section && slot != null && origin.slot() == slot)) return;
        releasedSources.add(section);
        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        dragOrigins.clear();
        hoveredSelection = null;
        dropButtonSelection = null;
    }

    /** Classifies only the terminal mouse gesture; it never submits an item move. */
    static boolean completesShiftSourceRelease(boolean shiftAtPress, Boolean shiftAtRelease,
                                               boolean rightButton, boolean sameSourceSlot) {
        return !rightButton && sameSourceSlot && (shiftAtPress || Boolean.TRUE.equals(shiftAtRelease));
    }

    /** Drops only the stack currently carried by the client onto the full-screen dimmed backdrop. */
    private void dropOutside(InventoryContext context, InventoryContentEvent event) {
        reconcileRemovals();
        var drag = event.drag();
        if (drag == null && dragOrigin == null) {
            status = "The selected item changed. Try again.";
            return;
        }
        Integer sourceId = drag == null ? null : drag.sectionId();
        Integer sourceSlot = drag == null ? null : drag.slotId();
        String itemId = drag == null ? null : drag.itemId();
        Integer requested = drag == null ? null : drag.quantity();
        InventorySelection origin = null;
        DragKey sourceKey = sourceId == null || sourceSlot == null ? null : new DragKey(sourceId, sourceSlot);
        NativeInventorySection source = UtilitySlotProjection.isProjectedGrid(sourceId)
                ? NativeInventorySection.UTILITY
                : NativeInventorySection.fromId(sourceId == null ? Integer.MAX_VALUE : sourceId);

        if (UtilitySlotProjection.isProjectedGrid(sourceId)) {
            origin = sourceKey == null ? null : dragOrigins.get(sourceKey);
            if (origin == null) {
                var projectedKey = new DragKey(sourceId, 0);
                origin = dragOrigins.get(projectedKey);
                if (origin != null) sourceKey = projectedKey;
            }
            var utilitySlots = displayed.get(NativeInventorySection.UTILITY);
            int actualSlot = utilitySlots == null ? -1 : UtilitySlotProjection.dragSourceIndex(sourceId,
                    sourceSlot == null ? 0 : sourceSlot, displayedUtilitySlot, utilitySlots.length, origin);
            if (origin == null && actualSlot >= 0) {
                var resolved = resolveCapturedSource(context, NativeInventorySection.UTILITY, actualSlot, itemId, requested);
                if (resolved != null) {
                    origin = resolved.origin();
                    sourceKey = resolved.key();
                }
            }
        } else {
            if (sourceKey != null) origin = dragOrigins.get(sourceKey);
            if (origin == null && sourceId != null && sourceId == dragGridId) origin = dragOrigin;
            if (origin == null && dragOrigin != null && (itemId == null || itemId.equals(dragOrigin.itemId()))) {
                origin = dragOrigin;
                if (sourceKey == null) sourceKey = new DragKey(dragGridId, dragOrigin.slot());
            }
            if (origin == null) {
                Integer actualSlot = source == NativeInventorySection.UTILITY && sourceSlot != null && sourceSlot == 0
                        ? null : sourceSlot;
                var resolved = resolveCapturedSource(context, source, actualSlot, itemId, requested);
                if (resolved != null) {
                    origin = resolved.origin();
                    sourceKey = resolved.key();
                }
            }
        }
        if (origin == null && dragOrigin != null) {
            origin = dragOrigin;
            if (sourceKey == null) sourceKey = new DragKey(dragGridId, dragOrigin.slot());
        }

        if (origin == null || (itemId != null && !itemId.equals(origin.itemId()))) {
            status = "The selected item changed. Try again.";
            return;
        }

        int quantity = (requested != null && requested > 0)
                ? Math.min(requested, origin.quantity())
                : origin.quantity();

        if (quantity <= 0) {
            status = "Invalid item quantity.";
            return;
        }
        if (hasPendingMove(origin)) {
            status = "The previous inventory move is still pending. Try again.";
            return;
        }

        var result = InventoryOperations.drop(context.ref(), context.store(), origin, quantity);
        if (result == InventoryOperations.Result.SUBMITTED && sourceKey != null) {
            pendingRemovals.put(sourceKey, new PendingRemoval(origin, quantity, System.nanoTime()));
        }
        if (result == InventoryOperations.Result.SUBMITTED) {
            // A backdrop release consumes the whole cursor stack, including a split.
            // A second callback from the same release must not resolve its source again.
            dragOrigins.clear();
            dropButtonSelection = null;
        }
        status = switch (result) {
            case SUBMITTED -> "";
            case LOCKED -> "Inventory access is currently locked.";
            case INVALID_QUANTITY -> "Invalid item quantity.";
            case INVALID_SOURCE, INVALID_TARGET, STALE_SELECTION -> "The selected item changed. Try again.";
            case DENIED, DROP_FAILED, SAME_SLOT -> "The selected item cannot be dropped.";
        };
        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        hoveredSelection = null;
    }

    /** Captures a source before right-click/split gestures, which may not emit a left-click event. */
    public void observeUtilityHover(Integer index, boolean center) {
        observeHover(NativeInventorySection.UTILITY, 0, !center, index == null ? null : Integer.toString(index));
    }

    private void observeHover(NativeInventorySection section, Integer visibleSlot, boolean wheel, String payload) {
        var snapshot = displayed.get(section);
        if (snapshot == null || visibleSlot == null) return;
        int actual = wheel ? UtilitySlotProjection.wheelIndex(payload, visibleSlot, snapshot.length)
                : section == NativeInventorySection.UTILITY
                ? UtilitySlotProjection.sourceIndex(visibleSlot, displayedUtilitySlot, snapshot.length, null) : visibleSlot;
        if (actual < 0 || actual >= snapshot.length) return;
        var origin = InventorySelection.fromDisplayed(section, displayedContainers.get(section), actual, snapshot[actual]);
        hoveredSelection = origin;
        if (origin == null) return;
        dropButtonSelection = origin;
        int gridId = section == NativeInventorySection.UTILITY
                ? wheel ? UtilitySlotProjection.wheelGridId(actual) : UtilitySlotProjection.CENTER_GRID_ID : section.id();
        var key = new DragKey(gridId, visibleSlot);
        // Only register candidate drag origins when not already actively dragging an item.
        // Hovering other slots while carrying an item must not pollute dragOrigins.
        if (dragOrigin == null) {
            dragOrigins.put(key, origin);
        }
    }

    private void reconcileRemovals() {
        pendingRemovals.entrySet().removeIf(entry -> {
            var before = entry.getValue().origin();
            if (!InventoryOperations.validSlot(before.container(), before.slot())) return true;
            var current = before.container().getItemStack((short) before.slot());
            if (before.matches(current)) {
                // InventoryUtils has no completion result. Bound the wait so a vetoed
                // native request cannot leave a source permanently blocked.
                return System.nanoTime() - entry.getValue().submittedAt() >= PENDING_MOVE_TIMEOUT;
            }
            var remaining = before.afterRemoval(current, entry.getValue().quantity());
            if (remaining == null && !ItemStack.isEmpty(current)) return true; // Unrelated changes stay stale.
            dragOrigins.entrySet().removeIf(origin -> remaining == null && origin.getValue().sameSnapshot(before));
            if (remaining != null) dragOrigins.replaceAll((key, origin) -> origin.sameSnapshot(before) ? remaining : origin);
            if (before.sameSnapshot(dragOrigin)) dragOrigin = remaining;
            if (before.sameSnapshot(hoveredSelection)) hoveredSelection = remaining;
            if (before.sameSnapshot(dropButtonSelection)) dropButtonSelection = remaining;
            return true;
        });
    }

    private ResolvedSource resolveCapturedSource(InventoryContext context, NativeInventorySection section,
                                                 Integer actualSlot, String itemId, Integer quantity) {
        ResolvedSource result = null;
        for (var entry : dragOrigins.entrySet()) {
            var origin = entry.getValue();
            if ((section != null && origin.section() != section)
                    || (actualSlot != null && origin.slot() != actualSlot)
                    || (itemId != null && !itemId.equals(origin.itemId()))
                    || (quantity != null && (quantity <= 0 || quantity > origin.quantity()))
                    || InventoryOperations.resolveContainer(context.ref(), context.store(), origin.section()) != origin.container()
                    || !InventoryOperations.validSlot(origin.container(), origin.slot())
                    || !origin.matches(origin.container().getItemStack((short) origin.slot()))) continue;
            if (result != null && !result.origin().sameSnapshot(origin)) return null;
            result = new ResolvedSource(entry.getKey(), origin);
        }
        return result;
    }

    public void refreshDropAction(InventoryContext context, UICommandBuilder commands) {
        var source = hoveredSelection != null ? hoveredSelection : dropButtonSelection;
        if (source == null) source = dragOrigin;
        boolean disabled = InventoryOperations.locked(context.ref(), context.store()) || source == null
                || InventoryOperations.resolveContainer(context.ref(), context.store(), source.section()) != source.container()
                || !InventoryOperations.validSlot(source.container(), source.slot())
                || hasPendingMove(source)
                || !source.matches(source.container().getItemStack((short) source.slot()));
        if (dropDisabled == null || dropDisabled != disabled) {
            commands.set("#InventoryDropButton.Disabled", disabled);
            dropDisabled = disabled;
        }
        String language = context.playerRef().getLanguage();
        boolean portuguese = language != null && language.toLowerCase(Locale.ROOT).startsWith("pt");
        String tooltip = source == null
                ? portuguese ? "Passe o mouse sobre um item para escolher a pilha que deseja largar."
                    : "Hover an inventory item to select a stack to drop."
                : (portuguese ? "Largar a pilha selecionada" : "Drop the selected stack") + " (" + source.quantity() + ")";
        if (!tooltip.equals(dropTooltip)) {
            commands.set("#InventoryDropButton.TooltipText", tooltip);
            dropTooltip = tooltip;
        }
    }

    private boolean hasPendingMove(InventorySelection source) {
        return pendingRemovals.values().stream().anyMatch(pending -> pending.origin().sameSource(source));
    }

    private static String grid(NativeInventorySection section) {
        String panel = switch (section) {
            case STORAGE, HOTBAR -> "#InventoryPanelHost";
            case ARMOR, UTILITY -> "#PlayerPanelHost";
            case BACKPACK -> "#ContentHost";
        };
        return panel + " #" + section.gridId();
    }

    private static ItemStack itemAt(ItemStack[] stacks, int index) {
        return stacks != null && index >= 0 && index < stacks.length ? stacks[index] : null;
    }

    @Override
    public void onDismiss(InventoryContext context) {
        for (var listener : listeners.values()) listener.unregister();
        listeners.clear();
        displayed.clear();
        displayedContainers.clear();
        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        releasedSources.clear();
        dragOrigins.clear();
        pendingRemovals.clear();
        hoveredSelection = null;
        dropButtonSelection = null;
        displayedUtilitySlot = -1;
    }
}
