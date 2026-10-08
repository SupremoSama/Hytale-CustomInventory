package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.PickupLocation;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.CustomInventoryPlugin;
import com.supremosan.custominventory.api.InventoryContent;
import com.supremosan.custominventory.api.InventoryContentEvent;
import com.supremosan.custominventory.api.InventoryContext;
import com.supremosan.custominventory.api.InventoryEventBindings;
import com.supremosan.custominventory.api.InventoryRegistry;
import com.supremosan.custominventory.ui.player.UtilitySlotSelector;
import com.supremosan.custominventory.ui.InventoryText;
import com.supremosan.custominventory.ui.InventoryTooltips;

import java.util.Collections;
import java.util.Arrays;
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
            NativeInventorySection.ARMOR, NativeInventorySection.UTILITY, NativeInventorySection.EXTRA);
    private final InventoryRegistry registry;
    @FunctionalInterface
    interface MoveRequest {
        InventoryOperations.Result move(InventoryContext context, InventorySelection source,
                                        NativeInventorySection targetSection, int targetSlot, int quantity,
                                        ItemContainer sourceContainer, ItemContainer targetContainer);
    }
    private final MoveRequest moveRequest;
    private final Map<NativeInventorySection, ItemStack[]> displayed = new EnumMap<>(NativeInventorySection.class);
    private final Map<NativeInventorySection, String[]> displayedDescriptions = new EnumMap<>(NativeInventorySection.class);
    private final Map<NativeInventorySection, ItemContainer> displayedContainers = new EnumMap<>(NativeInventorySection.class);
    private final Map<ItemContainer, EventRegistration<Void, ItemContainer.ItemContainerChangeEvent>> listeners = new IdentityHashMap<>();
    private InventorySelection selection;
    private InventorySelection dragOrigin;
    private boolean shiftSourceGesture;
    private final Set<NativeInventorySection> releasedSources = java.util.EnumSet.noneOf(NativeInventorySection.class);
    private int dragGridId;
    private record DragKey(int grid, int slot) { }
    private record PendingRemoval(InventorySelection origin, int quantity, long submittedAt,
                                  InventorySelection heldBefore) {
        PendingRemoval(InventorySelection origin, int quantity, long submittedAt) {
            this(origin, quantity, submittedAt, null);
        }
    }
    private record ResolvedSource(DragKey key, InventorySelection origin) { }
    private static final long PENDING_MOVE_TIMEOUT = TimeUnit.SECONDS.toNanos(2);
    private final Map<DragKey, InventorySelection> dragOrigins = new HashMap<>();
    private final Map<DragKey, PendingRemoval> pendingRemovals = new HashMap<>();
    private InventorySelection hoveredSelection;
    private InventorySelection dropButtonSelection;
    private Boolean dropDisabled;
    private String dropTooltip;
    private Boolean activeHintsVisible;
    private String status = "";
    private int activeHotbarSlot = Integer.MIN_VALUE;
    private int displayedUtilitySlot = -1;
    private NativeInventorySection lastClickedSection;
    private Integer lastClickedSlot;
    private long lastClickedTime;
    /** Right-button sweep: one deposit per visit; exiting a slot permits another deposit. */
    private boolean rightSweep;
    private final Set<DragKey> sweepVisited = new java.util.HashSet<>();
    /** Keep release bookkeeping until the matching Dropped callback has been consumed. */
    private final Set<DragKey> releasedRightPlacements = new java.util.HashSet<>();
    private record SweepSlot(NativeInventorySection section, int slot) { }
    /** Shift-drag: slots already quick-moved during the current shift gesture. */
    private final Set<SweepSlot> shiftSwept = new java.util.HashSet<>();
    /** Tracks whether the active drag holding session was initiated or held by right mouse button. */
    private boolean rightDragSession;

    public NativeInventoryContent() {
        this(resolveRegistry());
    }

    public NativeInventoryContent(InventoryRegistry registry) {
        this(registry, (context, source, targetSection, targetSlot, quantity, sourceContainer, targetContainer) ->
                InventoryOperations.move(context.ref(), context.store(), source, targetSection, targetSlot,
                        quantity, sourceContainer, targetContainer));
    }

    NativeInventoryContent(InventoryRegistry registry, MoveRequest moveRequest) {
        this.registry = registry != null ? registry : resolveRegistry();
        this.moveRequest = java.util.Objects.requireNonNull(moveRequest);
    }

    private static InventoryRegistry resolveRegistry() {
        try {
            var plugin = CustomInventoryPlugin.get();
            return plugin != null ? plugin.getInventoryRegistry() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The shell mounts both panels before invoking this controller beneath #InventoryShell. */
    @Override
    public void build(InventoryContext context, UICommandBuilder commands,
                      InventoryEventBindings events, String selector) {
        selection = null;
        dragOrigin = null;
        endSweep();
        shiftSourceGesture = false;
        releasedSources.clear();
        hoveredSelection = null;
        dragOrigins.clear();
        displayedDescriptions.clear();
        activeHotbarSlot = Integer.MIN_VALUE;
        dropDisabled = null;
        dropTooltip = null;
        activeHintsVisible = null;
        render(context, commands, events, selector);
    }

    /** Updates mounted slots without accumulating a second set of drag bindings. */
    public void refresh(InventoryContext context, UICommandBuilder commands, String selector) {
        render(context, commands, null, selector);
    }

    public boolean hasPendingReleasedSources() {
        return !releasedSources.isEmpty();
    }

    private void render(InventoryContext context, UICommandBuilder commands,
                        InventoryEventBindings events, String selector) {
        Set<ItemContainer> current = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var section : PERSISTENT_SECTIONS) {
            boolean sourceReleased = releasedSources.remove(section);
            ItemContainer container = InventoryOperations.resolveContainer(context.ref(), context.store(), section);
            var previous = displayed.get(section);
            var previousDescriptions = displayedDescriptions.get(section);
            boolean containerChanged = container != displayedContainers.get(section);
            int capacity = container == null ? 0 : container.getCapacity();
            ItemGridSlot[] slots = new ItemGridSlot[capacity];
            ItemStack[] snapshot = new ItemStack[capacity];
            String[] currentDescriptions = new String[capacity];
            for (short slot = 0; slot < capacity; slot++) {
                ItemStack stack = container.getItemStack(slot);
                String desc = registry != null
                        ? InventoryDisplay.description(stack, context, registry, section.name(), slot)
                        : null;
                currentDescriptions[slot] = desc;
                slots[slot] = InventoryDisplay.slot(stack, desc);
                if (section == NativeInventorySection.EXTRA && ItemStack.isEmpty(stack)) {
                    String icon = switch (slot) { case 0 -> "Hat"; case 1 -> "Backpack"; case 2 -> "Collar"; default -> "Belt"; };
                    slots[slot].setIcon(Value.ref("Inventory/ExtraEquipment.ui", "Empty" + icon));
                }
                if (section == NativeInventorySection.ARMOR && ItemStack.isEmpty(stack) && slot < 4) {
                    String icon = switch (slot) { case 0 -> "Head"; case 1 -> "Chest"; case 2 -> "Hands"; default -> "Legs"; };
                    slots[slot].setIcon(Value.ref("Inventory/InventoryGridStyle.ui", "EmptyArmor" + icon));
                }
                snapshot[slot] = InventorySelection.snapshot(stack);
            }
            boolean descriptionsChanged = !Arrays.equals(previousDescriptions, currentDescriptions);
            boolean slotsChanged = events != null || sourceReleased || containerChanged || descriptionsChanged || !Arrays.equals(previous, snapshot);
            boolean centerChanged = slotsChanged;
            displayed.put(section, snapshot);
            displayedDescriptions.put(section, currentDescriptions);
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
                        var stack = itemAt(snapshot, index);
                        String desc = registry != null
                                ? InventoryDisplay.description(stack, context, registry, NativeInventorySection.UTILITY.name(), index)
                                : null;
                        var choice = new ItemGridSlot[]{InventoryDisplay.slot(stack, desc)};
                        commands.set(selector + " #PlayerPanelHost #UtilityChoiceGrid" + index + ".Slots", choice);
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
                events.bind(CustomUIEventBindingType.SlotClickPressWhileDragging, grid, "DragPress", section.name(), false);
                events.bind(CustomUIEventBindingType.SlotClickReleaseWhileDragging, grid, "CompleteSourceRelease", section.name(), false);
                events.bind(CustomUIEventBindingType.Dropped, grid, "Drop", section.name(), false);
                events.bind(CustomUIEventBindingType.SlotDoubleClicking, grid, "DoubleClickSlot", section.name(), false);
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
        commands.set(selector + " #InventoryPanelHost #InventoryStatus.Text",
                status.isBlank() ? "" : InventoryText.get(context.playerRef().getLanguage(), status));
        commands.set(selector + " #InventoryPanelHost #InventoryStatus.Visible", !status.isBlank());
        commands.set(selector + " #InventoryPanelHost #InventorySortButton.TooltipText", InventoryTooltips.sort());
        if (events != null) events.bind(CustomUIEventBindingType.Activating, "#InventoryPanelHost #InventorySortButton", "Sort", "", false);
        commands.set(selector + " #InventoryPanelHost #InventoryDropAllButton.TooltipText", InventoryText.get(context.playerRef().getLanguage(), "tooltip.drop_all"));
        if (events != null) events.bind(CustomUIEventBindingType.Activating, "#InventoryPanelHost #InventoryDropAllButton", "DropAll", "", false);
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
        var previousDescriptions = displayedDescriptions.get(section);
        boolean containerChanged = container != displayedContainers.get(section);
        int capacity = container == null ? 0 : container.getCapacity();
        var slots = new ItemGridSlot[capacity];
        var snapshot = new ItemStack[capacity];
        String[] currentDescriptions = new String[capacity];
        for (short slot = 0; slot < capacity; slot++) {
            var stack = container.getItemStack(slot);
            String desc = registry != null
                    ? InventoryDisplay.description(stack, context, registry, section.name(), slot)
                    : null;
            currentDescriptions[slot] = desc;
            slots[slot] = InventoryDisplay.slot(stack, desc);
            snapshot[slot] = InventorySelection.snapshot(stack);
        }
        boolean descriptionsChanged = !Arrays.equals(previousDescriptions, currentDescriptions);
        displayed.put(section, snapshot);
        displayedDescriptions.put(section, currentDescriptions);
        if (container != null) {
            displayedContainers.put(section, container);
            listeners.computeIfAbsent(container, c -> c.registerChangeEvent(change -> context.requestRefresh()));
        } else displayedContainers.remove(section);
        String grid = grid(section);
        if (bindEvents || sourceReleased || containerChanged || descriptionsChanged || !Arrays.equals(previous, snapshot))
            commands.set(selector + " " + grid + ".Slots", slots);
        if (bindEvents) {
            events.bind(CustomUIEventBindingType.SlotClicking, grid, "DragSource", section.name(), false);
            events.bind(CustomUIEventBindingType.SlotClickPressWhileDragging, grid, "DragPress", section.name(), false);
            events.bind(CustomUIEventBindingType.SlotClickReleaseWhileDragging, grid, "CompleteSourceRelease", section.name(), false);
            events.bind(CustomUIEventBindingType.Dropped, grid, "Drop", section.name(), false);
            events.bind(CustomUIEventBindingType.SlotDoubleClicking, grid, "DoubleClickSlot", section.name(), false);
            events.bind(CustomUIEventBindingType.DragCancelled, grid, "CancelDrag", "", false);
            events.bind(CustomUIEventBindingType.SlotMouseEntered, grid, "HoverSource", section.name(), false);
            events.bind(CustomUIEventBindingType.SlotMouseExited, grid, "UnhoverSource", section.name(), false);
        }
    }

    public void unmountBackpack() {
        displayed.remove(NativeInventorySection.BACKPACK);
        displayedDescriptions.remove(NativeInventorySection.BACKPACK);
        displayedContainers.remove(NativeInventorySection.BACKPACK);
        if (selection != null && selection.section() == NativeInventorySection.BACKPACK) selection = null;
        if (dragOrigin != null && dragOrigin.section() == NativeInventorySection.BACKPACK) {
            dragOrigin = null;
            shiftSourceGesture = false;
            rightDragSession = false;
        }
        if (lastClickedSection == NativeInventorySection.BACKPACK) {
            lastClickedSection = null;
            lastClickedSlot = null;
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
        if (event.mouseButton() != null && !event.rightMouseButton() && !isLeftButton(event)) {
            // Middle/auxiliary buttons belong to native resizing and must not place a stack.
            boolean slotClick = switch (event.action() == null ? "" : event.action()) {
                case "DragSource", "UtilityWheelDragSource", "DragPress", "UtilityWheelDragPress",
                     "DragRelease", "UtilityWheelDragRelease", "CompleteSourceRelease", "CompleteUtilitySourceRelease",
                     "Drop", "UtilityWheelDrop", "DoubleClickSlot" -> true;
                default -> false;
            };
            if (slotClick) return;
        }
        if ("UnhoverSource".equals(event.action())) {
            hoveredSelection = null;
            NativeInventorySection unhoverSection = NativeInventorySection.parse(event.payload());
            if (unhoverSection != null && event.slotIndex() != null) {
                Integer slot = unhoverSection == NativeInventorySection.UTILITY
                        ? destinationSlot(unhoverSection, false, event) : event.slotIndex();
                if (slot != null) sweepVisited.remove(new DragKey(unhoverSection.id(), slot));
            }
            return;
        }
        if ("CancelDrag".equals(event.action())) {
            endSweep();
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            rightDragSession = false;
            // Hiding a wheel source can emit this while the client still holds its stack.
            // Keep its keyed snapshot, but do not use it as the next gesture's default.
            return;
        }
        if ("DragPress".equals(event.action()) || "UtilityWheelDragPress".equals(event.action())) {
            ensureDragOrigin(event);
        }
        boolean release = "DragRelease".equals(event.action()) || "UtilityWheelDragRelease".equals(event.action())
                || "CompleteSourceRelease".equals(event.action()) || "CompleteUtilitySourceRelease".equals(event.action());
        if (release) {
            if (rightSweep || event.rightMouseButton()) {
                finishRightSweep();
                return;
            }
            if (completeSourceRelease(context, event)) {
                return;
            }
            if (event.drag() != null && event.drag().rightDrag()) {
                return;
            }
            if (context == null) return;
        }
        if ("DoubleClickSlot".equals(event.action())) {
            NativeInventorySection targetSection = NativeInventorySection.parse(event.payload());
            if (targetSection != null && event.slotIndex() != null) {
                combineItemStacks(context, targetSection, event.slotIndex());
            }
            return;
        }
        if (context != null && InventoryOperations.locked(context.ref(), context.store())) {
            endSweep();
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            rightDragSession = false;
            status = "status.locked";
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
                status = "status.pending";
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
            status = result == InventoryOperations.Result.SUBMITTED ? "" : "status.cannot_drop";
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            rightDragSession = false;
            hoveredSelection = null;
            dropButtonSelection = null;
            dragOrigins.clear();
            endSweep();
            return;
        }
        if ("DropAll".equals(event.action())) {
            dropAllOfType(context);
            return;
        }
        if ("Sort".equals(event.action())) {
            reconcileRemovals();
            if (!pendingRemovals.isEmpty()) {
                status = "status.pending";
                return;
            }
            selection = null;
            dragOrigin = null;
            shiftSourceGesture = false;
            rightDragSession = false;
            dragOrigins.clear();
            pendingRemovals.clear();
            endSweep();
            InventoryUtils.sortStorage(context.ref(), context.store());
            status = "";
            return;
        }
        boolean press = "DragPress".equals(event.action()) || "UtilityWheelDragPress".equals(event.action());
        // Right placement happens on press; its release must not place a second time.
        if (press && !event.rightMouseButton() || release && (rightSweep || event.rightMouseButton())) {
            if (press) endSweep();
            if (release) finishRightSweep();
            return;
        }
        boolean wheel = "UtilityWheelDragSource".equals(event.action()) || "UtilityWheelDrop".equals(event.action())
                || "UtilityWheelDropOne".equals(event.action()) || "UtilityWheelDragPress".equals(event.action())
                || "UtilityWheelDragRelease".equals(event.action()) || "CompleteUtilitySourceRelease".equals(event.action())
                || "UtilityWheelHover".equals(event.action()) || "UtilityWheelUnhover".equals(event.action());
        NativeInventorySection target = wheel ? NativeInventorySection.UTILITY : NativeInventorySection.parse(event.payload());
        if (target == null || !displayed.containsKey(target)) return;

        boolean isDrop = "Drop".equals(event.action()) || "UtilityWheelDrop".equals(event.action());
        Integer destination = destinationSlot(target, wheel, event);
        var destinationKey = destination == null ? null : new DragKey(target.id(), destination);
        if (isDrop && destinationKey != null) {
            if (sweepVisited.contains(destinationKey) && (rightSweep || event.rightMouseButton())
                    || releasedRightPlacements.contains(destinationKey) && !isLeftButton(event)) {
                finishRightSweep();
                releasedRightPlacements.clear();
                return;
            }
        }

        String action = "UtilityWheelDragSource".equals(event.action()) ? "DragSource"
                : "UtilityWheelDropOne".equals(event.action()) || press || (isDrop && event.rightMouseButton()) ? "DropOne"
                : isDrop || release ? "Drop"
                : "UtilityWheelHover".equals(event.action()) ? "HoverSource" : event.action();
        if ("UtilityWheelUnhover".equals(action)) {
            hoveredSelection = null;
            sweepVisited.remove(destinationKey);
            return;
        }
        if ("HoverSource".equals(action)) {
            // Carrying a stack with the right button held: place one in each newly entered compatible slot.
            if (dragOrigin != null && dragOrigin.quantity() > 0 && (rightSweep || event.rightMouseButton() || rightDragSession)
                    && destination != null) {
                if (!sweepVisited.contains(destinationKey) && canDepositOne(target, destination)) {
                    if (!rightSweep) releasedRightPlacements.clear();
                    rightSweep = true;
                    action = "DropOne";
                }
            }
            if (!"DropOne".equals(action)) {
                if (dragOrigin != null && event.slotIndex() != null && target != NativeInventorySection.UTILITY) {
                    var entered = new SweepSlot(target, event.slotIndex());
                    if (shiftSourceGesture && !Boolean.FALSE.equals(event.shiftHeld())) {
                        // Shift held across slots: quick-move each one once (Minecraft shift-drag).
                        if (!(entered.section() == dragOrigin.section() && entered.slot() == dragOrigin.slot())
                                && shiftSwept.add(entered) && context != null
                                && !InventoryOperations.locked(context.ref(), context.store())) {
                            quickMove(context, target, event.slotIndex());
                        }
                    }
                }
                observeHover(target, event.slotIndex(), wheel, event.payload());
                return;
            }
        }
        if ("DropOne".equals(action)) {
            // Late terminal callbacks cannot recreate a holding gesture from a source remainder.
            if (dragOrigin == null || destinationKey == null) return;
            if (press) {
                if (!rightSweep) releasedRightPlacements.clear();
                rightSweep = true;
            }
            if (rightSweep && !sweepVisited.add(destinationKey)) return;
        } else if ("Drop".equals(action)) {
            endSweep();
            if (dragOrigin == null && dragOrigins.isEmpty()) return;
        }
        if ("DragSource".equals(action)) {
            reconcileRemovals();
            // SlotClicking can accompany a destination click; keep the existing held source.
            if (dragOrigin != null) return;
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
                int carried = event.rightMouseButton()
                        ? Boolean.TRUE.equals(event.shiftHeld()) ? (captured.quantity() + 1) / 2 : 1
                        : captured.quantity();
                if (metadata != null && metadata.quantity() != null && metadata.quantity() > 0) {
                    carried = Math.min(carried, metadata.quantity());
                }
                if (carried < captured.quantity()) {
                    captured = captured.withQuantity(carried);
                    dragOrigins.put(key, captured);
                }
                if (dragOrigin == null) endSweep();
                selection = captured;
                dragOrigin = captured;
                dragGridId = sourceGrid;
                shiftSourceGesture = Boolean.TRUE.equals(event.shiftHeld()) && !event.rightMouseButton();
                rightDragSession = event.rightMouseButton();
            }
            lastClickedSection = target;
            lastClickedSlot = slot;
            lastClickedTime = System.currentTimeMillis();
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
                status = "status.invalid_drop";
                return;
            }
            if ("DropOne".equals(action) && !canDepositOne(target, targetSlot)) {
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
                    status = "status.item_changed";
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
                status = "status.invalid_drop";
                return;
            }
            var expected = retainedOrigin != null && retainedOrigin.section() == source && retainedOrigin.slot() == sourceSlot
                    ? retainedOrigin : projectedSource ? null
                    : InventorySelection.fromDisplayed(source, displayedContainers.get(source), sourceSlot, sourceSlots[sourceSlot]);
            String id = drag == null ? null : drag.itemId();
            if (expected == null || (id != null && !id.equals(expected.itemId()))) {
                status = "status.item_changed";
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
                status = "status.pending";
                return;
            }
            if ("DropOne".equals(action) && source == target && java.util.Objects.equals(sourceSlot, targetSlot)) {
                consumeHeld(1);
                releasedSources.add(target);
                if (context != null) context.requestRefresh();
                status = "";
                return;
            }
            var beforeMove = InventorySelection.capture(source, displayedContainers.get(source), sourceSlot);
            var result = moveRequest.move(context, expected, target, targetSlot, quantity,
                    displayedContainers.get(source), displayedContainers.get(target));
            if (result == InventoryOperations.Result.SUBMITTED && sourceKey != null && beforeMove != null) {
                // Submission can be vetoed/deferred. Consume only a confirmed source removal.
                pendingRemovals.put(sourceKey, new PendingRemoval(beforeMove, quantity, System.nanoTime(), dragOrigin));
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
            long now = System.currentTimeMillis();
            boolean sameSlotDoubleClick = lastClickedSection == target && lastClickedSlot != null
                    && lastClickedSlot.equals(targetSlot) && (now - lastClickedTime) < 400;
            lastClickedSection = target;
            lastClickedSlot = targetSlot;
            lastClickedTime = now;
            if (result == InventoryOperations.Result.SAME_SLOT) {
                if (sameSlotDoubleClick) {
                    combineItemStacks(context, target, targetSlot);
                    return;
                }
                consumeHeld(quantity);
                releasedSources.add(target);
                if (source != null) releasedSources.add(source);
                if (context != null) context.requestRefresh();
            } else if (result == InventoryOperations.Result.SUBMITTED) {
                releasedSources.add(target);
                if (source != null) releasedSources.add(source);
                if (context != null) context.requestRefresh();
            }
            status = switch (result) {
                case SUBMITTED, SAME_SLOT -> "";
                case LOCKED -> "status.locked";
                case STALE_SELECTION -> "status.item_changed";
                case INVALID_QUANTITY -> "status.invalid_quantity";
                case INVALID_SOURCE, INVALID_TARGET -> "status.inventory_changed";
                case DENIED, DROP_FAILED -> "status.cannot_change";
            };
        } finally {
            selection = dragOrigin;
            if (dragOrigin == null) {
                shiftSourceGesture = false;
                dragOrigins.clear();
                rightDragSession = false;
            }
            if (isDrop) {
                finishRightSweep();
                rightDragSession = false;
            }
            hoveredSelection = null;
        }
    }

    /**
     * A stationary shift gesture has no destination-enter/drop callback. Complete
     * it on the native ItemGrid mouse-release event, and re-deliver the source's
     * authoritative slots so its detached client drag is reconciled immediately.
     * Releases over other slots remain owned by ItemGrid's normal Dropped event.
     */
    private boolean completeSourceRelease(InventoryContext context, InventoryContentEvent event) {
        var origin = dragOrigin;
        if (origin == null) return false;
        boolean wheel = "CompleteUtilitySourceRelease".equals(event.action());
        var section = wheel ? NativeInventorySection.UTILITY : NativeInventorySection.parse(event.payload());
        var slots = section == null ? null : displayed.get(section);
        if (slots == null) return false;
        Integer slot = wheel ? UtilitySlotProjection.wheelIndex(event.payload(), event.slotIndex(), slots.length)
                : section == NativeInventorySection.UTILITY
                ? UtilitySlotProjection.sourceIndex(event.slotIndex(), displayedUtilitySlot, slots.length, origin) : event.slotIndex();
        boolean sameSlot = origin.section() == section && slot != null && origin.slot() == slot;
        if (!completesShiftSourceRelease(shiftSourceGesture, event.shiftHeld(), event.rightMouseButton(), sameSlot)) {
            return false;
        }

        if (context != null && !InventoryOperations.locked(context.ref(), context.store())) quickMove(context, section, slot);

        releasedSources.add(section);
        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        dragOrigins.clear();
        hoveredSelection = null;
        dropButtonSelection = null;
        return true;
    }

    private void endSweep() {
        rightSweep = false;
        sweepVisited.clear();
        releasedRightPlacements.clear();
        shiftSwept.clear();
    }

    private void finishRightSweep() {
        if (rightSweep) {
            releasedRightPlacements.clear();
            releasedRightPlacements.addAll(sweepVisited);
        }
        rightSweep = false;
        rightDragSession = false;
        sweepVisited.clear();
        shiftSwept.clear();
    }

    private void consumeHeld(int quantity) {
        if (dragOrigin == null) return;
        var before = dragOrigin;
        dragOrigin = before.withQuantity(before.quantity() - quantity);
        selection = dragOrigin;
        if (dragOrigin == null) {
            dragOrigins.clear();
            endSweep();
        } else {
            var updated = dragOrigin;
            dragOrigins.replaceAll((key, origin) -> origin.sameSource(before) ? updated : origin);
        }
    }

    private void ensureDragOrigin(InventoryContentEvent event) {
        if (event == null || event.drag() == null) return;
        if (event.drag().rightDrag() || event.rightMouseButton()) {
            rightDragSession = true;
        }
        if ("DragSource".equals(event.action()) || "UtilityWheelDragSource".equals(event.action())) return;
        var drag = event.drag();
        Integer sourceId = drag.sectionId();
        Integer sourceSlot = drag.slotId();
        if (sourceId == null || sourceSlot == null) return;

        boolean projected = UtilitySlotProjection.isProjectedGrid(sourceId);
        NativeInventorySection source = projected ? NativeInventorySection.UTILITY
                : NativeInventorySection.fromId(sourceId);
        if (source == null) return;

        if (projected) {
            var utilitySlots = displayed.get(NativeInventorySection.UTILITY);
            sourceSlot = utilitySlots == null ? -1 : UtilitySlotProjection.dragSourceIndex(sourceId, sourceSlot,
                    displayedUtilitySlot, utilitySlots.length, dragOrigin);
            if (sourceSlot < 0) return;
        }

        String itemId = drag.itemId();
        // If dragOrigin is already actively tracking this exact source and item, preserve its remaining carried quantity.
        if (dragOrigin != null && dragOrigin.section() == source && dragOrigin.slot() == sourceSlot
                && (itemId == null || itemId.equals(dragOrigin.itemId()))) {
            return;
        }

        var container = displayedContainers.get(source);
        if (!InventoryOperations.validSlot(container, sourceSlot)) return;

        var currentStack = container.getItemStack(sourceSlot.shortValue());
        var currentSnapshot = displayed.get(source);
        ItemStack snapshotStack = (currentSnapshot != null && sourceSlot < currentSnapshot.length
                && !ItemStack.isEmpty(currentSnapshot[sourceSlot]))
                ? currentSnapshot[sourceSlot] : currentStack;

        if (ItemStack.isEmpty(snapshotStack)) {
            if (itemId != null && drag.quantity() != null && drag.quantity() > 0) {
                snapshotStack = createHeldStack(itemId, drag.quantity());
            } else {
                return;
            }
        }
        if (itemId != null && !itemId.equals(snapshotStack.getItemId())) return;

        var captured = InventorySelection.fromDisplayed(source, container, sourceSlot, snapshotStack);
        if (captured == null) return;

        Integer dragQty = drag.quantity();
        int carried = (dragQty != null && dragQty > 0) ? Math.min(dragQty, captured.quantity()) : captured.quantity();
        if (carried < captured.quantity()) {
            captured = captured.withQuantity(carried);
        }

        dragOrigin = captured;
        dragGridId = sourceId;
        selection = captured;
        dragOrigins.put(new DragKey(sourceId, projected ? 0 : sourceSlot), captured);
    }

    private static final class SyntheticHeldStack extends ItemStack {
        SyntheticHeldStack(String id, int count, org.bson.BsonDocument data) {
            this.itemId = id;
            this.quantity = count;
            this.metadata = data;
        }

        @Override
        public String getItemId() {
            return itemId;
        }

        @Override
        public int getQuantity() {
            return quantity;
        }

        @Override
        public com.hypixel.hytale.server.core.asset.type.item.config.Item getItem() {
            try {
                var map = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap();
                if (map != null) {
                    var asset = map.getAsset(itemId);
                    if (asset != null) return asset;
                }
            } catch (Throwable ignored) {}
            return com.hypixel.hytale.server.core.asset.type.item.config.Item.UNKNOWN;
        }

        @Override
        public ItemStack withQuantity(int count) {
            if (count <= 0) return null;
            if (count == this.quantity) return this;
            var copy = new SyntheticHeldStack(itemId, count, metadata);
            copy.setOverrideDroppedItemAnimation(getOverrideDroppedItemAnimation());
            return copy;
        }

        @Override
        public ItemStack withMetadata(org.bson.BsonDocument data) {
            var copy = new SyntheticHeldStack(itemId, quantity, data);
            copy.setOverrideDroppedItemAnimation(getOverrideDroppedItemAnimation());
            return copy;
        }

        @Override
        public boolean isEmpty() {
            return quantity <= 0 || itemId == null || itemId.isBlank() || "Empty".equals(itemId);
        }

        @Override
        public boolean isStackableWith(ItemStack other) {
            return other != null && java.util.Objects.equals(this.itemId, other.getItemId());
        }

        @Override
        public boolean isEquivalentType(ItemStack other) {
            return other != null && java.util.Objects.equals(this.itemId, other.getItemId());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ItemStack otherStack)) return false;
            return this.quantity == otherStack.getQuantity()
                    && java.util.Objects.equals(this.itemId, otherStack.getItemId());
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(itemId, quantity);
        }
    }

    private static ItemStack createHeldStack(String itemId, int quantity) {
        try {
            return new ItemStack(itemId, quantity);
        } catch (Throwable fallback) {
            return new SyntheticHeldStack(itemId, quantity, null);
        }
    }

    private Integer destinationSlot(NativeInventorySection target, boolean wheel, InventoryContentEvent event) {
        var slots = displayed.get(target);
        if (slots == null) return null;
        return wheel ? UtilitySlotProjection.wheelIndex(event.payload(), event.slotIndex(), slots.length)
                : target == NativeInventorySection.UTILITY
                ? UtilitySlotProjection.destinationIndex(event.slotIndex(), slots, displayedUtilitySlot) : event.slotIndex();
    }

    private static boolean isLeftButton(InventoryContentEvent event) {
        if (event == null || event.mouseButton() == null) return false;
        return "Left".equalsIgnoreCase(event.mouseButton()) || "0".equals(event.mouseButton()) || "1".equals(event.mouseButton())
                || "LeftButton".equalsIgnoreCase(event.mouseButton()) || "LeftMouseButton".equalsIgnoreCase(event.mouseButton());
    }

    /** Checks whether a slot can receive 1 deposited item from the held cursor stack. */
    public boolean canDepositOne(NativeInventorySection targetSection, int targetSlot) {
        if (dragOrigin == null || dragOrigin.quantity() <= 0) return false;
        var container = displayedContainers.get(targetSection);
        if (!InventoryOperations.validSlot(container, targetSlot)) return false;
        if (!InventoryOperations.validSlot(dragOrigin.container(), dragOrigin.slot())) return false;
        var source = dragOrigin.container().getItemStack((short) dragOrigin.slot());
        if (!dragOrigin.canTakeFrom(source, 1)) return false;
        if (container == dragOrigin.container() && targetSlot == dragOrigin.slot()) return true;
        return container.canAddItemStackToSlot((short) targetSlot, source.withQuantity(1), false, true);
    }

    /** Native shift-click destination rules, shared by shift-release and shift-drag. */
    private void quickMove(InventoryContext context, NativeInventorySection section, Integer slot) {
        if (section == NativeInventorySection.EXTRA) {
            var source = displayedContainers.get(section);
            var storage = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.STORAGE);
            if (slot != null && InventoryOperations.validSlot(source, slot) && storage != null) {
                source.moveItemStackFromSlot(slot.shortValue(), storage);
                releasedSources.add(section);
                releasedSources.add(NativeInventorySection.STORAGE);
                context.requestRefresh();
            }
            return;
        }
        ItemContainer bpContainer = displayedContainers.get(NativeInventorySection.BACKPACK);
        boolean backpackOpen = bpContainer != null;
        if (backpackOpen) {
            if (section == NativeInventorySection.STORAGE || section == NativeInventorySection.HOTBAR) {
                ItemContainer sourceContainer = displayedContainers.get(section);
                if (sourceContainer == null) {
                    sourceContainer = InventoryOperations.resolveContainer(context.ref(), context.store(), section);
                }
                if (sourceContainer != null && slot != null && slot >= 0 && slot < sourceContainer.getCapacity()) {
                    ItemStack sourceStack = sourceContainer.getItemStack(slot.shortValue());
                    if (!ItemStack.isEmpty(sourceStack)) {
                        sourceContainer.moveItemStackFromSlot(slot.shortValue(), sourceStack.getQuantity(), bpContainer);
                        releasedSources.add(section);
                        releasedSources.add(NativeInventorySection.BACKPACK);
                        context.requestRefresh();
                    }
                }
            } else if (section == NativeInventorySection.BACKPACK) {
                if (slot != null && slot >= 0 && slot < bpContainer.getCapacity()) {
                    ItemStack sourceStack = bpContainer.getItemStack(slot.shortValue());
                    if (!ItemStack.isEmpty(sourceStack)) {
                        PickupLocation pickupPref = PickupLocation.Storage;
                        try {
                            var entityModule = com.hypixel.hytale.server.core.modules.entity.EntityModule.get();
                            if (entityModule != null) {
                                var settingsType = entityModule.getPlayerSettingsComponentType();
                                if (settingsType != null) {
                                    var settings = context.store().getComponent(context.ref(), settingsType);
                                    if (settings != null) {
                                        pickupPref = settings.miscItemsPreferredPickupLocation();
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                        ItemContainer storage = displayedContainers.get(NativeInventorySection.STORAGE);
                        if (storage == null) storage = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.STORAGE);
                        ItemContainer hotbar = displayedContainers.get(NativeInventorySection.HOTBAR);
                        if (hotbar == null) hotbar = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.HOTBAR);

                        if (storage != null && hotbar != null) {
                            if (pickupPref == PickupLocation.Hotbar) {
                                bpContainer.moveItemStackFromSlot(slot.shortValue(), sourceStack.getQuantity(), hotbar, storage);
                            } else {
                                bpContainer.moveItemStackFromSlot(slot.shortValue(), sourceStack.getQuantity(), storage, hotbar);
                            }
                        } else if (storage != null) {
                            bpContainer.moveItemStackFromSlot(slot.shortValue(), sourceStack.getQuantity(), storage);
                        } else if (hotbar != null) {
                            bpContainer.moveItemStackFromSlot(slot.shortValue(), sourceStack.getQuantity(), hotbar);
                        }
                        releasedSources.add(NativeInventorySection.BACKPACK);
                        releasedSources.add(NativeInventorySection.STORAGE);
                        releasedSources.add(NativeInventorySection.HOTBAR);
                        context.requestRefresh();
                    }
                }
            }
        } else {
            if (section == NativeInventorySection.STORAGE) {
                ItemContainer storageContainer = displayedContainers.get(NativeInventorySection.STORAGE);
                if (storageContainer == null) storageContainer = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.STORAGE);
                ItemContainer hotbarContainer = displayedContainers.get(NativeInventorySection.HOTBAR);
                if (hotbarContainer == null) hotbarContainer = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.HOTBAR);
                if (storageContainer != null && hotbarContainer != null && slot != null && slot >= 0 && slot < storageContainer.getCapacity()) {
                    ItemStack sourceStack = storageContainer.getItemStack(slot.shortValue());
                    if (!ItemStack.isEmpty(sourceStack)) {
                        storageContainer.moveItemStackFromSlot(slot.shortValue(), sourceStack.getQuantity(), hotbarContainer);
                        releasedSources.add(NativeInventorySection.STORAGE);
                        releasedSources.add(NativeInventorySection.HOTBAR);
                        context.requestRefresh();
                    }
                }
            } else if (section == NativeInventorySection.HOTBAR) {
                ItemContainer hotbarContainer = displayedContainers.get(NativeInventorySection.HOTBAR);
                if (hotbarContainer == null) hotbarContainer = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.HOTBAR);
                ItemContainer storageContainer = displayedContainers.get(NativeInventorySection.STORAGE);
                if (storageContainer == null) storageContainer = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.STORAGE);
                if (hotbarContainer != null && storageContainer != null && slot != null && slot >= 0 && slot < hotbarContainer.getCapacity()) {
                    ItemStack sourceStack = hotbarContainer.getItemStack(slot.shortValue());
                    if (!ItemStack.isEmpty(sourceStack)) {
                        hotbarContainer.moveItemStackFromSlot(slot.shortValue(), sourceStack.getQuantity(), storageContainer);
                        releasedSources.add(NativeInventorySection.HOTBAR);
                        releasedSources.add(NativeInventorySection.STORAGE);
                        context.requestRefresh();
                    }
                }
            }
        }
    
    }

    /** Drops every stack matching the hovered (or last selected) item from the player's own sections. */
    private void dropAllOfType(InventoryContext context) {
        reconcileRemovals();
        var source = hoveredSelection != null ? hoveredSelection : dropButtonSelection;
        if (source == null) {
            status = "status.drop_all_hover";
            return;
        }
        if (InventoryOperations.locked(context.ref(), context.store())) {
            status = "status.locked";
            return;
        }
        String itemId = source.itemId();
        boolean failed = false;
        for (var section : List.of(NativeInventorySection.STORAGE, NativeInventorySection.HOTBAR, NativeInventorySection.BACKPACK)) {
            var container = displayedContainers.get(section);
            if (container == null) continue;
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                var stack = container.getItemStack(slot);
                if (ItemStack.isEmpty(stack) || !itemId.equals(stack.getItemId())) continue;
                var captured = InventorySelection.capture(section, container, slot);
                if (captured == null || hasPendingMove(captured)) continue;
                if (InventoryOperations.drop(context.ref(), context.store(), captured, captured.quantity())
                        == InventoryOperations.Result.SUBMITTED) {
                    pendingRemovals.put(new DragKey(section.id(), slot), new PendingRemoval(captured, captured.quantity(), System.nanoTime()));
                    releasedSources.add(section);
                } else failed = true;
            }
        }
        status = failed ? "status.drop_all_failed" : "";
        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        hoveredSelection = null;
        dropButtonSelection = null;
        dragOrigins.clear();
        endSweep();
        context.requestRefresh();
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
            status = "status.item_changed";
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
            status = "status.item_changed";
            return;
        }

        int quantity = (requested != null && requested > 0)
                ? Math.min(requested, origin.quantity())
                : origin.quantity();

        if (quantity <= 0) {
            status = "status.invalid_quantity";
            return;
        }
        if (hasPendingMove(origin)) {
            status = "status.pending";
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
            case LOCKED -> "status.locked";
            case INVALID_QUANTITY -> "status.invalid_quantity";
            case INVALID_SOURCE, INVALID_TARGET, STALE_SELECTION -> "status.item_changed";
            case DENIED, DROP_FAILED, SAME_SLOT -> "status.cannot_drop";
        };
        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        hoveredSelection = null;
        endSweep();
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
            if (ItemStack.isEmpty(current) && before.quantity() > entry.getValue().quantity()) return true;
            if (remaining == null && !ItemStack.isEmpty(current)) return true; // Unrelated changes stay stale.
            var heldBefore = entry.getValue().heldBefore();
            if (heldBefore != null) {
                if (heldBefore.sameSnapshot(dragOrigin)) {
                    int removed = before.quantity() - (ItemStack.isEmpty(current) ? 0 : current.getQuantity());
                    consumeHeld(Math.min(removed, entry.getValue().quantity()));
                }
                return true;
            }
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
        var resolvedContainer = source == null ? null : InventoryOperations.resolveContainer(context.ref(), context.store(), source.section());
        ItemContainer activeContainer = source == null ? null : (resolvedContainer != null ? resolvedContainer : displayedContainers.get(source.section()));
        boolean disabled = InventoryOperations.locked(context.ref(), context.store()) || source == null
                || activeContainer != source.container()
                || !InventoryOperations.validSlot(source.container(), source.slot())
                || hasPendingMove(source)
                || (source != dragOrigin && !source.matches(source.container().getItemStack((short) source.slot())));
        if (dropDisabled == null || dropDisabled != disabled) {
            commands.set("#InventoryDropButton.Disabled", disabled);
            commands.set("#InventoryHelpHints #InventoryDropButton.Disabled", disabled);
            commands.set("#InventoryDropButtonActive.Disabled", disabled);
            commands.set("#InventoryHelpHints #InventoryDropButtonActive.Disabled", disabled);
            dropDisabled = disabled;
        }
        String language = context.playerRef().getLanguage();
        String tooltip = source == null
                ? InventoryText.get(language, "tooltip.drop_hover")
                : InventoryText.get(language, "tooltip.drop_selected", source.quantity());
        if (!tooltip.equals(dropTooltip)) {
            commands.set("#InventoryDropButton.TooltipText", tooltip);
            commands.set("#InventoryHelpHints #InventoryDropButton.TooltipText", tooltip);
            commands.set("#InventoryDropButtonActive.TooltipText", tooltip);
            commands.set("#InventoryHelpHints #InventoryDropButtonActive.TooltipText", tooltip);
            dropTooltip = tooltip;
        }
        boolean holding = dragOrigin != null && dragOrigin.quantity() > 0;
        if (activeHintsVisible == null || activeHintsVisible != holding) {
            commands.set("#InactiveKeybinds.Visible", !holding);
            commands.set("#InventoryHelpHints #InactiveKeybinds.Visible", !holding);
            commands.set("#ActiveKeybinds.Visible", holding);
            commands.set("#InventoryHelpHints #ActiveKeybinds.Visible", holding);
            commands.set("#InventoryHelpPlaceAll #Name.Text", InventoryText.get(language, "action.place_all"));
            commands.set("#InventoryHelpHints #InventoryHelpPlaceAll #Name.Text", InventoryText.get(language, "action.place_all"));
            commands.set("#InventoryHelpDistributeOne #Name.Text", InventoryText.get(language, "action.distribute_one"));
            commands.set("#InventoryHelpHints #InventoryHelpDistributeOne #Name.Text", InventoryText.get(language, "action.distribute_one"));
            activeHintsVisible = holding;
        }
    }

    private boolean hasPendingMove(InventorySelection source) {
        return pendingRemovals.values().stream().anyMatch(pending -> pending.origin().sameSource(source));
    }

    private static String grid(NativeInventorySection section) {
        String panel = switch (section) {
            case STORAGE, HOTBAR -> "#InventoryPanelHost";
            case ARMOR, UTILITY -> "#PlayerPanelHost";
            case EXTRA -> "#ExtraEquipmentHost";
            case BACKPACK -> "#ContentHost";
        };
        return panel + " #" + section.gridId();
    }

    private static ItemStack itemAt(ItemStack[] stacks, int index) {
        return stacks != null && index >= 0 && index < stacks.length ? stacks[index] : null;
    }

    public void combineItemStacks(InventoryContext context, NativeInventorySection targetSection, int targetSlot) {
        if (targetSection == null) return;
        if (context != null && InventoryOperations.locked(context.ref(), context.store())) {
            status = "status.locked";
            return;
        }
        ItemContainer targetContainer = displayedContainers.get(targetSection);
        if (targetContainer == null && context != null) {
            targetContainer = InventoryOperations.resolveContainer(context.ref(), context.store(), targetSection);
        }
        if (targetContainer == null || !InventoryOperations.validSlot(targetContainer, targetSlot)) return;

        ItemStack targetStack = targetContainer.getItemStack((short) targetSlot);
        if (ItemStack.isEmpty(targetStack)) return;

        if (context != null) {
            try {
                var everything = InventoryComponent.getCombined(context.store(), context.ref(), InventoryComponent.EVERYTHING);
                if (everything != null) {
                    everything.combineItemStacksIntoSlot(targetContainer, (short) targetSlot);
                }
            } catch (Throwable ignored) {}
        }

        for (var entry : displayedContainers.entrySet()) {
            ItemContainer other = entry.getValue();
            if (other != null && other != targetContainer) {
                other.combineItemStacksIntoSlot(targetContainer, (short) targetSlot);
                releasedSources.add(entry.getKey());
            }
        }
        targetContainer.combineItemStacksIntoSlot(targetContainer, (short) targetSlot);

        releasedSources.add(targetSection);
        releasedSources.add(NativeInventorySection.STORAGE);
        releasedSources.add(NativeInventorySection.HOTBAR);
        if (displayedContainers.containsKey(NativeInventorySection.BACKPACK)) {
            releasedSources.add(NativeInventorySection.BACKPACK);
        }

        selection = null;
        dragOrigin = null;
        shiftSourceGesture = false;
        dragOrigins.clear();
        hoveredSelection = null;
        dropButtonSelection = null;
        status = "";
        if (context != null) context.requestRefresh();
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
        lastClickedSection = null;
        lastClickedSlot = null;
        lastClickedTime = 0;
        activeHintsVisible = null;
        endSweep();
    }
}
