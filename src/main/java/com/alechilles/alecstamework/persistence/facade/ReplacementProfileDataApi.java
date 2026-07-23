package com.alechilles.alecstamework.persistence.facade;

import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataEntryView;
import com.alechilles.alecstamework.api.ProfileDataOperationView;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionValue;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.runtime.PublicOperationEvidence;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceOperations;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Released profile-data API backed only by replacement extension and operation authorities.
 */
public final class ReplacementProfileDataApi implements ProfileDataApi {
    private static final String RESERVED_NAMESPACE = "Alechilles:Tamework";

    private final PublicPersistenceQueries queries;
    private final PublicPersistenceOperations operations;
    private final LongSupplier clock;

    public ReplacementProfileDataApi(
            @Nonnull PublicPersistenceQueries queries,
            @Nonnull PublicPersistenceOperations operations,
            @Nonnull LongSupplier clock
    ) {
        if (queries == null || operations == null || clock == null) {
            throw new IllegalArgumentException(
                    "Complete replacement profile-data API dependencies are required"
            );
        }
        this.queries = queries;
        this.operations = operations;
        this.clock = clock;
    }

    @Override
    public Optional<String> get(String profileId, String namespace, String key) {
        ProfileExtensionKey extensionKey = key(profileId, namespace, key);
        if (extensionKey == null) {
            return Optional.empty();
        }
        return queries.projectedExtension(extensionKey)
                .map(ProfileExtensionProjectionValue::jsonPayload);
    }

