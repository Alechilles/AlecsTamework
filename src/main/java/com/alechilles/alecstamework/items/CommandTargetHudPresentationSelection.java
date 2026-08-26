package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached config identity for one target HUD presentation. */
record CommandTargetHudPresentationSelection(
        @Nullable String configId,
        @Nullable String activeItemId,
        @Nullable String rendererId,
        @Nonnull List<CommandHudContributorRequirement> contributors
) {
    CommandTargetHudPresentationSelection {
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    @Nonnull
    static CommandTargetHudPresentationSelection from(
            @Nonnull TwCommandItemConfig config,
            @Nullable String activeItemId
    ) {
        return new CommandTargetHudPresentationSelection(
                config.getId(), activeItemId, config.getTargetHudRendererId(),
                config.getTargetHudContributors());
    }
}
