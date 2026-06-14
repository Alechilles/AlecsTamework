package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionGenderService;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.TalentIdCodec;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Captures and restores companion progression state (happiness, breeding, and traits) on spawner item metadata.
 */
final class SpawnerNpcProgressionMetadataService {
    ItemStack applyNpcProgressionMetadata(@Nullable ItemStack stack,
                                          @Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store) {
        if (stack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return stack;
        }
        ItemStack updated = applyHealthMetadata(stack, npcRef, store);
        updated = applyNeedsMetadata(updated, npcRef, store);
        updated = applyHappinessMetadata(updated, npcRef, store);
        updated = applyBreedingMetadata(updated, npcRef, store);
        updated = applyLevelingMetadata(updated, npcRef, store);
        updated = applyTraitsMetadata(updated, npcRef, store);
        updated = applyTalentsMetadata(updated, npcRef, store);
        return applyLifeStageMetadata(updated, npcRef, store);
    }

    void applyNpcProgressionFromItem(@Nullable ItemStack stack,
                                     @Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store) {
        if (stack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        restoreNeedsComponent(stack, npcRef, store);
        restoreHappinessComponent(stack, npcRef, store);
        restoreBreedingComponent(stack, npcRef, store);
        restoreLevelingComponent(stack, npcRef, store);
        restoreTraitsComponent(stack, npcRef, store);
        restoreTalentsComponent(stack, npcRef, store);
        restoreLifeStageComponent(stack, npcRef, store);
    }

    ItemStack clearProgressionMetadata(@Nullable ItemStack stack) {
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.HEALTH_PERCENT);
        updated = clearNeedsMetadata(updated);
        updated = clearHappinessMetadata(updated);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_HAPPINESS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_ENABLED);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LEVELING_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LEVELING_LEVEL);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LEVELING_TOTAL_XP);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TALENTS_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TALENTS_SPENT_POINTS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TALENTS_PURCHASED_IDS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_ROLL_SEED);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_VALUES);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SWITCH_SCALE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_GROWTH_SCALING_ENABLED);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_GENDER);
        return updated;
    }

    void applyNpcHealthFromItem(@Nullable ItemStack stack,
                                @Nullable Ref<EntityStore> npcRef,
                                @Nullable Store<EntityStore> store) {
        if (stack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        Double healthPercent = stack.getFromMetadataOrNull(TameworkMetadataKeys.HEALTH_PERCENT, Codec.DOUBLE);
        CompanionHealthStateService.applyStoredHealthPercent(npcRef, store, healthPercent);
    }

    private ItemStack applyHealthMetadata(ItemStack stack,
                                          Ref<EntityStore> npcRef,
                                          Store<EntityStore> store) {
        Double healthPercent = CompanionHealthStateService.captureHealthPercent(npcRef, store);
        if (healthPercent == null) {
            return clearMetadataKey(stack, TameworkMetadataKeys.HEALTH_PERCENT);
        }
        return stack.withMetadata(TameworkMetadataKeys.HEALTH_PERCENT, Codec.DOUBLE, healthPercent);
    }

    private ItemStack applyNeedsMetadata(ItemStack stack,
                                         Ref<EntityStore> npcRef,
                                         Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkNeedsComponent> type = TameworkNeedsComponent.getComponentType();
        if (type == null) {
            return stack;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            return clearNeedsMetadata(stack);
        }
        ItemStack updated = stack;
        if (component.getConfigId() != null && !component.getConfigId().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.NEEDS_CONFIG_ID, Codec.STRING, component.getConfigId());
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.NEEDS_CONFIG_ID);
        }
        updated = updated.withMetadata(TameworkMetadataKeys.NEEDS_HUNGER, Codec.DOUBLE, component.getHunger());
        updated = updated.withMetadata(TameworkMetadataKeys.NEEDS_THIRST, Codec.DOUBLE, component.getThirst());
        updated = updated.withMetadata(
                TameworkMetadataKeys.NEEDS_APPLIED_HAPPINESS_PENALTY,
                Codec.DOUBLE,
                component.getAppliedHappinessPenalty()
        );
        updated = updated.withMetadata(TameworkMetadataKeys.NEEDS_LAST_UPDATE_MS, Codec.LONG, component.getLastUpdateMs());
        updated = updated.withMetadata(
                TameworkMetadataKeys.NEEDS_LAST_PASSIVE_SWEEP_MS,
                Codec.LONG,
                component.getLastPassiveSweepMs()
        );
        return updated;
    }

    private ItemStack clearNeedsMetadata(@Nullable ItemStack stack) {
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.NEEDS_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.NEEDS_HUNGER);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.NEEDS_THIRST);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.NEEDS_APPLIED_HAPPINESS_PENALTY);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.NEEDS_LAST_UPDATE_MS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.NEEDS_LAST_PASSIVE_SWEEP_MS);
        return updated;
    }

    private ItemStack clearHappinessMetadata(@Nullable ItemStack stack) {
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.HAPPINESS_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_VALUE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS);
        return updated;
    }

    private ItemStack applyHappinessMetadata(ItemStack stack,
                                             Ref<EntityStore> npcRef,
                                             Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent component = type != null ? store.getComponent(npcRef, type) : null;
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkBreedingComponent breedingComponent = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        if (component == null && breedingComponent == null) {
            return clearHappinessMetadata(stack);
        }
        ItemStack updated = stack;
        String configId = component != null ? component.getConfigId() : null;
        if (configId != null && !configId.isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.HAPPINESS_CONFIG_ID, Codec.STRING, configId);
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_CONFIG_ID);
        }
        double value = component != null && Double.isFinite(component.getValue())
                ? component.getValue()
                : breedingComponent != null && Double.isFinite(breedingComponent.getHappiness())
                ? breedingComponent.getHappiness()
                : 0.0;
        long lastUpdateMs = component != null && component.getLastUpdateMs() > 0L
                ? component.getLastUpdateMs()
                : breedingComponent != null && breedingComponent.getLastHappinessUpdateMs() > 0L
                ? breedingComponent.getLastHappinessUpdateMs()
                : 0L;
        updated = updated.withMetadata(TameworkMetadataKeys.HAPPINESS_VALUE, Codec.DOUBLE, value);
        updated = updated.withMetadata(TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS, Codec.LONG, lastUpdateMs);
        return updated;
    }

    private void restoreNeedsComponent(ItemStack stack,
                                       Ref<EntityStore> npcRef,
                                       Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkNeedsComponent> type = TameworkNeedsComponent.getComponentType();
        if (type == null) {
            return;
        }
        String configId = stack.getFromMetadataOrNull(TameworkMetadataKeys.NEEDS_CONFIG_ID, Codec.STRING);
        Double hunger = stack.getFromMetadataOrNull(TameworkMetadataKeys.NEEDS_HUNGER, Codec.DOUBLE);
        Double thirst = stack.getFromMetadataOrNull(TameworkMetadataKeys.NEEDS_THIRST, Codec.DOUBLE);
        Double appliedPenalty = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.NEEDS_APPLIED_HAPPINESS_PENALTY,
                Codec.DOUBLE
        );
        Long lastUpdateMs = stack.getFromMetadataOrNull(TameworkMetadataKeys.NEEDS_LAST_UPDATE_MS, Codec.LONG);
        Long lastPassiveSweepMs = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.NEEDS_LAST_PASSIVE_SWEEP_MS,
                Codec.LONG
        );
        boolean hasData = (configId != null && !configId.isBlank())
                || hunger != null
                || thirst != null
                || appliedPenalty != null
                || lastUpdateMs != null
                || lastPassiveSweepMs != null;
        if (!hasData) {
            return;
        }
        TameworkNeedsComponent existing = store.getComponent(npcRef, type);
        TameworkNeedsComponent restored = buildPausedRestoredNeedsComponent(
                existing,
                configId,
                hunger,
                thirst,
                appliedPenalty
        );
        store.putComponent(
                npcRef,
                type,
                restored
        );
        CompanionNeedsService.ensureNeedsComponent(npcRef, store, null);
    }

    static TameworkNeedsComponent buildPausedRestoredNeedsComponent(@Nullable TameworkNeedsComponent existing,
                                                                    @Nullable String configId,
                                                                    @Nullable Double hunger,
                                                                    @Nullable Double thirst,
                                                                    @Nullable Double appliedPenalty) {
        String resolvedConfigId = (configId != null && !configId.isBlank())
                ? configId
                : existing != null ? existing.getConfigId() : null;
        double resolvedHunger = hunger != null
                ? hunger
                : existing != null ? existing.getHunger() : 0.0;
        double resolvedThirst = thirst != null
                ? thirst
                : existing != null ? existing.getThirst() : 0.0;
        double resolvedPenalty = appliedPenalty != null
                ? appliedPenalty
                : existing != null ? existing.getAppliedHappinessPenalty() : 0.0;

        // Capture items pause needs progression while stowed, so restored timers must restart live ticking.
        return new TameworkNeedsComponent(
                resolvedConfigId,
                resolvedHunger,
                resolvedThirst,
                resolvedPenalty,
                0L,
                0L
        );
    }

    @Nullable
    static String resolveCapturedGenderForMetadata(@Nullable TameworkLifeStageComponent component,
                                                   @Nullable String resolvedGender) {
        String componentGender = component != null ? CompanionGenderService.normalizeGender(component.getGender()) : null;
        if (componentGender != null) {
            return componentGender;
        }
        return CompanionGenderService.normalizeGender(resolvedGender);
    }

    private ItemStack applyBreedingMetadata(ItemStack stack,
                                            Ref<EntityStore> npcRef,
                                            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type == null) {
            return stack;
        }
        TameworkBreedingComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.BREEDING_CONFIG_ID);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_HAPPINESS);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_ENABLED);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID);
            return updated;
        }
        ItemStack updated = stack;
        if (component.getConfigId() != null && !component.getConfigId().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.BREEDING_CONFIG_ID, Codec.STRING, component.getConfigId());
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_CONFIG_ID);
        }
        updated = updated.withMetadata(
                TameworkMetadataKeys.BREEDING_HAPPINESS,
                Codec.DOUBLE,
                resolveCurrentHappiness(npcRef, store, component.getHappiness())
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.BREEDING_ENABLED,
                Codec.BOOLEAN,
                component.isEnabled()
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL,
                Codec.LONG,
                component.getCooldownUntilMs()
        );
        UUID partner = component.getLastPartnerUuid();
        if (partner != null) {
            updated = updated.withMetadata(TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID, Codec.UUID_STRING, partner);
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID);
        }
        return updated;
    }

    private double resolveCurrentHappiness(Ref<EntityStore> npcRef,
                                           Store<EntityStore> store,
                                           double fallback) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType != null) {
            TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
            if (happiness != null && Double.isFinite(happiness.getValue())) {
                return happiness.getValue();
            }
        }
        return fallback;
    }

    private ItemStack applyTraitsMetadata(ItemStack stack,
                                          Ref<EntityStore> npcRef,
                                          Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        if (type == null) {
            return stack;
        }
        TameworkTraitsComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.TRAITS_CONFIG_ID);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_ROLL_SEED);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_VALUES);
            return updated;
        }
        ItemStack updated = stack;
        if (component.getConfigId() != null && !component.getConfigId().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.TRAITS_CONFIG_ID, Codec.STRING, component.getConfigId());
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_CONFIG_ID);
        }
        updated = updated.withMetadata(TameworkMetadataKeys.TRAITS_ROLL_SEED, Codec.LONG, component.getRollSeed());
        String encodedValues = TraitValueCodec.encode(component.getTraitValues());
        if (encodedValues != null && !encodedValues.isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.TRAITS_VALUES, Codec.STRING, encodedValues);
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_VALUES);
        }
        return updated;
    }

    private ItemStack applyLevelingMetadata(ItemStack stack,
                                            Ref<EntityStore> npcRef,
                                            Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) {
            return stack;
        }
        TameworkLevelingComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.LEVELING_CONFIG_ID);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LEVELING_LEVEL);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LEVELING_TOTAL_XP);
            return updated;
        }
        ItemStack updated = stack;
        if (component.getConfigId() != null && !component.getConfigId().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.LEVELING_CONFIG_ID, Codec.STRING, component.getConfigId());
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LEVELING_CONFIG_ID);
        }
        updated = updated.withMetadata(TameworkMetadataKeys.LEVELING_LEVEL, Codec.INTEGER, component.getLevel());
        updated = updated.withMetadata(TameworkMetadataKeys.LEVELING_TOTAL_XP, Codec.DOUBLE, component.getTotalXp());
        return updated;
    }

    private ItemStack applyTalentsMetadata(ItemStack stack,
                                           Ref<EntityStore> npcRef,
                                           Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        if (type == null) {
            return stack;
        }
        TameworkTalentsComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.TALENTS_CONFIG_ID);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TALENTS_SPENT_POINTS);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TALENTS_PURCHASED_IDS);
            return updated;
        }
        ItemStack updated = stack;
        if (component.getConfigId() != null && !component.getConfigId().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.TALENTS_CONFIG_ID, Codec.STRING, component.getConfigId());
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TALENTS_CONFIG_ID);
        }
        updated = updated.withMetadata(TameworkMetadataKeys.TALENTS_SPENT_POINTS, Codec.INTEGER, component.getSpentPoints());
        String encodedIds = TalentIdCodec.encode(component.getPurchasedTalentIds());
        if (encodedIds != null && !encodedIds.isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.TALENTS_PURCHASED_IDS, Codec.STRING, encodedIds);
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.TALENTS_PURCHASED_IDS);
        }
        return updated;
    }

    private void restoreBreedingComponent(ItemStack stack,
                                          Ref<EntityStore> npcRef,
                                          Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type == null) {
            return;
        }
        String configId = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_CONFIG_ID, Codec.STRING);
        Double legacyHappiness = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_HAPPINESS, Codec.DOUBLE);
        Boolean enabled = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_ENABLED, Codec.BOOLEAN);
        Long cooldown = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL, Codec.LONG);
        UUID partner = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID, Codec.UUID_STRING);
        boolean hasData = (configId != null && !configId.isBlank())
                || legacyHappiness != null
                || enabled != null
                || cooldown != null
                || partner != null;
        if (!hasData) {
            return;
        }
        TameworkBreedingComponent existing = store.getComponent(npcRef, type);
        String resolvedConfigId = (configId != null && !configId.isBlank())
                ? configId
                : existing != null ? existing.getConfigId() : null;
        Double currentHappiness = getRestoredHappiness(npcRef, store);
        double value = currentHappiness != null
                ? currentHappiness
                : legacyHappiness != null
                ? legacyHappiness
                : existing != null
                ? existing.getHappiness()
                : 0.0;
        long lastHappinessUpdateMs = getRestoredHappinessTimestamp(npcRef, store);
        long cooldownUntil = cooldown != null
                ? cooldown
                : existing != null
                ? existing.getCooldownUntilMs()
                : 0L;
        long cooldownStartedAtMs = existing != null ? existing.getCooldownStartedAtMs() : 0L;
        long cooldownDurationMs = existing != null ? existing.getCooldownDurationMs() : 0L;
        if (cooldownUntil > 0L && cooldownDurationMs <= 0L) {
            long now = BreedingTimeService.resolveCurrentTimeMs(store);
            cooldownDurationMs = Math.max(0L, cooldownUntil - now);
            cooldownStartedAtMs = cooldownDurationMs > 0L ? now : 0L;
        }
        boolean breedingEnabled = enabled != null
                ? enabled
                : existing != null && existing.isEnabled();
        UUID lastPartner = partner != null
                ? partner
                : existing != null ? existing.getLastPartnerUuid() : null;
        boolean ready = false;
        if (breedingEnabled && resolvedConfigId != null && !resolvedConfigId.isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveById(resolvedConfigId);
            if (config != null) {
                String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
                ready = value >= TameworkRuntimeSettings.breedingHappinessThreshold(
                        config.resolveHappiness(roleId).getThreshold(),
                        TwHappinessConfig.isEnabledForRole(roleId)
                );
            }
        }
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                resolvedConfigId,
                value,
                lastHappinessUpdateMs > 0L ? lastHappinessUpdateMs : System.currentTimeMillis(),
                ready,
                breedingEnabled,
                cooldownUntil,
                lastPartner,
                cooldownStartedAtMs,
                cooldownDurationMs
        );
        store.putComponent(npcRef, type, component);
    }

    private void restoreHappinessComponent(ItemStack stack,
                                           Ref<EntityStore> npcRef,
                                           Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type = TameworkHappinessComponent.getComponentType();
        if (type == null) {
            return;
        }
        String configId = stack.getFromMetadataOrNull(TameworkMetadataKeys.HAPPINESS_CONFIG_ID, Codec.STRING);
        Double value = stack.getFromMetadataOrNull(TameworkMetadataKeys.HAPPINESS_VALUE, Codec.DOUBLE);
        Long lastUpdate = stack.getFromMetadataOrNull(TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS, Codec.LONG);
        Double legacyBreedingHappiness = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_HAPPINESS, Codec.DOUBLE);
        boolean hasData = (configId != null && !configId.isBlank())
                || value != null
                || lastUpdate != null
                || legacyBreedingHappiness != null;
        if (!hasData) {
            return;
        }
        TameworkHappinessComponent existing = store.getComponent(npcRef, type);
        String resolvedConfigId = (configId != null && !configId.isBlank())
                ? configId
                : existing != null ? existing.getConfigId() : null;
        double resolvedValue = value != null
                ? value
                : legacyBreedingHappiness != null
                ? legacyBreedingHappiness
                : existing != null
                ? existing.getValue()
                : 0.0;
        long resolvedLastUpdate = lastUpdate != null && lastUpdate > 0L
                ? lastUpdate
                : existing != null && existing.getLastUpdateMs() > 0L
                ? existing.getLastUpdateMs()
                : System.currentTimeMillis();
        store.putComponent(
                npcRef,
                type,
                new TameworkHappinessComponent(resolvedConfigId, resolvedValue, resolvedLastUpdate)
        );
    }

    @Nullable
    private Double getRestoredHappiness(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return null;
        }
        TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
        if (happiness == null || !Double.isFinite(happiness.getValue())) {
            return null;
        }
        return happiness.getValue();
    }

    private long getRestoredHappinessTimestamp(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return 0L;
        }
        TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
        return happiness != null ? happiness.getLastUpdateMs() : 0L;
    }

    private void restoreTraitsComponent(ItemStack stack,
                                        Ref<EntityStore> npcRef,
                                        Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTraitsComponent> type = TameworkTraitsComponent.getComponentType();
        if (type == null) {
            return;
        }
        String configId = stack.getFromMetadataOrNull(TameworkMetadataKeys.TRAITS_CONFIG_ID, Codec.STRING);
        Long seed = stack.getFromMetadataOrNull(TameworkMetadataKeys.TRAITS_ROLL_SEED, Codec.LONG);
        String values = stack.getFromMetadataOrNull(TameworkMetadataKeys.TRAITS_VALUES, Codec.STRING);
        boolean hasData = (configId != null && !configId.isBlank())
                || seed != null
                || (values != null && !values.isBlank());
        if (!hasData) {
            return;
        }
        long resolvedSeed = seed != null ? seed : 0L;
        TameworkTraitsComponent component = new TameworkTraitsComponent(
                configId,
                resolvedSeed,
                TraitValueCodec.decode(values)
        );
        store.putComponent(npcRef, type, component);
    }

    private void restoreLevelingComponent(ItemStack stack,
                                          Ref<EntityStore> npcRef,
                                          Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) {
            return;
        }
        String configId = stack.getFromMetadataOrNull(TameworkMetadataKeys.LEVELING_CONFIG_ID, Codec.STRING);
        Integer level = stack.getFromMetadataOrNull(TameworkMetadataKeys.LEVELING_LEVEL, Codec.INTEGER);
        Double totalXp = stack.getFromMetadataOrNull(TameworkMetadataKeys.LEVELING_TOTAL_XP, Codec.DOUBLE);
        boolean hasData = (configId != null && !configId.isBlank()) || level != null || totalXp != null;
        if (!hasData) {
            return;
        }
        TameworkLevelingComponent component = new TameworkLevelingComponent(
                configId,
                level != null ? level : 1,
                0.0,
                totalXp != null ? totalXp : 0.0
        );
        store.putComponent(npcRef, type, component);
        CompanionLevelingService.ensureLevelingComponent(
                npcRef,
                store,
                CompanionRoleIdResolver.resolveRoleId(npcRef, store)
        );
    }

    private void restoreTalentsComponent(ItemStack stack,
                                         Ref<EntityStore> npcRef,
                                         Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        if (type == null) {
            return;
        }
        String configId = stack.getFromMetadataOrNull(TameworkMetadataKeys.TALENTS_CONFIG_ID, Codec.STRING);
        Integer spentPoints = stack.getFromMetadataOrNull(TameworkMetadataKeys.TALENTS_SPENT_POINTS, Codec.INTEGER);
        String purchasedIds = stack.getFromMetadataOrNull(TameworkMetadataKeys.TALENTS_PURCHASED_IDS, Codec.STRING);
        boolean hasData = (configId != null && !configId.isBlank())
                || spentPoints != null
                || (purchasedIds != null && !purchasedIds.isBlank());
        if (!hasData) {
            return;
        }
        TameworkTalentsComponent component = new TameworkTalentsComponent(
                configId,
                spentPoints != null ? spentPoints : 0,
                TalentIdCodec.decode(purchasedIds)
        );
        store.putComponent(npcRef, type, component);
    }

    private ItemStack applyLifeStageMetadata(ItemStack stack,
                                             Ref<EntityStore> npcRef,
                                             Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return stack;
        }
        TameworkLifeStageComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.LIFE_STAGE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SWITCH_SCALE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE_GROWTH_SCALING_ENABLED);
            return applyCapturedGenderMetadata(
                    updated,
                    resolveCapturedGenderForMetadata(null, CompanionGenderService.resolveGender(npcRef, store, null, null))
            );
        }
        ItemStack updated = stack;
        if (component.getStage() != null && !component.getStage().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.LIFE_STAGE, Codec.STRING, component.getStage());
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.LIFE_STAGE);
        }
        updated = updated.withMetadata(TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS, Codec.LONG, component.getBornAtMs());
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS,
                Codec.LONG,
                component.getAdolescentAtMs()
        );
        updated = updated.withMetadata(TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS, Codec.LONG, component.getAdultAtMs());
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS,
                Codec.LONG,
                component.getFullyGrownAtMs()
        );
        updated = updated.withMetadata(TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE, Codec.DOUBLE, component.getBabyScale());
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE,
                Codec.DOUBLE,
                component.getAdolescentScale()
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SWITCH_SCALE,
                Codec.DOUBLE,
                component.getAdolescentSwitchScale()
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE,
                Codec.DOUBLE,
                component.getAdultStartScale()
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE,
                Codec.DOUBLE,
                component.getAdultSwitchScale()
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE,
                Codec.DOUBLE,
                component.getAdultScale()
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.LIFE_STAGE_GROWTH_SCALING_ENABLED,
                Codec.BOOLEAN,
                component.isGrowthScalingEnabled()
        );
        updated = applyCapturedGenderMetadata(
                updated,
                resolveCapturedGenderForMetadata(component, CompanionGenderService.resolveGender(npcRef, store, null, null))
        );
        return updated;
    }

    private ItemStack applyCapturedGenderMetadata(ItemStack stack, @Nullable String gender) {
        if (gender != null && !gender.isBlank()) {
            return stack.withMetadata(TameworkMetadataKeys.LIFE_STAGE_GENDER, Codec.STRING, gender);
        }
        return clearMetadataKey(stack, TameworkMetadataKeys.LIFE_STAGE_GENDER);
    }

    private void restoreLifeStageComponent(ItemStack stack,
                                           Ref<EntityStore> npcRef,
                                           Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        String stage = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE, Codec.STRING);
        Long bornAtMs = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_BORN_AT_MS, Codec.LONG);
        Long adolescentAtMs = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_AT_MS, Codec.LONG);
        Long adultAtMs = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_ADULT_AT_MS, Codec.LONG);
        Long fullyGrownAtMs = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_FULLY_GROWN_AT_MS, Codec.LONG);
        Double babyScale = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_BABY_SCALE, Codec.DOUBLE);
        Double adolescentScale = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SCALE, Codec.DOUBLE);
        Double adolescentSwitchScale = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.LIFE_STAGE_ADOLESCENT_SWITCH_SCALE,
                Codec.DOUBLE
        );
        Double adultStartScale = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_ADULT_START_SCALE, Codec.DOUBLE);
        Double adultSwitchScale = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.LIFE_STAGE_ADULT_SWITCH_SCALE,
                Codec.DOUBLE
        );
        Double adultScale = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_ADULT_SCALE, Codec.DOUBLE);
        Boolean growthScaling = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.LIFE_STAGE_GROWTH_SCALING_ENABLED,
                Codec.BOOLEAN
        );
        String gender = stack.getFromMetadataOrNull(TameworkMetadataKeys.LIFE_STAGE_GENDER, Codec.STRING);
        boolean hasData = (stage != null && !stage.isBlank())
                || bornAtMs != null
                || adolescentAtMs != null
                || adultAtMs != null
                || fullyGrownAtMs != null
                || babyScale != null
                || adolescentScale != null
                || adolescentSwitchScale != null
                || adultStartScale != null
                || adultSwitchScale != null
                || adultScale != null
                || growthScaling != null
                || (gender != null && !gender.isBlank());
        if (!hasData) {
            return;
        }
        TameworkLifeStageComponent existing = store.getComponent(npcRef, type);
        TameworkLifeStageComponent restored = new TameworkLifeStageComponent(
                stage != null && !stage.isBlank()
                        ? stage
                        : existing != null ? existing.getStage() : "Adult",
                bornAtMs != null
                        ? bornAtMs
                        : existing != null ? existing.getBornAtMs() : 0L,
                adolescentAtMs != null
                        ? adolescentAtMs
                        : existing != null ? existing.getAdolescentAtMs() : 0L,
                adultAtMs != null
                        ? adultAtMs
                        : existing != null ? existing.getAdultAtMs() : 0L,
                fullyGrownAtMs != null
                        ? fullyGrownAtMs
                        : existing != null ? existing.getFullyGrownAtMs() : 0L,
                babyScale != null
                        ? babyScale
                        : existing != null ? existing.getBabyScale() : 0.55,
                adolescentScale != null
                        ? adolescentScale
                        : existing != null ? existing.getAdolescentScale() : 0.80,
                adolescentSwitchScale != null
                        ? adolescentSwitchScale
                        : existing != null ? existing.getAdolescentSwitchScale() : 0.80,
                adultStartScale != null
                        ? adultStartScale
                        : existing != null ? existing.getAdultStartScale() : 0.80,
                adultSwitchScale != null
                        ? adultSwitchScale
                        : existing != null ? existing.getAdultSwitchScale() : 1.00,
                adultScale != null
                        ? adultScale
                        : existing != null ? existing.getAdultScale() : 1.00,
                growthScaling != null
                        ? growthScaling
                        : existing != null && existing.isGrowthScalingEnabled()
        );
        restored.setAdultRoleId(existing != null ? existing.getAdultRoleId() : null);
        restored.setBabyRoleId(existing != null ? existing.getBabyRoleId() : null);
        restored.setAdolescentRoleId(existing != null ? existing.getAdolescentRoleId() : null);
        restored.setGender(gender != null && !gender.isBlank()
                ? gender
                : existing != null ? existing.getGender() : null);
        store.putComponent(npcRef, type, restored);
    }

    private ItemStack clearMetadataKey(@Nullable ItemStack stack, @Nullable String key) {
        if (stack == null || key == null) {
            return stack;
        }
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null || !metadata.containsKey(key)) {
            return stack;
        }
        BsonDocument copy = metadata.clone();
        copy.remove(key);
        return stack.withMetadata(copy);
    }

}
