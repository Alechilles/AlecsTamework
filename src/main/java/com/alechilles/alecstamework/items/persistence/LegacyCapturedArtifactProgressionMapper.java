package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import javax.annotation.Nullable;

/** Maps the progression component groups written by released-public captured items. */
final class LegacyCapturedArtifactProgressionMapper {
    State map(
            LegacyCapturedArtifactMetadata metadata,
            long capturedAtMs,
            @Nullable String canonicalRole
    ) {
        return new State(
                happiness(metadata, capturedAtMs),
                needs(metadata),
                breeding(metadata),
                leveling(metadata),
                traits(metadata),
                talents(metadata),
                lifeStage(metadata, canonicalRole),
                metadata.finiteDouble(
                        TameworkMetadataKeys.HEALTH_PERCENT
                )
        );
    }

    @Nullable
    private TameworkHappinessComponent happiness(
            LegacyCapturedArtifactMetadata metadata,
            long capturedAtMs
    ) {
        metadata.requireCompleteGroup(
                "happiness",
                new String[]{
                        TameworkMetadataKeys.HAPPINESS_VALUE,
                        TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS
                },
                TameworkMetadataKeys.HAPPINESS_CONFIG_ID,
                TameworkMetadataKeys.HAPPINESS_VALUE,
                TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS
        );
        String config = metadata.text(
                TameworkMetadataKeys.HAPPINESS_CONFIG_ID
        );
        Double value = metadata.finiteDouble(
                TameworkMetadataKeys.HAPPINESS_VALUE
        );
        Long updated = metadata.integer(
                TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS
        );
        Double legacyValue = metadata.finiteDouble(
                TameworkMetadataKeys.BREEDING_HAPPINESS
        );
        if (config == null && value == null && updated == null
                && legacyValue == null) {
            return null;
        }
        return new TameworkHappinessComponent(
                config,
                value != null
                        ? value
                        : legacyValue == null ? 0.0D : legacyValue,
                updated == null ? capturedAtMs : updated
        );
    }

    @Nullable
    private TameworkNeedsComponent needs(
            LegacyCapturedArtifactMetadata metadata
    ) {
        String[] fields = {
                TameworkMetadataKeys.NEEDS_CONFIG_ID,
                TameworkMetadataKeys.NEEDS_HUNGER,
                TameworkMetadataKeys.NEEDS_THIRST,
                TameworkMetadataKeys.NEEDS_APPLIED_HAPPINESS_PENALTY,
                TameworkMetadataKeys.NEEDS_LAST_UPDATE_MS,
                TameworkMetadataKeys.NEEDS_LAST_PASSIVE_SWEEP_MS
        };
        metadata.requireCompleteGroup(
                "needs",
                new String[]{
                        TameworkMetadataKeys.NEEDS_HUNGER,
                        TameworkMetadataKeys.NEEDS_THIRST,
                        TameworkMetadataKeys
                                .NEEDS_APPLIED_HAPPINESS_PENALTY,
                        TameworkMetadataKeys.NEEDS_LAST_UPDATE_MS,
                        TameworkMetadataKeys.NEEDS_LAST_PASSIVE_SWEEP_MS
                },
                fields
        );
        String config = metadata.text(TameworkMetadataKeys.NEEDS_CONFIG_ID);
        Double hunger = metadata.finiteDouble(
                TameworkMetadataKeys.NEEDS_HUNGER
        );
        if (config == null && hunger == null) {
            return null;
        }
        Double thirst = metadata.finiteDouble(
                TameworkMetadataKeys.NEEDS_THIRST
        );
        Double penalty = metadata.finiteDouble(
                TameworkMetadataKeys.NEEDS_APPLIED_HAPPINESS_PENALTY
        );
        metadata.integer(TameworkMetadataKeys.NEEDS_LAST_UPDATE_MS);
        metadata.integer(TameworkMetadataKeys.NEEDS_LAST_PASSIVE_SWEEP_MS);
        return new TameworkNeedsComponent(
                config,
                hunger,
                thirst,
                penalty,
                0L,
                0L
        );
    }

