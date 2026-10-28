package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.HusbandryToolContext;
import com.alechilles.alecstamework.api.internal.HusbandryYieldResolver;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.systems.CommandLinkedRevivableDropSuppressionSystem;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Treats an owner's killing blow on an owned managed-cull animal as a cull when its managed
 * activity profile sets {@code Activities.OwnerKillCountsAsCull}.
 *
 * <p>It runs when the death component is added, before the engine's NPC drop system ticks:
 * {@code NPCDamageSystems.DropDeathItems} drops the role's drop list only while the death's
 * items-loss mode is {@code ALL}, so switching it to {@code NONE} here suppresses the native
 * drop, and later drop logic in other mods sees {@code NONE}. It runs after the revivable
 * companion suppression, so a revivable death (already {@code NONE}) is left alone. It reads
 * through the store, writes item drops through the command buffer, and never touches the
 * companion record, death or revive handling.</p>
 */
public final class OwnerKillCullSystem extends DeathSystems.OnDeathSystem {
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, CommandLinkedRevivableDropSuppressionSystem.class),
            new SystemDependency<>(Order.BEFORE, NPCDamageSystems.DropDeathItems.class)
    );

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        if (npcType != null && ownerType != null) {
            return Query.and(npcType, ownerType);
        }
        return npcType != null ? Query.and(npcType) : Query.any();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull DeathComponent component,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType =
                TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null ? null : store.getComponent(ref, ownerType);
        Ref<EntityStore> killerRef = killerRef(component.getDeathInfo());
        Player killer = killerRef == null ? null : store.getComponent(killerRef, Player.getComponentType());
        UUID ownerId = owner == null ? null : owner.getOwnerId();
        UUID killerId = killer == null ? null : killer.getUuid();
        if (!countsAsOwnerKill(ownerId, killerId, component.getItemsLossMode())
                || !CommandGenericTargetAuthority.allowsGenericTargetMutation(ref, store)) {
            return;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        if (!ActivityRuntime.ownerKillCountsAsCull(roleId)) {
            return;
        }
        HusbandryToolContext tool = authorizedHeldTool(killer, ref, store, roleId, killerId);
        CullRewardService.PreparedOutcome prepared =
                CullRewardService.prepareManaged(ref, store, roleId, tool, killerId);
        if (!prepared.domesticDropsPrepared()) {
            // No configured list and no provider table: keep the normal death drops.
            return;
        }
        component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);
        CullRewardService.Outcome rewards = CullRewardService.apply(prepared, ref, commandBuffer);
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        ActivityRuntime.publishCull(
                UUID.randomUUID(),
                roleId,
                ownerId,
                npc == null ? null : npc.getUuid(),
                rewards.itemQuantities(),
                tool,
                tool == null ? null : killerId,
                rewards.ageStage()
        );
    }

    /**
     * Returns whether this death is an owner's kill that may count as a cull: the killer is the
     * owner and the death still drops items (a revivable companion's death is already
     * {@code NONE}).
     */
    static boolean countsAsOwnerKill(@Nullable UUID ownerId,
                                     @Nullable UUID killerId,
                                     @Nullable DeathConfig.ItemsLossMode itemsLossMode) {
        return ownerId != null && ownerId.equals(killerId)
                && itemsLossMode == DeathConfig.ItemsLossMode.ALL;
    }

    /** Returns the entity that dealt the killing blow; for a projectile, its shooter. */
    @Nullable
    private static Ref<EntityStore> killerRef(@Nullable Damage deathInfo) {
        if (deathInfo == null || !(deathInfo.getSource() instanceof Damage.EntitySource source)) {
            return null;
        }
        Ref<EntityStore> ref = source.getRef();
        return ref != null && ref.isValid() ? ref : null;
    }

    /**
     * The owner's held item becomes the cull's tool only when the husbandry outcome provider
     * authorizes it. Otherwise the cull has no tool, so a weapon the provider does not know
     * cannot zero the rewards.
     */
    @Nullable
    private static HusbandryToolContext authorizedHeldTool(@Nonnull Player killer,
                                                           @Nonnull Ref<EntityStore> ref,
                                                           @Nonnull Store<EntityStore> store,
                                                           @Nullable String roleId,
                                                           @Nullable UUID killerId) {
        HusbandryToolContext held = HusbandryToolContext.from(PlayerInventoryAccess.getActiveHotbarItem(killer));
        if (held == null || !held.present()) {
            return null;
        }
        return HusbandryYieldResolver.resolveCull(ref, store, roleId, null, held, killerId).toolAuthorized()
                ? held : null;
    }
}
