package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationStatus;
import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningResult;
import com.alechilles.alecstamework.api.CompanionProvisioningDisposition;
import com.alechilles.alecstamework.api.CompanionProvisioningRequest;
import com.alechilles.alecstamework.api.CompanionProvisioningResult;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivalRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PersistenceDomainFacades;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for facade boundaries that must reject incomplete live
 * evidence before any shared persistence operation is submitted.
 */
class ReplacementPersistenceFacadesFailClosedTest {
    private static final UUID OWNER =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final String PROFILE =
            "40000000-0000-0000-0000-000000000002";

    @TempDir
    Path tempDir;

    private PersistenceBootstrap persistence;
    private PersistenceDomainFacades facades;

    @BeforeEach
    void startPersistence() {
        persistence = new PersistenceBootstrap(configuration());
        assertTrue(persistence.start().toCompletableFuture().join().complete());
        facades = persistence.facades();
    }

    @AfterEach
    void closePersistence() {
        if (persistence != null) {
            persistence.close();
        }
    }

    @Test
    void rosterMutationDoesNotInventAuthorEvidence() {
        ReplacementCommandFamilyRosterApi api =
                new ReplacementCommandFamilyRosterApi(
                        facades.queries(),
                        facades.operations(),
                        (request, action) -> {
                            throw new IllegalStateException(
                                    "roster-evidence-unavailable"
                            );
                        }
                );
        var result = api.upsert(rosterRequest())
                .toCompletableFuture().join();

        assertEquals(
                CommandFamilyRosterMutationStatus.UNAVAILABLE,
                result.status()
        );
        assertEquals("roster-evidence-unavailable", result.reason());
        assertFalse(result.accepted());
    }

    @Test
    void timedMutationDoesNotInventTransitionEvidence() {
        ReplacementCommandTimedSummoningApi api =
                new ReplacementCommandTimedSummoningApi(
                        facades.queries(),
                        facades.operations(),
                        (request, action) -> {
                            throw new IllegalStateException(
                                    "timed-evidence-unavailable"
                            );
                        },
                        () -> -50L
                );
        CommandTimedSummoningResult result = api.summon(timedRequest())
                .toCompletableFuture().join();

        assertEquals(
                CommandTimedSummoningResult.Status.UNAVAILABLE,
                result.status()
        );
        assertEquals("timed-evidence-unavailable", result.reason());
    }

    @Test
    void provisioningDoesNotInventCanonicalCreationEvidence() {
        ReplacementCompanionProvisioningApi api =
                new ReplacementCompanionProvisioningApi(
                        facades.queries(),
                        facades.operations(),
                        rejectingProvisioningAuthor(),
                        CommandFamilyRosterApi.unavailable(),
                        CommandTimedSummoningApi.unavailable(),
                        Duration.ofSeconds(5)
                );
        CompanionProvisioningResult result = api.provision(
                provisioningRequest()
        ).toCompletableFuture().join();

        assertEquals(
                CompanionProvisioningResult.Status.UNAVAILABLE,
                result.status()
        );
        assertEquals("provisioning-evidence-unavailable", result.reason());
    }

    @Test
    void paidRevivalDoesNotInventInventoryOrWorldEvidence() {
        ReplacementPaidCommandRevivalApi api =
                new ReplacementPaidCommandRevivalApi(
                        facades.queries(),
                        facades.operations(),
                        rejectingPaidAuthor()
                );
        PaidCommandRevivalQuote quote = api.quote(
                new PaidCommandRevivalQuoteRequest(
                        OWNER, PROFILE, "companions"
                )
        ).toCompletableFuture().join();
        PaidCommandRevivalResult result = api.revive(
                new PaidCommandRevivalRequest(
                        "test", "paid-1", OWNER, PROFILE, "companions"
                )
        ).toCompletableFuture().join();

        assertEquals(PaidCommandRevivalQuote.Status.UNAVAILABLE, quote.status());
        assertEquals(PaidCommandRevivalResult.Status.UNAVAILABLE, result.status());
        assertEquals("paid-evidence-unavailable", result.reason());
    }

    private ReplacementCompanionProvisioningApi.MutationAuthor
    rejectingProvisioningAuthor() {
        return new ReplacementCompanionProvisioningApi.MutationAuthor() {
            @Override
            public java.util.concurrent.CompletionStage<
                    ReplacementCompanionProvisioningApi.PreparedProvisioning>
            prepare(CompanionProvisioningRequest request) {
                throw unavailable();
            }

            @Override
            public java.util.concurrent.CompletionStage<
                    ReplacementCompanionProvisioningApi.PreparedProvisioning>
            prepare(
                    com.alechilles.alecstamework.api
                            .CompanionProvisioningLinkRequest request
            ) {
                throw unavailable();
            }

            @Override
            public java.util.concurrent.CompletionStage<
                    ReplacementCompanionProvisioningApi.PreparedTransition>
            prepare(
                    com.alechilles.alecstamework.api
                            .ProvisionedCompanionTransitionRequest request
            ) {
                throw unavailable();
            }

            private IllegalStateException unavailable() {
                return new IllegalStateException(
                        "provisioning-evidence-unavailable"
                );
            }
        };
    }

    private ReplacementPaidCommandRevivalApi.RequestAuthor
    rejectingPaidAuthor() {
        return new ReplacementPaidCommandRevivalApi.RequestAuthor() {
            @Override
            public java.util.concurrent.CompletionStage<
                    PaidCommandRevivalQuote> quote(
                    PaidCommandRevivalQuoteRequest request
            ) {
                throw unavailable();
            }

            @Override
            public java.util.concurrent.CompletionStage<
                    ReplacementPaidCommandRevivalApi.PreparedRevival> prepare(
                    PaidCommandRevivalRequest request
            ) {
                throw unavailable();
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

            private IllegalStateException unavailable() {
                return new IllegalStateException(
                        "paid-evidence-unavailable"
                );
            }
        };
    }

    private CommandFamilyRosterMutationRequest rosterRequest() {
        return new CommandFamilyRosterMutationRequest(
                "test",
                "roster-1",
                null,
                OWNER,
                "companions",
                PROFILE,
                null,
                null,
                CommandFamilyRosterMemberState.ROSTER_STORED,
                null,
                true,
                null,
                0L,
                0L
        );
    }

    private CommandTimedSummoningRequest timedRequest() {
        return new CommandTimedSummoningRequest(
                OWNER, "companions", PROFILE, "timed-1"
        );
    }

    private CompanionProvisioningRequest provisioningRequest() {
        return new CompanionProvisioningRequest(
                "test",
                "provision-1",
                null,
                OWNER,
                "Tamed_Chicken",
                CompanionProvisioningDisposition.PROVISIONED_DORMANT,
                "world",
                null,
                null,
                null,
                CompanionProvisioningRequest.CURRENT_POLICY_REVISION
        );
    }

    private PublicPersistenceRuntimeConfiguration configuration() {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "replacement-facade-fail-closed-test",
                () -> -100L,
                (claim, operation) -> confirmed("refund"),
                event -> {
                },
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
