package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.api.ExtraEquipment;
import java.util.Set;

/** Applies the world's normal loss settings to the additional equipment inventory. */
public final class ExtraEquipmentDeathSystem extends DeathSystems.OnDeathSystem {
    @Override public Query<EntityStore> getQuery() { return Query.and(Player.getComponentType(), ExtraEquipment.TYPE); }
    @Override public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER, DeathSystems.PlayerDropItemsConfig.class));
    }
    @Override public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death,
            Store<EntityStore> store, CommandBuffer<EntityStore> commands) {
        var player = store.getComponent(ref, Player.getComponentType());
        if (player.getGameMode() == GameMode.Creative) return;
        var inventory = store.getComponent(ref, ExtraEquipment.TYPE).getInventory();
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            var item = inventory.getItemStack(slot);
            if (ItemStack.isEmpty(item)) continue;
            if (!item.isUnbreakable() && death.getItemsDurabilityLossPercentage() > 0) {
                item = item.withIncreasedDurability(-item.getMaxDurability() * death.getItemsDurabilityLossPercentage() / 100.0);
                inventory.setItemStackForSlot(slot, item);
            }
            boolean lose = switch (death.getItemsLossMode()) {
                case ALL -> true;
                case CONFIGURED -> death.getItemsAmountLossPercentage() > 0 && item.getItem().dropsOnDeath();
                case NONE -> false;
            };
            if (lose) {
                inventory.removeItemStackFromSlot(slot);
                if (ItemUtils.dropItem(ref, item, commands) == null) inventory.setItemStackForSlot(slot, item);
            }
        }
    }
}