    @Nullable
    private TameworkBreedingComponent breeding(
            LegacyCapturedArtifactMetadata metadata
    ) {
        String[] fields = {
                TameworkMetadataKeys.BREEDING_CONFIG_ID,
                TameworkMetadataKeys.BREEDING_HAPPINESS,
                TameworkMetadataKeys.BREEDING_ENABLED,
                TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL,
                TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID
        };
        metadata.requireCompleteGroup(
                "breeding",
                new String[]{
                        TameworkMetadataKeys.BREEDING_HAPPINESS,
                        TameworkMetadataKeys.BREEDING_ENABLED,
                        TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL
                },
                fields
        );
        String config = metadata.text(
                TameworkMetadataKeys.BREEDING_CONFIG_ID
        );
        Double happiness = metadata.finiteDouble(
                TameworkMetadataKeys.BREEDING_HAPPINESS
        );
        if (config == null && happiness == null) {
            return null;
        }
        Boolean enabled = metadata.bool(
                TameworkMetadataKeys.BREEDING_ENABLED
        );
        Long cooldown = metadata.integer(
                TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL
        );
        Long happinessUpdated = metadata.integer(
                TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS
        );
        return new TameworkBreedingComponent(
                config,
                happiness,
                happinessUpdated == null ? 0L : happinessUpdated,
                false,
                enabled,
                cooldown,
                metadata.uuid(
                        TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID
                )
        );
    }

    @Nullable
    private TameworkLevelingComponent leveling(
            LegacyCapturedArtifactMetadata metadata
    ) {
        metadata.requireCompleteGroup(
                "leveling",
                new String[]{
                        TameworkMetadataKeys.LEVELING_LEVEL,
                        TameworkMetadataKeys.LEVELING_TOTAL_XP
                },
                TameworkMetadataKeys.LEVELING_CONFIG_ID,
                TameworkMetadataKeys.LEVELING_LEVEL,
                TameworkMetadataKeys.LEVELING_TOTAL_XP
        );
        String config = metadata.text(
                TameworkMetadataKeys.LEVELING_CONFIG_ID
        );
        Integer level = metadata.intValue(
                TameworkMetadataKeys.LEVELING_LEVEL
        );
        if (config == null && level == null) {
            return null;
        }
        return new TameworkLevelingComponent(
                config,
                level,
                0.0D,
                metadata.finiteDouble(
                        TameworkMetadataKeys.LEVELING_TOTAL_XP
                )
        );
    }

    @Nullable
    private TameworkTraitsComponent traits(
            LegacyCapturedArtifactMetadata metadata
    ) {
        metadata.requireCompleteGroup(
                "traits",
                new String[]{TameworkMetadataKeys.TRAITS_ROLL_SEED},
                TameworkMetadataKeys.TRAITS_CONFIG_ID,
                TameworkMetadataKeys.TRAITS_ROLL_SEED,
                TameworkMetadataKeys.TRAITS_VALUES
        );
        String config = metadata.text(TameworkMetadataKeys.TRAITS_CONFIG_ID);
        Long seed = metadata.integer(
                TameworkMetadataKeys.TRAITS_ROLL_SEED
        );
        if (config == null && seed == null) {
            return null;
        }
        return new TameworkTraitsComponent(
                config,
                seed,
                LegacyRestorationEvidence.traits(
                        metadata.text(TameworkMetadataKeys.TRAITS_VALUES)
                )
        );
    }

