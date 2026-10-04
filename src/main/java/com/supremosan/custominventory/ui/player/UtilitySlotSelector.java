package com.supremosan.custominventory.ui.player;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryActiveSlotRequestEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.InventoryContentEvent;
import com.supremosan.custominventory.api.InventoryContext;
import com.supremosan.custominventory.api.InventoryEventBindings;
import com.supremosan.custominventory.inventory.InventoryOperations;
import com.supremosan.custominventory.inventory.InventorySelection;
import com.supremosan.custominventory.inventory.NativeInventorySection;

import java.util.Locale;

/** Native utility-wheel artwork with supported legacy CustomUI controls and native slot-change events. */
public final class UtilitySlotSelector {
    public static final int DISPLAYED_SLOTS = 4;
    public static final String OPEN = "OpenUtilitySelector";
    public static final String CLOSE = "CloseUtilitySelector";
    public static final String SELECT = "SelectUtilitySlot";
    public static final String HOVER = "HoverUtilitySlot";
    public static final String UNHOVER = "UnhoverUtilitySlot";
    public static final String HOVER_CENTER = "HoverUtilityCenter";
    public static final String UNHOVER_CENTER = "UnhoverUtilityCenter";
    public enum Validation { READY, LOCKED, INVALID_SLOT, STALE_DISPLAY }

    private boolean open;
    private boolean centerHovered;
    private int hoveredSlot = -2;
    private ItemContainer displayedContainer;
    private ItemStack[] displayed = new ItemStack[0];
    private PresentationSnapshot presentationSnapshot;

    private record PresentationSnapshot(String host, boolean open, boolean centerHovered,
                                        int hoveredSlot, int activeSlot, boolean disabled, String language) { }

    public void build(InventoryContext context, UICommandBuilder commands, InventoryEventBindings events, String host) {
        open = false;
        centerHovered = false;
        hoveredSlot = -2;
        presentationSnapshot = null;
        commands.append(host + " #UtilityWheelHost", "Inventory/Utility/UtilityWheel.ui");
        bindEvents(events);
        refresh(context, commands, host);
    }

    /** Hover and explicit selection use the player-panel event scope. */
    static void bindEvents(InventoryEventBindings events) {
        events.bind(CustomUIEventBindingType.SlotMouseEntered, "#UtilityGrid", OPEN, "", false);
        events.bind(CustomUIEventBindingType.MouseEntered, "#UtilitySlotInputBinding", OPEN, "", false);
        events.bind(CustomUIEventBindingType.MouseExited, "#UtilityWheel", CLOSE, "", false);
        events.bind(CustomUIEventBindingType.SlotMouseEntered, "#UtilityWheelCenterGrid", HOVER_CENTER, "", false);
        events.bind(CustomUIEventBindingType.SlotMouseExited, "#UtilityWheelCenterGrid", UNHOVER_CENTER, "", false);
        for (int index = 0; index < DISPLAYED_SLOTS; index++) {
            events.bind(CustomUIEventBindingType.SlotDoubleClicking, "#UtilityChoiceGrid" + index, SELECT, Integer.toString(index), false);
            events.bind(CustomUIEventBindingType.SlotMouseEntered, "#UtilityChoiceGrid" + index, HOVER, Integer.toString(index), false);
            events.bind(CustomUIEventBindingType.SlotMouseExited, "#UtilityChoiceGrid" + index, UNHOVER, Integer.toString(index), false);
        }
        events.bind(CustomUIEventBindingType.Activating, "#UtilityClear", SELECT, "-1", false);
        events.bind(CustomUIEventBindingType.MouseEntered, "#UtilityClear", HOVER, "-1", false);
        events.bind(CustomUIEventBindingType.MouseExited, "#UtilityClear", UNHOVER, "-1", false);
    }

