package com.alechilles.alecstamework.persistence.migration;

/** Mutually exclusive classification of a persistence file presented at replacement startup. */
public enum LegacySourceKind {
    NO_SOURCE,
    PUBLIC_V2,
    PUBLIC_V3,
    PUBLIC_V4,
    LEGACY_DAT,
    REPLACEMENT_V1,
    DEVELOPMENT_V5_TO_V9,
    MALFORMED,
    AMBIGUOUS
}
