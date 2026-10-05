package com.supremosan.custominventory.api;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.Objects;
import java.util.function.Consumer;
import com.hypixel.hytale.server.core.Message;

/**
 * Callback-scoped mutations. Core grids cannot be removed/rebound or assigned arbitrary properties.
 * A registered extension receives a contribution token: every extension host then
 * resolves to that registration's own child, alongside the framework's native
 * content. Clearing or removing that child cannot destroy another contribution
 * or the native inventory. The token-free editor is reserved for an owned view,
 * which uses the direct content hosts. Mutations of supported core elements remain
 * shared and are applied in the framework's deterministic extension order.
 */
public final class InventoryUiEditor implements AutoCloseable {
    private static final InventoryElementId[] CONTRIBUTION_HOSTS = {
            InventoryElementId.CONTENT_HOST, InventoryElementId.HEADER_ACTIONS,
            InventoryElementId.NAVIGATION, InventoryElementId.EXTENSION_BUTTONS,
            InventoryElementId.AUXILIARY_HOST
    };
    private final UICommandBuilder commands;
    private final InventoryEventBindings bindings;
    private final String contributionToken;
    private boolean valid = true;

    /** Direct hosts for an inventory view that owns its content. */
    public InventoryUiEditor(UICommandBuilder commands, InventoryEventBindings bindings) {
        this(commands, bindings, null);
    }

    /**
     * Isolated hosts for one mounted registration. Tokens contain 1–80 letters,
     * digits or underscores; the framework derives them from registration identity.
     * Use {@link #mountContribution} before delivering the editor to callbacks.
     */
    public InventoryUiEditor(UICommandBuilder commands, InventoryEventBindings bindings, String contributionToken) {
        this.commands = Objects.requireNonNull(commands);
        this.bindings = Objects.requireNonNull(bindings);
        this.contributionToken = contributionToken == null ? null : token(contributionToken);
    }
    public String selector(InventoryElementId id) {
        check();
        Objects.requireNonNull(id);
        return contributionToken != null && id.extensionHost()
                ? contributionSelector(id, contributionToken) : id.selector();
    }

    /**
     * Mounts one registration's five isolated hosts. The shell must have already
     * mounted its sibling extension anchors. Call once per registration and UI
     * instance, before creation/open callbacks; rebuilding the shell requires a
     * fresh mount. This method does not clear native controls or existing siblings.
     */
    public static void mountContribution(UICommandBuilder commands, String contributionToken) {
        Objects.requireNonNull(commands);
        String token = token(contributionToken);
        for (var host : CONTRIBUTION_HOSTS) {
            String layout = host == InventoryElementId.CONTENT_HOST ? " LayoutMode: Top;"
                    : host == InventoryElementId.HEADER_ACTIONS ? " LayoutMode: Left;" : "";
            commands.appendInline(contributionParent(host),
                    "Group #CIContribution" + token + " { Anchor: (Full: 0);" + layout + " }");
        }
    }

    /**
     * Removes only this registration's mounted children, including their native
     * event bindings. Call once when unregistering or disposing a contribution;
     * callbacks and server state are cleaned up by the framework's lifecycle.
     */
    public static void removeContribution(UICommandBuilder commands, String contributionToken) {
        Objects.requireNonNull(commands);
        String token = token(contributionToken);
        for (var host : CONTRIBUTION_HOSTS) commands.remove(contributionSelector(host, token));
    }

    private static String contributionSelector(InventoryElementId host, String token) {
        return contributionParent(host) + " #CIContribution" + token;
    }

    private static String contributionParent(InventoryElementId host) {
        return switch (host) {
            case CONTENT_HOST -> "#ContentExtensions";
            case HEADER_ACTIONS -> "#HeaderExtensions";
            case NAVIGATION -> "#NavigationExtensions";
            case EXTENSION_BUTTONS -> "#ButtonExtensions";
            case AUXILIARY_HOST -> "#AuxiliaryExtensions";
            default -> throw new IllegalArgumentException("Element has no contribution host: " + host);
        };
    }

