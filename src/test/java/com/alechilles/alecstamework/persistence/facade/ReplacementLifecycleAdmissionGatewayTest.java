package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
                            "BreedingSuccess":"runeteria:breed"
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
