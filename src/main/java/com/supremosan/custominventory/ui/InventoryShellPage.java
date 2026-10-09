package com.supremosan.custominventory.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.function.FunctionCodec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.packets.inventory.DropItemStack;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.api.*;
import com.supremosan.custominventory.inventory.NativeInventoryContent;
import com.supremosan.custominventory.inventory.NativeInventorySection;
import com.supremosan.custominventory.inventory.InventoryOperations;
import com.supremosan.custominventory.inventory.PocketCraftingContent;
import com.supremosan.custominventory.inventory.CollectedMemoriesContent;
import com.supremosan.custominventory.inventory.BackpackInventoryContent;
import com.supremosan.custominventory.ui.player.PlayerInventoryPanel;
import com.supremosan.custominventory.ui.player.UtilitySlotSelector;

import java.util.Objects;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Extensible inventory page using the supported custom-page lifecycle. */
public final class InventoryShellPage extends InteractiveCustomUIPage<InventoryShellPage.Event> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final String DEFAULT_PAGE = "inventory:native";
    public static final String MEMORIES_PAGE = "inventory:memories";
    public static final String BACKPACK_PAGE = "inventory:backpack";
    // Deliberately outside the public registration ID grammar.
    private static final String INVENTORY_PANELS = "@inventory:panels";
    private static final String PLAYER_PANEL = "@inventory:player";
    private static final int MAX_VISIBLE_BACKPACK_SLOTS = 45;
    private final NativeInventoryContent inventoryPanels;
    private final PlayerInventoryPanel playerPanel = new PlayerInventoryPanel();
    private final InventoryRegistry registry;
    private final InventoryPageDefinition hostedView;
    private final InventoryUiExtension viewExtension;
    private final Map<String, MountedExtension> extensions = new LinkedHashMap<>();
    private long extensionSequence;
    private boolean opened;
    private boolean viewExtensionMounted;
    private String navigationInstanceId;
    private java.util.List<InventoryPageDefinition> renderedPages = java.util.List.of();
    private java.util.List<InventoryButtonDefinition> renderedButtons = java.util.List.of();
    private record MountedExtension(InventoryRegistry.Entry<InventoryUiExtensionDefinition> registration,
                                    InventoryUiExtension extension, InventoryContext context, String token) {}
    private final Consumer<InventoryShellPage> dismissedCallback;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean panelRefreshQueued = new AtomicBoolean();
    private ScheduledFuture<?> panelRefresh;
    private InventoryPageDefinition activeDefinition;
    private InventoryRegistry.Entry<InventoryPageDefinition> activeRegistration;
    private final Map<String, InventoryRegistry.Entry<InventoryButtonDefinition>> mountedButtons = new HashMap<>();
    private final Map<String, InventoryRegistry.Entry<InventoryPageDefinition>> mountedPages = new HashMap<>();
    private String pageInstanceId = UUID.randomUUID().toString();
    private InventoryContent activeContent;
    private volatile InventoryContext activeContext;
    private String pageId = DEFAULT_PAGE;
    private String sessionId;
    private String hostedContentSessionId;
    private long revision;
    private volatile boolean dismissed;
    private NativeTabState nativeTabState;
    private String memoriesTabSelector;
    private String backpackTabSelector;

    public InventoryShellPage(PlayerRef playerRef, InventoryRegistry registry,
                              Consumer<InventoryShellPage> dismissedCallback) {
        this(playerRef, registry, dismissedCallback, null, null);
    }

    public InventoryShellPage(PlayerRef playerRef, InventoryRegistry registry,
                              Consumer<InventoryShellPage> dismissedCallback,
                              InventoryPageDefinition hostedView, InventoryUiExtension viewExtension) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Event.CODEC);
        this.registry = Objects.requireNonNull(registry);
        this.inventoryPanels = new NativeInventoryContent(this.registry);
        this.dismissedCallback = Objects.requireNonNull(dismissedCallback);
        this.hostedView = hostedView;
        this.viewExtension = viewExtension;
        if (hostedView != null) pageId = hostedView.id();
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands,
                      UIEventBuilder events, Store<EntityStore> store) {
        activeContext = createContext(ref, store);
        render(commands, events, true);
        startPanelRefresh();
    }

    /** Restores this same page when the client has locally opened the native inventory again. */
    public void refocus(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!canRefocus(ref, store) || !remainInAdventure(ref, store)) return;
        activeContext = createContext(ref, store);
        var commands = new UICommandBuilder();
        var events = new UIEventBuilder();
        try {
            render(commands, events, true);
            var player = store.getComponent(ref, Player.getComponentType());
            if (player != null && isActive(ref, store)) {
                player.getPageManager().updateLegacyCustomPage(new CustomPage(getClass().getName(),
                        true, true, getLifetime(), commands.getCommands(), events.getEvents()));
            }
        } catch (RuntimeException failure) {
            if (isActive(ref, store)) {
                store.getComponent(ref, Player.getComponentType()).getPageManager().setPage(ref, store, Page.None);
            } else onDismiss(ref, store);
            throw failure;
        }
    }

    private void render(UICommandBuilder commands, UIEventBuilder events, boolean initial) {
        var pages = hostedView == null ? registry.pagesSnapshot() : java.util.List.<InventoryPageDefinition>of();
        if ((MEMORIES_PAGE.equals(pageId) && memoryCapacity(activeContext) == 0)
                || (BACKPACK_PAGE.equals(pageId) && backpackCapacity(activeContext) == 0)) pageId = DEFAULT_PAGE;
        var selectedRegistration = hostedView == null ? registry.getPageRegistration(pageId) : null;
        if (selectedRegistration == null && !pages.isEmpty()) selectedRegistration = registry.getPageRegistration(pages.getFirst().id());
        var selected = hostedView != null ? hostedView : selectedRegistration == null ? null : selectedRegistration.definition();
        boolean pageChanged = selectedRegistration != activeRegistration || selected != activeDefinition;
        if (pageChanged) {
            dismissContent();
            activeRegistration = selectedRegistration;
            activeDefinition = selected;
            pageId = selected == null ? null : selected.id();
            activeContext = createContext(activeContext.ref(), activeContext.store());
            activeContent = selected == null ? null : Objects.requireNonNull(selected.factory().apply(activeContext),
                    "Inventory page factory returned null: " + selected.id());
        }
        pageId = selected == null ? null : selected.id();
        boolean keepBackpackBody = !initial && !pageChanged && activeContent instanceof BackpackInventoryContent;
        sessionId = pageInstanceId + ":" + (++revision);
        var buttons = hostedView == null ? registry.buttonsSnapshot() : java.util.List.<InventoryButtonDefinition>of();
        boolean remountNavigation = initial || !pages.equals(renderedPages) || !buttons.equals(renderedButtons)
                || mountedPages.entrySet().stream().anyMatch(e -> registry.getPageRegistration(e.getKey()) != e.getValue())
                || mountedButtons.entrySet().stream().anyMatch(e -> registry.getButtonRegistration(e.getKey()) != e.getValue());
        if (remountNavigation) navigationInstanceId = UUID.randomUUID().toString();
        renderedPages = pages;
        renderedButtons = buttons;
        mountedButtons.clear();
        mountedPages.clear();

        if (initial) {
            commands.append("Inventory/InventoryShell.ui");
            commands.set("#InventoryBackdrop #InventoryDropZone.Slots", new ItemGridSlot[]{new ItemGridSlot()});
            commands.append("Inventory/Navigation.ui");
            commands.set("#NavigationVersionLabel.Text", GameVersionLabel.text());
            commands.append("Inventory/InputHints.ui");
            commands.append("#PlayerPanelHost", "Inventory/PlayerPanel.ui");
            commands.append("#InventoryPanelHost", "Inventory/InventoryPanel.ui");
        } else {
            if (pageChanged) commands.clear("#ContentHost");
            if (hostedView == null && remountNavigation) {
                commands.clear("#Navigation");
                commands.clear("#ExtensionButtons");
            }
        }
        // These controls remain mounted across extension page changes and inventory updates.
        if (initial) {
            var backdropEvents = new InventoryEventBindings(events, "#InventoryBackdrop", INVENTORY_PANELS, pageInstanceId);
            backdropEvents.bind(CustomUIEventBindingType.Dropped, "#InventoryDropZone", "DropOutside", "", false);
            playerPanel.build(activeContext, commands,
                    new InventoryEventBindings(events, "#PlayerPanelHost", PLAYER_PANEL, pageInstanceId),
                    new InventoryEventBindings(events, "#ExtraEquipmentHost", PLAYER_PANEL, pageInstanceId), "#PlayerPanelHost");
            inventoryPanels.build(activeContext, commands,
                    new InventoryEventBindings(events, "#InventoryShell", INVENTORY_PANELS, pageInstanceId), "#InventoryShell");
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", persistentCoreEvent("Close"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#NavigationInventoryButton", persistentCoreEvent("Close"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#InventoryBackButton", persistentCoreEvent("Close"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#NavigationMapButton", persistentCoreEvent("Map"));
            events.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton", persistentCoreEvent("Refresh"));
        } else {
            inventoryPanels.refresh(activeContext, commands, "#InventoryShell");
            playerPanel.refresh(activeContext, commands, "#PlayerPanelHost");
        }
        renderHeader(commands, events, initial || pageChanged, selected);
        commands.set("#NavigationMapButton.Disabled", !activeContext.store().getExternalData().getWorld().getWorldMapManager().isWorldMapEnabled());
        if (!(activeContent instanceof BackpackInventoryContent)) inventoryPanels.unmountBackpack();
        memoriesTabSelector = null;
        backpackTabSelector = null;

        for (int index = 0; index < pages.size(); index++) {
            var definition = pages.get(index);
            var registration = registry.getPageRegistration(definition.id());
            if (registration != null && registration.definition() == definition) mountedPages.put(definition.id(), registration);
            if (remountNavigation) commands.append("#Navigation", "Inventory/NavigationButton.ui");
            String selector = "#Navigation[" + index + "] #EntryButton";
            commands.setObject("#Navigation[" + index + "].Anchor", tabAnchor(index));
            renderTab(commands, selector, definition.id(), definition.title(), definition == selected);
            if (MEMORIES_PAGE.equals(definition.id())) memoriesTabSelector = selector;
            if (BACKPACK_PAGE.equals(definition.id())) backpackTabSelector = selector;
            if (remountNavigation) events.addEventBinding(CustomUIEventBindingType.Activating, selector, navigationEvent("Navigate", definition.id()));
        }
        for (int index = 0; index < buttons.size(); index++) {
            var definition = buttons.get(index);
            var registration = registry.getButtonRegistration(definition.id());
            if (registration != null && registration.definition() == definition) mountedButtons.put(definition.id(), registration);
            if (remountNavigation) commands.append("#ExtensionButtons", "Inventory/NavigationButton.ui");
            String selector = "#ExtensionButtons[" + index + "] #EntryButton";
            commands.setObject("#ExtensionButtons[" + index + "].Anchor", tabAnchor(index + pages.size()));
            renderTab(commands, selector, definition.id(), definition.title(), false);
            if (remountNavigation) events.addEventBinding(CustomUIEventBindingType.Activating, selector, navigationEvent("Button", definition.id()));
        }
        applyCharacterTabLayout(commands, pages.size() + buttons.size());
        nativeTabState = readNativeTabState(activeContext);
        writeNativeBadges(commands, nativeTabState);

        if (activeContent != null && !keepBackpackBody) {
            var bindings = new InventoryEventBindings(events, "#ContentHost", pageId, sessionId);
            if (!initial && !pageChanged) activeContent.refresh(activeContext, commands, bindings, "#ContentHost");
            else activeContent.build(activeContext, commands, bindings, "#ContentHost");
            // Mounted forms keep their bindings on presentation-only refreshes.
            // A content rebuild/rebind gets a new token, invalidating events from removed controls.
            if (hostedView != null && (initial || pageChanged || bindings.bindingCount() > 0)) hostedContentSessionId = sessionId;
        } else if (activeContent == null) {
            commands.appendInline("#ContentHost", "Label { Text: \"No inventory pages registered.\"; Style: (TextColor: #c9d6df, FontSize: 18); }");
        }
        if (activeContent instanceof BackpackInventoryContent) {
            inventoryPanels.mountBackpack(activeContext, commands,
                    new InventoryEventBindings(events, "#InventoryShell", INVENTORY_PANELS, pageInstanceId), "#InventoryShell", !keepBackpackBody);
        }
        renderExtensions(commands, events, initial);
    }

    private void renderExtensions(UICommandBuilder commands, UIEventBuilder events, boolean initial) {
        var snapshot = registry.extensionsSnapshot();
        var iterator = extensions.entrySet().iterator();
        while (iterator.hasNext()) {
            var mounted = iterator.next();
            if (registry.getExtensionRegistration(mounted.getKey()) != mounted.getValue().registration()) {
                iterator.remove();
                InventoryUiEditor.removeContribution(commands, mounted.getValue().token());
                closeExtension(mounted.getValue().extension(), mounted.getValue().context());
            }
        }
        for (var registration : snapshot) {
            String id = registration.definition().id();
            var mounted = extensions.get(id);
            boolean created = mounted == null;
            if (created) {
                String token = "E" + (++extensionSequence);
                var context = extensionContext(registration, token);
                mounted = new MountedExtension(registration, Objects.requireNonNull(registration.definition().factory().apply(context)),
                        context, token);
                extensions.put(id, mounted);
            }
            if (initial || created) InventoryUiEditor.mountContribution(commands, mounted.token());
            try (var editor = new InventoryUiEditor(commands,
                    new InventoryEventBindings(events, "", "@extension:" + id, pageInstanceId), mounted.token())) {
                if (initial || created) mounted.extension().onCreated(mounted.context(), editor);
                mounted.extension().onUpdate(mounted.context(), editor);
            }
            if (!opened || created) mounted.extension().onOpened(mounted.context());
        }
        if (viewExtension != null) {
            viewExtensionMounted = true;
            try (var editor = new InventoryUiEditor(commands,
                    new InventoryEventBindings(events, "", "@extension:view", pageInstanceId))) {
                if (initial) viewExtension.onCreated(activeContext, editor);
                viewExtension.onUpdate(activeContext, editor);
            }
            if (!opened) viewExtension.onOpened(activeContext);
        }
        opened = true;
    }

    private void closeExtension(InventoryUiExtension extension, InventoryContext context) {
        try { extension.onClosed(context); }
        catch (RuntimeException failure) { LOGGER.atWarning().withCause(failure).log("Inventory extension cleanup failed"); }
    }

    private InventoryContext extensionContext(InventoryRegistry.Entry<InventoryUiExtensionDefinition> registration, String token) {
        var context = createContext(activeContext.ref(), activeContext.store(), false);
        java.util.function.BooleanSupplier registered = () -> registry.getExtensionRegistration(registration.definition().id()) == registration;
        return new InventoryContext(context.ref(), context.store(), context.playerRef(),
                () -> { if (registered.getAsBoolean()) context.requestRefresh(); }, context.viewId(),
                () -> { if (registered.getAsBoolean()) context.requestClose(); },
                () -> registered.getAsBoolean() && context.isActive(),
                commands -> {
                    if (!registered.getAsBoolean()) return;
                    for (var command : commands.getCommands()) {
                        if (command.selector == null || java.util.List.of("#ContentExtensions", "#HeaderExtensions", "#NavigationExtensions",
                                "#ButtonExtensions", "#AuxiliaryExtensions").stream()
                                .noneMatch(root -> command.selector.startsWith(root + " #CIContribution" + token + " ")))
                            throw new IllegalArgumentException("Updates must target this registration's contribution elements");
                    }
                    context.update(commands);
                });
    }

    private InventoryContext createContext(Ref<EntityStore> ref, Store<EntityStore> store) {
        return createContext(ref, store, true);
    }

    private InventoryContext createContext(Ref<EntityStore> ref, Store<EntityStore> store, boolean contentScoped) {
        String generation = pageInstanceId;
        var contentRegistration = activeRegistration;
        var contentDefinition = activeDefinition;
        java.util.function.BooleanSupplier active = () -> !dismissed && generation.equals(pageInstanceId)
                && (!contentScoped || activeRegistration == contentRegistration && activeDefinition == contentDefinition)
                && playerRef.getReference() == ref && isActive(ref, store);
        Runnable close = () -> runOnWorld(store.getExternalData().getWorld(), () -> {
            if (active.getAsBoolean()) store.getComponent(ref, Player.getComponentType()).getPageManager().setPage(ref, store, Page.None);
        });
        return new InventoryContext(ref, store, playerRef, this::requestRefresh,
                hostedView == null ? DEFAULT_PAGE : hostedView.id(), close, active,
                commands -> {
                    var world = store.getExternalData().getWorld();
                    if (!world.isInThread()) throw new IllegalStateException("Presentation updates require the player's world thread");
                    if (active.getAsBoolean()) {
                        validatePresentation(commands);
                        sendPresentationUpdate(ref, store, commands);
                    }
                });
    }

    private static void runOnWorld(World world, Runnable task) {
        if (world.isInThread()) task.run();
        else world.execute(task);
    }

    private static void validatePresentation(UICommandBuilder commands) {
        var roots = java.util.List.of("#ContentHost ", "#PageHeaderActions ", "#Navigation ", "#ExtensionButtons ", "#AuxiliaryHost ",
                "#ContentExtensions ", "#HeaderExtensions ", "#NavigationExtensions ", "#ButtonExtensions ", "#AuxiliaryExtensions ");
        for (var command : commands.getCommands()) {
            if (command.selector == null
                    || roots.stream().noneMatch(command.selector::startsWith)
                    || command.selector.endsWith(".InventorySectionId") || command.selector.endsWith(".AreItemsDraggable"))
                throw new IllegalArgumentException("Updates must target owned contribution elements; use lifecycle editors for core controls");
        }
    }

    private void renderHeader(UICommandBuilder commands, UIEventBuilder events, boolean mount, InventoryPageDefinition selected) {
        if (mount) commands.clear("#PageHeaderActions");
        boolean memories = activeContent instanceof CollectedMemoriesContent;
        boolean backpack = activeContent instanceof BackpackInventoryContent;
        boolean crafting = activeContent instanceof PocketCraftingContent;
        int capacity = backpack ? backpackCapacity(activeContext) : 0;
        applyContentLayout(commands, backpack, memories, capacity);
        commands.set("#PageTitle.Text", crafting ? localized("Pocket crafting", "Fabricação de bolso")
                : memories ? localized("Collected memories", "Memórias coletadas")
                : backpack ? "" : selected == null ? "" : selected.title());
        if (crafting) {
            var content = (PocketCraftingContent) activeContent;
            if (mount) {
                commands.append("#PageHeaderActions", "Inventory/CraftingHeader.ui");
                commands.set("#CraftingSearch.Value", content.getSearch());
                bindRecipeSearch(events, pageInstanceId);
                events.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleUnknownRecipes",
                        headerEvent("CraftingHeader", "ToggleUnknownRecipes"));
            }
            applyRecipeVisibility(commands, content.isHideUnknownRecipes());
        } else if (memories) {
            if (mount) commands.append("#PageHeaderActions", "Inventory/Memories/Count.ui");
            commands.set("#MemoryCount.Text", memoryCount(activeContext) + "/" + memoryCapacity(activeContext));
        } else if (backpack && mount) {
            commands.append("#PageHeaderActions", "Inventory/BackpackActions.ui");
            String[] ids = {"#TakeAllButton", "#PutAllButton", "#QuickStackButton", "#BackpackSortButton"};
            String[] actions = {"TakeAll", "PutAll", "QuickStack", "SortBackpack"};
            for (int index = 0; index < ids.length; index++) {
                events.addEventBinding(CustomUIEventBindingType.Activating, ids[index], headerEvent("BackpackHeader", actions[index]));
                commands.set(ids[index] + ".TooltipText", InventoryTooltips.containerAction(actions[index]));
            }
        }
    }

    // Anchor fields are not individually writable CustomUI markup properties.
    // Replace the complete value, preserving the fixed coordinates of the native tabs.
    static void applyCharacterTabLayout(UICommandBuilder commands, int entries) {
        var anchor = new Anchor();
        anchor.setLeft(Value.of(302));
        anchor.setTop(Value.of(98));
        anchor.setWidth(Value.of(72));
        anchor.setHeight(Value.of(Math.max(212, entries * 67 + 11)));
        commands.setObject("#CharacterTabs.Anchor", anchor);
    }

    static void applyContentLayout(UICommandBuilder commands, boolean backpack, boolean memories, int capacity) {
        var anchor = new Anchor();
        int visibleSlots = Math.max(1, Math.min(capacity, MAX_VISIBLE_BACKPACK_SLOTS));
        anchor.setHeight(Value.of(backpack ? 70 + ((visibleSlots + 8) / 9) * 76 : 423));
        commands.setObject("#ContentPanel.Anchor", anchor);
        commands.set("#ContentPanel #Content.Padding", Value.ref("Inventory/InventoryShell.ui",
                memories ? "MemoriesContentPadding" : backpack
                        ? capacity > MAX_VISIBLE_BACKPACK_SLOTS ? "ScrollableBackpackContentPadding" : "BackpackContentPadding"
                        : "CraftingContentPadding"));
        commands.set("#PageTitle.Style", Value.ref("Inventory/InventoryShell.ui",
                memories ? "MemoriesPanelTitleStyle" : "PanelTitleStyle"));
    }

    static void applyRecipeVisibility(UICommandBuilder commands, boolean hideUnknown) {
        commands.set("#ToggleUnknownRecipes.Style", Value.ref("Inventory/CraftingHeader.ui",
                hideUnknown ? "HideUnknownRecipesStyle" : "ShowUnknownRecipesStyle"));
    }

    private EventData headerEvent(String action, String contentAction) {
        return new EventData().append("Action", action).append("ContentAction", contentAction).append("SessionId", pageInstanceId);
    }

    /** Client-bound keys retain their @ prefix when the client returns the evaluated value. */
    public static void bindRecipeSearch(UIEventBuilder events, String pageInstanceId) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#CraftingSearch",
                new EventData().append("Action", "CraftingHeader").append("ContentAction", "FilterRecipes")
                        .append("SessionId", pageInstanceId).append(Event.SEARCH_QUERY, "#CraftingSearch.Value"), false);
    }

    /** Extension tabs reuse the client's shared recipes icon, which has no state variants. */
    private static String tabIconPath(String icon, String state) {
        return "Recipes".equals(icon) ? "Common/RecipesIcon.png" : "Inventory/Textures/" + icon + state + "Icon.png";
    }

    private void renderTab(UICommandBuilder commands, String selector, String id, String title, boolean selected) {
        String icon = DEFAULT_PAGE.equals(id) ? "PocketCrafting" : MEMORIES_PAGE.equals(id) ? "Memories"
                : BACKPACK_PAGE.equals(id) ? "Backpack" : "Recipes";
        boolean locked = MEMORIES_PAGE.equals(id) && memoryCapacity(activeContext) == 0
                || BACKPACK_PAGE.equals(id) && backpackCapacity(activeContext) == 0;
        commands.set(selector + ".Disabled", locked);
        commands.set(selector + ".TooltipText", DEFAULT_PAGE.equals(id) ? localized("Pocket crafting", "Fabricação de bolso")
                : MEMORIES_PAGE.equals(id) ? localized("Collected memories", "Memórias coletadas")
                : BACKPACK_PAGE.equals(id) ? localized("Backpack", "Mochila") : title);
        commands.setObject(selector + " #IconInactive.Background", new PatchStyle(Value.of(tabIconPath(icon, locked ? "Locked" : ""))));
        commands.setObject(selector + " #IconActive.Background", new PatchStyle(Value.of(tabIconPath(icon, "Active"))));
        int size = "Backpack".equals(icon) ? 50 : 48;
        var iconAnchor = new Anchor();
        iconAnchor.setWidth(Value.of(size)); iconAnchor.setHeight(Value.of(size));
        if ("Memories".equals(icon)) iconAnchor.setTop(Value.of(12));
        commands.setObject(selector + " #IconInactive.Anchor", iconAnchor);
        commands.setObject(selector + " #IconActive.Anchor", iconAnchor);
        commands.set(selector + " #IconInactive.Visible", !selected);
        commands.set(selector + " #IconActive.Visible", selected);
        commands.set(selector + ".Style", Value.ref("Inventory/NavigationButton.ui", selected ? "ActiveStyle" : "DefaultStyle"));
        commands.set(selector + " #ActiveBackground.Visible", false);
        commands.set(selector + " #Lock.Visible", locked);
        commands.set(selector + " #Alert.Visible", locked && MEMORIES_PAGE.equals(id));
    }

    private static Anchor tabAnchor(int index) {
        var anchor = new Anchor();
        anchor.setLeft(Value.of(0)); anchor.setTop(Value.of(4 + index * 67));
        anchor.setWidth(Value.of(70)); anchor.setHeight(Value.of(70));
        return anchor;
    }

    private String localized(String english, String portuguese) {
        String language = playerRef.getLanguage();
        return language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("pt") ? portuguese : english;
    }

    private static int memoryCapacity(InventoryContext context) {
        var memories = context.store().getComponent(context.ref(), PlayerMemories.getComponentType());
        return memories == null ? 0 : memories.getMemoriesCapacity();
    }
    private static int memoryCount(InventoryContext context) {
        var memories = context.store().getComponent(context.ref(), PlayerMemories.getComponentType());
        return memories == null ? 0 : memories.getRecordedMemories().size();
    }
    private static int backpackCapacity(InventoryContext context) {
        var backpack = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.BACKPACK);
        return backpack == null ? 0 : backpack.getCapacity();
    }
    private static NativeTabState readNativeTabState(InventoryContext context) {
        var backpack = InventoryOperations.resolveContainer(context.ref(), context.store(), NativeInventorySection.BACKPACK);
        return new NativeTabState(memoryCapacity(context), memoryCount(context), backpack == null ? 0 : backpack.getCapacity(),
                InventorySlotUsage.occupiedSlots(backpack));
    }
    private void writeNativeBadges(UICommandBuilder commands, NativeTabState state) {
        if (memoriesTabSelector != null) {
            commands.set(memoriesTabSelector + " #Badge.Visible", state.memoryCount() > 0);
            commands.set(memoriesTabSelector + " #BadgeCount.Text", String.valueOf(state.memoryCount()));
        }
        if (backpackTabSelector != null) {
            commands.set(backpackTabSelector + " #Badge.Visible", state.backpackCount() > 0);
            commands.set(backpackTabSelector + " #BadgeCount.Text", String.valueOf(state.backpackCount()));
        }
    }
    private record NativeTabState(int memoryCapacity, int memoryCount, int backpackCapacity, int backpackCount) { }

    private EventData navigationEvent(String action, String target) {
        return new EventData().append("Action", action).append("Target", target).append("SessionId", navigationInstanceId);
    }

    private EventData persistentCoreEvent(String action) {
        return new EventData().append("Action", action).append("SessionId", pageInstanceId);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Event event) {
        boolean panelEvent = "Content".equals(event.action)
                && (INVENTORY_PANELS.equals(event.pageId) || PLAYER_PANEL.equals(event.pageId));
        boolean persistentEvent = panelEvent || "Close".equals(event.action) || "Map".equals(event.action) || "Refresh".equals(event.action)
                || "CraftingHeader".equals(event.action) || "BackpackHeader".equals(event.action)
                || "Content".equals(event.action) && (hostedView != null || event.pageId != null && event.pageId.startsWith("@extension:"));
        String expectedSession = "Navigate".equals(event.action) || "Button".equals(event.action)
                ? navigationInstanceId : persistentEvent ? pageInstanceId : sessionId;
        if (hostedView != null && "Content".equals(event.action) && hostedView.id().equals(event.pageId))
            expectedSession = hostedContentSessionId;
        if (dismissed || !acceptsEvent(expectedSession, event.sessionId)
                || !isActive(ref, store) || !remainInAdventure(ref, store)) return;
        switch (event.action == null ? "" : event.action) {
            case "Close" -> store.getComponent(ref, Player.getComponentType()).getPageManager().setPage(ref, store, Page.None);
            case "Map" -> {
                if (store.getExternalData().getWorld().getWorldMapManager().isWorldMapEnabled()) {
                    store.getComponent(ref, Player.getComponentType()).getPageManager().setPage(ref, store, Page.Map);
                }
            }
            case "Refresh" -> requestRefresh();
            case "CraftingHeader", "BackpackHeader" -> {
                if (activeRegistration != registry.getPageRegistration(pageId)) return;
                if (("CraftingHeader".equals(event.action) && activeContent instanceof PocketCraftingContent)
                        || ("BackpackHeader".equals(event.action) && activeContent instanceof BackpackInventoryContent)) {
                    activeContent.handleEvent(activeContext, event.headerContentEvent());
                    requestRefresh();
                }
            }
            case "Navigate" -> {
                var mounted = mountedPages.get(event.target);
                if (mounted != null && mounted == registry.getPageRegistration(event.target)) {
                    pageId = event.target;
                    requestRefresh();
                }
            }
            case "Button" -> {
                var mounted = mountedButtons.get(event.target);
                if (mounted != null && mounted == registry.getButtonRegistration(event.target)) {
                    mounted.definition().action().accept(activeContext);
                    requestRefresh();
                }
            }
            case "Content" -> {
                var contentEvent = new InventoryContentEvent(event.contentAction, event.payload, event.slotIndex,
                        event.dragData(), event.resolvedMouseButton(),
                        event.formValues(), event.shiftHeld);
                if ("@extension:view".equals(event.pageId) && viewExtension != null) {
                    viewExtension.handleEvent(activeContext, contentEvent);
                    requestRefresh();
                    return;
                }
                if (event.pageId != null && event.pageId.startsWith("@extension:")) {
                    String id = event.pageId.substring("@extension:".length());
                    var mounted = extensions.get(id);
                    if (mounted != null && mounted.registration() == registry.getExtensionRegistration(id)) {
                        mounted.extension().handleEvent(mounted.context(), contentEvent);
                        requestRefresh();
                    }
                    return;
                }
                if (INVENTORY_PANELS.equals(event.pageId)) {
                    boolean utilityHover = "UtilityWheelHover".equals(event.contentAction)
                            || "UtilityWheelUnhover".equals(event.contentAction);
                    if (utilityHover) {
                        playerPanel.handleEvent(activeContext, new InventoryContentEvent(
                                "UtilityWheelHover".equals(event.contentAction)
                                        ? UtilitySlotSelector.HOVER : UtilitySlotSelector.UNHOVER,
                                event.payload, event.slotIndex));
                    }
                    inventoryPanels.handleEvent(activeContext, contentEvent);
                    if ("HoverSource".equals(event.contentAction) || "UnhoverSource".equals(event.contentAction)
                            || "UtilityWheelHover".equals(event.contentAction) || "UtilityWheelUnhover".equals(event.contentAction)) {
                        if (!inventoryPanels.hasPendingReleasedSources()) {
                            var commands = new UICommandBuilder();
                            if (utilityHover) playerPanel.refresh(activeContext, commands, "#PlayerPanelHost");
                            inventoryPanels.refreshDropAction(activeContext, commands);
                            sendPresentationUpdate(ref, store, commands);
                            return;
                        }
                    }
                    if ("DragSource".equals(event.contentAction) || "UtilityWheelDragSource".equals(event.contentAction)
                            || "CancelDrag".equals(event.contentAction)
                            || ("DragPress".equals(event.contentAction) && !contentEvent.rightMouseButton())) {
                        var commands = new UICommandBuilder();
                        inventoryPanels.refreshDropAction(activeContext, commands);
                        sendPresentationUpdate(ref, store, commands);
                        return;
                    }
                    requestRefresh();
                    return;
                }
                if (PLAYER_PANEL.equals(event.pageId)) {
                    if (UtilitySlotSelector.HOVER.equals(event.contentAction)) {
                        var index = UtilitySlotSelector.parseSlot(event.payload);
                        if (index != null && index >= 0) inventoryPanels.observeUtilityHover(index, false);
                    } else if (UtilitySlotSelector.HOVER_CENTER.equals(event.contentAction)
                            || UtilitySlotSelector.OPEN.equals(event.contentAction)) {
                        inventoryPanels.observeUtilityHover(null, true);
                    }
                    if (playerPanel.handleEvent(activeContext, contentEvent)) {
                        if (UtilitySlotSelector.isPresentationEvent(event.contentAction)) {
                            // Hover can occur while a stack is held. Do not rewrite any inventory Slots.
                            var commands = new UICommandBuilder();
                            playerPanel.refresh(activeContext, commands, "#PlayerPanelHost");
                            inventoryPanels.refreshDropAction(activeContext, commands);
                            sendPresentationUpdate(ref, store, commands);
                        } else requestRefresh();
                    }
                    return;
                }
                // A removed/replaced contribution cannot receive old UI events.
                if (activeContent != null && (hostedView != null || activeRegistration == registry.getPageRegistration(pageId))
                        && Objects.equals(pageId, event.pageId)) {
                    activeContent.handleEvent(activeContext, contentEvent);
                    requestRefresh();
                }
            }
            default -> { }
        }
    }

    /**
     * The game's legacy dispatcher drops Data while any presentation update awaits
     * acknowledgement. During presentation and stable slot updates inventory bindings use this page's session,
     * ownership and detached-source validation; dynamic extension bindings stay native.
     * Acknowledgements and dismissal never pass through this path.
     */
    public boolean tryHandleInventoryInput(Ref<EntityStore> ref, Store<EntityStore> store, String rawData) {
        if (rawData == null || !isActive(ref, store)) return false;
        Event event;
        var extraInfo = ExtraInfo.THREAD_LOCAL.get();
        try {
            event = Event.CODEC.decodeJson(new RawJsonReader(rawData.toCharArray()), extraInfo);
        } catch (IOException failure) {
            throw new RuntimeException(failure);
        }
        extraInfo.getValidationResults().logOrThrowValidatorExceptions(LOGGER);
        if (!"Content".equals(event.action)
                || !(INVENTORY_PANELS.equals(event.pageId) || PLAYER_PANEL.equals(event.pageId))) return false;
        handleDataEvent(ref, store, event);
        return true;
    }

    /** Native DropItemStack input is retargeted only while this Adventure page owns the UI. */
    public void dropHoveredItem(Ref<EntityStore> ref, Store<EntityStore> store, DropItemStack packet) {
        if (dismissed || !isActive(ref, store) || !remainInAdventure(ref, store)) return;
        String payload = packet != null ? (packet.inventorySectionId + ":" + packet.slotId) : "";
        inventoryPanels.handleEvent(activeContext, new InventoryContentEvent("DropHovered", payload, null));
        requestRefresh();
    }

    public void dropHoveredItem(Ref<EntityStore> ref, Store<EntityStore> store) {
        dropHoveredItem(ref, store, null);
    }

    private void sendPresentationUpdate(Ref<EntityStore> ref, Store<EntityStore> store, UICommandBuilder commands) {
        if (commands.getCommands().length == 0) return;
        store.getComponent(ref, Player.getComponentType()).getPageManager().updateLegacyCustomPage(
                new CustomPage(getClass().getName(), false, false, getLifetime(),
                        commands.getCommands(), new UIEventBuilder().getEvents()));
    }

    /** Prevents old rendered events from applying to a newer selection or content session. */
    public static boolean acceptsEvent(String mountedSession, String eventSession) {
        return mountedSession != null && mountedSession.equals(eventSession);
    }

    private boolean isActive(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!ref.isValid() || ref.getStore() != store) return false;
        var context = activeContext;
        if (context == null || context.ref() != ref || context.store() != store) return false;
        var player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getPageManager().getCustomPage() == this;
    }

    /** Called on the owning world thread, after checking this page's ownership. */
    private boolean remainInAdventure(Ref<EntityStore> ref, Store<EntityStore> store) {
        var player = store.getComponent(ref, Player.getComponentType());
        if (player != null && (hostedView != null || player.getGameMode() == GameMode.Adventure)) return true;
        if (isActive(ref, store)) player.getPageManager().setPage(ref, store, Page.None);
        return false;
    }

    /** A dismissed or old-world shell must be replaced rather than refocused. */
    public boolean canRefocus(Ref<EntityStore> ref, Store<EntityStore> store) {
        return !dismissed && isActive(ref, store);
    }

    public boolean isHostedView() { return hostedView != null; }

    private void requestRefresh() {
        if (dismissed) return;
        if (!refreshQueued.compareAndSet(false, true)) return;
        var ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            refreshQueued.set(false);
            return;
        }
        var store = ref.getStore();
        var retainedContext = activeContext;
        if (retainedContext != null && retainedContext.store() != store) {
            refreshQueued.set(false);
            closeForWorldRemoval(retainedContext.store().getExternalData().getWorld());
            return;
        }
        try { store.getExternalData().getWorld().execute(() -> {
            refreshQueued.set(false);
            if (dismissed || !isActive(ref, store) || !remainInAdventure(ref, store)) return;
            activeContext = createContext(ref, store);
            var commands = new UICommandBuilder();
            var events = new UIEventBuilder();
            try {
                render(commands, events, false);
            } catch (RuntimeException failure) {
                LOGGER.atWarning().withCause(failure).log("Failed to refresh custom inventory");
                var player = store.getComponent(ref, Player.getComponentType());
                if (player != null && isActive(ref, store)) player.getPageManager().setPage(ref, store, Page.None);
                else onDismiss(ref, store);
                return;
            }
            // Already on the world thread; avoid delayed updates leaking into a different page.
            var player = store.getComponent(ref, Player.getComponentType());
            if (player != null && isActive(ref, store)) {
                player.getPageManager().updateLegacyCustomPage(new CustomPage(getClass().getName(),
                        false, false, getLifetime(), commands.getCommands(), events.getEvents()));
            }
        }); } catch (RuntimeException worldStopped) {
            refreshQueued.set(false);
        }
    }

    /** Thread-safe reconciliation requested by registry registration/unregistration. */
    public void refreshRegistrations() { requestRefresh(); }

    /** Keep the native preview/grids mounted while health, equipment visibility and hotbar selection change. */
    private void startPanelRefresh() {
        if (panelRefresh != null) return;
        panelRefresh = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            if (dismissed || !panelRefreshQueued.compareAndSet(false, true)) return;
            var context = activeContext;
            if (context == null) { panelRefreshQueued.set(false); return; }
            try {
                context.store().getExternalData().getWorld().execute(() -> {
                    try {
                        if (dismissed || playerRef.getReference() != context.ref() || !isActive(context.ref(), context.store())
                                || !remainInAdventure(context.ref(), context.store())) return;
                        var commands = new UICommandBuilder();
                        playerPanel.refresh(context, commands, "#PlayerPanelHost");
                        inventoryPanels.refreshHotbar(context, commands, "#InventoryShell");
                        inventoryPanels.refreshUtility(context, commands, "#InventoryShell");
                        var tabs = readNativeTabState(context);
                        if (!tabs.equals(nativeTabState)) {
                            if (nativeTabState != null && (tabs.memoryCapacity() != nativeTabState.memoryCapacity()
                                    || tabs.backpackCapacity() != nativeTabState.backpackCapacity())) requestRefresh();
                            else { writeNativeBadges(commands, tabs); nativeTabState = tabs; }
                        }
                        if (activeContent instanceof CollectedMemoriesContent memories && memories.needsRefresh(context)) requestRefresh();
                        if (commands.getCommands().length == 0) return;
                        var player = context.store().getComponent(context.ref(), Player.getComponentType());
                        player.getPageManager().updateLegacyCustomPage(new CustomPage(getClass().getName(),
                                false, false, getLifetime(), commands.getCommands(), new UIEventBuilder().getEvents()));
                    } catch (RuntimeException failure) {
                        LOGGER.atWarning().withCause(failure).log("Failed to update inventory player panel");
                    } finally {
                        panelRefreshQueued.set(false);
                    }
                });
            } catch (RuntimeException failure) {
                panelRefreshQueued.set(false);
                LOGGER.atWarning().withCause(failure).log("Failed to queue inventory player panel update");
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void dismissContent() {
        var content = activeContent;
        activeContent = null;
        activeDefinition = null;
        activeRegistration = null;
        if (content != null) content.onDismiss(activeContext);
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (dismissed) return;
        var context = activeContext;
        if (context != null) {
            var world = context.store().getExternalData().getWorld();
            if (!world.isInThread()) {
                // PageManager may detach an old-world page while opening one in the new world.
                world.execute(() -> onDismiss(context.ref(), context.store()));
                return;
            }
        }
        dismissed = true;
        if (panelRefresh != null) { panelRefresh.cancel(false); panelRefresh = null; }
        try {
            try { dismissContent(); }
            finally { inventoryPanels.onDismiss(activeContext); }
        } finally {
            for (var extension : extensions.values()) closeExtension(extension.extension(), extension.context());
            extensions.clear();
            if (viewExtension != null && viewExtensionMounted) closeExtension(viewExtension, activeContext);
            mountedButtons.clear();
            mountedPages.clear();
            activeContext = null;
            dismissedCallback.accept(this);
        }
    }

    /** Queues teardown on the owning world thread. */
    public void closeForShutdown() {
        var ref = playerRef.getReference();
        var retainedContext = activeContext;
        if (retainedContext == null) return;
        var store = retainedContext.store();
        var contextRef = retainedContext.ref();
        runOnWorld(store.getExternalData().getWorld(), () -> {
            if (dismissed) return;
            if (ref != null && isActive(ref, store)) {
                store.getComponent(ref, Player.getComponentType()).getPageManager().setPage(ref, store, Page.None);
            } else onDismiss(contextRef, store);
        });
    }

    public boolean belongsTo(PlayerRef player) { return playerRef == player; }

    /** The old world owns listeners even after the player entity moves to another world. */
    public void closeForWorldRemoval(World world) {
        var retainedContext = activeContext;
        if (retainedContext == null || retainedContext.store().getExternalData().getWorld() != world) return;
        runOnWorld(world, () -> {
            if (!dismissed && activeContext != null && activeContext.store() == retainedContext.store())
                onDismiss(retainedContext.ref(), retainedContext.store());
        });
    }

    public static final class Event {
        public static final String SEARCH_QUERY = "@SearchQuery";
        // ItemGrid can explicitly send null source/quantity metadata (for example
        // when taking part of a stack). A non-PrimitiveCodec wrapper lets the
        // BuilderCodec preserve those nulls instead of rejecting the entire event.
        private static final Codec<Integer> OPTIONAL_INTEGER = new FunctionCodec<>(Codec.INTEGER, value -> value, value -> value);
        // Client versions may encode the mouse-button enum as a name or an integer.
        private static final Codec<String> MOUSE_BUTTON = new Codec<>() {
            @Override
            public String decode(org.bson.BsonValue value, ExtraInfo info) {
                if (value == null || value.isNull()) return null;
                if (value.isString()) return value.asString().getValue();
                if (value.isNumber()) return Integer.toString(value.asNumber().intValue());
                return null;
            }

            @Override
            public org.bson.BsonValue encode(String value, ExtraInfo info) {
                return value == null ? org.bson.BsonNull.VALUE : new org.bson.BsonString(value);
            }

            @Override
            public com.hypixel.hytale.codec.schema.config.Schema toSchema(com.hypixel.hytale.codec.schema.SchemaContext context) {
                return Codec.STRING.toSchema(context);
            }
        };
        // ItemGrid callbacks serialize UI mouse buttons: Left=1, Middle=2, Right=3.
        // 0 is also accepted as Left for 0-indexed compatibility.
        private static final Codec<String> UI_MOUSE_BUTTON = new FunctionCodec<>(MOUSE_BUTTON,
                value -> value == null ? null : switch (value) {
                    case "0", "1" -> "Left";
                    case "2" -> "Middle";
                    case "3" -> "Right";
                    case "4" -> "XButton1";
                    case "5" -> "XButton2";
                    default -> value;
                }, value -> value);
        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>("Target", Codec.STRING), (d, v) -> d.target = v, d -> d.target).add()
                .append(new KeyedCodec<>("PageId", Codec.STRING), (d, v) -> d.pageId = v, d -> d.pageId).add()
                .append(new KeyedCodec<>("SessionId", Codec.STRING), (d, v) -> d.sessionId = v, d -> d.sessionId).add()
                .append(new KeyedCodec<>("ContentAction", Codec.STRING), (d, v) -> d.contentAction = v, d -> d.contentAction).add()
                .append(new KeyedCodec<>("Payload", Codec.STRING), (d, v) -> d.payload = v, d -> d.payload).add()
                .append(new KeyedCodec<>(SEARCH_QUERY, Codec.STRING), (d, v) -> d.searchQuery = v, d -> d.searchQuery).add()
                .append(new KeyedCodec<>("@Text", Codec.STRING), (d, v) -> d.textValue = v, d -> d.textValue).add()
                .append(new KeyedCodec<>("@Color", Codec.STRING), (d, v) -> d.colorValue = v, d -> d.colorValue).add()
                .append(new KeyedCodec<>("@Choice", Codec.STRING), (d, v) -> d.choiceValue = v, d -> d.choiceValue).add()
                .append(new KeyedCodec<>("@Checked", Codec.BOOLEAN), (d, v) -> d.checkedValue = v, d -> d.checkedValue).add()
                .append(new KeyedCodec<>("ShiftHeld", Codec.BOOLEAN), (d, v) -> d.shiftHeld = v, d -> d.shiftHeld).add()
                .append(new KeyedCodec<>("SlotIndex", OPTIONAL_INTEGER), (d, v) -> d.slotIndex = v, d -> d.slotIndex).add()
                .append(new KeyedCodec<>("PressedMouseButton", UI_MOUSE_BUTTON), (d, v) -> d.pressedMouseButton = v, d -> d.pressedMouseButton).add()
                .append(new KeyedCodec<>("DragPressedMouseButton", UI_MOUSE_BUTTON), (d, v) -> d.dragPressedMouseButton = v, d -> d.dragPressedMouseButton).add()
                .append(new KeyedCodec<>("MouseButton", MOUSE_BUTTON), (d, v) -> d.mouseButton = v, d -> d.mouseButton).add()
                .append(new KeyedCodec<>("ClickMouseButton", UI_MOUSE_BUTTON), (d, v) -> d.clickMouseButton = v, d -> d.clickMouseButton).add()
                .append(new KeyedCodec<>("Button", MOUSE_BUTTON), (d, v) -> d.button = v, d -> d.button).add()
                .append(new KeyedCodec<>("SourceInventorySectionId", OPTIONAL_INTEGER), (d, v) -> d.sourceInventorySectionId = v, d -> d.sourceInventorySectionId).add()
                .append(new KeyedCodec<>("SourceSlotId", OPTIONAL_INTEGER), (d, v) -> d.sourceSlotId = v, d -> d.sourceSlotId).add()
                .append(new KeyedCodec<>("DragSourceInventorySectionId", OPTIONAL_INTEGER), (d, v) -> d.dragSourceInventorySectionId = v, d -> d.dragSourceInventorySectionId).add()
                .append(new KeyedCodec<>("DragSourceSlotId", OPTIONAL_INTEGER), (d, v) -> d.dragSourceSlotId = v, d -> d.dragSourceSlotId).add()
                .append(new KeyedCodec<>("ItemStackId", Codec.STRING), (d, v) -> d.itemStackId = v, d -> d.itemStackId).add()
                .append(new KeyedCodec<>("ItemStackQuantity", OPTIONAL_INTEGER), (d, v) -> d.itemStackQuantity = v, d -> d.itemStackQuantity).add()
                .append(new KeyedCodec<>("DragItemStackId", Codec.STRING), (d, v) -> d.dragItemStackId = v, d -> d.dragItemStackId).add()
                .append(new KeyedCodec<>("DragItemStackQuantity", OPTIONAL_INTEGER), (d, v) -> d.dragItemStackQuantity = v, d -> d.dragItemStackQuantity).add()
                .build();
        public String action;
        public String target;
        public String pageId;
        public String sessionId;
        public String contentAction;
        public String payload;
        public String searchQuery;
        public String textValue;
        public String colorValue;
        public String choiceValue;
        public Boolean checkedValue;
        public Boolean shiftHeld;
        public Integer slotIndex;
        public String pressedMouseButton;
        public String dragPressedMouseButton;
        public String mouseButton;
        public String clickMouseButton;
        public String button;
        public Integer sourceInventorySectionId;
        public Integer sourceSlotId;
        public Integer dragSourceInventorySectionId;
        public Integer dragSourceSlotId;
        public String itemStackId;
        public Integer itemStackQuantity;
        public String dragItemStackId;
        public Integer dragItemStackQuantity;

        public String resolvedMouseButton() {
            // ClickMouseButton belongs to this callback; drag fields belong to the pickup.
            if (clickMouseButton != null) return clickMouseButton;
            if (mouseButton != null) return mouseButton;
            if (button != null) return button;
            if ("DragSource".equals(contentAction) || "UtilityWheelDragSource".equals(contentAction)) {
                if (pressedMouseButton != null) return pressedMouseButton;
                return dragPressedMouseButton;
            }
            // A Dropped callback can omit the current button. Its pickup button cannot
            // turn a completed right placement into a second, whole-stack placement.
            return null;
        }

        public InventoryContentEvent headerContentEvent() {
            return new InventoryContentEvent(contentAction,
                    "FilterRecipes".equals(contentAction) && searchQuery != null ? searchQuery : payload,
                    slotIndex, dragData());
        }

        public Map<String, String> formValues() {
            var values = new HashMap<String, String>();
            if (textValue != null) values.put("@Text", textValue);
            if (colorValue != null) values.put("@Color", colorValue);
            if (choiceValue != null) values.put("@Choice", choiceValue);
            if (checkedValue != null) values.put("@Checked", checkedValue.toString());
            return values;
        }

        public InventoryDragData dragData() {
            return new InventoryDragData(sourceInventorySectionId, sourceSlotId,
                    dragSourceInventorySectionId, dragSourceSlotId, itemStackId, itemStackQuantity,
                    dragItemStackId, dragItemStackQuantity, dragPressedMouseButton);
        }
    }
}
