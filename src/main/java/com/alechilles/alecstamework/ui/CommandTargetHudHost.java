package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudController;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudUpdate;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudView;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Tamework-owned fixed-key host for one custom command target HUD session. */
public final class CommandTargetHudHost extends CommandHudHost<CommandTargetHudUpdate> {
    public static final String HUD_KEY = TameworkCommandTargetHud.HUD_KEY;

    private final CommandHudOpenContext context;
    private final CommandTargetHudController controller;
    private final CommandTargetHudView initialView;

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
        this(playerRef, context, controller, initialView, failureHandler,
                CommandHudHost::runUpdate, CommandHudHost::buildAndUpdate);
    }

    /** Creates a host with a lifecycle gate for the final client update. */
    public CommandTargetHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandTargetHudController controller,
            @Nonnull CommandTargetHudView initialView,
            @Nonnull FailureHandler failureHandler,
            @Nonnull UpdateGate updateGate
    ) {
        this(playerRef, context, controller, initialView, failureHandler, updateGate,
                CommandHudHost::buildAndUpdate);
    }

    /** Creates a host with lifecycle gates for initial and incremental packets. */
    public CommandTargetHudHost(
            @Nonnull PlayerRef playerRef,
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandTargetHudController controller,
            @Nonnull CommandTargetHudView initialView,
            @Nonnull FailureHandler failureHandler,
            @Nonnull UpdateGate updateGate,
            @Nonnull InitialBuildGate initialBuildGate
    ) {
        super(playerRef, HUD_KEY, 0,
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
    protected void renderUpdate(CommandTargetHudUpdate update, UICommandBuilder commandBuilder) {
        controller.update(update, commandBuilder);
    }

    /** Applies one complete detached update with partial HUD semantics. */
    public boolean applyUpdate(@Nonnull CommandTargetHudUpdate update) {
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
