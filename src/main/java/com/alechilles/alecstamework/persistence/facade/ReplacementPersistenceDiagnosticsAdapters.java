package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteOperationFeatureScopeCatalog;
import com.alechilles.alecstamework.persistence.incidents
        .PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceDomainFacades;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceFeatureRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Keeps exact SQLite feature-scope and incident-hash composition inside the
 * replacement persistence boundary.
 */
public record ReplacementPersistenceDiagnosticsAdapters(
        @Nonnull ReplacementPersistenceDiagnosticsApi.AvailabilityProbe
                availability,
        @Nonnull ReplacementPersistenceDiagnosticsApi.IncidentLookup incidents
) {
    public ReplacementPersistenceDiagnosticsAdapters {
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(incidents, "incidents");
    }

    /** Creates production adapters with one durable installation-local key. */
    @Nonnull
    public static ReplacementPersistenceDiagnosticsAdapters create(
            @Nonnull Path scopeHashKey,
            @Nonnull PersistenceBootstrap bootstrap,
            @Nonnull PersistenceDomainFacades facades,
            @Nonnull Duration timeout
    ) throws Exception {
        return create(
                PersistenceScopeFactory.loadOrCreate(scopeHashKey),
                bootstrap,
                facades,
                timeout
        );
    }

    /** Creates a fail-safe session-local composition when key storage fails. */
    @Nonnull
    public static ReplacementPersistenceDiagnosticsAdapters ephemeral(
            @Nonnull PersistenceBootstrap bootstrap,
            @Nonnull PersistenceDomainFacades facades,
            @Nonnull Duration timeout
    ) {
        return create(
                PersistenceScopeFactory.ephemeral(),
                bootstrap,
                facades,
                timeout
        );
    }

    private static ReplacementPersistenceDiagnosticsAdapters create(
            PersistenceScopeFactory scopeHashes,
            PersistenceBootstrap bootstrap,
            PersistenceDomainFacades facades,
            Duration timeout
    ) {
        Objects.requireNonNull(scopeHashes, "scopeHashes");
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(facades, "facades");
        Objects.requireNonNull(timeout, "timeout");
        var registry = PublicPersistenceFeatureRegistry.create();
        var featureScopes = new SqliteOperationFeatureScopeCatalog(registry);
        return new ReplacementPersistenceDiagnosticsAdapters(
                new ReplacementPersistenceAvailabilityProbe(
                        registry,
                        bootstrap.diagnosticsReader(),
                        facades.queries(),
                        featureScopes::resolve,
                        timeout
                ),
                new ReplacementPersistenceIncidentLookup(
                        registry,
                        scopeHashes,
                        facades.queries(),
                        timeout
                )
        );
    }
}
