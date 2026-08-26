package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/** Tamework-owned fixed-key host for one custom command hotswap HUD session. */
public final class CommandHotswapHudHost extends CustomUIHud {
    public static final String HUD_KEY = TameworkCommandHotswapHud.HUD_KEY;
    public static final int HUD_Z_ORDER = 1;

    private final CommandHudOpenContext context;
    private final CommandHotswapHudController controller;
    private final CommandHotswapHudView initialView;
    private final FailureHandler failureHandler;
    private final UpdateGate updateGate;
    private final InitialBuildGate initialBuildGate;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean built = new AtomicBoolean();

    /** Creates a host with no failure callback. */
    public CommandHotswapHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHotswapHudController controller,
            @Nonnull CommandHotswapHudView initialView
    ) {
        this(playerRef, context, controller, initialView, (phase, failure) -> { });
    }

    /** Creates a host that reports controller failures to its coordinator. */
    public CommandHotswapHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHotswapHudController controller,
            @Nonnull CommandHotswapHudView initialView,
            @Nonnull FailureHandler failureHandler
    ) {
        this(playerRef, context, controller, initialView, failureHandler,
                action -> {
                    action.run();
                    return true;
                }, (build, initialUpdate) -> {
                    build.run();
                    initialUpdate.run();
                    return true;
                });
    }

    /** Creates a host with a lifecycle gate for the final client update. */
    public CommandHotswapHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHotswapHudController controller,
            @Nonnull CommandHotswapHudView initialView,
            @Nonnull FailureHandler failureHandler,
            @Nonnull UpdateGate updateGate
    ) {
        this(playerRef, context, controller, initialView, failureHandler, updateGate,
                (build, initialUpdate) -> {
                    build.run();
                    initialUpdate.run();
                    return true;
                });
    }

    /** Creates a host with lifecycle gates for initial and incremental packets. */
    public CommandHotswapHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandHotswapHudController controller,
            @Nonnull CommandHotswapHudView initialView,
            @Nonnull FailureHandler failureHandler,
            @Nonnull UpdateGate updateGate,
            @Nonnull InitialBuildGate initialBuildGate
    ) {
        super(Objects.requireNonNull(playerRef, "playerRef"), HUD_KEY, HUD_Z_ORDER);
        this.context = Objects.requireNonNull(context, "context");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.initialView = Objects.requireNonNull(initialView, "initialView");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.updateGate = Objects.requireNonNull(updateGate, "updateGate");
        this.initialBuildGate = Objects.requireNonNull(initialBuildGate, "initialBuildGate");
    }

    @Override
    public void show() {
        if (!open.get()) return;
        UICommandBuilder commandBuilder = new UICommandBuilder();
        try {
            boolean delivered = initialBuildGate.apply(
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
        controller.buildInitial(context, initialView, commandBuilder);
        built.set(true);
    }

    /** Applies one complete detached update with partial HUD semantics. */
    public boolean applyUpdate(@Nonnull CommandHotswapHudUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!open.get() || !built.get()) return false;
        try {
            UICommandBuilder commandBuilder = new UICommandBuilder();
            controller.update(update, commandBuilder);
            if (!open.get()) return false;
            return updateGate.apply(() -> {
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
        if (open.compareAndSet(true, false)) failureHandler.closed();
    }

    private void fail(@Nonnull String phase, @Nonnull Throwable failure) {
        if (!open.compareAndSet(true, false)) return;
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

    /** Guards the final UI packet against a concurrent session close. */
    @FunctionalInterface
    public interface UpdateGate {
        boolean apply(@Nonnull Runnable update);
    }

    /** Guards a first build and its full packet as one lifecycle operation. */
    @FunctionalInterface
    public interface InitialBuildGate {
        boolean apply(@Nonnull Runnable build, @Nonnull Runnable initialUpdate);
    }
}
