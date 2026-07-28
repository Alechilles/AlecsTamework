package com.alechilles.alecstamework.items.persistence;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Immutable value for the death snapshot payload written by the June persistence system.
 *
 * <p>Profile identity and command-tool links were stored outside the payload and therefore do
 * not belong to this versioned value.</p>
 */
public record LegacyDeathV1Payload(
        @Nullable UUID ownerId,
        @Nullable String ownerName,
        @Nullable String roleId,
        boolean tamed,
        @Nullable String customName,
        @Nullable String displayName,
        @Nullable SnapshotVector3 lastKnownPosition,
        @Nullable SnapshotVector3 homePosition,
        long diedAtMs,
        long respawnAvailableAtMs,
        @Nullable String breedingConfigId,
        @Nullable Double breedingHappiness,
        long breedingCooldownUntilMs,
        @Nullable UUID breedingLastPartnerUuid,
        @Nullable String traitsConfigId,
        long traitsRollSeed,
        @Nullable String traitsValues,
        @Nullable String happinessConfigId,
        @Nullable Double happinessValue,
        long happinessLastUpdateMs,
        @Nullable String lifeStage,
        long lifeStageBornAtMs,
        long lifeStageAdolescentAtMs,
        long lifeStageAdultAtMs,
        long lifeStageFullyGrownAtMs,
        double lifeStageBabyScale,
        double lifeStageAdolescentScale,
        double lifeStageAdolescentSwitchScale,
        double lifeStageAdultStartScale,
        double lifeStageAdultSwitchScale,
        double lifeStageAdultScale,
        boolean lifeStageGrowthScalingEnabled,
        @Nullable String attachmentsConfigId,
        @Nullable String attachmentsValues,
        boolean breedingEnabled,
        @Nullable String levelingConfigId,
        int levelingLevel,
        double levelingTotalXp,
        @Nullable String talentsConfigId,
        int talentsSpentPoints,
        @Nullable String purchasedTalentIds,
        @Nullable DeathCauseKind deathCauseKind,
        @Nullable String deathSourceName,
        @Nullable String lifeStageGender
) {
    public LegacyDeathV1Payload {
        ownerName = absentWhenBlank(ownerName);
        roleId = absentWhenBlank(roleId);
        customName = absentWhenBlank(customName);
        displayName = absentWhenBlank(displayName);
        breedingConfigId = absentWhenBlank(breedingConfigId);
        traitsConfigId = absentWhenBlank(traitsConfigId);
        traitsValues = absentWhenBlank(traitsValues);
        happinessConfigId = absentWhenBlank(happinessConfigId);
        lifeStage = absentWhenBlank(lifeStage);
        attachmentsConfigId = absentWhenBlank(attachmentsConfigId);
        attachmentsValues = absentWhenBlank(attachmentsValues);
        levelingConfigId = absentWhenBlank(levelingConfigId);
        talentsConfigId = absentWhenBlank(talentsConfigId);
        purchasedTalentIds = absentWhenBlank(purchasedTalentIds);
        deathSourceName = absentWhenBlank(deathSourceName);
        lifeStageGender = absentWhenBlank(lifeStageGender);
        requireFinite(breedingHappiness, "breedingHappiness");
        requireFinite(happinessValue, "happinessValue");
        requireFinite(lifeStageBabyScale, "lifeStageBabyScale");
        requireFinite(lifeStageAdolescentScale, "lifeStageAdolescentScale");
        requireFinite(lifeStageAdolescentSwitchScale, "lifeStageAdolescentSwitchScale");
        requireFinite(lifeStageAdultStartScale, "lifeStageAdultStartScale");
        requireFinite(lifeStageAdultSwitchScale, "lifeStageAdultSwitchScale");
        requireFinite(lifeStageAdultScale, "lifeStageAdultScale");
        requireFinite(levelingTotalXp, "levelingTotalXp");
    }

    @Nullable
    private static String absentWhenBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireFinite(@Nullable Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    /** Released death-cause identifiers stored in the version-1 payload. */
    public enum DeathCauseKind {
        STARVATION,
        DEHYDRATION,
        STARVATION_AND_DEHYDRATION,
        PLAYER,
        NPC,
        ENVIRONMENT,
        UNKNOWN
    }
}