    /** Routes every wheel drag through the shell's shared authoritative inventory snapshot. */
    public static void bindInventoryEvents(InventoryEventBindings events) {
        for (int index = 0; index < DISPLAYED_SLOTS; index++) {
            String grid = "#PlayerPanelHost #UtilityChoiceGrid" + index;
            String slot = Integer.toString(index);
            events.bind(CustomUIEventBindingType.SlotClicking, grid, "UtilityWheelDragSource", slot, false);
            events.bind(CustomUIEventBindingType.Dropped, grid, "UtilityWheelDrop", slot, false);
            events.bind(CustomUIEventBindingType.SlotClickReleaseWhileDragging, grid, "UtilityWheelDragRelease", slot, false);
            events.bind(CustomUIEventBindingType.SlotClickPressWhileDragging, grid, "UtilityWheelDragPress", slot, false);
            events.bind(CustomUIEventBindingType.DragCancelled, grid, "CancelDrag", "", false);
        }
        String center = "#PlayerPanelHost #UtilityWheelCenterGrid";
        events.bind(CustomUIEventBindingType.SlotClicking, center, "DragSource", "UTILITY", false);
        events.bind(CustomUIEventBindingType.Dropped, center, "Drop", "UTILITY", false);
        events.bind(CustomUIEventBindingType.SlotClickReleaseWhileDragging, center, "DragRelease", "UTILITY", false);
        events.bind(CustomUIEventBindingType.SlotClickPressWhileDragging, center, "DragPress", "UTILITY", false);
        events.bind(CustomUIEventBindingType.DragCancelled, center, "CancelDrag", "", false);
    }

    public void refresh(InventoryContext context, UICommandBuilder commands, String host) {
        boolean valid = validContext(context);
        var utility = valid ? context.store().getComponent(context.ref(), InventoryComponent.Utility.getComponentType()) : null;
        displayedContainer = utility == null ? null : utility.getInventory();
        int capacity = displayedContainer == null ? 0 : Math.min(DISPLAYED_SLOTS, displayedContainer.getCapacity());
        displayed = new ItemStack[capacity];
        // Selection authority must follow the live inventory even when no visual
        // state changed and this refresh sends no commands to the client.
        for (short index = 0; index < capacity; index++) {
            displayed[index] = InventorySelection.snapshot(displayedContainer.getItemStack(index));
        }
        boolean disabled = !valid || InventoryOperations.locked(context.ref(), context.store()) || utility == null;
        if (disabled || displayedContainer == null) {
            open = false;
            centerHovered = false;
            hoveredSlot = -2;
        }
        int activeSlot = utility == null ? Integer.MIN_VALUE : utility.getActiveSlot();
        String language = valid ? context.playerRef().getLanguage() : null;
        var next = new PresentationSnapshot(host, open, open && centerHovered, hoveredSlot, activeSlot, disabled, language);
        if (next.equals(presentationSnapshot)) return;
        presentationSnapshot = next;
        commands.set(host + " #UtilityWheelHost.Visible", open);
        commands.set(host + " #UtilityGrid.Visible", !open);
        commands.set(host + " #UtilitySlotInputBinding.Visible", !open);
        commands.set(host + " #UtilitySlotCompatibleHighlight.Visible", false);
        commands.set(host + " #UtilityCenterPointer.Visible", next.centerHovered());
        commands.set(host + " #UtilityWheelCenterGrid.Style", Value.ref("Inventory/Utility/UtilityWheel.ui",
                next.centerHovered() ? "CenterCompatibleStyle" : "CenterStyle"));
        for (int index = 0; index < DISPLAYED_SLOTS; index++) {
            commands.set(host + " #UtilitySelected" + index + ".Visible", activeSlot == index);
            commands.set(host + " #UtilityHovered" + index + ".Visible", open && hoveredSlot == index);
        }
        commands.set(host + " #UtilityClear.Disabled", disabled);
        commands.set(host + " #UtilitySelectedClear.Visible", activeSlot == InventoryComponent.INACTIVE_SLOT_INDEX);
        commands.set(host + " #UtilityHoveredClear.Visible", open && hoveredSlot == -1);
        if (valid) {
            commands.set(host + " #UtilityClear.TooltipText", localized(context, "Unequip utility item", "Desequipar item utilitário"));
            commands.set(host + " #UtilitySlotInputBinding.TooltipText", localized(context, "Select utility item", "Selecionar item utilitário"));
        }
    }

    public boolean handleEvent(InventoryContext context, InventoryContentEvent event) {
        if (OPEN.equals(event.action())) {
            if (validContext(context) && !InventoryOperations.locked(context.ref(), context.store())) {
                open = true;
                // The closed Z slot opens the wheel with the pointer already
                // over its center; subsequent center-exit events remove the glow.
                centerHovered = true;
            }
        } else if (CLOSE.equals(event.action())) {
            open = false;
            centerHovered = false;
            hoveredSlot = -2;
        } else if (HOVER_CENTER.equals(event.action())) {
            if (open) {
                centerHovered = true;
                hoveredSlot = -2;
            }
        } else if (UNHOVER_CENTER.equals(event.action())) {
            centerHovered = false;
        } else if (HOVER.equals(event.action())) {
            Integer slot = parseSlot(event.payload());
            if (open && slot != null) {
                hoveredSlot = slot;
                centerHovered = false;
            }
        } else if (UNHOVER.equals(event.action())) {
            Integer slot = parseSlot(event.payload());
            if (slot != null && hoveredSlot == slot) hoveredSlot = -2;
        } else if (SELECT.equals(event.action())) {
            if (open) select(context, parseSlot(event.payload()));
            open = false;
            centerHovered = false;
            hoveredSlot = -2;
        } else return false;
        return true;
    }

