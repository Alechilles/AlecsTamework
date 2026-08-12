package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Removes transient lethal state when a stored companion returns to the world.
 *
 * <p>Capture items pause needs progression while stored. Death restoration
 * starts a new life and lets the normal progression bootstrap create safe role
 * defaults.</p>
 */
public final class CompanionReturnStateNormalizer {

    private CompanionReturnStateNormalizer() {
    }

    /** Keeps stored needs values but restarts live timers after capture release. */
    @Nonnull
    public static CoopResidentStateSnapshot forCaptureRelease(
            @Nonnull CoopResidentStateSnapshot source
    ) {
        Objects.requireNonNull(source, "source");
        return copy(source, pausedNeeds(source.needs()),
                source.currentHealth(), source.maximumHealth(),
                source.healthPercent());
    }

    /** Returns a death snapshot at full health with no lethal needs backlog. */
    @Nonnull
    public static CoopResidentStateSnapshot forDeathRestoration(
            @Nonnull CoopResidentStateSnapshot source
    ) {
        Objects.requireNonNull(source, "source");
        Double maximumHealth = source.maximumHealth();
        Double currentHealth = maximumHealth == null ? null : maximumHealth;
        return copy(source, null, currentHealth, maximumHealth, 100.0D);
    }

    @Nullable
    private static TameworkNeedsComponent pausedNeeds(
            @Nullable TameworkNeedsComponent source
    ) {
        if (source == null) {
            return null;
        }
        return new TameworkNeedsComponent(
                source.getConfigId(),
                source.getHunger(),
                source.getThirst(),
                source.getAppliedHappinessPenalty(),
                0.0D,
                0L,
                0L
        );
    }

    private static CoopResidentStateSnapshot copy(
            CoopResidentStateSnapshot source,
            @Nullable TameworkNeedsComponent needs,
            @Nullable Double currentHealth,
            @Nullable Double maximumHealth,
            @Nullable Double healthPercent
    ) {
        return new CoopResidentStateSnapshot(
                source.npcUuid(),
                source.coopId(),
                source.residentSlot(),
                source.roleId(),
                source.commandLinks(),
                source.owner(),
                source.tamed(),
                source.npcName(),
                source.happiness(),
                needs,
                source.breeding(),
                source.leveling(),
                source.traits(),
                source.talents(),
                source.lifeStage(),
                source.attachments(),
                currentHealth,
                maximumHealth,
                healthPercent,
                source.capturedAtMs()
        );
    }
}
