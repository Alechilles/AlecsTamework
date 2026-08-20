package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PopulationAdmissionProvider;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV3;
import com.alechilles.alecstamework.api.PopulationCompanionLifecycle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects provider identity, failure translation, and registration lifecycle. */
class AdmissionProviderRegistryTest {
    @Test
    void duplicateRegistrationAndClosedIdentityFailClosed() throws Exception {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry();
        PopulationAdmissionProvider provider = request -> CompletableFuture.completedFuture(
                new PopulationAdmissionProviderDecision(
                        PopulationAdmissionProviderStatus.ALLOW,
                        "allowed",
                        Set.of(),
                        Map.of(),
                        7,
                        9
                )
        );

        AutoCloseable first = registry.register(" Animal.Policy ", 2, provider);
        assertThrows(
                IllegalStateException.class,
                () -> registry.register("animal.policy", 2, provider)
        );
        first.close();
        assertEquals(
                PopulationAdmissionProviderStatus.UNAVAILABLE,
                registry.evaluate("animal.policy", request()).toCompletableFuture()
                        .join().status()
        );
        registry.close();
    }

    @Test
    void callbackFailuresBecomeUnavailable() {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry();
        registry.register("failure", 1, request -> {
            throw new IllegalStateException("provider-failed");
        });
        assertEquals(
                PopulationAdmissionProviderStatus.UNAVAILABLE,
                registry.evaluate("failure", request()).toCompletableFuture()
                        .join().status()
        );
        registry.close();
    }

    @Test
    void callbackOutcomesAreTranslatedAndBlockingCallbacksTimeOut()
            throws Exception {
        try (AdmissionProviderRegistry registry = new AdmissionProviderRegistry(
                Duration.ofMillis(40)
        )) {
            registry.register("allow", 1, ignored -> CompletableFuture.completedFuture(
                    new PopulationAdmissionProviderDecision(
                            PopulationAdmissionProviderStatus.ALLOW,
                            "allowed", Set.of(), Map.of(), 7, 7
                    )
            ));
            registry.register("deny", 1, ignored -> CompletableFuture.completedFuture(
                    new PopulationAdmissionProviderDecision(
                            PopulationAdmissionProviderStatus.DENY,
                            "denied", Set.of(), Map.of(), 7, 7
                    )
            ));
            registry.register("null-stage", 1, ignored -> null);
            registry.register("null-decision", 1,
                    ignored -> CompletableFuture.completedFuture(null));
            registry.register("exceptional", 1, ignored -> {
                CompletableFuture<PopulationAdmissionProviderDecision> failed =
                        new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("bad"));
                return failed;
            });
            CountDownLatch blockingInterrupted = new CountDownLatch(1);
            registry.register("blocking", 1, ignored -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    blockingInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(
                        PopulationAdmissionProviderDecision.unavailable("late")
                );
            });

