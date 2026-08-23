package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Creates public filter and group-flow bindings for one command panel. */
final class CommandUiManagedPanelActions {
    private final CommandToolInventoryService tools;
    private final CommandPanelActionService panelActions;
    private final CommandUiManagedGroupFlowService groupFlows;

    CommandUiManagedPanelActions(
            @Nullable CommandToolInventoryService tools,
            @Nullable CommandPanelActionService panelActions
    ) {
        this.tools = tools;
        this.panelActions = panelActions;
        this.groupFlows = new CommandUiManagedGroupFlowService();
    }

    @Nonnull
    CommandSelectionPageService.GenericUiActionBinding filterTextBinding(
            @Nonnull Context context
    ) {
        return new CommandSelectionPageService.GenericUiActionBinding(
                new CommandUiAction("SET_FILTER_TEXT"),
                context.preferenceAuthority(), unavailable(
                        "filter text requires a bounded request"), false,
                (ignored, input) -> applyFilterText(context, input),
                CommandUiActionGateway.InputPolicy.OPTIONAL_TEXT,
                CommandPanelPreferenceService.MAX_FILTER_TEXT_LENGTH, null);
    }

    @Nonnull
    CommandSelectionPageService.GenericUiActionBinding groupFlowBinding(
            @Nonnull Context context
    ) {
        return new CommandSelectionPageService.GenericUiActionBinding(
                new CommandUiAction("MANAGE_GROUPS"),
                context.genericAuthority(), unavailable(
                        "managed group flow is unavailable"), false, null,
                CommandUiActionGateway.InputPolicy.NONE, 0,
                session -> groupFlows.open(session, groupContext(context)));
    }

    private java.util.concurrent.CompletionStage<CommandUiActionResult>
    applyFilterText(Context context, @Nullable String input) {
        Player player = context.player();
        if (player == null || panelActions == null) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.unavailable(
                            "current command player is unavailable"));
        }
        boolean changed = panelActions.trySetSelectedFilterText(
                player, context.toolId(), input);
        return CompletableFuture.completedFuture(changed
                ? CommandUiActionResult.applied()
                : CommandUiActionResult.conflict(
                        "command filter did not change"));
    }

    @Nonnull
    private CommandUiManagedGroupFlowService.Context groupContext(
            Context context
    ) {
        return new CommandUiManagedGroupFlowService.Context(
                context.toolId(), context.genericAuthority(),
                () -> {
                    Player player = context.player();
                    return player == null || tools == null ? null
                            : tools.findUniqueToolStack(
                                    player, context.toolId());
                },
                mutator -> {
                    Player player = context.player();
                    return player != null && tools != null
                            && tools.findUniqueToolStack(
                                    player, context.toolId()) != null
                            && tools.mutateToolStack(
                                    player, context.toolId(), mutator);
                });
    }

    private static Supplier<java.util.concurrent.CompletionStage<
            CommandUiActionResult>> unavailable(String message) {
        return () -> CompletableFuture.completedFuture(
                CommandUiActionResult.unavailable(message));
    }

    /** Supplies live panel authority without retaining a runtime player. */
    record Context(
            String toolId,
            BooleanSupplier preferenceAuthority,
            BooleanSupplier genericAuthority,
            Supplier<Player> playerSupplier
    ) {
        Context {
            if (toolId == null || toolId.isBlank()) {
                throw new IllegalArgumentException("toolId is required");
            }
            Objects.requireNonNull(preferenceAuthority,
                    "preferenceAuthority");
            Objects.requireNonNull(genericAuthority, "genericAuthority");
            Objects.requireNonNull(playerSupplier, "playerSupplier");
        }

        @Nullable
        Player player() {
            return playerSupplier.get();
        }
    }
}
