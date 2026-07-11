package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies and resolves owner/tamed/name state on NPC entities during spawner flows.
 */
final class SpawnerNpcStateService {

    boolean applyOwner(ItemFeatureConfig config,
                       Ref<EntityStore> npcRef,
                       NPCEntity npc,
                       Ref<EntityStore> playerRef,
                       UUID ownerUuid,
                       @Nullable String capturedProfileId,
                       @Nullable UUID previousNpcUuid,
                       World world,
                       @Nullable OwnerApplyCallbacks callbacks) {
        OwnerApplyCallbacks safeCallbacks = callbacks == null ? OwnerApplyCallbacks.NOOP : callbacks;
        Store<EntityStore> store = world != null && world.getEntityStore() != null
                ? world.getEntityStore().getStore()
                : null;
        if (npc == null || npcRef == null || !npcRef.isValid() || store == null) {
            safeCallbacks.onDenied("spawner-owner-target-unavailable", null);
            return false;
        }
        OwnerMutationScheduler scheduler = resolveMutationScheduler();
        if (scheduler == null) {
            safeCallbacks.onDenied("owner-mutation-scheduler-unavailable", null);
            return false;
        }
        String ownerName = resolveOwnerName(ownerUuid, playerRef, world, store);
        UUID npcUuid = npc.getUuid();
        if (npcUuid == null) {
            safeCallbacks.onDenied("spawner-owner-uuid-unavailable", null);
            return false;
        }
        OwnerPopulationOperation operation = previousNpcUuid == null
                ? OwnerPopulationOperation.NEW_OWNERSHIP
                : OwnerPopulationOperation.RESTORE;
        OwnerMutationScheduler.MutationCallbacks mutationCallbacks = new OwnerMutationScheduler.MutationCallbacks() {
                    @Override
                    public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                        safeCallbacks.onDenied(reason, decision);
                    }

                    @Override
                    public void onPopulationDenied(@Nonnull CompanionPopulationPreparationResult result) {
                        safeCallbacks.onPopulationDenied(result);
                    }

                    @Override
                    public boolean beforeApply(@Nonnull String profileId) {
                        return safeCallbacks.beforeApply(profileId);
                    }

                    @Override
                    public void onApplyCompensated(@Nonnull String profileId, @Nonnull String reason) {
                        safeCallbacks.onApplyCompensated(profileId, reason);
                    }

                    @Override
                    public void onApplied(@Nonnull OwnerPopulationDecision decision) {
                        applyMasterTarget(config, npc, playerRef, ownerUuid, world);
                        safeCallbacks.onApplied();
                    }

                    @Override
                    public void onDurabilityDegraded(@Nonnull String reason) {
                        safeCallbacks.onDurabilityDegraded(reason);
                    }
                };
        String idempotencyKey = "spawner-spawn:"
                + (previousNpcUuid == null ? "new" : previousNpcUuid)
                + ":"
                + npcUuid;
        if (previousNpcUuid == null) {
            return scheduler.schedule(
                    npcRef,
                    store,
                    ownerUuid,
                    ownerName,
                    CompanionLifecycleState.ACTIVE,
                    operation,
                    false,
                    idempotencyKey,
                    mutationCallbacks
            );
        }
        CompanionIdentityResolver identityResolver = resolveIdentityResolver();
        String canonicalProfileId = capturedProfileId == null || capturedProfileId.isBlank()
                ? identityResolver == null
                        ? null
                        : identityResolver.resolveProfileId(previousNpcUuid).orElse(null)
                : capturedProfileId.trim();
        OwnerPopulationOperation restoreOperation = operation;
        if (canonicalProfileId == null && identityResolver != null) {
            canonicalProfileId = identityResolver.resolveOrAllocate(
                    previousNpcUuid,
                    idempotencyKey + ":legacy-item"
            ).profileId();
            restoreOperation = OwnerPopulationOperation.LEGACY_ADOPTION;
        }
        if (canonicalProfileId == null) {
            safeCallbacks.onDenied("spawner-restore-canonical-profile-unavailable", null);
            return false;
        }
        return scheduler.scheduleRestore(
                npcRef,
                store,
                canonicalProfileId,
                previousNpcUuid,
                readOwnerId(npcRef, store),
                ownerUuid,
                ownerName,
                CompanionLifecycleState.ACTIVE,
                restoreOperation,
                false,
                idempotencyKey,
                mutationCallbacks
        );
    }

    @Nullable
    private OwnerMutationScheduler resolveMutationScheduler() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerMutationScheduler();
    }

    @Nullable
    private CompanionIdentityResolver resolveIdentityResolver() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getCompanionIdentityResolver();
    }

    @Nullable
    private UUID readOwnerId(@Nonnull Ref<EntityStore> npcRef,
                             @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = type == null ? null : store.getComponent(npcRef, type);
        return owner == null ? null : owner.getOwnerId();
    }

    @Nullable
    private String resolveOwnerName(@Nullable UUID ownerUuid,
                                    @Nullable Ref<EntityStore> playerRef,
                                    @Nonnull World world,
                                    @Nonnull Store<EntityStore> store) {
        if (ownerUuid == null) {
            return null;
        }
        Player ownerPlayer = playerRef == null
                ? null
                : store.getComponent(playerRef, Player.getComponentType());
        if (ownerPlayer != null && ownerUuid.equals(ownerPlayer.getUuid())) {
            return OwnerNameUtil.resolve(ownerPlayer);
        }
        Ref<EntityStore> ownerRef = world.getEntityRef(ownerUuid);
        Player resolvedOwner = ownerRef == null || !ownerRef.isValid()
                ? null
                : store.getComponent(ownerRef, Player.getComponentType());
        return resolvedOwner == null ? null : OwnerNameUtil.resolve(resolvedOwner);
    }

    void applyMasterTarget(@Nullable ItemFeatureConfig config,
                           @Nonnull NPCEntity npc,
                           @Nullable Ref<EntityStore> playerRef,
                           @Nullable UUID ownerUuid,
                           @Nonnull World world) {
        if (config == null || !config.isSpawnAssignsOwner() || npc.getRole() == null) {
            return;
        }
        Ref<EntityStore> ownerRef = playerRef;
        if (ownerUuid != null) {
            Ref<EntityStore> resolved = world.getEntityRef(ownerUuid);
            if (resolved != null && resolved.isValid()) {
                ownerRef = resolved;
            }
        }
        if (ownerRef != null && ownerRef.isValid()) {
            npc.getRole().setMarkedTarget("MasterTarget", ownerRef);
        }
    }

    void applyTamed(Ref<EntityStore> npcRef, boolean tamed, World world) {
        if (npcRef == null || !npcRef.isValid() || world == null) {
            return;
        }
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.putComponent(npcRef, type, new TameworkTamedComponent(tamed));
        if (tamed) {
            CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        }
    }

    void applyCapturedName(ItemStack itemStack, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (itemStack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        String name = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME, Codec.STRING);
        if (name == null || name.isBlank()) {
            return;
        }
        UUID ownerId = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_OWNER_UUID, Codec.UUID_STRING);
        Long updatedMs = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_UPDATED_MS, Codec.LONG);
        String sourceRaw = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.NPC_NAME_SOURCE, Codec.STRING);
        TameworkNpcNameComponent.NameSource source = parseNameSource(sourceRaw);
        if (source == null) {
            source = TameworkNpcNameComponent.NameSource.Player;
        }
        long resolvedUpdatedMs = (updatedMs != null && updatedMs > 0) ? updatedMs : System.currentTimeMillis();
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            store.putComponent(npcRef, nameType, new TameworkNpcNameComponent(name, ownerId, resolvedUpdatedMs, source));
        }
        EntitySupport.setDisplayName(npcRef, name, store);
    }

    boolean resolveTamedState(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        return TamedStateResolver.isTamed(targetRef, store);
    }

    UUID resolveOwnerFromComponent(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkOwnerComponent owner = type != null ? store.getComponent(targetRef, type) : null;
        if (owner != null && owner.getOwnerId() != null) {
            return owner.getOwnerId();
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType != null ? store.getComponent(targetRef, linksType) : null;
        return links != null ? links.getOwnerId() : null;
    }

    String resolveOwnerNameFromComponent(Ref<EntityStore> targetRef, World world) {
        if (targetRef == null || world == null || !targetRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        TameworkOwnerComponent owner = store.getComponent(targetRef, type);
        if (owner == null || owner.getOwnerName() == null || owner.getOwnerName().isBlank()) {
            return null;
        }
        return owner.getOwnerName();
    }

    private TameworkNpcNameComponent.NameSource parseNameSource(String sourceRaw) {
        if (sourceRaw == null || sourceRaw.isBlank()) {
            return null;
        }
        try {
            return TameworkNpcNameComponent.NameSource.valueOf(sourceRaw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    interface OwnerApplyCallbacks {
        OwnerApplyCallbacks NOOP = new OwnerApplyCallbacks() {
        };

        default void onApplied() {
        }

        default boolean beforeApply(@Nonnull String profileId) {
            return true;
        }

        default void onApplyCompensated(@Nonnull String profileId, @Nonnull String reason) {
        }

        default void onPopulationDenied(@Nonnull CompanionPopulationPreparationResult result) {
        }

        default void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
        }

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }
}
