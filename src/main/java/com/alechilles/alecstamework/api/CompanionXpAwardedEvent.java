package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Event emitted after a linked companion successfully receives XP.
 *
 * <p>This event is informational and non-cancelable. Listener failures are isolated by the
 * Tamework event bus, so one integration cannot prevent companion progression or other listeners.
 */
public record CompanionXpAwardedEvent(@Nonnull UUID npcUuid,
                                      @Nullable UUID ownerUuid,
                                      @Nonnull Set<String> toolIds,
                                      @Nullable String roleId,
                                      @Nullable String levelingConfigId,
                                      @Nonnull CompanionXpSource source,
                                      double awardedXp,
                                      int previousLevel,
                                      int currentLevel,
                                      double previousTotalXp,
                                      double currentTotalXp,
                                      double previousCurrentXp,
                                      double currentXp,
                                      double nextLevelXp,
                                      int maxLevel,
                                      boolean atMaxLevel,
                                      boolean leveledUp,
                                      long occurredAtMs,
                                      long emittedAtMs) implements TameworkEvent {
    public CompanionXpAwardedEvent {
        npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
        toolIds = Set.copyOf(Objects.requireNonNull(toolIds, "toolIds"));
        source = Objects.requireNonNull(source, "source");
    }
}
