package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataEntryView;
import com.alechilles.alecstamework.api.ProfileDataOperationStatus;
import com.alechilles.alecstamework.api.ProfileDataOperationView;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.ExtensionEntries;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Namespaced profile data stored in the companion record's extensions under
 * {@code namespace + "/" + key}. Each value has its own revision, starting at 1.
 *
 * <p>Reads are synchronous and lock-free. {@code put} and {@code delete} change the record and
 * return without waiting; the writer saves the owner file on its next flush. {@code compareAndSet}
 * completes only after the owner file is written, and undoes its change when that write fails.
 * No call blocks the caller on file I/O.</p>
 *
 * <p>There is no stored operation log. The operation in a result is built from the profile, key
 * and revision, so repeating a request while its value is still current reports the same
 * committed operation once the owner file is written; after a later change it reports a revision
 * mismatch. A released companion, an unparsable profile id, the reserved Tamework namespaces and
 * a namespace containing "/" read as "no profile data" and refuse writes.</p>
 */
public final class IndexProfileDataApi implements ProfileDataApi {
    static final String COMMITTED = "profile-data-committed";
    static final String REVISION_MISMATCH = "profile-data-revision-mismatch";
    static final String PROFILE_NOT_FOUND = "profile-data-profile-not-found";
    static final String FLUSH_FAILED = "profile-data-flush-failed";
    static final String NAMESPACE_REFUSED = "profile-data-namespace-refused";

    private final CompanionIndex index;
    private final Function<UUID, CompletableFuture<Void>> flush;

    /** @param flush writes an owner's file to disk; {@code CompanionWriter::flushNow} in production */
    public IndexProfileDataApi(@Nonnull CompanionIndex index,
                               @Nonnull Function<UUID, CompletableFuture<Void>> flush) {
        this.index = Objects.requireNonNull(index, "index");
        this.flush = Objects.requireNonNull(flush, "flush");
    }

    @Override
    public Optional<String> get(String profileId, String namespace, String key) {
        return getVersioned(profileId, namespace, key).map(ProfileDataEntryView::jsonPayload);
    }

    @Override
    public Map<String, String> list(String profileId, String namespace) {
        CompanionRecord record = record(profileId);
        if (record == null || !usable(namespace)) {
            return Map.of();
        }
        String prefix = namespace.trim() + "/";
        Map<String, String> values = new LinkedHashMap<>();
        record.extensions().forEach((extensionKey, entry) -> {
            if (extensionKey.startsWith(prefix)) {
                values.put(extensionKey.substring(prefix.length()), entry.json());
            }
        });
        return Map.copyOf(values);
    }

    @Override
    public Optional<ProfileDataEntryView> getVersioned(String profileId, String namespace, String key) {
        CompanionRecord record = record(profileId);
        if (record == null || !usable(namespace) || key == null || key.isBlank()) {
            return Optional.empty();
        }
        ExtensionEntry entry = record.extensions().get(extensionKey(namespace, key));
        return entry == null ? Optional.empty() : Optional.of(new ProfileDataEntryView(
                record.profileId().toString(), namespace, key, revisionOf(entry), entry.json(), record.updatedAtMs()));
    }

    @Override
    public boolean put(String profileId, String namespace, String key, String jsonPayload) {
        return write(profileId, namespace, key, jsonPayload, false);
    }

    /** True when the profile exists and no longer has the value, including when it never had it. */
    @Override
    public boolean delete(String profileId, String namespace, String key) {
        return write(profileId, namespace, key, "{}", true);
    }

    private boolean write(String profileId, String namespace, String key, String jsonPayload, boolean delete) {
        ProfileDataCompareAndSetRequest valid;
        try {
            // Borrowed for its validation and normalization of the scope and the JSON.
            valid = new ProfileDataCompareAndSetRequest(profileId, namespace, key, 0L, "write", jsonPayload);
        } catch (RuntimeException invalid) {
            return false;
        }
        if (!usable(valid.namespace())) {
            return false;
        }
        String extensionKey = extensionKey(valid.namespace(), valid.key());
        return index.atomically(() -> {
            CompanionRecord record = record(valid.profileId());
            if (record == null) {
                return false;
            }
            ExtensionEntry current = record.extensions().get(extensionKey);
            if (delete && current == null) {
                return true;
            }
            ExtensionEntry next = delete ? null : new ExtensionEntry(revisionOf(current) + 1L, valid.jsonPayload());
            return index.update(record.profileId(), record.revision(), b -> b.extension(extensionKey, next)).applied();
        });
    }

