package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureAttempt;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureOutcome;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.OutcomeStatus;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemEnvelopeCodec.DecodeOutcome;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemEnvelopeCodec.Envelope;
import com.alechilles.alecstamework.items.ManagedCoopItemCaptureFinalizer.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService.CapturePlacement;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure admission and two-phase retirement coordinator for captured items targeting managed coops.
 *
 * <p>The caller must already have classified the physical block as Tamework-managed and must not
 * invoke vanilla admission afterward. This handler verifies a complete portable snapshot and
 * canonical profile, commits the slot, replaces the filled item with a non-spawnable receipt, and
 * only then completes the durable capture.</p>
 */
public final class ManagedCoopItemIntakeHandler {
    private static final int MAX_TERMINAL_FINGERPRINTS = 4096;

    public enum StartStatus {
        ACCEPTED,
        DEDUPLICATED,
        REJECTED
    }

    public enum OutcomeStatusValue {
        COMPLETED,
        DEDUPLICATED,
        REJECTED,
        FAILED
    }

    /** Immutable interaction input. Live player components must not be captured here. */
    public record IntakeRequest(@Nonnull ManagedCoopContext context,
                                @Nonnull UUID playerUuid,
                                short hotbarSlot,
                                @Nonnull String itemId,
                                @Nullable String rawEnvelope,
                                @Nonnull ItemRetirementAction itemRetirement,
                                @Nonnull FeedbackSink feedback) {
        public IntakeRequest {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(playerUuid, "playerUuid");
            itemId = requireText(itemId, "itemId");
            Objects.requireNonNull(itemRetirement, "itemRetirement");
            Objects.requireNonNull(feedback, "feedback");
            if (hotbarSlot < 0) {
                throw new IllegalArgumentException("hotbarSlot must not be negative");
            }
        }
    }

    public record IntakeOutcome(@Nonnull OutcomeStatusValue status,
                                @Nullable RetirementReady retirementReady,
                                @Nullable String detail) {
        public IntakeOutcome {
            Objects.requireNonNull(status, "status");
        }

        public boolean completed() {
            return status == OutcomeStatusValue.COMPLETED
                    || status == OutcomeStatusValue.DEDUPLICATED;
        }
    }

    public record IntakeStart(@Nonnull StartStatus status,
                              @Nonnull CompletionStage<IntakeOutcome> completion,
                              @Nullable String detail) {
        public IntakeStart {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(completion, "completion");
        }

        public boolean accepted() {
            return status != StartStatus.REJECTED;
        }
    }

    /** Receipt proves the filled item was replaced after the durable slot claim. */
    public record ItemRetirementReceipt(@Nonnull String itemFingerprint,
                                        @Nonnull String operationId,
                                        @Nonnull ReceiptCleanup cleanup) {
        public ItemRetirementReceipt {
            requireSha256(itemFingerprint, "itemFingerprint");
            operationId = requireText(operationId, "operationId");
            Objects.requireNonNull(cleanup, "cleanup");
        }
    }

    private final ManagedCoopCapturedItemEnvelopeCodec envelopes;
    private final PlacementGateway placements;
    private final ProfileGateway profiles;
    private final CaptureGateway captures;
    private final FinalizationGateway finalization;
    @Nullable
    private final ManagedCoopPersistenceGate persistenceGate;
    private final ManagedCoopCapturedItemAttemptFactory attempts =
            new ManagedCoopCapturedItemAttemptFactory();
    private final ConcurrentHashMap<String, CompletableFuture<IntakeOutcome>> pending =
            new ConcurrentHashMap<>();
    private final TerminalRegistry terminal = new TerminalRegistry(MAX_TERMINAL_FINGERPRINTS);

    public ManagedCoopItemIntakeHandler(
            @Nonnull ManagedCoopOccupancyService occupancy,
            @Nonnull NpcProfileRepository profiles,
            @Nonnull ManagedCoopCaptureCoordinator captures,
            @Nonnull ManagedCoopItemCaptureFinalizer finalizer) {
        this(
                new ManagedCoopCapturedItemEnvelopeCodec(),
                occupancy::resolveCapturePlacement,
                profiles::resolveProfileId,
                captures::coordinate,
                finalizer::complete
        );
    }

