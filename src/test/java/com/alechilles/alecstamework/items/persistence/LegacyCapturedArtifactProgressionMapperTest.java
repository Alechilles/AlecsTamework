package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.util.Objects;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for the complete progression shape shipped in v2.16.1 capture items. */
class LegacyCapturedArtifactProgressionMapperTest {
    @Test
    void mapsReleasedPublicProgressionWithoutCurrentOnlyFields() {
        BsonDocument metadata = releasedPublicProgression();

        LegacyCapturedArtifactProgressionMapper.State state =
                new LegacyCapturedArtifactProgressionMapper().map(
                        new LegacyCapturedArtifactMetadata(metadata),
                        900L,
                        "Tamed_Wolf_Black"
                );

        assertHappiness(state);
        assertNeeds(state);
        assertBreeding(state);
        assertLeveling(state);
        assertTraitsAndTalents(state);
        assertLifeStage(state);
        assertEquals(0.75D, state.healthPercent());
    }

    private void assertHappiness(
            LegacyCapturedArtifactProgressionMapper.State state
    ) {
        TameworkHappinessComponent happiness = Objects.requireNonNull(
                state.happiness()
        );
        assertEquals("default", happiness.getConfigId());
        assertEquals(82.5D, happiness.getValue());
        assertEquals(-777L, happiness.getLastUpdateMs());
    }

    private void assertNeeds(
            LegacyCapturedArtifactProgressionMapper.State state
    ) {
        TameworkNeedsComponent needs = Objects.requireNonNull(state.needs());
        assertEquals("default", needs.getConfigId());
        assertEquals(61.0D, needs.getHunger());
        assertEquals(73.0D, needs.getThirst());
        assertEquals(4.5D, needs.getAppliedHappinessPenalty());
        assertEquals(0L, needs.getLastUpdateMs());
        assertEquals(0L, needs.getLastPassiveSweepMs());
    }

    private void assertBreeding(
            LegacyCapturedArtifactProgressionMapper.State state
    ) {
        TameworkBreedingComponent breeding = Objects.requireNonNull(
                state.breeding()
        );
        assertEquals("default", breeding.getConfigId());
        assertEquals(82.5D, breeding.getHappiness());
        assertEquals(-777L, breeding.getLastHappinessUpdateMs());
        assertFalse(breeding.isReady());
        assertFalse(breeding.isEnabled());
        assertEquals(-100L, breeding.getCooldownUntilMs());
        assertNull(breeding.getLastPartnerUuid());
    }

    private void assertLeveling(
            LegacyCapturedArtifactProgressionMapper.State state
    ) {
        TameworkLevelingComponent leveling = Objects.requireNonNull(
                state.leveling()
        );
        assertEquals("default", leveling.getConfigId());
        assertEquals(3, leveling.getLevel());
        assertEquals(0.0D, leveling.getCurrentXp());
        assertEquals(240.0D, leveling.getTotalXp());
    }

    private void assertTraitsAndTalents(
            LegacyCapturedArtifactProgressionMapper.State state
    ) {
        TameworkTraitsComponent traits = Objects.requireNonNull(
                state.traits()
        );
        assertEquals("default", traits.getConfigId());
        assertEquals(42L, traits.getRollSeed());
        assertEquals(1.25D, traits.getTraitValue("speed"));

        TameworkTalentsComponent talents = Objects.requireNonNull(
                state.talents()
        );
        assertEquals("default", talents.getConfigId());
        assertEquals(0, talents.getSpentPoints());
        assertArrayEquals(new String[0], talents.getPurchasedTalentIds());
    }

