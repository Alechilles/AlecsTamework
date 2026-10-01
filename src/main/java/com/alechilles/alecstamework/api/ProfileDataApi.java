package com.alechilles.alecstamework.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface ProfileDataApi {
    Optional<String> get(String profileId, String namespace, String key);

    Map<String, String> list(String profileId, String namespace);

    boolean put(String profileId, String namespace, String key, String jsonPayload);

    boolean delete(String profileId, String namespace, String key);

    /**
     * Reads the durable revision with the value. The compatibility default is deliberately empty;
     * consumers must also require {@link TameworkApiCapability#PROFILE_DATA_TRANSACTIONS}.
     */
    default Optional<ProfileDataEntryView> getVersioned(String profileId, String namespace, String key) {
        ProfileDataValidation.requireText(profileId, "profileId", 256);
        ProfileDataValidation.requireText(namespace, "namespace", 128);
        ProfileDataValidation.requireText(key, "key", 256);
        return Optional.empty();
    }

    /**
     * Atomically compare-and-sets one namespaced value and records the outcome under its stable
     * namespace/idempotency-key origin. Queue acceptance is never returned as success.
     *
     * <p>The stage may complete on a thread that is not a world thread (the store's writer
     * thread). A continuation must not block and must not read or change entities, components or
     * worlds; hop to the owning world with {@code world.execute(...)} first.</p>
     */
    default CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(
            ProfileDataCompareAndSetRequest request
    ) {
        if (request == null) throw new NullPointerException("request");
        return CompletableFuture.completedFuture(ProfileDataCompareAndSetResult.unavailable());
    }

    /** Convenience overload matching the common integration call shape. */
    default CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(
            String profileId,
            String namespace,
            String key,
            long expectedRevision,
            String idempotencyKey,
            String jsonPayload
    ) {
        return compareAndSet(new ProfileDataCompareAndSetRequest(
                profileId, namespace, key, expectedRevision, idempotencyKey, jsonPayload));
    }
}