    @Override
    public CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(ProfileDataCompareAndSetRequest request) {
        Objects.requireNonNull(request, "request");
        if (!usable(request.namespace())) {
            return CompletableFuture.completedFuture(denied(request, NAMESPACE_REFUSED, 0L));
        }
        CompanionRecord found = record(request.profileId());
        if (found == null) {
            return CompletableFuture.completedFuture(denied(request, PROFILE_NOT_FOUND, 0L));
        }
        return ExtensionEntries.compareAndSet(index, flush, found.profileId(),
                extensionKey(request.namespace(), request.key()), request.expectedRevision(), request.jsonPayload(),
                current -> current.location().kind() != LocationKind.RELEASED)
                .thenApply(outcome -> switch (outcome.status()) {
                    case WRITTEN -> committed(request, outcome.record());
                    case NOT_FOUND -> denied(request, PROFILE_NOT_FOUND, 0L);
                    case REVISION_MISMATCH -> denied(request, REVISION_MISMATCH, outcome.record().updatedAtMs());
                    case FLUSH_FAILED -> new ProfileDataCompareAndSetResult(
                            ProfileDataCompareAndSetResult.Status.UNAVAILABLE, FLUSH_FAILED, null, null);
                });
    }

    private static ProfileDataCompareAndSetResult committed(ProfileDataCompareAndSetRequest request,
                                                            CompanionRecord record) {
        long revision = request.expectedRevision() + 1L;
        return new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.COMMITTED,
                COMMITTED,
                operation(request, revision, ProfileDataOperationStatus.COMMITTED, COMMITTED, record.updatedAtMs()),
                new ProfileDataEntryView(request.profileId(), request.namespace(), request.key(), revision,
                        request.jsonPayload(), record.updatedAtMs()));
    }

    private static ProfileDataCompareAndSetResult denied(ProfileDataCompareAndSetRequest request, String reason,
                                                         long updatedAtMs) {
        return new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED,
                reason,
                operation(request, ProfileDataOperationView.UNKNOWN_REVISION,
                        ProfileDataOperationStatus.TERMINAL_DENIED, reason, updatedAtMs),
                null);
    }

    /** The operation id is the same for every request that targets the same value revision. */
    private static ProfileDataOperationView operation(ProfileDataCompareAndSetRequest request, long resultingRevision,
                                                      ProfileDataOperationStatus status, String reason,
                                                      long updatedAtMs) {
        String target = String.join("\n", request.profileId(), request.namespace(), request.key(),
                Long.toString(request.expectedRevision() + 1L));
        return new ProfileDataOperationView(
                UUID.nameUUIDFromBytes(target.getBytes(StandardCharsets.UTF_8)),
                request.namespace(),
                request.idempotencyKey(),
                request.profileId(),
                request.key(),
                request.expectedRevision(),
                resultingRevision,
                UUID.nameUUIDFromBytes(request.jsonPayload().getBytes(StandardCharsets.UTF_8)).toString(),
                status,
                reason,
                updatedAtMs);
    }

    /** A stored value's revision is at least 1; 0 means "no value" in a compare-and-set. */
    private static long revisionOf(@Nullable ExtensionEntry entry) {
        return ExtensionEntries.storedRevision(entry);
    }

    /** The one key layout record extensions use; bonded extension data shares it. */
    private static String extensionKey(String namespace, String key) {
        return ExtensionEntries.key(namespace, key);
    }

    /**
     * Tamework's own extension entries (for example bonded capture evidence) are not public data,
     * and a namespace with "/" is refused; {@link ExtensionEntries#publicNamespace} owns the rule.
     * Keys may contain "/".
     */
    private static boolean usable(@Nullable String namespace) {
        return ExtensionEntries.publicNamespace(namespace);
    }

    @Nullable
    private CompanionRecord record(@Nullable String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        CompanionRecord record;
        try {
            record = index.get(UUID.fromString(profileId.trim()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return record != null && record.location().kind() != LocationKind.RELEASED ? record : null;
    }
}
