package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.function.BooleanSupplier;

/**
 * Cancels damage based on owner/tamework protection settings.
 */
public final class OwnerDamageFilterSystem extends DamageEventSystem {
    private static final long SLOW_DAMAGE_FILTER_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(15L);

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    private final BooleanSupplier blockOwnerDamage;
    private final BooleanSupplier blockAllPlayerDamageIfOwned;
    private final BooleanSupplier invulnerableIfOwned;
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    private final HytaleLogger logger;

    public OwnerDamageFilterSystem(BooleanSupplier blockOwnerDamage,
                                   BooleanSupplier blockAllPlayerDamageIfOwned,
                                   BooleanSupplier invulnerableIfOwned,
                                   HytaleLogger logger) {
        this.blockOwnerDamage = blockOwnerDamage != null ? blockOwnerDamage : () -> false;
        this.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned != null ? blockAllPlayerDamageIfOwned : () -> false;
        this.invulnerableIfOwned = invulnerableIfOwned != null ? invulnerableIfOwned : () -> false;
        this.ownerType = TameworkOwnerComponent.getComponentType();
        this.logger = logger;
    }

    @Override
    public Query<EntityStore> getQuery() {
        ComponentType<EntityStore, TameworkOwnerComponent> type = ownerType != null
                ? ownerType
                : TameworkOwnerComponent.getComponentType();
        return type != null ? Query.and(type) : Query.any();
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
            // Fast path if all protections are disabled.
            if (!blockOwnerDamage.getAsBoolean()
                    && !blockAllPlayerDamageIfOwned.getAsBoolean()
                    && !invulnerableIfOwned.getAsBoolean()) {
                return;
            }
            ComponentType<EntityStore, TameworkOwnerComponent> type = ownerType != null
                    ? ownerType
                    : TameworkOwnerComponent.getComponentType();
            if (type == null) {
                return;
            }
            // Only apply filters to owned NPCs.
            TameworkOwnerComponent owner = chunk.getComponent(index, type);
            if (owner == null || owner.getOwnerId() == null) {
                return;
            }
            // Optional full invulnerability for owned pets.
            if (invulnerableIfOwned.getAsBoolean()) {
                cancelDamage(damage);
                return;
            }
            // Owner-based rules only apply to player-sourced damage.
            Damage.Source source = damage.getSource();
            if (!(source instanceof Damage.EntitySource)) {
                return;
            }
            Ref<EntityStore> sourceRef = ((Damage.EntitySource) source).getRef();
            if (sourceRef == null || !sourceRef.isValid()) {
                return;
            }
            Player player = store.getComponent(sourceRef, Player.getComponentType());
            if (player == null) {
                return;
            }
            // Optionally block all player damage against owned pets.
            if (blockAllPlayerDamageIfOwned.getAsBoolean()) {
                cancelDamage(damage);
                return;
            }
            if (blockOwnerDamage.getAsBoolean() && owner.getOwnerId().equals(player.getUuid())) {
                cancelDamage(damage);
            }
        } finally {
            if (debugLag) {
                logSlowDamageFilter(startedNs, damage);
            }
        }
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
