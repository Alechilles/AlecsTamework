package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionExtensionData;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveQuote;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
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
    private final BondedCompanionStore store;
    private final BondedCompanionChangePublisher changes;
    private final BondedCompanionDiagnosticContributor diagnostics;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BondedCompanionApiFacade(
            @Nonnull Supplier<BondedCompanionPersistenceReadiness> readiness,
            @Nonnull BondedCompanionStore store,
            @Nonnull BondedCompanionChangePublisher changes,
            @Nonnull BondedCompanionDiagnosticContributor diagnostics
    ) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.store = Objects.requireNonNull(store, "store");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    @Override
    public BondedCompanionAvailability availability() {
        return closed.get()
                ? BondedCompanionAvailability.unavailable(CLOSED)
                : readiness.get().availability();
    }

    @Override
    public CompletableFuture<BondedCompanionResult<
            List<BondedCompanionProfileView>>> list(
            UUID ownerUuid,
            String rosterId
    ) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(rosterId, "rosterId");
        BondedCompanionResult<List<BondedCompanionProfileView>> denied = denied();
        if (denied != null) {
            return CompletableFuture.completedFuture(denied);
        }
        try {
            Map<String, BondedCompanionRecord.Lease> leases = new LinkedHashMap<>();
            store.findActiveLeases(ownerUuid, rosterId).forEach(
                    lease -> leases.put(lease.profileId(), lease)
            );
            List<BondedCompanionProfileView> views = store.listProfiles(
                    ownerUuid, rosterId
            ).stream().map(profile -> view(profile, leases.get(
                    profile.profileId()
            ))).toList();
            return completed(views);
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
        return notYetComposed("bonded-provision-snapshot-context-required");
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            summon(BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return worldContext(request.worldKey());
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            store(BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return worldContext(request.worldKey());
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionReviveQuote>>
            quoteRevive(BondedCompanionActionRequest request) {
        Objects.requireNonNull(request, "request");
        return worldContext(request.worldKey());
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            revive(BondedCompanionReviveRequest request) {
        Objects.requireNonNull(request, "request");
        return worldContext(request.action().worldKey());
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
            getExtensionData(BondedCompanionExtensionDataKey key) {
        Objects.requireNonNull(key, "key");
        return notYetComposed("bonded-extension-roster-context-required");
    }

    @Override
    public CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
            compareAndSetExtensionData(BondedCompanionExtensionDataUpdate update) {
        Objects.requireNonNull(update, "update");
        return notYetComposed("bonded-extension-roster-context-required");
    }

    @Override
    public AutoCloseable subscribe(
            Consumer<BondedCompanionChangedEvent> listener
    ) {
        return changes.subscribe(listener);
    }

    private BondedCompanionProfileView view(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Lease lease
    ) {
        Map<String, String> presentation = profile.policy().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("presentation:"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().substring("presentation:".length()),
                        Map.Entry::getValue
                ));
        BondedCompanionLeaseView active = lease == null ? null
                : new BondedCompanionLeaseView(
                        lease.leaseToken(), lease.liveNpcUuid(), lease.worldKey(),
                        lease.startedAtMs(), lease.expiresAtMs()
                );
        return new BondedCompanionProfileView(
                profile.profileId(), profile.ownerUuid(), profile.rosterId(),
                profile.familyId(), profile.roleId(), profile.displayName(),
                profile.species(), profile.gender(), profile.revision(),
                profile.state(), profile.state() == BondedCompanionState.STORED,
                profile.state() == BondedCompanionState.ACTIVE,
                profile.state() == BondedCompanionState.DEAD,
                presentation, active, profile.reviveCooldownUntilMs(), null
        );
    }

    private <T> CompletableFuture<BondedCompanionResult<T>> worldContext(
            String worldKey
    ) {
        BondedCompanionResult<T> denied = denied();
        if (denied != null) {
            return CompletableFuture.completedFuture(denied);
        }
        diagnostics.recordFailure(
                BondedCompanionDiagnosticSnapshot.FailureCategory.WORLD_CONTEXT
        );
        return failed(
                BondedCompanionResultCode.WORLD_UNAVAILABLE,
                worldKey == null ? "bonded-world-context-required"
                        : "bonded-world-context-unavailable"
        );
    }

    private <T> CompletableFuture<BondedCompanionResult<T>> notYetComposed(
            String reason
    ) {
        BondedCompanionResult<T> denied = denied();
        return denied != null
                ? CompletableFuture.completedFuture(denied)
                : failed(BondedCompanionResultCode.VALIDATION_FAILED, reason);
    }

    private <T> BondedCompanionResult<T> denied() {
        BondedCompanionAvailability state = availability();
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
