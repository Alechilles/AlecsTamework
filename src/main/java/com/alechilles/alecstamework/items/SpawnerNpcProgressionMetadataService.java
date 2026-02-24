package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
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
 * Captures and restores companion progression state (breeding + traits) on spawner item metadata.
 */
final class SpawnerNpcProgressionMetadataService {
    ItemStack applyNpcProgressionMetadata(@Nullable ItemStack stack,
                                          @Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store) {
        if (stack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return stack;
        }
        ItemStack updated = applyBreedingMetadata(stack, npcRef, store);
        return applyTraitsMetadata(updated, npcRef, store);
    }

    void applyNpcProgressionFromItem(@Nullable ItemStack stack,
                                     @Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store) {
        if (stack == null || npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        restoreBreedingComponent(stack, npcRef, store);
        restoreTraitsComponent(stack, npcRef, store);
    }

    ItemStack clearProgressionMetadata(@Nullable ItemStack stack) {
        ItemStack updated = clearMetadataKey(stack, TameworkMetadataKeys.BREEDING_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_HAPPINESS);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_CONFIG_ID);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_ROLL_SEED);
        updated = clearMetadataKey(updated, TameworkMetadataKeys.TRAITS_VALUES);
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
        updated = updated.withMetadata(TameworkMetadataKeys.BREEDING_HAPPINESS, Codec.DOUBLE, component.getHappiness());
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
        Double happiness = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_HAPPINESS, Codec.DOUBLE);
        Long cooldown = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_COOLDOWN_UNTIL, Codec.LONG);
        UUID partner = stack.getFromMetadataOrNull(TameworkMetadataKeys.BREEDING_LAST_PARTNER_UUID, Codec.UUID_STRING);
        boolean hasData = (configId != null && !configId.isBlank())
                || happiness != null
                || cooldown != null
                || partner != null;
        if (!hasData) {
            return;
        }
        double value = happiness != null ? happiness : 0.0;
        long cooldownUntil = cooldown != null ? cooldown : 0L;
        boolean ready = false;
        if (configId != null && !configId.isBlank()) {
            TwBreedingConfig config = TwBreedingConfig.resolveById(configId);
            if (config != null) {
                ready = value >= config.getHappiness().getThreshold();
            }
        }
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                configId,
                value,
                System.currentTimeMillis(),
                ready,
                cooldownUntil,
                partner
        );
        store.putComponent(npcRef, type, component);
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
