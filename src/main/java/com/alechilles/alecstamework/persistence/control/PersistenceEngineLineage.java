package com.alechilles.alecstamework.persistence.control;

/** Mutually exclusive persistence engines that may own one data directory. */
public enum PersistenceEngineLineage {
    LEGACY_PUBLIC("legacy-public"),
    REPLACEMENT("replacement");

    private final String manifestValue;

    PersistenceEngineLineage(String manifestValue) {
        this.manifestValue = manifestValue;
    }

    public String manifestValue() {
        return manifestValue;
    }

    static PersistenceEngineLineage parse(String value) {
        for (PersistenceEngineLineage lineage : values()) {
            if (lineage.manifestValue.equals(value)) {
                return lineage;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported persistence engine lineage: " + value
        );
    }
}