    @Nullable
    private TameworkTalentsComponent talents(
            LegacyCapturedArtifactMetadata metadata
    ) {
        metadata.requireCompleteGroup(
                "talents",
                new String[]{TameworkMetadataKeys.TALENTS_SPENT_POINTS},
                TameworkMetadataKeys.TALENTS_CONFIG_ID,
                TameworkMetadataKeys.TALENTS_ALLOCATION_REVISION,
                TameworkMetadataKeys.TALENTS_SPENT_POINTS,
                TameworkMetadataKeys.TALENTS_PURCHASED_IDS
        );
        String config = metadata.text(
                TameworkMetadataKeys.TALENTS_CONFIG_ID
        );
        Long allocationRevision = metadata.integer(
                TameworkMetadataKeys.TALENTS_ALLOCATION_REVISION
        );
        Integer spent = metadata.intValue(
                TameworkMetadataKeys.TALENTS_SPENT_POINTS
        );
        if (config == null && allocationRevision == null && spent == null) {
            return null;
        }
        return new TameworkTalentsComponent(
                config,
                spent,
                LegacyRestorationEvidence.talents(metadata.text(
                        TameworkMetadataKeys.TALENTS_PURCHASED_IDS
                )),
                allocationRevision == null ? 0L : allocationRevision
        );
    }

    @Nullable
    private TameworkLifeStageComponent lifeStage(
            LegacyCapturedArtifactMetadata metadata,
            @Nullable String canonicalRole
    ) {
        String[] required = {
                TameworkMetadataKeys.LIFE_STAGE,
                TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SWITCH_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_GROWTH_SCALING_ENABLED
        };
        metadata.requireCompleteGroup(
                "life-stage",
                required,
                TameworkMetadataKeys.LIFE_STAGE,
                TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS,
                TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SWITCH_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE,
                TameworkMetadataKeys.LIFE_STAGE_GROWTH_SCALING_ENABLED,
                TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID,
                TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID,
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID,
                TameworkMetadataKeys.LIFE_STAGE_GENDER
        );
        String stage = metadata.text(TameworkMetadataKeys.LIFE_STAGE);
        if (stage == null) {
            return null;
        }
        TameworkLifeStageComponent result = new TameworkLifeStageComponent(
                stage,
                metadata.integer(
                        TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS
                ),
                metadata.integer(
                        TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS
                ),
                metadata.integer(
                        TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS
                ),
                metadata.integer(
                        TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS
                ),
                metadata.finiteDouble(
                        TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE
                ),
                metadata.finiteDouble(
                        TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE
                ),
                metadata.finiteDouble(
                        TameworkMetadataKeys
                                .LIFE_STAGE_ADOLESCENT_SWITCH_SCALE
                ),
                metadata.finiteDouble(
                        TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE
                ),
                metadata.finiteDouble(
                        TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE
                ),
                metadata.finiteDouble(
                        TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE
                ),
                metadata.bool(
                        TameworkMetadataKeys
                                .LIFE_STAGE_GROWTH_SCALING_ENABLED
                )
        );
        result.setAdultRoleId(resolveStageRole(
                metadata.text(TameworkMetadataKeys.LIFE_STAGE_ADULT_ROLE_ID),
                canonicalRole,
                stage,
                "Adult"
        ));
        result.setBabyRoleId(resolveStageRole(
                metadata.text(TameworkMetadataKeys.LIFE_STAGE_BABY_ROLE_ID),
                canonicalRole,
                stage,
                "Baby"
        ));
        result.setAdolescentRoleId(resolveStageRole(
                metadata.text(
                        TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_ROLE_ID
                ),
                canonicalRole,
                stage,
                "Adolescent"
        ));
        result.setGender(metadata.text(
                TameworkMetadataKeys.LIFE_STAGE_GENDER
        ));
        return result;
    }

    @Nullable
    private String resolveStageRole(
            @Nullable String saved,
            @Nullable String canonical,
            String actualStage,
            String expectedStage
    ) {
        if (saved != null) {
            return saved;
        }
        return expectedStage.equalsIgnoreCase(actualStage)
                ? canonical
                : null;
    }

    record State(
            @Nullable TameworkHappinessComponent happiness,
            @Nullable TameworkNeedsComponent needs,
            @Nullable TameworkBreedingComponent breeding,
            @Nullable TameworkLevelingComponent leveling,
            @Nullable TameworkTraitsComponent traits,
            @Nullable TameworkTalentsComponent talents,
            @Nullable TameworkLifeStageComponent lifeStage,
            @Nullable Double healthPercent
    ) {
    }
}