    ManagedCoopItemIntakeHandler(
            @Nonnull ManagedCoopCapturedItemEnvelopeCodec envelopes,
            @Nonnull PlacementGateway placements,
            @Nonnull ProfileGateway profiles,
            @Nonnull CaptureGateway captures,
            @Nonnull FinalizationGateway finalization,
            @Nonnull ManagedCoopPersistenceGate... persistenceGates) {
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.captures = Objects.requireNonNull(captures, "captures");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        if (persistenceGates.length > 1) throw new IllegalArgumentException("persistenceGates");
        this.persistenceGate = persistenceGates.length == 0 ? null : persistenceGates[0];
    }

    /** Starts one managed intake. A returned REJECTED result still means vanilla must stay blocked. */
    @Nonnull
    public IntakeStart handle(@Nonnull IntakeRequest request) {
        Objects.requireNonNull(request, "request");
        DecodeOutcome decoded = envelopes.decode(request.itemId(), request.rawEnvelope());
        if (!decoded.found() || decoded.envelope() == null) {
            return reject(request, fallback(decoded.detail(), "managed_coop_item_state_incomplete"));
        }
        Envelope envelope = decoded.envelope();
        RetirementReady completed = terminal.get(envelope.fingerprint());
        if (completed != null) {
            return replayCompletedItem(request, envelope, completed);
        }
        CompletableFuture<IntakeOutcome> existing = pending.get(envelope.fingerprint());
        if (existing != null) {
            return new IntakeStart(StartStatus.DEDUPLICATED, existing, "item_intake_already_in_flight");
        }
        String canonicalProfile = safeResolveProfile(envelope.sourceNpcUuid());
        if (!envelope.profileId().equals(canonicalProfile)) {
            return reject(request, "managed_coop_item_profile_identity_mismatch");
        }
        CapturePlacement placement = safeResolvePlacement(request.context(), envelope);
        if (placement == null || !placement.permitted()) {
            return reject(request, placement != null
                    ? fallback(placement.detail(), "managed_coop_item_capacity_rejected")
                    : "managed_coop_item_capacity_check_failed");
        }
        if (persistenceGate != null && !persistenceGate.intake(
                request.context(), envelope.profileId(), placement.residentSlot(), true).allowed()) {
            return reject(request, "managed_coop_intake_unavailable");
        }
        final CaptureAttempt attempt;
        try {
            attempt = attempts.build(request, envelope, placement);
        } catch (RuntimeException exception) {
            return reject(request, detail("managed_coop_item_attempt", exception));
        }
        CompletableFuture<IntakeOutcome> completion = new CompletableFuture<>();
        CompletableFuture<IntakeOutcome> raced = pending.putIfAbsent(envelope.fingerprint(), completion);
        if (raced != null) {
            return new IntakeStart(StartStatus.DEDUPLICATED, raced, "item_intake_already_in_flight");
        }
        startPipeline(request, envelope, attempt, completion);
        return new IntakeStart(StartStatus.ACCEPTED, completion, null);
    }

    private void startPipeline(IntakeRequest request,
                               Envelope envelope,
                               CaptureAttempt attempt,
                               CompletableFuture<IntakeOutcome> completion) {
        final CompletionStage<CaptureOutcome> capture;
        try {
            capture = captures.capture(attempt);
        } catch (RuntimeException exception) {
            finish(request, envelope, completion, failed(detail("managed_coop_item_capture", exception)));
            return;
        }
        if (capture == null) {
            finish(request, envelope, completion, failed("managed_coop_item_capture_completion_missing"));
            return;
        }
        capture.thenCompose(outcome -> afterCapture(request, envelope, attempt, outcome))
                .handle((outcome, failure) -> failure == null
                        ? outcome
                        : failed(detail("managed_coop_item_pipeline", unwrap(failure))))
                .whenComplete((outcome, failure) -> finish(
                        request,
                        envelope,
                        completion,
                        failure == null ? outcome : failed(detail("managed_coop_item_pipeline", failure))));
    }