    private static String token(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_]{1,80}"))
            throw new IllegalArgumentException("Contribution tokens must contain 1–80 letters, digits or underscores");
        return token;
    }
    public String selector(InventoryElementId id, String relativeSelector) {
        host(id);
        if (relativeSelector == null || !relativeSelector.startsWith("#") || relativeSelector.contains(","))
            throw new IllegalArgumentException("Use a local #id inside an extension host");
        return selector(id) + " " + relativeSelector;
    }
    public void visible(InventoryElementId id, boolean visible) { commands.set(selector(id) + ".Visible", visible); }
    public void disabled(InventoryElementId id, boolean disabled) {
        if (id != InventoryElementId.SORT_BUTTON && id != InventoryElementId.CLOSE_BUTTON && id != InventoryElementId.BACK_BUTTON
                && id != InventoryElementId.INVENTORY_MENU && id != InventoryElementId.MAP_MENU && id != InventoryElementId.CREATIVE_MENU)
            throw new IllegalArgumentException("Element is not a supported button: " + id);
        commands.set(selector(id) + ".Disabled", disabled);
    }
    public void text(InventoryElementId id, String text) {
        if (id != InventoryElementId.TITLE) throw new IllegalArgumentException("Element has no supported Text property: " + id);
        commands.set(selector(id) + ".Text", Objects.requireNonNull(text));
    }
    public void anchor(InventoryElementId id, Anchor anchor) { commands.setObject(selector(id) + ".Anchor", Objects.requireNonNull(anchor)); }
    public void padding(InventoryElementId id, Value<?> value) { commands.set(selector(id) + ".Padding", Objects.requireNonNull(value)); }
    /** Icon for a contribution's own element; never mutates native ItemGrid drag metadata. */
    public void icon(InventoryElementId host, String localId, String texturePath) {
        commands.setObject(selector(host, localId) + ".Background", new PatchStyle(Value.of(Objects.requireNonNull(texturePath))));
    }
    public void append(InventoryElementId id, String document) { host(id); commands.append(selector(id), Objects.requireNonNull(document)); }
    public void clear(InventoryElementId id) { host(id); commands.clear(selector(id)); }
    public void remove(InventoryElementId host, String localId) { commands.remove(selector(host, localId)); }
    /** Native builder restricted to a contribution's descendants; invalid after this callback. */
    public void edit(InventoryElementId host, Consumer<UICommandBuilder> operation) {
        host(host);
        operation.accept(new ScopedCommands(host));
    }
    public void bind(CustomUIEventBindingType type, InventoryElementId id, String localId, String action, String payload, boolean locksInterface) {
        bindings.bind(type, selector(id, localId), action, payload, locksInterface);
    }
    private void host(InventoryElementId id) {
        check();
        if (!Objects.requireNonNull(id).extensionHost()) throw new IllegalArgumentException("Element is framework-owned: " + id);
    }
    private void check() { if (!valid) throw new IllegalStateException("UI editor is only valid during its lifecycle callback"); }
    @Override public void close() { valid = false; }

    private final class ScopedCommands extends UICommandBuilder {
        private final InventoryElementId host;
        ScopedCommands(InventoryElementId host) { this.host = host; }
        private String path(String selector) { return InventoryUiEditor.this.selector(host, selector); }
        @Override public UICommandBuilder append(String document) { check(); commands.append(InventoryUiEditor.this.selector(host), document); return this; }
        @Override public UICommandBuilder append(String selector, String document) { commands.append(path(selector), document); return this; }
        @Override public UICommandBuilder appendInline(String selector, String document) { commands.appendInline(path(selector), document); return this; }
        @Override public UICommandBuilder clear(String selector) { commands.clear(path(selector)); return this; }
        @Override public UICommandBuilder remove(String selector) { commands.remove(path(selector)); return this; }
        @Override public UICommandBuilder set(String selector, String value) { commands.set(path(selector), value); return this; }
        @Override public UICommandBuilder set(String selector, Message value) { commands.set(path(selector), value); return this; }
        @Override public UICommandBuilder set(String selector, boolean value) { commands.set(path(selector), value); return this; }
        @Override public UICommandBuilder set(String selector, int value) { commands.set(path(selector), value); return this; }
        @Override public UICommandBuilder set(String selector, float value) { commands.set(path(selector), value); return this; }
        @Override public UICommandBuilder set(String selector, double value) { commands.set(path(selector), value); return this; }
        @Override public <T> UICommandBuilder set(String selector, Value<T> value) { commands.set(path(selector), value); return this; }
        @Override public <T> UICommandBuilder set(String selector, T[] value) { commands.set(path(selector), value); return this; }
        @Override public <T> UICommandBuilder set(String selector, java.util.List<T> value) { commands.set(path(selector), value); return this; }
        @Override public UICommandBuilder setObject(String selector, Object value) { commands.setObject(path(selector), value); return this; }
        @Override public UICommandBuilder setNull(String selector) { commands.setNull(path(selector)); return this; }
        @Override public UICommandBuilder insertBefore(String selector, String document) { commands.insertBefore(path(selector), document); return this; }
        @Override public UICommandBuilder insertBeforeInline(String selector, String document) { commands.insertBeforeInline(path(selector), document); return this; }
    }
}
