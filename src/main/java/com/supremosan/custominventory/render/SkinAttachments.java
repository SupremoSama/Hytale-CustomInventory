package com.supremosan.custominventory.render;

import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPartTexture;

import java.util.Map;

/** Resolves player skin parts into model attachments, the way the client renders a skin. */
final class SkinAttachments {

    private static final double DEFAULT_SCALE = 1.0D;

    private SkinAttachments() {
    }

    static ModelAttachment fromPlayerSkinPart(PlayerSkinPart part, String gradientId) {
        return attachment(
                part.getModel(),
                part.getGreyscaleTexture(),
                part.getGradientSet(),
                gradientId
        );
    }

    static ModelAttachment resolveAttachment(PlayerSkinPart part, String textureId,
                                             String variantId, String bodyGradientId) {
        String fallbackGradientId = fallback(textureId, bodyGradientId);
        Map<String, PlayerSkinPart.Variant> variants = part.getVariants();

        if (hasEntries(variants)) {
            PlayerSkinPart.Variant variant = get(variants, variantId);
            if (variant == null) {
                return fromPlayerSkinPart(part, fallbackGradientId);
            }

            return resolveVariantAttachment(part, variant, textureId, fallbackGradientId);
        }

        return resolvePartAttachment(part, textureId, fallbackGradientId);
    }

    static String[] splitId(String rawId) {
        return rawId.split("\\.", -1);
    }

    static String part(String[] parts, int index) {
        if (index < 0 || index >= parts.length) {
            return null;
        }

        String value = parts[index];
        return value == null || value.isEmpty() ? null : value;
    }

    static String assetId(String rawId) {
        return part(splitId(rawId), 0);
    }

    static String fallback(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static ModelAttachment resolveVariantAttachment(PlayerSkinPart part,
                                                            PlayerSkinPart.Variant variant,
                                                            String textureId,
                                                            String fallbackGradientId) {
        Map<String, PlayerSkinPartTexture> textures = variant.getTextures();

        if (hasEntries(textures)) {
            PlayerSkinPartTexture texture = get(textures, textureId);
            if (texture != null) {
                if (texture.getBaseColor() == null) {
                    return attachment(variant.getModel(), texture.getTexture(), part.getGradientSet(), fallbackGradientId);
                }
                return attachment(variant.getModel(), texture.getTexture(), null, null);
            }
        }

        String texture = variant.getGreyscaleTexture() != null
                ? variant.getGreyscaleTexture()
                : part.getGreyscaleTexture();

        return attachment(
                variant.getModel() != null ? variant.getModel() : part.getModel(),
                texture,
                part.getGradientSet(),
                fallbackGradientId
        );
    }

    private static ModelAttachment resolvePartAttachment(PlayerSkinPart part,
                                                         String textureId,
                                                         String fallbackGradientId) {
        Map<String, PlayerSkinPartTexture> textures = part.getTextures();

        if (hasEntries(textures)) {
            PlayerSkinPartTexture texture = get(textures, textureId);
            if (texture != null) {
                if (texture.getBaseColor() == null) {
                    return attachment(part.getModel(), texture.getTexture(), part.getGradientSet(), fallbackGradientId);
                }
                return attachment(part.getModel(), texture.getTexture(), null, null);
            }
        }

        return attachment(
                part.getModel(),
                part.getGreyscaleTexture(),
                part.getGradientSet(),
                fallbackGradientId
        );
    }

    private static ModelAttachment attachment(String model,
                                              String texture,
                                              String gradientSet,
                                              String gradientId) {
        return new ModelAttachment(
                model,
                texture,
                emptyToNull(gradientSet),
                emptyToNull(gradientId),
                DEFAULT_SCALE);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static <K, V> boolean hasEntries(Map<K, V> map) {
        return map != null && !map.isEmpty();
    }

    private static <K, V> V get(Map<K, V> map, K key) {
        return map == null || key == null ? null : map.get(key);
    }
}