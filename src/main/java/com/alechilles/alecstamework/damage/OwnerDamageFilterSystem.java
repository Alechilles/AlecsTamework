package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cancels damage based on owner/tamework protection settings.
 */
public final class OwnerDamageFilterSystem extends DamageEventSystem {
    private static final long SLOW_DAMAGE_FILTER_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(15L);

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> linksType;
    private final ComponentType<EntityStore, TameworkNpcNameComponent> npcNameType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final HytaleLogger logger;
    private final SimpleClaimsTamedDamagePolicy damagePolicy;
    private final DamageAttackerAttributionResolver attackerAttributionResolver;

    public OwnerDamageFilterSystem(HytaleLogger logger) {
        this(logger, new SimpleClaimsTamedDamagePolicy());
    }

    OwnerDamageFilterSystem(HytaleLogger logger,
                            @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy) {
        this.ownerType = TameworkOwnerComponent.getComponentType();
        this.linksType = TameworkCommandLinksComponent.getComponentType();
        this.npcNameType = TameworkNpcNameComponent.getComponentType();
        this.transformType = TransformComponent.getComponentType();
        this.npcType = NPCEntity.getComponentType();
        this.logger = logger;
        this.damagePolicy = damagePolicy;
        this.attackerAttributionResolver = new DamageAttackerAttributionResolver();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(npcType);
    }

    @Override
    public void handle(int index,
                       ArchetypeChunk<EntityStore> chunk,
                       Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer,
                       Damage damage) {
        boolean debugLag = isLagDebugEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        try {
            if (DamagePolicyEventGate.shouldSkip(damage)) {
                return;
            }
            Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
            if (targetRef == null || !targetRef.isValid()) {
                return;
            }

            UUID attackerPlayerUuid = attackerAttributionResolver.resolve(damage.getSource(), store);
            TwGlobalConfig globalConfig = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
            if (globalConfig == null) {
                globalConfig = TwGlobalConfig.resolveActive();
            }
            if (globalConfig == null) {
                globalConfig = TwGlobalConfig.defaultConfig();
            }
            TamedDamageDecision decision = damagePolicy.evaluate(
                    resolveOwnerPolicy(index, chunk, store, targetRef),
                    targetRef,
                    store,
                    resolveWorldName(store),
                    resolveTargetPosition(index, chunk, store, targetRef),
                    attackerPlayerUuid,
                    globalConfig
            );
            if (!decision.allowed()) {
                cancelDamage(damage);
            }
        } finally {
            if (debugLag) {
                logSlowDamageFilter(startedNs, damage);
            }
        }
    }

    @Nonnull
    private TamedDamageOwnerPolicy resolveOwnerPolicy(int index,
                                                      ArchetypeChunk<EntityStore> chunk,
                                                      Store<EntityStore> store,
                                                      Ref<EntityStore> targetRef) {
        TameworkOwnerComponent owner = ownerType == null ? null : chunk.getComponent(index, ownerType);
        TameworkCommandLinksComponent links = linksType == null ? null : chunk.getComponent(index, linksType);
        TameworkNpcNameComponent npcName = npcNameType == null ? null : chunk.getComponent(index, npcNameType);
        String roleId = CompanionRoleIdResolver.resolveRoleId(targetRef, store);
        return TamedDamageOwnerPolicyResolver.resolve(owner, links, npcName, roleId).policy();
    }

    @Nullable
    private Vector3d resolveTargetPosition(int index,
                                           ArchetypeChunk<EntityStore> chunk,
                                           Store<EntityStore> store,
                                           Ref<EntityStore> targetRef) {
        ComponentType<EntityStore, TransformComponent> type = transformType != null
                ? transformType
                : TransformComponent.getComponentType();
        if (type == null) {
            return null;
        }
        TransformComponent chunkTransform = chunk.getComponent(index, type);
        if (chunkTransform != null && chunkTransform.getPosition() != null) {
            return chunkTransform.getPosition();
        }
        TransformComponent storeTransform = store.getComponent(targetRef, type);
        return storeTransform != null ? storeTransform.getPosition() : null;
    }

    @Nullable
    private String resolveWorldName(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        return store.getExternalData().getWorld().getName();
    }

    private void cancelDamage(Damage damage) {
        damage.setAmount(0f);
        damage.setCancelled(true);
    }

    private boolean isLagDebugEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugLagEnabled();
    }

    private void logSlowDamageFilter(long startedNs, Damage damage) {
        if (startedNs <= 0L || logger == null) {
            return;
        }
        long elapsedNs = System.nanoTime() - startedNs;
        if (elapsedNs < SLOW_DAMAGE_FILTER_THRESHOLD_NS) {
            return;
        }
        double elapsedMs = elapsedNs / 1_000_000.0;
        String sourceType = "<none>";
        if (damage != null && damage.getSource() != null) {
            sourceType = damage.getSource().getClass().getSimpleName();
        }
        logger.at(Level.WARNING).log(
                "Tamework lag probe: owner damage filter took "
                        + elapsedMs
                        + "ms (sourceType="
                        + sourceType
                        + ")."
        );
    }
}
