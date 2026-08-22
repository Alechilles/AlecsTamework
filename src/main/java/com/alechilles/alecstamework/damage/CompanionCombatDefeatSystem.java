package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.CombatParticipantView;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Removes bounded contribution state and publishes confirmed defeats. */
public final class CompanionCombatDefeatSystem
        extends DeathSystems.OnDeathSystem {
    private static final ConcurrentHashMap<
            String, CompanionCombatContributionLedger> WORLD_LEDGERS =
            new ConcurrentHashMap<>();

    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final Query<EntityStore> query;

    public CompanionCombatDefeatSystem(
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType
    ) {
        this.uuidType = Objects.requireNonNull(uuidType, "uuidType");
        this.query = Query.and(uuidType);
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        UUIDComponent identity = store.getComponent(reference, uuidType);
        World world = world(store);
        if (identity == null || identity.getUuid() == null || world == null) {
            return;
        }
        long occurredAtMs = System.currentTimeMillis();
        Optional<CompanionCombatContributionLedger.DefeatCredit> credit =
                remove(world, identity.getUuid(), occurredAtMs);
        if (credit.isEmpty()
                || !ActivityRuntime.hasCombatInterest(
                ActivityIds.COMBAT_DEFEAT)) {
            return;
        }
        CompanionCombatContributionLedger.DefeatCredit resolved =
                credit.orElseThrow();
        ActivityRuntime.publishCombatDefeat(
                resolved.operationId(),
                new CombatParticipantView(
                        identity.getUuid(),
                        CompanionCombatExperienceSystem.resolveOwnerId(
                                reference, store)),
                resolved.finalBlowCredit(),
                resolved.contributors(),
                resolved.ownerCredit(),
                occurredAtMs);
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    static void record(
            @Nonnull World world,
            @Nonnull UUID operationId,
            @Nonnull UUID targetId,
            @Nonnull UUID companionId,
            @Nullable UUID ownerId,
            double finalDamage,
            long occurredAtMs
    ) {
        WORLD_LEDGERS.computeIfAbsent(
                        world.getName(),
                        ignored -> new CompanionCombatContributionLedger())
                .record(
                        operationId, targetId, companionId, ownerId,
                        finalDamage, occurredAtMs);
    }

    /** Drops all bounded combat state for an unloaded world. */
    public static void clearWorld(@Nullable World world) {
        if (world == null) {
            return;
        }
        CompanionCombatContributionLedger removed =
                WORLD_LEDGERS.remove(world.getName());
        if (removed != null) {
            removed.clear();
        }
    }

    /** Drops all process-local combat state during plugin shutdown. */
    public static void clearAll() {
        WORLD_LEDGERS.clear();
    }

    private static Optional<CompanionCombatContributionLedger.DefeatCredit>
            remove(World world, UUID targetId, long occurredAtMs) {
        CompanionCombatContributionLedger ledger =
                WORLD_LEDGERS.get(world.getName());
        return ledger == null
                ? Optional.empty()
                : ledger.remove(targetId, occurredAtMs);
    }

    @Nullable
    private static World world(Store<EntityStore> store) {
        return store.getExternalData() == null
                ? null : store.getExternalData().getWorld();
    }
}
