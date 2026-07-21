package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselHeldItemLocatorRequest;
import com.alechilles.alecstamework.api.BondedVesselHeldItemLocatorResult;
import com.alechilles.alecstamework.api.BondedVesselHeldItemProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselOperationResult;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransition;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.api.BondedVesselTransitionToken;
import com.alechilles.alecstamework.api.BondedVesselView;
import com.alechilles.alecstamework.api.BondedVesselsApi;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Routes a normal bonded spawner click through the generation-fenced vessel state machine. */
public final class BondedVesselInteractionDispatcher {
    private static final String CALLER_NAMESPACE = "tamework:bonded-spawner";
    private final BondedVesselsApi vessels;

    public BondedVesselInteractionDispatcher(@Nonnull BondedVesselsApi vessels) {
        this.vessels = Objects.requireNonNull(vessels, "vessels");
    }

    /**
     * Dispatches SUMMON or STORE according to the authoritative metadata in the exact held slot.
     * The deterministic generation key makes a repeated packet/callback converge on one operation.
     */
    @Nonnull
    public CompletionStage<Result> toggle(@Nonnull Request request) {
        Objects.requireNonNull(request, "request");
        return locate(request, BondedVesselState.STORED)
                .thenCompose(stored -> {
                    if (stored.status() == BondedVesselHeldItemProjectionStatus.VALID) {
                        return execute(request, stored, BondedVesselTransition.SUMMON);
                    }
                    return locate(request, BondedVesselState.ACTIVE)
                            .thenCompose(active -> active.status()
                                    == BondedVesselHeldItemProjectionStatus.VALID
                                    ? execute(request, active, BondedVesselTransition.STORE)
                                    : CompletableFuture.completedFuture(
                                            unresolvedResult(stored, active)));
                })
                .exceptionally(failure -> Result.unavailable(
                        "bonded-vessel-interaction-dispatch-failed"));
    }

