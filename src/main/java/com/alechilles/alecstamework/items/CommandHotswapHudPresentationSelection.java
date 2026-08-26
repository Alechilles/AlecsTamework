package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached effective hotswap-HUD selection used to keep a session stable. */
record CommandHotswapHudPresentationSelection(
        @Nullable String configId,
        @Nullable String activeItemId,
        @Nullable String rendererId,
        @Nonnull List<CommandHudContributorRequirement> contributors
) {
    CommandHotswapHudPresentationSelection {
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    @Nonnull
    static CommandHotswapHudPresentationSelection from(
            @Nonnull TwCommandItemConfig config,
            @Nonnull CommandHotswapHudToolIdentity identity
    ) {
        return new CommandHotswapHudPresentationSelection(
                config.getId(), identity.itemId(), config.getHotswapHudRendererId(),
                config.getHotswapHudContributors());
    }
}
