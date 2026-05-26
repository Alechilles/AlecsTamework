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
import com.hypixel.hytale.builtin.adventure.farming.component.CoopResidentComponent;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
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
    private final ConcurrentHashMap<String, CoopResidentStateSnapshot> recentRemovedByRole = new ConcurrentHashMap<>();

    @Nullable
    public RemovedCoopResidentCapture onNpcRemoved(@Nullable Ref<EntityStore> reference,
                                                   @Nullable RemoveReason reason,
                                                   @Nullable Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return null;
        }
        CoopResidentComponent coopResident = store.getComponent(reference, CoopResidentComponent.getComponentType());
        if (coopResident == null) {
            return null;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : resolveUuidFromComponent(reference, store);
        if (npcUuid == null) {
            return null;
        }
        CoopContext coopContext = resolveCoopContext(store, coopResident);
        Vector3i coopLocation = coopResident.getCoopLocation();
        if (coopContext == null || coopContext.coopId() == null || coopLocation == null) {
            return null;
        }
        int residentSlot = coopContext.coopBlock() != null
                ? CoopResidentSlotResolver.resolveResidentSlotByUuid(coopContext.coopBlock(), npcUuid)
                : -1;
        String roleId = resolveRoleId(npc);
        CoopResidentStateSnapshot snapshot = createSnapshot(
                reference,
                store,
                npcUuid,
                coopContext != null ? coopContext.coopId() : null,
                residentSlot,
                roleId
        );
        if (snapshot == null) {
            return null;
        }
        if (residentSlot >= 0) {
            snapshotsByNpc.put(npcUuid, snapshot);
            debugCoop(
                    "state snapshot capture npc=" + npcUuid
                            + " coop=" + coopContext.coopId()
                            + " slot=" + residentSlot
                            + " role=" + roleId
                            + " reason=" + reason
                            + " attachments=" + attachmentCount(snapshot.attachments())
            );
        }
        registerRecentRemovedSnapshot(snapshot);
        String worldName = store.getExternalData() != null && store.getExternalData().getWorld() != null
                ? store.getExternalData().getWorld().getName()
                : null;
        return new RemovedCoopResidentCapture(
                npcUuid,
                normalizeIdentifier(worldName),
                snapshot.coopId(),
                coopLocation != null ? new Vector3i(coopLocation.x, coopLocation.y, coopLocation.z) : null,
                residentSlot,
                snapshot.roleId(),
                snapshot
        );
    }

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

    @Nullable
    public CoopResidentStateSnapshot consumeRecentRemovedSnapshot(@Nullable String roleId, long maxAgeMs) {
        return consumeRecentRemovedSnapshot(roleId, null, -1, maxAgeMs);
    }

    @Nullable
    public CoopResidentStateSnapshot consumeRecentRemovedSnapshot(@Nullable String roleId,
                                                                  @Nullable String coopId,
                                                                  int residentSlot,
                                                                  long maxAgeMs) {
        String normalizedRoleId = normalizeIdentifier(roleId);
        if (normalizedRoleId == null || maxAgeMs <= 0L) {
            return null;
        }
        CoopResidentStateSnapshot snapshot = recentRemovedByRole.get(normalizedRoleId);
        if (snapshot == null) {
            return null;
        }
        String normalizedCoopId = normalizeIdentifier(coopId);
        if (!isCoopIdMatchForRecent(snapshot.coopId(), normalizedCoopId)
                || !isResidentSlotMatchForRecent(snapshot.residentSlot(), residentSlot)) {
            return null;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - snapshot.capturedAtMs());
        if (ageMs > maxAgeMs) {
            recentRemovedByRole.remove(normalizedRoleId, snapshot);
            return null;
        }
        recentRemovedByRole.remove(normalizedRoleId, snapshot);
        debugCoop(
                "state snapshot consume recent role=" + normalizedRoleId
                        + " npc=" + snapshot.npcUuid()
                        + " coop=" + coopId
                        + " slot=" + residentSlot
                        + " ageMs=" + ageMs
        );
        return snapshot;
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
        CoopResidentStateSnapshot remapped = new CoopResidentStateSnapshot(
                currentNpcUuid,
                normalizedCoopId != null ? normalizedCoopId : sourceSnapshot.coopId(),
                residentSlot >= 0 ? residentSlot : sourceSnapshot.residentSlot(),
                normalizedRoleId != null ? normalizedRoleId : sourceSnapshot.roleId(),
                copyComponent(sourceSnapshot.commandLinks()),
                copyComponent(sourceSnapshot.owner()),
                copyComponent(sourceSnapshot.tamed()),
                copyComponent(sourceSnapshot.npcName()),
                copyComponent(sourceSnapshot.happiness()),
                copyComponent(sourceSnapshot.needs()),
                copyComponent(sourceSnapshot.breeding()),
                copyComponent(sourceSnapshot.leveling()),
                copyComponent(sourceSnapshot.traits()),
                copyComponent(sourceSnapshot.talents()),
                copyComponent(sourceSnapshot.lifeStage()),
                copyComponent(sourceSnapshot.attachments()),
                sourceSnapshot.healthPercent(),
                System.currentTimeMillis()
        );
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
        putIfPresent(commandBuffer, reference, TameworkCommandLinksComponent.getComponentType(), snapshot.commandLinks());
        putIfPresent(commandBuffer, reference, TameworkOwnerComponent.getComponentType(), snapshot.owner());
        putIfPresent(commandBuffer, reference, TameworkTamedComponent.getComponentType(), snapshot.tamed());
        putIfPresent(commandBuffer, reference, TameworkNpcNameComponent.getComponentType(), snapshot.npcName());
        applyDisplayNameIfPresent(reference, commandBuffer, snapshot.npcName());
        putIfPresent(commandBuffer, reference, TameworkHappinessComponent.getComponentType(), snapshot.happiness());
        putIfPresent(commandBuffer, reference, TameworkNeedsComponent.getComponentType(), snapshot.needs());
        putIfPresent(commandBuffer, reference, TameworkBreedingComponent.getComponentType(), snapshot.breeding());
        putIfPresent(commandBuffer, reference, TameworkLevelingComponent.getComponentType(), snapshot.leveling());
        putIfPresent(commandBuffer, reference, TameworkTraitsComponent.getComponentType(), snapshot.traits());
        putIfPresent(commandBuffer, reference, TameworkTalentsComponent.getComponentType(), snapshot.talents());
        putIfPresent(commandBuffer, reference, TameworkLifeStageComponent.getComponentType(), snapshot.lifeStage());
        putIfPresent(commandBuffer, reference, TameworkAttachmentsComponent.getComponentType(), snapshot.attachments());
        if (snapshot.healthPercent() != null) {
            commandBuffer.run(bufferStore -> applyRestoredHealth(reference, bufferStore, snapshot.healthPercent()));
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
                                           @Nullable TameworkNpcNameComponent npcName) {
        if (npcName == null || npcName.getName() == null || npcName.getName().isBlank()) {
            return;
        }
        NpcDisplayNameComponentService.putPersistentAndRuntimeName(commandBuffer, reference, npcName.getName());
    }

    @Nullable
    private UUID resolveUuidFromComponent(@Nonnull Ref<EntityStore> reference, @Nonnull Store<EntityStore> store) {
        UUIDComponent uuidComponent = store.getComponent(reference, UUIDComponent.getComponentType());
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    @Nullable
    private String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null || npc.getRoleName() == null || npc.getRoleName().isBlank()) {
            return null;
        }
        return npc.getRoleName().trim();
    }

    @Nullable
    private CoopContext resolveCoopContext(@Nonnull Store<EntityStore> store,
                                           @Nonnull CoopResidentComponent coopResident) {
        Vector3i coopLocation = coopResident.getCoopLocation();
        if (coopLocation == null) {
            return null;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null || world.getChunkStore() == null) {
            return null;
        }
        WorldChunk worldChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(coopLocation.x, coopLocation.z));
        if (worldChunk == null) {
            return null;
        }
        Ref<ChunkStore> blockRef = worldChunk.getBlockComponentEntity(coopLocation.x, coopLocation.y, coopLocation.z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        if (chunkStore == null) {
            return null;
        }
        CoopBlock coopBlock = chunkStore.getComponent(blockRef, CoopBlock.getComponentType());
        if (coopBlock == null) {
            return null;
        }
        FarmingCoopAsset coopAsset = coopBlock.getCoopAsset();
        return new CoopContext(coopAsset != null ? coopAsset.getId() : null, coopBlock);
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

    @Nullable
    @SuppressWarnings("unchecked")
    private <T extends Component<EntityStore>> T copyComponent(@Nullable T component) {
        if (component == null) {
            return null;
        }
        return (T) component.clone();
    }

    private <T extends Component<EntityStore>> void putIfPresent(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                                  @Nonnull Ref<EntityStore> reference,
                                                                  @Nullable ComponentType<EntityStore, T> type,
                                                                  @Nullable T component) {
        if (type == null || component == null) {
            return;
        }
        commandBuffer.putComponent(reference, type, copyComponent(component));
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

    private void registerRecentRemovedSnapshot(@Nonnull CoopResidentStateSnapshot snapshot) {
        if (!isEligibleForRecentSnapshot(snapshot)) {
            return;
        }
        String normalizedRoleId = normalizeIdentifier(snapshot.roleId());
        if (normalizedRoleId == null) {
            return;
        }
        recentRemovedByRole.put(normalizedRoleId, snapshot);
        debugCoop(
                "state snapshot recent register role=" + normalizedRoleId
                        + " npc=" + snapshot.npcUuid()
                        + " coop=" + snapshot.coopId()
                        + " slot=" + snapshot.residentSlot()
        );
    }

    private boolean isEligibleForRecentSnapshot(@Nonnull CoopResidentStateSnapshot snapshot) {
        String normalizedRoleId = normalizeIdentifier(snapshot.roleId());
        if (normalizedRoleId == null) {
            return false;
        }
        TameworkTamedComponent tamed = snapshot.tamed();
        boolean explicitlyTamed = tamed != null && tamed.isTamed();
        TameworkOwnerComponent owner = snapshot.owner();
        boolean hasOwner = owner != null && owner.getOwnerId() != null;
        TameworkCommandLinksComponent commandLinks = snapshot.commandLinks();
        boolean hasLinks = commandLinks != null
                && commandLinks.getToolIds() != null
                && commandLinks.getToolIds().length > 0;
        boolean roleLooksTamed = normalizedRoleId.startsWith("tamed_");
        return explicitlyTamed || hasOwner || hasLinks || roleLooksTamed;
    }

    private boolean isCoopIdMatchForRecent(@Nullable String snapshotCoopId, @Nullable String expectedCoopId) {
        if (expectedCoopId == null) {
            return true;
        }
        if (snapshotCoopId == null) {
            return true;
        }
        return snapshotCoopId.equals(expectedCoopId);
    }

    private boolean isResidentSlotMatchForRecent(int snapshotResidentSlot, int expectedResidentSlot) {
        if (expectedResidentSlot < 0) {
            return true;
        }
        if (snapshotResidentSlot < 0) {
            return true;
        }
        return snapshotResidentSlot == expectedResidentSlot;
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
        TameworkNeedsComponent needs = copyComponent(store.getComponent(reference, TameworkNeedsComponent.getComponentType()));
        TameworkAttachmentsComponent attachments = resolveAttachmentSnapshot(reference, store);
        Double healthPercent = CompanionHealthStateService.captureHealthPercent(reference, store);
        return new CoopResidentStateSnapshot(
                npcUuid,
                normalizeIdentifier(coopId),
                residentSlot,
                normalizeIdentifier(roleId),
                copyComponent(store.getComponent(reference, TameworkCommandLinksComponent.getComponentType())),
                copyComponent(store.getComponent(reference, TameworkOwnerComponent.getComponentType())),
                copyComponent(store.getComponent(reference, TameworkTamedComponent.getComponentType())),
                copyComponent(store.getComponent(reference, TameworkNpcNameComponent.getComponentType())),
                happiness,
                needs,
                copyComponent(store.getComponent(reference, TameworkBreedingComponent.getComponentType())),
                copyComponent(store.getComponent(reference, TameworkLevelingComponent.getComponentType())),
                copyComponent(store.getComponent(reference, TameworkTraitsComponent.getComponentType())),
                copyComponent(store.getComponent(reference, TameworkTalentsComponent.getComponentType())),
                copyComponent(store.getComponent(reference, TameworkLifeStageComponent.getComponentType())),
                attachments,
                healthPercent,
                System.currentTimeMillis()
        );
    }

    @Nullable
    private TameworkHappinessComponent resolveHappinessSnapshot(@Nonnull Ref<EntityStore> reference,
                                                                @Nonnull Store<EntityStore> store) {
        TameworkHappinessComponent happiness = copyComponent(
                store.getComponent(reference, TameworkHappinessComponent.getComponentType())
        );
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
                Math.max(0L, fallbackLastUpdateMs)
        );
    }

    @Nullable
    private TameworkAttachmentsComponent resolveAttachmentSnapshot(@Nonnull Ref<EntityStore> reference,
                                                                   @Nonnull Store<EntityStore> store) {
        TameworkAttachmentsComponent attachments = copyComponent(
                store.getComponent(reference, TameworkAttachmentsComponent.getComponentType())
        );
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

    private record CoopContext(@Nullable String coopId, @Nonnull CoopBlock coopBlock) {
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

    public record RemovedCoopResidentCapture(@Nonnull UUID npcUuid,
                                             @Nullable String worldName,
                                             @Nullable String coopId,
                                             @Nullable Vector3i coopLocation,
                                             int residentSlot,
                                             @Nullable String roleId,
                                             @Nonnull CoopResidentStateSnapshot snapshot) {
    }
}
