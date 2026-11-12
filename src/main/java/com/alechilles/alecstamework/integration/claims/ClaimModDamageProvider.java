package com.alechilles.alecstamework.integration.claims;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * A claim mod that Tamework reads through a plain claim and damage-rule check. SimpleClaims does
 * not use this seam: its tamed damage path keeps its own version gate and legacy permission
 * bypass in {@code SimpleClaimsTamedDamagePolicy}.
 *
 * <p>Call on the world thread that owns {@code worldName}. Implementations never throw.</p>
 */
public interface ClaimModDamageProvider extends ClaimModProvider {
    /** Why the claim mod cannot be used right now, or null when it is ready. */
    @Nullable
    String unavailableReason();

    /** Whether the claim mod lets this online player hurt creatures at {@code position}. */
    @Nonnull
    DamageResult evaluateTamedDamage(@Nonnull String worldName,
                                     @Nonnull Vector3d position,
                                     @Nonnull UUID attackerPlayerUuid);

    /** One damage-rule answer; {@code message} is a diagnostic detail, not player-facing. */
    record DamageResult(@Nonnull Status status, @Nullable String message) {
        public enum Status {
            ALLOWED,
            DENIED,
            ERROR,
            UNAVAILABLE
        }

        public DamageResult {
            Objects.requireNonNull(status, "status");
        }
    }
}
