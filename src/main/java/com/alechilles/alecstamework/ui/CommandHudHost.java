package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/** Shared packet gating and failure isolation for command HUD sessions. */
abstract class CommandHudHost<U> extends CustomUIHud {
    private final BiConsumer<String, Throwable> failed;
    private final Runnable closed;
    private final Predicate<Runnable> updateGate;
    private final BiPredicate<Runnable, Runnable> initialBuildGate;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean built = new AtomicBoolean();

    CommandHudHost(PlayerRef playerRef, String key, int zOrder,
                   BiConsumer<String, Throwable> failed, Runnable closed,
                   Predicate<Runnable> updateGate,
                   BiPredicate<Runnable, Runnable> initialBuildGate) {
        super(Objects.requireNonNull(playerRef, "playerRef"), key, zOrder);
        this.failed = failed;
        this.closed = closed;
        this.updateGate = updateGate;
        this.initialBuildGate = initialBuildGate;
    }

    static boolean runUpdate(Runnable update) {
        update.run();
        return true;
    }

    static boolean buildAndUpdate(Runnable build, Runnable initialUpdate) {
        build.run();
        return runUpdate(initialUpdate);
    }

    protected abstract void renderInitial(UICommandBuilder commandBuilder);

    protected abstract void renderUpdate(U update, UICommandBuilder commandBuilder);

    @Override
    public void show() {
        if (!open.get()) return;
        UICommandBuilder commandBuilder = new UICommandBuilder();
        try {
            boolean delivered = initialBuildGate.test(
                    () -> buildInitial(commandBuilder),
                    () -> {
                        if (open.get() && built.get()) update(true, commandBuilder);
                    });
            if (!delivered && open.get()) {
                fail("initial build", new IllegalStateException(
                        "HUD composition is no longer active"));
            }
        } catch (RuntimeException | LinkageError failure) {
            fail("initial build", failure);
        }
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        try {
            buildInitial(commandBuilder);
        } catch (RuntimeException | LinkageError failure) {
            fail("initial build", failure);
        }
    }

    private void buildInitial(@Nonnull UICommandBuilder commandBuilder) {
        if (!open.get()) return;
        renderInitial(commandBuilder);
        built.set(true);
    }

    /** Applies one complete detached update with partial HUD semantics. */
    protected boolean applyDetachedUpdate(@Nonnull U update) {
        Objects.requireNonNull(update, "update");
        if (!open.get() || !built.get()) return false;
        try {
            UICommandBuilder commandBuilder = new UICommandBuilder();
            renderUpdate(update, commandBuilder);
            if (!open.get()) return false;
            return updateGate.test(() -> {
                if (open.get()) update(false, commandBuilder);
            });
        } catch (RuntimeException | LinkageError failure) {
            fail("update", failure);
            return false;
        }
    }

    /** Clears the fixed HUD without targeting controls from a removed tree. */
    public void hideNow() {
        if (!open.get()) return;
        update(true, new UICommandBuilder());
    }

    /** Returns whether this host can still receive updates. */
    public boolean isOpen() {
        return open.get();
    }

    /** Invalidates this host; the coordinator owns controller cleanup. */
    public void close() {
        if (open.compareAndSet(true, false)) closed.run();
    }

    private void fail(@Nonnull String phase, @Nonnull Throwable failure) {
        if (!open.compareAndSet(true, false)) return;
        try {
            failed.accept(phase, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // A renderer failure must not escape the Hytale HUD callback.
        }
    }
}
