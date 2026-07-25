package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.PersistenceMutationAvailabilityView;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.persistence.facade.ReplacementCompanionProvisioningApi;
import com.alechilles.alecstamework.persistence.facade.ReplacementPaidCommandRevivalApi;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplacementTameworkApiFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void composesStablePublicApiOnlyFromReplacementFacades()
            throws Exception {
        TameworkEventBus events = new TameworkEventBus(null);
        AtomicInteger profileEvents = new AtomicInteger();
        events.subscribe(
                NpcProfileChangedEvent.class,
                event -> profileEvents.incrementAndGet()
        );
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(
                configuration(events)
        )) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            var created = persistence.facades().operations().mutateProfile(
                    OperationId.create(),
                    new IdempotencyKey("api-composition-profile"),
                    profile()
            );
            assertTrue(created.accepted());
            created.completion().toCompletableFuture().get(
                    5, TimeUnit.SECONDS
            );

            try (TameworkApiImpl api =
                         ReplacementTameworkApiFactory.create(
                                 persistence,
                                 Duration.ofSeconds(5),
                                 () -> -50L,
                                 events,
                                 null,
                                 new InteractionExtensionRegistry(null),
                                 new TraitEffectRegistry(null, null),
                                 new SimpleClaimsTamedDamagePolicy()
                         )) {
                assertTrue(api.getByProfileId(profileId().toString()).isPresent());
                assertEquals(
                        "HEALTHY",
                        api.diagnostics().getPersistenceDiagnostics()
                                .health().status()
                );
                ProfileDataCompareAndSetResult extension =
                        api.profileData().compareAndSet(
                                new ProfileDataCompareAndSetRequest(
                                        profileId().toString(),
                                        "Alechilles:Test",
                                        "state",
                                        0L,
                                        "create-1",
                                        "{\"ready\":true}"
                                )
                        ).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(
                        ProfileDataCompareAndSetResult.Status.COMMITTED,
                        extension.status()
                );
                assertEquals(
                        "{\"ready\":true}",
                        api.profileData().get(
                                profileId().toString(),
                                "Alechilles:Test",
                                "state"
                        ).orElseThrow()
                );
            }
            assertEquals(1, profileEvents.get());
        } finally {
            events.close();
        }
    }

    @Test
    void composesRestoredFeaturesBehindReadinessAndLifecycleSeam() {
        TameworkEventBus events = new TameworkEventBus(null);
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(
                configuration(events)
        )) {
            assertTrue(
                    persistence.start().toCompletableFuture().join().complete()
            );
            try (ReplacementTameworkApiFactory.Composition composition =
                         ReplacementTameworkApiFactory.compose(
                                 persistence,
                                 Duration.ofSeconds(5),
                                 () -> -50L,
                                 events,
                                 null,
                                 new InteractionExtensionRegistry(null),
                                 new TraitEffectRegistry(null, null),
                                 new SimpleClaimsTamedDamagePolicy(),
                                 restoredDependencies()
                         )) {
                TameworkApi api = composition.api();
                assertTrue(api instanceof ReplacementTameworkApi);
                assertTrue(api.getCapabilities().containsAll(List.of(
                        TameworkApiCapability.PERSISTENCE_RESILIENCE,
                        TameworkApiCapability.POPULATION_GROUPS,
                        TameworkApiCapability.COMMAND_FAMILY_ROSTERS,
                        TameworkApiCapability.COMMAND_TIMED_SUMMONING,
                        TameworkApiCapability.COMPANION_PROVISIONING,
                        TameworkApiCapability.PAID_COMMAND_REVIVAL,
                        TameworkApiCapability
                                .CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION,
                        TameworkApiCapability.CAPTURE_TAME_AND_LINK
                )));
                composition.onRuntimeSettingsChanged();
            }
        } finally {
            events.close();
        }
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            TameworkEventBus events
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "replacement-api-composition-test",
                () -> -100L,
                (claim, operation) -> confirmed("refund"),
                events::publishProfileChanged,
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

    private ReplacementFeatureApiDependencies restoredDependencies() {
        var rosterAuthor = (com.alechilles.alecstamework.persistence.facade
                .ReplacementCommandFamilyRosterApi.MutationAuthor)
                (request, action) -> CompletableFuture.completedFuture(null);
        var timedAuthor = (com.alechilles.alecstamework.persistence.facade
                .ReplacementCommandTimedSummoningApi.TransitionAuthor)
                (request, action) -> CompletableFuture.completedFuture(null);
        var provisioningAuthor =
                new ReplacementCompanionProvisioningApi.MutationAuthor() {
                    @Override
                    public java.util.concurrent.CompletionStage<
                            ReplacementCompanionProvisioningApi
                                    .PreparedProvisioning> prepare(
                            com.alechilles.alecstamework.api
                                    .CompanionProvisioningRequest request
                    ) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            ReplacementCompanionProvisioningApi
                                    .PreparedProvisioning> prepare(
                            com.alechilles.alecstamework.api
                                    .CompanionProvisioningLinkRequest request
                    ) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            ReplacementCompanionProvisioningApi
                                    .PreparedTransition> prepare(
                            com.alechilles.alecstamework.api
                                    .ProvisionedCompanionTransitionRequest request
                    ) {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var paidAuthor =
                new ReplacementPaidCommandRevivalApi.RequestAuthor() {
                    @Override
                    public java.util.concurrent.CompletionStage<
                            com.alechilles.alecstamework.api
                                    .PaidCommandRevivalQuote> quote(
                            com.alechilles.alecstamework.api
                                    .PaidCommandRevivalQuoteRequest request
                    ) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public java.util.concurrent.CompletionStage<
                            ReplacementPaidCommandRevivalApi.PreparedRevival>
                    prepare(
                            com.alechilles.alecstamework.api
                                    .PaidCommandRevivalRequest request
                    ) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public IdempotencyKey operationKey(
                            String callerNamespace,
                            String idempotencyKey
                    ) {
                        return new IdempotencyKey(
                                callerNamespace + ":" + idempotencyKey
                        );
                    }
                };
        return new ReplacementFeatureApiDependencies(
                new PopulationGroupConfigRegistry(),
                rosterAuthor,
                timedAuthor,
                provisioningAuthor,
                paidAuthor,
                request -> new PersistenceMutationAvailabilityView(
                        "ALLOW", "ready", null
                ),
                ignored -> Optional.empty(),
                true,
                true
        );
    }

    private CompanionProfileMutation.Create profile() {
        String metadata = "{\"source\":\"api-composition-test\"}";
        CompanionIdentity identity = new CompanionIdentity(
                profileId(),
                "Companion",
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -200L,
                -200L,
                -200L,
                0L
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId(),
                null,
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                -200L,
                ReconciliationGeneration.INITIAL,
                null
        );
        return new CompanionProfileMutation.Create(
                identity, lifecycle, List.of(), -200L
        );
    }

    private ProfileId profileId() {
        return ProfileId.parse(
                "20000000-0000-0000-0000-000000000007"
        );
    }
}
