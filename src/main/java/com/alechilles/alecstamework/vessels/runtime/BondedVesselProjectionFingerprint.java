package com.alechilles.alecstamework.vessels.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Stable public fingerprint for one exact bonded-vessel live projection. */
public final class BondedVesselProjectionFingerprint {
    private BondedVesselProjectionFingerprint() {
    }

    @Nonnull
    public static String live(@Nonnull UUID bindingId, @Nonnull String profileId,
                              long generation, @Nonnull UUID npcUuid) {
        if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        String canonical = "binding=" + Objects.requireNonNull(bindingId, "bindingId") + "\n"
                + "profile=" + requireText(profileId, "profileId") + "\n"
                + "generation=" + generation + "\n"
                + "kind=LIVE_ENTITY\n"
                + "npc=" + Objects.requireNonNull(npcUuid, "npcUuid");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
