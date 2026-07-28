package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityRequest;
import com.alechilles.alecstamework.api.PersistenceMutationDirection;
import com.alechilles.alecstamework.api.PersistenceMutationDomain;
import com.alechilles.alecstamework.api.PersistenceScopeKind;
import com.alechilles.alecstamework.api.PersistenceScopeReference;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteIncidentStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationFeatureScopeCatalog;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.alechilles.alecstamework.persistence.incidents.IncidentRecord;
import com.alechilles.alecstamework.persistence.incidents.IncidentState;
import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementPersistenceFeatureDiagnosticsAdaptersTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000041"
    );
    private static final ProfileId PROFILE = ProfileId.parse(
            "20000000-0000-0000-0000-000000000041"
    );
    private static final IncidentId INCIDENT = new IncidentId(
            UUID.fromString("50000000-0000-0000-0000-000000000041")
    );

    @TempDir
    Path tempDir;

    @Test
    void availabilityUsesExactReplacementReadinessAndScopedQuarantine()
            throws Exception {
        try (PersistenceBootstrap persistence = started()) {
            var featureScopes = new SqliteOperationFeatureScopeCatalog(
                    PublicPersistenceFeatureRegistry.create()
            );
            var probe = new ReplacementPersistenceAvailabilityProbe(
                    PublicPersistenceFeatureRegistry.create(),
                    persistence.diagnosticsReader(),
                    persistence.facades().queries(),
                    featureScopes::resolve,
                    Duration.ofSeconds(2)
            );
            assertEquals("ALLOW", probe.query(request(
                    new PersistenceScopeReference(
                            PersistenceScopeKind.PROFILE,
                            PROFILE.toString(),
                            null
                    )
            )).status());
            assertEquals("ALLOW", probe.query(
                    new PersistenceMutationAvailabilityRequest(
                            PersistenceMutationDomain.RECALL_RELOCATION,
                            "command_roster_membership",
                            List.of(
                                    new PersistenceScopeReference(
                                            PersistenceScopeKind.PROFILE,
                                            PROFILE.toString(),
                                            null
                                    ),
                                    new PersistenceScopeReference(
                                            PersistenceScopeKind.OWNER_GLOBAL,
                                            OWNER.toString(),
                                            null
                                    ),
                                    new PersistenceScopeReference(
                                            PersistenceScopeKind.COMMAND_FAMILY,
                                            OWNER + ":dragon_horn",
                                            null
                                    )
                            ),
                            Set.of(),
                            PersistenceMutationDirection.ZERO,
                            null,
                            null,
                            false,
                            false
                    )
            ).status());

            writeQuarantine();
            var denied = probe.query(request(
                    new PersistenceScopeReference(
                            PersistenceScopeKind.PROFILE,
                            PROFILE.toString(),
                            null
                    )
            ));
            assertEquals("QUARANTINED", denied.status());
            assertEquals("profile_recovery_unknown", denied.reasonCode());
            assertEquals(INCIDENT.toString(), denied.incidentId());
        }
    }

    @Test
    void unsupportedLegacyScopeAndUnmappedFeatureScopeFailClosed() {
        try (PersistenceBootstrap persistence = started()) {
            var featureScopes = new SqliteOperationFeatureScopeCatalog(
                    PublicPersistenceFeatureRegistry.create()
            );
            var strictProbe = new ReplacementPersistenceAvailabilityProbe(
                    PublicPersistenceFeatureRegistry.create(),
                    persistence.diagnosticsReader(),
                    persistence.facades().queries(),
                    featureScopes::resolve,
                    Duration.ofSeconds(2)
            );
            assertEquals(
                    "replacement_operation_scope_unsupported",
                    strictProbe.query(request(
                            new PersistenceScopeReference(
                                    PersistenceScopeKind.OWNER_WORLD,
                                    UUID.randomUUID() + "|world",
                                    null
                            )
                    )).reasonCode()
            );
            var unmappedProbe = new ReplacementPersistenceAvailabilityProbe(
                    PublicPersistenceFeatureRegistry.create(),
                    persistence.diagnosticsReader(),
                    persistence.facades().queries(),
                    ignored -> Optional.empty(),
                    Duration.ofSeconds(2)
            );
            assertEquals(
                    "replacement_operation_scope_unsupported",
                    unmappedProbe.query(request(
                            new PersistenceScopeReference(
                                    PersistenceScopeKind.PROFILE,
                                    PROFILE.toString(),
                                    null
                            )
                    )).reasonCode()
            );
        }
    }

    @Test
    void incidentLookupReturnsOnlyHashedDeterministicScopeEvidence()
            throws Exception {
        try (PersistenceBootstrap persistence = started()) {
            writeQuarantine();
            byte[] salt = new byte[32];
            java.util.Arrays.fill(salt, (byte) 7);
            var lookup = new ReplacementPersistenceIncidentLookup(
                    PublicPersistenceFeatureRegistry.create(),
                    new PersistenceScopeFactory(salt),
                    persistence.facades().queries(),
                    Duration.ofSeconds(2)
            );

            var summary = lookup.find("50000000-0000-0000").orElseThrow();
            assertEquals(INCIDENT.toString(), summary.incidentId());
            assertEquals("POPULATION", summary.domain());
            assertEquals("UNKNOWN", summary.phase());
            assertEquals("SCOPED_QUARANTINE", summary.disposition());
            assertEquals(1, summary.scopes().size());
            assertEquals("PROFILE", summary.scopes().getFirst().kind());
            assertEquals(64, summary.scopes().getFirst().scopeHash().length());
            assertFalse(
                    summary.scopes().getFirst().scopeHash()
                            .contains(PROFILE.toString())
            );
            assertTrue(lookup.find("not-a-uuid-prefix").isEmpty());
        }
    }

    @Test
    void diagnosticReadTimeoutsFailClosed() {
        try (PersistenceBootstrap persistence = started()) {
            var availability = new ReplacementPersistenceAvailabilityProbe(
                    PublicPersistenceFeatureRegistry.create(),
                    persistence.diagnosticsReader()::status,
                    () -> new CompletableFuture<>(),
                    ignored -> CompletableFuture.completedFuture(
                            com.alechilles.alecstamework.persistence.kernel
                                    .PersistenceReadResult.absent()
                    ),
                    kind -> Optional.of("companion_restoration"),
                    Duration.ofMillis(1)
            );
            assertEquals(
                    "GLOBAL_READ_ONLY",
                    availability.query(request(new PersistenceScopeReference(
                            PersistenceScopeKind.PROFILE,
                            PROFILE.toString(),
                            null
                    ))).status()
            );

            byte[] salt = new byte[32];
            var incidents = new ReplacementPersistenceIncidentLookup(
                    PublicPersistenceFeatureRegistry.create(),
                    new PersistenceScopeFactory(salt),
                    ignored -> new CompletableFuture<>(),
                    Duration.ofMillis(1)
            );
            assertTrue(incidents.find(INCIDENT.toString()).isEmpty());
        }
    }

    private PersistenceBootstrap started() {
        PersistenceBootstrap persistence = new PersistenceBootstrap(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "feature-diagnostics-test",
                        () -> -100L,
                        (claim, operation) -> confirmed("refund"),
                        event -> {
                        },
                        boundaries(),
                        PublicPersistenceWorldReconciliation
                                .alreadyComplete(),
                        Duration.ofSeconds(5)
                )
        );
        assertTrue(
                persistence.start().toCompletableFuture().join().complete()
        );
        return persistence;
    }

    private PersistenceMutationAvailabilityRequest request(
            PersistenceScopeReference scope
    ) {
        return new PersistenceMutationAvailabilityRequest(
                PersistenceMutationDomain.DEATH_LOST_RECOVERY,
                "companion_restoration",
                List.of(scope),
                Set.of(),
                PersistenceMutationDirection.ZERO,
                null,
                null,
                false,
                false
        );
    }

    private void writeQuarantine() throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            SqliteIncidentStore incidents =
                    new SqliteIncidentStore(connection);
            incidents.createIncident(new IncidentRecord(
                    INCIDENT,
                    "RECONCILIATION",
                    "profile_recovery_unknown",
                    IncidentState.OPEN,
                    "Profile recovery outcome requires review",
                    "{}",
                    -90L,
                    null
            ));
            incidents.quarantine(new ScopeQuarantine(
                    OperationScope.profile(PROFILE),
                    INCIDENT,
                    QuarantineState.ACTIVE,
                    "profile_recovery_unknown",
                    -90L,
                    null
            ));
            connection.commit();
        }
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) -> confirmed("capture"),
                (request, operation) -> confirmed("capture_release"),
                (request, operation) -> confirmed("restoration"),
                (request, operation) -> confirmed("coop_capture"),
                (request, operation) -> confirmed("coop_release")
        );
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult>
    confirmed(String code) {
        return LiveOperationResult.confirmed(code).completed();
    }
}
