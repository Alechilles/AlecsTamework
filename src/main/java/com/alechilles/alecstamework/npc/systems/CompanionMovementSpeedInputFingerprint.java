package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Captures cheap raw movement inputs so expensive multiplier resolution runs only after state changes.
 */
final class CompanionMovementSpeedInputFingerprint {
    private static final long SIGNATURE_SEED = 0xcbf29ce484222325L;
    private static final long SIGNATURE_PRIME = 0x100000001b3L;

    long resolve(@Nonnull Ref<EntityStore> npcRef,
                 @Nonnull Store<EntityStore> store,
                 int roleIndex,
                 boolean levelingEnabled,
                 boolean talentsEnabled) {
        long signature = SIGNATURE_SEED;
        signature = mix(signature, roleIndex);
        signature = mix(signature, levelingEnabled);
        signature = mix(signature, talentsEnabled);
        signature = mix(signature, hasComponent(
                npcRef, store, TameworkMountedGlideComponent.getComponentType()));
        signature = mix(signature, hasComponent(
                npcRef, store, AvatarFlightSourceComponent.getComponentType()));

        ModelComponent model = store.getComponent(npcRef, ModelComponent.getComponentType());
        Map<String, String> attachments = model == null || model.getModel() == null
                ? null : model.getModel().getRandomAttachmentIds();
        signature = mixMap(signature, attachments);

        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        TameworkTraitsComponent traits = traitsType == null ? null : store.getComponent(npcRef, traitsType);
        signature = mixTraits(signature, traits);

        ComponentType<EntityStore, TameworkLevelingComponent> levelingType =
                TameworkLevelingComponent.getComponentType();
        TameworkLevelingComponent leveling = levelingType == null ? null : store.getComponent(npcRef, levelingType);
        signature = mixLeveling(signature, leveling);

        ComponentType<EntityStore, TameworkTalentsComponent> talentsType = TameworkTalentsComponent.getComponentType();
        TameworkTalentsComponent talents = talentsType == null ? null : store.getComponent(npcRef, talentsType);
        return mixTalents(signature, talents);
    }

    private static long mixTraits(long signature, @Nullable TameworkTraitsComponent traits) {
        if (traits == null) {
            return mix(signature, 0L);
        }
        signature = mix(signature, 1L);
        signature = mix(signature, traits.getConfigId());
        signature = mix(signature, traits.getRollSeed());
        TameworkTraitsComponent.TraitValue[] values = traits.getTraitValues();
        signature = mix(signature, values.length);
        for (TameworkTraitsComponent.TraitValue value : values) {
            if (value == null) {
                signature = mix(signature, 0L);
                continue;
            }
            signature = mix(signature, value.getId());
            signature = mix(signature, Double.doubleToLongBits(value.getValue()));
        }
        return signature;
    }

    private static long mixLeveling(long signature, @Nullable TameworkLevelingComponent leveling) {
        if (leveling == null) {
            return mix(signature, 0L);
        }
        signature = mix(signature, 1L);
        signature = mix(signature, leveling.getConfigId());
        signature = mix(signature, leveling.getLevel());
        return mix(signature, Double.doubleToLongBits(leveling.getTotalXp()));
    }

    private static long mixTalents(long signature, @Nullable TameworkTalentsComponent talents) {
        if (talents == null) {
            return mix(signature, 0L);
        }
        signature = mix(signature, 1L);
        signature = mix(signature, talents.getConfigId());
        signature = mix(signature, talents.getAllocationRevision());
        String[] talentIds = talents.getPurchasedTalentIds();
        signature = mix(signature, talentIds.length);
        for (String talentId : talentIds) {
            signature = mix(signature, talentId);
        }
        return signature;
    }

    private static long mixMap(long signature, @Nullable Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return mix(signature, 0L);
        }
        signature = mix(signature, values.size());
        long entries = 0L;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            long entrySignature = mix(mix(SIGNATURE_SEED, entry.getKey()), entry.getValue());
            entries += Long.rotateLeft(entrySignature, (int) (entrySignature & 31));
        }
        return mix(signature, entries);
    }

    private static <T extends Component<EntityStore>> boolean hasComponent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> type) {
        return type != null && store.getComponent(ref, type) != null;
    }

    private static long mix(long signature, boolean value) {
        return mix(signature, value ? 1L : 0L);
    }

    private static long mix(long signature, int value) {
        return mix(signature, (long) value);
    }

    private static long mix(long signature, @Nullable String value) {
        if (value == null) {
            return mix(signature, 0L);
        }
        signature = mix(signature, 1L);
        signature = mix(signature, value.length());
        for (int index = 0; index < value.length(); index++) {
            signature = mix(signature, value.charAt(index));
        }
        return signature;
    }

    private static long mix(long signature, long value) {
        signature ^= value;
        return signature * SIGNATURE_PRIME;
    }
}
