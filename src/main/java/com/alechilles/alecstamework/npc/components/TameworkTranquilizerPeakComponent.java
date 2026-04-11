package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Tracks the peak tranquilizer duration reached during the current active tranquilizer effect lifetime.
 */
public final class TameworkTranquilizerPeakComponent implements Component<EntityStore> {
    private static final String PEAK_REMAINING_SECONDS_TAG = "PeakRemainingSeconds";

    public static final BuilderCodec<TameworkTranquilizerPeakComponent> CODEC = BuilderCodec.builder(
            TameworkTranquilizerPeakComponent.class,
            TameworkTranquilizerPeakComponent::new
    ).append(
            new KeyedCodec<>(PEAK_REMAINING_SECONDS_TAG, Codec.DOUBLE),
            TameworkTranquilizerPeakComponent::setPeakRemainingSeconds,
            TameworkTranquilizerPeakComponent::getPeakRemainingSeconds
    ).add().build();

    private double peakRemainingSeconds;

    public TameworkTranquilizerPeakComponent() {
        this(0.0);
    }

    public TameworkTranquilizerPeakComponent(double peakRemainingSeconds) {
        setPeakRemainingSeconds(peakRemainingSeconds);
    }

    public static ComponentType<EntityStore, TameworkTranquilizerPeakComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getTranquilizerPeakComponentType() : null;
    }

    public double getPeakRemainingSeconds() {
        return sanitizePositive(peakRemainingSeconds);
    }

    public void setPeakRemainingSeconds(double peakRemainingSeconds) {
        this.peakRemainingSeconds = sanitizePositive(peakRemainingSeconds);
    }

    @Override
    public TameworkTranquilizerPeakComponent clone() {
        return new TameworkTranquilizerPeakComponent(peakRemainingSeconds);
    }

    private static double sanitizePositive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return value;
    }
}
