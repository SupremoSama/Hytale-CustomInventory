package com.supremosan.custominventory.render;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Cosmetic;
import com.hypixel.hytale.protocol.ItemArmor;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.api.EquipmentManager;
import com.supremosan.custominventory.api.ExtraEquipment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * The single writer of player {@link ModelComponent}s for equipment and other mod attachments.
 * <p>
 * A player's ModelComponent is the bare player model; the client draws the skin from PlayerSkin.
 * Once attachments are injected, the skin is rebuilt as attachments too (honoring armor that hides
 * cosmetics and the player's armor visibility settings), so several mods can contribute without
 * overwriting each other. Players nobody contributes to keep the native model untouched.
 * Rebuilds run on the player's world thread and are coalesced per player.
 */
public final class PlayerModelRenderer {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final ThreadLocal<Boolean> REBUILDING = ThreadLocal.withInitial(() -> false);
    private static final String INNER_HAIR_PREFIX = "Items/Hats/InnerHair_";

    /** Free-form attachments per player, keyed by namespaced contribution id, in insertion order. */
    private static final Map<UUID, Map<String, ModelAttachment>> ATTACHMENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<ModelAttachment>> PREVIOUS_INJECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, String> BASE_MODEL_ASSETS = new ConcurrentHashMap<>();
    /** Players whose model this renderer has replaced; they keep being rebuilt until they leave. */
    private static final Set<UUID> MODIFIED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Mode> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, ModelAsset.AnimationSet> ANIMATIONS = new ConcurrentHashMap<>();
    private static final List<BiConsumer<Ref<EntityStore>, Store<EntityStore>>> REBUILT = new CopyOnWriteArrayList<>();
    private static volatile Set<String> skinModels;

    private PlayerModelRenderer() { }

    public enum Mode {
        /** Re-inject contributions on top of the current model. */
        ATTACHMENTS,
        /** Also rebuild the skin parts, e.g. after armor, skin or settings changes. */
        FULL
    }

    public static boolean isRebuilding() { return Boolean.TRUE.equals(REBUILDING.get()); }

    // Contributions ---------------------------------------------------------------------------

    public static void put(UUID player, String key, ModelAttachment attachment) {
        Objects.requireNonNull(attachment, "attachment");
        var previous = ATTACHMENTS.computeIfAbsent(player, ignored -> Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(key, attachment);
        if (!sameAttachment(attachment, previous)) requestRebuild(player, Mode.ATTACHMENTS);
    }

    public static void remove(UUID player, String key) {
        var slots = ATTACHMENTS.get(player);
        if (slots == null || slots.remove(key) == null) return;
        ATTACHMENTS.computeIfPresent(player, (ignored, current) -> current.isEmpty() ? null : current);
        requestRebuild(player, Mode.ATTACHMENTS);
    }

    public static ModelAttachment get(UUID player, String key) {
        var slots = ATTACHMENTS.get(player);
        return slots == null ? null : slots.get(key);
    }

    public static void registerAnimationSet(String id, ModelAsset.AnimationSet animations) {
        ANIMATIONS.put(id, Objects.requireNonNull(animations, "animations"));
    }

    public static void unregisterAnimationSet(String id, ModelAsset.AnimationSet animations) {
        ANIMATIONS.remove(id, animations);
    }

    public static void addRebuildListener(BiConsumer<Ref<EntityStore>, Store<EntityStore>> listener) {
        REBUILT.add(Objects.requireNonNull(listener, "listener"));
    }

    public static void removeRebuildListener(BiConsumer<Ref<EntityStore>, Store<EntityStore>> listener) {
        REBUILT.remove(listener);
    }

    // Scheduling ------------------------------------------------------------------------------

    /** Thread-safe; looks the player up and rebuilds on their world thread. */
    public static void requestRebuild(UUID player, Mode mode) {
        var universe = Universe.get();
        var playerRef = universe == null ? null : universe.getPlayer(player);
        if (playerRef == null) return;
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;
        schedule(player, ref, ref.getStore(), mode);
    }

    /** Call on the player's world thread. */
    public static void requestRebuild(Ref<EntityStore> ref, Store<EntityStore> store, Mode mode) {
        var uuid = uuid(store, ref);
        if (uuid != null) schedule(uuid, ref, store, mode);
    }

    /** Several requests before the rebuild runs collapse into one, keeping the strongest mode. */
    private static void schedule(UUID uuid, Ref<EntityStore> ref, Store<EntityStore> store, Mode mode) {
        boolean[] schedule = {false};
        PENDING.compute(uuid, (ignored, pending) -> {
            if (pending == null) { schedule[0] = true; return mode; }
            return pending == Mode.FULL ? pending : mode;
        });
        if (!schedule[0]) return;
        try {
            store.getExternalData().getWorld().execute(() -> {
                var pending = PENDING.remove(uuid);
                if (pending != null) rebuild(ref, store, uuid, pending);
            });
        } catch (RuntimeException worldStopped) {
            PENDING.remove(uuid);
        }
    }

    public static void onPlayerReady(Ref<EntityStore> ref, Store<EntityStore> store) {
        var uuid = uuid(store, ref);
        if (uuid == null) return;
        var model = store.getComponent(ref, ModelComponent.getComponentType());
        if (model != null && model.getModel().getModelAssetId() != null) {
            BASE_MODEL_ASSETS.putIfAbsent(uuid, model.getModel().getModelAssetId());
        }
        requestRebuild(ref, store, Mode.FULL);
    }

    public static void onPlayerLeave(UUID player) {
        ATTACHMENTS.remove(player);
        PREVIOUS_INJECTED.remove(player);
        BASE_MODEL_ASSETS.remove(player);
        MODIFIED.remove(player);
        PENDING.remove(player);
    }

    public static void clear() {
        ATTACHMENTS.clear();
        PREVIOUS_INJECTED.clear();
        BASE_MODEL_ASSETS.clear();
        MODIFIED.clear();
        PENDING.clear();
        ANIMATIONS.clear();
        REBUILT.clear();
    }

    // Rebuild ---------------------------------------------------------------------------------

    private static void rebuild(Ref<EntityStore> ref, Store<EntityStore> store, UUID uuid, Mode mode) {
        if (!ref.isValid() || ref.getStore() != store || store.getComponent(ref, Player.getComponentType()) == null) return;
        var contributions = contributions(ref, store, uuid);
        // Leave untouched players on the native model; a first takeover must rebuild the skin.
        if (!MODIFIED.contains(uuid)) {
            if (contributions.isEmpty()) return;
            mode = Mode.FULL;
        }
        boolean replaced;
        REBUILDING.set(true);
        try {
            replaced = rebuildModel(ref, store, uuid, mode, contributions);
        } catch (RuntimeException failure) {
            LOGGER.atWarning().withCause(failure).log("Player model rebuild failed for %s", uuid);
            return;
        } finally {
            REBUILDING.set(false);
        }
        if (!replaced) return;
        // A new model makes clients rebuild the character, so worn and held items are re-sent.
        markEquipmentOutdated(ref, store);
        for (var listener : REBUILT) {
            try {
                listener.accept(ref, store);
            } catch (RuntimeException failure) {
                LOGGER.atWarning().withCause(failure).log("Player model rebuild listener failed");
            }
        }
    }

    /** Equipment slots first, in slot order, then free-form attachments in insertion order. */
    private static List<ModelAttachment> contributions(Ref<EntityStore> ref, Store<EntityStore> store, UUID uuid) {
        var result = new ArrayList<ModelAttachment>();
        for (short slot = 0; slot < ExtraEquipment.SLOT_COUNT; slot++) {
            var attachment = EquipmentManager.renderedAttachment(ref, store, slot);
            if (attachment != null) result.add(attachment);
        }
        var extras = ATTACHMENTS.get(uuid);
        if (extras != null) {
            synchronized (extras) { result.addAll(extras.values()); }
        }
        return result;
    }

    /**
     * Replacing the model also resets the player's movement settings (engine
     * {@code PlayerUpdateMovementManager}), e.g. ending a flight, so it only happens on a real change.
     *
     * @return true if the model was replaced
     */
    private static boolean rebuildModel(Ref<EntityStore> ref, Store<EntityStore> store, UUID uuid, Mode mode,
                                        List<ModelAttachment> contributions) {
        var modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
        if (modelComponent == null) return false;

        var skinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        Model current = modelComponent.getModel();
        PlayerSkin skin = skinComponent == null ? null : skinComponent.getPlayerSkin();
        List<ModelAttachment> attachments = new ArrayList<>(Arrays.asList(current.getAttachments()));

        var previous = PREVIOUS_INJECTED.remove(uuid);
        if (previous != null) removePreviousAttachments(attachments, previous);

        // Another model (e.g. a transformation) is not a player skin: only re-inject contributions.
        boolean rebuildSkin = mode == Mode.FULL && skin != null && !hasExternalModel(uuid, current);
        BodySkinData body = rebuildSkin
                ? resolveBodySkinData(skin, current)
                : new BodySkinData("", current.getGradientSet(), current.getTexture());
        if (rebuildSkin) rebuildSkinAttachments(store, ref, skin, body.gradientId(), attachments);

        attachments.addAll(contributions);
        if (!contributions.isEmpty()) PREVIOUS_INJECTED.put(uuid, List.copyOf(contributions));

        MODIFIED.add(uuid);
        if (hasSameRebuildState(current, attachments, body)) return false;
        store.replaceComponent(ref, ModelComponent.getComponentType(),
                new ModelComponent(copyModelWithAttachments(current, attachments, body)));
        return true;
    }

    private static void markEquipmentOutdated(Ref<EntityStore> ref, Store<EntityStore> store) {
        var armor = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armor != null) armor.setOutdatedEquipment(true);
        var hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbar != null) hotbar.setOutdatedEquipment(true);
        var utility = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        if (utility != null) utility.setOutdatedEquipment(true);
    }

    private static boolean hasExternalModel(UUID uuid, Model current) {
        String baseAsset = BASE_MODEL_ASSETS.get(uuid);
        return baseAsset == null || !Objects.equals(baseAsset, current.getModelAssetId());
    }

    private static void removePreviousAttachments(List<ModelAttachment> current, List<ModelAttachment> previous) {
        for (var injected : previous) {
            for (int index = 0; index < current.size(); index++) {
                if (sameAttachment(current.get(index), injected)) {
                    current.remove(index);
                    break;
                }
            }
        }
    }

    private static boolean sameAttachment(ModelAttachment left, ModelAttachment right) {
        if (left == right) return true;
        return left != null && right != null
                && Objects.equals(left.getModel(), right.getModel())
                && Objects.equals(left.getTexture(), right.getTexture())
                && Objects.equals(left.getGradientSet(), right.getGradientSet())
                && Objects.equals(left.getGradientId(), right.getGradientId())
                && Double.compare(left.getWeight(), right.getWeight()) == 0;
    }

    /** ModelAttachment has no equals(), and contributions are new objects on every rebuild. */
    private static boolean hasSameRebuildState(Model current, List<ModelAttachment> attachments, BodySkinData body) {
        return sameAttachments(current.getAttachments(), attachments)
                && Objects.equals(current.getGradientSet(), body.gradientSet() != null ? body.gradientSet() : current.getGradientSet())
                && Objects.equals(current.getGradientId(), !body.gradientId().isEmpty() ? body.gradientId() : current.getGradientId())
                && Objects.equals(current.getTexture(), body.texture() != null ? body.texture() : current.getTexture())
                && (ANIMATIONS.isEmpty() || current.getAnimationSetMap().keySet().containsAll(ANIMATIONS.keySet()));
    }

    private static boolean sameAttachments(ModelAttachment[] current, List<ModelAttachment> next) {
        if (current.length != next.size()) return false;
        for (int i = 0; i < current.length; i++) {
            if (!sameAttachment(current[i], next.get(i))) return false;
        }
        return true;
    }

    private static Model copyModelWithAttachments(Model current, List<ModelAttachment> attachments, BodySkinData body) {
        String gradientSet = body.gradientSet() != null ? body.gradientSet() : current.getGradientSet();
        String gradientId = !body.gradientId().isEmpty() ? body.gradientId() : current.getGradientId();
        String texture = body.texture() != null ? body.texture() : current.getTexture();

        Map<String, ModelAsset.AnimationSet> animationSetMap = current.getAnimationSetMap();
        if (!ANIMATIONS.isEmpty()) {
            animationSetMap = new LinkedHashMap<>(animationSetMap);
            animationSetMap.putAll(ANIMATIONS);
        }

        return new Model(
                current.getModelAssetId(),
                current.getScale(),
                current.getRandomAttachmentIds(),
                attachments.toArray(new ModelAttachment[0]),
                current.getBoundingBox(),
                current.getModel(),
                texture,
                gradientSet,
                gradientId,
                current.getEyeHeight(),
                current.getCrouchOffset(),
                current.getSittingOffset(),
                current.getSleepingOffset(),
                animationSetMap,
                current.getCamera(),
                current.getLight(),
                current.getParticles(),
                current.getTrails(),
                current.getPhysicsValues(),
                current.getDetailBoxes(),
                current.getPhobia(),
                current.getPhobiaModelAssetId()
        );
    }

    // Skin reconstruction ---------------------------------------------------------------------

    private static void rebuildSkinAttachments(Store<EntityStore> store, Ref<EntityStore> ref, PlayerSkin skin,
                                               String bodyGradientId, List<ModelAttachment> attachments) {
        var settings = store.getComponent(ref, PlayerSettings.getComponentType());
        var registry = CosmeticsModule.get().getRegistry();
        var hiddenCosmetics = resolveHiddenCosmetics(store, ref, settings);
        var restored = new ArrayList<ModelAttachment>();

        removeRegisteredSkinAttachments(attachments, registry);
        restoreSkinAttachments(restored, skin, hiddenCosmetics, bodyGradientId);
        for (var attachment : restored) upsertAttachmentByModel(attachments, attachment);
    }

    private static Set<String> registeredSkinModels(CosmeticRegistry registry) {
        var cached = skinModels;
        if (cached != null) return cached;
        Set<String> models = new HashSet<>(128);
        collectModels(models, registry.getSkinFeatures());
        collectModels(models, registry.getFaces());
        collectModels(models, registry.getMouths());
        collectModels(models, registry.getEars());
        collectModels(models, registry.getEyebrows());
        collectModels(models, registry.getEyes());
        collectModels(models, registry.getUnderwear());
        collectModels(models, registry.getHaircuts());
        collectModels(models, registry.getFacialHairs());
        collectModels(models, registry.getCapes());
        collectModels(models, registry.getFaceAccessories());
        collectModels(models, registry.getGloves());
        collectModels(models, registry.getHeadAccessories());
        collectModels(models, registry.getOverpants());
        collectModels(models, registry.getOvertops());
        collectModels(models, registry.getPants());
        collectModels(models, registry.getShoes());
        collectModels(models, registry.getUndertops());
        collectModels(models, registry.getEarAccessories());
        skinModels = cached = Collections.unmodifiableSet(models);
        return cached;
    }

    private static void removeRegisteredSkinAttachments(List<ModelAttachment> attachments, CosmeticRegistry registry) {
        var models = registeredSkinModels(registry);
        attachments.removeIf(attachment -> {
            String model = attachment.getModel();
            return model != null && (models.contains(model) || model.startsWith(INNER_HAIR_PREFIX));
        });
    }

    private static void collectModels(Set<String> models, Map<String, PlayerSkinPart> registry) {
        for (var part : registry.values()) {
            if (part.getModel() != null) models.add(part.getModel());
            if (part.getVariants() == null) continue;
            for (var variant : part.getVariants().values()) {
                if (variant.getModel() != null) models.add(variant.getModel());
            }
        }
    }

    private static BodySkinData resolveBodySkinData(PlayerSkin skin, Model current) {
        if (skin.bodyCharacteristic == null) return new BodySkinData("", null, null);
        String[] parts = SkinAttachments.splitId(skin.bodyCharacteristic);
        String assetId = SkinAttachments.part(parts, 0);
        String gradientId = SkinAttachments.fallback(SkinAttachments.part(parts, 1), "");
        if (assetId == null) return new BodySkinData(gradientId, null, null);
        var bodyPart = CosmeticsModule.get().getRegistry().getBodyCharacteristics().get(assetId);
        if (bodyPart == null) return new BodySkinData(gradientId, current.getGradientSet(), current.getTexture());
        return new BodySkinData(gradientId, bodyPart.getGradientSet(), bodyPart.getGreyscaleTexture());
    }

    /** Cosmetics hidden by equipped armor whose own rendering the player has not switched off. */
    private static Set<Cosmetic> resolveHiddenCosmetics(Store<EntityStore> store, Ref<EntityStore> ref,
                                                        PlayerSettings settings) {
        Set<Cosmetic> hidden = EnumSet.noneOf(Cosmetic.class);
        var armorComponent = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComponent == null) return hidden;
        ItemContainer armor = armorComponent.getInventory();
        for (short slot = 0; slot < armor.getCapacity(); slot++) {
            ItemStack stack = armor.getItemStack(slot);
            if (stack == null || stack.isEmpty() || stack.getItem().getArmor() == null) continue;
            ItemArmor itemArmor = stack.getItem().getArmor().toPacket();
            if (isArmorHidden(itemArmor, settings) || itemArmor.cosmeticsToHide == null) continue;
            for (var cosmetic : itemArmor.cosmeticsToHide) {
                if (cosmetic != Cosmetic.Ear) hidden.add(cosmetic);
            }
        }
        return hidden;
    }

    private static boolean isArmorHidden(ItemArmor itemArmor, PlayerSettings settings) {
        if (settings == null) return false;
        return switch (itemArmor.armorSlot) {
            case Head -> settings.hideHelmet();
            case Chest -> settings.hideCuirass();
            case Hands -> settings.hideGauntlets();
            case Legs -> settings.hidePants();
        };
    }

    private static void restoreSkinAttachments(List<ModelAttachment> attachments, PlayerSkin skin,
                                               Set<Cosmetic> hiddenCosmetics, String bodyGradientId) {
        if (skin.bodyCharacteristic == null) return;
        var registry = CosmeticsModule.get().getRegistry();

        addSkinPart(attachments, skin.skinFeature, registry.getSkinFeatures(), bodyGradientId);
        addFacePart(attachments, skin.face, registry.getFaces(), bodyGradientId);
        addFacePart(attachments, skin.mouth, registry.getMouths(), bodyGradientId);

        addFacePart(attachments, skin.ears, registry.getEars(), bodyGradientId);
        addSkinPart(attachments, skin.eyebrows, registry.getEyebrows(), bodyGradientId);
        addSkinPart(attachments, skin.eyes, registry.getEyes(), bodyGradientId);
        addSkinPart(attachments, skin.underwear, registry.getUnderwear(), bodyGradientId);

        addHaircutPart(attachments, skin, registry, hiddenCosmetics, bodyGradientId);

        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.FacialHair, skin.facialHair, registry.getFacialHairs(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.Cape, skin.cape, registry.getCapes(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.FaceAccessory, skin.faceAccessory, registry.getFaceAccessories(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.Gloves, skin.gloves, registry.getGloves(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.HeadAccessory, skin.headAccessory, registry.getHeadAccessories(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.Overpants, skin.overpants, registry.getOverpants(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.Overtop, skin.overtop, registry.getOvertops(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.Pants, skin.pants, registry.getPants(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.Shoes, skin.shoes, registry.getShoes(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.Undertop, skin.undertop, registry.getUndertops(), bodyGradientId);
        addHiddenAwarePart(attachments, hiddenCosmetics, Cosmetic.EarAccessory, skin.earAccessory, registry.getEarAccessories(), bodyGradientId);
    }

    private static void addHiddenAwarePart(List<ModelAttachment> attachments, Set<Cosmetic> hiddenCosmetics,
                                           Cosmetic cosmetic, String rawId, Map<String, PlayerSkinPart> registry,
                                           String bodyGradientId) {
        if (!hiddenCosmetics.contains(cosmetic)) addSkinPart(attachments, rawId, registry, bodyGradientId);
    }

    /** Headgear that hides the haircut leaves a close-cropped inner hair of the same type. */
    private static void addHaircutPart(List<ModelAttachment> attachments, PlayerSkin skin, CosmeticRegistry registry,
                                       Set<Cosmetic> hiddenCosmetics, String bodyGradientId) {
        if (skin.haircut == null) return;
        String[] parts = SkinAttachments.splitId(skin.haircut);
        String assetId = SkinAttachments.part(parts, 0);
        String textureId = SkinAttachments.part(parts, 1);
        String variantId = SkinAttachments.part(parts, 2);
        if (assetId == null) return;

        var part = registry.getHaircuts().get(assetId);
        if (part == null) return;

        if (variantId != null && textureId != null && part.getVariants() != null
                && !part.getVariants().containsKey(variantId) && part.getVariants().containsKey(textureId)) {
            String resolvedVariant = textureId;
            textureId = variantId;
            variantId = resolvedVariant;
        }

        var headAccessoryType = resolveHeadAccessoryType(skin, registry);
        if (headAccessoryType == PlayerSkinPart.HeadAccessoryType.FullyCovering
                && !hiddenCosmetics.contains(Cosmetic.HeadAccessory)) return;

        if (hiddenCosmetics.contains(Cosmetic.Haircut) && part.getHairType() != null) {
            String hairType = part.getHairType().name();
            attachments.add(new ModelAttachment(
                    INNER_HAIR_PREFIX + hairType + ".blockymodel",
                    INNER_HAIR_PREFIX + hairType + "_Greyscale.png",
                    "Hair",
                    textureId != null ? textureId : "Black",
                    1.0));
            return;
        }
        attachments.add(SkinAttachments.resolveAttachment(part, textureId, variantId, bodyGradientId));
    }

    private static PlayerSkinPart.HeadAccessoryType resolveHeadAccessoryType(PlayerSkin skin, CosmeticRegistry registry) {
        if (skin.headAccessory == null) return PlayerSkinPart.HeadAccessoryType.Simple;
        String assetId = SkinAttachments.assetId(skin.headAccessory);
        if (assetId == null) return PlayerSkinPart.HeadAccessoryType.Simple;
        var headAccessory = registry.getHeadAccessories().get(assetId);
        return headAccessory == null ? PlayerSkinPart.HeadAccessoryType.Simple : headAccessory.getHeadAccessoryType();
    }

    private static void addSkinPart(List<ModelAttachment> attachments, String rawId,
                                    Map<String, PlayerSkinPart> registry, String bodyGradientId) {
        addPart(attachments, rawId, registry, bodyGradientId, false);
    }

    private static void addFacePart(List<ModelAttachment> attachments, String rawId,
                                    Map<String, PlayerSkinPart> registry, String bodyGradientId) {
        addPart(attachments, rawId, registry, bodyGradientId, true);
    }

    private static void addPart(List<ModelAttachment> attachments, String rawId, Map<String, PlayerSkinPart> registry,
                                String bodyGradientId, boolean useBodyGradientWhenMissingTexture) {
        if (rawId == null) return;
        String[] parts = SkinAttachments.splitId(rawId);
        String assetId = SkinAttachments.part(parts, 0);
        if (assetId == null) return;
        var part = registry.get(assetId);
        if (part == null) return;

        String textureId = SkinAttachments.part(parts, 1);
        if (textureId == null && useBodyGradientWhenMissingTexture) textureId = bodyGradientId;
        String variantId = SkinAttachments.part(parts, 2);
        if (variantId != null && textureId != null && part.getVariants() != null
                && !part.getVariants().containsKey(variantId) && part.getVariants().containsKey(textureId)) {
            String resolvedVariant = textureId;
            textureId = variantId;
            variantId = resolvedVariant;
        }
        attachments.add(SkinAttachments.resolveAttachment(part, textureId, variantId, bodyGradientId));
    }

    private static void upsertAttachmentByModel(List<ModelAttachment> attachments, ModelAttachment updated) {
        String updatedModel = updated.getModel();
        if (updatedModel == null) return;
        for (int i = 0; i < attachments.size(); i++) {
            if (updatedModel.equals(attachments.get(i).getModel())) {
                attachments.set(i, updated);
                return;
            }
        }
        attachments.add(updated);
    }

    private static UUID uuid(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        var component = store.getComponent(ref, UUIDComponent.getComponentType());
        return component == null ? null : component.getUuid();
    }

    private record BodySkinData(String gradientId, String gradientSet, String texture) { }

    // Triggers --------------------------------------------------------------------------------

    /** Armor visibility settings change which cosmetics armor hides. */
    public static final class OnPlayerSettingsChange extends RebuildOnChange<PlayerSettings> {
        @Override public ComponentType<EntityStore, PlayerSettings> componentType() { return PlayerSettings.getComponentType(); }
    }

    public static final class OnPlayerSkinChange extends RebuildOnChange<PlayerSkinComponent> {
        @Override public ComponentType<EntityStore, PlayerSkinComponent> componentType() { return PlayerSkinComponent.getComponentType(); }
    }

    /** Native code replacing the model drops injected attachments; put them back. */
    public static final class OnModelChange extends RebuildOnChange<ModelComponent> {
        @Override public ComponentType<EntityStore, ModelComponent> componentType() { return ModelComponent.getComponentType(); }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, ModelComponent oldComponent, ModelComponent newComponent,
                                   Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
            if (oldComponent != null && !isRebuilding()) {
                var uuid = uuid(store, ref);
                String previousAsset = oldComponent.getModel().getModelAssetId();
                if (uuid != null && previousAsset != null) BASE_MODEL_ASSETS.putIfAbsent(uuid, previousAsset);
            }
            handleChange(ref, store, commandBuffer, Mode.ATTACHMENTS);
        }

        @Override protected Mode mode() { return Mode.ATTACHMENTS; }
    }

    public static final class OnArmorChange extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
        public OnArmorChange() { super(InventoryChangeEvent.class); }

        @Override public Query<EntityStore> getQuery() { return InventoryComponent.Armor.getComponentType(); }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commands, InventoryChangeEvent event) {
            if (isRebuilding() || event.getComponentType() != InventoryComponent.Armor.getComponentType()) return;
            if (chunk.getComponent(index, Player.getComponentType()) == null) return;
            requestRebuild(chunk.getReferenceTo(index), store, Mode.FULL);
        }
    }

    private abstract static class RebuildOnChange<T extends Component<EntityStore>> extends RefChangeSystem<EntityStore, T> {
        @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, T component, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> commandBuffer) {
            handleChange(ref, store, commandBuffer, mode());
        }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, T oldComponent, T newComponent, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
            handleChange(ref, store, commandBuffer, mode());
        }

        @Override
        public void onComponentRemoved(Ref<EntityStore> ref, T component, Store<EntityStore> store,
                                       CommandBuffer<EntityStore> commandBuffer) { }

        protected Mode mode() { return Mode.FULL; }

        protected static void handleChange(Ref<EntityStore> ref, Store<EntityStore> store,
                                           CommandBuffer<EntityStore> commandBuffer, Mode mode) {
            if (isRebuilding() || commandBuffer.getComponent(ref, Player.getComponentType()) == null) return;
            requestRebuild(ref, store, mode);
        }
    }
}
