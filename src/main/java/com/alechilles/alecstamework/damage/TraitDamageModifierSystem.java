package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Applies trait-driven incoming and outgoing damage multipliers.
 */
public final class TraitDamageModifierSystem extends DamageEventSystem {
    private static final String TOUGHNESS_MULTIPLIER_EFFECT_KEY = "DamageTakenMultiplier";
    private static final String DAMAGE_DEALT_MULTIPLIER_EFFECT_KEY = "DamageDealtMultiplier";
    private static final double EPSILON = 0.000001;

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
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
        if (damage == null || damage.isCancelled()) {
            return;
        }
        float amount = damage.getAmount();
        if (!(amount > 0.0f)) {
            return;
        }
        if (shouldBypassTraitModifiers(damage)) {
            return;
        }
        if (chunk == null || store == null) {
            return;
        }
        Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }

        double toughnessMultiplier = sanitizeMultiplier(TraitModifierService.resolveMultiplier(
                targetRef,
                store,
                TOUGHNESS_MULTIPLIER_EFFECT_KEY,
                1.0
        ));
        double takenMultiplier = resolveIncomingDamageMultiplier(toughnessMultiplier);
        double dealtMultiplier = sanitizeMultiplier(resolveSourceDamageMultiplier(damage, store));
        if (Math.abs(takenMultiplier - 1.0) <= EPSILON && Math.abs(dealtMultiplier - 1.0) <= EPSILON) {
            return;
        }

        double nextAmount = amount * takenMultiplier * dealtMultiplier;
        if (!Double.isFinite(nextAmount)) {
            return;
        }
        if (nextAmount <= 0.0) {
            damage.setAmount(0.0f);
            damage.setCancelled(true);
            return;
        }
        damage.setAmount((float) nextAmount);
    }

    static boolean shouldBypassTraitModifiers(Damage damage) {
        if (damage == null) {
            return false;
        }
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EnvironmentSource environmentSource)) {
            return false;
        }
        String type = environmentSource.getType();
        return type != null && type.equalsIgnoreCase(CompanionNeedsService.NEEDS_DAMAGE_SOURCE_TYPE);
    }

    private double resolveSourceDamageMultiplier(Damage damage, Store<EntityStore> store) {
        if (damage == null || store == null) {
            return 1.0;
        }
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) {
            return 1.0;
        }
        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (sourceRef == null || !sourceRef.isValid()) {
            return 1.0;
        }
        return TraitModifierService.resolveMultiplier(
                sourceRef,
                store,
                DAMAGE_DEALT_MULTIPLIER_EFFECT_KEY,
                1.0
        );
    }

    private double sanitizeMultiplier(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 1.0;
        }
        return multiplier;
    }

    static double resolveIncomingDamageMultiplier(double toughnessMultiplier) {
        if (!Double.isFinite(toughnessMultiplier) || toughnessMultiplier <= 0.0) {
            return 1.0;
        }
        double resolved = 1.0 / toughnessMultiplier;
        if (!Double.isFinite(resolved) || resolved <= 0.0) {
            return 1.0;
        }
        return resolved;
    }
}
