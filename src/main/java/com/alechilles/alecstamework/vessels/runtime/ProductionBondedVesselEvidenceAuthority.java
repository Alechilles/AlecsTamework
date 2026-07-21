package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationStatus;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselHeldItemLocatorRequest;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.vessels.BondedVesselEvidenceAuthority;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Production orchestration for exact vessel item and projection evidence.
 *
 * <p>The Hytale inventory API currently has no public, revision-bearing resolver for an arbitrary
 * holder/container path. Composition therefore supplies {@link ExactInventoryPort}; the adapter
 * never falls back to scanning a player's current hotbar. Item metadata is returned as evidence
 * only. The coordinator remains responsible for resolving it against the canonical binding row.</p>
 */
public final class ProductionBondedVesselEvidenceAuthority
        implements BondedVesselEvidenceAuthority {
    private static final String EMPTY_EVIDENCE_JSON = "{}";

    private final ExactInventoryPort inventory;
    private final ProjectionEvidencePort projections;
    private final BondedVesselHeldSlotEvidenceFactory heldSlotEvidenceFactory;

    public ProductionBondedVesselEvidenceAuthority(
            @Nonnull ExactInventoryPort inventory,
            @Nonnull ProjectionEvidencePort projections
    ) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.heldSlotEvidenceFactory = new BondedVesselHeldSlotEvidenceFactory(
                new BondedVesselItemFingerprintCodec());
    }

    @Nonnull
    public Readiness readiness() {
        PortReadiness inventoryReadiness = safeReadiness(inventory::readiness,
                "exact-inventory-port-readiness-failed");
        PortReadiness projectionReadiness = safeReadiness(projections::readiness,
                "projection-evidence-port-readiness-failed");
        boolean ready = inventoryReadiness.heldSlotSnapshotReady()
                && inventoryReadiness.exactReadReady()
                && inventoryReadiness.exactCasReady()
                && projectionReadiness.projectionReadReady();
        String reason = ready ? "bonded-vessel-evidence-ready"
                : firstUnavailableReason(inventoryReadiness, projectionReadiness);
        return new Readiness(
                inventoryReadiness.heldSlotSnapshotReady(),
                inventoryReadiness.exactReadReady(),
                inventoryReadiness.exactCasReady(),
                projectionReadiness.projectionReadReady(),
                ready,
                reason);
    }

    public boolean isCapabilityReady() {
        return readiness().capabilityReady();
    }

    /**
     * Snapshots one exact held slot and generates the source evidence inside Tamework. No caller
     * supplies an inventory revision or fingerprint.
     */
    @Nonnull
    public CompletionStage<LocatedHeldItemEvidence> locateHeldSlot(
            @Nonnull HeldSlotLocator locator
    ) {
        Objects.requireNonNull(locator, "locator");
        String canonicalHolder = BondedVesselHeldSlotEvidenceFactory.holderEvidenceId(
                locator.actorUuid());
        if (!canonicalHolder.equals(locator.holderEvidenceId())
                || !BondedVesselHeldSlotEvidenceFactory.HOTBAR_CONTAINER_PATH.equals(
                        locator.containerPath())) {
            return CompletableFuture.completedFuture(new LocatedHeldItemEvidence(
                    LocatedHeldItemStatus.SOURCE_CHANGED,
                    "held-slot-locator-is-not-canonical", null, null));
        }
        if (!readiness().heldSlotSnapshotReady()) {
            return CompletableFuture.completedFuture(new LocatedHeldItemEvidence(
                    LocatedHeldItemStatus.UNAVAILABLE,
                    "held-slot-revision-snapshot-unavailable", null, null));
        }
        CompletionStage<HeldSlotSnapshot> stage;
        try {
            stage = inventory.snapshotHeldSlot(locator);
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(new LocatedHeldItemEvidence(
                    LocatedHeldItemStatus.UNAVAILABLE,
                    "held-slot-snapshot-dispatch-failed", null, null));
        }
        return stage.handle((snapshot, failure) -> failure == null && snapshot != null
                ? toLocatedHeldItem(locator, snapshot)
                : new LocatedHeldItemEvidence(LocatedHeldItemStatus.UNAVAILABLE,
                        "held-slot-snapshot-failed", null, null));
    }

    @Override
    @Nonnull
    public CompletionStage<HeldItemObservation> locateHeldItem(
            @Nonnull BondedVesselHeldItemLocatorRequest request
    ) {
        Objects.requireNonNull(request, "request");
        HeldSlotLocator locator = new HeldSlotLocator(
                request.actorUuid(), request.holderEvidenceId(), request.containerPath(),
                request.inventorySlot(), request.requiredState(), request.expectedItemId());
        return locateHeldSlot(locator).thenApply(located -> {
            BondedVesselItemFingerprintCodec.VesselItemMetadata metadata = located.metadata();
            BondedVesselSourceItemEvidence observed = located.sourceEvidence() != null
                    ? located.sourceEvidence() : locatorPlaceholder(request, metadata);
            HeldItemStatus status = switch (located.status()) {
                case EXACT -> HeldItemStatus.EXACT;
                case NOT_FOUND -> HeldItemStatus.NOT_FOUND;
                case NOT_BONDED -> HeldItemStatus.NOT_BONDED;
                case SOURCE_CHANGED, STATE_MISMATCH -> HeldItemStatus.CHANGED;
                case AMBIGUOUS -> HeldItemStatus.AMBIGUOUS;
                case UNAVAILABLE -> HeldItemStatus.UNAVAILABLE;
            };
            return new HeldItemObservation(
                    status, located.reason(), observed,
                    metadata == null ? null : metadata.bindingId(),
                    metadata == null ? null : metadata.profileId(),
                    metadata == null ? HeldItemObservation.UNKNOWN_GENERATION
                            : metadata.generation());
        });
    }

    @Override
    @Nonnull
    public CompletionStage<HeldItemObservation> resolveHeldItem(
            @Nonnull UUID actorUuid,
            @Nonnull BondedVesselSourceItemEvidence expected
    ) {
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(expected, "expected");
        if (!readiness().exactInventoryReadReady()) {
            return CompletableFuture.completedFuture(HeldItemObservation.unavailable(expected));
        }
        return safeRead(actorUuid, expected).thenApply(read -> toHeldObservation(read, expected));
    }

    @Override
    @Nonnull
    public CompletionStage<SourceObservation> observe(
            @Nonnull BondedVesselTransitionContext expected
    ) {
        Objects.requireNonNull(expected, "expected");
        BondedVesselSourceItemEvidence expectedEvidence = evidence(expected);
        if (!readiness().exactInventoryReadReady()) {
            return CompletableFuture.completedFuture(sourceObservation(
                    Status.UNAVAILABLE, "exact-inventory-read-unavailable", expectedEvidence));
        }
        return safeRead(null, expectedEvidence).thenApply(read -> toSourceObservation(
                read, expectedEvidence));
    }

    @Override
    @Nonnull
    public CompletionStage<SourceFinalization> finalizeSource(
            @Nonnull BondedVesselOperationRecord operation,
            @Nonnull BondedVesselTransitionContext expected
    ) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(expected, "expected");
        String replacementFingerprint = fallback(
                operation.replacementFingerprint(), expected.sourceItemFingerprint());
        if (!operationMatchesExpectedSource(operation, expected)) {
            return CompletableFuture.completedFuture(new SourceFinalization(
                    FinalizationStatus.SOURCE_CHANGED,
                    "operation-source-evidence-mismatch",
                    replacementFingerprint,
                    EMPTY_EVIDENCE_JSON));
        }
        if (!readiness().exactInventoryCasReady()) {
            return CompletableFuture.completedFuture(new SourceFinalization(
                    FinalizationStatus.INDETERMINATE,
                    "exact-inventory-cas-unavailable",
                    replacementFingerprint,
                    EMPTY_EVIDENCE_JSON));
        }
        ReplacementProjection replacement;
        try {
            replacement = new ReplacementProjection(
                    requireText(operation.targetItemId(), "targetItemId"),
                    replacementFingerprint,
                    UUID.fromString(operation.bindingId()),
                    operation.profileId(),
                    operation.candidateGeneration(),
                    operation.configId(),
                    operation.targetLifecycleState());
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(new SourceFinalization(
                    FinalizationStatus.INDETERMINATE,
                    "invalid-replacement-projection",
                    replacementFingerprint,
                    EMPTY_EVIDENCE_JSON));
        }
        ExactCasRequest request = new ExactCasRequest(evidence(expected), replacement);
        CompletionStage<ExactCasResult> stage;
        try {
            stage = inventory.compareAndSet(request);
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(new SourceFinalization(
                    FinalizationStatus.INDETERMINATE,
                    "source-cas-dispatch-failed",
                    replacementFingerprint,
                    EMPTY_EVIDENCE_JSON));
        }
        return stage.handle((result, failure) -> failure == null && result != null
                ? toFinalization(result, replacementFingerprint)
                : new SourceFinalization(
                        FinalizationStatus.INDETERMINATE,
                        "source-cas-failed",
                        replacementFingerprint,
                        EMPTY_EVIDENCE_JSON));
    }

    @Override
    @Nonnull
    public BondedVesselProjectionValidationView validateProjection(
            @Nonnull BondedVesselBindingRecord binding,
            @Nonnull BondedVesselProjectionValidationRequest request
    ) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(request, "request");
        UUID bindingId;
        try {
            bindingId = UUID.fromString(binding.bindingId());
        } catch (IllegalArgumentException invalid) {
            return BondedVesselProjectionValidationView.unavailable(request.bindingId());
        }
        if (!bindingId.equals(request.bindingId())) {
            return view(request, BondedVesselProjectionValidationStatus.UNKNOWN,
                    "projection-binding-mismatch", binding.generation(), false);
        }
        if (request.generation() != binding.generation()) {
            return view(request, BondedVesselProjectionValidationStatus.STALE_GENERATION,
                    "stale-generation", binding.generation(), true);
        }
        if (!readiness().projectionEvidenceReady()) {
            return BondedVesselProjectionValidationView.unavailable(request.bindingId());
        }
        ProjectionObservation observation;
        try {
            observation = projections.observe(binding, request);
        } catch (RuntimeException | LinkageError failure) {
            observation = null;
        }
        if (observation == null) {
            return BondedVesselProjectionValidationView.unavailable(request.bindingId());
        }
        return switch (observation.status()) {
            case EXACT -> exactProjectionView(binding, request, observation);
            case PENDING -> view(request, BondedVesselProjectionValidationStatus.PENDING,
                    observation.reason(), binding.generation(), true);
            case MISSING -> view(request, BondedVesselProjectionValidationStatus.MISSING,
                    observation.reason(), binding.generation(), true);
            case DUPLICATE -> view(request, BondedVesselProjectionValidationStatus.DUPLICATE,
                    observation.reason(), binding.generation(), true);
            case STALE_GENERATION -> view(request,
                    BondedVesselProjectionValidationStatus.STALE_GENERATION,
                    observation.reason(), binding.generation(), true);
            case QUARANTINED -> view(request, BondedVesselProjectionValidationStatus.QUARANTINED,
                    observation.reason(), binding.generation(), true);
            case UNKNOWN -> view(request, BondedVesselProjectionValidationStatus.UNKNOWN,
                    observation.reason(), binding.generation(), false);
        };
    }

    private CompletionStage<ExactItemRead> safeRead(
            @Nullable UUID actorUuid,
            BondedVesselSourceItemEvidence expected
    ) {
        CompletionStage<ExactItemRead> stage;
        try {
            stage = inventory.readExact(actorUuid, expected);
        } catch (RuntimeException | LinkageError failure) {
            stage = null;
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(ExactItemRead.unavailable(expected));
        }
        return stage.handle((read, failure) -> failure == null && read != null
                ? read : ExactItemRead.unavailable(expected));
    }

    private HeldItemObservation toHeldObservation(
            ExactItemRead read,
            BondedVesselSourceItemEvidence expected
    ) {
        BondedVesselSourceItemEvidence observed = read.observedEvidence() == null
                ? expected : read.observedEvidence();
        return switch (read.status()) {
            case FOUND -> {
                if (!observed.equals(expected)) {
                    yield new HeldItemObservation(HeldItemStatus.CHANGED,
                            "held-item-source-changed", observed,
                            read.bindingId(), read.profileId(), read.generation());
                }
                if (read.bindingId() == null || read.profileId() == null || read.generation() < 0L) {
                    yield new HeldItemObservation(HeldItemStatus.NOT_BONDED,
                            "held-item-has-no-complete-vessel-metadata", observed,
                            null, null, HeldItemObservation.UNKNOWN_GENERATION);
                }
                yield new HeldItemObservation(HeldItemStatus.EXACT,
                        "held-item-evidence-exact", observed,
                        read.bindingId(), read.profileId(), read.generation());
            }
            case NOT_FOUND -> new HeldItemObservation(HeldItemStatus.NOT_FOUND,
                    read.reason(), observed, null, null, HeldItemObservation.UNKNOWN_GENERATION);
            case AMBIGUOUS -> new HeldItemObservation(HeldItemStatus.AMBIGUOUS,
                    read.reason(), observed, null, null, HeldItemObservation.UNKNOWN_GENERATION);
            case INCOMPLETE, UNAVAILABLE -> HeldItemObservation.unavailable(expected);
        };
    }

    private SourceObservation toSourceObservation(
            ExactItemRead read,
            BondedVesselSourceItemEvidence expected
    ) {
        BondedVesselSourceItemEvidence observed = read.observedEvidence() == null
                ? expected : read.observedEvidence();
        return switch (read.status()) {
            case FOUND -> sourceObservation(observed.equals(expected) ? Status.EXACT : Status.CHANGED,
                    observed.equals(expected) ? "source-evidence-exact" : "source-evidence-changed",
                    observed);
            case NOT_FOUND -> sourceObservation(Status.CHANGED, read.reason(), observed);
            case AMBIGUOUS, INCOMPLETE -> sourceObservation(Status.INCOMPLETE, read.reason(), observed);
            case UNAVAILABLE -> sourceObservation(Status.UNAVAILABLE, read.reason(), observed);
        };
    }

    private SourceFinalization toFinalization(
            ExactCasResult result,
            String replacementFingerprint
    ) {
        String evidenceJson = fallback(result.itemEvidenceJson(), EMPTY_EVIDENCE_JSON);
        return switch (result.status()) {
            case REPLACED -> new SourceFinalization(FinalizationStatus.FINALIZED,
                    result.reason(), replacementFingerprint, evidenceJson);
            case ALREADY_REPLACED -> new SourceFinalization(FinalizationStatus.ALREADY_FINALIZED,
                    result.reason(), replacementFingerprint, evidenceJson);
            case SOURCE_CHANGED -> new SourceFinalization(FinalizationStatus.SOURCE_CHANGED,
                    result.reason(), replacementFingerprint, evidenceJson);
            case AMBIGUOUS, UNAVAILABLE -> new SourceFinalization(FinalizationStatus.INDETERMINATE,
                    result.reason(), replacementFingerprint, evidenceJson);
        };
    }

    private LocatedHeldItemEvidence toLocatedHeldItem(
            HeldSlotLocator locator,
            HeldSlotSnapshot snapshot
    ) {
        if (snapshot.status() != HeldSlotSnapshotStatus.FOUND) {
            LocatedHeldItemStatus status = switch (snapshot.status()) {
                case FOUND -> throw new IllegalStateException();
                case NOT_FOUND -> LocatedHeldItemStatus.NOT_FOUND;
                case AMBIGUOUS -> LocatedHeldItemStatus.AMBIGUOUS;
                case REVISION_UNAVAILABLE, UNAVAILABLE -> LocatedHeldItemStatus.UNAVAILABLE;
            };
            return new LocatedHeldItemEvidence(status, snapshot.reason(), null, null);
        }
        BondedVesselItemFingerprintCodec.VesselItemMetadata metadata = snapshot.metadata();
        if (metadata == null) {
            return new LocatedHeldItemEvidence(LocatedHeldItemStatus.NOT_BONDED,
                    "held-slot-has-no-vessel-metadata", null, null);
        }
        if (snapshot.slot() != locator.inventorySlot()
                || (locator.expectedItemId() != null
                    && !locator.expectedItemId().equals(metadata.itemId()))) {
            return new LocatedHeldItemEvidence(LocatedHeldItemStatus.SOURCE_CHANGED,
                    "held-slot-item-or-slot-changed", null, metadata);
        }
        if (metadata.state() != locator.requiredState()) {
            return new LocatedHeldItemEvidence(LocatedHeldItemStatus.STATE_MISMATCH,
                    "held-slot-vessel-state-mismatch", null, metadata);
        }
        BondedVesselSourceItemEvidence evidence = heldSlotEvidenceFactory.create(
                locator.actorUuid(), snapshot.slot(), snapshot.monotonicInventoryRevision(), metadata);
        return new LocatedHeldItemEvidence(LocatedHeldItemStatus.EXACT,
                "held-slot-evidence-generated", evidence, metadata);
    }

    private BondedVesselProjectionValidationView exactProjectionView(
            BondedVesselBindingRecord binding,
            BondedVesselProjectionValidationRequest request,
            ProjectionObservation observation
    ) {
        if (observation.generation() != binding.generation()) {
            return view(request, BondedVesselProjectionValidationStatus.STALE_GENERATION,
                    "projection-generation-mismatch", binding.generation(), true);
        }
        boolean exact = Objects.equals(observation.bindingId(), request.bindingId())
                && Objects.equals(observation.profileId(), binding.profileId())
                && Objects.equals(observation.fingerprint(), request.projectionFingerprint());
        return exact
                ? view(request, BondedVesselProjectionValidationStatus.CONSISTENT,
                        observation.reason(), binding.generation(), true)
                : view(request, BondedVesselProjectionValidationStatus.DUPLICATE,
                        "projection-identity-or-fingerprint-mismatch", binding.generation(), true);
    }

    private static boolean operationMatchesExpectedSource(
            BondedVesselOperationRecord operation,
            BondedVesselTransitionContext expected
    ) {
        return Objects.equals(operation.sourceItemId(), expected.sourceItemId())
                && Objects.equals(operation.sourceFingerprint(), expected.sourceItemFingerprint());
    }

    private static BondedVesselSourceItemEvidence evidence(BondedVesselTransitionContext context) {
        return new BondedVesselSourceItemEvidence(
                context.sourceItemId(),
                context.sourceHolderEvidenceId(),
                context.sourceContainerPath(),
                context.sourceInventorySlot(),
                context.sourceInventoryRevision(),
                context.sourceItemFingerprint());
    }

    private static BondedVesselSourceItemEvidence locatorPlaceholder(
            BondedVesselHeldItemLocatorRequest request,
            @Nullable BondedVesselItemFingerprintCodec.VesselItemMetadata metadata
    ) {
        return new BondedVesselSourceItemEvidence(
                metadata != null ? metadata.itemId()
                        : fallback(request.expectedItemId(), "unknown-held-item"),
                request.holderEvidenceId(), request.containerPath(), request.inventorySlot(),
                0L, "unavailable-held-item-fingerprint");
    }

    private static SourceObservation sourceObservation(
            Status status,
            String reason,
            BondedVesselSourceItemEvidence evidence
    ) {
        return new SourceObservation(status, fallback(reason, "source-evidence-unavailable"),
                evidence.holderEvidenceId(), evidence.containerPath(), evidence.inventorySlot(),
                evidence.inventoryRevision(), evidence.itemId(), evidence.itemFingerprint());
    }

    private static BondedVesselProjectionValidationView view(
            BondedVesselProjectionValidationRequest request,
            BondedVesselProjectionValidationStatus status,
            String reason,
            long generation,
            boolean authoritative
    ) {
        return new BondedVesselProjectionValidationView(
                request.bindingId(), status, fallback(reason, "projection-evidence-unavailable"),
                generation, authoritative);
    }

    private static PortReadiness safeReadiness(
            ReadinessSupplier supplier,
            String failureReason
    ) {
        try {
            PortReadiness readiness = supplier.get();
            return readiness == null ? PortReadiness.unavailable(failureReason) : readiness;
        } catch (RuntimeException | LinkageError failure) {
            return PortReadiness.unavailable(failureReason);
        }
    }

    private static String firstUnavailableReason(PortReadiness first, PortReadiness second) {
        if (!first.heldSlotSnapshotReady()
                || !first.exactReadReady() || !first.exactCasReady()) return first.reason();
        return second.reason();
    }

    private static String fallback(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    @FunctionalInterface
    private interface ReadinessSupplier {
        PortReadiness get();
    }

    /** Exact world-thread inventory access; implementations must never scan alternate slots. */
    public interface ExactInventoryPort {
        /**
         * Returns a snapshot of only the requested held slot. REVISION_UNAVAILABLE is mandatory
         * when Hytale cannot provide a monotonic inventory revision for that exact container.
         */
        @Nonnull
        CompletionStage<HeldSlotSnapshot> snapshotHeldSlot(@Nonnull HeldSlotLocator locator);

        @Nonnull
        CompletionStage<ExactItemRead> readExact(
                @Nullable UUID actorUuid,
                @Nonnull BondedVesselSourceItemEvidence expected
        );

        @Nonnull
        CompletionStage<ExactCasResult> compareAndSet(@Nonnull ExactCasRequest request);

        @Nonnull
        PortReadiness readiness();
    }

    /** Current projection evidence assembled by reconciliation/world-owned indexes. */
    public interface ProjectionEvidencePort {
        @Nonnull
        ProjectionObservation observe(
                @Nonnull BondedVesselBindingRecord binding,
                @Nonnull BondedVesselProjectionValidationRequest request
        );

        @Nonnull
        PortReadiness readiness();
    }

    public enum ExactReadStatus { FOUND, NOT_FOUND, AMBIGUOUS, INCOMPLETE, UNAVAILABLE }

    public record HeldSlotLocator(
            @Nonnull UUID actorUuid,
            @Nonnull String holderEvidenceId,
            @Nonnull String containerPath,
            int inventorySlot,
            @Nonnull BondedVesselState requiredState,
            @Nullable String expectedItemId
    ) {
        public HeldSlotLocator {
            actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
            holderEvidenceId = requireText(holderEvidenceId, "holderEvidenceId");
            containerPath = requireText(containerPath, "containerPath");
            requiredState = Objects.requireNonNull(requiredState, "requiredState");
            expectedItemId = expectedItemId == null ? null : requireText(
                    expectedItemId, "expectedItemId");
            if (inventorySlot < 0) throw new IllegalArgumentException("inventorySlot cannot be negative");
        }
    }

    public enum HeldSlotSnapshotStatus {
        FOUND, NOT_FOUND, AMBIGUOUS, REVISION_UNAVAILABLE, UNAVAILABLE
    }

    public record HeldSlotSnapshot(
            @Nonnull HeldSlotSnapshotStatus status,
            @Nonnull String reason,
            int slot,
            long monotonicInventoryRevision,
            @Nullable BondedVesselItemFingerprintCodec.VesselItemMetadata metadata
    ) {
        public HeldSlotSnapshot {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if (slot < 0) throw new IllegalArgumentException("slot cannot be negative");
            if (monotonicInventoryRevision < -1L) {
                throw new IllegalArgumentException("monotonicInventoryRevision cannot be less than -1");
            }
            if (status == HeldSlotSnapshotStatus.FOUND
                    && monotonicInventoryRevision < 0L) {
                throw new IllegalArgumentException("FOUND requires a monotonic inventory revision");
            }
        }
    }

    public enum LocatedHeldItemStatus {
        EXACT, NOT_FOUND, NOT_BONDED, SOURCE_CHANGED, STATE_MISMATCH, AMBIGUOUS, UNAVAILABLE
    }

    public record LocatedHeldItemEvidence(
            @Nonnull LocatedHeldItemStatus status,
            @Nonnull String reason,
            @Nullable BondedVesselSourceItemEvidence sourceEvidence,
            @Nullable BondedVesselItemFingerprintCodec.VesselItemMetadata metadata
    ) {
        public LocatedHeldItemEvidence {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if ((status == LocatedHeldItemStatus.EXACT)
                    != (sourceEvidence != null && metadata != null)) {
                throw new IllegalArgumentException("only EXACT exposes generated source evidence");
            }
        }
    }

    public record ExactItemRead(
            @Nonnull ExactReadStatus status,
            @Nonnull String reason,
            @Nullable BondedVesselSourceItemEvidence observedEvidence,
            @Nullable UUID bindingId,
            @Nullable String profileId,
            long generation
    ) {
        public ExactItemRead {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            profileId = profileId == null ? null : requireText(profileId, "profileId");
            if (generation < HeldItemObservation.UNKNOWN_GENERATION) {
                throw new IllegalArgumentException("generation cannot be less than -1");
            }
        }

        public static ExactItemRead unavailable(BondedVesselSourceItemEvidence expected) {
            return new ExactItemRead(ExactReadStatus.UNAVAILABLE,
                    "exact-inventory-read-unavailable", expected, null, null,
                    HeldItemObservation.UNKNOWN_GENERATION);
        }
    }

    public record ReplacementProjection(
            @Nonnull String itemId,
            @Nonnull String fingerprint,
            @Nonnull UUID bindingId,
            @Nonnull String profileId,
            long generation,
            @Nonnull String configId,
            @Nonnull BondedVesselBindingRecord.LifecycleState state
    ) {
        public ReplacementProjection {
            itemId = requireText(itemId, "itemId");
            fingerprint = requireText(fingerprint, "fingerprint");
            bindingId = Objects.requireNonNull(bindingId, "bindingId");
            profileId = requireText(profileId, "profileId");
            configId = requireText(configId, "configId");
            state = Objects.requireNonNull(state, "state");
            if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        }
    }

    public record ExactCasRequest(
            @Nonnull BondedVesselSourceItemEvidence expected,
            @Nonnull ReplacementProjection replacement
    ) {
        public ExactCasRequest {
            expected = Objects.requireNonNull(expected, "expected");
            replacement = Objects.requireNonNull(replacement, "replacement");
        }
    }

    public enum ExactCasStatus {
        REPLACED, ALREADY_REPLACED, SOURCE_CHANGED, AMBIGUOUS, UNAVAILABLE
    }

    public record ExactCasResult(
            @Nonnull ExactCasStatus status,
            @Nonnull String reason,
            @Nullable String itemEvidenceJson
    ) {
        public ExactCasResult {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            itemEvidenceJson = itemEvidenceJson == null ? null : requireText(
                    itemEvidenceJson, "itemEvidenceJson");
        }
    }

    public enum ProjectionEvidenceStatus {
        EXACT, PENDING, MISSING, DUPLICATE, STALE_GENERATION, QUARANTINED, UNKNOWN
    }

    public record ProjectionObservation(
            @Nonnull ProjectionEvidenceStatus status,
            @Nonnull String reason,
            @Nullable UUID bindingId,
            @Nullable String profileId,
            long generation,
            @Nullable String fingerprint
    ) {
        public ProjectionObservation {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            profileId = profileId == null ? null : requireText(profileId, "profileId");
            fingerprint = fingerprint == null ? null : requireText(fingerprint, "fingerprint");
            if (generation < -1L) throw new IllegalArgumentException("generation cannot be less than -1");
        }
    }

    /** Independent readiness facets prevent a partial evidence adapter from enabling capability. */
    public record PortReadiness(
            boolean heldSlotSnapshotReady,
            boolean exactReadReady,
            boolean exactCasReady,
            boolean projectionReadReady,
            @Nonnull String reason
    ) {
        public PortReadiness {
            reason = requireText(reason, "reason");
        }

        public static PortReadiness unavailable(String reason) {
            return new PortReadiness(false, false, false, false, reason);
        }
    }

    public record Readiness(
            boolean heldSlotSnapshotReady,
            boolean exactInventoryReadReady,
            boolean exactInventoryCasReady,
            boolean projectionEvidenceReady,
            boolean capabilityReady,
            @Nonnull String reason
    ) {
        public Readiness {
            reason = requireText(reason, "reason");
            if (capabilityReady != (heldSlotSnapshotReady && exactInventoryReadReady
                    && exactInventoryCasReady && projectionEvidenceReady)) {
                throw new IllegalArgumentException("capability readiness must equal all evidence facets");
            }
        }
    }
}
