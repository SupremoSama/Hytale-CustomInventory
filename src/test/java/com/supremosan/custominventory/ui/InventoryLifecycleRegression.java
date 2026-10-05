package com.supremosan.custominventory.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.supremosan.custominventory.api.*;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the shell's lifecycle dispatch without starting a world or rendering client UI. */
public final class InventoryLifecycleRegression {
    private static int assertions;

    public static int verify() {
        assertions = 0;
        var registry = new InventoryRegistry();
        var sessions = new ArrayList<Observer>();
        var definition = new InventoryUiExtensionDefinition("tests:lifecycle", 0, ignored -> {
            var observer = new Observer();
            sessions.add(observer);
            return observer;
        });
        var firstRegistration = registry.registerUiExtension(definition);
        var dismissed = new AtomicInteger();
        var firstPage = fixturePage(registry, ignored -> dismissed.incrementAndGet(), null, null);
        var secondPage = fixturePage(registry, ignored -> dismissed.incrementAndGet(), null, null);
        renderExtensions(firstPage, true);
        renderExtensions(secondPage, true);
        check(sessions.size() == 2 && sessions.get(0) != sessions.get(1), "two inventory sessions receive independent extensions");
        var first = sessions.get(0);
        var second = sessions.get(1);
        check(first.created == 1 && first.opened == 1 && first.updated == 1, "initial lifecycle creates/updates/opens exactly once");
        try {
            first.context.update(new UICommandBuilder().set("#ContentExtensions #CIContributionOther #Name.Text", "Cross-mod edit"));
            throw new AssertionError("expected contribution ownership rejection");
        } catch (IllegalArgumentException expected) {
            check(true, "registered extension cannot update another contribution's elements");
        }
        renderExtensions(firstPage, false);
        check(first.created == 1 && first.opened == 1 && first.updated == 2, "refresh updates existing extension without duplicate opening");
        check(second.updated == 1, "refresh does not touch another inventory session");
        firstRegistration.close();
        check(!first.context.isActive(), "unregistered extension context becomes inactive immediately");
        first.context.update(new UICommandBuilder().set("#StorageGrid.Text", "Stale edit"));
        check(true, "unregistered context silently discards delayed updates");
        renderExtensions(firstPage, false);
        check(first.closed == 1, "unregistered extension closes on next refresh");
        var replacement = registry.registerUiExtension(definition);
        renderExtensions(firstPage, false);
        check(sessions.size() == 3, "re-register receives a fresh session instance");
        check(sessions.get(2).created == 1 && sessions.get(2).opened == 1, "replacement starts its own lifecycle");
        dismissWithoutWorld(firstPage);
        dismissWithoutWorld(firstPage);
        check(first.closed == 1 && sessions.get(2).closed == 1, "dismiss does not close prior registrations twice");
        check(second.closed == 0, "closing one page preserves the other session");
        dismissWithoutWorld(secondPage);
        check(second.closed == 1 && dismissed.get() == 2, "each independent page disposes once");
        replacement.close();

        var failedView = new Observer() {
            @Override public void onCreated(InventoryContext context, InventoryUiEditor editor) {
                super.onCreated(context, editor);
                throw new IllegalStateException("simulated first-render failure");
            }
        };
        var view = new InventoryPageDefinition("tests:failed-view", "Failed view", 0, ignored -> null);
        var failedPage = fixturePage(registry, ignored -> {}, view, failedView);
        try {
            renderExtensions(failedPage, true);
            throw new AssertionError("expected first-render failure");
        } catch (IllegalStateException expected) {
            check(failedView.created == 1 && failedView.opened == 0, "failure occurs before opening");
        }
        dismissWithoutWorld(failedPage);
        dismissWithoutWorld(failedPage);
        check(failedView.closed == 1, "failed initial view still releases its extension once");
        return assertions;
    }

    private static InventoryShellPage fixturePage(InventoryRegistry registry, Consumer<InventoryShellPage> dismissed,
                                                   InventoryPageDefinition view, InventoryUiExtension extension) {
        // A real empty ECS store provides context identities without a server World.
        // Dispatch observers never read components or submit inventory operations.
        var components = new ComponentRegistry<EntityStore>();
        var store = components.addStore(null, null);
        var player = new PlayerRef(components.newHolder(), UUID.randomUUID(), "Regression", "en-US", null, null);
        var page = new InventoryShellPage(player, registry, dismissed, view, extension);
        setContext(page, new InventoryContext(new Ref<>(store), store, player, () -> {}, "tests:lifecycle",
                () -> {}, () -> false, ignored -> {}));
        return page;
    }

    private static void dismissWithoutWorld(InventoryShellPage page) {
        // World-thread teardown is a runtime concern; this fixture verifies lifecycle dispatch.
        setContext(page, null);
        page.onDismiss(null, null);
    }

    private static void setContext(InventoryShellPage page, InventoryContext context) {
        try {
            var field = InventoryShellPage.class.getDeclaredField("activeContext");
            field.setAccessible(true);
            field.set(page, context);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void renderExtensions(InventoryShellPage page, boolean initial) {
        try {
            var dispatch = InventoryShellPage.class.getDeclaredMethod("renderExtensions", UICommandBuilder.class, UIEventBuilder.class, boolean.class);
            dispatch.setAccessible(true);
            dispatch.invoke(page, new UICommandBuilder(), new UIEventBuilder(), initial);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static class Observer implements InventoryUiExtension {
        int created, opened, updated, closed;
        InventoryContext context;
        @Override public void onCreated(InventoryContext context, InventoryUiEditor editor) { this.context = context; created++; }
        @Override public void onOpened(InventoryContext context) { opened++; }
        @Override public void onUpdate(InventoryContext context, InventoryUiEditor editor) { updated++; }
        @Override public void onClosed(InventoryContext context) { closed++; }
    }
}