    private CompletionStage<IntakeOutcome> afterCapture(IntakeRequest request,
                                                        Envelope envelope,
                                                        CaptureAttempt attempt,
                                                        @Nullable CaptureOutcome capture) {
        RetirementReady ready = capture != null ? capture.retirementReady() : null;
        if (capture == null || capture.status() != OutcomeStatus.RETIREMENT_READY
                || !matchesReady(envelope, attempt, ready)) {
            String reason = capture != null ? capture.detail() : null;
            return CompletableFuture.completedFuture(failed(
                    "managed_coop_item_capture_not_ready" + suffix(reason)));
        }
        return retireItem(request, envelope, ready).thenCompose(receipt -> {
            if (!matchesReceipt(envelope, ready, receipt)) {
                return CompletableFuture.completedFuture(
                        failed("managed_coop_item_retirement_receipt_invalid"));
            }
            if (ready.durableState()
                    == com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository
                    .OperationState.COMPLETE) {
                return cleanupThen(receipt, new IntakeOutcome(
                        OutcomeStatusValue.DEDUPLICATED, ready, "capture_already_complete"));
            }
            return finalizeCapture(ready).thenCompose(result -> {
                if (result == null || !result.completed()) {
                    return CompletableFuture.completedFuture(failed(
                            result != null
                                    ? fallback(result.detail(), "managed_coop_item_finalization_rejected")
                                    : "managed_coop_item_finalization_missing"));
                }
                return cleanupThen(receipt, new IntakeOutcome(
                        OutcomeStatusValue.COMPLETED, ready, null));
            });
        });
    }

