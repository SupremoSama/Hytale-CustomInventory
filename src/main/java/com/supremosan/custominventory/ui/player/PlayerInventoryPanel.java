package com.supremosan.custominventory.ui.player;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.InventoryContentEvent;
import com.supremosan.custominventory.api.InventoryContext;
import com.supremosan.custominventory.api.InventoryEventBindings;
import com.supremosan.custominventory.inventory.InventoryOperations;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Player preview, native statistics and armor-eye controls for an already mounted PlayerPanel.ui.
 * CharacterPreviewComponent owns its client-side player setup; the server does not inject model data.
 * Calls execute on the owning world thread. Grid rendering/transfers remain the inventory content's job.
 */
public final class PlayerInventoryPanel {
    public static final String TOGGLE_ARMOR_VISIBILITY = "ToggleArmorVisibility";
    private final UtilitySlotSelector utilitySelector = new UtilitySlotSelector();
    private Snapshot snapshot;
    private String snapshotHost;
    private boolean equipmentVisible = true;

    /** The event bindings must be scoped to hostSelector, for example #PlayerPanelHost. */
    public void build(InventoryContext context, UICommandBuilder commands,
                      InventoryEventBindings events, InventoryEventBindings equipmentEvents, String hostSelector) {
        com.supremosan.custominventory.api.ExtraEquipment.ensure(context.ref(), context.store());
        commands.append("#ExtraEquipmentHost", "Inventory/ExtraEquipment.ui");
        equipmentEvents.bind(CustomUIEventBindingType.Activating, "#ExtraEquipmentToggle", "ToggleExtraEquipment", "", false);
        for (var slot : ArmorVisibilityPreferences.Slot.values()) {
            events.bind(CustomUIEventBindingType.Activating, slot.selector(), TOGGLE_ARMOR_VISIBILITY, slot.name(), true);
        }
        utilitySelector.build(context, commands, events, hostSelector);
        // A full rebuild creates fresh UI elements, so it must always write their values.
        snapshot = null;
        snapshotHost = null;
        refresh(context, commands, hostSelector);
    }

    /** Refresh only values/eye state without remounting the character preview or equipment grids. */
    public void refresh(InventoryContext context, UICommandBuilder commands, String hostSelector) {
        commands.set("#ExtraEquipmentPanel.Visible", equipmentVisible);
        commands.set("#ExtraEquipmentToggle.Text", equipmentVisible ? "<" : ">");
        utilitySelector.refresh(context, commands, hostSelector);
        var next = readSnapshot(context);
        if (Objects.equals(snapshotHost, hostSelector) && next.equals(snapshot)) return;
        commands.set(selector(hostSelector, "#PlayerName.Text"), next.name());
        updateArmorVisibility(context, commands, hostSelector, next);
        commands.set(selector(hostSelector, "#StatHealth.Text"), next.health());
        commands.set(selector(hostSelector, "#StatStamina.Text"), next.stamina());
        commands.set(selector(hostSelector, "#StatMana.Text"), next.mana());
        commands.set(selector(hostSelector, "#StatDefense.Text"), next.defense());
        snapshot = next;
        snapshotHost = hostSelector;
    }

    /** @return true for recognized armor-eye actions, including stale/denied requests. */
    public boolean handleEvent(InventoryContext context, InventoryContentEvent event) {
        if ("ToggleExtraEquipment".equals(event.action())) {
            equipmentVisible = !equipmentVisible;
            context.requestRefresh();
            return true;
        }
        if (utilitySelector.handleEvent(context, event)) return true;
        if (!TOGGLE_ARMOR_VISIBILITY.equals(event.action())) return false;
        toggleArmorVisibility(context, ArmorVisibilityPreferences.Slot.parse(event.payload()));
        // Like the original page, denied and stale requests refresh the interface as well.
        context.requestRefresh();
        return true;
    }

    private static void toggleArmorVisibility(InventoryContext context, ArmorVisibilityPreferences.Slot slot) {
        if (slot == null || InventoryOperations.locked(context.ref(), context.store())) return;
        var armor = context.store().getComponent(context.ref(), InventoryComponent.Armor.getComponentType());
        if (armor == null || slot.ordinal() >= armor.getInventory().getCapacity()
                || ItemStack.isEmpty(armor.getInventory().getItemStack((short) slot.ordinal()))) return;
        var settings = context.store().getComponent(context.ref(), PlayerSettings.getComponentType());
        if (settings == null) settings = PlayerSettings.defaults();
        var option = context.store().getExternalData().getWorld().getGameplayConfig().getPlayerConfig().getArmorVisibilityOption();
        var updated = ArmorVisibilityPreferences.toggle(settings, slot, option);
        if (updated == settings) return;
        context.store().putComponent(context.ref(), PlayerSettings.getComponentType(), updated);
        // The native preference handler uses this flag to resend rendered equipment to the world.
        armor.setOutdatedEquipment(true);
    }

