package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudCloseReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Small identity helpers shared by the hotswap presentation coordinator. */
final class CommandHotswapHudPresentationSupport {
    private CommandHotswapHudPresentationSupport() {
    }

    @Nonnull
    static CommandHudCloseReason closeReason(
            @Nonnull CommandHotswapHudPresentation previous,
            @Nullable Store<EntityStore> currentStore,
            @Nonnull CommandHotswapHudToolIdentity currentTool
    ) {
        if (previous.store() != currentStore) {
            return CommandHudCloseReason.WORLD_TRANSFER;
        }
        if (!previous.toolIdentity().same(currentTool)) {
            return CommandHudCloseReason.TOOL_CHANGED;
        }
        return CommandHudCloseReason.CONFIG_CHANGED;
    }

    static boolean matchesFailedTool(
            @Nullable FailedTool failed,
            @Nullable Store<EntityStore> store,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudPresentationSelection selection
    ) {
        return failed != null && failed.store() == store
                && failed.toolIdentity().same(toolIdentity)
                && failed.selection().equals(selection);
    }

    record FailedTool(
            @Nullable Store<EntityStore> store,
            @Nonnull CommandHotswapHudToolIdentity toolIdentity,
            @Nonnull CommandHotswapHudPresentationSelection selection
    ) {
        FailedTool {
            Objects.requireNonNull(toolIdentity, "toolIdentity");
            Objects.requireNonNull(selection, "selection");
        }
    }
}
