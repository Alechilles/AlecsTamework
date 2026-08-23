package com.alechilles.alecstamework.api.commandui;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Detached context supplied while a provider creates one page controller.
 *
 * <p>Only stable identity and presentation values belong here. The context
 * intentionally has no live Hytale object or gameplay authority.</p>
 */
public final class CommandUiOpenContext {
    @Nullable
    private final UUID playerUuid;
    @Nullable
    private final String language;
    @Nullable
    private final String toolId;
    @Nullable
    private final String configId;
    @Nullable
    private final CommandUiProviderId providerId;
    @Nullable
    private final String rosterMode;

    /** Creates an empty context for adapters that do not need presentation values. */
    public CommandUiOpenContext() {
        this(null, null, null, null, (CommandUiProviderId) null, null);
    }

    public CommandUiOpenContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String configId,
            @Nullable CommandUiProviderId providerId,
            @Nullable String rosterMode
    ) {
        this.playerUuid = playerUuid;
        this.language = normalize(language);
        this.toolId = normalize(toolId);
        this.configId = normalize(configId);
        this.providerId = providerId;
        this.rosterMode = normalize(rosterMode);
    }

    /** Convenience constructor for provider-facing string IDs. */
    public CommandUiOpenContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String configId,
            @Nullable String providerId,
            @Nullable String rosterMode
    ) {
        this(
                playerUuid,
                language,
                toolId,
                configId,
                providerId == null || providerId.isBlank()
                        ? null
                        : CommandUiProviderId.of(providerId),
                rosterMode
        );
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
    public String configId() {
        return configId;
    }

    @Nullable
    public CommandUiProviderId providerId() {
        return providerId;
    }

    @Nullable
    public String rosterMode() {
        return rosterMode;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
