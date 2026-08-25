package com.alechilles.alecstamework.api.commandui;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Detached context supplied while a renderer creates one page controller.
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
    private final CommandUiRendererId rendererId;
    @Nullable
    private final String rosterMode;

    /** Creates an empty context for adapters that do not need presentation values. */
    public CommandUiOpenContext() {
        this(null, null, null, null, (CommandUiRendererId) null, null);
    }

    /** Creates detached context with the selected renderer identifier. */
    public CommandUiOpenContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String configId,
            @Nullable CommandUiRendererId rendererId,
            @Nullable String rosterMode
    ) {
        this.playerUuid = playerUuid;
        this.language = normalize(language);
        this.toolId = normalize(toolId);
        this.configId = normalize(configId);
        this.rendererId = rendererId;
        this.rosterMode = normalize(rosterMode);
    }

    /** Convenience constructor for renderer-facing string IDs. */
    public CommandUiOpenContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String configId,
            @Nullable String rendererId,
            @Nullable String rosterMode
    ) {
        this(
                playerUuid,
                language,
                toolId,
                configId,
                rendererId == null || rendererId.isBlank()
                        ? null
                        : CommandUiRendererId.of(rendererId),
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
    public CommandUiRendererId rendererId() {
        return rendererId;
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