    @Override
    public Map<String, String> list(String profileId, String namespace) {
        ProfileId parsed = profile(profileId);
        String normalizedNamespace = namespace(namespace);
        if (parsed == null || normalizedNamespace == null) {
            return Map.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        queries.projectedExtensions(parsed, normalizedNamespace)
                .forEach((key, value) ->
                        values.put(key, value.jsonPayload()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public boolean put(
            String profileId,
            String namespace,
            String key,
            String jsonPayload
    ) {
        return submitCompatibility(
                profileId,
                namespace,
                key,
                ProfileExtensionMutationAction.PUT,
                jsonPayload
        );
    }

    @Override
    public boolean delete(String profileId, String namespace, String key) {
        return submitCompatibility(
                profileId,
                namespace,
                key,
                ProfileExtensionMutationAction.DELETE,
                null
        );
    }

    @Override
    public Optional<ProfileDataEntryView> getVersioned(
            String profileId,
            String namespace,
            String key
    ) {
        ProfileExtensionKey extensionKey = key(profileId, namespace, key);
        if (extensionKey == null) {
            return Optional.empty();
        }
        return queries.projectedExtension(extensionKey).map(this::entry);
    }

    @Override
    public CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(
            ProfileDataCompareAndSetRequest request
    ) {
        Objects.requireNonNull(request, "request");
        ProfileExtensionKey extensionKey =
                key(request.profileId(), request.namespace(), request.key());
        if (extensionKey == null) {
            return completedUnavailable("profile-data-scope-invalid");
        }
        IdempotencyKey scoped =
                scopedIdempotency(request.namespace(), request.idempotencyKey());
        ProfileExtensionMutation requestedMutation = new ProfileExtensionMutation(
                extensionKey,
                ProfileExtensionMutationAction.PUT,
                request.expectedRevision(),
                request.jsonPayload(),
                clock.getAsLong()
        );
        return queries.findOperation(
                ProfileExtensionMutationDefinition.KIND,
                scoped
        ).thenCompose(existing -> {
            if (existing instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found) {
                ProfileExtensionMutation durable =
                        ProfileExtensionMutationDefinition.INSTANCE.decode(
                                found.value().operation().payloadJson()
                        );
                if (!sameRequest(durable, requestedMutation)) {
                    return completedUnavailable(
                            "profile-data-idempotency-conflict"
                    );
                }
                return operations.mutateExtension(
                        found.value().operation().operationId(),
                        scoped,
                        durable
                ).completion().thenApply(result ->
                        ReplacementProfileDataMapper
                                .compareAndSetResult(
                                        result,
                                        request.idempotencyKey()
                                ));
            }
            if (existing instanceof PersistenceReadResult.Failed<?>) {
                return completedUnavailable(
                        "profile-data-operation-read-failed"
                );
            }
            return operations.mutateExtension(
                    OperationId.create(),
                    scoped,
                    requestedMutation
            ).completion().thenApply(result ->
                    ReplacementProfileDataMapper.compareAndSetResult(
                            result,
                            request.idempotencyKey()
                    ));
        });
    }

    @Override
    public CompletionStage<Optional<ProfileDataOperationView>> findOperation(
            String namespace,
            String idempotencyKey
    ) {
        String normalizedNamespace = namespace(namespace);
        if (normalizedNamespace == null
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    Optional.empty()
            );
        }
        String normalizedKey = idempotencyKey.trim();
        return queries.findOperation(
                ProfileExtensionMutationDefinition.KIND,
                scopedIdempotency(normalizedNamespace, normalizedKey)
        ).thenApply(result -> {
            if (!(result instanceof PersistenceReadResult.Found<
                    PublicOperationEvidence> found)) {
                return Optional.empty();
            }
            return Optional.of(ReplacementProfileDataMapper.operationView(
                    found.value(),
                    normalizedKey
            ));
        });
    }

    private boolean submitCompatibility(
            String profileId,
            String namespace,
            String dataKey,
            ProfileExtensionMutationAction action,
            String jsonPayload
    ) {
        ProfileExtensionKey extensionKey = key(profileId, namespace, dataKey);
        if (extensionKey == null) {
            return false;
        }
        try {
            ProfileExtensionMutation mutation = new ProfileExtensionMutation(
                    extensionKey,
                    action,
                    null,
                    jsonPayload,
                    clock.getAsLong()
            );
            String nonce = OperationId.create().toString();
            return operations.mutateExtension(
                    OperationId.create(),
                    new IdempotencyKey("compat:" + nonce),
                    mutation
            ).accepted();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private ProfileDataEntryView entry(
            ProfileExtensionProjectionValue value
    ) {
        return new ProfileDataEntryView(
                value.key().profileId().toString(),
                value.key().namespace(),
                value.key().dataKey(),
                value.revision(),
                value.jsonPayload(),
                value.updatedAtMs()
        );
    }

    private ProfileExtensionKey key(
            String profileId,
            String namespace,
            String dataKey
    ) {
        ProfileId parsed = profile(profileId);
        String normalizedNamespace = namespace(namespace);
        if (parsed == null || normalizedNamespace == null
                || dataKey == null || dataKey.isBlank()) {
            return null;
        }
        try {
            return new ProfileExtensionKey(
                    parsed,
                    normalizedNamespace,
                    dataKey.trim()
            );
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private ProfileId profile(String value) {
        try {
            return value == null ? null : ProfileId.parse(value);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private String namespace(String value) {
        if (value == null || value.isBlank()
                || RESERVED_NAMESPACE.equalsIgnoreCase(value.trim())) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= 128 ? normalized : null;
    }

    private IdempotencyKey scopedIdempotency(String namespace, String key) {
        return new IdempotencyKey(
                "api:" + Sha256Hash.ofUtf8(namespace.trim() + "\u0000" + key.trim())
        );
    }

    private boolean sameRequest(
            ProfileExtensionMutation durable,
            ProfileExtensionMutation requested
    ) {
        return durable.key().equals(requested.key())
                && durable.action() == requested.action()
                && Objects.equals(
                durable.expectedRevision(),
                requested.expectedRevision()
        )
                && Objects.equals(
                durable.jsonPayload(),
                requested.jsonPayload()
        );
    }

    private CompletionStage<ProfileDataCompareAndSetResult> completedUnavailable(
            String reason
    ) {
        return java.util.concurrent.CompletableFuture.completedFuture(
                unavailable(reason)
        );
    }

    private ProfileDataCompareAndSetResult unavailable(String reason) {
        return new ProfileDataCompareAndSetResult(
                ProfileDataCompareAndSetResult.Status.UNAVAILABLE,
                reason,
                null,
                null
        );
    }

}
