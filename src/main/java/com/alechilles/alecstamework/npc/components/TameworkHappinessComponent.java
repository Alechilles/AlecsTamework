package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Stores shared companion happiness state for systems beyond breeding.
 */
public final class TameworkHappinessComponent implements Component<EntityStore> {
    private static final ActiveImpulse[] EMPTY_ACTIVE_IMPULSES = new ActiveImpulse[0];
    private static final BuilderCodec<ActiveImpulse> ACTIVE_IMPULSE_CODEC = BuilderCodec.builder(
            ActiveImpulse.class,
            ActiveImpulse::new
    )
        .append(
            new KeyedCodec<>("Key", Codec.STRING),
            ActiveImpulse::setKey,
            ActiveImpulse::getKey
        )
        .add()
        .append(
            new KeyedCodec<>("Label", Codec.STRING),
            ActiveImpulse::setLabel,
            ActiveImpulse::getLabel
        )
        .add()
        .append(
            new KeyedCodec<>("Value", Codec.DOUBLE),
            ActiveImpulse::setValue,
            ActiveImpulse::getValue
        )
        .add()
        .append(
            new KeyedCodec<>("ExpiresAtMs", Codec.LONG),
            ActiveImpulse::setExpiresAtMs,
            ActiveImpulse::getExpiresAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("ItemId", Codec.STRING),
            ActiveImpulse::setItemId,
            ActiveImpulse::getItemId
        )
        .add()
        .build();
    private static final ArrayCodec<ActiveImpulse> ACTIVE_IMPULSE_ARRAY_CODEC =
            new ArrayCodec<>(ACTIVE_IMPULSE_CODEC, ActiveImpulse[]::new);

    public static final BuilderCodec<TameworkHappinessComponent> CODEC = BuilderCodec.builder(
            TameworkHappinessComponent.class,
            TameworkHappinessComponent::new
    )
        .append(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            TameworkHappinessComponent::setConfigId,
            TameworkHappinessComponent::getConfigId
        )
        .add()
        .append(
            new KeyedCodec<>("Value", Codec.DOUBLE),
            TameworkHappinessComponent::setValue,
            TameworkHappinessComponent::getValue
        )
        .add()
        .append(
            new KeyedCodec<>("LastUpdateMs", Codec.LONG),
            TameworkHappinessComponent::setLastUpdateMs,
            TameworkHappinessComponent::getLastUpdateMs
        )
        .add()
        .append(
            new KeyedCodec<>("ActiveImpulses", ACTIVE_IMPULSE_ARRAY_CODEC),
            TameworkHappinessComponent::setActiveImpulses,
            TameworkHappinessComponent::getActiveImpulses
        )
        .add()
        .build();

    private String configId;
    private double value;
    private long lastUpdateMs;
    private ActiveImpulse[] activeImpulses = EMPTY_ACTIVE_IMPULSES;

    public TameworkHappinessComponent() {
    }

    public TameworkHappinessComponent(String configId, double value, long lastUpdateMs) {
        this(configId, value, lastUpdateMs, EMPTY_ACTIVE_IMPULSES);
    }

    public TameworkHappinessComponent(String configId,
                                      double value,
                                      long lastUpdateMs,
                                      ActiveImpulse[] activeImpulses) {
        this.configId = configId;
        this.value = value;
        this.lastUpdateMs = lastUpdateMs;
        setActiveImpulses(activeImpulses);
    }

    public static ComponentType<EntityStore, TameworkHappinessComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getHappinessComponentType() : null;
    }

    public String getConfigId() {
        return configId;
    }

    public void setConfigId(String configId) {
        this.configId = configId;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public long getLastUpdateMs() {
        return lastUpdateMs;
    }

    public void setLastUpdateMs(long lastUpdateMs) {
        this.lastUpdateMs = lastUpdateMs;
    }

    public ActiveImpulse[] getActiveImpulses() {
        return activeImpulses == null ? EMPTY_ACTIVE_IMPULSES : activeImpulses;
    }

    public void setActiveImpulses(ActiveImpulse[] activeImpulses) {
        this.activeImpulses = activeImpulses == null ? EMPTY_ACTIVE_IMPULSES : activeImpulses;
    }

    @Override
    public TameworkHappinessComponent clone() {
        ActiveImpulse[] clonedImpulses = getActiveImpulses().clone();
        for (int i = 0; i < clonedImpulses.length; i++) {
            if (clonedImpulses[i] != null) {
                clonedImpulses[i] = clonedImpulses[i].clone();
            }
        }
        return new TameworkHappinessComponent(configId, value, lastUpdateMs, clonedImpulses);
    }

    public static final class ActiveImpulse {
        private String key;
        private String label;
        private double value;
        private long expiresAtMs;
        private String itemId;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }

        public long getExpiresAtMs() {
            return expiresAtMs;
        }

        public void setExpiresAtMs(long expiresAtMs) {
            this.expiresAtMs = expiresAtMs;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public ActiveImpulse clone() {
            ActiveImpulse copy = new ActiveImpulse();
            copy.key = key;
            copy.label = label;
            copy.value = value;
            copy.expiresAtMs = expiresAtMs;
            copy.itemId = itemId;
            return copy;
        }
    }
}
