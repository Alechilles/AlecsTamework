package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Permanently culls a linked companion only after its owner-population release has applied. */
final class CommandOwnerCullService {
    private static final float CULL_DAMAGE_AMOUNT = 2.1474836E9F;
    private static final Logger LOGGER = Logger.getLogger(CommandOwnerCullService.class.getName());

    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandOwnerCullContinuation continuation =
            new CommandOwnerCullContinuation(CommandOwnerCullService::logCallbackFailure);

    CommandOwnerCullService(CommandLinkPolicyService linkPolicyService,
                            CommandLinkMutationService linkMutationService,
                            CommandFeedbackService feedbackService,
                            CommandNpcNameResolver npcNameResolver) {
        this.linkPolicyService = linkPolicyService;
        this.linkMutationService = linkMutationService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
    }

    /** Pure world-thread preflight used before a canonical roster membership is removed. */
    boolean canCullNow(@Nullable Player player,
                       @Nullable TwCommandItemConfig config,
                       @Nullable UUID npcUuid) {
        if (player == null || npcUuid == null || resolveScheduler() == null) {
            return false;
        }
        World world = player.getWorld();
        LiveCullTarget target = world == null ? null : findLiveTarget(world, npcUuid);
        UUID playerUuid = target == null
                ? null
                : resolveEntityUuid(player.getReference(), target.store());
        DamageCause cause = DamageCause.COMMAND != null ? DamageCause.COMMAND : DamageCause.PHYSICAL;
        return target != null && playerUuid != null && cause != null
                && canCull(playerUuid, config, target.reference(), target.store());
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
        UUID playerUuid = resolveEntityUuid(player.getReference(), target.store());
        if (playerUuid == null || !canCull(playerUuid, config, target.reference(), target.store())) {
            warn(player, "tamework.ui.notifications.command.cull.ownedNearbyOnly");
            return;
        }
        DamageCause cause = DamageCause.COMMAND != null ? DamageCause.COMMAND : DamageCause.PHYSICAL;
        OwnerMutationScheduler scheduler = resolveScheduler();
        if (cause == null || scheduler == null) {
            warn(player, "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        String displayName = resolveDisplayName(player, target);
        try {
            scheduler.schedulePermanentRelease(
                    target.reference(),
                    target.store(),
                    false,
                    "command-cull:" + npcUuid,
                    callbacks(target.world(), playerUuid, npcUuid, cause, displayName)
            );
        } catch (RuntimeException | LinkageError failure) {
            logCallbackFailure("schedule", failure);
            warn(player, "tamework.ui.notifications.command.cull.unavailable");
        }
    }

    @Nonnull
    private OwnerMutationScheduler.MutationCallbacks callbacks(
            World world,
            UUID playerUuid,
            UUID npcUuid,
            DamageCause cause,
            String displayName) {
        return new OwnerMutationScheduler.MutationCallbacks() {
            @Override
            public void onDenied(@Nonnull String reason, @Nullable OwnerPopulationDecision decision) {
                continuation.run(new CommandOwnerCullContinuation.Step(
                        "denied-feedback",
                        () -> warnResolvedPlayer(
                                world,
                                playerUuid,
                                "tamework.ui.notifications.command.cull.unavailable"
                        )
                ));
            }

            @Override
            public void onApplied(@Nonnull OwnerPopulationDecision decision) {
                continuation.run(
                        new CommandOwnerCullContinuation.Step(
                                "clear-live-links",
                                () -> clearNpcCommandLinks(findLiveTarget(world, npcUuid))
                        ),
                        new CommandOwnerCullContinuation.Step(
                                "clear-tool-records",
                                () -> removeNpcFromAllCommandToolRecords(world, playerUuid, npcUuid)
                        ),
                        new CommandOwnerCullContinuation.Step(
                                "apply-fatal-damage",
                                () -> applyFatalDamage(findLiveTarget(world, npcUuid), cause)
                        ),
                        new CommandOwnerCullContinuation.Step(
                                "success-feedback",
                                () -> showSuccessResolvedPlayer(world, playerUuid, displayName)
                        )
                );
            }

            @Override
            public void onDurabilityDegraded(@Nonnull String reason) {
                continuation.run(new CommandOwnerCullContinuation.Step(
                        "durability-warning",
                        () -> LOGGER.warning(
                                "Command cull population durability degraded for npc="
                                        + npcUuid + " reason=" + reason
                        )
                ));
            }
        };
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
        return new LiveCullTarget(world, npcRef, store, npc);
    }

    @Nullable
    private LiveCullTarget findLiveTarget(World world, UUID npcUuid) {
        Store<EntityStore> store = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        Ref<EntityStore> npcRef = store == null ? null : world.getEntityRef(npcUuid);
        NPCEntity npc = npcRef == null || !npcRef.isValid()
                ? null
                : store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc == null ? null : new LiveCullTarget(world, npcRef, store, npc);
    }

    private boolean canCull(UUID ownerUuid,
                            @Nullable TwCommandItemConfig config,
                            Ref<EntityStore> npcRef,
                            Store<EntityStore> store) {
        boolean requireTamed = config != null && config.isRequireTamed();
        return linkPolicyService.passesOwnerAndTamed(
                linkingRequiresOwner(),
                requireTamed,
                npcRef,
                ownerUuid,
                store
        );
    }

    @Nullable
    private UUID resolveEntityUuid(@Nullable Ref<EntityStore> reference,
                                   Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        UUIDComponent identity = reference == null || !reference.isValid() || uuidType == null
                ? null
                : store.getComponent(reference, uuidType);
        return identity == null ? null : identity.getUuid();
    }

    private boolean linkingRequiresOwner() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = global == null ? TwGlobalConfig.defaultConfig() : global;
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }

    private void clearNpcCommandLinks(@Nullable LiveCullTarget target) {
        if (target == null) {
            throw new IllegalStateException("Applied cull target is no longer live");
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType == null
                ? null
                : target.store().getComponent(target.reference(), linksType);
        if (links == null) {
            return;
        }
        links.setOwnerId(null);
        links.setToolIds(new String[0]);
        links.setHomePosition(null);
        target.store().putComponent(target.reference(), linksType, links);
    }

    private void applyFatalDamage(@Nullable LiveCullTarget target, DamageCause cause) {
        if (target == null) {
            throw new IllegalStateException("Applied cull target is no longer live");
        }
        DeathComponent.tryAddComponent(
                target.store(),
                target.reference(),
                new Damage(Damage.NULL_SOURCE, cause, CULL_DAMAGE_AMOUNT)
        );
    }

    private void removeNpcFromAllCommandToolRecords(World world,
                                                    UUID playerUuid,
                                                    UUID npcUuid) {
        Player player = resolveLivePlayer(world, playerUuid);
        if (player == null) {
            throw new IllegalStateException("Cull player is no longer live");
        }
        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        if (hotbar == null) {
            return;
        }
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(
                    TameworkMetadataKeys.COMMAND_TOOL_ID,
                    Codec.STRING
            );
            if (stackToolId == null || stackToolId.isBlank()) {
                continue;
            }
            ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, npcUuid);
            if (updated != stack) {
                hotbar.setItemStackForSlot(slot, updated);
            }
        }
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
        continuation.run(new CommandOwnerCullContinuation.Step(
                "warning-feedback",
                () -> feedbackService.showWarningKey(player, key)
        ));
    }

