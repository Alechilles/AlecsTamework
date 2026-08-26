package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/** Tamework-owned fixed-key host for one custom command target HUD session. */
public final class CommandTargetHudHost extends CustomUIHud {
    public static final String HUD_KEY = TameworkCommandTargetHud.HUD_KEY;

    private final CommandHudOpenContext context;
    private final CommandTargetHudController controller;
    private final CommandTargetHudView initialView;
    private final FailureHandler failureHandler;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean built = new AtomicBoolean();

    /** Creates a host with no failure callback. */
    public CommandTargetHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandTargetHudController controller,
            @Nonnull CommandTargetHudView initialView
    ) {
        this(playerRef, context, controller, initialView, (phase, failure) -> { });
    }

    /** Creates a host that reports controller failures to its coordinator. */
    public CommandTargetHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandTargetHudController controller,
            @Nonnull CommandTargetHudView initialView,
            @Nonnull FailureHandler failureHandler
    ) {
        super(Objects.requireNonNull(playerRef, "playerRef"), HUD_KEY);
        this.context = Objects.requireNonNull(context, "context");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.initialView = Objects.requireNonNull(initialView, "initialView");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        if (!open.get()) {
            return;
        }
        try {
            controller.buildInitial(context, initialView, commandBuilder);
            built.set(true);
        } catch (RuntimeException | LinkageError failure) {
            fail("initial build", failure);
        }
    }

    /** Applies one complete detached update with partial HUD semantics. */
    public boolean applyUpdate(@Nonnull CommandTargetHudUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!open.get() || !built.get()) {
            return false;
        }
        try {
            UICommandBuilder commandBuilder = new UICommandBuilder();
            controller.update(update, commandBuilder);
            update(false, commandBuilder);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            fail("update", failure);
            return false;
        }
    }

    /** Clears the fixed HUD without targeting controls from a removed tree. */
    public void hideNow() {
        if (!open.get()) {
            return;
        }
        update(true, new UICommandBuilder());
    }

    /** Returns whether this host can still receive updates. */
    public boolean isOpen() {
        return open.get();
    }

    /** Invalidates this host; the coordinator owns controller cleanup. */
    public void close() {
        if (open.compareAndSet(true, false)) {
            failureHandler.closed();
        }
    }

    private void fail(@Nonnull String phase, @Nonnull Throwable failure) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            failureHandler.failed(phase, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // A renderer failure must not escape the Hytale HUD callback.
        }
    }

    /** Receives host lifecycle failures without exposing live Hytale state. */
    public interface FailureHandler {
        void failed(@Nonnull String phase, @Nonnull Throwable failure);

        default void closed() {
        }
    }
}
