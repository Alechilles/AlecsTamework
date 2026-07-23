package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataEntryView;
import com.alechilles.alecstamework.api.ProfileDataOperationStatus;
import com.alechilles.alecstamework.api.ProfileDataOperationView;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.facade.ReplacementProfileDataApi;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntime;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end released profile-data contract against only replacement authorities. */
class ReplacementProfileDataApiTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private PublicPersistenceRuntime runtime;
    private ReplacementProfileDataApi api;

    @BeforeEach
    void setUp() throws Exception {
        AtomicLong clock = new AtomicLong(-9_000);
        runtime = runtime(clock);
        assertTrue(runtime.start().toCompletableFuture().join().complete());
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                runtime.operations().mutateProfile(
                        OperationId.create(),
                        new IdempotencyKey("profile-create"),
                        profileCreate()
                ).completion().toCompletableFuture().join().status()
        );
        api = new ReplacementProfileDataApi(
                runtime.queries(),
                runtime.operations(),
                clock::incrementAndGet
        );
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void compatibilityReadsWritesListsAndDeletesUseReplacementStore() throws Exception {
        assertTrue(api.put(
                PROFILE.toString(),
                "Example:Mod",
                "settings",
                "{\"enabled\":true}"
        ));
        awaitValue("settings", true);

        assertEquals(
                Map.of("settings", "{\"enabled\":true}"),
                api.list(PROFILE.toString(), "Example:Mod")
        );
        ProfileDataEntryView entry = api.getVersioned(
                PROFILE.toString(),
                "Example:Mod",
                "settings"
        ).orElseThrow();
        assertEquals(1, entry.revision());
        assertTrue(entry.updatedAtMs() < 0);

        assertTrue(api.delete(
                PROFILE.toString(),
                "Example:Mod",
                "settings"
        ));
        awaitValue("settings", false);
        assertEquals(Map.of(), api.list(PROFILE.toString(), "Example:Mod"));
    }

    @Test
    void compareAndSetReplaysAndPublishesDurableDenials() throws Exception {
        ProfileDataCompareAndSetRequest request =
                new ProfileDataCompareAndSetRequest(
                        PROFILE.toString(),
                        "Example:Mod",
                        "counter",
                        0,
                        "create-counter",
                        "{\"value\":1}"
                );

        ProfileDataCompareAndSetResult committed =
                api.compareAndSet(request).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        ProfileDataCompareAndSetResult replay =
                api.compareAndSet(request).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        ProfileDataCompareAndSetResult stale = api.compareAndSet(
                new ProfileDataCompareAndSetRequest(
                        PROFILE.toString(),
                        "Example:Mod",
                        "counter",
                        0,
                        "stale-counter",
                        "{\"value\":2}"
                )
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        Optional<ProfileDataOperationView> found = api.findOperation(
                "Example:Mod",
                "create-counter"
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(ProfileDataCompareAndSetResult.Status.COMMITTED,
                committed.status());
        assertEquals(committed, replay);
        assertEquals(1, committed.committedEntry().orElseThrow().revision());
        assertEquals(ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED,
                stale.status());
        assertEquals("profile-data-revision-mismatch", stale.reason());
        assertEquals(
                committed.durableOperation().orElseThrow().operationId(),
                found.orElseThrow().operationId()
        );
        assertEquals(ProfileDataOperationStatus.COMMITTED,
                found.orElseThrow().status());
        assertTrue(found.orElseThrow().updatedAtMs() < 0);
    }

    @Test
    void reservedNamespaceAndInvalidProfilesFailWithoutAdmission() throws Exception {
        assertFalse(api.put(
                PROFILE.toString(),
                "Alechilles:Tamework",
                "key",
                "{}"
        ));
        assertFalse(api.delete("not-a-uuid", "Example:Mod", "key"));
        assertEquals(
                ProfileDataCompareAndSetResult.Status.UNAVAILABLE,
                api.compareAndSet(new ProfileDataCompareAndSetRequest(
                        PROFILE.toString(),
                        "Alechilles:Tamework",
                        "key",
                        0,
                        "reserved",
                        "{}"
                )).toCompletableFuture().get(10, TimeUnit.SECONDS).status()
        );
        assertEquals(
                Optional.empty(),
                api.findOperation("Alechilles:Tamework", "reserved")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS)
        );
    }

    private PublicPersistenceRuntime runtime(AtomicLong clock) {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "profile-data-facade-test",
                        clock::incrementAndGet,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed").completed(),
                        event -> {
                        },
                        boundaries(),
                        PublicPersistenceWorldReconciliation
                                .alreadyComplete(),
                        Duration.ofSeconds(5)
                )
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (rotation, operation) ->
                        CompanionAliasLiveBoundary.Result.confirmed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("capture").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("restoration").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("coop_capture").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("coop_release").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("timed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("provisioning").completed(),
                com.alechilles.alecstamework.companion.revival
                        .PaidRevivalBoundaries.unavailable()
        );
    }

    private CompanionProfileMutation.Create profileCreate() {
        String metadata = "{\"source\":\"profile-data-test\"}";
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Companion",
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -10_000,
                -10_000,
                -10_000,
                0
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                OwnerId.parse("20000000-0000-0000-0000-000000000001"),
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                -10_000,
                ReconciliationGeneration.INITIAL,
                null
        );
        return new CompanionProfileMutation.Create(
                identity,
                lifecycle,
                java.util.List.of(),
                -10_000
        );
    }

    private void awaitValue(String key, boolean present) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (api.get(PROFILE.toString(), "Example:Mod", key).isPresent()
                    == present) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Profile data did not reach expected state");
    }
}
