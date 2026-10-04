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

    public InventoryEventBindings(UIEventBuilder events, String hostSelector, String pageId, String sessionId) {
        this.events = Objects.requireNonNull(events);
        this.hostSelector = Objects.requireNonNull(hostSelector);
        this.pageId = Objects.requireNonNull(pageId);
        this.sessionId = Objects.requireNonNull(sessionId);
    }

    public void bind(CustomUIEventBindingType type, String relativeSelector,
                     String action, String payload, boolean locksInterface) {
        events.addEventBinding(type, selector(relativeSelector), data(action, payload), locksInterface);
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
