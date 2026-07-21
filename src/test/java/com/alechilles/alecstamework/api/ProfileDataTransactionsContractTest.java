package com.alechilles.alecstamework.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileDataTransactionsContractTest {
    @Test
    void legacyImplementationInheritsFailClosedTransactionalMethods() {
        ProfileDataApi legacy = new LegacyProfileDataApi();

        assertEquals(Optional.empty(), legacy.getVersioned("profile", "plugin", "state"));
        assertEquals(
                ProfileDataCompareAndSetResult.Status.UNAVAILABLE,
                legacy.compareAndSet(request()).toCompletableFuture().join().status()
        );
        assertEquals(
                Optional.empty(),
                legacy.findOperation("plugin", "attune:owner:fire:1").toCompletableFuture().join()
        );
        assertThrows(NullPointerException.class, () -> legacy.compareAndSet(null));
    }

    @Test
    void requestNormalizesStableOriginAndAllowsMissingRevisionFence() {
        ProfileDataCompareAndSetRequest request = new ProfileDataCompareAndSetRequest(
                " profile-mini ",
                " Alechilles:HyDragon ",
                " profile ",
                ProfileDataCompareAndSetRequest.MISSING_REVISION,
                " attune:owner:fire:1 ",
                " {\"archetypeId\":\"fire\"} "
        );

        assertEquals("profile-mini", request.profileId());
        assertEquals("Alechilles:HyDragon", request.namespace());
        assertEquals("attune:owner:fire:1", request.idempotencyKey());
        assertEquals(0L, request.expectedRevision());
        assertThrows(IllegalArgumentException.class, () -> new ProfileDataCompareAndSetRequest(
                "profile", "plugin", "state", -1L, "operation", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDataCompareAndSetRequest(
                "profile", "plugin", "state", Long.MAX_VALUE, "operation", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDataCompareAndSetRequest(
                "profile", "plugin", "state", 0L, "operation", "not json"));
    }

    @Test
    void committedResultProvesOnePublishedRevisionAndExactEntry() {
        UUID operationId = UUID.randomUUID();
        ProfileDataOperationView operation = new ProfileDataOperationView(
                operationId,
                "Alechilles:HyDragon",
                "attune:owner:fire:1",
                "profile-mini",
                "profile",
                4L,
                5L,
                "sha256:desired-payload",
                ProfileDataOperationStatus.COMMITTED,
                "committed",
                50L
        );
        ProfileDataEntryView entry = new ProfileDataEntryView(
                "profile-mini",
                "Alechilles:HyDragon",
                "profile",
                5L,
                "{\"archetypeId\":\"fire\"}",
                50L
        );

        ProfileDataCompareAndSetResult result = new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.COMMITTED,
                "committed",
                operation,
                entry
        );

        assertTrue(result.committed());
        assertTrue(result.durableOperation().orElseThrow().terminal());
        assertEquals(5L, result.committedEntry().orElseThrow().revision());
    }

    @Test
    void terminalDenialCannotMasqueradeAsCommittedData() {
        ProfileDataOperationView denied = new ProfileDataOperationView(
                UUID.randomUUID(),
                "Alechilles:HyDragon",
                "attune:owner:fire:1",
                "profile-mini",
                "profile",
                4L,
                ProfileDataOperationView.UNKNOWN_REVISION,
                "sha256:desired-payload",
                ProfileDataOperationStatus.TERMINAL_DENIED,
                "revision-mismatch",
                51L
        );

        ProfileDataCompareAndSetResult result = new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED,
                "revision-mismatch",
                denied,
                null
        );

        assertFalse(result.committed());
        assertTrue(result.committedEntry().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.COMMITTED,
                "not-really-committed",
                denied,
                null
        ));
    }

    private static ProfileDataCompareAndSetRequest request() {
        return new ProfileDataCompareAndSetRequest(
                "profile-mini",
                "Alechilles:HyDragon",
                "profile",
                2L,
                "attune:owner:fire:1",
                "{\"archetypeId\":\"fire\"}"
        );
    }

    private static final class LegacyProfileDataApi implements ProfileDataApi {
        @Override public Optional<String> get(String profileId, String namespace, String key) {
            return Optional.empty();
        }

        @Override public Map<String, String> list(String profileId, String namespace) {
            return Map.of();
        }

        @Override public boolean put(String profileId, String namespace, String key, String jsonPayload) {
            return false;
        }

        @Override public boolean delete(String profileId, String namespace, String key) {
            return false;
        }
    }
}
