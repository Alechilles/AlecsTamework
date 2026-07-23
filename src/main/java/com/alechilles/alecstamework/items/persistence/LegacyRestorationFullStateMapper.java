package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Maps released dormant payload facts into the complete-state projection shape. */
final class LegacyRestorationFullStateMapper {
    private LegacyRestorationFullStateMapper() {
    }

    static CoopResidentStateSnapshot death(
            UUID sourceNpcUuid,
            String roleId,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            @Nullable String customName,
            String[] toolIds,
            LegacyDeathV1Payload source,
            long capturedAtMs
    ) {
        TameworkHappinessComponent happiness = happiness(source);
        return new CoopResidentStateSnapshot(
                sourceNpcUuid,
                null,
                -1,
                roleId,
                commandLinks(ownerId, toolIds, source.homePosition()),
                owner(ownerId, ownerName),
                new TameworkTamedComponent(source.tamed()),
                name(customName, ownerId),
                happiness,
                null,
                breeding(source, happiness),
                leveling(source),
                traits(source),
                talents(source),
                lifeStage(source),
                attachments(source),
                null,
                capturedAtMs
        );
    }

    static CoopResidentStateSnapshot lost(
            UUID sourceNpcUuid,
            String roleId,
            @Nullable UUID ownerId,
            @Nullable String ownerName,
            @Nullable String customName,
            @Nullable Boolean tamed,
            String[] toolIds,
            LegacyLostV1Payload source,
            long capturedAtMs
    ) {
        return new CoopResidentStateSnapshot(
                sourceNpcUuid,
                null,
                -1,
                roleId,
                commandLinks(ownerId, toolIds, source.homePosition()),
                owner(ownerId, ownerName),
                tamed == null ? null : new TameworkTamedComponent(tamed),
                name(customName, ownerId),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                capturedAtMs
        );
    }

    private static TameworkCommandLinksComponent commandLinks(
            @Nullable UUID ownerId,
            String[] toolIds,
            @Nullable SnapshotVector3 home
    ) {
        Vector3d position = home == null
                ? null
                : new Vector3d(home.x(), home.y(), home.z());
        return new TameworkCommandLinksComponent(ownerId, toolIds, position);
    }

    @Nullable
    private static TameworkOwnerComponent owner(
            @Nullable UUID ownerId,
            @Nullable String ownerName
    ) {
        return ownerId == null && ownerName == null
                ? null
                : new TameworkOwnerComponent(ownerId, ownerName);
    }

    @Nullable
    private static TameworkNpcNameComponent name(
            @Nullable String customName,
            @Nullable UUID ownerId
    ) {
        return customName == null
                ? null
                : new TameworkNpcNameComponent(
                        customName,
                        ownerId,
                        0L,
                        TameworkNpcNameComponent.NameSource.System
                );
    }

    @Nullable
    private static TameworkHappinessComponent happiness(
            LegacyDeathV1Payload source
    ) {
        boolean present = source.happinessConfigId() != null
                || source.happinessValue() != null
                || source.happinessLastUpdateMs() != 0L
                || source.breedingHappiness() != null;
        if (!present) {
            return null;
        }
        double value = source.happinessValue() != null
                ? source.happinessValue()
                : source.breedingHappiness() != null
                ? source.breedingHappiness()
                : 0.0;
        return new TameworkHappinessComponent(
                source.happinessConfigId(),
                value,
                source.happinessLastUpdateMs()
        );
    }

    @Nullable
    private static TameworkBreedingComponent breeding(
            LegacyDeathV1Payload source,
            @Nullable TameworkHappinessComponent happiness
    ) {
        boolean present = source.breedingConfigId() != null
                || source.breedingHappiness() != null
                || source.breedingEnabled()
                || source.breedingCooldownUntilMs() != 0L
                || source.breedingLastPartnerUuid() != null;
        if (!present) {
            return null;
        }
        double value = happiness != null
                ? happiness.getValue()
                : source.breedingHappiness() == null
                ? 0.0
                : source.breedingHappiness();
        return new TameworkBreedingComponent(
                source.breedingConfigId(),
                value,
                source.happinessLastUpdateMs(),
                false,
                source.breedingEnabled(),
                source.breedingCooldownUntilMs(),
                source.breedingLastPartnerUuid(),
                0L,
                0L
        );
    }

    @Nullable
    private static TameworkLevelingComponent leveling(
            LegacyDeathV1Payload source
    ) {
        boolean present = source.levelingConfigId() != null
                || source.levelingLevel() > 1
                || source.levelingTotalXp() != 0.0;
        return present
                ? new TameworkLevelingComponent(
                        source.levelingConfigId(),
                        source.levelingLevel(),
                        0.0,
                        source.levelingTotalXp(),
                        0L
                )
                : null;
    }

    @Nullable
    private static TameworkTraitsComponent traits(
            LegacyDeathV1Payload source
    ) {
        boolean present = source.traitsConfigId() != null
                || source.traitsRollSeed() != 0L
                || source.traitsValues() != null;
        return present
                ? new TameworkTraitsComponent(
                        source.traitsConfigId(),
                        source.traitsRollSeed(),
                        LegacyRestorationEvidence.traits(
                                source.traitsValues()
                        )
                )
                : null;
    }

    @Nullable
    private static TameworkTalentsComponent talents(
            LegacyDeathV1Payload source
    ) {
        boolean present = source.talentsConfigId() != null
                || source.talentsSpentPoints() != 0
                || source.purchasedTalentIds() != null;
        return present
                ? new TameworkTalentsComponent(
                        source.talentsConfigId(),
                        source.talentsSpentPoints(),
                        LegacyRestorationEvidence.talents(
                                source.purchasedTalentIds()
                        )
                )
                : null;
    }

    @Nullable
    private static TameworkLifeStageComponent lifeStage(
            LegacyDeathV1Payload source
    ) {
        boolean present = source.lifeStage() != null
                || source.lifeStageBornAtMs() != 0L
                || source.lifeStageAdolescentAtMs() != 0L
                || source.lifeStageAdultAtMs() != 0L
                || source.lifeStageFullyGrownAtMs() != 0L
                || source.lifeStageGender() != null;
        if (!present) {
            return null;
        }
        TameworkLifeStageComponent result =
                new TameworkLifeStageComponent(
                        source.lifeStage(),
                        source.lifeStageBornAtMs(),
                        source.lifeStageAdolescentAtMs(),
                        source.lifeStageAdultAtMs(),
                        source.lifeStageFullyGrownAtMs(),
                        source.lifeStageBabyScale(),
                        source.lifeStageAdolescentScale(),
                        source.lifeStageAdolescentSwitchScale(),
                        source.lifeStageAdultStartScale(),
                        source.lifeStageAdultSwitchScale(),
                        source.lifeStageAdultScale(),
                        source.lifeStageGrowthScalingEnabled()
                );
        result.setGender(source.lifeStageGender());
        return result;
    }

    @Nullable
    private static TameworkAttachmentsComponent attachments(
            LegacyDeathV1Payload source
    ) {
        Map<String, String> values = LegacyRestorationEvidence.attachments(
                source.attachmentsValues()
        );
        return source.attachmentsConfigId() == null && values.isEmpty()
                ? null
                : new TameworkAttachmentsComponent(
                        source.attachmentsConfigId(),
                        values
                );
    }
}
