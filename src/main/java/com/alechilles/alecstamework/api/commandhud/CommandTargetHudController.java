package com.alechilles.alecstamework.api.commandhud;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import javax.annotation.Nonnull;

/** Passive per-session controller for a custom target HUD renderer. */
public interface CommandTargetHudController extends AutoCloseable {
    /** Builds the initial custom HUD state from a detached composite view. */
    void buildInitial(
            @Nonnull CommandHudOpenContext context,
            @Nonnull CommandTargetHudView view,
            @Nonnull UICommandBuilder commands
    );

    /** Applies a complete current view using the supplied focused update hint. */
    default void update(
            @Nonnull CommandTargetHudUpdate update,
            @Nonnull UICommandBuilder commands
    ) {
    }

    /** Releases renderer-local state when the HUD session closes. */
    @Override
    default void close() {
    }
}
