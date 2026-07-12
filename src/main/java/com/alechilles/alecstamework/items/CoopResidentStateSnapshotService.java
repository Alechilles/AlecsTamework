package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Captures and restores coop resident state so coop re-emerge can preserve companion identity/state.
 */
public final class CoopResidentStateSnapshotService {
    private final ConcurrentHashMap<UUID, CoopResidentStateSnapshot> snapshotsByNpc = new ConcurrentHashMap<>();
    private final CoopResidentStateSnapshotCodec snapshotCodec = new CoopResidentStateSnapshotCodec();
    private final CoopResidentStateRestorer stateRestorer = new CoopResidentStateRestorer(snapshotCodec);

    public void captureSnapshot(@Nullable Ref<EntityStore> reference,
                                @Nullable Store<EntityStore> store,
                                @Nullable UUID npcUuid,
                                @Nullable String coopId,
                                int residentSlot,
                                @Nullable String roleId) {
        captureSnapshotForLedger(reference, store, npcUuid, coopId, residentSlot, roleId);
    }

    @Nullable
    public CoopResidentStateSnapshot captureSnapshotForLedger(@Nullable Ref<EntityStore> reference,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable UUID npcUuid,
                                                              @Nullable String coopId,
                                                              int residentSlot,
                                                              @Nullable String roleId) {
        CoopResidentStateSnapshot snapshot = createSnapshot(reference, store, npcUuid, coopId, residentSlot, roleId);
        if (snapshot == null || snapshot.npcUuid() == null) {
            return null;
        }
        snapshotsByNpc.put(snapshot.npcUuid(), snapshot);
        debugCoop(
                "state snapshot capture npc=" + snapshot.npcUuid()
                        + " coop=" + coopId
                        + " slot=" + residentSlot
                        + " role=" + roleId
                        + " attachments=" + attachmentCount(snapshot.attachments())
        );
        return snapshot;
    }

    /** Captures full restorable state without registering a transient coop-ledger entry. */
    @Nullable
    public CoopResidentStateSnapshot captureSnapshotForPersistence(@Nullable Ref<EntityStore> reference,
                                                                   @Nullable Store<EntityStore> store,
                                                                   @Nullable UUID npcUuid,
                                                                   @Nullable String roleId) {
        return createSnapshot(reference, store, npcUuid, null, -1, roleId);
    }

    /** Captures durable managed-coop state without populating the legacy transient sidecar. */
    @Nullable
    public CoopResidentStateSnapshot captureSnapshotForManagedCoopPersistence(
            @Nullable Ref<EntityStore> reference,
            @Nullable Store<EntityStore> store,
            @Nullable UUID npcUuid,
            @Nullable String coopId,
            int residentSlot,
            @Nullable String roleId) {
        return createSnapshot(reference, store, npcUuid, coopId, residentSlot, roleId);
    }

    @Nullable
    public CoopResidentStateSnapshot consumeSnapshotForReplacement(@Nullable UUID previousNpcUuid,
                                                                   @Nullable String coopId,
                                                                   int residentSlot,
                                                                   @Nullable String roleId) {
        if (previousNpcUuid == null) {
            return null;
        }
        CoopResidentStateSnapshot candidate = snapshotsByNpc.get(previousNpcUuid);
        if (candidate == null) {
            debugCoop(
                    "state snapshot consume replacement miss previous=" + previousNpcUuid
                            + " coop=" + coopId
                            + " slot=" + residentSlot
                            + " role=" + roleId
            );
            return null;
        }
        String normalizedCoopId = normalizeIdentifier(coopId);
        String normalizedRoleId = normalizeIdentifier(roleId);
        if (!isCoopIdMatch(candidate.coopId(), normalizedCoopId)
                || !isResidentSlotMatch(candidate.residentSlot(), residentSlot)
                || !isRoleIdMatch(candidate.roleId(), normalizedRoleId)) {
            debugCoop(
                    "state snapshot consume replacement rejected previous=" + previousNpcUuid
                            + " coop=" + coopId
                            + " slot=" + residentSlot
                            + " role=" + roleId
            );
            return null;
        }
        snapshotsByNpc.remove(previousNpcUuid);
        debugCoop(
                "state snapshot consume replacement matched previous=" + previousNpcUuid
                        + " coop=" + coopId
                        + " slot=" + residentSlot
                        + " role=" + roleId
        );
        return candidate;
    }

