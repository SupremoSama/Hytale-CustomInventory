package com.supremosan.custominventory.api;

/** Native ItemGrid source metadata; both naming variants are emitted by supported clients. */
public record InventoryDragData(Integer sourceInventorySectionId, Integer sourceSlotId,
                                Integer dragSourceInventorySectionId, Integer dragSourceSlotId,
                                String itemStackId, Integer itemStackQuantity,
                                String dragItemStackId, Integer dragItemStackQuantity) {
    public Integer sectionId() { return dragSourceInventorySectionId != null ? dragSourceInventorySectionId : sourceInventorySectionId; }
    public Integer slotId() { return dragSourceSlotId != null ? dragSourceSlotId : sourceSlotId; }
    public String itemId() {
        String id = dragItemStackId != null && !dragItemStackId.isBlank() ? dragItemStackId : itemStackId;
        return id == null || id.isBlank() ? null : id;
    }
    public Integer quantity() { return dragItemStackQuantity != null ? dragItemStackQuantity : itemStackQuantity; }
}
