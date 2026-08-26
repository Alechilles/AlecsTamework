package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.compat.NpcMarkedTargetAccess;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Spawns owned+tamed NPC batches for commands and optionally links them to the held command item.
 */
public final class NpcSpawnCommandService {
    private static final double SPAWN_RING_RADIUS_STEP = 0.9;
    private static final int SPAWN_RING_SIZE = 6;

    private final SpawnerSpawnPositionService spawnPositionService;
    private final SpawnerAttachmentService attachmentService;
    private final CommandLinkedNpcRecordStore linkedNpcRecordStore;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkPolicyService linkPolicyService;
    private final NpcSpawnAttachmentResolutionService attachmentResolutionService;
    private final Tamework plugin;
    @Nullable
    private final PopulationAdmissionApi populationAdmissions;
    @Nullable
    private final CommandLinkedNpcStateSnapshotService profileSnapshots;

    public NpcSpawnCommandService(@Nonnull Tamework plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.spawnPositionService = new SpawnerSpawnPositionService(plugin.getLogger());
        this.attachmentService = new SpawnerAttachmentService(plugin.getLogger());
        this.linkedNpcRecordStore = new CommandLinkedNpcRecordStore();
        this.npcNameResolver = new CommandNpcNameResolver();
        this.linkPolicyService = new CommandLinkPolicyService();
        this.attachmentResolutionService = new NpcSpawnAttachmentResolutionService();
        this.populationAdmissions = plugin.getApi() == null
                || plugin.getApi().policies() == null
                ? null : plugin.getApi().policies().populationAdmissions();
        this.profileSnapshots =
                plugin.getCommandLinkedNpcStateSnapshotService();
    }

    public void spawnTamedOwnedBatch(@Nonnull Player player,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> playerRef,
                                     @Nonnull World world,
                                     @Nonnull String roleId,
                                     int quantity,
                                     @Nullable Double spawnRadius,
                                     @Nullable Map<String, String> attachmentOverrides,
                                     @Nonnull Consumer<SpawnBatchResult> completion) {
        Objects.requireNonNull(completion, "completion");
        SpawnPreparation preparation = prepareSpawn(player, roleId, quantity);
        if (preparation.failure() != null) {
            completion.accept(SpawnBatchResult.failure(preparation.failure()));
            return;
        }
        BatchTracker tracker = newBatchTracker(quantity, player, completion);
        for (int index = 0; index < quantity; index++) {
            if (!spawnOne(
                    player, store, playerRef, world, preparation,
                    offsetSpawnPosition(preparation.base(), index, spawnRadius),
                    attachmentOverrides, tracker
            )) {
                break;
            }
        }
        tracker.seal();
    }

    @Nonnull
    private SpawnPreparation prepareSpawn(Player player, String roleId, int quantity) {
        if (quantity <= 0) {
            return SpawnPreparation.failure("Quantity must be greater than zero.");
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return SpawnPreparation.failure("NPC plugin not available.");
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            return SpawnPreparation.failure("Unknown role '" + roleId + "'.");
        }
        UUID ownerId = player.getUuid();
        if (ownerId == null) {
            return SpawnPreparation.failure("Player UUID not available.");
        }
        Vector3d base = spawnPositionService.resolveSpawnPosition(player, null);
        return base == null
                ? SpawnPreparation.failure("Unable to resolve a spawn position.")
                : new SpawnPreparation(
                        npcPlugin, roleIndex, roleId.trim(), ownerId, base, null
                );
    }