    public void recordSnapshotForReplacement(@Nullable UUID currentNpcUuid,
                                             @Nullable CoopResidentStateSnapshot sourceSnapshot,
                                             @Nullable String coopId,
                                             int residentSlot,
                                             @Nullable String roleId) {
        if (currentNpcUuid == null || sourceSnapshot == null) {
            return;
        }
        String normalizedCoopId = normalizeIdentifier(coopId);
        String normalizedRoleId = normalizeIdentifier(roleId);
        CoopResidentStateSnapshot remapped = snapshotCodec.copy(new CoopResidentStateSnapshot(
                currentNpcUuid,
                normalizedCoopId != null ? normalizedCoopId : sourceSnapshot.coopId(),
                residentSlot >= 0 ? residentSlot : sourceSnapshot.residentSlot(),
                normalizedRoleId != null ? normalizedRoleId : sourceSnapshot.roleId(),
                sourceSnapshot.commandLinks(),
                sourceSnapshot.owner(),
                sourceSnapshot.tamed(),
                sourceSnapshot.npcName(),
                sourceSnapshot.happiness(),
                sourceSnapshot.needs(),
                sourceSnapshot.breeding(),
                sourceSnapshot.leveling(),
                sourceSnapshot.traits(),
                sourceSnapshot.talents(),
                sourceSnapshot.lifeStage(),
                sourceSnapshot.attachments(),
                sourceSnapshot.healthPercent(),
                System.currentTimeMillis()
        ));
        snapshotsByNpc.put(currentNpcUuid, remapped);
        debugCoop(
                "state snapshot remap store current=" + currentNpcUuid
                        + " from=" + sourceSnapshot.npcUuid()
                        + " coop=" + remapped.coopId()
                        + " slot=" + remapped.residentSlot()
                        + " role=" + remapped.roleId()
        );
    }

    @Nullable
    public CoopResidentStateSnapshot consumeSnapshotForRespawn(@Nullable UUID currentNpcUuid,
                                                               @Nullable String coopId,
                                                               int residentSlot,
                                                               @Nullable String roleId) {
        if (currentNpcUuid == null) {
            return null;
        }
        CoopResidentStateSnapshot direct = snapshotsByNpc.remove(currentNpcUuid);
        if (direct != null) {
            debugCoop(
                    "state snapshot consume direct current=" + currentNpcUuid
                            + " matched=" + direct.npcUuid()
                            + " coop=" + coopId
                            + " slot=" + residentSlot
                            + " role=" + roleId
            );
            return direct;
        }

        String normalizedCoopId = normalizeIdentifier(coopId);
        String normalizedRoleId = normalizeIdentifier(roleId);
        CoopResidentStateSnapshot match = null;
        int matches = 0;
        for (CoopResidentStateSnapshot candidate : snapshotsByNpc.values()) {
            if (candidate == null || candidate.npcUuid() == null) {
                continue;
            }
            if (candidate.npcUuid().equals(currentNpcUuid)) {
                continue;
            }
            if (!isCoopIdMatch(candidate.coopId(), normalizedCoopId)) {
                continue;
            }
            if (!isResidentSlotMatch(candidate.residentSlot(), residentSlot)) {
                continue;
            }
            if (!isRoleIdMatch(candidate.roleId(), normalizedRoleId)) {
                continue;
            }
            matches++;
            match = candidate;
            if (matches > 1) {
                debugCoop(
                        "state snapshot consume ambiguous current=" + currentNpcUuid
                                + " coop=" + coopId
                                + " slot=" + residentSlot
                                + " role=" + roleId
                                + " candidates=" + matches
                );
                return null;
            }
        }
        if (match == null) {
            debugCoop(
                    "state snapshot consume miss current=" + currentNpcUuid
                            + " coop=" + coopId
                            + " slot=" + residentSlot
                            + " role=" + roleId
                            + " tracked=" + snapshotsByNpc.size()
            );
            return null;
        }
        snapshotsByNpc.remove(match.npcUuid());
        debugCoop(
                "state snapshot consume matched current=" + currentNpcUuid
                        + " matched=" + match.npcUuid()
                        + " coop=" + coopId
                        + " slot=" + residentSlot
                        + " role=" + roleId
        );
        return match;
    }

    public void applySnapshot(@Nullable Ref<EntityStore> reference,
                              @Nullable Store<EntityStore> store,
                              @Nullable CommandBuffer<EntityStore> commandBuffer,
                              @Nullable CoopResidentStateSnapshot snapshot) {
        if (reference == null || !reference.isValid() || store == null || commandBuffer == null || snapshot == null) {
            return;
        }
        CoopResidentStateRestorer.PostAddWork postAddWork =
                stateRestorer.restoreToCommandBuffer(commandBuffer, reference, snapshot);
        applyDisplayNameIfPresent(reference, commandBuffer, postAddWork.displayName());
        if (postAddWork.hasHealthWork()) {
            commandBuffer.run(bufferStore -> applyRestoredHealth(
                    reference,
                    bufferStore,
                    postAddWork.healthPercent()
            ));
        }
    }

    void applyRestoredHealth(@Nullable Ref<EntityStore> reference,
                             @Nullable Store<EntityStore> store,
                             @Nullable Double healthPercent) {
        if (reference == null || !reference.isValid() || store == null || healthPercent == null) {
            return;
        }
        CompanionStatModifierService.applyTraitModifiers(reference, store);
        CompanionHealthStateService.applyStoredHealthPercent(reference, store, healthPercent);
    }

