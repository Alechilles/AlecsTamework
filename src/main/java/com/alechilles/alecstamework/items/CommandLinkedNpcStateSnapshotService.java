package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.TalentIdCodec;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shared in-memory cache of linked companion state snapshots.
 *
 * <p>This snapshot is intentionally shaped like {@link CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot}
 * so existing respawn/recovery pipelines can reuse it directly.
 */
public final class CommandLinkedNpcStateSnapshotService {
    private final ConcurrentHashMap<UUID, CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> snapshotsByNpc =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CoopResidentStateSnapshotService.CoopResidentStateSnapshot>
            fullSnapshotsByNpc = new ConcurrentHashMap<>();
    private final CoopResidentStateSnapshotService fullStateCaptureService = new CoopResidentStateSnapshotService();
    private final CoopResidentStateSnapshotCodec fullStateCodec = new CoopResidentStateSnapshotCodec();
    @Nullable
    private final NpcProfileRepository profileRepository;
    private final LoadedNpcIdentityIndex loadedNpcIdentityIndex;

    public CommandLinkedNpcStateSnapshotService() {
        this(null, new LoadedNpcIdentityIndex());
    }

    public CommandLinkedNpcStateSnapshotService(@Nullable NpcProfileRepository profileRepository) {
        this(profileRepository, new LoadedNpcIdentityIndex());
    }

    public CommandLinkedNpcStateSnapshotService(@Nullable NpcProfileRepository profileRepository,
                                                @Nonnull LoadedNpcIdentityIndex loadedNpcIdentityIndex) {
        this.profileRepository = profileRepository;
        this.loadedNpcIdentityIndex = Objects.requireNonNull(loadedNpcIdentityIndex, "loadedNpcIdentityIndex");
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        indexNpcAdded(reference, store);
        refreshFromEntity(reference, store);
    }

    public void onNpcRemoved(Ref<EntityStore> reference,
                             RemoveReason reason,
                             Store<EntityStore> store) {
        if (reference == null || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID componentUuid = resolveComponentUuid(reference, store);
        UUID legacyNpcUuid = npc != null ? npc.getUuid() : null;
        LoadedNpcIdentityIndex.Location location = LoadedNpcLocationResolver.resolve(store);
        loadedNpcIdentityIndex.recordRemoved(componentUuid, location);
        if (legacyNpcUuid != null && !legacyNpcUuid.equals(componentUuid)) {
            loadedNpcIdentityIndex.recordRemoved(legacyNpcUuid, location);
        }
        UUID indexedUuid = componentUuid != null ? componentUuid : legacyNpcUuid;
        UUID npcUuid = npc != null && npc.getUuid() != null ? npc.getUuid() : indexedUuid;
        if (npcUuid == null) {
            return;
        }
        if (reason == RemoveReason.REMOVE) {
            snapshotsByNpc.remove(npcUuid);
            fullSnapshotsByNpc.remove(npcUuid);
            return;
        }
        refreshFromEntity(reference, store);
    }

    @Nonnull
    public LoadedNpcIdentityIndex getLoadedNpcIdentityIndex() {
        return loadedNpcIdentityIndex;
    }

    private void indexNpcAdded(@Nonnull Ref<EntityStore> reference,
                               @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null) {
            return;
        }
        UUID componentUuid = resolveComponentUuid(reference, store);
        UUID legacyNpcUuid = npc.getUuid();
        LoadedNpcIdentityIndex.Location location = LoadedNpcLocationResolver.resolve(store);
        loadedNpcIdentityIndex.recordAdded(componentUuid, location);
        if (legacyNpcUuid != null && !legacyNpcUuid.equals(componentUuid)) {
            loadedNpcIdentityIndex.recordAdded(legacyNpcUuid, location);
        }
    }

