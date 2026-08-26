package com.alechilles.alecstamework.api.commandhud;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached context used to create one session-scoped HUD contributor. */
public final class CommandHudContributorCreateContext {
    @Nonnull
    private final CommandHudOpenContext openContext;
    @Nonnull
    private final CommandHudContributorId contributorId;
    private final long registrationGeneration;
    @Nonnull
    private final CommandHudContributorDirtySink dirtySink;

    /** Creates a contributor context with a detached generation. */
    public CommandHudContributorCreateContext(
            @Nonnull CommandHudOpenContext openContext,
            @Nonnull CommandHudContributorId contributorId,
            @Nonnull CommandHudContributorDirtySink dirtySink
    ) {
        this(openContext, contributorId, 0L, dirtySink);
    }

    /** Creates a context for one exact contributor registration generation. */
    public CommandHudContributorCreateContext(
            @Nonnull CommandHudOpenContext openContext,
            @Nonnull CommandHudContributorId contributorId,
            long registrationGeneration,
            @Nonnull CommandHudContributorDirtySink dirtySink
    ) {
        this.openContext = Objects.requireNonNull(openContext, "openContext");
        this.contributorId = Objects.requireNonNull(contributorId, "contributorId");
        if (registrationGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Contributor registration generation cannot be negative.");
        }
        this.registrationGeneration = registrationGeneration;
        this.dirtySink = Objects.requireNonNull(dirtySink, "dirtySink");
    }

    @Nonnull
    public CommandHudOpenContext openContext() {
        return openContext;
    }

    @Nonnull
    public CommandHudContributorId contributorId() {
        return contributorId;
    }

    public long registrationGeneration() {
        return registrationGeneration;
    }

    @Nonnull
    public CommandHudContributorDirtySink dirtySink() {
        return dirtySink;
    }

    @Nullable
    public UUID playerUuid() {
        return openContext.playerUuid();
    }

    @Nullable
    public UUID playerId() {
        return openContext.playerId();
    }

    @Nullable
    public String language() {
        return openContext.language();
    }

    @Nullable
    public String toolId() {
        return openContext.toolId();
    }

    @Nullable
    public String itemId() {
        return openContext.itemId();
    }

    @Nullable
    public String configId() {
        return openContext.configId();
    }

    @Nullable
    public CommandHudSurface surface() {
        return openContext.surface();
    }

    @Nullable
    public CommandHudRendererId rendererId() {
        return openContext.rendererId();
    }
}
