package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionExpiryWarningSchedule;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;

/** Sends each finite bonded-lease expiry warning once to its current owner. */
public final class BondedCompanionExpiryWarningSystem
        extends TickingSystem<EntityStore> {
    private static final int MAXIMUM_LEASES_PER_WORLD = 64;
    private static final long INTERVAL_NANOS = 1_000_000_000L;

    private final TameworkBondedCompanionComposition composition;
    private final TameworkUiMessageService notifications =
            new TameworkUiMessageService();
    private final Set<WarningKey> delivered = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Long> nextRuns = new ConcurrentHashMap<>();

    public BondedCompanionExpiryWarningSystem(
            @Nonnull TameworkBondedCompanionComposition composition
    ) {
        this.composition = composition;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        EntityStore entityStore = store.getExternalData();
        World world = entityStore == null ? null : entityStore.getWorld();
        if (world == null || world.getName() == null) return;

        long nowNanos = System.nanoTime();
        Long next = nextRuns.compute(world.getName(), (ignored, prior) ->
                prior == null || nowNanos >= prior
                        ? nowNanos + INTERVAL_NANOS : prior);
        if (next == null || next != nowNanos + INTERVAL_NANOS) return;
        long nowMs = System.currentTimeMillis();
        List<BondedCompanionProjectionValidator.LeaseExpectation> leases =
                composition.activeLeasesInWorld(
                        world.getName(), MAXIMUM_LEASES_PER_WORLD);
        Set<String> activeLeaseTokens = new HashSet<>();
        for (var lease : leases) {
            activeLeaseTokens.add(lease.leaseToken());
            if (lease.phase()
                    != BondedCompanionProjectionValidator.LeasePhase.LIVE) {
                continue;
            }
            BondedCompanionExpiryWarningSchedule.warning(
                    lease.expiresAtMs(), nowMs).ifPresent(warning ->
                    sendIfDue(world, store, lease, warning));
        }
        delivered.removeIf(key -> key.worldKey().equals(world.getName())
                && !activeLeaseTokens.contains(key.leaseToken()));
    }

    private void sendIfDue(
            World world,
            Store<EntityStore> store,
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionExpiryWarningSchedule.Warning warning
    ) {
        WarningKey key = new WarningKey(world.getName(), lease.leaseToken(),
                warning.secondsRemaining());
        if (!delivered.add(key)) return;

        Player owner = resolveOwner(world, store, lease.ownerUuid());
        if (owner == null) {
            delivered.remove(key);
            return;
        }
        String message = companionName(
                composition.displayNameFor(lease), world, store,
                lease.liveNpcUuid())
                + " expires in " + warning.secondsRemaining() + "s";
        if (!notifications.show(owner, message, warning.style())) {
            delivered.remove(key);
            return;
        }
        applyModelEffect(world, store, lease, warning);
    }

    private void applyModelEffect(
            World world,
            Store<EntityStore> store,
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionExpiryWarningSchedule.Warning warning
    ) {
        BondedCompanionExpiryWarningSchedule.modelEffectId(
                warning, composition.expiryWarningEffectIdFor(lease))
                .ifPresent(effectId -> {
                    Ref<EntityStore> companionRef = world.getEntityRef(
                            lease.liveNpcUuid());
                    if (companionRef == null || !companionRef.isValid()) return;
                    EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
                    EffectControllerComponent controller = store.getComponent(
                            companionRef, EffectControllerComponent.getComponentType());
                    if (effect == null || controller == null) return;
                    controller.addEffect(companionRef, effect,
                            Math.max(0.05F, effect.getDuration()),
                            OverlapBehavior.OVERWRITE, store);
                });
    }

    private static Player resolveOwner(
            World world, Store<EntityStore> store, UUID ownerUuid
    ) {
        Ref<EntityStore> ownerRef = world.getEntityRef(ownerUuid);
        return ownerRef == null || !ownerRef.isValid()
                ? null : store.getComponent(ownerRef, Player.getComponentType());
    }

    private static String companionName(
            String durableName,
            World world,
            Store<EntityStore> store,
            UUID companionUuid
    ) {
        Ref<EntityStore> companionRef = world.getEntityRef(companionUuid);
        if (companionRef == null || !companionRef.isValid()) {
            return BondedCompanionExpiryWarningNameResolver.resolve(
                    durableName, null, null);
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType =
                TameworkNpcNameComponent.getComponentType();
        TameworkNpcNameComponent name = nameType == null ? null
                : store.getComponent(companionRef, nameType);
        String liveCustomName = name == null ? null : name.getName();
        NPCEntity npc = store.getComponent(companionRef, NPCEntity.getComponentType());
        return BondedCompanionExpiryWarningNameResolver.resolve(
                durableName, liveCustomName,
                npc == null ? null : npc.getRoleName());
    }

    private record WarningKey(
            String worldKey, String leaseToken, int secondsRemaining
    ) {
    }
}
