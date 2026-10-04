package com.supremosan.custominventory.ui.player;

import com.hypixel.hytale.server.core.asset.type.gameplay.PlayerConfig.ArmorVisibilityOption;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;

import java.util.Objects;

/** Native armor render preferences; changing visibility never removes equipment or its stats. */
public final class ArmorVisibilityPreferences {
    public enum Slot {
        HELMET("#HelmetVisibility", "helmet", "Helmet", "Capacete"),
        CUIRASS("#CuirassVisibility", "cuirass", "Chest armor", "Peitoral"),
        GAUNTLETS("#GauntletsVisibility", "gauntlets", "Gloves", "Luvas"),
        PANTS("#PantsVisibility", "pants", "Leg armor", "Armadura das pernas");

        private final String selector;
        private final String translationKey;
        private final String englishLabel;
        private final String portugueseLabel;

        Slot(String selector, String translationKey, String englishLabel, String portugueseLabel) {
            this.selector = selector;
            this.translationKey = translationKey;
            this.englishLabel = englishLabel;
            this.portugueseLabel = portugueseLabel;
        }

        public String selector() { return selector; }
        public String translationKey() { return translationKey; }
        public String englishLabel() { return englishLabel; }
        public String portugueseLabel() { return portugueseLabel; }

        public boolean allowed(ArmorVisibilityOption option) {
            Objects.requireNonNull(option);
            return switch (this) {
                case HELMET -> option.canHideHelmet();
                case CUIRASS -> option.canHideCuirass();
                case GAUNTLETS -> option.canHideGauntlets();
                case PANTS -> option.canHidePants();
            };
        }

        public boolean hidden(PlayerSettings settings) {
            Objects.requireNonNull(settings);
            return switch (this) {
                case HELMET -> settings.hideHelmet();
                case CUIRASS -> settings.hideCuirass();
                case GAUNTLETS -> settings.hideGauntlets();
                case PANTS -> settings.hidePants();
            };
        }

        public static Slot parse(String value) {
            if (value == null) return null;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
    }

    private ArmorVisibilityPreferences() { }

    /** Preserve every unrelated native preference, including creative and voice settings. */
    public static PlayerSettings toggle(PlayerSettings settings, Slot slot, ArmorVisibilityOption option) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(slot);
        if (!slot.allowed(option)) return settings;
        return new PlayerSettings(
                settings.showEntityMarkers(),
                settings.armorItemsPreferredPickupLocation(),
                settings.weaponAndToolItemsPreferredPickupLocation(),
                settings.usableItemsItemsPreferredPickupLocation(),
                settings.solidBlockItemsPreferredPickupLocation(),
                settings.miscItemsPreferredPickupLocation(),
                settings.creativeSettings(),
                slot == Slot.HELMET ? !settings.hideHelmet() : settings.hideHelmet(),
                slot == Slot.CUIRASS ? !settings.hideCuirass() : settings.hideCuirass(),
                slot == Slot.GAUNTLETS ? !settings.hideGauntlets() : settings.hideGauntlets(),
                slot == Slot.PANTS ? !settings.hidePants() : settings.hidePants(),
                settings.voiceSettings());
    }
}
