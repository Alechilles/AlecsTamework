package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Awards combat XP for linked companions based on final damage dealt and taken.
 */
public final class CompanionCombatExperienceSystem extends DamageEventSystem {
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index,
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer,
                       Damage damage) {
        if (damage == null || damage.isCancelled() || !(damage.getAmount() > 0.0f) || chunk == null || store == null) {
            return;
        }
        Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        Ref<EntityStore> sourceRef = resolveNpcSourceRef(damage.getSource(), store);
        awardTakenXp(sourceRef, targetRef, store, damage);
        if (sourceRef != null && sourceRef.isValid() && !sourceRef.equals(targetRef)) {
            awardDealtXp(sourceRef, targetRef, store, damage);
        }
    }

    private void awardTakenXp(@Nullable Ref<EntityStore> sourceRef,
                              @Nonnull Ref<EntityStore> targetRef,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull Damage damage) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(targetRef, store);
        TwLevelingConfig config = roleId != null ? TwLevelingConfig.resolveForRole(roleId) : null;
        if (config == null || !config.isEnabled()) {
            return;
        }
        TwLevelingConfig.CombatXpSourceSettings combat = config.getXpSources().getCombat();
        if (!combat.isEnabled()) {
            return;
        }
        float finalDamage = damage.getAmount();
        if (!(finalDamage >= combat.getMinimumDamageEvent())) {
            return;
        }
        if (!isCombatPairEligible(sourceRef, targetRef, store, combat, damage.getSource())) {
            return;
        }
        double xp = finalDamage * combat.getDamageTakenXpPerPoint();
        if (xp > 0.0) {
            CompanionLevelingService.awardXp(targetRef, store, roleId, xp);
        }
    }

    private void awardDealtXp(@Nonnull Ref<EntityStore> sourceRef,
                              @Nonnull Ref<EntityStore> targetRef,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull Damage damage) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(sourceRef, store);
        TwLevelingConfig config = roleId != null ? TwLevelingConfig.resolveForRole(roleId) : null;
        if (config == null || !config.isEnabled()) {
            return;
        }
        TwLevelingConfig.CombatXpSourceSettings combat = config.getXpSources().getCombat();
        if (!combat.isEnabled()) {
            return;
        }
        float finalDamage = damage.getAmount();
        if (!(finalDamage >= combat.getMinimumDamageEvent())) {
            return;
        }
        if (!isCombatPairEligible(sourceRef, targetRef, store, combat, damage.getSource())) {
            return;
        }
        double xp = finalDamage * combat.getDamageDealtXpPerPoint();
        if (xp > 0.0) {
            CompanionLevelingService.awardXp(sourceRef, store, roleId, xp);
        }
    }

    private boolean isCombatPairEligible(@Nullable Ref<EntityStore> sourceRef,
                                         @Nonnull Ref<EntityStore> targetRef,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull TwLevelingConfig.CombatXpSourceSettings combat,
                                         @Nullable Damage.Source source) {
        if (!combat.isAwardVsPlayers()) {
            if (isPlayerRef(targetRef, store)) {
                return false;
            }
            if (source instanceof Damage.EntitySource entitySource && isPlayerRef(entitySource.getRef(), store)) {
                return false;
            }
            if (source instanceof Damage.ProjectileSource projectileSource) {
                if (isPlayerRef(projectileSource.getRef(), store) || isPlayerRef(projectileSource.getProjectile(), store)) {
                    return false;
                }
            }
        }
        if (!combat.isAwardVsOwnedAllies()) {
            UUID sourceOwner = resolveOwnerId(sourceRef, store);
            UUID targetOwner = resolveOwnerId(targetRef, store);
            if (sourceOwner != null && targetOwner != null && sourceOwner.equals(targetOwner)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private Ref<EntityStore> resolveNpcSourceRef(@Nullable Damage.Source source, @Nonnull Store<EntityStore> store) {
        if (source instanceof Damage.EntitySource entitySource) {
            return isNpcRef(entitySource.getRef(), store) ? entitySource.getRef() : null;
        }
        if (source instanceof Damage.ProjectileSource projectileSource) {
            if (isNpcRef(projectileSource.getRef(), store)) {
                return projectileSource.getRef();
            }
            if (isNpcRef(projectileSource.getProjectile(), store)) {
                return projectileSource.getProjectile();
            }
        }
        return null;
    }

    private boolean isNpcRef(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return ref != null && ref.isValid() && CompanionRoleIdResolver.resolveRoleId(ref, store) != null;
    }

    private boolean isPlayerRef(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return ref != null && ref.isValid() && store.getComponent(ref, Player.getComponentType()) != null;
    }

    @Nullable
    private UUID resolveOwnerId(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            TameworkOwnerComponent owner = store.getComponent(ref, ownerType);
            if (owner != null && owner.getOwnerId() != null) {
                return owner.getOwnerId();
            }
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType != null) {
            TameworkCommandLinksComponent links = store.getComponent(ref, linksType);
            if (links != null && links.getOwnerId() != null) {
                return links.getOwnerId();
            }
        }
        return null;
    }
}
