package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.supremosan.custominventory.api.InventoryContext;
import com.supremosan.custominventory.api.InventoryItemTooltipContext;
import com.supremosan.custominventory.api.InventoryRegistry;
import org.bson.BsonDocument;

/** Converts server items into safe CustomUI display copies without altering authoritative inventory. */
public final class InventoryDisplay {
    private InventoryDisplay() {}

    public static ItemGridSlot slot(ItemStack stack) {
        return slot(stack, metadataDescription(stack));
    }

    public static ItemGridSlot slot(ItemStack stack, InventoryContext context, InventoryRegistry registry,
                                    String sectionId, int slot) {
        return slot(stack, description(stack, context, registry, sectionId, slot));
    }

    /** Resolve independently so hosts can detect tooltip changes even when the underlying stack is unchanged. */
    public static String description(ItemStack stack, InventoryContext context, InventoryRegistry registry,
                                     String sectionId, int slot) {
        if (ItemStack.isEmpty(stack)) return null;
        String custom = registry.itemTooltip(new InventoryItemTooltipContext(context, sectionId, slot, stack));
        return custom != null ? custom : metadataDescription(stack);
    }

    /** Serializes a resolved description into the supported CustomUI field, never arbitrary BSON. */
    public static ItemGridSlot slot(ItemStack stack, String description) {
        ItemGridSlot display = new ItemGridSlot();
        display.setActivatable(true);
        if (ItemStack.isEmpty(stack)) return display;

        // Legacy CustomUI accepts client display metadata, not arbitrary server BSON documents.
        ItemStack copy = stack.withMetadata((BsonDocument) null);
        copy.setOverrideDroppedItemAnimation(stack.getOverrideDroppedItemAnimation());
        display.setItemStack(copy);

        ItemDisplayMetadata metadata = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        Message nameMsg = metadata != null ? metadata.getName() : null;
        if (nameMsg != null && nameMsg.getRawText() != null) {
            display.setName(nameMsg.getRawText());
        }
        if (description != null) {
            display.setDescription(description);
        } else if (metadata != null && metadata.getDescription() != null && metadata.getDescription().getRawText() != null) {
            display.setDescription(metadata.getDescription().getRawText());
        }
        return display;
    }

    private static String metadataDescription(ItemStack stack) {
        if (ItemStack.isEmpty(stack)) return null;
        ItemDisplayMetadata metadata = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        return metadata == null || metadata.getDescription() == null ? null : metadata.getDescription().getRawText();
    }
}
