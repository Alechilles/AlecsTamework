package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerMutationContext;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Coordinates command-item release only after owner admission has applied. */
final class CommandOwnerReleaseService {
    private static final float RELEASE_DESPAWN_DELAY_SECONDS = 4.0F;
    private static final String[] RELEASE_STATE_CANDIDATES = new String[] { "Flee", "Wander", "Idle" };

    private final CommandLinkPolicyService linkPolicyService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandOwnerReleaseService(CommandLinkPolicyService linkPolicyService,
                               CommandStepExecutionService stepExecutionService,
                               CommandFeedbackService feedbackService,
                               CommandNpcNameResolver npcNameResolver) {
        this.linkPolicyService = linkPolicyService;
        this.stepExecutionService = stepExecutionService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
    }

    /** Pure world-thread preflight used before a canonical roster membership is removed. */
    boolean canReleaseNow(@Nullable Player player,
                          @Nullable TwCommandItemConfig config,
                          @Nullable UUID npcUuid) {
        if (player == null || npcUuid == null || resolveScheduler() == null) {
            return false;
        }
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        Ref<EntityStore> npcRef = store == null ? null : world.getEntityRef(npcUuid);
        NPCEntity npc = npcRef == null || !npcRef.isValid()
                ? null
                : store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc != null && canRelease(player, config, npcRef, store);
    }

    void release(Player player,
                 String toolId,
                 TwCommandItemConfig config,
                 UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
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
        if (!canRelease(player, config, npcRef, store)) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.ownedNearbyOnly");
            return;
        }
        OwnerMutationScheduler scheduler = resolveScheduler();
        if (scheduler == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        String displayName = resolveDisplayName(player, npcRef, store, npc);
        scheduler.schedule(
                npcRef,
                store,
                null,
                null,
                CompanionLifecycleState.RELEASED,
                OwnerPopulationOperation.OWNER_CLEAR,
                false,
                "command-release:" + npcUuid,
                callbacks(player, displayName)
        );
    }

    @Nonnull
    private OwnerMutationScheduler.MutationCallbacks callbacks(
            Player player,
            String displayName) {
        return new OwnerMutationScheduler.MutationCallbacks() {
            @Override
            public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                feedbackService.showWarningKey(
                        player,
                        "tamework.ui.notifications.command.release.unavailable"
                );
            }

            @Override
            public void onApplied(@Nonnull OwnerPopulationDecision decision,
                                  @Nonnull String profileId,
                                  @Nonnull OwnerMutationContext context) {
                NPCEntity liveNpc = context.store().getComponent(
                        context.npcRef(), NPCEntity.getComponentType()
                );
                if (liveNpc == null) {
                    throw new IllegalStateException("Applied release target is no longer an NPC");
                }
                clearTamedAndLinks(context.npcRef(), context.store());
                applyReleaseState(context.npcRef(), liveNpc, context.store());
                liveNpc.setToDespawn();
                liveNpc.setDespawnTime(RELEASE_DESPAWN_DELAY_SECONDS);
                feedbackService.showSuccessKey(
                        player,
                        "tamework.ui.notifications.command.release.success",
                        displayName
                );
            }
        };
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

    @Nullable
    private OwnerMutationScheduler resolveScheduler() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerMutationScheduler();
    }
}
