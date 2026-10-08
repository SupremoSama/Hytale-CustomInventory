package com.supremosan.custominventory.inventory;

import com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ResourceType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.supremosan.custominventory.api.InventoryContent;
import com.supremosan.custominventory.api.InventoryContentEvent;
import com.supremosan.custominventory.api.InventoryContext;
import com.supremosan.custominventory.api.InventoryEventBindings;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/** Native pocket presentation. The engine owns all crafting validation and transactions. */
public final class PocketCraftingContent implements InventoryContent {
    private static final String DOCUMENT = "Inventory/Crafting/PocketCrafting.ui";
    private final Map<ItemContainer, EventRegistration> listeners = new IdentityHashMap<>();
    private final Map<String, CraftingRecipe> displayedRecipes = new LinkedHashMap<>();
    private List<String> displayedRecipeIds = List.of();
    private String selectedRecipeId;
    private String status = "";
    private String search = "";
    private boolean hideUnknownRecipes;

    /** A bench recipe cannot be forged into a pocket crafting action. */
    public static boolean isPocketRecipe(CraftingRecipe recipe) {
        if (recipe == null || recipe.getId() == null || recipe.getId().isBlank()
                || recipe.getPrimaryOutput() == null || recipe.getPrimaryOutput().getItemId() == null
                || recipe.getPrimaryOutput().getItemId().isBlank() || recipe.getBenchRequirement() == null) return false;
        for (var requirement : recipe.getBenchRequirement()) {
            if (requirement != null && requirement.type == BenchType.Crafting
                    && CraftingRecipe.FIELDCRAFT_REQUIREMENT.equals(requirement.id)) return true;
        }
        return false;
    }

    private static int recipeItemLevel(CraftingRecipe recipe) {
        if (recipe == null || recipe.getPrimaryOutput() == null || recipe.getPrimaryOutput().getItemId() == null) {
            return Integer.MAX_VALUE;
        }
        var item = Item.getAssetMap().getAsset(recipe.getPrimaryOutput().getItemId());
        return item != null ? item.getItemLevel() : 0;
    }

    private static int recipeInputCount(CraftingRecipe recipe) {
        return recipe != null && recipe.getInput() != null ? recipe.getInput().length : 0;
    }

    public static List<CraftingRecipe> pocketRecipes(Collection<CraftingRecipe> recipes, Set<String> knownRecipes) {
        return pocketRecipes(recipes, knownRecipes, true);
    }

    static List<CraftingRecipe> pocketRecipes(Collection<CraftingRecipe> recipes, Set<String> knownRecipes, boolean hideUnknown) {
        return recipes.stream().filter(PocketCraftingContent::isPocketRecipe)
                .filter(recipe -> !hideUnknown || knownRecipe(recipe, knownRecipes))
                .sorted(Comparator.comparingInt(PocketCraftingContent::recipeItemLevel)
                        .thenComparingInt(PocketCraftingContent::recipeInputCount)
                        .thenComparing((CraftingRecipe recipe) -> recipe.getPrimaryOutput().getItemId())
                        .thenComparing(CraftingRecipe::getId)).toList();
    }

    public void setSearch(String value) { search = value == null ? "" : value; }
    public String getSearch() { return search; }
    public void setHideUnknownRecipes(boolean value) { hideUnknownRecipes = value; }
    public boolean isHideUnknownRecipes() { return hideUnknownRecipes; }

    List<CraftingRecipe> filteredPocketRecipes(Collection<CraftingRecipe> recipes, Set<String> knownRecipes,
                                               Function<String, String> translatedNames) {
        return pocketRecipes(recipes, knownRecipes, hideUnknownRecipes).stream()
                .filter(recipe -> matchesSearch(search, recipe.getPrimaryOutput().getItemId(),
                        translatedNames.apply(recipe.getPrimaryOutput().getItemId()))).toList();
    }

    private static boolean knownRecipe(CraftingRecipe recipe, Set<String> knownRecipes) {
        return !recipe.isKnowledgeRequired() || knownRecipes.contains(recipe.getPrimaryOutput().getItemId());
    }

    @Override public void build(InventoryContext context, UICommandBuilder commands, InventoryEventBindings bindings, String selector) {
        render(context, commands, bindings, selector, true);
    }

    @Override public void refresh(InventoryContext context, UICommandBuilder commands, InventoryEventBindings bindings, String selector) {
        render(context, commands, bindings, selector, false);
    }