    private boolean spawnOne(
            Player player,
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            World world,
            SpawnPreparation preparation,
            Vector3d position,
            @Nullable Map<String, String> attachmentOverrides,
            BatchTracker tracker
    ) {
        ManagedActivityConfigRegistry.RoleResolution managed = plugin
                .getManagedActivityConfigRegistry()
                .resolveRole(preparation.roleId())
                .orElse(null);
        if (managed == null) {
            OwnerPopulationCapService.Decision cap =
                    OwnerPopulationCapService.evaluateAcquisition(
                            store, preparation.ownerId()
                    );
            if (!cap.allowed()) {
                OwnerMessageUtil.sendPopulationCapReached(
                        player, cap.currentCount(), cap.limit(), cap.scope()
                );
                tracker.stop("Owner population cap reached.");
                return false;
            }
        }
        Rotation3f rotation = spawnPositionService.resolveSpawnRotation(
                store, playerRef, position
        );
        var spawned = preparation.npcPlugin().spawnEntity(
                store, preparation.roleIndex(), position, rotation, null, null
        );
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            tracker.stop("Spawn failed before completing the requested quantity.");
            return false;
        }
        if (managed != null) {
            return prepareManagedSpawn(
                    store,
                    world,
                    spawned.first(),
                    spawned.second(),
                    preparation,
                    position,
                    attachmentOverrides,
                    tracker,
                    managed
            );
        }
        AppliedSpawn applied = applySpawnedState(
                player,
                store,
                playerRef,
                world,
                spawned.first(),
                spawned.second(),
                preparation.ownerId(),
                attachmentOverrides
        );
        if (applied == null) {
            tracker.stop("Spawn failed while applying owned companion state.");
            return false;
        }
        tracker.register(applied.attachments());
        tracker.applied(applied.linked(), applied.attachments());
        return true;
    }

    @Nullable
    private AppliedSpawn applySpawnedState(
            Player player,
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            World world,
            Ref<EntityStore> npcRef,
            NPCEntity npc,
            UUID ownerId,
            @Nullable Map<String, String> attachmentOverrides
    ) {
        var ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            npc.setToDespawn();
            return null;
        }
        store.putComponent(
                npcRef,
                ownerType,
                new TameworkOwnerComponent(ownerId, OwnerNameUtil.resolve(player))
        );
        AttachmentResolution resolution =
                resolveAttachmentOverrides(npcRef, store, attachmentOverrides);
        applyPostAdmissionState(store, world, playerRef, npcRef, npc, resolution);
        AutoLinkContext autoLink = resolveHeldCommandItem(player);
        boolean linked = linkHeldCommandItem(
                autoLink, player, store, npcRef, npc
        );
        if (autoLink != null && autoLink.changed
                && !updateHeldItem(autoLink)) {
            linked = false;
        }
        return new AppliedSpawn(linked, resolution);
    }

    private boolean prepareManagedSpawn(
            Store<EntityStore> store,
            World world,
            Ref<EntityStore> npcRef,
            NPCEntity npc,
            SpawnPreparation preparation,
            Vector3d position,
            @Nullable Map<String, String> attachmentOverrides,
            BatchTracker tracker,
            ManagedActivityConfigRegistry.RoleResolution managed
    ) {
        UUID npcUuid = npc.getUuid();
        var markerType = TameworkProjectionIdentityComponent.getComponentType();
        if (populationAdmissions == null || profileSnapshots == null
                || npcUuid == null || markerType == null
                || world.getName() == null || world.getName().isBlank()) {
            npc.setToDespawn();
            tracker.stop("Managed population admission is unavailable.");
            return false;
        }
        UUID commandSpawnId = UUID.randomUUID();
        store.putComponent(
                npcRef,
                markerType,
                new TameworkProjectionIdentityComponent(
                        npcUuid.toString(),
                        commandSpawnId.toString(),
                        TameworkProjectionIdentityComponent.KIND_ADMIN_FORCE,
                        null,
                        null,
                        0L
                )
        );
        tracker.register(null);
        PopulationAdmissionRequestV3 request = managedRequest(
                npcUuid,
                commandSpawnId,
                preparation,
                world,
                position,
                managed
        );
        CompletionStage<PopulationAdmissionDecision> prepared;
        try {
            prepared = populationAdmissions.tryAdmitV3(request);
        } catch (RuntimeException | LinkageError failure) {
            npc.setToDespawn();
            tracker.denied("Managed population admission failed.");
            warnManagedSpawn("prepare_failed", npcUuid, failure);
            return true;
        }
        if (prepared == null) {
            npc.setToDespawn();
            tracker.denied("Managed population admission failed.");
            return true;
        }
        prepared.whenComplete((decision, failure) -> {
            if (failure != null || decision == null
                    || !decision.accepted() || decision.token() == null) {
                finishManagedFailure(
                        world,
                        npcUuid,
                        tracker,
                        "Managed population admission was not accepted.",
                        failure
                );
                return;
            }
            dispatchManagedApply(
                    world,
                    npcUuid,
                    preparation.ownerId(),
                    attachmentOverrides,
                    tracker,
                    decision.token()
            );
        });
        return true;
    }

    private PopulationAdmissionRequestV3 managedRequest(
            UUID npcUuid,
            UUID commandSpawnId,
            SpawnPreparation preparation,
            World world,
            Vector3d position,
            ManagedActivityConfigRegistry.RoleResolution managed
    ) {
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        null,
                        npcUuid.toString(),
                        "admin-spawn:" + commandSpawnId
                ),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                preparation.ownerId(),
                null,
                new PopulationAdmissionLocation(
                        world.getName(),
                        ChunkUtil.chunkCoordinate((int) Math.floor(position.x)),
                        ChunkUtil.chunkCoordinate((int) Math.floor(position.z))
                ),
                PopulationAdmissionOperation.ADMIN_FORCE,
                1,
                PopulationAdmissionForcePolicy.ADMIN_OVERRIDE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return new PopulationAdmissionRequestV3(
                new PopulationAdmissionRequestV2(
                        admission,
                        preparation.roleId(),
                        world.getName()
                ),
                managed.profile().profileId()
        );
    }

    private void dispatchManagedApply(
            World world,
            UUID npcUuid,
            UUID ownerId,
            @Nullable Map<String, String> attachmentOverrides,
            BatchTracker tracker,
            PopulationAdmissionToken token
    ) {
        try {
            world.execute(() -> {
                World current = Universe.get().getWorld(world.getName());
                if (current != world || !world.isAlive()
                        || world.getEntityStore() == null) {
                    tracker.abandonWithoutCompletion(
                            "World closed during managed spawn admission."
                    );
                    return;
                }
                Store<EntityStore> currentStore =
                        world.getEntityStore().getStore();
                Ref<EntityStore> currentPlayerRef =
                        world.getEntityRef(ownerId);
                Player currentPlayer = currentPlayerRef == null
                        ? null : currentStore.getComponent(
                                currentPlayerRef, Player.getComponentType()
                        );
                Ref<EntityStore> currentRef = world.getEntityRef(npcUuid);
                NPCEntity currentNpc = currentRef == null
                        ? null : currentStore.getComponent(
                                currentRef, NPCEntity.getComponentType()
                        );
                if (currentPlayerRef == null || !currentPlayerRef.isValid()
                        || currentPlayer == null
                        || currentRef == null || !currentRef.isValid()
                        || currentNpc == null) {
                    cancelUnused(token);
                    tracker.denied("Spawned NPC was no longer available.");
                    return;
                }
                PopulationAdmissionDecision claim =
                        populationAdmissions.claimForApply(token);
                if (claim.status()
                        != PopulationAdmissionDecision.Status.APPLYING) {
                    currentNpc.setToDespawn();
                    cancelUnused(token);
                    tracker.denied("Managed population admission could not be claimed.");
                    return;
                }
                var markerType =
                        TameworkProjectionIdentityComponent.getComponentType();
                if (markerType != null) {
                    currentStore.putComponent(
                            currentRef,
                            markerType,
                            new TameworkProjectionIdentityComponent(
                                    npcUuid.toString(),
                                    token.operationId().toString(),
                                    TameworkProjectionIdentityComponent
                                            .KIND_ADMIN_FORCE,
                                    null,
                                    null,
                                    0L
                            )
                    );
                }
                AppliedSpawn applied = applySpawnedState(
                        currentPlayer,
                        currentStore,
                        currentPlayerRef,
                        world,
                        currentRef,
                        currentNpc,
                        ownerId,
                        attachmentOverrides
                );
                if (applied == null) {
                    currentNpc.setToDespawn();
                    tracker.denied(
                            "Spawn failed while applying owned companion state."
                    );
                    return;
                }
                publishAndCommitManagedSpawn(
                        world,
                        currentRef,
                        currentStore,
                        npcUuid,
                        tracker,
                        token,
                        applied
                );
            });
        } catch (RuntimeException | LinkageError failure) {
            tracker.abandonWithoutCompletion(
                    "World dispatch failed during managed spawn admission."
            );
            warnManagedSpawn("world_dispatch_failed", npcUuid, failure);
        }
    }

    private void publishAndCommitManagedSpawn(
            World world,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            UUID npcUuid,
            BatchTracker tracker,
            PopulationAdmissionToken token,
            AppliedSpawn applied
    ) {
        CompletionStage<Void> profile;
        try {
            profile = profileSnapshots.publishAdminSpawnProfile(npcRef, store);
        } catch (RuntimeException | LinkageError failure) {
            finishManagedFailure(
                    world,
                    npcUuid,
                    tracker,
                    "Managed profile publication failed.",
                    failure
            );
            return;
        }
        profile.whenComplete((ignored, profileFailure) -> {
            if (profileFailure != null) {
                finishManagedFailure(
                        world,
                        npcUuid,
                        tracker,
                        "Managed profile publication failed.",
                        profileFailure
                );
                return;
            }
            CompletionStage<PopulationAdmissionDecision> committed;
            try {
                committed = populationAdmissions.commit(token);
            } catch (RuntimeException | LinkageError failure) {
                finishManagedFailure(
                        world,
                        npcUuid,
                        tracker,
                        "Managed population admission did not commit.",
                        failure
                );
                return;
            }
            committed.whenComplete((decision, commitFailure) -> {
                if (commitFailure != null || decision == null
                        || decision.status()
                        != PopulationAdmissionDecision.Status.COMMITTED) {
                    finishManagedFailure(
                            world,
                            npcUuid,
                            tracker,
                            "Managed population admission did not commit.",
                            commitFailure
                    );
                    return;
                }
                finishManagedSuccess(world, tracker, applied);
            });
        });
    }

    private void finishManagedSuccess(
            World world,
            BatchTracker tracker,
            AppliedSpawn applied
    ) {
        dispatchCompletion(world, tracker, () -> tracker.applied(
                applied.linked(), applied.attachments()
        ));
    }

    private void finishManagedFailure(
            World world,
            UUID npcUuid,
            BatchTracker tracker,
            String reason,
            @Nullable Throwable failure
    ) {
        warnManagedSpawn("settlement_failed", npcUuid, failure);
        dispatchCompletion(world, tracker, () -> {
            Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
            if (npcRef != null && npcRef.isValid()) {
                NPCEntity npc = world.getEntityStore().getStore().getComponent(
                        npcRef, NPCEntity.getComponentType()
                );
                if (npc != null) {
                    npc.setToDespawn();
                }
            }
            tracker.denied(reason);
        });
    }

    private void dispatchCompletion(
            World world,
            BatchTracker tracker,
            Runnable completion
    ) {
        try {
            world.execute(() -> {
                World current = Universe.get().getWorld(world.getName());
                if (current != world || !world.isAlive()
                        || world.getEntityStore() == null) {
                    tracker.abandonWithoutCompletion(
                            "World closed during managed spawn settlement."
                    );
                    return;
                }
                completion.run();
            });
        } catch (RuntimeException | LinkageError failure) {
            tracker.abandonWithoutCompletion(
                    "World dispatch failed during managed spawn settlement."
            );
        }
    }

    private void cancelUnused(PopulationAdmissionToken token) {
        try {
            populationAdmissions.cancel(token);
        } catch (RuntimeException | LinkageError ignored) {
            // Expiry cleanup owns any unused token that cannot be canceled now.
        }
    }

    private void warnManagedSpawn(
            String detail,
            UUID npcUuid,
            @Nullable Throwable failure
    ) {
        String message = "Managed admin spawn " + detail + " (npc="
                + npcUuid + ").";
        if (failure == null) {
            plugin.getLogger().at(Level.WARNING).log(message);
        } else {
            plugin.getLogger().at(Level.WARNING).withCause(failure).log(message);
        }
    }

    @Nonnull
    private static Vector3d offsetSpawnPosition(
            Vector3d base, int spawnIndex, @Nullable Double spawnRadius
    ) {
        if (spawnIndex <= 0) {
            return new Vector3d(base);
        }
        int ring = (spawnIndex - 1) / SPAWN_RING_SIZE + 1;
        int ringSlot = (spawnIndex - 1) % SPAWN_RING_SIZE;
        double radius = spawnRadius == null
                ? ring * SPAWN_RING_RADIUS_STEP
                : Math.min(spawnRadius, Math.max(
                        SPAWN_RING_RADIUS_STEP, ring * SPAWN_RING_RADIUS_STEP
                ));
        double angle = (Math.PI * 2.0 * ringSlot) / SPAWN_RING_SIZE;
        return new Vector3d(
                base.x + Math.cos(angle) * radius,
                base.y,
                base.z + Math.sin(angle) * radius
        );
    }

    @Nullable
    AttachmentResolution resolveAttachmentOverrides(@Nonnull Ref<EntityStore> npcRef,
                                                    @Nonnull Store<EntityStore> store,
                                                    @Nullable Map<String, String> requestedSelections) {
        NpcSpawnAttachmentResolutionService.Resolution resolution =
                attachmentResolutionService.resolve(npcRef, store, requestedSelections);
        return resolution == null ? null : new AttachmentResolution(
                resolution.applied(), resolution.invalid()
        );
    }

    void applyPostAdmissionState(Store<EntityStore> store,
                                 World world,
                                 Ref<EntityStore> playerRef,
                                 Ref<EntityStore> npcRef,
                                 NPCEntity npc,
                                 @Nullable AttachmentResolution attachmentResolution) {
        if (TameworkTamedComponent.getComponentType() != null) {
            store.putComponent(npcRef, TameworkTamedComponent.getComponentType(), new TameworkTamedComponent(true));
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        if (attachmentResolution != null && !attachmentResolution.appliedSelections.isEmpty()) {
            attachmentService.applyAttachments(attachmentResolution.appliedSelections, npcRef, npc, store);
        }
        Ref<EntityStore> masterRef = playerRef;
        if (npc.getRole() != null) {
            NpcMarkedTargetAccess.set(
                    npc.getRole(),
                    npcRef,
                    store,
                    "MasterTarget",
                    masterRef
            );
        }
    }

    @Nullable
    private AutoLinkContext resolveHeldCommandItem(Player player) {
        Tamework plugin = Tamework.getInstance();
        CommandItemRegistry registry = plugin != null ? plugin.getCommandItemRegistry() : null;
        if (registry == null) {
            return null;
        }

        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        if (hotbar == null) {
            return null;
        }
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        if (activeSlot < 0) {
            return null;
        }

        ItemStack heldStack = hotbar.getItemStack((short) activeSlot);
        if (heldStack == null || heldStack.isEmpty()) {
            return null;
        }
        ItemStack originalStack = heldStack;

        TwCommandItemConfig config = registry.get(heldStack.getItemId());
        if (config == null || config.usesBondedCompanionRoster()
                || !config.isEnabled() || !config.isLinkEnabled()) {
            return null;
        }

        String toolId = heldStack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
        boolean changed = false;
        if (toolId == null || toolId.isBlank()) {
            toolId = UUID.randomUUID().toString();
            heldStack = heldStack.withMetadata(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, toolId);
            changed = true;
        }
        if (toolId.isBlank()) {
            return null;
        }

        return new AutoLinkContext(
                heldStack,
                originalStack,
                hotbar,
                (short) activeSlot,
                toolId,
                config,
                changed
        );
    }

    boolean linkHeldCommandItem(@Nullable AutoLinkContext context,
                               Player player,
                               Store<EntityStore> store,
                               Ref<EntityStore> npcRef,
                               NPCEntity npc) {
        if (context == null || npcRef == null || !npcRef.isValid() || npc == null) {
            return false;
        }
        String roleId = linkPolicyService.resolveRoleId(npc);
        if (!linkPolicyService.isRoleAllowed(roleId, context.config)) {
            return false;
        }
        if (context.config.isRequireTamed() && !TamedStateResolver.isTamed(npcRef, store)) {
            return false;
        }

        UUID ownerUuid = player.getUuid();
        boolean requireOwner = resolveLinkingRequireOwner();
        TameworkCommandLinksComponent current = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (current == null) {
            current = new TameworkCommandLinksComponent(ownerUuid, new String[0]);
        }
        if (requireOwner && current.getOwnerId() != null && !current.getOwnerId().equals(ownerUuid)) {
            return false;
        }

        TameworkCommandLinksComponent updated = current.containsToolId(context.toolId)
                ? current
                : current.withToolIdAdded(context.toolId);
        updated.setOwnerId(ownerUuid);
        store.putComponent(npcRef, TameworkCommandLinksComponent.getComponentType(), updated);

        UUID npcUuid = npc.getUuid();
        if (npcUuid == null) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d lastKnown = transform != null ? new Vector3d(transform.getPosition()) : null;
        boolean activate = shouldActivateOnLink(context.stack, context.config);
        context.stack = linkedNpcRecordStore.upsert(
                context.stack,
                npcUuid,
                lastKnown,
                resolveWorldName(player),
                updated.hasHome() ? updated.getHomePosition() : null,
                npcNameResolver.resolveNpcDisplayNameFromComponents(npcRef, store),
                npcNameResolver.resolveNpcNameKey(npc),
                roleId,
                activate,
                resolveCachedCommandState(npc, npcRef, store)
        );
        context.changed = true;
        return true;
    }

    private boolean shouldActivateOnLink(ItemStack stack, TwCommandItemConfig config) {
        int maxActive = config != null ? Math.max(0, config.getMaxActive()) : 0;
        if (maxActive <= 0) {
            return true;
        }
        List<LinkedNpcRecord> records = linkedNpcRecordStore.read(stack);
        int activeCount = 0;
        for (LinkedNpcRecord record : records) {
            if (record != null && record.active) {
                activeCount++;
            }
        }
        return activeCount < maxActive;
    }

    @Nullable
    private String resolveWorldName(Player player) {
        World world = player != null ? player.getWorld() : null;
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        return world.getName();
    }

    private boolean resolveLinkingRequireOwner() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = config != null ? config : TwGlobalConfig.defaultConfig();
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }

    @Nullable
    private String resolveCachedCommandState(NPCEntity npc,
                                             Ref<EntityStore> npcRef,
                                             Store<EntityStore> store) {
        if (npc == null || npc.getRole() == null || npcRef == null || store == null) {
            return null;
        }
        StateSupport stateSupport = NpcSupportAccess.state(npc.getRole(), npcRef, store);
        if (stateSupport == null) {
            return null;
        }
        String stateName = stateSupport.getStateName();
        return (stateName != null && !stateName.isBlank()) ? stateName : null;
    }

    private boolean updateHeldItem(AutoLinkContext context) {
        if (context == null || !Objects.equals(
                context.hotbar.getItemStack(context.slot),
                context.originalStack
        )) {
            return false;
        }
        context.hotbar.setItemStackForSlot(context.slot, context.stack);
        return true;
    }

    static final class AutoLinkContext {
        private ItemStack stack;
        private final ItemStack originalStack;
        private final ItemContainer hotbar;
        private final short slot;
        private final String toolId;
        private final TwCommandItemConfig config;
        private boolean changed;

        private AutoLinkContext(ItemStack stack,
                                ItemStack originalStack,
                                ItemContainer hotbar,
                                short slot,
                                String toolId,
                                TwCommandItemConfig config,
                                boolean changed) {
            this.stack = stack;
            this.originalStack = originalStack;
            this.hotbar = hotbar;
            this.slot = slot;
            this.toolId = toolId;
            this.config = config;
            this.changed = changed;
        }
    }

    BatchTracker newBatchTracker(int requestedCount,
                                 Player player,
                                 Consumer<SpawnBatchResult> completion) {
        return new BatchTracker(
                requestedCount,
                resolveHeldCommandItem(player) != null,
                completion
        );
    }

    final class BatchTracker {
        private final int requestedCount;
        private final boolean hadHeldCommandItem;
        private final Consumer<SpawnBatchResult> completion;
        private int pendingCount;
        private int spawnedCount;
        private int linkedCount;
        private boolean sealed;
        private boolean completed;
        @Nullable
        private String stoppedReason;
        @Nullable
        private AttachmentResolution attachmentResolution;

        private BatchTracker(int requestedCount,
                             boolean hadHeldCommandItem,
                             Consumer<SpawnBatchResult> completion) {
            this.requestedCount = requestedCount;
            this.hadHeldCommandItem = hadHeldCommandItem;
            this.completion = completion;
        }

        synchronized void register(@Nullable AttachmentResolution resolution) {
            pendingCount++;
            if (attachmentResolution == null && resolution != null) {
                attachmentResolution = resolution;
            }
        }

        synchronized void stop(@Nonnull String reason) {
            if (stoppedReason == null) {
                stoppedReason = reason;
            }
        }

        synchronized void denied(@Nonnull String reason) {
            stop(reason);
            pendingCount = Math.max(0, pendingCount - 1);
            finishIfReady();
        }

        synchronized void applied(boolean linked, @Nullable AttachmentResolution resolution) {
            if (attachmentResolution == null && resolution != null) {
                attachmentResolution = resolution;
            }
            spawnedCount++;
            if (linked) {
                linkedCount++;
            }
            pendingCount = Math.max(0, pendingCount - 1);
            finishIfReady();
        }

        synchronized void durabilityDegraded(@Nonnull String reason) {
            if (!completed && stoppedReason == null) {
                stoppedReason = "Ownership durability degraded: " + reason + ".";
            }
        }

        /** Closes an abandoned world callback without invoking player-facing completion off-thread. */
        synchronized void abandonWithoutCompletion(@Nonnull String reason) {
            if (completed) {
                return;
            }
            stop(reason);
            pendingCount = 0;
            sealed = true;
            completed = true;
        }

        synchronized void seal() {
            sealed = true;
            finishIfReady();
        }

        private void finishIfReady() {
            if (!sealed || pendingCount > 0 || completed) {
                return;
            }
            completed = true;
            completion.accept(new SpawnBatchResult(
                    null,
                    requestedCount,
                    spawnedCount,
                    linkedCount,
                    hadHeldCommandItem,
                    stoppedReason,
                    attachmentResolution == null ? null : attachmentResolution.appliedSelections,
                    attachmentResolution == null ? List.of() : attachmentResolution.invalidSelections
            ));
        }

    }

    public static final class SpawnBatchResult {
        @Nullable
        private final String failureMessage;
        private final int requestedCount;
        private final int spawnedCount;
        private final int linkedCount;
        private final boolean hadHeldCommandItem;
        @Nullable
        private final String stoppedReason;
        @Nullable
        private final Map<String, String> appliedAttachments;
        @Nonnull
        private final List<String> invalidAttachments;

        SpawnBatchResult(@Nullable String failureMessage,
                                 int requestedCount,
                                 int spawnedCount,
                                 int linkedCount,
                                 boolean hadHeldCommandItem,
                                 @Nullable String stoppedReason,
                                 @Nullable Map<String, String> appliedAttachments,
                                 @Nullable List<String> invalidAttachments) {
            this.failureMessage = failureMessage;
            this.requestedCount = requestedCount;
            this.spawnedCount = spawnedCount;
            this.linkedCount = linkedCount;
            this.hadHeldCommandItem = hadHeldCommandItem;
            this.stoppedReason = stoppedReason;
            this.appliedAttachments = appliedAttachments;
            this.invalidAttachments = invalidAttachments != null ? List.copyOf(invalidAttachments) : List.of();
        }

        @Nonnull
        static SpawnBatchResult failure(@Nonnull String failureMessage) {
            return new SpawnBatchResult(failureMessage, 0, 0, 0, false, null, null, List.of());
        }

        @Nullable
        public String getFailureMessage() {
            return failureMessage;
        }

        public int getRequestedCount() {
            return requestedCount;
        }

        public int getSpawnedCount() {
            return spawnedCount;
        }

        public int getLinkedCount() {
            return linkedCount;
        }

        public boolean hadHeldCommandItem() {
            return hadHeldCommandItem;
        }

        @Nullable
        public String getStoppedReason() {
            return stoppedReason;
        }

        @Nullable
        public Map<String, String> getAppliedAttachments() {
            return appliedAttachments;
        }

        @Nonnull
        public List<String> getInvalidAttachments() {
            return invalidAttachments;
        }
    }

    static final class AttachmentResolution {
        private final Map<String, String> appliedSelections;
        private final List<String> invalidSelections;

        private AttachmentResolution(Map<String, String> appliedSelections, List<String> invalidSelections) {
            this.appliedSelections = appliedSelections != null ? Map.copyOf(appliedSelections) : Map.of();
            this.invalidSelections = invalidSelections != null ? List.copyOf(invalidSelections) : List.of();
        }
    }

    private record SpawnPreparation(
            @Nullable NPCPlugin npcPlugin,
            int roleIndex,
            @Nullable String roleId,
            @Nullable UUID ownerId,
            @Nullable Vector3d base,
            @Nullable String failure
    ) {
        @Nonnull
        private static SpawnPreparation failure(@Nonnull String reason) {
            return new SpawnPreparation(
                    null, -1, null, null, null, reason
            );
        }
    }

    private record AppliedSpawn(
            boolean linked,
            @Nullable AttachmentResolution attachments
    ) {
    }
}
