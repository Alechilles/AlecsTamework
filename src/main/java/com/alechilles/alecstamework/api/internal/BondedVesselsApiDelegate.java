package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.BondedVesselOperationResult;
import com.alechilles.alecstamework.api.BondedVesselOperationView;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselReadinessView;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.api.BondedVesselTransitionToken;
import com.alechilles.alecstamework.api.BondedVesselView;
import com.alechilles.alecstamework.api.BondedVesselsApi;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.vessels.BondedVesselCoordinator;
import com.alechilles.alecstamework.vessels.BondedVesselEvidenceAuthority;
import com.alechilles.alecstamework.vessels.BondedVesselEventSink;
import com.alechilles.alecstamework.vessels.BondedVesselMutationAuthority;
import com.alechilles.alecstamework.vessels.BondedVesselTransitionPlanner;
import com.alechilles.alecstamework.vessels.SqliteBondedVesselJournal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Public API adapter kept separate from the vessel domain and the already-large API facade. */
public final class BondedVesselsApiDelegate implements BondedVesselsApi {
    private final BondedVesselCoordinator coordinator;

    public BondedVesselsApiDelegate(@Nonnull BondedVesselCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * Production composition path. Supplying the schema-v8 repository is mandatory so capability
     * advertisement can never accidentally use a process-local-only implementation.
     */
    @Nonnull
    public static BondedVesselsApiDelegate journalBacked(
            @Nonnull BondedVesselRepository repository,
            @Nonnull BondedVesselTransitionPlanner planner,
            @Nonnull BondedVesselEvidenceAuthority evidenceAuthority,
            @Nonnull BondedVesselMutationAuthority mutationAuthority,
            @Nullable BondedVesselEventSink eventSink,
            @Nonnull Executor executor,
            @Nonnull LongSupplier wallClockMs,
            @Nonnull LongSupplier monotonicNanos,
            long tokenLifetimeMs,
            int recoveryLimit
    ) {
        return new BondedVesselsApiDelegate(new BondedVesselCoordinator(
                new SqliteBondedVesselJournal(Objects.requireNonNull(repository, "repository")),
                planner, evidenceAuthority, mutationAuthority, eventSink, executor,
                wallClockMs, monotonicNanos, tokenLifetimeMs, recoveryLimit));
    }

    @Nonnull
    public BondedVesselCoordinator coordinator() {
        return coordinator;
    }

    @Override
    public Optional<BondedVesselView> getByBindingId(UUID bindingId) {
        return coordinator.getByBindingId(bindingId);
    }

    @Override
    public Optional<BondedVesselView> getByProfileId(String profileId) {
        return coordinator.getByProfileId(profileId);
    }

    @Override
    public BondedVesselReadinessView readiness() {
        return coordinator.readiness();
    }

    @Override
    public BondedVesselProjectionValidationView validateProjection(
            BondedVesselProjectionValidationRequest request
    ) {
        return coordinator.validateProjection(request);
    }

    @Override
    public CompletionStage<BondedVesselOperationResult> prepareTransition(
            BondedVesselTransitionRequest request
    ) {
        return coordinator.prepareTransition(request);
    }

    @Override
    public CompletionStage<BondedVesselOperationResult> resumeTransition(
            BondedVesselTransitionRequest request
    ) {
        return coordinator.resumeTransition(request);
    }

    @Override
    public BondedVesselOperationResult claimForApply(BondedVesselTransitionToken token) {
        return coordinator.claimForApply(token);
    }

    @Override
    public CompletionStage<BondedVesselOperationResult> commit(BondedVesselTransitionToken token) {
        return coordinator.commit(token);
    }

    @Override
    public CompletionStage<BondedVesselOperationResult> cancel(BondedVesselTransitionToken token) {
        return coordinator.cancel(token);
    }

    @Override
    public CompletionStage<Optional<BondedVesselOperationView>> findOperation(
            String callerNamespace,
            String idempotencyKey
    ) {
        return coordinator.findOperation(callerNamespace, idempotencyKey);
    }
}
