package com.alechilles.alecstamework.api.commandui;

import java.util.UUID;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached context used to create one session-scoped command UI contributor. */
public final class CommandUiContributorCreateContext {
    @Nullable
    private final UUID playerUuid;
    @Nullable
    private final String language;
    @Nullable
    private final String toolId;
    @Nullable
    private final String itemId;
    @Nullable
    private final String configId;
    @Nullable
    private final CommandUiRendererId rendererId;
    @Nonnull
    private final CommandUiContributorId contributorId;
    private final long registrationGeneration;
    @Nonnull
    private final CommandUiContributorDirtySink dirtySink;
    @Nonnull
    private final CommandUiOpenContext openContext;

    /** Creates a context from the existing detached command UI open context. */
    public CommandUiContributorCreateContext(
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull CommandUiContributorId contributorId,
            @Nonnull CommandUiContributorDirtySink dirtySink
    ) {
        this(openContext, contributorId, 0L, dirtySink);
    }

    /** Creates a context for one exact contributor registration generation. */
    public CommandUiContributorCreateContext(
            @Nonnull CommandUiOpenContext openContext,
            @Nonnull CommandUiContributorId contributorId,
            long registrationGeneration,
            @Nonnull CommandUiContributorDirtySink dirtySink
    ) {
        this(
                Objects.requireNonNull(openContext, "openContext").playerUuid(),
                openContext.language(),
                openContext.toolId(),
                null,
                openContext.configId(),
                parseRendererId(openContext.providerId()),
                contributorId,
                registrationGeneration,
                dirtySink,
                openContext
        );
    }

    /** Creates a detached context with explicit renderer and item data. */
    public CommandUiContributorCreateContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable CommandUiRendererId rendererId,
            @Nonnull CommandUiContributorId contributorId,
            @Nonnull CommandUiContributorDirtySink dirtySink
    ) {
        this(
                playerUuid,
                language,
                toolId,
                itemId,
                configId,
                rendererId,
                contributorId,
                0L,
                dirtySink,
                new CommandUiOpenContext(
                        playerUuid,
                        language,
                        toolId,
                        configId,
                        rendererId == null ? null : rendererId.value(),
                        null
                )
        );
    }

    private CommandUiContributorCreateContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable CommandUiRendererId rendererId,
            @Nonnull CommandUiContributorId contributorId,
            long registrationGeneration,
            @Nonnull CommandUiContributorDirtySink dirtySink,
            @Nonnull CommandUiOpenContext openContext
    ) {
        this.playerUuid = playerUuid;
        this.language = normalize(language);
        this.toolId = normalize(toolId);
        this.itemId = normalize(itemId);
        this.configId = normalize(configId);
        this.rendererId = rendererId;
        this.contributorId = Objects.requireNonNull(contributorId, "contributorId");
        if (registrationGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Registration generation cannot be negative.");
        }
        this.registrationGeneration = registrationGeneration;
        this.dirtySink = Objects.requireNonNull(dirtySink, "dirtySink");
        this.openContext = Objects.requireNonNull(openContext, "openContext");
    }

    @Nullable
    public UUID playerUuid() {
        return playerUuid;
    }

    @Nullable
    public UUID playerId() {
        return playerUuid;
    }

    @Nullable
    public String language() {
        return language;
    }

    @Nullable
    public String toolId() {
        return toolId;
    }

    @Nullable
    public String itemId() {
        return itemId;
    }

    @Nullable
    public String configId() {
        return configId;
    }

    @Nullable
    public CommandUiRendererId rendererId() {
        return rendererId;
    }

    @Nonnull
    public CommandUiContributorId contributorId() {
        return contributorId;
    }

    /** Returns the exact registration generation, or zero for a detached context. */
    public long registrationGeneration() {
        return registrationGeneration;
    }

    @Nonnull
    public CommandUiContributorDirtySink dirtySink() {
        return dirtySink;
    }

    /** Returns the detached open context used to build this context. */
    @Nonnull
    public CommandUiOpenContext openContext() {
        return openContext;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nullable
    private static CommandUiRendererId parseRendererId(
            @Nullable CommandUiProviderId providerId
    ) {
        return providerId == null
                ? null
                : CommandUiRendererId.tryParse(providerId.value()).orElse(null);
    }
}
