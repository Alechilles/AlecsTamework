package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
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
        ItemStack updated = applyHappinessMetadata(stack, npcRef, store);
        updated = applyBreedingMetadata(updated, npcRef, store);
        updated = applyTraitsMetadata(updated, npcRef, store);
        return applyLifeStageMetadata(updated, npcRef, store);
    }

    void applyNpcProgressionFromItem(@Nullable ItemStack stack,
                                     @Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store) {
        if (stack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        restoreHappinessComponent(stack, npcRef, store);
        restoreBreedingComponent(stack, npcRef, store);
        restoreTraitsComponent(stack, npcRef, store);
        restoreLifeStageComponent(stack, npcRef, store);
    }

    ItemStack clearProgressionMetadata(@Nullable ItemStack stack) {
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.HAPPINESS_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_VALUE);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_HAPPINESS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID);
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
        return updated;
    }

    private ItemStack applyHappinessMetadata(ItemStack stack,
                                             Ref<EntityStore> npcRef,
                                             Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type = TameworkHappinessComponent.getComponentType();
        if (type == null) {
            return stack;
        }
        TameworkHappinessComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.HAPPINESS_CONFIG_ID);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_VALUE);
            updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS);
            return updated;
        }
        ItemStack updated = stack;
        if (component.getConfigId() != null && !component.getConfigId().isBlank()) {
            updated = updated.withMetadata(TameworkMetadataKeys.HAPPINESS_CONFIG_ID, Codec.STRING, component.getConfigId());
        } else {
            updated = clearMetadataKey(updated, TameworkMetadataKeys.HAPPINESS_CONFIG_ID);
        }
        updated = updated.withMetadata(TameworkMetadataKeys.HAPPINESS_VALUE, Codec.DOUBLE, component.getValue());
        updated = updated.withMetadata(TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS, Codec.LONG, component.getLastUpdateMs());
        return updated;
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

    private void restoreBreedingComponent(ItemStack stack,
                                          Ref<EntityStore> npcRef,
                                          Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type == null) {
            return;
        }
        String configId = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_CONFIG_ID, Codec.STRING);
        Double legacyHappiness = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_HAPPINESS, Codec.DOUBLE);
        Long cooldown = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL, Codec.LONG);
        UUID partner = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID, Codec.UUID_STRING);
        boolean hasData = (configId != null && !configId.isBlank())
                || legacyHappiness != null
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
        UUID lastPartner = partner != null
                ? partner
                : existing != null ? existing.getLastPartnerUuid() : null;
        boolean ready = false;
        if (resolvedConfigId != null && !resolvedConfigId.isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveById(resolvedConfigId);
            if (config != null) {
                String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
                ready = value >= config.resolveHappiness(roleId).getThreshold();
            }
        }
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                resolvedConfigId,
                value,
                lastHappinessUpdateMs > 0L ? lastHappinessUpdateMs : System.currentTimeMillis(),
                ready,
                cooldownUntil,
                lastPartner
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
            return updated;
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
        return updated;
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
                || growthScaling != null;
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
