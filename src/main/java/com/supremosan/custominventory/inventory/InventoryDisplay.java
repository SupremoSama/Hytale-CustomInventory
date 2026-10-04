package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import org.bson.BsonDocument;

/** Converts server items into safe CustomUI display copies without altering authoritative inventory. */
public final class InventoryDisplay {
    private InventoryDisplay() {}

    public static ItemGridSlot slot(ItemStack stack) {
        ItemGridSlot display = new ItemGridSlot();
        display.setActivatable(true);
        if (ItemStack.isEmpty(stack)) return display;

        // Legacy CustomUI accepts client display metadata, not arbitrary server BSON documents.
        ItemStack copy = stack.withMetadata((BsonDocument) null);
        copy.setOverrideDroppedItemAnimation(stack.getOverrideDroppedItemAnimation());
        display.setItemStack(copy);
        ItemDisplayMetadata metadata = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        if (metadata != null) {
            if (metadata.getName() != null && metadata.getName().getRawText() != null) {
                display.setName(metadata.getName().getRawText());
            }
            if (metadata.getDescription() != null && metadata.getDescription().getRawText() != null) {
                display.setDescription(metadata.getDescription().getRawText());
            }
        }
        return display;
    }
}