    @Nullable
    private UUID resolveComponentUuid(@Nonnull Ref<EntityStore> reference,
                                      @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        UUIDComponent uuidComponent = uuidType != null ? store.getComponent(reference, uuidType) : null;
        return uuidComponent != null ? uuidComponent.getUuid() : null;
    }

    public void refreshFromEntity(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null) {
            return;
        }
        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot = buildSnapshot(reference, store, npc);
        if (snapshot == null) {
            snapshotsByNpc.remove(npcUuid);
            fullSnapshotsByNpc.remove(npcUuid);
            return;
        }
        snapshotsByNpc.put(npcUuid, snapshot);
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot =
                fullStateCaptureService.captureSnapshotForPersistence(
                        reference,
                        store,
                        npcUuid,
                        resolveRoleId(npc)
                );
        if (fullSnapshot != null) {
            fullSnapshotsByNpc.put(npcUuid, fullSnapshot);
        } else {
            fullSnapshotsByNpc.remove(npcUuid);
        }
        if (!hasProjectionIdentity(reference, store)) {
            upsertProfile(snapshot);
        }
    }

    @Nullable
    public CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot getSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return snapshotsByNpc.get(npcUuid);
    }

    /** Returns an isolated complete snapshot captured at the last live ECS boundary. */
    @Nullable
    public CoopResidentStateSnapshotService.CoopResidentStateSnapshot getFullSnapshot(UUID npcUuid) {
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                npcUuid != null ? fullSnapshotsByNpc.get(npcUuid) : null;
        return snapshot != null ? fullStateCodec.copy(snapshot) : null;
    }

    public void clearSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        snapshotsByNpc.remove(npcUuid);
        fullSnapshotsByNpc.remove(npcUuid);
    }

    private boolean hasProjectionIdentity(@Nonnull Ref<EntityStore> reference,
                                          @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
        if (markerType == null) {
            return false;
        }
        return shouldDeferProfileUpsert(store.getComponent(reference, markerType));
    }

    static boolean shouldDeferProfileUpsert(@Nullable TameworkProjectionIdentityComponent marker) {
        if (marker == null || marker.getProfileId() == null || marker.getProfileId().isBlank()
                || marker.getOperationId() == null || marker.getOperationId().isBlank()) {
            return false;
        }
        String kind = marker.getProjectionKind();
        return TameworkProjectionIdentityComponent.KIND_RECOVERY.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE.equals(kind)
                || TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION.equals(kind);
    }

    private void upsertProfile(@Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (profileRepository == null || snapshot.npcUuid() == null) {
            return;
        }
        profileRepository.upsertSnapshotAsync(new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                snapshot.ownerId(),
                snapshot.ownerName(),
                snapshot.roleId(),
                snapshot.displayName(),
                snapshot.customName(),
                snapshot.tamed(),
                null,
                null,
                null,
                snapshot.toolIds()
        ));
    }

    @Nullable
    private CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot buildSnapshot(Ref<EntityStore> npcRef,
                                                                             Store<EntityStore> store,
                                                                             NPCEntity npc) {
        if (npcRef == null || !npcRef.isValid() || store == null || npc == null || npc.getUuid() == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType != null ? store.getComponent(npcRef, linksType) : null;
        if (links == null || links.getToolIds() == null || links.getToolIds().length == 0) {
            return null;
        }
        String[] toolIds = sanitizeToolIds(links.getToolIds());
        if (toolIds.length == 0) {
            return null;
        }

        UUID ownerId = links.getOwnerId();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent ownerComponent = ownerType != null ? store.getComponent(npcRef, ownerType) : null;
        if (ownerComponent != null && ownerComponent.getOwnerId() != null) {
            ownerId = ownerComponent.getOwnerId();
        }
        String ownerName = ownerComponent != null ? ownerComponent.getOwnerName() : null;
        boolean tamed = TamedStateResolver.isTamed(npcRef, store);

        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkBreedingComponent breedingComponent = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        String breedingConfigId = breedingComponent != null ? breedingComponent.getConfigId() : null;
        Double breedingHappiness = breedingComponent != null ? breedingComponent.getHappiness() : null;
        boolean breedingEnabled = breedingComponent != null && breedingComponent.isEnabled();
        long breedingCooldownUntilMs = breedingComponent != null ? breedingComponent.getCooldownUntilMs() : 0L;
        UUID breedingLastPartnerUuid = breedingComponent != null ? breedingComponent.getLastPartnerUuid() : null;

        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happinessComponent = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        String happinessConfigId = happinessComponent != null ? happinessComponent.getConfigId() : null;
        Double happinessValue = null;
        if (happinessComponent != null) {
            happinessValue = happinessComponent.getValue();
        } else if (breedingHappiness != null) {
            happinessValue = breedingHappiness;
        }
        long happinessLastUpdateMs = happinessComponent != null
                ? happinessComponent.getLastUpdateMs()
                : breedingComponent != null
                ? breedingComponent.getLastHappinessUpdateMs()
                : 0L;

        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        TameworkTraitsComponent traitsComponent = traitsType != null ? store.getComponent(npcRef, traitsType) : null;
        String traitsConfigId = traitsComponent != null ? traitsComponent.getConfigId() : null;
        long traitsRollSeed = traitsComponent != null ? traitsComponent.getRollSeed() : 0L;
        String traitsValues = traitsComponent != null ? TraitValueCodec.encode(traitsComponent.getTraitValues()) : null;
        if (traitsValues != null && traitsValues.isBlank()) {
            traitsValues = null;
        }

        ComponentType<EntityStore, TameworkLevelingComponent> levelingType = TameworkLevelingComponent.getComponentType();
        TameworkLevelingComponent levelingComponent = levelingType != null ? store.getComponent(npcRef, levelingType) : null;
        String levelingConfigId = levelingComponent != null ? levelingComponent.getConfigId() : null;
        int levelingLevel = levelingComponent != null ? levelingComponent.getLevel() : 1;
        double levelingTotalXp = levelingComponent != null ? levelingComponent.getTotalXp() : 0.0;

        ComponentType<EntityStore, TameworkTalentsComponent> talentsType = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent talentsComponent = talentsType != null ? store.getComponent(npcRef, talentsType) : null;
        String talentsConfigId = talentsComponent != null ? talentsComponent.getConfigId() : null;
        int talentsSpentPoints = talentsComponent != null ? talentsComponent.getSpentPoints() : 0;
        String purchasedTalentIds = talentsComponent != null ? TalentIdCodec.encode(talentsComponent.getPurchasedTalentIds()) : null;

        ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageType = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent lifeStageComponent = lifeStageType != null
                ? store.getComponent(npcRef, lifeStageType)
                : null;
        String lifeStage = lifeStageComponent != null ? lifeStageComponent.getStage() : null;
        long lifeStageBornAtMs = lifeStageComponent != null ? lifeStageComponent.getBornAtMs() : 0L;
        long lifeStageAdolescentAtMs = lifeStageComponent != null ? lifeStageComponent.getAdolescentAtMs() : 0L;
        long lifeStageAdultAtMs = lifeStageComponent != null ? lifeStageComponent.getAdultAtMs() : 0L;
        long lifeStageFullyGrownAtMs = lifeStageComponent != null ? lifeStageComponent.getFullyGrownAtMs() : 0L;
        double lifeStageBabyScale = lifeStageComponent != null ? lifeStageComponent.getBabyScale() : 0.55;
        double lifeStageAdolescentScale = lifeStageComponent != null ? lifeStageComponent.getAdolescentScale() : 0.80;
        double lifeStageAdolescentSwitchScale = lifeStageComponent != null
                ? lifeStageComponent.getAdolescentSwitchScale()
                : 0.80;
        double lifeStageAdultStartScale = lifeStageComponent != null ? lifeStageComponent.getAdultStartScale() : 0.80;
        double lifeStageAdultSwitchScale = lifeStageComponent != null ? lifeStageComponent.getAdultSwitchScale() : 1.00;
        double lifeStageAdultScale = lifeStageComponent != null ? lifeStageComponent.getAdultScale() : 1.00;
        boolean lifeStageGrowthScalingEnabled = lifeStageComponent != null
                && lifeStageComponent.isGrowthScalingEnabled();
        String lifeStageGender = lifeStageComponent != null ? lifeStageComponent.getGender() : null;

        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        TameworkAttachmentsComponent attachmentsComponent = attachmentsType != null
                ? store.getComponent(npcRef, attachmentsType)
                : null;
        String attachmentsConfigId = attachmentsComponent != null ? attachmentsComponent.getConfigId() : null;
        Map<String, String> attachmentSelections = attachmentsComponent != null
                && attachmentsComponent.getAttachmentIds() != null
                && !attachmentsComponent.getAttachmentIds().isEmpty()
                ? attachmentsComponent.getAttachmentIds()
                : CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        String attachmentsValues = CommandLinkedNpcDeathService.encodeAttachmentSelections(attachmentSelections);

        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d lastKnownPosition = transform != null ? new Vector3d(transform.getPosition()) : null;
        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
        String roleId = resolveRoleId(npc);
        String customName = resolveCustomName(npcRef, store);
        String displayName = resolveDisplayName(npcRef, store, npc, roleId, customName);
        long snapshotAtMs = System.currentTimeMillis();

        return new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                npc.getUuid(),
                ownerId,
                ownerName,
                toolIds,
                roleId,
                tamed,
                customName,
                displayName,
                lastKnownPosition,
                homePosition,
                snapshotAtMs,
                snapshotAtMs,
                breedingConfigId,
                breedingHappiness,
                breedingCooldownUntilMs,
                breedingLastPartnerUuid,
                traitsConfigId,
                traitsRollSeed,
                traitsValues,
                happinessConfigId,
                happinessValue,
                happinessLastUpdateMs,
                lifeStage,
                lifeStageBornAtMs,
                lifeStageAdolescentAtMs,
                lifeStageAdultAtMs,
                lifeStageFullyGrownAtMs,
                lifeStageBabyScale,
                lifeStageAdolescentScale,
                lifeStageAdolescentSwitchScale,
                lifeStageAdultStartScale,
                lifeStageAdultSwitchScale,
                lifeStageAdultScale,
                lifeStageGrowthScalingEnabled,
                attachmentsConfigId,
                attachmentsValues,
                breedingEnabled,
                levelingConfigId,
                levelingLevel,
                levelingTotalXp,
                talentsConfigId,
                talentsSpentPoints,
                purchasedTalentIds,
                null,
                null,
                lifeStageGender
        );
    }

    @Nullable
    private String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        NPCPlugin plugin = NPCPlugin.get();
        if (roleIndex >= 0 && plugin != null) {
            String name = plugin.getName(roleIndex);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    @Nullable
    private String resolveCustomName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType == null) {
            return null;
        }
        TameworkNpcNameComponent component = store.getComponent(npcRef, nameType);
        if (component == null || component.getName() == null || component.getName().isBlank()) {
            return null;
        }
        return component.getName();
    }

    private String resolveDisplayName(Ref<EntityStore> npcRef,
                                      Store<EntityStore> store,
                                      NPCEntity npc,
                                      @Nullable String roleId,
                                      @Nullable String customName) {
        if (customName != null && !customName.isBlank()) {
            return customName;
        }
        String componentName = NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
        if (componentName != null && !componentName.isBlank()) {
            return componentName;
        }
        if (npc != null) {
            String legacy = npc.getLegacyDisplayName();
            if (legacy != null && !legacy.isBlank()) {
                return legacy;
            }
        }
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return "Companion";
    }

    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }
}
