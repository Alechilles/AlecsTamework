package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataEntryView;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexProfileDataApiTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String NS = "Alechilles:HyDragon";

    private final CompanionIndex index = new CompanionIndex(() -> 1_000L, (before, after) -> { });
    /** One future per flush request, completed by the test. */
    private final List<CompletableFuture<Void>> flushes = new ArrayList<>();
    private final List<UUID> flushedOwners = new ArrayList<>();
    private final IndexProfileDataApi data = new IndexProfileDataApi(index, owner -> {
        CompletableFuture<Void> flush = new CompletableFuture<>();
        flushes.add(flush);
        flushedOwners.add(owner);
        return flush;
    });
    private final String profile = insert(CompanionLocation.live("default", 0, 0, 0));

    private String insert(CompanionLocation location) {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), "Sheep", location).ownerUuid(OWNER).build();
        index.insert(record);
        return record.profileId().toString();
    }

    private static ProfileDataCompareAndSetRequest request(String profileId, long expected, String json) {
        return new ProfileDataCompareAndSetRequest(profileId, NS, "state", expected, "op-" + expected, json);
    }

    private ProfileDataCompareAndSetResult cas(long expected, String json) {
        CompletableFuture<ProfileDataCompareAndSetResult> result =
                data.compareAndSet(request(profile, expected, json)).toCompletableFuture();
        if (!result.isDone()) {
            flushes.get(flushes.size() - 1).complete(null);
        }
        return result.join();
    }

    @Test
    void aValueIsStoredPerNamespaceAndKeyAndItsRevisionGoesUpWithEachWrite() {
        assertTrue(data.put(profile, NS, "state", " {\"level\": 1} "));
        assertTrue(data.put(profile, NS, "other", "[1]"));
        assertTrue(data.put(profile, "runeteria:husbandry", "state", "true"));

        assertEquals(Optional.of("{\"level\":1}"), data.get(profile, NS, "state"));
        assertEquals(Map.of("state", "{\"level\":1}", "other", "[1]"), data.list(profile, NS));
        assertEquals(1L, data.getVersioned(profile, NS, "state").orElseThrow().revision());

        assertTrue(data.put(profile, NS, "state", "{\"level\":2}"));
        ProfileDataEntryView second = data.getVersioned(profile, NS, "state").orElseThrow();
        assertEquals(2L, second.revision());
        assertEquals("{\"level\":2}", second.jsonPayload());

        assertTrue(data.delete(profile, NS, "state"));
        assertTrue(data.get(profile, NS, "state").isEmpty());
        assertTrue(data.delete(profile, NS, "state"), "deleting a missing value is not a failure");
        assertEquals(Optional.of("true"), data.get(profile, "runeteria:husbandry", "state"));
    }

    @Test
    void writesAreRefusedForMissingReleasedOrMalformedTargets() {
        String released = insert(CompanionLocation.released("released"));

        assertFalse(data.put(UUID.randomUUID().toString(), NS, "state", "{}"));
        assertFalse(data.put("not-a-uuid", NS, "state", "{}"));
        assertFalse(data.put(released, NS, "state", "{}"));
        assertFalse(data.put(profile, NS, "state", "not json"));
        assertFalse(data.put(profile, " ", "state", "{}"));
        assertFalse(data.put(profile, "tamework", "bonded-capture", "{}"), "Tamework entries are not public data");
        assertFalse(data.delete("not-a-uuid", NS, "state"));
        assertTrue(data.list(profile, NS).isEmpty());
        assertTrue(data.get(released, NS, "state").isEmpty());
        assertTrue(data.getVersioned("not-a-uuid", NS, "state").isEmpty());
    }

    @Test
    void aCompareAndSetCommitsOnlyAfterTheOwnerFileIsWritten() {
        CompletableFuture<ProfileDataCompareAndSetResult> pending = data.compareAndSet(
                request(profile, ProfileDataCompareAndSetRequest.MISSING_REVISION, "{\"a\":1}")).toCompletableFuture();

        assertFalse(pending.isDone(), "the owner file is not written yet");
        assertEquals(List.of(OWNER), flushedOwners);
        flushes.get(0).complete(null);
        ProfileDataCompareAndSetResult result = pending.join();

        assertTrue(result.committed());
        assertEquals(1L, result.committedEntry().orElseThrow().revision());
        assertEquals(1L, result.durableOperation().orElseThrow().resultingRevision());
        assertEquals(Optional.of("{\"a\":1}"), data.get(profile, NS, "state"));
        assertTrue(cas(1L, "{\"a\":2}").committed());
        assertEquals(2L, data.getVersioned(profile, NS, "state").orElseThrow().revision());
    }

    @Test
    void aStaleRevisionOrAMissingProfileIsDeniedAndChangesNothing() {
        assertTrue(cas(0L, "{\"a\":1}").committed());

        ProfileDataCompareAndSetResult stale = cas(0L, "{\"a\":9}");
        ProfileDataCompareAndSetResult ahead = cas(5L, "{\"a\":9}");
        ProfileDataCompareAndSetResult missing = data.compareAndSet(
                request(UUID.randomUUID().toString(), 0L, "{}")).toCompletableFuture().join();

        assertEquals(ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED, stale.status());
        assertEquals(IndexProfileDataApi.REVISION_MISMATCH, stale.reason());
        assertEquals(ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED, ahead.status());
        assertEquals(ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED, missing.status());
        assertEquals(IndexProfileDataApi.PROFILE_NOT_FOUND, missing.reason());
        assertEquals(Optional.of("{\"a\":1}"), data.get(profile, NS, "state"));
        assertEquals(1, flushes.size(), "a denied request writes nothing");
    }

    @Test
    void repeatingACommittedRequestReportsTheSameOperation() {
        ProfileDataCompareAndSetResult first = cas(0L, "{\"a\":1}");
        ProfileDataCompareAndSetResult again = cas(0L, "{\"a\":1}");

        assertTrue(again.committed());
        assertEquals(first.durableOperation().orElseThrow().operationId(),
                again.durableOperation().orElseThrow().operationId());
        assertEquals(1L, data.getVersioned(profile, NS, "state").orElseThrow().revision());
        assertNotEquals(first.durableOperation().orElseThrow().operationId(),
                cas(1L, "{\"a\":2}").durableOperation().orElseThrow().operationId());
    }

    @Test
    void aRepeatWhileTheFirstWriteIsPendingIsNotCommittedWhenThatWriteFails() {
        CompletableFuture<ProfileDataCompareAndSetResult> first =
                data.compareAndSet(request(profile, 0L, "{\"a\":1}")).toCompletableFuture();
        CompletableFuture<ProfileDataCompareAndSetResult> repeat =
                data.compareAndSet(request(profile, 0L, "{\"a\":1}")).toCompletableFuture();

        assertFalse(repeat.isDone(), "the owner file is not written yet");
        flushes.forEach(flush -> flush.completeExceptionally(new IOException("disk full")));

        assertEquals(ProfileDataCompareAndSetResult.Status.UNAVAILABLE, first.join().status());
        assertEquals(ProfileDataCompareAndSetResult.Status.UNAVAILABLE, repeat.join().status());
        assertEquals(IndexProfileDataApi.FLUSH_FAILED, repeat.join().reason());
        assertTrue(data.get(profile, NS, "state").isEmpty());
    }

    @Test
    void aNamespaceWithASlashIsRefusedButAKeyMayHaveOne() {
        assertFalse(data.put(profile, "a/b", "c", "1"));
        assertFalse(data.delete(profile, "a/b", "c"));
        ProfileDataCompareAndSetResult refused = data.compareAndSet(new ProfileDataCompareAndSetRequest(
                profile, "a/b", "c", 0L, "op", "1")).toCompletableFuture().join();
        assertEquals(ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED, refused.status());
        assertEquals(IndexProfileDataApi.NAMESPACE_REFUSED, refused.reason());

        assertTrue(data.put(profile, "a", "b/c", "2"));
        assertEquals(Optional.of("2"), data.get(profile, "a", "b/c"));
        assertTrue(data.list(profile, "a/b").isEmpty());
        assertTrue(data.getVersioned(profile, "a/b", "c").isEmpty());
        assertEquals(Map.of("b/c", "2"), data.list(profile, "a"));
    }

    @Test
    void aFailedWriteUndoesTheChangeAndReportsUnavailable() {
        assertTrue(cas(0L, "{\"a\":1}").committed());

        CompletableFuture<ProfileDataCompareAndSetResult> pending =
                data.compareAndSet(request(profile, 1L, "{\"a\":2}")).toCompletableFuture();
        flushes.get(1).completeExceptionally(new IOException("disk full"));
        ProfileDataCompareAndSetResult result = pending.join();

        assertEquals(ProfileDataCompareAndSetResult.Status.UNAVAILABLE, result.status());
        assertEquals(IndexProfileDataApi.FLUSH_FAILED, result.reason());
        ProfileDataEntryView kept = data.getVersioned(profile, NS, "state").orElseThrow();
        assertEquals(1L, kept.revision());
        assertEquals("{\"a\":1}", kept.jsonPayload());
    }
}
