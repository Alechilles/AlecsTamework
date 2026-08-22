package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchAdmissionRequest;
import com.alechilles.alecstamework.companion.population.domain.ManagedAdmissionEvidenceAuthor;
import com.alechilles.alecstamework.config.assets.TwManagedActivityConfig;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Readiness and fail-closed behavior for the managed admission facade. */
class ReplacementPopulationAdmissionApiTest {
    private static final UUID OWNER = UUID.fromString(
            "30000000-0000-0000-0000-000000000411"
    );

    @TempDir
    Path tempDir;

    @Test
    void productionFacadeCanBeComposedBeforePersistenceStartup() {
        try (PersistenceBootstrap persistence =
                     new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers =
                     new AdmissionProviderRegistry()) {
            PopulationGroupConfigRegistry groups =
                    new PopulationGroupConfigRegistry();
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);

            ReplacementPopulationAdmissionApi api =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            persistence.facades().operations(),
                            managed,
                            groups,
                            providers,
                            () -> -50L
                    );

            assertTrue(persistence.start().toCompletableFuture().join().complete());
            assertEquals(0, api.cleanupExpired().toCompletableFuture().join());
        }
    }

    @Test
    void missingManagedProfileFailsClosedBeforeOperationPreparation() {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration())) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            AdmissionProviderRegistry providers = new AdmissionProviderRegistry();
            ManagedAdmissionEvidenceAuthor author =
                    new ManagedAdmissionEvidenceAuthor(
                            managed, groups, providers, () -> -50L
                    );
            ReplacementPopulationAdmissionApi api =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            persistence.facades().operations()
                                    .populationDomainAdmission(),
                            author,
                            managed,
                            providers,
                            () -> -50L
                    );

            PopulationAdmissionDecision result = api.tryAdmitV3(request())
                    .toCompletableFuture().join();

            assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE, result.status());
            assertFalse(api.status("runeteria:husbandry").available());
            assertEquals("profile-not-found",
                    api.status("runeteria:husbandry").detail());
        }
    }

    @Test
    void newProfileBatchComposesOwnerGroupAndWeightedDomainsAtomically()
            throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            TwPopulationGroupConfig group = groupConfig(2);
            assertTrue(groups.replace(List.of(group), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(
                    List.of(managedConfig()), 1L
            ).applied());
            AtomicInteger domainLimit = new AtomicInteger(2);
            providers.register("runeteria:provider", 1, ignored ->
                    CompletableFuture.completedFuture(providerDecision(
                            domainLimit.get(), managed.snapshot().revision()
                    )));
            ReplacementPopulationAdmissionApi api =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            persistence.facades().operations(),
                            managed,
                            groups,
                            providers,
                            () -> -50L
                    );

            PopulationAdmissionDecision single = api.tryAdmitV3(request())
                    .toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.RESERVED,
                    single.status(), single.reason());
            PopulationAdmissionDecision duplicate = api.tryAdmitV3(request())
                    .toCompletableFuture().join();
            assertEquals(single.token(), duplicate.token());
            assertEquals(OperationPhase.LIVE_APPLYING,
                    persistence.facades().operations().populationDomainAdmission()
                            .findByIdempotency(new IdempotencyKey(
                                    "population-domain:facade-test"
                            )).toCompletableFuture().join().orElseThrow().phase());
            assertEquals(PopulationAdmissionDecision.Status.CANCELED,
                    api.cancel(single.token()).toCompletableFuture().join().status());

            ManagedBatchAdmissionRequest preparedBatch =
                    ManagedBatchAdmissionRequest.create(
                            UUID.fromString("50000000-0000-0000-0000-000000000501"),
                            request(),
                            2
                    );
            PopulationAdmissionDecision prepared = api.prepareManagedBatch(
                    preparedBatch
            ).toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.RESERVED,
                    prepared.status(), prepared.reason());
            PopulationAdmissionDecision groupBlocked = api.prepareManagedBatch(
                    ManagedBatchAdmissionRequest.create(
                            UUID.fromString("50000000-0000-0000-0000-000000000504"),
                            request(),
                            1
                    )
            ).toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE,
                    groupBlocked.status());
            assertEquals(PopulationAdmissionDecision.Status.CANCELED,
                    api.cancel(prepared.token()).toCompletableFuture().join().status());

            domainLimit.set(1);
            PopulationAdmissionDecision failed = api.prepareManagedBatch(
                    ManagedBatchAdmissionRequest.create(
                            UUID.fromString("50000000-0000-0000-0000-000000000502"),
                            request(),
                            2
                    )
            ).toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE,
                    failed.status());

            PopulationAdmissionDecision afterRollback = api.prepareManagedBatch(
                    ManagedBatchAdmissionRequest.create(
                            UUID.fromString("50000000-0000-0000-0000-000000000503"),
                            request(),
                            1
                    )
            ).toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.RESERVED,
                    afterRollback.status(), afterRollback.reason());
            assertEquals(PopulationAdmissionDecision.Status.CANCELED,
                    api.cancel(afterRollback.token()).toCompletableFuture().join().status());
        }
    }

    @Test
    void claimForApplyIsInMemoryAndSingleUseUnderConcurrency() throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig(2)), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            providers.register("runeteria:provider", 1, ignored ->
                    CompletableFuture.completedFuture(providerDecision(
                            2, managed.snapshot().revision()
                    )));
            ReplacementPopulationAdmissionApi api =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            persistence.facades().operations(),
                            managed,
                            groups,
                            providers,
                            () -> -50L
                    );

            PopulationAdmissionDecision reserved = api.tryAdmitV3(request())
                    .toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.RESERVED,
                    reserved.status(), reserved.reason());
            persistence.close();

            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<Future<PopulationAdmissionDecision>> attempts =
                        new java.util.ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    attempts.add(executor.submit(
                            () -> api.claimForApply(reserved.token())
                    ));
                }
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)
                        || shutdownAndAwait(executor));
                int applying = 0;
                int unavailable = 0;
                for (Future<PopulationAdmissionDecision> attempt : attempts) {
                    PopulationAdmissionDecision result = attempt.get(
                            2, TimeUnit.SECONDS
                    );
                    if (result.status()
                            == PopulationAdmissionDecision.Status.APPLYING) {
                        applying++;
                    } else if (result.status()
                            == PopulationAdmissionDecision.Status.UNAVAILABLE) {
                        unavailable++;
                    }
                }
                assertEquals(1, applying);
                assertEquals(7, unavailable);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void restartedLiveApplyingTokenCannotCancelAsUnused() throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig(2)), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            providers.register("runeteria:provider", 1, ignored ->
                    CompletableFuture.completedFuture(providerDecision(
                            2, managed.snapshot().revision()
                    )));
            ReplacementPopulationAdmissionApi api =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            persistence.facades().operations(),
                            managed,
                            groups,
                            providers,
                            () -> -50L
                    );
            PopulationAdmissionDecision reserved = api.tryAdmitV3(request())
                    .toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.RESERVED,
                    reserved.status(), reserved.reason());

            ReplacementPopulationAdmissionApi restarted =
                    new ReplacementPopulationAdmissionApi(
                            persistence,
                            persistence.facades().operations(),
                            managed,
                            groups,
                            providers,
                            () -> -50L
                    );
            PopulationAdmissionDecision canceled = restarted.cancel(
                    reserved.token()
            ).toCompletableFuture().join();

            assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE,
                    canceled.status());
            assertEquals(OperationPhase.LIVE_APPLYING,
                    persistence.facades().operations().populationDomainAdmission()
                            .findByIdempotency(new IdempotencyKey(
                                    "population-domain:facade-test"
                            )).toCompletableFuture().join().orElseThrow().phase());
        }
    }

    @Test
    void cleanupRetainsExpiredApplyingTokenWhenContainmentFails() throws Exception {
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(configuration());
             AdmissionProviderRegistry providers = new AdmissionProviderRegistry()) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
            assertTrue(groups.replace(List.of(groupConfig(2)), 1L).applied());
            ManagedActivityConfigRegistry managed =
                    new ManagedActivityConfigRegistry(groups);
            assertTrue(managed.replace(List.of(managedConfig()), 1L).applied());
            providers.register("runeteria:provider", 1, ignored ->
                    CompletableFuture.completedFuture(providerDecision(
                            2, managed.snapshot().revision()
                    )));

            var operations = persistence.facades().operations()
                    .populationDomainAdmission();
            AtomicLong monotonic = new AtomicLong(0L);
            PopulationAdmissionStaging staging = new PopulationAdmissionStaging(
                    operations,
                    () -> -50L,
                    monotonic::get
            );
            ManagedAdmissionEvidenceAuthor author =
                    new ManagedAdmissionEvidenceAuthor(
                            managed, groups, providers, () -> -50L
                    );
            PopulationAdmissionStaging.Identity identity = staging.identity(request());
            ManagedAdmissionEvidenceAuthor.Authoring evidence = author.author(
                    new OperationId(identity.operationId()),
                    identity.reservationId(),
                    request()
            ).toCompletableFuture().join();
            PopulationAdmissionDecision reserved = staging.prepareOrReuse(
                    identity, evidence
            ).toCompletableFuture().join();
            assertEquals(PopulationAdmissionDecision.Status.RESERVED,
                    reserved.status(), reserved.reason());
            assertEquals(PopulationAdmissionDecision.Status.APPLYING,
                    staging.claimForApply(reserved.token()).status());

            monotonic.set(Long.MAX_VALUE);
            persistence.close();

            assertEquals(1, staging.cleanupExpired().toCompletableFuture().join());
            assertEquals(1, staging.cleanupExpired().toCompletableFuture().join());
        }
    }

    private boolean shutdownAndAwait(ExecutorService executor)
            throws InterruptedException {
        executor.shutdown();
        return executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    private PopulationAdmissionProviderDecision providerDecision(
            int domainLimit,
            long configRevision
    ) {
        return new PopulationAdmissionProviderDecision(
                PopulationAdmissionProviderStatus.ALLOW,
                "allowed",
                Set.of(
                        new PopulationDomainClaim(
                                "runeteria:owned", 1, true, false
                        ),
                        new PopulationDomainClaim(
                                "runeteria:deployable", 1, false, true
                        )
                ),
                Map.of(
                        "runeteria:owned", domainLimit,
                        "runeteria:deployable", domainLimit
                ),
                1L,
                configRevision
        );
    }

    private TwPopulationGroupConfig groupConfig(int maxOwned)
            throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("""
                        {
                          "Enabled": true,
                          "Priority": 1,
                          "GroupId": "runeteria:livestock",
                          "RoleIds": ["unmanaged-role"],
                          "Limits": {
                            "MaxOwnedPerOwner": %d,
                            "MaxActivePerOwner": %d,
                            "Scope": "Global"
                          }
                        }
                        """.formatted(maxOwned, maxOwned)),
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

    private static void set(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private PopulationAdmissionRequestV3 request() {
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        null,
                        "40000000-0000-0000-0000-000000000411",
                        "facade-test"
                ),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                OWNER,
                null,
                new PopulationAdmissionLocation("world", 0, 0),
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return new PopulationAdmissionRequestV3(
                new PopulationAdmissionRequestV2(
                        admission, "unmanaged-role", "world"
                ),
                "runeteria:husbandry"
        );
    }

    private PublicPersistenceRuntimeConfiguration configuration() {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "replacement-population-admission-test",
                () -> -100L,
                (claim, operation) -> confirmed("refund"),
                event -> { },
                boundaries(),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
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

    private java.util.concurrent.CompletionStage<LiveOperationResult> confirmed(
            String code
    ) {
        return LiveOperationResult.confirmed(code).completed();
    }
}
