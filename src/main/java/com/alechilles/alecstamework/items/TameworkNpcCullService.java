package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Applies one authorized companion cull on the current world thread. */
public final class TameworkNpcCullService {
    private static final float CULL_DAMAGE_AMOUNT = 2.1474836E9F;

    enum Outcome {
        CULLED,
        QUEUED,
        DENIED,
        UNAVAILABLE
    }

    private final TameworkCullEligibility eligibility;
    private final CommandItemRegistry registry;
    private final CommandLinkMutationService linkMutationService;
    private final CommandRosterCullUnlinkService rosterUnlinkService;

    TameworkNpcCullService(TameworkCullEligibility eligibility,
                           @Nullable CommandItemRegistry registry,
                           CommandLinkMutationService linkMutationService) {
        this(eligibility, registry, linkMutationService,
                () -> {
                    Tamework plugin = Tamework.getInstance();
                    return plugin == null ? null : plugin.getApi();
                });
    }

    TameworkNpcCullService(TameworkCullEligibility eligibility,
                           @Nullable CommandItemRegistry registry,
                           CommandLinkMutationService linkMutationService,
                           Supplier<TameworkApi> api) {
        this.eligibility = eligibility;
        this.registry = registry;
        this.linkMutationService = linkMutationService;
        this.rosterUnlinkService = new CommandRosterCullUnlinkService(
                registry, api
        );
    }

    /** Checks item-interaction eligibility while its context is still active. */
    public static boolean canCullFromItemInteraction(
            @Nullable Player player,
            @Nullable Ref<EntityStore> target,
            @Nullable ComponentAccessor<EntityStore> components,
            boolean requireOwner,
            boolean requireTamed
    ) {
        if (player == null || target == null || !target.isValid()
                || components == null
                || components.getComponent(
                target, NPCEntity.getComponentType()) == null) {
            return false;
        }
        return new TameworkCullEligibility(new CommandLinkPolicyService())
                .allows(player.getUuid(), requireOwner, requireTamed,
                        target, components);
    }

    /** Culls a target from a registered item interaction. */
    public static boolean cullFromItemInteraction(
            @Nullable Player player,
            @Nullable Ref<EntityStore> target,
            @Nullable Store<EntityStore> store,
            boolean requireOwner,
            boolean requireTamed
    ) {
        Tamework plugin = Tamework.getInstance();
        CommandLinkPolicyService policy = new CommandLinkPolicyService();
        TameworkNpcCullService service = new TameworkNpcCullService(
                new TameworkCullEligibility(policy),
                plugin == null ? null : plugin.getCommandItemRegistry(),
                new CommandLinkMutationService(null, policy, null, null)
        );
        Outcome outcome = service.cull(
                player, target, store, requireOwner, requireTamed
        );
        return outcome == Outcome.CULLED || outcome == Outcome.QUEUED;
    }

    Outcome cull(@Nullable Player player,
                 @Nullable Ref<EntityStore> target,
                 @Nullable Store<EntityStore> store,
                 boolean requireOwner,
                 boolean requireTamed) {
        if (player == null || target == null || !target.isValid() || store == null) {
            return Outcome.UNAVAILABLE;
        }
        NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
        if (npc == null) {
            return Outcome.UNAVAILABLE;
        }
        if (!eligibility.allows(player.getUuid(), requireOwner, requireTamed,
                target, store)) {
            return Outcome.DENIED;
        }
        CommandRosterCullUnlinkService.Preparation rosterRemoval =
                prepareRosterRemoval(player.getUuid(), target, store);
        if (rosterRemoval.status()
                == CommandRosterCullUnlinkService.PreparationStatus.UNAVAILABLE) {
            return Outcome.UNAVAILABLE;
        }
        if (rosterRemoval.isReady()) {
            return queueCullAfterRosterRemoval(
                    player, npc.getUuid(), requireOwner, requireTamed,
                    rosterRemoval
            );
        }
        return applyCull(player, target, store);
    }

