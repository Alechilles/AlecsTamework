package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Releases a companion through the companion index, then removes its loaded body on the body's
 * world thread. Runs on the releasing player's world thread.
 */
final class CommandOwnerReleaseService {
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    @Nullable private final ReleaseFlow releaseFlow;
    @Nullable private final CompanionQueries companions;
    @Nullable private final CommandLinkedNpcInventoryRepairService inventoryRepair;

    CommandOwnerReleaseService(CommandLinkPolicyService linkPolicyService,
                               CommandFeedbackService feedbackService,
                               CommandNpcNameResolver npcNameResolver) {
        this(linkPolicyService, feedbackService, npcNameResolver, null, null, null);
    }

    /** With a null flow or null queries, release falls back to removing a loaded, owned NPC only. */
    CommandOwnerReleaseService(
            CommandLinkPolicyService linkPolicyService,
            CommandFeedbackService feedbackService,
            CommandNpcNameResolver npcNameResolver,
            @Nullable ReleaseFlow releaseFlow,
            @Nullable CompanionQueries companions,
            @Nullable CommandLinkedNpcInventoryRepairService inventoryRepair
    ) {
        this.linkPolicyService = linkPolicyService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
        this.releaseFlow = releaseFlow;
        this.companions = companions;
        this.inventoryRepair = inventoryRepair;
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
        if (releaseFlow == null || companions == null) {
            releaseLive(player, config, npcUuid);
            return;
        }
        // Panel rows carry the loaded NPC uuid, or the profile id for a companion without a body.
        CompanionRecord record = companions.byNpcUuid(npcUuid);
        if (record == null) {
            record = companions.get(npcUuid);
        }
        // Bonded companions are released through their own flow, never by a generic tool.
        if (record == null || record.bonded()) {
            warn(player, "tamework.ui.notifications.command.release.unavailable");
            return;
        }
        UUID bodyUuid = record.currentNpcUuid();
        ReleaseFlow.Outcome outcome = releaseFlow.release(record.profileId(), ownerUuid);
        switch (outcome.result()) {
            case NOT_OWNER -> warn(player, "tamework.ui.notifications.command.release.ownedNearbyOnly");
            case NOT_FOUND, NOT_RELEASABLE -> warn(player, "tamework.ui.notifications.command.release.unavailable");
            case RELEASED -> {
                if (outcome.body() != null && bodyUuid != null) {
                    removeOnBodyWorld(outcome.body(), ownerUuid, bodyUuid);
                }
                if (inventoryRepair != null) {
                    inventoryRepair.canonicalize(player);
                }
                String displayName = record.displayName();
                feedbackService.showSuccessKey(
                        player,
                        "tamework.ui.notifications.command.release.success",
                        displayName == null || displayName.isBlank()
                                ? LocalizedText.resolve(player,
                                "tamework.ui.notifications.command.shared.defaultMobName")
                                : displayName
                );
            }
        }
    }

    /** The body may be in another world; its removal runs on that world's thread and re-resolves the ref there. */
    private void removeOnBodyWorld(Ref<EntityStore> body, UUID ownerUuid, UUID bodyUuid) {
        Store<EntityStore> bodyStore = body.getStore();
        World world = bodyStore == null || bodyStore.getExternalData() == null
                ? null : bodyStore.getExternalData().getWorld();
        if (world == null || !world.isAlive()) {
            // The fence removes the released body when it next loads.
            return;
        }
        world.execute(() -> releaseLiveEntity(world, ownerUuid, bodyUuid));
    }

    private void warn(Player player, String key) {
        feedbackService.showWarningKey(player, key);
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
        removeReleasedNpc(npcRef, store);
        feedbackService.showSuccessKey(
                player,
                "tamework.ui.notifications.command.release.success",
                displayName
        );
    }

    private void releaseLiveEntity(World world, UUID ownerUuid, UUID npcUuid) {
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        Ref<EntityStore> npcRef = store == null ? null : world.getEntityRef(npcUuid);
        NPCEntity npc = npcRef == null || !npcRef.isValid() ? null
                : store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || !CommandGenericTargetAuthority
                .allowsGenericTargetMutation(npcRef, store)
                || !linkPolicyService.passesOwnerAndTamed(true, false, npcRef, ownerUuid, store)) {
            return;
        }
        removeReleasedNpc(npcRef, store);
    }

    private void removeReleasedNpc(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        clearOwner(npcRef, store);
        clearTamedAndLinks(npcRef, store);
        // Release runs on the owning world thread, outside an ECS system callback.
        // NPC despawn timers cannot finish while the entity is frozen or non-ticking.
        store.removeEntity(npcRef, RemoveReason.REMOVE);
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
        // Match Cull: terminal removal must not be observed as a lost linked companion.
        store.removeComponent(npcRef, linksType);
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