    private CompletionStage<BondedVesselHeldItemLocatorResult> locate(
            Request request,
            BondedVesselState state) {
        BondedVesselHeldItemLocatorRequest locator = new BondedVesselHeldItemLocatorRequest(
                request.actorUuid(), holder(request.actorUuid()),
                BondedVesselHeldSlotEvidenceFactory.HOTBAR_CONTAINER_PATH,
                request.inventorySlot(), request.expectedItemId(), state);
        CompletionStage<BondedVesselHeldItemLocatorResult> stage;
        try {
            stage = vessels.resolveHeldItemLocator(locator);
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        return stage == null
                ? CompletableFuture.completedFuture(
                        BondedVesselHeldItemLocatorResult.unavailable(locator))
                : stage;
    }

    private CompletionStage<Result> execute(
            Request request,
            BondedVesselHeldItemLocatorResult located,
            BondedVesselTransition transition) {
        BondedVesselSourceItemEvidence source = located.resolvedSourceEvidence().orElse(null);
        BondedVesselView vessel = located.resolvedVessel().orElse(null);
        if (!located.authoritative() || source == null || vessel == null) {
            return CompletableFuture.completedFuture(Result.unavailable(
                    "bonded-vessel-held-source-not-authoritative"));
        }
        if (transition == BondedVesselTransition.SUMMON && request.destination() == null) {
            return CompletableFuture.completedFuture(Result.denied(
                    "bonded-vessel-summon-destination-unavailable"));
        }
        if (transition == BondedVesselTransition.STORE && vessel.currentNpcUuid() == null) {
            return CompletableFuture.completedFuture(Result.denied(
                    "bonded-vessel-active-projection-unavailable"));
        }
        BondedVesselTransitionContext context = new BondedVesselTransitionContext(
                source.itemId(), source.holderEvidenceId(), source.containerPath(),
                source.inventorySlot(), source.inventoryRevision(), source.itemFingerprint(),
                transition == BondedVesselTransition.STORE ? vessel.currentNpcUuid() : null,
                transition == BondedVesselTransition.SUMMON ? request.destination() : null);
        BondedVesselTransitionRequest transitionRequest = new BondedVesselTransitionRequest(
                CALLER_NAMESPACE,
                "toggle:" + vessel.bindingId() + ":" + vessel.generation(),
                request.actorUuid(), vessel.bindingId(), vessel.generation(),
                vessel.profileRevision(), transition, context);
        CompletionStage<BondedVesselOperationResult> preparation;
        try {
            preparation = vessels.prepareTransition(transitionRequest);
        } catch (RuntimeException | LinkageError failure) {
            preparation = null;
        }
        if (preparation == null) {
            return CompletableFuture.completedFuture(Result.unavailable(
                    "bonded-vessel-transition-prepare-unavailable"));
        }
        return preparation.thenCompose(prepared -> continuePrepared(transition, prepared));
    }

    private CompletionStage<Result> continuePrepared(
            BondedVesselTransition transition,
            @Nullable BondedVesselOperationResult prepared) {
        if (prepared == null) {
            return CompletableFuture.completedFuture(Result.unavailable(
                    "bonded-vessel-transition-result-missing"));
        }
        if (prepared.status() == BondedVesselOperationResult.Status.COMMITTED) {
            return CompletableFuture.completedFuture(Result.committed(
                    transition, prepared.reason(), prepared));
        }
        if (prepared.status() != BondedVesselOperationResult.Status.RESERVED
                && prepared.status() != BondedVesselOperationResult.Status.APPLYING
                && prepared.status() != BondedVesselOperationResult.Status.APPLIED) {
            return CompletableFuture.completedFuture(fromClosed(prepared));
        }
        BondedVesselTransitionToken token = prepared.token();
        BondedVesselOperationResult claimed;
        try {
            claimed = vessels.claimForApply(token);
        } catch (RuntimeException | LinkageError failure) {
            claimed = null;
        }
        if (claimed == null || claimed.status() != BondedVesselOperationResult.Status.APPLYING) {
            return CompletableFuture.completedFuture(claimed == null
                    ? Result.unavailable("bonded-vessel-transition-claim-unavailable")
                    : fromClosed(claimed));
        }
        CompletionStage<BondedVesselOperationResult> commit;
        try {
            commit = vessels.commit(token);
        } catch (RuntimeException | LinkageError failure) {
            commit = null;
        }
        if (commit == null) {
            return CompletableFuture.completedFuture(Result.unavailable(
                    "bonded-vessel-transition-commit-unavailable"));
        }
        return commit.thenApply(result -> result != null
                && result.status() == BondedVesselOperationResult.Status.COMMITTED
                ? Result.committed(transition, result.reason(), result)
                : result == null
                ? Result.unavailable("bonded-vessel-transition-commit-result-missing")
                : fromClosed(result));
    }

    private static Result fromClosed(BondedVesselOperationResult result) {
        return switch (result.status()) {
            case QUARANTINED -> Result.quarantined(result.reason());
            case UNAVAILABLE, APPLYING, APPLIED, RESERVED -> Result.unavailable(result.reason());
            case COMMITTED -> Result.committed(null, result.reason(), result);
            case CANCELED, DENIED -> Result.denied(result.reason());
        };
    }

    private static Result unresolvedResult(
            BondedVesselHeldItemLocatorResult stored,
            BondedVesselHeldItemLocatorResult active) {
        if (active.status() == BondedVesselHeldItemProjectionStatus.UNAVAILABLE
                || stored.status() == BondedVesselHeldItemProjectionStatus.UNAVAILABLE) {
            return Result.unavailable("bonded-vessel-held-item-authority-unavailable");
        }
        if (active.status() == BondedVesselHeldItemProjectionStatus.STALE_GENERATION
                || stored.status() == BondedVesselHeldItemProjectionStatus.STALE_GENERATION) {
            return Result.denied("bonded-vessel-stale-generation");
        }
        return Result.denied(active.reason());
    }

    private static String holder(UUID actorUuid) {
        return BondedVesselHeldSlotEvidenceFactory.holderEvidenceId(actorUuid);
    }

    public record Request(@Nonnull UUID actorUuid,
                          int inventorySlot,
                          @Nonnull String expectedItemId,
                          @Nullable PopulationAdmissionLocation destination) {
        public Request {
            actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
            expectedItemId = Objects.requireNonNull(expectedItemId, "expectedItemId").trim();
            if (expectedItemId.isEmpty()) {
                throw new IllegalArgumentException("expectedItemId is required");
            }
            if (inventorySlot < 0) {
                throw new IllegalArgumentException("inventorySlot cannot be negative");
            }
        }
    }

    public enum Status { COMMITTED, DENIED, QUARANTINED, UNAVAILABLE }

    public record Result(@Nonnull Status status,
                         @Nonnull String reason,
                         @Nullable BondedVesselTransition transition,
                         @Nullable BondedVesselOperationResult operation) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            reason = Objects.requireNonNull(reason, "reason").trim();
            if (reason.isEmpty()) throw new IllegalArgumentException("reason is required");
            if (status == Status.COMMITTED && operation == null) {
                throw new IllegalArgumentException("Committed dispatch requires operation evidence");
            }
        }

        static Result committed(BondedVesselTransition transition, String reason,
                                BondedVesselOperationResult operation) {
            return new Result(Status.COMMITTED, reason, transition, operation);
        }

        static Result denied(String reason) {
            return new Result(Status.DENIED, reason, null, null);
        }

        static Result quarantined(String reason) {
            return new Result(Status.QUARANTINED, reason, null, null);
        }

        static Result unavailable(String reason) {
            return new Result(Status.UNAVAILABLE, reason, null, null);
        }
    }
}
