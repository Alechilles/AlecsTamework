package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.api.CompanionXpOutcomeView;
import com.alechilles.alecstamework.api.CompanionXpSource;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable record of one committed companion XP mutation. */
public record CompanionXpTransition(
        @Nonnull UUID npcUuid,
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
        long emittedAtMs
) {
    public CompanionXpTransition {
        npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
        toolIds = Set.copyOf(Objects.requireNonNull(toolIds, "toolIds"));
        source = Objects.requireNonNull(source, "source");
    }

    /** Projects this progression fact into the public Activity API V2 outcome shape. */
    @Nonnull
    public CompanionXpOutcomeView toOutcomeView() {
        return new CompanionXpOutcomeView(
                npcUuid,
                ownerUuid,
                source,
                awardedXp,
                previousLevel,
                currentLevel,
                previousTotalXp,
                currentTotalXp,
                leveledUp
        );
    }
}