            assertEquals(PopulationAdmissionProviderStatus.ALLOW,
                    registry.evaluate("allow", request("allow"))
                            .toCompletableFuture().join().status());
            assertEquals(PopulationAdmissionProviderStatus.DENY,
                    registry.evaluate("deny", request("deny"))
                            .toCompletableFuture().join().status());
            assertEquals("provider-null-stage",
                    registry.evaluate("null-stage", request("null-stage"))
                            .toCompletableFuture().join().messageKey());
            assertEquals("provider-null-decision",
                    registry.evaluate("null-decision", request("null-decision"))
                            .toCompletableFuture().join().messageKey());
            assertEquals("provider-exception",
                    registry.evaluate("exceptional", request("exceptional"))
                            .toCompletableFuture().join().messageKey());
            assertEquals("provider-timeout",
                    registry.evaluate("blocking", request("blocking"))
                            .toCompletableFuture().join().messageKey());
            assertTrue(blockingInterrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void workerAndQueueSaturationFailsClosedWithoutUnboundedBacklog()
            throws Exception {
        try (AdmissionProviderRegistry registry = new AdmissionProviderRegistry(
                Duration.ofSeconds(5)
        )) {
            CountDownLatch workersStarted = new CountDownLatch(4);
            CountDownLatch releaseWorkers = new CountDownLatch(1);
            PopulationAdmissionProviderDecision allowed =
                    new PopulationAdmissionProviderDecision(
                            PopulationAdmissionProviderStatus.ALLOW,
                            "allowed",
                            Set.of(),
                            Map.of(),
                            7,
                            9
                    );
            registry.register("saturated", 1, ignored -> {
                workersStarted.countDown();
                try {
                    releaseWorkers.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture(allowed);
            });

            List<CompletableFuture<PopulationAdmissionProviderDecision>> results =
                    new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                results.add(registry.evaluate("saturated", request("saturated"))
                        .toCompletableFuture());
            }
            assertTrue(workersStarted.await(1, TimeUnit.SECONDS));
            for (int index = 0; index < 32; index++) {
                results.add(registry.evaluate("saturated", request("saturated"))
                        .toCompletableFuture());
            }

            long saturated = results.stream()
                    .filter(CompletableFuture::isDone)
                    .map(CompletableFuture::join)
                    .filter(result -> "provider-saturated".equals(
                            result.messageKey()
                    ))
                    .count();
            assertTrue(saturated > 0);

            releaseWorkers.countDown();
            results.forEach(CompletableFuture::join);
        }
    }

    @Test
    void closeCancelsQueuedAndRunningProviderWork() throws Exception {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry(
                Duration.ofSeconds(5)
        );
        CountDownLatch workersStarted = new CountDownLatch(4);
        CountDownLatch workersInterrupted = new CountDownLatch(4);
        PopulationAdmissionProviderDecision allowed =
                new PopulationAdmissionProviderDecision(
                        PopulationAdmissionProviderStatus.ALLOW,
                        "allowed",
                        Set.of(),
                        Map.of(),
                        7,
                        9
                );
        registry.register("close", 1, ignored -> {
            workersStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                workersInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(allowed);
        });

        List<CompletableFuture<PopulationAdmissionProviderDecision>> results =
                new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            results.add(registry.evaluate("close", request("close"))
                    .toCompletableFuture());
        }
        assertTrue(workersStarted.await(1, TimeUnit.SECONDS));
        results.add(registry.evaluate("close", request("close"))
                .toCompletableFuture());

        registry.close();

        results.forEach(result -> assertEquals(
                "provider-closed", result.join().messageKey()
        ));
        assertTrue(workersInterrupted.await(1, TimeUnit.SECONDS));
        assertEquals("provider-not-ready",
                registry.evaluate("close", request("close"))
                        .toCompletableFuture().join().messageKey());
    }

    @Test
    void closeMakesInFlightEvaluationUnavailableAndAllowsGenerationReplacement()
            throws Exception {
        AdmissionProviderRegistry registry = new AdmissionProviderRegistry(
                Duration.ofSeconds(1)
        );
        AutoCloseable first = registry.register(
                "generation", 1,
                ignored -> CompletableFuture.completedFuture(
                        PopulationAdmissionProviderDecision.unavailable("first")
                )
        );
        String firstGeneration = registry.readiness("generation", 1)
                .generationToken();
        first.close();
        registry.register(
                "generation", 1,
                ignored -> CompletableFuture.completedFuture(
                        PopulationAdmissionProviderDecision.unavailable("second")
                )
        );
        assertNotEquals(firstGeneration,
                registry.readiness("generation", 1).generationToken());
        registry.close();
        assertEquals("provider-not-ready",
                registry.evaluate("generation", request("generation"))
                        .toCompletableFuture().join().messageKey());
    }

    private PopulationAdmissionProviderRequest request() {
        return request("failure");
    }

    private PopulationAdmissionProviderRequest request(String providerId) {
        PopulationAdmissionRequest admission = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(
                        null,
                        "20000000-0000-0000-0000-000000000401",
                        "provider-test"
                ),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                java.util.UUID.fromString(
                        "30000000-0000-0000-0000-000000000401"
                ),
                null,
                new PopulationAdmissionLocation("world", 0, 0),
                PopulationAdmissionOperation.NEW_OWNERSHIP,
                1,
                PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        return new PopulationAdmissionProviderRequest(
                providerId,
                1,
                new PopulationAdmissionRequestV3(
                        new PopulationAdmissionRequestV2(
                                admission, "role", "world"
                        ),
                        "profile"
                ),
                "group",
                Set.of("group"),
                "gate",
                1,
                7
        );
    }
}