    private void assertLifeStage(
            LegacyCapturedArtifactProgressionMapper.State state
    ) {
        TameworkLifeStageComponent lifeStage = Objects.requireNonNull(
                state.lifeStage()
        );
        assertEquals("Adult", lifeStage.getStage());
        assertEquals(10L, lifeStage.getBornAtMs());
        assertEquals(20L, lifeStage.getAdolescentAtMs());
        assertEquals(30L, lifeStage.getAdultAtMs());
        assertEquals(40L, lifeStage.getFullyGrownAtMs());
        assertEquals(0.4D, lifeStage.getBabyScale());
        assertEquals(0.7D, lifeStage.getAdolescentScale());
        assertEquals(0.6D, lifeStage.getAdolescentSwitchScale());
        assertEquals(0.8D, lifeStage.getAdultStartScale());
        assertEquals(0.9D, lifeStage.getAdultSwitchScale());
        assertEquals(1.0D, lifeStage.getAdultScale());
        assertFalse(lifeStage.isGrowthScalingEnabled());
        assertEquals("Tamed_Wolf_Black", lifeStage.getAdultRoleId());
        assertNull(lifeStage.getBabyRoleId());
        assertNull(lifeStage.getAdolescentRoleId());
        assertEquals("Female", lifeStage.getGender());
    }

    private BsonDocument releasedPublicProgression() {
        return new BsonDocument()
                .append(TameworkMetadataKeys.HAPPINESS_CONFIG_ID, text("default"))
                .append(TameworkMetadataKeys.HAPPINESS_VALUE, number(82.5D))
                .append(TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS, integer(-777L))
                .append(TameworkMetadataKeys.NEEDS_CONFIG_ID, text("default"))
                .append(TameworkMetadataKeys.NEEDS_HUNGER, number(61.0D))
                .append(TameworkMetadataKeys.NEEDS_THIRST, number(73.0D))
                .append(
                        TameworkMetadataKeys.NEEDS_APPLIED_HAPPINESS_PENALTY,
                        number(4.5D)
                )
                .append(TameworkMetadataKeys.NEEDS_LAST_UPDATE_MS, integer(-600L))
                .append(
                        TameworkMetadataKeys.NEEDS_LAST_PASSIVE_SWEEP_MS,
                        integer(-500L)
                )
                .append(TameworkMetadataKeys.BREEDING_CONFIG_ID, text("default"))
                .append(TameworkMetadataKeys.BREEDING_HAPPINESS, number(82.5D))
                .append(
                        TameworkMetadataKeys.BREEDING_ENABLED,
                        new BsonBoolean(false)
                )
                .append(
                        TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL,
                        integer(-100L)
                )
                .append(TameworkMetadataKeys.LEVELING_CONFIG_ID, text("default"))
                .append(TameworkMetadataKeys.LEVELING_LEVEL, new BsonInt32(3))
                .append(TameworkMetadataKeys.LEVELING_TOTAL_XP, number(240.0D))
                .append(TameworkMetadataKeys.TRAITS_CONFIG_ID, text("default"))
                .append(TameworkMetadataKeys.TRAITS_ROLL_SEED, integer(42L))
                .append(
                        TameworkMetadataKeys.TRAITS_VALUES,
                        text("[{\"id\":\"speed\",\"value\":1.25}]")
                )
                .append(TameworkMetadataKeys.TALENTS_CONFIG_ID, text("default"))
                .append(TameworkMetadataKeys.TALENTS_SPENT_POINTS, new BsonInt32(0))
                .append(TameworkMetadataKeys.LIFE_STAGE, text("Adult"))
                .append(TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS, integer(10L))
                .append(
                        TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS,
                        integer(20L)
                )
                .append(TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS, integer(30L))
                .append(
                        TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS,
                        integer(40L)
                )
                .append(TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE, number(0.4D))
                .append(
                        TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE,
                        number(0.7D)
                )
                .append(
                        TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SWITCH_SCALE,
                        number(0.6D)
                )
                .append(
                        TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE,
                        number(0.8D)
                )
                .append(
                        TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE,
                        number(0.9D)
                )
                .append(TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE, number(1.0D))
                .append(
                        TameworkMetadataKeys.LIFE_STAGE_GROWTH_SCALING_ENABLED,
                        new BsonBoolean(false)
                )
                .append(TameworkMetadataKeys.LIFE_STAGE_GENDER, text("Female"))
                .append(TameworkMetadataKeys.HEALTH_PERCENT, number(0.75D));
    }

    private BsonString text(String value) {
        return new BsonString(value);
    }

    private BsonDouble number(double value) {
        return new BsonDouble(value);
    }

    private BsonInt64 integer(long value) {
        return new BsonInt64(value);
    }
}
