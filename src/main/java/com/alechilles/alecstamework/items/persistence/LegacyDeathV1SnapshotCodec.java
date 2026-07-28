package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.snapshot.SnapshotCodec;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Strict deterministic codec for the released June death snapshot payload. */
public final class LegacyDeathV1SnapshotCodec implements SnapshotCodec<LegacyDeathV1Payload> {
    @Override
    @Nonnull
    public SnapshotKind kind() {
        return TameworkSnapshotCodecs.DEATH;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    @Nonnull
    public Class<LegacyDeathV1Payload> valueType() {
        return LegacyDeathV1Payload.class;
    }

    @Override
    @Nonnull
    public String encode(@Nonnull LegacyDeathV1Payload value) {
        if (value == null) {
            throw new IllegalArgumentException("Legacy death snapshot is required");
        }
        JsonObject root = new JsonObject();
        LegacySnapshotJson.putUuid(root, "ownerId", value.ownerId());
        LegacySnapshotJson.putString(root, "ownerName", value.ownerName());
        LegacySnapshotJson.putString(root, "roleId", value.roleId());
        root.addProperty("tamed", value.tamed());
        LegacySnapshotJson.putString(root, "customName", value.customName());
        LegacySnapshotJson.putString(root, "displayName", value.displayName());
        LegacySnapshotJson.putVector(root, "lastKnownPosition", value.lastKnownPosition());
        LegacySnapshotJson.putVector(root, "homePosition", value.homePosition());
        root.addProperty("diedAtMs", value.diedAtMs());
        root.addProperty("respawnAvailableAtMs", value.respawnAvailableAtMs());
        LegacySnapshotJson.putString(root, "breedingConfigId", value.breedingConfigId());
        if (value.breedingHappiness() != null) {
            root.addProperty("breedingHappiness", value.breedingHappiness());
        }
        root.addProperty("breedingCooldownUntilMs", value.breedingCooldownUntilMs());
        LegacySnapshotJson.putUuid(
                root,
                "breedingLastPartnerUuid",
                value.breedingLastPartnerUuid()
        );
        LegacySnapshotJson.putString(root, "traitsConfigId", value.traitsConfigId());
        root.addProperty("traitsRollSeed", value.traitsRollSeed());
        LegacySnapshotJson.putString(root, "traitsValues", value.traitsValues());
        LegacySnapshotJson.putString(root, "happinessConfigId", value.happinessConfigId());
        if (value.happinessValue() != null) {
            root.addProperty("happinessValue", value.happinessValue());
        }
        root.addProperty("happinessLastUpdateMs", value.happinessLastUpdateMs());
        LegacySnapshotJson.putString(root, "lifeStage", value.lifeStage());
        root.addProperty("lifeStageBornAtMs", value.lifeStageBornAtMs());
        root.addProperty("lifeStageAdolescentAtMs", value.lifeStageAdolescentAtMs());
        root.addProperty("lifeStageAdultAtMs", value.lifeStageAdultAtMs());
        root.addProperty("lifeStageFullyGrownAtMs", value.lifeStageFullyGrownAtMs());
        root.addProperty("lifeStageBabyScale", value.lifeStageBabyScale());
        root.addProperty("lifeStageAdolescentScale", value.lifeStageAdolescentScale());
        root.addProperty(
                "lifeStageAdolescentSwitchScale",
                value.lifeStageAdolescentSwitchScale()
        );
        root.addProperty("lifeStageAdultStartScale", value.lifeStageAdultStartScale());
        root.addProperty("lifeStageAdultSwitchScale", value.lifeStageAdultSwitchScale());
        root.addProperty("lifeStageAdultScale", value.lifeStageAdultScale());
        root.addProperty(
                "lifeStageGrowthScalingEnabled",
                value.lifeStageGrowthScalingEnabled()
        );
        LegacySnapshotJson.putString(root, "attachmentsConfigId", value.attachmentsConfigId());
        LegacySnapshotJson.putString(root, "attachmentsValues", value.attachmentsValues());
        root.addProperty("breedingEnabled", value.breedingEnabled());
        LegacySnapshotJson.putString(root, "levelingConfigId", value.levelingConfigId());
        root.addProperty("levelingLevel", value.levelingLevel());
        root.addProperty("levelingTotalXp", value.levelingTotalXp());
        LegacySnapshotJson.putString(root, "talentsConfigId", value.talentsConfigId());
        root.addProperty("talentsSpentPoints", value.talentsSpentPoints());
        LegacySnapshotJson.putString(root, "purchasedTalentIds", value.purchasedTalentIds());
        if (value.deathCauseKind() != null) {
            root.addProperty("deathCauseKind", value.deathCauseKind().name());
        }
        LegacySnapshotJson.putString(root, "deathSourceName", value.deathSourceName());
        LegacySnapshotJson.putString(root, "lifeStageGender", value.lifeStageGender());
        return root.toString();
    }

    @Override
    @Nonnull
    public LegacyDeathV1Payload decode(@Nonnull String payloadJson) {
        JsonObject root = LegacySnapshotJson.parseRoot(payloadJson);
        return new LegacyDeathV1Payload(
                LegacySnapshotJson.optionalUuid(root, "ownerId"),
                LegacySnapshotJson.optionalString(root, "ownerName"),
                LegacySnapshotJson.optionalString(root, "roleId"),
                LegacySnapshotJson.optionalBoolean(root, "tamed", false),
                LegacySnapshotJson.optionalString(root, "customName"),
                LegacySnapshotJson.optionalString(root, "displayName"),
                LegacySnapshotJson.optionalVector(root, "lastKnownPosition"),
                LegacySnapshotJson.optionalVector(root, "homePosition"),
                LegacySnapshotJson.optionalLong(root, "diedAtMs", 0L),
                LegacySnapshotJson.optionalLong(root, "respawnAvailableAtMs", 0L),
                LegacySnapshotJson.optionalString(root, "breedingConfigId"),
                LegacySnapshotJson.optionalNullableDouble(root, "breedingHappiness"),
                LegacySnapshotJson.optionalLong(root, "breedingCooldownUntilMs", 0L),
                LegacySnapshotJson.optionalUuid(root, "breedingLastPartnerUuid"),
                LegacySnapshotJson.optionalString(root, "traitsConfigId"),
                LegacySnapshotJson.optionalLong(root, "traitsRollSeed", 0L),
                LegacySnapshotJson.optionalString(root, "traitsValues"),
                LegacySnapshotJson.optionalString(root, "happinessConfigId"),
                LegacySnapshotJson.optionalNullableDouble(root, "happinessValue"),
                LegacySnapshotJson.optionalLong(root, "happinessLastUpdateMs", 0L),
                LegacySnapshotJson.optionalString(root, "lifeStage"),
                LegacySnapshotJson.optionalLong(root, "lifeStageBornAtMs", 0L),
                LegacySnapshotJson.optionalLong(root, "lifeStageAdolescentAtMs", 0L),
                LegacySnapshotJson.optionalLong(root, "lifeStageAdultAtMs", 0L),
                LegacySnapshotJson.optionalLong(root, "lifeStageFullyGrownAtMs", 0L),
                LegacySnapshotJson.optionalDouble(root, "lifeStageBabyScale", 0.55),
                LegacySnapshotJson.optionalDouble(root, "lifeStageAdolescentScale", 0.80),
                LegacySnapshotJson.optionalDouble(
                        root,
                        "lifeStageAdolescentSwitchScale",
                        0.80
                ),
                LegacySnapshotJson.optionalDouble(root, "lifeStageAdultStartScale", 0.80),
                LegacySnapshotJson.optionalDouble(root, "lifeStageAdultSwitchScale", 1.00),
                LegacySnapshotJson.optionalDouble(root, "lifeStageAdultScale", 1.00),
                LegacySnapshotJson.optionalBoolean(
                        root,
                        "lifeStageGrowthScalingEnabled",
                        false
                ),
                LegacySnapshotJson.optionalString(root, "attachmentsConfigId"),
                LegacySnapshotJson.optionalString(root, "attachmentsValues"),
                LegacySnapshotJson.optionalBoolean(root, "breedingEnabled", false),
                LegacySnapshotJson.optionalString(root, "levelingConfigId"),
                LegacySnapshotJson.optionalInt(root, "levelingLevel", 1),
                LegacySnapshotJson.optionalDouble(root, "levelingTotalXp", 0.0),
                LegacySnapshotJson.optionalString(root, "talentsConfigId"),
                LegacySnapshotJson.optionalInt(root, "talentsSpentPoints", 0),
                LegacySnapshotJson.optionalString(root, "purchasedTalentIds"),
                decodeDeathCause(root),
                LegacySnapshotJson.optionalString(root, "deathSourceName"),
                LegacySnapshotJson.optionalString(root, "lifeStageGender")
        );
    }

    private LegacyDeathV1Payload.DeathCauseKind decodeDeathCause(JsonObject root) {
        String raw = LegacySnapshotJson.optionalString(root, "deathCauseKind");
        if (raw == null) {
            return null;
        }
        try {
            return LegacyDeathV1Payload.DeathCauseKind.valueOf(raw.trim());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid snapshot field deathCauseKind", failure);
        }
    }
}
