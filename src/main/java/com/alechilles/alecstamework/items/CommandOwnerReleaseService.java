package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.UUID;
import javax.annotation.Nullable;

/** Commits permanent release, then applies live cleanup on the current world thread. */
final class CommandOwnerReleaseService {
    private static final float RELEASE_DESPAWN_DELAY_SECONDS = 4.0F;
    private static final String[] RELEASE_STATE_CANDIDATES = new String[] { "Flee", "Wander", "Idle" };

    private final CommandLinkPolicyService linkPolicyService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    @Nullable private final PersistenceDomainFacades persistence;
    @Nullable private final CommandLinkedNpcInventoryRepairService inventoryRepair;
    private final CommandUiHostPage.WorldDispatcher worldDispatcher;

    CommandOwnerReleaseService(CommandLinkPolicyService linkPolicyService,
                               CommandStepExecutionService stepExecutionService,
                               CommandFeedbackService feedbackService,
                               CommandNpcNameResolver npcNameResolver) {
        this(linkPolicyService, stepExecutionService, feedbackService,
                npcNameResolver, null, null,
                CommandUiCurrentWorldDispatcher.production());
    }

    CommandOwnerReleaseService(
            CommandLinkPolicyService linkPolicyService,
            CommandStepExecutionService stepExecutionService,
            CommandFeedbackService feedbackService,
            CommandNpcNameResolver npcNameResolver,
            @Nullable PersistenceDomainFacades persistence,
            @Nullable CommandLinkedNpcInventoryRepairService inventoryRepair,
            CommandUiHostPage.WorldDispatcher worldDispatcher
    ) {
        this.linkPolicyService = linkPolicyService;
        this.stepExecutionService = stepExecutionService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
        this.persistence = persistence;
        this.inventoryRepair = inventoryRepair;
        this.worldDispatcher = worldDispatcher;
    }

    void release(Player player,
                 String toolId,
                 TwCommandItemConfig config,
                 UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        UUID ownerUuid = player.getUuid();
        if (ownerUuid == null) {
            return;
        }
        if (persistence == null) {
            releaseLive(player, config, npcUuid);
            return;
        }
        findProfile(npcUuid)
                .whenComplete((read, failure) -> completeRead(
                        ownerUuid, npcUuid, read, failure
                ));
    }

    private CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
    findProfile(UUID npcUuid) {
        return persistence.queries().findProfile(new NpcAlias(npcUuid))
                .thenCompose(result -> result instanceof PersistenceReadResult.Absent<?>
                        ? persistence.queries().findProfile(new ProfileId(npcUuid))
                        : CompletableFuture.completedFuture(result));
    }

    private void completeRead(
            UUID ownerUuid,
            UUID requestedAlias,
            PersistenceReadResult<CompanionProfileReadModel> read,
            Throwable failure
    ) {
        if (failure != null || !(read instanceof PersistenceReadResult.Found<?>)) {
            warnUnavailable(ownerUuid);
            return;
        }
        @SuppressWarnings("unchecked")
        var found = (PersistenceReadResult.Found<CompanionProfileReadModel>) read;
        var profile = found.value();
        var lifecycle = profile.lifecycle();
        if (lifecycle.ownerId() == null
                || !ownerUuid.equals(lifecycle.ownerId().value())
                || lifecycle.state() == LifecycleState.RELEASED
                || lifecycle.state() == LifecycleState.CAPTURED
                || lifecycle.state() == LifecycleState.COOP) {
            warnUnavailable(ownerUuid);
            return;
        }
        String operationKey = "command-permanent-release:"
                + lifecycle.profileId() + ":" + lifecycle.revision();
        OperationId operationId = new OperationId(UUID.nameUUIDFromBytes(
                operationKey.getBytes(StandardCharsets.UTF_8)
        ));
        var submission = persistence.operations().transitionOwnerPopulation(
                operationId,
                new IdempotencyKey(operationKey),
                new OwnerPopulationTransitionRequest(
                        lifecycle.profileId(),
                        lifecycle.revision(),
                        lifecycle.ownerId(),
                        lifecycle.ownerWorldKey(),
                        null,
                        null,
                        0,
                        0,
                        lifecycle.stateChangedAtMs() + 1L
                )
        );
        if (!submission.accepted()) {
            warnUnavailable(ownerUuid);
            return;
        }
        UUID liveAlias = profile.currentAlias() == null
                ? requestedAlias : profile.currentAlias().alias().value();
        String displayName = profile.identity().displayName();
        submission.completion().whenComplete((result, submitFailure) -> {
            if (submitFailure != null || result == null
                    || result.status() != OperationWorkflowResult.Status.PUBLISHED) {
                warnUnavailable(ownerUuid);
                return;
            }
            dispatch(ownerUuid, current -> {
                releaseLiveEntity(current, liveAlias);
                if (inventoryRepair != null) {
                    inventoryRepair.canonicalize(current);
                }
                feedbackService.showSuccessKey(
                        current,
                        "tamework.ui.notifications.command.release.success",
                        displayName == null || displayName.isBlank()
                                ? LocalizedText.resolve(current,
                                "tamework.ui.notifications.command.shared.defaultMobName")
                                : displayName
                );
            });
        });
    }

