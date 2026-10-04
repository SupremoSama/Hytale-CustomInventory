package com.supremosan.custominventory.api;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Registration changes appear on next open or Refresh. No dependency on any inventory extension. */
public final class InventoryRegistry {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_.-]*:[a-z][a-z0-9_./-]*");
    private final ConcurrentMap<String, Entry<InventoryPageDefinition>> pages = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Entry<InventoryButtonDefinition>> buttons = new ConcurrentHashMap<>();

    /** Identity token for an exact registration, including re-registration of the same definition. */
    public static final class Entry<T> {
        private final T definition;
        private Entry(T definition) { this.definition = definition; }
        public T definition() { return definition; }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        /** Idempotent; removes only the exact registration represented by this handle. */
        @Override void close();
    }

    public Registration registerInventoryPage(InventoryPageDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        var entry = new Entry<>(definition);
        if (pages.putIfAbsent(definition.id(), entry) != null)
            throw new IllegalArgumentException("Inventory page already registered: " + definition.id());
        var closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) pages.remove(definition.id(), entry); };
    }

    public Registration registerInventoryButton(InventoryButtonDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        var entry = new Entry<>(definition);
        if (buttons.putIfAbsent(definition.id(), entry) != null)
            throw new IllegalArgumentException("Inventory button already registered: " + definition.id());
        var closed = new AtomicBoolean();
        return () -> { if (closed.compareAndSet(false, true)) buttons.remove(definition.id(), entry); };
    }

    /** Immutable deterministic snapshot. */
    public List<InventoryPageDefinition> pagesSnapshot() {
        return pages.values().stream().map(entry -> entry.definition).sorted(Comparator.comparingInt(InventoryPageDefinition::order)
                .thenComparing(InventoryPageDefinition::id)).toList();
    }

    public List<InventoryButtonDefinition> buttonsSnapshot() {
        return buttons.values().stream().map(entry -> entry.definition).sorted(Comparator.comparingInt(InventoryButtonDefinition::order)
                .thenComparing(InventoryButtonDefinition::id)).toList();
    }

    public InventoryPageDefinition getPage(String id) {
        var entry = id == null ? null : pages.get(id);
        return entry == null ? null : entry.definition;
    }

    public Entry<InventoryPageDefinition> getPageRegistration(String id) { return id == null ? null : pages.get(id); }
    public Entry<InventoryButtonDefinition> getButtonRegistration(String id) { return id == null ? null : buttons.get(id); }
    public InventoryButtonDefinition getButton(String id) {
        var entry = id == null ? null : buttons.get(id);
        return entry == null ? null : entry.definition;
    }

    public void clear() {
        pages.clear();
        buttons.clear();
    }

    static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches())
            throw new IllegalArgumentException("Use a namespaced identifier, e.g. example:appearance");
    }
}
