package com.alechilles.alecstamework.persistence.legacy;

import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetRequest;
import com.alechilles.alecstamework.api.ProfileDataCompareAndSetResult;
import com.alechilles.alecstamework.api.ProfileDataEntryView;
import com.alechilles.alecstamework.api.ProfileDataOperationStatus;
import com.alechilles.alecstamework.api.ProfileDataOperationView;
import com.alechilles.alecstamework.persistence.sqlite.ApiProfileDataRepository;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Temporary unsupported-development adapter for legacy extension storage. */
public final class LegacyProfileDataApi implements ProfileDataApi {
    private static final String RESERVED_NAMESPACE = "Alechilles:Tamework";

    private final ApiProfileDataRepository data;

    public LegacyProfileDataApi(@Nonnull ApiProfileDataRepository data) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "Legacy profile-data repository is required"
            );
        }
        this.data = data;
    }

    @Override
    public Optional<String> get(
            String profileId,
            String namespace,
            String key
    ) {
        return valid(profileId, namespace, key)
                ? Optional.ofNullable(data.get(
                profileId.trim(), namespace.trim(), key.trim()
        ))
                : Optional.empty();
    }

    @Override
    public Map<String, String> list(String profileId, String namespace) {
        return profileId == null || profileId.isBlank()
                || !validNamespace(namespace)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data.list(
                profileId.trim(), namespace.trim()
        )));
    }

    @Override
    public boolean put(
            String profileId,
            String namespace,
            String key,
            String jsonPayload
    ) {
        if (!valid(profileId, namespace, key)
                || jsonPayload == null || jsonPayload.isBlank()) {
            return false;
        }
        try {
            String normalizedJson = JsonParser.parseString(
                    jsonPayload
            ).toString();
            return data.putAsync(
                    profileId.trim(),
                    namespace.trim(),
                    key.trim(),
                    normalizedJson
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean delete(
            String profileId,
            String namespace,
            String key
    ) {
        return valid(profileId, namespace, key)
                && data.deleteAsync(
                profileId.trim(), namespace.trim(), key.trim()
        );
    }

    @Override
    public Optional<ProfileDataEntryView> getVersioned(
            String profileId,
            String namespace,
            String key
    ) {
        return valid(profileId, namespace, key)
                ? Optional.ofNullable(data.getVersioned(
                profileId.trim(), namespace.trim(), key.trim()
        )).map(this::entry)
                : Optional.empty();
    }

    @Override
    public CompletionStage<ProfileDataCompareAndSetResult> compareAndSet(
            ProfileDataCompareAndSetRequest request
    ) {
        Objects.requireNonNull(request, "request");
        return data.compareAndSetAsync(
                request.profileId(),
                request.namespace(),
                request.key(),
                request.expectedRevision(),
                request.idempotencyKey(),
                request.jsonPayload()
        ).thenApply(this::transaction);
    }

    @Override
    public CompletionStage<Optional<ProfileDataOperationView>> findOperation(
            String namespace,
            String idempotencyKey
    ) {
        if (!validNamespace(namespace)
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(
                Optional.ofNullable(data.findOperation(
                        namespace.trim(), idempotencyKey.trim()
                )).map(this::operation)
        );
    }

    private ProfileDataCompareAndSetResult transaction(
            ApiProfileDataRepository.TransactionResult result
    ) {
        if (result == null || result.outcome()
                == ApiProfileDataRepository.TransactionOutcome.UNAVAILABLE) {
            return new ProfileDataCompareAndSetResult(
                    ProfileDataCompareAndSetResult.Status.UNAVAILABLE,
                    result == null
                            ? "profile-data-transaction-authority-unavailable"
                            : result.reason(),
                    null,
                    null
            );
        }
        ProfileDataOperationView durable = operation(Objects.requireNonNull(
                result.operation(), "durable operation"
        ));
        return switch (result.outcome()) {
            case COMMITTED -> new ProfileDataCompareAndSetResult(
                    ProfileDataCompareAndSetResult.Status.COMMITTED,
                    result.reason(),
                    durable,
                    entry(Objects.requireNonNull(
                            result.value(), "committed value"
                    ))
            );
            case TERMINAL_DENIED -> new ProfileDataCompareAndSetResult(
                    ProfileDataCompareAndSetResult.Status.TERMINAL_DENIED,
                    result.reason(),
                    durable,
                    null
            );
            case QUARANTINED -> new ProfileDataCompareAndSetResult(
                    ProfileDataCompareAndSetResult.Status.QUARANTINED,
                    result.reason(),
                    durable,
                    null
            );
            case UNAVAILABLE -> throw new IllegalStateException(
                    "Unavailable transaction handled above"
            );
        };
    }

    private ProfileDataEntryView entry(
            ApiProfileDataRepository.VersionedValue value
    ) {
        return new ProfileDataEntryView(
                value.profileId(),
                value.namespace(),
                value.key(),
                value.revision(),
                value.jsonPayload(),
                value.updatedAtMs()
        );
    }

    private ProfileDataOperationView operation(
            ApiProfileDataRepository.TransactionOperation value
    ) {
        return new ProfileDataOperationView(
                value.operationId(),
                value.namespace(),
                value.idempotencyKey(),
                value.profileId(),
                value.key(),
                value.expectedRevision(),
                value.resultingRevision(),
                value.payloadFingerprint(),
                ProfileDataOperationStatus.valueOf(value.status().name()),
                value.reason(),
                value.updatedAtMs()
        );
    }

    private boolean valid(
            String profileId,
            String namespace,
            String key
    ) {
        return profileId != null && !profileId.isBlank()
                && key != null && !key.isBlank()
                && validNamespace(namespace);
    }

    private boolean validNamespace(String value) {
        return value != null && !value.isBlank()
                && !RESERVED_NAMESPACE.equalsIgnoreCase(value.trim());
    }
}
