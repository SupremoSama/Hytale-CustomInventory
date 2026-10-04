package com.supremosan.custominventory.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.CustomInventoryPlugin;

public final class CustomInventoryCommand extends AbstractPlayerCommand {
    private final CustomInventoryPlugin plugin;

    public CustomInventoryCommand(CustomInventoryPlugin plugin) {
        super("custominventory", "Open the extensible custom inventory");
        this.plugin = plugin;
        // Only displays and edits the invoking player's own inventory, under native access checks.
        requireNoPermission();
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef playerRef, World world) {
        plugin.open(ref, store, playerRef);
    }
}
