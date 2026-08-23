package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.CommandItemRegistry;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Permanently culls a linked companion on the current world thread. */
final class CommandOwnerCullService {
    private final TameworkNpcCullService cullService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandOwnerCullService(CommandLinkPolicyService linkPolicyService,
                            CommandItemRegistry registry,
                            CommandLinkMutationService linkMutationService,
                            CommandFeedbackService feedbackService,
                            CommandNpcNameResolver npcNameResolver) {
        this.cullService = new TameworkNpcCullService(
                new TameworkCullEligibility(linkPolicyService),
                registry,
                linkMutationService
        );
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
    }

    void cull(@Nullable Player player,
              @Nullable String toolId,
              @Nullable TwCommandItemConfig config,
              @Nullable UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        LiveCullTarget target = resolveTarget(player, npcUuid);
        if (target == null) {
            return;
        }
        TameworkNpcCullService.Outcome outcome = cullService.cull(
                player,
                target.reference(),
                target.store(),
                linkingRequiresOwner(),
                config != null && config.isRequireTamed()
        );
        if (outcome == TameworkNpcCullService.Outcome.DENIED) {
            warn(player, "tamework.ui.notifications.command.cull.ownedNearbyOnly");
            return;
        }
        if (outcome != TameworkNpcCullService.Outcome.CULLED
                && outcome != TameworkNpcCullService.Outcome.QUEUED) {
            warn(player, "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        String displayName = resolveDisplayName(player, target);
        feedbackService.showSuccessKey(
                player,
                "tamework.ui.notifications.command.cull.success",
                displayName
        );
    }

    @Nullable
    private LiveCullTarget resolveTarget(Player player, UUID npcUuid) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (world == null || store == null) {
            warn(player, "tamework.ui.notifications.command.cull.unavailable");
            return null;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            warn(player, "tamework.ui.notifications.command.cull.mustBeLoaded");
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            warn(player, "tamework.ui.notifications.command.cull.mustBeLoaded");
            return null;
        }
        return new LiveCullTarget(npcRef, store, npc);
    }

    private boolean linkingRequiresOwner() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = global == null ? TwGlobalConfig.defaultConfig() : global;
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }

    private String resolveDisplayName(Player player, LiveCullTarget target) {
        String displayName = npcNameResolver.resolveNpcDisplayName(
                target.reference(),
                target.store(),
                target.npc()
        );
        return displayName == null || displayName.isBlank()
                ? LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultMobName")
                : displayName;
    }

    private void warn(Player player, String key) {
        feedbackService.showWarningKey(player, key);
    }

    private record LiveCullTarget(@Nonnull Ref<EntityStore> reference,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull NPCEntity npc) {
    }
}
