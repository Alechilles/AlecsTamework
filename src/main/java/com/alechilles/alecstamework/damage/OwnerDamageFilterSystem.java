package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
    private final HytaleLogger logger;
    private final SimpleClaimsTamedDamagePolicyService claimDamagePolicyService;

    public OwnerDamageFilterSystem(HytaleLogger logger) {
        this.ownerType = TameworkOwnerComponent.getComponentType();
        this.linksType = TameworkCommandLinksComponent.getComponentType();
        this.npcNameType = TameworkNpcNameComponent.getComponentType();
        this.transformType = TransformComponent.getComponentType();
        this.logger = logger;
        this.claimDamagePolicyService = new SimpleClaimsTamedDamagePolicyService();
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
        boolean debugLag = isLagDebugEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        try {
            if (damage == null || damage.isCancelled()) {
                return;
            }
            Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
            if (targetRef == null || !targetRef.isValid()) {
                return;
            }

            UUID attackerPlayerUuid = resolveAttackerPlayerUuid(damage.getSource(), store);
            if (shouldDenyOwnedDamage(index, chunk, store, targetRef, attackerPlayerUuid)) {
                cancelDamage(damage);
                return;
            }

            TwGlobalConfig globalConfig = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
            if (globalConfig == null) {
                globalConfig = TwGlobalConfig.resolveActive();
            }
            if (globalConfig == null) {
                globalConfig = TwGlobalConfig.defaultConfig();
            }
            SimpleClaimsTamedDamagePolicyService.Decision claimDecision = claimDamagePolicyService.evaluate(
                    targetRef,
                    store,
                    resolveTargetPosition(index, chunk, store, targetRef),
                    attackerPlayerUuid,
                    globalConfig
            );
            if (!claimDecision.allowed()) {
                cancelDamage(damage);
            }
        } finally {
            if (debugLag) {
                logSlowDamageFilter(startedNs, damage);
            }
        }
    }

    private boolean shouldDenyOwnedDamage(int index,
                                          ArchetypeChunk<EntityStore> chunk,
                                          Store<EntityStore> store,
                                          Ref<EntityStore> targetRef,
                                          @Nullable UUID attackerPlayerUuid) {
        UUID ownerId = resolveOwnerId(index, chunk, store, targetRef);
        if (ownerId == null) {
            return false;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(targetRef, store);
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(roleId);
        boolean blockOwnerDamage = TameworkRuntimeSettings.blockOwnerDamage(settings.isBlockOwnerDamage());
        boolean blockAllPlayerDamageIfOwned =
                TameworkRuntimeSettings.blockAllPlayerDamageIfOwned(settings.isBlockAllPlayerDamageIfOwned());
        boolean invulnerableIfOwned = TameworkRuntimeSettings.invulnerableIfOwned(settings.isInvulnerableIfOwned());
        if (!blockOwnerDamage && !blockAllPlayerDamageIfOwned && !invulnerableIfOwned) {
            return false;
        }
        if (invulnerableIfOwned) {
            return true;
        }
        if (attackerPlayerUuid == null) {
            return false;
        }
        if (blockAllPlayerDamageIfOwned) {
            return true;
        }
        return blockOwnerDamage && ownerId.equals(attackerPlayerUuid);
    }

    @Nullable
    private UUID resolveOwnerId(int index,
                                @Nonnull ArchetypeChunk<EntityStore> chunk,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull Ref<EntityStore> targetRef) {
        ComponentType<EntityStore, TameworkOwnerComponent> resolvedOwnerType = ownerType != null
                ? ownerType
                : TameworkOwnerComponent.getComponentType();
        if (resolvedOwnerType != null) {
            TameworkOwnerComponent owner = chunk.getComponent(index, resolvedOwnerType);
            if (owner == null) {
                owner = store.getComponent(targetRef, resolvedOwnerType);
            }
            if (owner != null && owner.getOwnerId() != null) {
                return owner.getOwnerId();
            }
        }

        ComponentType<EntityStore, TameworkCommandLinksComponent> resolvedLinksType = linksType != null
                ? linksType
                : TameworkCommandLinksComponent.getComponentType();
        if (resolvedLinksType != null) {
            TameworkCommandLinksComponent links = chunk.getComponent(index, resolvedLinksType);
            if (links == null) {
                links = store.getComponent(targetRef, resolvedLinksType);
            }
            if (links != null && links.getOwnerId() != null) {
                return links.getOwnerId();
            }
        }

        ComponentType<EntityStore, TameworkNpcNameComponent> resolvedNpcNameType = npcNameType != null
                ? npcNameType
                : TameworkNpcNameComponent.getComponentType();
        if (resolvedNpcNameType == null) {
            return null;
        }
        TameworkNpcNameComponent npcName = chunk.getComponent(index, resolvedNpcNameType);
        if (npcName == null) {
            npcName = store.getComponent(targetRef, resolvedNpcNameType);
        }
        return npcName != null ? npcName.getOwnerId() : null;
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
    private UUID resolveAttackerPlayerUuid(@Nullable Damage.Source source,
                                           @Nullable Store<EntityStore> store) {
        if (source == null || store == null) {
            return null;
        }
        if (source instanceof Damage.ProjectileSource projectileSource) {
            UUID sourcePlayerUuid = resolvePlayerUuidFromRef(projectileSource.getRef(), store);
            if (sourcePlayerUuid != null) {
                return sourcePlayerUuid;
            }
            return resolvePlayerUuidFromRef(projectileSource.getProjectile(), store);
        }
        if (source instanceof Damage.EntitySource entitySource) {
            return resolvePlayerUuidFromRef(entitySource.getRef(), store);
        }
        return null;
    }

    @Nullable
    private UUID resolvePlayerUuidFromRef(@Nullable Ref<EntityStore> sourceRef,
                                          @Nonnull Store<EntityStore> store) {
        if (sourceRef == null || !sourceRef.isValid()) {
            return null;
        }
        Player player = store.getComponent(sourceRef, Player.getComponentType());
        return player != null ? player.getUuid() : null;
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