    private void applyDisplayNameIfPresent(@Nonnull Ref<EntityStore> reference,
                                           @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                           @Nullable String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return;
        }
        NpcDisplayNameComponentService.putPersistentAndRuntimeName(commandBuffer, reference, displayName);
    }

    private boolean isCoopIdMatch(@Nullable String left, @Nullable String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private boolean isRoleIdMatch(@Nullable String left, @Nullable String right) {
        if (left == null || right == null) {
            return true;
        }
        return left.equals(right);
    }

    private boolean isResidentSlotMatch(int left, int right) {
        if (left < 0 || right < 0) {
            return false;
        }
        return left == right;
    }

    @Nullable
    private String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void debugCoop(String message) {
        CoopDebugLogger.log(message);
    }

    private int attachmentCount(@Nullable TameworkAttachmentsComponent attachments) {
        if (attachments == null || attachments.getAttachmentIds() == null) {
            return 0;
        }
        return attachments.getAttachmentIds().size();
    }

    @Nullable
    private CoopResidentStateSnapshot createSnapshot(@Nullable Ref<EntityStore> reference,
                                                     @Nullable Store<EntityStore> store,
                                                     @Nullable UUID npcUuid,
                                                     @Nullable String coopId,
                                                     int residentSlot,
                                                     @Nullable String roleId) {
        if (reference == null || !reference.isValid() || store == null || npcUuid == null) {
            return null;
        }
        TameworkHappinessComponent happiness = resolveHappinessSnapshot(reference, store);
        TameworkNeedsComponent needs = store.getComponent(reference, TameworkNeedsComponent.getComponentType());
        TameworkAttachmentsComponent attachments = resolveAttachmentSnapshot(reference, store);
        Double healthPercent = CompanionHealthStateService.captureHealthPercent(reference, store);
        return snapshotCodec.copy(new CoopResidentStateSnapshot(
                npcUuid,
                normalizeIdentifier(coopId),
                residentSlot,
                normalizeIdentifier(roleId),
                store.getComponent(reference, TameworkCommandLinksComponent.getComponentType()),
                store.getComponent(reference, TameworkOwnerComponent.getComponentType()),
                store.getComponent(reference, TameworkTamedComponent.getComponentType()),
                store.getComponent(reference, TameworkNpcNameComponent.getComponentType()),
                happiness,
                needs,
                store.getComponent(reference, TameworkBreedingComponent.getComponentType()),
                store.getComponent(reference, TameworkLevelingComponent.getComponentType()),
                store.getComponent(reference, TameworkTraitsComponent.getComponentType()),
                store.getComponent(reference, TameworkTalentsComponent.getComponentType()),
                store.getComponent(reference, TameworkLifeStageComponent.getComponentType()),
                attachments,
                healthPercent,
                System.currentTimeMillis()
        ));
    }

    @Nullable
    private TameworkHappinessComponent resolveHappinessSnapshot(@Nonnull Ref<EntityStore> reference,
                                                                @Nonnull Store<EntityStore> store) {
        TameworkHappinessComponent happiness =
                store.getComponent(reference, TameworkHappinessComponent.getComponentType());
        if (happiness != null) {
            return happiness;
        }
        TameworkBreedingComponent breeding = store.getComponent(reference, TameworkBreedingComponent.getComponentType());
        if (breeding == null) {
            return null;
        }
        double fallbackValue = breeding.getHappiness();
        if (!Double.isFinite(fallbackValue)) {
            return null;
        }
        long fallbackLastUpdateMs = breeding.getLastHappinessUpdateMs();
        String configId = null;
        return new TameworkHappinessComponent(
                configId,
                fallbackValue,
                fallbackLastUpdateMs
        );
    }

    @Nullable
    private TameworkAttachmentsComponent resolveAttachmentSnapshot(@Nonnull Ref<EntityStore> reference,
                                                                   @Nonnull Store<EntityStore> store) {
        TameworkAttachmentsComponent attachments =
                store.getComponent(reference, TameworkAttachmentsComponent.getComponentType());
        if (attachments != null && attachments.getAttachmentIds() != null && !attachments.getAttachmentIds().isEmpty()) {
            return attachments;
        }
        Map<String, String> modelAttachments = CompanionModelAttachmentService.resolveCurrentAttachments(reference, store);
        if (modelAttachments == null || modelAttachments.isEmpty()) {
            return attachments;
        }
        String configId = attachments != null ? attachments.getConfigId() : null;
        return new TameworkAttachmentsComponent(configId, modelAttachments);
    }

    public record CoopResidentStateSnapshot(UUID npcUuid,
                                            @Nullable String coopId,
                                            int residentSlot,
                                            @Nullable String roleId,
                                            @Nullable TameworkCommandLinksComponent commandLinks,
                                            @Nullable TameworkOwnerComponent owner,
                                            @Nullable TameworkTamedComponent tamed,
                                            @Nullable TameworkNpcNameComponent npcName,
                                            @Nullable TameworkHappinessComponent happiness,
                                            @Nullable TameworkNeedsComponent needs,
                                            @Nullable TameworkBreedingComponent breeding,
                                            @Nullable TameworkLevelingComponent leveling,
                                            @Nullable TameworkTraitsComponent traits,
                                            @Nullable TameworkTalentsComponent talents,
                                            @Nullable TameworkLifeStageComponent lifeStage,
                                            @Nullable TameworkAttachmentsComponent attachments,
                                            @Nullable Double healthPercent,
                                            long capturedAtMs) {
    }

}
