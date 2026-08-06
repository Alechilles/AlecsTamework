package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Set;
import javax.annotation.Nonnull;

/** Configures NPC-backed avatar-flight mounting and voluntary dismount behavior. */
public final class AvatarFlightMountingSettings {
    public static final BuilderCodec<AvatarFlightMountingSettings> CODEC = BuilderCodec.builder(
            AvatarFlightMountingSettings.class,
            AvatarFlightMountingSettings::new
    )
            .<Double>append(new KeyedCodec<>("DismountHoldMs", Codec.DOUBLE),
                    (settings, value) -> settings.dismountHoldMs = positive(value, 750.0),
                    settings -> settings.dismountHoldMs)
            .documentation("Milliseconds grounded back+crouch must be held to dismount. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("RequireGroundedDismount", Codec.BOOLEAN),
                    (settings, value) -> settings.requireGroundedDismount = value == null || value,
                    settings -> settings.requireGroundedDismount)
            .documentation("Whether the back+crouch dismount gesture requires the avatar to be grounded. The explicit dismount key remains available while airborne. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Boolean>append(new KeyedCodec<>("RestoreNpcAtLastSafeGround", Codec.BOOLEAN),
                    (settings, value) -> settings.restoreNpcAtLastSafeGround = value == null || value,
                    settings -> settings.restoreNpcAtLastSafeGround)
            .documentation("Whether grounded normal dismount restores the source NPC at the most recent safe-ground position. Airborne normal dismount restores it at the current flight position. Inheritance: missing nested key inherits parent value.")
            .add()
            .<Double>append(new KeyedCodec<>("PlayerDismountOffset", Codec.DOUBLE),
                    (settings, value) -> settings.playerDismountOffset = nonNegative(value, 1.75),
                    settings -> settings.playerDismountOffset)
            .documentation("Horizontal distance used to place the restored player behind the source NPC. Inheritance: missing nested key inherits parent value.")
            .add()
            .build();

    double dismountHoldMs = 750.0;
    boolean requireGroundedDismount = true;
    boolean restoreNpcAtLastSafeGround = true;
    double playerDismountOffset = 1.75;

    public long getDismountHoldMs() {
        return Math.max(1L, Math.round(dismountHoldMs));
    }

    public boolean isRequireGroundedDismount() {
        return requireGroundedDismount;
    }

    public boolean isRestoreNpcAtLastSafeGround() {
        return restoreNpcAtLastSafeGround;
    }

    public double getPlayerDismountOffset() {
        return Math.max(0.0, playerDismountOffset);
    }

    void inheritMissingFrom(@Nonnull AvatarFlightMountingSettings parent,
                            @Nonnull Set<String> explicitNestedKeys) {
        if (!explicitNestedKeys.contains("DismountHoldMs")) dismountHoldMs = parent.dismountHoldMs;
        if (!explicitNestedKeys.contains("RequireGroundedDismount")) {
            requireGroundedDismount = parent.requireGroundedDismount;
        }
        if (!explicitNestedKeys.contains("RestoreNpcAtLastSafeGround")) {
            restoreNpcAtLastSafeGround = parent.restoreNpcAtLastSafeGround;
        }
        if (!explicitNestedKeys.contains("PlayerDismountOffset")) {
            playerDismountOffset = parent.playerDismountOffset;
        }
    }

    private static double positive(Double value, double fallback) {
        return value != null && Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double nonNegative(Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }
}
