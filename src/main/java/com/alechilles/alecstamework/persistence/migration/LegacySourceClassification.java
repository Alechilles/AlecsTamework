package com.alechilles.alecstamework.persistence.migration;

import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

/** Complete, immutable result of read-only source classification. */
public record LegacySourceClassification(
        @Nonnull LegacySourceKind kind,
        int schemaVersion,
        @Nonnull String diagnosticCode,
        @Nonnull Optional<LegacySourceFingerprint> fingerprint,
        @Nonnull Set<String> tables
) {
    public LegacySourceClassification {
        if (kind == null || diagnosticCode == null || diagnosticCode.isBlank()
                || fingerprint == null || tables == null) {
            throw new IllegalArgumentException("Source classification fields are required");
        }
        if (schemaVersion < 0) {
            throw new IllegalArgumentException("Source schema version cannot be negative");
        }
        tables = Set.copyOf(tables);
    }

    /** Returns whether this source may enter the public import pipeline. */
    public boolean importablePublicSource() {
        return kind == LegacySourceKind.PUBLIC_V2
                || kind == LegacySourceKind.PUBLIC_V3
                || kind == LegacySourceKind.PUBLIC_V4;
    }
}
