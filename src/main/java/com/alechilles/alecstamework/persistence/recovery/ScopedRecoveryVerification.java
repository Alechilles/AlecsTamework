package com.alechilles.alecstamework.persistence.recovery;

import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Verifier result with exact per-quarantine evidence hashes and a publication fence. */
public record ScopedRecoveryVerification(@Nonnull ScopedRecoveryResolution resolution,
                                         @Nonnull String resolutionCode,
                                         @Nonnull Map<String, String> evidenceHashes,
                                         @Nullable StorageRecoveryIndexPublisher indexPublisher,
                                         @Nullable Throwable failure) {
    public ScopedRecoveryVerification {
        if (resolution == null) throw new IllegalArgumentException("resolution");
        resolutionCode = normalize(resolutionCode);
        evidenceHashes = evidenceHashes == null ? Map.of() : Map.copyOf(evidenceHashes);
        if (resolution.isResolved() && evidenceHashes.isEmpty()) {
            throw new IllegalArgumentException("resolved verification requires evidence hashes");
        }
    }

    @Nonnull
    public static ScopedRecoveryVerification unresolved(@Nonnull ScopedRecoveryResolution resolution,
                                                        @Nonnull String reason) {
        if (resolution.isResolved()) throw new IllegalArgumentException("resolution must remain unresolved");
        return new ScopedRecoveryVerification(resolution, reason, Map.of(), null, null);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "unspecified";
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(160, trimmed.length()));
    }
}