    private void warnUnavailable(UUID ownerUuid) {
        dispatch(ownerUuid, current -> feedbackService.showWarningKey(
                current, "tamework.ui.notifications.command.release.unavailable"
        ));
    }

    private void dispatch(UUID ownerUuid, Consumer<Player> action) {
        worldDispatcher.dispatch(ownerUuid, new CommandUiHostPage.WorldOperation() {
            @Override
            public void run(Ref<EntityStore> playerRef, Store<EntityStore> store) {
                Player current = playerRef == null || !playerRef.isValid()
                        || store == null ? null
                        : store.getComponent(playerRef, Player.getComponentType());
                if (current != null) {
                    action.accept(current);
                }
            }
        });
    }

    private void releaseLive(Player player,
                             @Nullable TwCommandItemConfig config,
                             UUID npcUuid) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (store == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        NPCEntity npc = npcRef == null || !npcRef.isValid()
                ? null
                : store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.mustBeLoaded");
            return;
        }
        if (!CommandGenericTargetAuthority.allowsGenericTargetMutation(
                npcRef, store
        )) {
            return;
        }
        if (!canRelease(player, config, npcRef, store)) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.ownedNearbyOnly");
            return;
        }
        String displayName = resolveDisplayName(player, npcRef, store, npc);
        clearOwner(npcRef, store);
        clearTamedAndLinks(npcRef, store);
        applyReleaseState(npcRef, npc, store);
        npc.setToDespawn();
        npc.setDespawnTime(RELEASE_DESPAWN_DELAY_SECONDS);
        feedbackService.showSuccessKey(
                player,
                "tamework.ui.notifications.command.release.success",
                displayName
        );
    }

    private void releaseLiveEntity(Player player, UUID npcUuid) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        Ref<EntityStore> npcRef = store == null ? null : world.getEntityRef(npcUuid);
        NPCEntity npc = npcRef == null || !npcRef.isValid() ? null
                : store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || !CommandGenericTargetAuthority
                .allowsGenericTargetMutation(npcRef, store)) {
            return;
        }
        clearOwner(npcRef, store);
        clearTamedAndLinks(npcRef, store);
        applyReleaseState(npcRef, npc, store);
        npc.setToDespawn();
        npc.setDespawnTime(RELEASE_DESPAWN_DELAY_SECONDS);
    }

    private boolean canRelease(Player player,
                               @Nullable TwCommandItemConfig config,
                               Ref<EntityStore> npcRef,
                               Store<EntityStore> store) {
        UUID ownerUuid = player.getUuid();
        if (ownerUuid == null) {
            return false;
        }
        boolean requireTamed = config != null && config.isRequireTamed();
        return linkPolicyService.passesOwnerAndTamed(
                linkingRequiresOwner(),
                requireTamed,
                npcRef,
                ownerUuid,
                store
        );
    }

    private boolean linkingRequiresOwner() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = global == null ? TwGlobalConfig.defaultConfig() : global;
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }

    private void clearTamedAndLinks(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        TameworkTamedComponent tamed = tamedType == null ? null : store.getComponent(npcRef, tamedType);
        if (tamed != null && tamed.isTamed()) {
            tamed.setTamed(false);
            store.putComponent(npcRef, tamedType, tamed);
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType == null ? null : store.getComponent(npcRef, linksType);
        if (links == null) {
            return;
        }
        links.setOwnerId(null);
        links.setToolIds(new String[0]);
        links.setHomePosition(null);
        store.putComponent(npcRef, linksType, links);
    }

    private void clearOwner(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null ? null : store.getComponent(npcRef, ownerType);
        if (owner != null && (owner.getOwnerId() != null || owner.getOwnerName() != null)) {
            owner.setOwnerId(null);
            owner.setOwnerName(null);
            store.putComponent(npcRef, ownerType, owner);
        }
    }

    private void applyReleaseState(Ref<EntityStore> npcRef,
                                   NPCEntity npc,
                                   Store<EntityStore> store) {
        for (String state : RELEASE_STATE_CANDIDATES) {
            if (stepExecutionService.applyState(npcRef, npc, store, state, null)
                    || stepExecutionService.applyState(npcRef, npc, store, "$" + state, null)) {
                return;
            }
        }
    }

    private String resolveDisplayName(Player player,
                                      Ref<EntityStore> npcRef,
                                      Store<EntityStore> store,
                                      NPCEntity npc) {
        String displayName = npcNameResolver.resolveNpcDisplayName(npcRef, store, npc);
        return displayName == null || displayName.isBlank()
                ? LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultMobName")
                : displayName;
    }
}
