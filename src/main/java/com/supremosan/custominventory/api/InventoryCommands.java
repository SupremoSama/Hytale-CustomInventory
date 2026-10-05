package com.supremosan.custominventory.api;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.Objects;

/** Native UI commands scoped beneath a host supplied to InventoryContent.build/refresh. */
public class InventoryCommands extends UICommandBuilder {
    private final UICommandBuilder target;
    private final String host;
    public InventoryCommands(String host) { this(new UICommandBuilder(), host); }
    public InventoryCommands(UICommandBuilder target, String host) {
        this.target = Objects.requireNonNull(target);
        if (host == null || !host.startsWith("#") || host.contains(",")) throw new IllegalArgumentException("Use a provided #host selector");
        this.host = host;
    }
    protected String path(String local) {
        if (local == null || !local.startsWith("#") || local.contains(",")) throw new IllegalArgumentException("Expected a local #id selector");
        return host + " " + local;
    }
    @Override public UICommandBuilder append(String document) { target.append(host, document); return this; }
    @Override public UICommandBuilder append(String local, String document) { target.append(path(local), document); return this; }
    @Override public UICommandBuilder appendInline(String local, String document) { target.appendInline(path(local), document); return this; }
    @Override public UICommandBuilder clear(String local) { target.clear(path(local)); return this; }
    @Override public UICommandBuilder remove(String local) { target.remove(path(local)); return this; }
    @Override public UICommandBuilder set(String local, String value) { target.set(path(local), value); return this; }
    @Override public UICommandBuilder set(String local, Message value) { target.set(path(local), value); return this; }
    @Override public UICommandBuilder set(String local, boolean value) { target.set(path(local), value); return this; }
    @Override public UICommandBuilder set(String local, int value) { target.set(path(local), value); return this; }
    @Override public UICommandBuilder set(String local, float value) { target.set(path(local), value); return this; }
    @Override public UICommandBuilder set(String local, double value) { target.set(path(local), value); return this; }
    @Override public <T> UICommandBuilder set(String local, Value<T> value) { target.set(path(local), value); return this; }
    @Override public <T> UICommandBuilder set(String local, T[] value) { target.set(path(local), value); return this; }
    @Override public <T> UICommandBuilder set(String local, List<T> value) { target.set(path(local), value); return this; }
    @Override public UICommandBuilder setObject(String local, Object value) { target.setObject(path(local), value); return this; }
    @Override public UICommandBuilder setNull(String local) { target.setNull(path(local)); return this; }
    @Override public UICommandBuilder insertBefore(String local, String document) { target.insertBefore(path(local), document); return this; }
    @Override public UICommandBuilder insertBeforeInline(String local, String document) { target.insertBeforeInline(path(local), document); return this; }
    @Override public CustomUICommand[] getCommands() { return target.getCommands(); }
}