    private static void updateArmorVisibility(InventoryContext context, UICommandBuilder commands, String hostSelector,
                                              Snapshot state) {
        for (var slot : ArmorVisibilityPreferences.Slot.values()) {
            int bit = 1 << slot.ordinal();
            boolean occupied = (state.occupiedSlots() & bit) != 0;
            boolean allowed = (state.allowedSlots() & bit) != 0;
            boolean hidden = (state.hiddenSlots() & bit) != 0;
            String mount = selector(hostSelector, slot.selector());
            commands.set(mount + ".Visible", occupied);
            commands.set(mount + ".Disabled", !allowed);
            commands.set(mount + " #Visible.Visible", !hidden);
            commands.set(mount + " #Hidden.Visible", hidden);
            String label = text(context, slot.translationKey(), slot.englishLabel(), slot.portugueseLabel());
            String visibility = !allowed ? "locked" : hidden ? "hidden" : "visible";
            String english = !allowed ? "{0}: visibility is locked by this world."
                    : hidden ? "{0}: hidden. Click to show." : "{0}: visible. Click to hide.";
            String portuguese = !allowed ? "{0}: a visibilidade está bloqueada neste mundo."
                    : hidden ? "{0}: oculto. Clique para mostrar." : "{0}: visível. Clique para ocultar.";
            commands.set(mount + ".TooltipText", MessageFormat.format(text(context, "armor_" + visibility, english, portuguese), label));
        }
    }

    private static Snapshot readSnapshot(InventoryContext context) {
        var settings = context.store().getComponent(context.ref(), PlayerSettings.getComponentType());
        if (settings == null) settings = PlayerSettings.defaults();
        var option = context.store().getExternalData().getWorld().getGameplayConfig().getPlayerConfig().getArmorVisibilityOption();
        var armor = context.store().getComponent(context.ref(), InventoryComponent.Armor.getComponentType());
        int occupiedSlots = 0;
        int allowedSlots = 0;
        int hiddenSlots = 0;
        for (var slot : ArmorVisibilityPreferences.Slot.values()) {
            boolean occupied = armor != null && slot.ordinal() < armor.getInventory().getCapacity()
                    && !ItemStack.isEmpty(armor.getInventory().getItemStack((short) slot.ordinal()));
            boolean allowed = slot.allowed(option);
            boolean hidden = allowed && slot.hidden(settings);
            int bit = 1 << slot.ordinal();
            if (occupied) occupiedSlots |= bit;
            if (allowed) allowedSlots |= bit;
            if (hidden) hiddenSlots |= bit;
        }
        var stats = context.store().getComponent(context.ref(), EntityStatMap.getComponentType());
        String defense = "0%";
        if (armor != null) {
            var physical = DamageCause.getAssetMap().getAsset("Physical");
            var effects = context.store().getComponent(context.ref(), EffectControllerComponent.getComponentType());
            var resistance = DamageSystems.ArmorDamageReduction.getResistanceModifiers(
                    context.store().getExternalData().getWorld(), armor.getInventory(),
                    ItemUtils.canApplyItemStackPenalties(context.ref(), context.store()), effects).get(physical);
            if (resistance != null) defense = Math.round(Math.clamp(resistance.multiplierModifier * 100f, 0f, 100f)) + "%";
        }
        return new Snapshot(context.playerRef().getUsername(), context.playerRef().getLanguage(), occupiedSlots,
                allowedSlots, hiddenSlots, statText(stats, DefaultEntityStatTypes.getHealth()),
                statText(stats, DefaultEntityStatTypes.getStamina()), statText(stats, DefaultEntityStatTypes.getMana()), defense);
    }

    private static String statText(EntityStatMap stats, int id) {
        var stat = stats == null ? null : stats.get(id);
        return stat == null ? "—" : Math.round(stat.get()) + "/" + Math.round(stat.getMax());
    }

    private static String selector(String host, String localSelector) {
        if (host == null || !host.startsWith("#")) throw new IllegalArgumentException("Player panel host must start with #");
        return host + " " + localSelector;
    }

    private static String text(InventoryContext context, String key, String english, String portuguese) {
        String language = context.playerRef().getLanguage();
        var i18n = I18nModule.get();
        String fullKey = "server.custominventory.inventory." + key;
        if (i18n != null) {
            String translated = i18n.getMessage(language, fullKey);
            if (translated != null && !translated.isBlank() && !translated.equals(fullKey)) return translated;
        }
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("pt") ? portuguese : english;
    }

    private record Snapshot(String name, String language, int occupiedSlots, int allowedSlots, int hiddenSlots,
                            String health, String stamina, String mana, String defense) { }
}
