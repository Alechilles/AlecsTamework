package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Ref;
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
                    ReleasedBodyRemoval.removeOnBodyWorld(outcome.body(), bodyUuid);
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
        ReleasedBodyRemoval.removeNow(npcRef, store);
        feedbackService.showSuccessKey(
                player,
                "tamework.ui.notifications.command.release.success",
                displayName
        );
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
