package com.alechilles.alecstamework.persistence.runtime;

import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * Immutable process-level engine decision resolved before persistence opens.
 *
 * <p>The replacement is the release-candidate default. The legacy engine is
 * deliberately hidden behind a second unsupported-development acknowledgement
 * so a stale launch option cannot silently select it.</p>
 */
public record PersistenceEngineSelection(
        @Nonnull PersistenceEngineMode mode,
        @Nonnull Source source
) {
    public static final String ENGINE_PROPERTY =
            "tamework.persistence.engine";
    public static final String ALLOW_LEGACY_PROPERTY =
            "tamework.persistence.allowUnsupportedLegacy";

    /** Explains whether the release default or an explicit option won. */
    public enum Source {
        RELEASE_DEFAULT,
        EXPLICIT_DEVELOPMENT_OPTION
    }

    public PersistenceEngineSelection {
        if (mode == null || source == null) {
            throw new IllegalArgumentException(
                    "Complete persistence engine selection is required"
            );
        }
    }

    /** Resolves the current process properties exactly once at the call site. */
    @Nonnull
    public static PersistenceEngineSelection resolveSystemProperties() {
        return resolve(System::getProperty);
    }

    /**
     * Resolves from a property reader so launch policy remains deterministic
     * and directly testable.
     */
    @Nonnull
    public static PersistenceEngineSelection resolve(
            @Nonnull Function<String, String> properties
    ) {
        if (properties == null) {
            throw new IllegalArgumentException(
                    "Persistence property reader is required"
            );
        }
        String configured = properties.apply(ENGINE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return new PersistenceEngineSelection(
                    PersistenceEngineMode.NEXT,
                    Source.RELEASE_DEFAULT
            );
        }
        PersistenceEngineMode mode =
                PersistenceEngineMode.parse(configured);
        if (mode == PersistenceEngineMode.LEGACY
                && !Boolean.parseBoolean(
                        properties.apply(ALLOW_LEGACY_PROPERTY)
                )) {
            throw new IllegalStateException(
                    "legacy_persistence_requires_unsupported_development_ack"
            );
        }
        return new PersistenceEngineSelection(
                mode,
                Source.EXPLICIT_DEVELOPMENT_OPTION
        );
    }
}