    private CommandRosterCullUnlinkService.Preparation prepareRosterRemoval(
            UUID ownerUuid,
            Ref<EntityStore> target,
            Store<EntityStore> store
    ) {
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> type =
                TameworkProjectionIdentityComponent.getComponentType();
        TameworkProjectionIdentityComponent marker = type == null ? null
                : store.getComponent(target, type);
        if (marker == null || !TameworkProjectionIdentityComponent
                .KIND_COMMAND_ROSTER.equals(marker.getProjectionKind())) {
            return new CommandRosterCullUnlinkService.Preparation(
                    CommandRosterCullUnlinkService.PreparationStatus.NOT_ROSTER_MEMBER,
                    null,
                    null
            );
        }
        return rosterUnlinkService.prepare(ownerUuid, marker.getProfileId());
    }

    private Outcome queueCullAfterRosterRemoval(
            Player player,
            @Nullable UUID targetUuid,
            boolean requireOwner,
            boolean requireTamed,
            CommandRosterCullUnlinkService.Preparation rosterRemoval
    ) {
        World world = player.getWorld();
        UUID ownerUuid = player.getUuid();
        if (world == null || !world.isAlive() || ownerUuid == null
                || targetUuid == null) {
            return Outcome.UNAVAILABLE;
        }
        CompletionStage<Boolean> stage = rosterUnlinkService.remove(
                rosterRemoval
        );
        if (stage == null) {
            return Outcome.UNAVAILABLE;
        }
        stage.whenComplete((removed, failure) -> {
            if (failure == null && Boolean.TRUE.equals(removed)) {
                LeaseBoundWorldDispatcher.execute(world, () ->
                        applyDeferredCull(
                                world, ownerUuid, targetUuid,
                                requireOwner, requireTamed
                        ));
            }
        });
        return Outcome.QUEUED;
    }

    private void applyDeferredCull(
            World world,
            UUID ownerUuid,
            UUID targetUuid,
            boolean requireOwner,
            boolean requireTamed
    ) {
        if (world.getEntityStore() == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> target = world.getEntityRef(targetUuid);
        if (store == null || target == null || !target.isValid()
                || !eligibility.allows(
                ownerUuid, requireOwner, requireTamed, target, store)) {
            return;
        }
        WorldPlayerResolver.ResolvedPlayer resolved =
                WorldPlayerResolver.resolve(world, ownerUuid);
        Player player = resolved == null ? null : resolved.player();
        applyCull(player, target, store);
    }

    private Outcome applyCull(
            @Nullable Player player,
            Ref<EntityStore> target,
            Store<EntityStore> store
    ) {
        DamageCause cause = DamageCause.COMMAND != null
                ? DamageCause.COMMAND : DamageCause.PHYSICAL;
        if (cause == null) {
            return Outcome.UNAVAILABLE;
        }
        unlinkCommandTarget(target, store);
        NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
        removeCommandToolRecords(player, npc == null ? null : npc.getUuid());
        DeathComponent.tryAddComponent(
                store,
                target,
                new Damage(Damage.NULL_SOURCE, cause, CULL_DAMAGE_AMOUNT)
        );
        return Outcome.CULLED;
    }

    /**
     * Removes the link component before death systems observe the target.
     *
     * <p>An empty link component still marks an NPC as a persistent command
     * companion. Removing it prevents culling from creating a revivable
     * dormant profile.</p>
     */
    private void unlinkCommandTarget(Ref<EntityStore> target,
                                     Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        if (linksType == null || store.getComponent(target, linksType) == null) {
            return;
        }
        store.removeComponent(target, linksType);
    }

    private void removeCommandToolRecords(@Nullable Player player,
                                          @Nullable UUID npcUuid) {
        if (player == null || registry == null || npcUuid == null
                || linkMutationService == null) {
            return;
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
            String toolId = stack.getFromMetadataOrNull(
                    TameworkMetadataKeys.COMMAND_TOOL_ID,
                    Codec.STRING
            );
            if (toolId == null || toolId.isBlank()) {
                continue;
            }
            TwCommandItemConfig config = registry.get(stack.getItemId());
            if (!CommandGenericTargetAuthority.allowsGenericCullRepair(
                    stack, config)) {
                continue;
            }
            ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, npcUuid);
            if (updated != stack) {
                hotbar.setItemStackForSlot(slot, updated);
            }
        }
    }
}
