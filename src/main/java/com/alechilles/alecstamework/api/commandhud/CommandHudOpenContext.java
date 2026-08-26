package com.alechilles.alecstamework.api.commandhud;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached context supplied while a renderer creates one HUD controller. */
public final class CommandHudOpenContext {
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
    private final CommandHudSurface surface;
    @Nullable
    private final CommandHudRendererId rendererId;
    @Nullable
    private final UUID targetUuid;
    @Nullable
    private final String targetKey;
    private final long sessionGeneration;

    /** Creates an empty context for adapters that need no presentation data. */
    public CommandHudOpenContext() {
        this(null, null, null, null, null, null, (CommandHudRendererId) null,
                null, null, 0L);
    }

    /** Creates a detached context with the selected renderer and session data. */
    public CommandHudOpenContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable CommandHudSurface surface,
            @Nullable CommandHudRendererId rendererId,
            @Nullable UUID targetUuid,
            @Nullable String targetKey,
            long sessionGeneration
    ) {
        if (sessionGeneration < 0L) {
            throw new IllegalArgumentException("HUD session generation cannot be negative.");
        }
        this.playerUuid = playerUuid;
        this.language = normalize(language);
        this.toolId = normalize(toolId);
        this.itemId = normalize(itemId);
        this.configId = normalize(configId);
        this.surface = surface;
        this.rendererId = rendererId;
        this.targetUuid = targetUuid;
        this.targetKey = normalize(targetKey);
        this.sessionGeneration = sessionGeneration;
    }

    /** Convenience constructor for a renderer-facing string identifier. */
    public CommandHudOpenContext(
            @Nullable UUID playerUuid,
            @Nullable String language,
            @Nullable String toolId,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable CommandHudSurface surface,
            @Nullable String rendererId,
            @Nullable UUID targetUuid,
            @Nullable String targetKey,
            long sessionGeneration
    ) {
        this(playerUuid, language, toolId, itemId, configId, surface,
                parseRendererId(rendererId), targetUuid, targetKey, sessionGeneration);
    }

    /** Convenience constructor for a surface and renderer without target data. */
    public CommandHudOpenContext(
            @Nullable UUID playerUuid,
            @Nullable CommandHudSurface surface,
            @Nullable CommandHudRendererId rendererId
    ) {
        this(playerUuid, null, null, null, null, surface, rendererId,
                null, null, 0L);
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
    public CommandHudSurface surface() {
        return surface;
    }

    @Nullable
    public CommandHudRendererId rendererId() {
        return rendererId;
    }

    @Nullable
    public UUID targetUuid() {
        return targetUuid;
    }

    @Nullable
    public UUID targetId() {
        return targetUuid;
    }

    @Nullable
    public String targetKey() {
        return targetKey;
    }

    public long sessionGeneration() {
        return sessionGeneration;
    }

    @Nullable
    private static CommandHudRendererId parseRendererId(@Nullable String value) {
        return value == null || value.isBlank() ? null : CommandHudRendererId.of(value);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
