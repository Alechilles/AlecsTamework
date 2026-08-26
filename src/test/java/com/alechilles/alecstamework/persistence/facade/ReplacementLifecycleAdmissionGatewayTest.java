package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionIdentityStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionEvidence;
import com.alechilles.alecstamework.persistence.runtime.LifecycleAdmissionRequest;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production lifecycle-gateway behavior for source-only managed transitions. */
class ReplacementLifecycleAdmissionGatewayTest {
    private static final UUID OWNER = UUID.fromString(
            "30000000-0000-0000-0000-000000000412"
    );
    private static final ProfileId PROFILE = ProfileId.parse(
            "40000000-0000-0000-0000-000000000412"
    );

    @TempDir
    Path tempDir;

    @Test
    void sourceOnlyManagedTransitionKeepsDurableEvidence() throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig()), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            ReplacementLifecycleAdmissionGateway gateway =
                    new ReplacementLifecycleAdmissionGateway(
                            persistence, managed, groups, providers, () -> -50L
                    );

            LifecycleAdmissionEvidence evidence = gateway.authorize(request())
                    .toCompletableFuture().join();

            assertEquals(LifecycleAdmissionEvidence.Status.MANAGED, evidence.status());
            assertTrue(evidence.payload().domains().isEmpty());
        }
    }

    @Test
    void capturedReleaseAcceptsCurrentAssignmentFromEarlierLifecycle()
            throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig()), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            providers.register("runeteria:provider", 1, ignored ->
                    CompletableFuture.completedFuture(providerDecision(
                            managed.snapshot().revision()
                    )));
            seedEarlierAssignment(
                    persistence,
                    groups.snapshot().resolvePoliciesForRole("managed-role")
                            .getFirst().policyRevision()
            );
            ReplacementLifecycleAdmissionGateway gateway =
                    new ReplacementLifecycleAdmissionGateway(
                            persistence, managed, groups, providers, () -> -50L
                    );

            LifecycleAdmissionEvidence evidence = gateway.authorize(
                    capturedReleaseRequest(new OwnerId(OWNER))
            ).toCompletableFuture().join();

            assertEquals(LifecycleAdmissionEvidence.Status.MANAGED, evidence.status());
            assertEquals(new LifecycleRevision(1),
                    evidence.payload().expectedLifecycleRevision());
            assertEquals(new LifecycleRevision(1),
                    evidence.composition().groupRequest().before().revision());
        }
    }

    @Test
    void capturedReleaseAuthorsInitialGroupEvidenceWhenAssignmentIsMissing()
            throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig()), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            providers.register("runeteria:provider", 1, ignored ->
                    CompletableFuture.completedFuture(providerDecision(
                            managed.snapshot().revision()
                    )));
            ReplacementLifecycleAdmissionGateway gateway =
                    new ReplacementLifecycleAdmissionGateway(
                            persistence, managed, groups, providers, () -> -50L
            );

            LifecycleAdmissionEvidence evidence = gateway.authorize(
                    capturedReleaseRequest(null, new OwnerId(OWNER))
            ).toCompletableFuture().join();

            assertEquals(LifecycleAdmissionEvidence.Status.MANAGED, evidence.status());
            assertTrue(evidence.composition().ownerPlan() != null);
            assertEquals(0, evidence.composition().groupRequest()
                    .expectedAssignmentRevision());
            assertEquals("runeteria:livestock", evidence.composition()
                    .groupRequest().policies().getFirst().groupId());
        }
    }

    /** Protects revival of managed profiles created before group tracking. */
    @Test
    void deadRestorationAuthorsInitialGroupEvidenceWhenAssignmentIsMissing()
            throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig()), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            providers.register("runeteria:provider", 1, ignored ->
                    CompletableFuture.completedFuture(providerDecision(
                            managed.snapshot().revision()
                    )));
            ReplacementLifecycleAdmissionGateway gateway =
                    new ReplacementLifecycleAdmissionGateway(
                            persistence, managed, groups, providers, () -> -50L
                    );

            LifecycleAdmissionEvidence evidence = gateway.authorize(
                    deadRestorationRequest()
            ).toCompletableFuture().join();

            assertEquals(LifecycleAdmissionEvidence.Status.MANAGED, evidence.status());
            assertEquals(0, evidence.composition().groupRequest()
                    .expectedAssignmentRevision());
            assertEquals("runeteria:livestock", evidence.composition()
                    .groupRequest().policies().getFirst().groupId());
        }
    }

    @Test
    void unownedReleaseKeepsDestinationSeparateFromOwnerWorld()
            throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig()), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            ReplacementLifecycleAdmissionGateway gateway =
                    new ReplacementLifecycleAdmissionGateway(
                            persistence, managed, groups, providers, () -> -50L
                    );

            LifecycleAdmissionEvidence evidence = gateway.authorize(
                    capturedReleaseRequest(null)
            ).toCompletableFuture().join();

            assertEquals(LifecycleAdmissionEvidence.Status.MANAGED, evidence.status());
            assertEquals(null, evidence.payload().ownerId());
            assertEquals(null, evidence.payload().ownerWorldKey());
            assertTrue(evidence.payload().domains().isEmpty());
        }
    }

    private LifecycleAdmissionRequest request() {
        LifecycleRevision revision = LifecycleRevision.INITIAL;
        CompanionLifecycle source = new CompanionLifecycle(
                PROFILE,
                new OwnerId(OWNER),
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(
                        "50000000-0000-0000-0000-000000000412", "world"
                ),
                revision,
                null,
                -60L,
                ReconciliationGeneration.INITIAL,
                null,
                "world"
        );
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(PROFILE.toString(), null, null),
                UUID.fromString("50000000-0000-0000-0000-000000000412"),
                revision.value(),
                OWNER,
                OWNER,
                new PopulationAdmissionLocation("world", 0, 0),
                new PopulationAdmissionLocation("world", 0, 0),
                PopulationAdmissionOperation.LIFECYCLE_CHANGE,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.CAPTURED
        );
        return LifecycleAdmissionRequest.managed(
                OperationId.parse("60000000-0000-0000-0000-000000000412"),
                UUID.fromString("70000000-0000-0000-0000-000000000412"),
                "managed-role",
                new PopulationAdmissionRequestV2(
                        admission, "managed-role", "world"
                ),
                source,
                LifecycleState.ACTIVE,
                LifecycleState.CAPTURED,
                new OwnerId(OWNER),
                "world"
        );
    }

    private LifecycleAdmissionRequest capturedReleaseRequest(OwnerId owner) {
        return capturedReleaseRequest(owner, owner);
    }

    private LifecycleAdmissionRequest capturedReleaseRequest(
            OwnerId sourceOwner,
            OwnerId targetOwner
    ) {
        LifecycleRevision revision = new LifecycleRevision(1);
        CompanionLifecycle source = new CompanionLifecycle(
                PROFILE,
                sourceOwner,
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        com.alechilles.alecstamework.companion.lifecycle
                                .LifecycleLocationKind.CAPTURE_ITEM,
                        "50000000-0000-0000-0000-000000000413"
                ),
                revision,
                null,
                -60L,
                ReconciliationGeneration.INITIAL,
                null,
                sourceOwner == null ? null : "world"
        );
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(PROFILE.toString(), null, null),
                targetOwner == null
                        ? UUID.fromString("50000000-0000-0000-0000-000000000412")
                        : null,
                revision.value(),
                sourceOwner == null ? null : sourceOwner.value(),
                targetOwner == null ? null : targetOwner.value(),
                sourceOwner == null
                        ? null
                        : new PopulationAdmissionLocation("world", 0, 0),
                new PopulationAdmissionLocation("world-two", 0, 0),
                targetOwner == null
                        ? PopulationAdmissionOperation.LIFECYCLE_CHANGE
                        : PopulationAdmissionOperation.RESTORE,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return LifecycleAdmissionRequest.managed(
                OperationId.parse("60000000-0000-0000-0000-000000000413"),
                UUID.fromString("70000000-0000-0000-0000-000000000413"),
                "managed-role",
                new PopulationAdmissionRequestV2(admission, "managed-role", "world-two"),
                source,
                LifecycleState.CAPTURED,
                LifecycleState.ACTIVE,
                sourceOwner,
                sourceOwner == null ? null : "world"
        );
    }

    private LifecycleAdmissionRequest deadRestorationRequest() {
        LifecycleRevision revision = new LifecycleRevision(1);
        CompanionLifecycle source = new CompanionLifecycle(
                PROFILE,
                new OwnerId(OWNER),
                LifecycleState.DEAD_REVIVABLE,
                LifecycleLocation.none(),
                revision,
                null,
                -60L,
                ReconciliationGeneration.INITIAL,
                null,
                "world"
        );
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(PROFILE.toString(), null, null),
                null,
                revision.value(),
                OWNER,
                OWNER,
                new PopulationAdmissionLocation("world", 0, 0),
                new PopulationAdmissionLocation("world-two", 0, 0),
                PopulationAdmissionOperation.RESTORE,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return LifecycleAdmissionRequest.managed(
                OperationId.parse("60000000-0000-0000-0000-000000000414"),
                UUID.fromString("70000000-0000-0000-0000-000000000414"),
                "managed-role",
                new PopulationAdmissionRequestV2(
                        admission, "managed-role", "world-two"
                ),
                source,
                LifecycleState.DEAD_REVIVABLE,
                LifecycleState.ACTIVE,
                new OwnerId(OWNER),
                "world"
        );
    }

    private void seedEarlierAssignment(
            PersistenceBootstrap persistence,
            long policyRevision
    ) throws Exception {
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                persistence.databasePath().orElseThrow()
        );
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            assertTrue(new SqliteCompanionIdentityStore(connection).createProfile(
                    new CompanionIdentity(
                            PROFILE, "Captured", "managed-role", null, null,
                            "world", -100, -100, -100, 0
                    )
            ).applied());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO population_group_classification(
                        profile_id, role_id, policy_revision,
                        source_metadata_revision, source_lifecycle_revision,
                        assignment_revision, assigned_at_ms
                    ) VALUES (?, 'managed-role', ?, 0, 0, 1, -100)
                    """)) {
                statement.setString(1, PROFILE.toString());
                statement.setLong(2, policyRevision);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO population_group_membership(
                        profile_id, group_id, scope_kind
                    ) VALUES (?, 'runeteria:livestock', 'GLOBAL')
                    """)) {
                statement.setString(1, PROFILE.toString());
                statement.executeUpdate();
            }
            connection.commit();
        }
    }

    private PopulationAdmissionProviderDecision providerDecision(
            long configRevision
    ) {
        return new PopulationAdmissionProviderDecision(
                PopulationAdmissionProviderStatus.ALLOW,
                "allowed",
                Set.of(
                        new PopulationDomainClaim("runeteria:owned", 1, true, false),
                        new PopulationDomainClaim("runeteria:deployable", 1, false, true)
                ),
                Map.of("runeteria:owned", 10, "runeteria:deployable", 10),
                1,
                configRevision
        );
    }

    private TwPopulationGroupConfig groupConfig() throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "Enabled": true,
                          "Priority": 1,
                          "GroupId": "runeteria:livestock",
                          "RoleIds": ["managed-role"],
                          "Limits": {
                            "MaxOwnedPerOwner": 2,
                            "MaxActivePerOwner": 2,
                            "Scope": "Global"
                          }
                        }
                        """),
                new ExtraInfo()
        );
        set(config, "id", "Livestock");
        return config;
    }

    private TwManagedActivityConfig managedConfig() throws Exception {
        TwManagedActivityConfig config = TwManagedActivityConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "ProfileId": "runeteria:husbandry",
                          "ProviderId": "runeteria:provider",
                          "ProviderContractVersion": 1,
                          "RequiredCapabilities": ["PROFILES"],
                          "Domains": [
                            {"DomainId":"runeteria:owned", "Owned":true},
                            {"DomainId":"runeteria:deployable", "Deployable":true}
                          ],
                          "Families": [
                            {"GroupId":"runeteria:livestock", "GateKey":"runeteria:husbandry", "Weight":1}
                          ],
                          "Activities": {
                            "Feed":"runeteria:feed",
                            "HarvestContexts":{"Milk":"runeteria:milk"},
                            "PendingOutputItems":{"Food_Egg":"runeteria:egg"},
                            "BreedingSuccess":"runeteria:breed",
                            "TameSuccess":"runeteria:tame_success",
                            "NeedSatisfied":"runeteria:need_satisfied"
                          }
                        }
                        """),
                new ExtraInfo()
        );
        set(config, "id", "Husbandry");
        return config;
    }

    private PublicPersistenceRuntimeConfiguration configuration() {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "replacement-lifecycle-gateway-test",
                () -> -100L,
                (claim, operation) -> confirmed("refund"),
                event -> { },
                new PublicPersistenceLiveBoundaries(
                        (request, operation) -> confirmed("capture"),
                        (request, operation) -> confirmed("capture_release"),
                        (request, operation) -> confirmed("restoration"),
                        (request, operation) -> confirmed("coop_capture"),
                        (request, operation) -> confirmed("coop_release")
                ),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult> confirmed(
            String code
    ) {
        return LiveOperationResult.confirmed(code).completed();
    }

    private static void set(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
