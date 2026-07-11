package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts a non-revivable companion death into a durable APPLYING owner-release operation. Normal
 * lethal damage is paused before {@link DeathComponent}; direct-death paths retain their corpse
 * until the same durable transition has applied.
 */
public final class CompanionPermanentDeathCoordinator {
    private final OwnerMutationScheduler scheduler;
    private final ConcurrentHashMap<UUID, PendingDeath> pendingByNpc = new ConcurrentHashMap<>();
    private volatile WarningSink warningSink = message -> { };

    public CompanionPermanentDeathCoordinator(@Nonnull OwnerMutationScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public void setWarningSink(@Nullable WarningSink warningSink) {
        this.warningSink = warningSink == null ? message -> { } : warningSink;
    }

    /** Returns true when the caller must cancel the original fatal damage event. */
    public boolean interceptLethalDamage(@Nonnull Ref<EntityStore> npcRef,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull UUID npcUuid,
                                         @Nonnull UUID ownerUuid,
                                         @Nonnull Damage damage,
                                         float finalDamage) {
        PendingDeath pending = new PendingDeath(
                npcUuid, ownerUuid, damage, finalDamage, true, "lethal-damage"
        );
        prepare(npcRef, store, pending);
        return true;
    }

    /** Returns true when the caller must remove the just-added death component while SQLite prepares. */
    public boolean interceptExistingDeath(@Nonnull Ref<EntityStore> npcRef,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull UUID npcUuid,
                                          @Nonnull UUID ownerUuid,
                                          @Nonnull DeathComponent death) {
        Damage damage = death.getDeathInfo();
        if (damage == null) {
            damage = new Damage(Damage.NULL_SOURCE, DamageCause.PHYSICAL, 0.0f);
        }
        PendingDeath pending = new PendingDeath(
                npcUuid, ownerUuid, damage, 0.0f, false, "direct-death-component"
        );
        prepare(npcRef, store, pending);
        return true;
    }

    public boolean isPending(@Nullable UUID npcUuid) {
        return npcUuid != null && pendingByNpc.containsKey(npcUuid);
    }

    private void prepare(@Nonnull Ref<EntityStore> npcRef,
                         @Nonnull Store<EntityStore> store,
                         @Nonnull PendingDeath pending) {
        PendingDeath existing = pendingByNpc.putIfAbsent(pending.npcUuid(), pending);
        if (existing != null) {
            return;
        }
        JsonObject context = new JsonObject();
        context.addProperty("permanentDeath", true);
        context.addProperty("deathSource", pending.source());
        boolean scheduled = scheduler.schedulePermanentRelease(
                npcRef,
                store,
                true,
                "permanent-death:" + pending.npcUuid(),
                context.toString(),
                callbacks(pending)
        );
        if (!scheduled) {
            pendingByNpc.remove(pending.npcUuid(), pending);
            warn("Permanent companion death was held because durable release preparation failed: npc="
                    + pending.npcUuid());
        }
    }

    @Nonnull
    private OwnerMutationScheduler.MutationCallbacks callbacks(@Nonnull PendingDeath pending) {
        return new OwnerMutationScheduler.MutationCallbacks() {
            @Override
            public boolean beforeApply(@Nonnull String profileId,
                                       @Nonnull OwnerMutationContext context) {
                return validateCurrentTarget(context, pending);
            }

            @Override
            public void onApplied(@Nonnull OwnerPopulationDecision decision,
                                  @Nonnull String profileId,
                                  @Nonnull OwnerMutationContext context) {
                applyDeath(context, pending);
                pendingByNpc.remove(pending.npcUuid(), pending);
            }

            @Override
            public void onApplyCompensated(@Nonnull String profileId,
                                           @Nonnull String reason,
                                           @Nonnull OwnerMutationContext context) {
                pendingByNpc.remove(pending.npcUuid(), pending);
            }

            @Override
            public void onDenied(@Nonnull String reason,
                                 @Nullable OwnerPopulationDecision decision) {
                pendingByNpc.remove(pending.npcUuid(), pending);
                warn("Permanent companion death remained canceled: npc=" + pending.npcUuid()
                        + ", reason=" + reason);
            }

            @Override
            public void onDurabilityDegraded(@Nonnull String reason) {
                pendingByNpc.remove(pending.npcUuid(), pending);
                warn("Permanent companion death entered recovery quarantine: npc="
                        + pending.npcUuid() + ", reason=" + reason);
            }
        };
    }

    private boolean validateCurrentTarget(@Nonnull OwnerMutationContext context,
                                          @Nonnull PendingDeath pending) {
        if (!context.npcUuid().equals(pending.npcUuid()) || !context.npcRef().isValid()) {
            return false;
        }
        TameworkOwnerComponent owner = context.store().getComponent(
                context.npcRef(), TameworkOwnerComponent.getComponentType()
        );
        if (owner == null || !pending.ownerUuid().equals(owner.getOwnerId())) {
            return false;
        }
        TameworkCommandLinksComponent links = context.store().getComponent(
                context.npcRef(), TameworkCommandLinksComponent.getComponentType()
        );
        String roleId = CompanionRoleIdResolver.resolveRoleId(context.npcRef(), context.store());
        if (CompanionRevivePolicy.supportsRevive(roleId, links)) {
            return false;
        }
        if (!pending.subtractHealth()) {
            return hasDeathComponent(context);
        }
        return !hasDeathComponent(context) && isLethal(context, pending.finalDamage());
    }

    private void applyDeath(@Nonnull OwnerMutationContext context,
                            @Nonnull PendingDeath pending) {
        if (!context.npcRef().isValid()) {
            throw new IllegalStateException("Permanent-death target disappeared before apply.");
        }
        if (!pending.subtractHealth()) {
            releaseExistingDeathHold(context);
            return;
        }
        Damage damage = pending.damage();
        damage.setCancelled(false);
        EntityStatMap stats = context.store().getComponent(
                context.npcRef(), EntityStatMap.getComponentType()
        );
        EntityStatValue health = health(stats);
        if (stats == null || health == null) {
            throw new IllegalStateException("Permanent-death target has no health state.");
        }
        damage.setAmount(pending.finalDamage());
        float remaining = stats.subtractStatValue(
                DefaultEntityStatTypes.getHealth(), pending.finalDamage()
        );
        if (remaining > health.getMin()) {
            throw new IllegalStateException("Prepared permanent damage was no longer lethal.");
        }
        DeathComponent.tryAddComponent(context.store(), context.npcRef(), damage);
        if (!hasDeathComponent(context)) {
            throw new IllegalStateException("Engine did not retain the prepared death component.");
        }
    }

    private static void releaseExistingDeathHold(@Nonnull OwnerMutationContext context) {
        if (!hasDeathComponent(context)) {
            throw new IllegalStateException("Prepared direct-death target is no longer dead.");
        }
        NPCEntity npc = context.store().getComponent(
                context.npcRef(), NPCEntity.getComponentType()
        );
        double delaySeconds = npc == null || npc.getRole() == null
                ? 0.0 : Math.max(0.0, npc.getRole().getDeathAnimationTime());
        String deathParticles = npc == null || npc.getRole() == null
                ? null : npc.getRole().getDeathParticles();
        context.store().putComponent(
                context.npcRef(),
                DeferredCorpseRemoval.getComponentType(),
                new DeferredCorpseRemoval(delaySeconds, deathParticles)
        );
    }

    private static boolean isLethal(@Nonnull OwnerMutationContext context, float finalDamage) {
        EntityStatMap stats = context.store().getComponent(
                context.npcRef(), EntityStatMap.getComponentType()
        );
        EntityStatValue health = health(stats);
        return health != null && health.get() - finalDamage <= health.getMin();
    }

    @Nullable
    private static EntityStatValue health(@Nullable EntityStatMap stats) {
        return stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
    }

    private static boolean hasDeathComponent(@Nonnull OwnerMutationContext context) {
        ComponentTypeAccess.requireDeathType();
        return context.store().getArchetype(context.npcRef()).contains(DeathComponent.getComponentType());
    }

    private void warn(@Nonnull String message) {
        try {
            warningSink.warn(message);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostic delivery cannot change fail-closed death behavior.
        }
    }

    private record PendingDeath(@Nonnull UUID npcUuid,
                                @Nonnull UUID ownerUuid,
                                @Nonnull Damage damage,
                                float finalDamage,
                                boolean subtractHealth,
                                @Nonnull String source) {
    }

    /** Keeps the engine component registration failure explicit at the narrow use site. */
    private static final class ComponentTypeAccess {
        private static void requireDeathType() {
            if (DeathComponent.getComponentType() == null) {
                throw new IllegalStateException("Death component type is unavailable.");
            }
        }
    }

    @FunctionalInterface
    public interface WarningSink {
        void warn(@Nonnull String message);
    }
}
