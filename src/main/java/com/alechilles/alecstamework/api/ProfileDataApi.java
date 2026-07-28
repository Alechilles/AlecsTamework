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

    /** Queries durable nonterminal or terminal state after a process/server restart. */
    default CompletionStage<Optional<ProfileDataOperationView>> findOperation(
            String namespace,
            String idempotencyKey
    ) {
        ProfileDataValidation.requireText(namespace, "namespace", 128);
        ProfileDataValidation.requireText(idempotencyKey, "idempotencyKey", 256);
        return CompletableFuture.completedFuture(Optional.empty());
    }
}

