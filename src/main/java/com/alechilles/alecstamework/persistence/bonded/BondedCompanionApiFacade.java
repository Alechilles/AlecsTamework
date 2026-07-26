package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionData;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Public bonded facade gated only by its own runtime and world context. */
public final class BondedCompanionApiFacade
        implements BondedCompanionApi, AutoCloseable {
    private static final String CLOSED = "bonded-companion-authority-closed";

    private final Supplier<BondedCompanionPersistenceReadiness> readiness;
    private final Supplier<BondedCompanionAvailability> capability;
    private final BondedCompanionStore store;
    private final BondedCompanionChangePublisher changes;
    private final BondedCompanionDiagnosticContributor diagnostics;
    private final BondedCompanionCoreApiOperations operations;
    private final BondedCompanionViewFactory views =
            new BondedCompanionViewFactory();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BondedCompanionApiFacade(
            @Nonnull Supplier<BondedCompanionPersistenceReadiness> readiness,
            @Nonnull Supplier<BondedCompanionAvailability> capability,
            @Nonnull BondedCompanionStore store,
            @Nonnull BondedCompanionChangePublisher changes,
            @Nonnull BondedCompanionDiagnosticContributor diagnostics,
            @Nonnull BondedCompanionCoreApiOperations operations
    ) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.store = Objects.requireNonNull(store, "store");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public BondedCompanionAvailability availability() {
        return closed.get()
                ? BondedCompanionAvailability.unavailable(CLOSED)
                : capability.get();
    }

    @Override
    public CompletableFuture<BondedCompanionResult<
            List<BondedCompanionProfileView>>> list(
            UUID ownerUuid,
            String rosterId
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(rosterId, "rosterId");
        BondedCompanionResult<List<BondedCompanionProfileView>> denied =
                persistenceDenied();
        if (denied != null) {
            return CompletableFuture.completedFuture(denied);
        }
        try {
            Map<String, BondedCompanionRecord.Lease> leases = new LinkedHashMap<>();
            store.findActiveLeases(ownerUuid, rosterId).forEach(
                    lease -> leases.put(lease.profileId(), lease)
            );
            List<BondedCompanionProfileView> profileViews = store.listProfiles(
                    ownerUuid, rosterId
            ).stream().map(profile -> views.view(profile, leases.get(
                    profile.profileId()
            ))).toList();
            return completed(profileViews);
        } catch (RuntimeException failure) {
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE
            );
            return failed(BondedCompanionResultCode.INTERNAL_FAILURE,
                    "bonded-storage-read-failed");
        }
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            provision(BondedCompanionProvisionRequest request) {
        Objects.requireNonNull(request, "request");
        return execute(() -> operations.provision(request));
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            summon(BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return execute(() -> operations.summon(request));
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            store(BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return execute(() -> operations.store(request));
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionReviveQuote>>
            quoteRevive(BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return execute(() -> operations.quoteRevive(request));
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            revive(BondedCompanionReviveRequest request) {
        Objects.requireNonNull(request, "request");
        return execute(() -> operations.revive(request));
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
            getExtensionData(BondedCompanionExtensionDataKey key) {
        Objects.requireNonNull(key, "key");
        return execute(() -> operations.extension(key));
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
            compareAndSetExtensionData(BondedCompanionExtensionDataUpdate update) {
        Objects.requireNonNull(update, "update");
        return execute(() -> operations.updateExtension(update));
    }

    @Override
    public AutoCloseable subscribe(
            Consumer<BondedCompanionChangedEvent> listener
    ) {
        return changes.subscribe(listener);
    }

    private <T> CompletableFuture<BondedCompanionResult<T>> execute(
            Supplier<BondedCompanionResult<T>> operation
    ) {
        BondedCompanionResult<T> denied = persistenceDenied();
        if (denied != null) {
            return CompletableFuture.completedFuture(denied);
        }
        try {
            return CompletableFuture.completedFuture(operation.get());
        } catch (IllegalArgumentException invalid) {
            return failed(BondedCompanionResultCode.VALIDATION_FAILED,
                    "bonded-request-invalid");
        } catch (RuntimeException failure) {
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE
            );
            return failed(BondedCompanionResultCode.INTERNAL_FAILURE,
                    "bonded-operation-failed");
        }
    }

    private <T> BondedCompanionResult<T> persistenceDenied() {
        if (closed.get()) {
            return BondedCompanionResult.unavailable(CLOSED);
        }
        BondedCompanionAvailability state = readiness.get().availability();
        return state.available() ? null
                : BondedCompanionResult.unavailable(state.reason());
    }

    private <T> CompletableFuture<BondedCompanionResult<T>> completed(T value) {
        return CompletableFuture.completedFuture(new BondedCompanionResult<>(
                BondedCompanionResultCode.SUCCESS, value, null
        ));
    }

    private <T> CompletableFuture<BondedCompanionResult<T>> failed(
            BondedCompanionResultCode code,
            String reason
    ) {
        return CompletableFuture.completedFuture(
                new BondedCompanionResult<>(code, null, reason)
        );
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
