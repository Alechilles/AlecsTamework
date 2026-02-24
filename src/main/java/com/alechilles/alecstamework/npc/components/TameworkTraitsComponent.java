package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Stores rolled trait values and cached selection seed for a companion.
 */
public final class TameworkTraitsComponent implements Component<EntityStore> {
    private static final TraitValue[] EMPTY_VALUES = new TraitValue[0];
    private static final BuilderCodec<TraitValue> TRAIT_VALUE_CODEC = BuilderCodec.builder(
            TraitValue.class,
            TraitValue::new
    )
        .append(
            new KeyedCodec<>("Id", Codec.STRING),
            TraitValue::setId,
            TraitValue::getId
        )
        .add()
        .append(
            new KeyedCodec<>("Value", Codec.DOUBLE),
            TraitValue::setValue,
            TraitValue::getValue
        )
        .add()
        .build();
    private static final ArrayCodec<TraitValue> TRAIT_VALUE_ARRAY_CODEC =
            new ArrayCodec<>(TRAIT_VALUE_CODEC, TraitValue[]::new);

    public static final BuilderCodec<TameworkTraitsComponent> CODEC = BuilderCodec.builder(
            TameworkTraitsComponent.class,
            TameworkTraitsComponent::new
    )
        .append(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            TameworkTraitsComponent::setConfigId,
            TameworkTraitsComponent::getConfigId
        )
        .add()
        .append(
            new KeyedCodec<>("RollSeed", Codec.LONG),
            TameworkTraitsComponent::setRollSeed,
            TameworkTraitsComponent::getRollSeed
        )
        .add()
        .append(
            new KeyedCodec<>("TraitValues", TRAIT_VALUE_ARRAY_CODEC),
            TameworkTraitsComponent::setTraitValues,
            TameworkTraitsComponent::getTraitValues
        )
        .add()
        .build();

    private String configId;
    private long rollSeed;
    private TraitValue[] traitValues = EMPTY_VALUES;

    public TameworkTraitsComponent() {
    }

    public TameworkTraitsComponent(@Nullable String configId,
                                   long rollSeed,
                                   @Nullable TraitValue[] traitValues) {
        this.configId = configId;
        this.rollSeed = rollSeed;
        this.traitValues = sanitizeTraitValues(traitValues);
    }

    public static ComponentType<EntityStore, TameworkTraitsComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getTraitsComponentType() : null;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public long getRollSeed() {
        return rollSeed;
    }

    public void setRollSeed(long rollSeed) {
        this.rollSeed = rollSeed;
    }

    public TraitValue[] getTraitValues() {
        return traitValues == null ? EMPTY_VALUES : traitValues;
    }

    public void setTraitValues(TraitValue[] traitValues) {
        this.traitValues = sanitizeTraitValues(traitValues);
    }

    @Nullable
    public Double getTraitValue(@Nullable String traitId) {
        if (traitId == null || traitId.isBlank()) {
            return null;
        }
        for (TraitValue value : getTraitValues()) {
            if (value == null || value.id == null) {
                continue;
            }
            if (value.id.equalsIgnoreCase(traitId)) {
                return value.value;
            }
        }
        return null;
    }

    @Override
    public TameworkTraitsComponent clone() {
        return new TameworkTraitsComponent(configId, rollSeed, getTraitValues().clone());
    }

    private static TraitValue[] sanitizeTraitValues(@Nullable TraitValue[] input) {
        if (input == null || input.length == 0) {
            return EMPTY_VALUES;
        }
        List<TraitValue> values = new ArrayList<>(input.length);
        for (TraitValue value : input) {
            if (value == null || value.id == null || value.id.isBlank()) {
                continue;
            }
            values.add(new TraitValue(value.id, value.value));
        }
        return values.isEmpty() ? EMPTY_VALUES : values.toArray(new TraitValue[0]);
    }

    /** Single rolled trait value attached to an NPC. */
    public static final class TraitValue {
        private String id;
        private double value;

        public TraitValue() {
        }

        public TraitValue(String id, double value) {
            this.id = id;
            this.value = value;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }
    }
}
