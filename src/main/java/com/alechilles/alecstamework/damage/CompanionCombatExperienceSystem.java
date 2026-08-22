package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.CombatParticipantView;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionXpTransition;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Awards combat XP for eligible companions based on final damage dealt and taken.
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
        CompanionXpTransition takenXp = awardTakenXp(
                sourceRef, targetRef, store, commandBuffer, damage);
        CompanionXpTransition dealtXp = null;
        if (sourceRef != null && sourceRef.isValid() && !sourceRef.equals(targetRef)) {
            dealtXp = awardDealtXp(
                    sourceRef, targetRef, store, commandBuffer, damage);
        }

        boolean damageInterested = ActivityRuntime.hasCombatInterest(
                ActivityIds.COMBAT_DAMAGE);
        boolean defeatInterested = ActivityRuntime.hasCombatInterest(
                ActivityIds.COMBAT_DEFEAT);
        if (!damageInterested && !defeatInterested) {
            return;
        }
        Ref<EntityStore> attributedSource = resolveSourceRef(
                damage.getSource(), store);
        CombatParticipantView source = participant(attributedSource, store);
        CombatParticipantView target = participant(targetRef, store);
        if (source == null || target == null) {
            return;
        }
        long occurredAtMs = System.currentTimeMillis();
        UUID operationId = UUID.randomUUID();
        if (defeatInterested && sourceRef != null
                && source.entityId().equals(resolveEntityId(sourceRef, store))
                && source.ownerId() != null) {
            World world = store.getExternalData() == null
                    ? null : store.getExternalData().getWorld();
            if (world != null) {
                CompanionCombatDefeatSystem.record(
                        world, operationId, target.entityId(),
                        source.entityId(), source.ownerId(),
                        damage.getAmount(), occurredAtMs);
            }
        }
        if (damageInterested) {
            ActivityRuntime.publishCombatDamage(
                    operationId,
                    source,
                    target,
                    damage.getAmount(),
                    damageType(damage),
                    dealtXp == null ? null : dealtXp.toOutcomeView(),
                    takenXp == null ? null : takenXp.toOutcomeView(),
                    occurredAtMs);
        }
    }

    @Nullable
    private CompanionXpTransition awardTakenXp(
                              @Nullable Ref<EntityStore> sourceRef,
                              @Nonnull Ref<EntityStore> targetRef,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull Damage damage) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(targetRef, store);
        TwLevelingConfig config = roleId != null ? TwLevelingConfig.resolveForRole(roleId) : null;
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TwLevelingConfig.CombatXpSourceSettings combat = config.getXpSources().getCombat();
        if (!combat.isEnabled()) {
            return null;
        }
        float finalDamage = damage.getAmount();
        if (!(finalDamage >= combat.getMinimumDamageEvent())) {
            return null;
        }
        if (!isCombatPairEligible(sourceRef, targetRef, store, combat, damage.getSource())) {
            return null;
        }
        double xp = finalDamage * combat.getDamageTakenXpPerPoint();
        if (xp > 0.0) {
            return CompanionLevelingService.awardXp(
                    targetRef, store, commandBuffer, roleId,
                    CompanionXpSource.COMBAT_DAMAGE_TAKEN, xp).transition();
        }
        return null;
    }

    @Nullable
    private CompanionXpTransition awardDealtXp(
                              @Nonnull Ref<EntityStore> sourceRef,
                              @Nonnull Ref<EntityStore> targetRef,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull Damage damage) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(sourceRef, store);
        TwLevelingConfig config = roleId != null ? TwLevelingConfig.resolveForRole(roleId) : null;
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TwLevelingConfig.CombatXpSourceSettings combat = config.getXpSources().getCombat();
        if (!combat.isEnabled()) {
            return null;
        }
        float finalDamage = damage.getAmount();
        if (!(finalDamage >= combat.getMinimumDamageEvent())) {
            return null;
        }
        if (!isCombatPairEligible(sourceRef, targetRef, store, combat, damage.getSource())) {
            return null;
        }
        double xp = finalDamage * combat.getDamageDealtXpPerPoint();
        if (xp > 0.0) {
            return CompanionLevelingService.awardXp(
                    sourceRef, store, commandBuffer, roleId,
                    CompanionXpSource.COMBAT_DAMAGE_DEALT, xp).transition();
        }
        return null;
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
            UUID sourceOwner = resolveSourceOwnerId(sourceRef, source, store);
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

    @Nullable
    private Ref<EntityStore> resolveSourceRef(
            @Nullable Damage.Source source,
            @Nonnull Store<EntityStore> store
    ) {
        if (source instanceof Damage.EntitySource entitySource) {
            return valid(entitySource.getRef()) ? entitySource.getRef() : null;
        }
        if (source instanceof Damage.ProjectileSource projectileSource) {
            if (valid(projectileSource.getRef())) {
                return projectileSource.getRef();
            }
            if (valid(projectileSource.getProjectile())) {
                return projectileSource.getProjectile();
            }
        }
        return null;
    }

    private boolean valid(@Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid();
    }

    @Nullable
    private CombatParticipantView participant(
            @Nullable Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        UUID entityId = resolveEntityId(ref, store);
        return entityId == null
                ? null
                : new CombatParticipantView(
                        entityId, resolveOwnerId(ref, store));
    }

    @Nullable
    static UUID resolveEntityId(
            @Nullable Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        ComponentType<EntityStore, UUIDComponent> uuidType =
                UUIDComponent.getComponentType();
        if (uuidType != null) {
            UUIDComponent identity = store.getComponent(ref, uuidType);
            if (identity != null && identity.getUuid() != null) {
                return identity.getUuid();
            }
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getUuid() != null) {
            return player.getUuid();
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc == null ? null : npc.getUuid();
    }

    @Nonnull
    private String damageType(@Nonnull Damage damage) {
        return damage.getCause() == null || damage.getCause().getId() == null
                ? "unknown"
                : String.valueOf(damage.getCause().getId());
    }

    private boolean isNpcRef(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return ref != null && ref.isValid() && CompanionRoleIdResolver.resolveRoleId(ref, store) != null;
    }

    private boolean isPlayerRef(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        return ref != null && ref.isValid() && store.getComponent(ref, Player.getComponentType()) != null;
    }

    @Nullable
    private UUID resolveSourceOwnerId(@Nullable Ref<EntityStore> sourceRef,
                                      @Nullable Damage.Source source,
                                      @Nonnull Store<EntityStore> store) {
        UUID ownerId = resolveOwnerId(sourceRef, store);
        if (ownerId != null) {
            return ownerId;
        }
        if (source instanceof Damage.EntitySource entitySource) {
            return resolveOwnerId(entitySource.getRef(), store);
        }
        if (source instanceof Damage.ProjectileSource projectileSource) {
            ownerId = resolveOwnerId(projectileSource.getRef(), store);
            if (ownerId != null) {
                return ownerId;
            }
            return resolveOwnerId(projectileSource.getProjectile(), store);
        }
        return null;
    }

    @Nullable
    static UUID resolveOwnerId(@Nullable Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getUuid() != null) {
            return player.getUuid();
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