    /** These events change only presentation and must never rewrite held-item grids. */
    public static boolean isPresentationEvent(String action) {
        return OPEN.equals(action) || CLOSE.equals(action) || HOVER.equals(action) || UNHOVER.equals(action)
                || HOVER_CENTER.equals(action) || UNHOVER_CENTER.equals(action);
    }

    private void select(InventoryContext context, Integer slot) {
        if (!validContext(context)) return;
        var utility = context.store().getComponent(context.ref(), InventoryComponent.Utility.getComponentType());
        var current = utility == null ? null : utility.getInventory();
        if (validateSelection(displayedContainer, displayed, current, slot,
                InventoryOperations.locked(context.ref(), context.store())) != Validation.READY) return;
        selectActiveSlot(context, slot);
    }

    /**
     * Shared with native drops after the engine has actually populated the destination.
     * Returns whether the requested slot is selected; an event listener may choose another valid slot.
     */
    public static boolean selectActiveSlot(InventoryContext context, int slot) {
        if (!validContext(context) || InventoryOperations.locked(context.ref(), context.store())) return false;
        var utility = context.store().getComponent(context.ref(), InventoryComponent.Utility.getComponentType());
        var current = utility == null ? null : utility.getInventory();
        if (current == null || slot < InventoryComponent.INACTIVE_SLOT_INDEX || slot > Byte.MAX_VALUE
                || slot >= current.getCapacity() || slot >= 0 && ItemStack.isEmpty(current.getItemStack((short) slot))) return false;
        byte previous = utility.getActiveSlot();
        if (previous == slot) return true;
        var event = new InventoryActiveSlotRequestEvent(InventoryComponent.UTILITY_SECTION_ID, previous, (byte) slot, false);
        context.store().invoke(context.ref(), event);
        if (event.isCancelled()) return false;
        byte target = event.getNewSlot();
        // A plugin may redirect a native request, but it cannot escape this section's valid range.
        if (target < InventoryComponent.INACTIVE_SLOT_INDEX || target >= current.getCapacity()) return false;
        if (!validContext(context) || InventoryOperations.locked(context.ref(), context.store())
                || context.store().getComponent(context.ref(), InventoryComponent.Utility.getComponentType()) != utility
                || utility.getInventory() != current) return false;
        if (target != previous) utility.setActiveSlot(target, context.ref(), context.store());
        context.playerRef().getPacketHandler().writeNoCache(new SetActiveSlot(InventoryComponent.UTILITY_SECTION_ID, target));
        return target == slot;
    }

    /** Selecting never moves or removes a stack. */
    public static Validation validateSelection(ItemContainer displayedContainer, ItemStack[] displayed,
                                               ItemContainer current, Integer slot, boolean locked) {
        if (locked) return Validation.LOCKED;
        if (slot == null || slot < -1 || slot >= DISPLAYED_SLOTS) return Validation.INVALID_SLOT;
        if (current == null || current != displayedContainer || displayed == null) return Validation.STALE_DISPLAY;
        if (slot == -1) return Validation.READY;
        if (slot >= displayed.length || !InventoryOperations.validSlot(current, slot)
                || ItemStack.isEmpty(displayed[slot])) return Validation.INVALID_SLOT;
        var expected = InventorySelection.fromDisplayed(NativeInventorySection.UTILITY, displayedContainer, slot, displayed[slot]);
        return expected != null && expected.matches(current.getItemStack(slot.shortValue()))
                ? Validation.READY : Validation.STALE_DISPLAY;
    }

    public static Integer parseSlot(String payload) {
        if (payload == null || !payload.matches("-1|[0-3]")) return null;
        return Integer.valueOf(payload);
    }

    private static boolean validContext(InventoryContext context) {
        return context != null && context.ref() != null && context.ref().isValid()
                && context.ref().getStore() == context.store()
                && context.playerRef().getReference() == context.ref();
    }

    private static String localized(InventoryContext context, String english, String portuguese) {
        String language = context.playerRef().getLanguage();
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("pt") ? portuguese : english;
    }
}
