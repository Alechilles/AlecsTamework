package com.alechilles.alecstamework.api.commandui;

import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Server-side execution callback retained by Tamework for one contributor action. */
@FunctionalInterface
public interface CommandUiContributorActionHandler {
    /**
     * Executes after Tamework validates the session, generation, input, and
     * current action authority.
     */
    @Nonnull
    CompletionStage<CommandUiActionResult> handle(
            @Nonnull CommandUiContributorActionContext context
    );
}
