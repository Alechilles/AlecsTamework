package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudController;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudView;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Tamework-owned fixed-key host for one custom command hotswap HUD session. */
public final class CommandHotswapHudHost extends CommandHudHost<CommandHotswapHudUpdate> {
    public static final String HUD_KEY = TameworkCommandHotswapHud.HUD_KEY;
    public static final int HUD_Z_ORDER = 1;

    private final CommandHudOpenContext context;
    private final CommandHotswapHudController controller;
    private final CommandHotswapHudView initialView;

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
                CommandHudHost::runUpdate, CommandHudHost::buildAndUpdate);
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
                CommandHudHost::buildAndUpdate);
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
        super(playerRef, HUD_KEY, HUD_Z_ORDER,
                Objects.requireNonNull(failureHandler, "failureHandler")::failed,
                failureHandler::closed,
                Objects.requireNonNull(updateGate, "updateGate")::apply,
                Objects.requireNonNull(initialBuildGate, "initialBuildGate")::apply);
        this.context = Objects.requireNonNull(context, "context");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.initialView = Objects.requireNonNull(initialView, "initialView");
    }

    @Override
    protected void renderInitial(UICommandBuilder commandBuilder) {
        controller.buildInitial(context, initialView, commandBuilder);
    }

    @Override
    protected void renderUpdate(CommandHotswapHudUpdate update, UICommandBuilder commandBuilder) {
        controller.update(update, commandBuilder);
    }

    /** Applies one complete detached update with partial HUD semantics. */
    public boolean applyUpdate(@Nonnull CommandHotswapHudUpdate update) {
        return applyDetachedUpdate(update);
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