    private void render(InventoryContext context, UICommandBuilder commands, InventoryEventBindings bindings, String selector, boolean mount) {
        if (mount) commands.append(selector, DOCUMENT);
        String root = selector + " #PocketCrafting";
        if (!validContext(context)) return;
        var player = context.store().getComponent(context.ref(), Player.getComponentType());
        if (player == null) return;
        var knownRecipes = player.getPlayerConfigData().getKnownRecipes();
        var recipes = filteredPocketRecipes(CraftingRecipe.getAssetMap().getAssetMap().values(), knownRecipes,
                itemId -> translatedName(itemId, context.playerRef().getLanguage()));
        displayedRecipes.clear();
        for (var recipe : recipes) displayedRecipes.put(recipe.getId(), recipe);
        displayedRecipeIds = recipes.stream().map(CraftingRecipe::getId).toList();
        int memories = MemoriesPlugin.get().getMemoriesLevel(context.store().getExternalData().getWorld().getGameplayConfig());
        var selected = selectUnlockedRecipe(recipes.stream().filter(recipe -> knownRecipe(recipe, knownRecipes)).toList(), selectedRecipeId, memories);
        selectedRecipeId = selected == null ? null : selected.getId();
        var inventory = InventoryComponent.getCombined(context.store(), context.ref(), InventoryComponent.BACKPACK_STORAGE_HOTBAR);
        syncListeners(context);
        boolean creative = player.getGameMode() == GameMode.Creative;
        var manager = context.store().getComponent(context.ref(), CraftingManager.getComponentType());
        boolean backendReady = manager != null && !manager.hasBenchSet() && !InventoryOperations.locked(context.ref(), context.store());
        var slots = new ItemGridSlot[recipes.size()];
        for (int index = 0; index < recipes.size(); index++) {
            var recipe = recipes.get(index);
            boolean memory = memoryUnlocked(recipe, memories);
            boolean known = knownRecipe(recipe, knownRecipes);
            slots[index] = recipeSlot(recipe, memory, known,
                    backendReady && memory && known && maxCraftable(recipe, inventory, creative) > 0,
                    recipe.getId().equals(selectedRecipeId));
        }
        commands.set(root + " #RecipeGrid.Slots", slots);
        if (mount) bindings.bind(CustomUIEventBindingType.SlotClicking, "#PocketCrafting #RecipeGrid", "SelectRecipe", "", true);
        commands.set(root + " #RecipeDetails.Visible", selected != null);
        commands.set(root + " #NoRecipes.Visible", selected == null);
        if (selected == null) return;
        String itemId = selected.getPrimaryOutput().getItemId();
        commands.set(root + " #RecipePreview.ItemId", itemId);
        var item = Item.getAssetMap().getAsset(itemId);
        if (item != null) commands.set(root + " #RecipeName.TextSpans", item.getTranslationMessage());
        else commands.set(root + " #RecipeName.Text", readable(itemId));
        var inputs = selected.getInput();
        commands.clear(root + " #Ingredients");
        int ingredientIndex = 0;
        if (inputs != null) for (var input : inputs) {
            if (input == null) continue;
            commands.append(root + " #Ingredients", "Inventory/Crafting/Ingredient.ui");
            String ingredient = root + " #Ingredients[" + ingredientIndex++ + "]";
            int count = inventory.countRemovableMaterial(input);
            boolean satisfied = creative || count >= input.getQuantity();
            commands.set(ingredient + " #SlotBackground.Background", Value.ref("Inventory/Crafting/Ingredient.ui", satisfied ? "SlotValidBackground" : "SlotInvalidBackground"));
            if (input.getItemId() != null) {
                var ingredientSlot = new ItemGridSlot(new ItemStack(input.getItemId(), 1));
                ingredientSlot.setSkipItemQualityBackground(true);
                commands.set(ingredient + " #IngredientItem.Slots", new ItemGridSlot[] { ingredientSlot });
                var ingredientItem = Item.getAssetMap().getAsset(input.getItemId());
                if (ingredientItem != null) commands.set(ingredient + " #IngredientName.TextSpans", ingredientItem.getTranslationMessage());
                else commands.set(ingredient + " #IngredientName.Text", readable(input.getItemId()));
            } else if (input.getResourceTypeId() != null) {
                commands.set(ingredient + " #IngredientItem.Visible", false);
                var resource = ResourceType.getAssetMap().getAsset(input.getResourceTypeId());
                if (resource != null && resource.getIcon() != null) {
                    commands.set(ingredient + " #IngredientResource.AssetPath", resource.getIcon());
                    commands.set(ingredient + " #IngredientResource.Visible", true);
                }
                commands.set(ingredient + " #IngredientName.TextSpans", Message.translation("server.resourceType." + input.getResourceTypeId() + ".name"));
            } else commands.set(ingredient + " #IngredientName.Text", "Material");
            commands.set(ingredient + " #IngredientCount.Text", count + "/" + input.getQuantity());
            commands.set(ingredient + " #IngredientCount.Style.TextColor", satisfied ? "#48d185" : "#D13B3B");
        }
        int limit = maxCraftable(selected, inventory, creative);
        commands.set(root + " #Craft1Button.Disabled", !backendReady || limit < 1);
        commands.set(root + " #Craft10Button.Disabled", !backendReady || limit < 10);
        commands.set(root + " #CraftAllButton.Disabled", !backendReady || limit < 1);
        commands.set(root + " #ProgressBar.Value", 0f); // Pocket crafting completes instantly in the engine.
        if (mount) {
            for (String quantity : List.of("1", "10", "All")) bindings.bind(CustomUIEventBindingType.Activating,
                    "#PocketCrafting #Craft" + quantity + "Button", "Craft" + quantity, "", true);
        }
        commands.set(root + " #Status.Text", status);
        commands.set(root + " #Status.Visible", !status.isBlank());
    }

