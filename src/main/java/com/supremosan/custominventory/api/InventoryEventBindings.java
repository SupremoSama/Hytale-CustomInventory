package com.supremosan.custominventory.api;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import java.util.Objects;

/** Scopes selectors and event envelopes to the active content instance. */
public final class InventoryEventBindings {
    private final UIEventBuilder events;
    private final String hostSelector;
    private final String pageId;
    private final String sessionId;
    private int bindingCount;

    public int bindingCount() { return bindingCount; }

    public InventoryEventBindings(UIEventBuilder events, String hostSelector, String pageId, String sessionId) {
        this.events = Objects.requireNonNull(events);
        this.hostSelector = Objects.requireNonNull(hostSelector);
        this.pageId = Objects.requireNonNull(pageId);
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    public void bind(CustomUIEventBindingType type, String relativeSelector,
                     String action, String payload, boolean locksInterface) {
        events.addEventBinding(type, selector(relativeSelector), data(action, payload), locksInterface);
        bindingCount++;
    }

    /** Adapts a contribution's local Action/Target envelope and generic form values. */
    public void addEventBinding(CustomUIEventBindingType type, String relativeSelector, EventData local) {
        addEventBinding(type, relativeSelector, local, true);
    }

    public void addEventBinding(CustomUIEventBindingType type, String relativeSelector, EventData local, boolean locksInterface) {
        var scoped = data(Objects.requireNonNull(local.events().get("Action"), "Action"), local.events().get("Target"));
        for (var entry : local.events().entrySet()) {
            if (entry.getKey().equals("Action") || entry.getKey().equals("Target")) continue;
            if (!java.util.Set.of("@Text", "@Color", "@Choice", "@Checked").contains(entry.getKey()))
                throw new IllegalArgumentException("Unsupported bound form value: " + entry.getKey());
            String value = entry.getValue();
            if (value.startsWith("#") && !hostSelector.isBlank()) value = hostSelector.strip() + " " + value;
            scoped.append(entry.getKey(), value);
        }
        events.addEventBinding(type, selector(relativeSelector), scoped, locksInterface);
        bindingCount++;
    }

    public String selector(String relativeSelector) {
        if (relativeSelector == null || !relativeSelector.startsWith("#"))
            throw new IllegalArgumentException("Content selectors must start with a local #id");
        return hostSelector.isBlank() ? relativeSelector : hostSelector.strip() + " " + relativeSelector;
    }

    /** Useful for codec-level verification; do not replace core routing fields. */
    public EventData data(String action, String payload) {
        return new EventData().append("Action", "Content").append("PageId", pageId)
                .append("SessionId", sessionId).append("ContentAction", Objects.requireNonNull(action))
                .append("Payload", payload == null ? "" : payload);
    }
}