    private void warnResolvedPlayer(World world, UUID playerUuid, String key) {
        Player player = resolveLivePlayer(world, playerUuid);
        if (player != null) {
            feedbackService.showWarningKey(player, key);
        }
    }

    private void showSuccessResolvedPlayer(World world, UUID playerUuid, String displayName) {
        Player player = resolveLivePlayer(world, playerUuid);
        if (player != null) {
            feedbackService.showSuccessKey(
                    player,
                    "tamework.ui.notifications.command.cull.success",
                    displayName
            );
        }
    }

    @Nullable
    private Player resolveLivePlayer(World world, UUID playerUuid) {
        Store<EntityStore> store = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = store == null ? null : world.getEntityRef(playerUuid);
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        return playerRef == null || !playerRef.isValid() || playerType == null
                ? null
                : store.getComponent(playerRef, playerType);
    }

    @Nullable
    private OwnerMutationScheduler resolveScheduler() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerMutationScheduler();
    }

    private static void logCallbackFailure(String action, Throwable failure) {
        LOGGER.log(Level.WARNING, "Command cull callback failed during " + action + '.', failure);
    }

    private record LiveCullTarget(@Nonnull World world,
                                  @Nonnull Ref<EntityStore> reference,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull NPCEntity npc) {
    }
}