    static ItemGridSlot recipeSlot(CraftingRecipe recipe, boolean memory, boolean known,
                                   boolean craftable, boolean selected) {
        var slot = new ItemGridSlot();
        slot.setActivatable(memory && known);
        slot.setSkipItemQualityBackground(!memory || !known);
        if (memory && known) {
            var output = recipe.getPrimaryOutput();
            slot.setItemStack(new ItemStack(output.getItemId(), Math.max(1, output.getQuantity())));
            slot.setItemUncraftable(!craftable);
        } else if (!memory) {
            slot.setIcon(Value.ref(DOCUMENT, "SlotMemoryLockedItemIcon"));
            slot.setName("Requires world memories level " + recipe.getRequiredMemoriesLevel());
        } else {
            slot.setIcon(Value.ref(DOCUMENT, "SlotUnknownItemIcon"));
            slot.setName("Unknown recipe");
        }
        if (selected) {
            slot.setBackground(Value.ref(DOCUMENT, "SlotSelectedBackground"));
            slot.setOverlay(Value.ref(DOCUMENT, "SlotSelectedOverlay"));
        }
        return slot;
    }

    @Override public void handleEvent(InventoryContext context, InventoryContentEvent event) {
        if (!validContext(context) || event == null) return;
        if (handlePresentationEvent(event)) return;
        String recipeId = event.payload();
        if ("SelectRecipe".equals(event.action()) && event.slotIndex() != null) {
            if (event.slotIndex() < 0 || event.slotIndex() >= displayedRecipeIds.size()) return;
            recipeId = displayedRecipeIds.get(event.slotIndex());
        } else if (recipeId == null || recipeId.isEmpty()) {
            recipeId = selectedRecipeId;
        }
        var displayed = displayedRecipes.get(recipeId);
        var recipe = displayed == null ? null : CraftingRecipe.getAssetMap().getAsset(recipeId);
        if (!isPocketRecipe(recipe)) return;
        var player = context.store().getComponent(context.ref(), Player.getComponentType());
        if (player == null || !knownRecipe(recipe, player.getPlayerConfigData().getKnownRecipes())) return;
        int memories = MemoriesPlugin.get().getMemoriesLevel(context.store().getExternalData().getWorld().getGameplayConfig());
        if (!memoryUnlocked(recipe, memories)) return;
        if ("SelectRecipe".equals(event.action())) {
            selectedRecipeId = recipe.getId();
            status = "";
            return;
        }
        if (!recipe.getId().equals(selectedRecipeId)) return;
        var manager = context.store().getComponent(context.ref(), CraftingManager.getComponentType());
        if (InventoryOperations.locked(context.ref(), context.store()) || manager == null || manager.hasBenchSet()) return;
        var inventory = InventoryComponent.getCombined(context.store(), context.ref(), InventoryComponent.BACKPACK_STORAGE_HOTBAR);
        int quantity = requestedQuantity(event.action(), maxCraftable(recipe, inventory, player.getGameMode() == GameMode.Creative));
        if (quantity < 1) return;
        // The player's real manager validates recipe knowledge, memory eligibility and material/output transactions.
        status = manager.craftItem(context.ref(), context.store(), recipe, quantity, inventory) ? "" : "Unable to craft this recipe";
        context.requestRefresh();
    }

    /** Presentation changes need no inventory mutation; the shell still validates the active page/session. */
    boolean handlePresentationEvent(InventoryContentEvent event) {
        if ("FilterRecipes".equals(event.action())) { setSearch(event.payload()); return true; }
        if ("ToggleUnknown".equals(event.action()) || "ToggleUnknownRecipes".equals(event.action())) {
            hideUnknownRecipes = !hideUnknownRecipes;
            return true;
        }
        return false;
    }