    private CompletionStage<ItemRetirementReceipt> retireItem(IntakeRequest request,
                                                               Envelope envelope,
                                                               RetirementReady ready) {
        try {
            CompletionStage<ItemRetirementReceipt> result =
                    request.itemRetirement().retire(ready, envelope);
            return result != null
                    ? result
                    : CompletableFuture.failedFuture(
                            new IllegalStateException("item_retirement_completion_missing"));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private CompletionStage<Outcome> finalizeCapture(RetirementReady ready) {
        try {
            CompletionStage<Outcome> result = finalization.complete(ready);
            return result != null
                    ? result
                    : CompletableFuture.completedFuture(
                            new Outcome(false, "managed_coop_item_finalization_missing"));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    new Outcome(false, detail("managed_coop_item_finalization", exception)));
        }
    }

    private CompletionStage<IntakeOutcome> cleanupThen(ItemRetirementReceipt receipt,
                                                       IntakeOutcome terminalOutcome) {
        final CompletionStage<Boolean> cleanup;
        try {
            cleanup = receipt.cleanup().cleanup();
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(terminalOutcome);
        }
        if (cleanup == null) {
            return CompletableFuture.completedFuture(terminalOutcome);
        }
        return cleanup.handle((ignored, failure) -> terminalOutcome);
    }

    private IntakeStart replayCompletedItem(IntakeRequest request,
                                            Envelope envelope,
                                            RetirementReady ready) {
        CompletionStage<IntakeOutcome> completion = retireItem(request, envelope, ready)
                .thenCompose(receipt -> cleanupThen(receipt, new IntakeOutcome(
                        OutcomeStatusValue.DEDUPLICATED, ready, "item_intake_terminal_replay")))
                .exceptionally(failure -> new IntakeOutcome(
                        OutcomeStatusValue.DEDUPLICATED, ready, "item_intake_terminal_replay_stale"));
        return new IntakeStart(StartStatus.DEDUPLICATED, completion, "item_intake_already_complete");
    }

    private boolean matchesReady(Envelope envelope,
                                 CaptureAttempt attempt,
                                 @Nullable RetirementReady ready) {
        return ready != null
                && ready.sourceNpcUuid().equals(envelope.sourceNpcUuid())
                && ready.profileId().equals(envelope.profileId())
                && ready.authorityKey().equals(attempt.authorityKey())
                && ready.coopId().equals(attempt.coopId())
                && ready.residentSlot() == attempt.residentSlot()
                && ready.snapshotHash().equals(attempt.snapshotHash());
    }

    private boolean matchesReceipt(Envelope envelope,
                                   RetirementReady ready,
                                   @Nullable ItemRetirementReceipt receipt) {
        return receipt != null
                && receipt.itemFingerprint().equals(envelope.fingerprint())
                && receipt.operationId().equals(ready.operationId());
    }

    @Nullable
    private String safeResolveProfile(UUID sourceNpcUuid) {
        try {
            String profileId = profiles.resolve(sourceNpcUuid);
            return profileId == null || profileId.isBlank() ? null : profileId.trim();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Nullable
    private CapturePlacement safeResolvePlacement(ManagedCoopContext context, Envelope envelope) {
        try {
            return placements.resolve(context, envelope.sourceNpcUuid(), envelope.profileId());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void finish(IntakeRequest request,
                        Envelope envelope,
                        CompletableFuture<IntakeOutcome> completion,
                        @Nullable IntakeOutcome outcome) {
        IntakeOutcome resolved = outcome != null
                ? outcome
                : failed("managed_coop_item_outcome_missing");
        if (resolved.completed() && resolved.retirementReady() != null) {
            terminal.put(envelope.fingerprint(), resolved.retirementReady());
        }
        pending.remove(envelope.fingerprint(), completion);
        completion.complete(resolved);
        sendFeedback(request.feedback(), resolved);
    }

    private IntakeStart reject(IntakeRequest request, String detail) {
        IntakeOutcome outcome = new IntakeOutcome(OutcomeStatusValue.REJECTED, null, detail);
        sendFeedback(request.feedback(), outcome);
        return new IntakeStart(
                StartStatus.REJECTED,
                CompletableFuture.completedFuture(outcome),
                detail
        );
    }

    private void sendFeedback(FeedbackSink feedback, IntakeOutcome outcome) {
        String message = outcome.completed()
                ? "Captured companion stored in the managed coop."
                : "Managed coop intake was blocked: " + fallback(outcome.detail(), "unknown failure");
        try {
            feedback.send(message);
        } catch (RuntimeException ignored) {
            // Feedback must never alter durable lifecycle state.
        }
    }

    @Nonnull
    private IntakeOutcome failed(String detail) {
        return new IntakeOutcome(OutcomeStatusValue.FAILED, null, detail);
    }

    @Nonnull
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    @Nonnull
    private static String detail(String stage, Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        return stage + "_failed" + suffix(message != null
                ? message
                : failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    @Nonnull
    private static String suffix(@Nullable String value) {
        return value == null || value.isBlank() ? "" : ":" + value;
    }

    @Nonnull
    private static String fallback(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireSha256(@Nullable String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical lowercase SHA-256");
        }
    }

    @FunctionalInterface
    public interface ItemRetirementAction {
        @Nonnull
        CompletionStage<ItemRetirementReceipt> retire(
                @Nonnull RetirementReady ready,
                @Nonnull Envelope envelope);
    }

    @FunctionalInterface
    public interface ReceiptCleanup {
        @Nonnull
        CompletionStage<Boolean> cleanup();
    }

    @FunctionalInterface
    public interface FeedbackSink {
        void send(@Nonnull String message);
    }

    @FunctionalInterface
    interface PlacementGateway {
        @Nonnull
        CapturePlacement resolve(@Nonnull ManagedCoopContext context,
                                 @Nonnull UUID sourceNpcUuid,
                                 @Nonnull String profileId);
    }

    @FunctionalInterface
    interface ProfileGateway {
        @Nullable
        String resolve(@Nonnull UUID sourceNpcUuid);
    }

    @FunctionalInterface
    interface CaptureGateway {
        @Nonnull
        CompletionStage<CaptureOutcome> capture(@Nonnull CaptureAttempt attempt);
    }

    @FunctionalInterface
    interface FinalizationGateway {
        @Nonnull
        CompletionStage<Outcome> complete(@Nonnull RetirementReady ready);
    }

    private static final class TerminalRegistry {
        private final int maximum;
        private final LinkedHashMap<String, RetirementReady> values =
                new LinkedHashMap<>(16, 0.75f, true);

        private TerminalRegistry(int maximum) {
            this.maximum = maximum;
        }

        @Nullable
        private synchronized RetirementReady get(String fingerprint) {
            return values.get(fingerprint);
        }

        private synchronized void put(String fingerprint, RetirementReady ready) {
            values.put(fingerprint, ready);
            while (values.size() > maximum) {
                String eldest = values.keySet().iterator().next();
                values.remove(eldest);
            }
        }
    }
}
