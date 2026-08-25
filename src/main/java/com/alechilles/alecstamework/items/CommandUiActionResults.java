package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.ui.LinkedNpcPanelFeatureAction;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Converts internal command callbacks into truthful public action results. */
final class CommandUiActionResults {
    private CommandUiActionResults() {
    }

    static CompletionStage<CommandUiActionResult> apply(
            @Nullable Runnable action
    ) {
        if (action == null) return completed(CommandUiActionResult.unavailable(
                "action is unavailable"));
        try {
            action.run();
            return completed(CommandUiActionResult.accepted());
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed("action failed"));
        }
    }

    static <T> CompletionStage<CommandUiActionResult> apply(
            @Nullable Consumer<T> action,
            T value
    ) {
        if (action == null) return completed(CommandUiActionResult.unavailable(
                "action is unavailable"));
        try {
            action.accept(value);
            return completed(CommandUiActionResult.accepted());
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed("action failed"));
        }
    }

    static CompletionStage<CommandUiActionResult> apply(
            @Nullable LinkedNpcPanelFeatureAction action,
            UUID target,
            UUID ownerUuid
    ) {
        if (action == null) return completed(CommandUiActionResult.unavailable(
                "action is unavailable"));
        try {
            PlayerRef playerRef = Universe.get() == null ? null
                    : Universe.get().getPlayer(ownerUuid);
            Ref<EntityStore> ref = playerRef == null ? null
                    : playerRef.getReference();
            Store<EntityStore> store = ref == null ? null : ref.getStore();
            if (ref == null || !ref.isValid() || store == null) {
                return completed(CommandUiActionResult.unavailable(
                        "current command world is unavailable"));
            }
            action.accept(target, ref, store);
            return completed(CommandUiActionResult.accepted());
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed("action failed"));
        }
    }

    static CompletionStage<CommandUiActionResult> applyHotswap(
            @Nullable CommandToolInventoryService tools,
            Supplier<Player> playerSupplier,
            String toolId,
            CommandHotswapAssignmentStore.Slot slot,
            String commandId
    ) {
        try {
            Player player = playerSupplier.get();
            if (player == null || tools == null) {
                return completed(CommandUiActionResult.unavailable(
                        "current command player is unavailable"));
            }
            boolean changed = tools.mutateActiveToolStack(
                    player, toolId, stack ->
                            new CommandHotswapAssignmentStore().write(
                                    stack, slot, commandId));
            return completed(changed ? CommandUiActionResult.applied()
                    : CommandUiActionResult.conflict(
                            "command item changed before assignment"));
        } catch (RuntimeException | LinkageError failure) {
            return completed(CommandUiActionResult.failed(
                    "hotswap assignment failed"));
        }
    }

    private static CompletionStage<CommandUiActionResult> completed(
            CommandUiActionResult result
    ) {
        return CompletableFuture.completedFuture(result);
    }
}