    static int requestedQuantity(String action, int available) {
        if (available < 1) return 0;
        return switch (action) {
            case "Craft1", "CraftRecipe" -> 1;
            case "Craft10" -> available >= 10 ? 10 : 0;
            case "CraftAll" -> available;
            default -> 0;
        };
    }

    static boolean matchesSearch(String query, String itemId, String translatedName) {
        String normalized = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() || itemId != null && itemId.replace('_', ' ').toLowerCase(java.util.Locale.ROOT).contains(normalized)
                || translatedName != null && translatedName.toLowerCase(java.util.Locale.ROOT).contains(normalized);
    }

    private static String translatedName(String itemId, String language) {
        var item = Item.getAssetMap().getAsset(itemId);
        var i18n = I18nModule.get();
        return item == null || i18n == null || item.getTranslationKey() == null ? null : i18n.getMessage(language, item.getTranslationKey());
    }

    private static int maxCraftable(CraftingRecipe recipe, ItemContainer inventory, boolean creative) {
        if (inventory == null) return 0;
        var output = recipe.getPrimaryOutput();
        var item = Item.getAssetMap().getAsset(output.getItemId());
        // Match the engine's permissive overflow ceiling: occupied slots may merge outputs,
        // and removing recipe inputs may free a slot. The real craft decides where outputs go.
        int outputLimit = outputLimit(inventory.getCapacity(), item == null ? 1 : item.getMaxStack(), output.getQuantity());
        return materialLimit(recipe, outputLimit, creative, inventory::countRemovableMaterial,
                quantity -> inventory.canRemoveMaterials(CraftingManager.getInputMaterials(recipe, quantity)));
    }

    static int outputLimit(int capacity, int stackSize, int outputQuantity) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) capacity * Math.max(1, stackSize) / Math.max(1, outputQuantity)));
    }

    /** Checks the combined request too, so overlapping ingredient predicates cannot double-count a stack. */
    static int materialLimit(CraftingRecipe recipe, int outputLimit, boolean creative,
                             ToIntFunction<MaterialQuantity> count, IntPredicate canRemove) {
        if (outputLimit < 1) return 0;
        var inputs = recipe.getInput();
        if (inputs == null || inputs.length == 0) return 1;
        int upper = outputLimit;
        boolean requirement = false;
        for (var input : inputs) {
            if (input == null || input.getQuantity() <= 0) continue;
            requirement = true;
            upper = Math.min(upper, Integer.MAX_VALUE / input.getQuantity());
            if (!creative) upper = Math.min(upper, Math.max(0, count.applyAsInt(input)) / input.getQuantity());
        }
        if (!requirement) return 1;
        if (creative) return upper;
        int lower = 0;
        while (lower < upper) {
            int probe = lower + (int) (((long) upper - lower + 1) / 2);
            if (canRemove.test(probe)) lower = probe;
            else upper = probe - 1;
        }
        return lower;
    }

    private static boolean validContext(InventoryContext context) {
        return context != null && context.ref() != null && context.store() != null && context.playerRef() != null && context.ref().isValid()
                && context.ref().getStore() == context.store() && context.playerRef().getReference() == context.ref();
    }

    static boolean memoryUnlocked(CraftingRecipe recipe, int level) { return recipe.getRequiredMemoriesLevel() <= 1 || level >= recipe.getRequiredMemoriesLevel(); }

    static CraftingRecipe selectUnlockedRecipe(List<CraftingRecipe> recipes, String preferredId, int memories) {
        CraftingRecipe first = null;
        for (var recipe : recipes) {
            if (!memoryUnlocked(recipe, memories)) continue;
            if (recipe.getId().equals(preferredId)) return recipe;
            if (first == null) first = recipe;
        }
        return first;
    }

    private void syncListeners(InventoryContext context) {
        Set<ItemContainer> current = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var section : List.of(NativeInventorySection.STORAGE, NativeInventorySection.HOTBAR, NativeInventorySection.BACKPACK)) {
            var container = InventoryOperations.resolveContainer(context.ref(), context.store(), section);
            if (container != null) {
                current.add(container);
                listeners.computeIfAbsent(container, key -> key.registerChangeEvent(event -> context.requestRefresh()));
            }
        }
        listeners.entrySet().removeIf(entry -> {
            if (current.contains(entry.getKey())) return false;
            entry.getValue().unregister();
            return true;
        });
    }

    @Override public void onDismiss(InventoryContext context) {
        for (var listener : listeners.values()) listener.unregister();
        listeners.clear();
        displayedRecipes.clear();
        displayedRecipeIds = List.of();
    }

    private static String readable(String id) { return id == null ? "Material" : id.replace('_', ' '); }
}
