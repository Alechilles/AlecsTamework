package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.ownership.CompanionRelocationAdmissionService;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** Owns the async prepare and mutation-bound claim/cancel/commit lifecycle for relocations. */
final class CommandRelocationAdmissionGate {
    @Nullable
    private volatile CompanionRelocationAdmissionService authority;

    void setAuthority(@Nullable CompanionRelocationAdmissionService authority) {
        this.authority = authority;
    }

    @Nullable
    ClaimChunkCoordinate resolveCanonicalSource(UUID npcUuid) {
        CompanionRelocationAdmissionService service = authority;
        return service == null || npcUuid == null
                ? null : service.resolveCanonicalSource(npcUuid);
    }

    boolean ensure(PendingRelocation pending,
                   CompanionRelocationAdmissionService.Request request,
                   Dispatcher dispatcher,
                   BooleanSupplier stillCurrent,
                   Runnable ready,
                   Consumer<String> retrying,
                   Consumer<String> denied) {
        Objects.requireNonNull(pending, "pending");
        if (pending.admissionPrepared()) {
            return true;
        }
        if (pending.admissionTransitionInProgress() || !pending.beginAdmissionPreparation()) {
            return false;
        }
        CompanionRelocationAdmissionService service = authority;
        if (service == null) {
            pending.finishUnclaimedPreparation(false);
            safeAccept(denied, "relocation-admission-authority-unavailable");
            return false;
        }
        try {
            service.prepare(request).whenComplete((decision, failure) -> dispatch(
                    dispatcher,
                    () -> finishPreparation(
                            pending, service, decision, failure, dispatcher,
                            stillCurrent, ready, retrying, denied
                    ),
                    () -> closePreparationAfterDispatchFailure(
                            pending, service, decision, stillCurrent, denied
                    )
            ));
        } catch (RuntimeException | LinkageError exception) {
            pending.finishUnclaimedPreparation(false);
            safeAccept(denied, "relocation-admission-prepare-failed");
        }
        return false;
    }

    /**
     * Claims a prepared capability synchronously. A true result may be followed immediately by
     * the first live mutation; this method never dispatches or retries on its success path.
     */
    boolean claimForApply(PendingRelocation pending,
                          Dispatcher dispatcher,
                          BooleanSupplier stillCurrent,
                          Consumer<String> retrying,
                          Consumer<String> denied) {
        if (pending.admissionApplying()) {
            return true;
        }
        CompanionRelocationAdmissionService.Admission admission = pending.beginApplyClaim();
        if (admission == null) {
            return false;
        }
        CompanionRelocationAdmissionService service = authority;
        if (service == null) {
            pending.failApplyClaimForCancellation();
            pending.finishCancellation(false);
            safeAccept(denied, "relocation-admission-authority-unavailable");
            return false;
        }

        CompanionRelocationAdmissionService.Decision decision;
        try {
            decision = service.claimForApply(admission);
        } catch (RuntimeException | LinkageError exception) {
            CompanionRelocationAdmissionService.Admission closing =
                    pending.failApplyClaimForCancellation();
            closeClaimFailure(
                    pending, service, closing, dispatcher, stillCurrent, denied,
                    "relocation-admission-claim-failed"
            );
            return false;
        }

        boolean retryable = CommandRelocationAdmissionRetryPolicy.shouldRetry(decision);
        PendingRelocation.ClaimCompletion completion = pending.finishApplyClaim(
                admission, decision, retryable
        );
        if (completion == PendingRelocation.ClaimCompletion.APPLYING) {
            return true;
        }
        if (completion == PendingRelocation.ClaimCompletion.CANCEL_REQUIRED) {
            closeClaimFailure(
                    pending, service, admission, dispatcher, stillCurrent, denied,
                    "relocation-admission-canceled-before-mutation"
            );
            return false;
        }
        if (completion == PendingRelocation.ClaimCompletion.RETRY_REQUIRED) {
            cancelOrphan(service, admission);
            safeAccept(retrying, decision == null
                    ? "relocation-admission-claim-retry" : decision.reason());
            return false;
        }
        cancelOrphan(service, admission);
        safeAccept(denied, decision == null
                ? "relocation-admission-claim-failed" : decision.reason());
        return false;
    }

    void cancel(PendingRelocation pending,
                boolean retry,
                Dispatcher dispatcher,
                BooleanSupplier stillCurrent,
                @Nullable Runnable continuation,
                Consumer<String> failed) {
        CompanionRelocationAdmissionService.Admission admission = pending.beginCancellation();
        if (admission == null) {
            if (retry && continuation != null && !pending.admissionTransitionInProgress()
                    && !pending.admissionPrepared()) {
                safeRun(continuation);
            }
            return;
        }
        CompanionRelocationAdmissionService service = authority;
        if (service == null) {
            pending.finishCancellation(false);
            safeAccept(failed, "relocation-admission-authority-unavailable");
            return;
        }
        try {
            service.cancel(admission).whenComplete((decision, failure) -> dispatch(
                    dispatcher,
                    () -> finishCancellation(
                            pending, retry, decision, failure, stillCurrent, continuation, failed
                    ),
                    () -> {
                        pending.finishCancellation(false);
                        if (isStillCurrent(stillCurrent)) {
                            safeAccept(failed, "relocation-admission-cancel-dispatch-failed");
                        }
                    }
            ));
        } catch (RuntimeException | LinkageError exception) {
            pending.finishCancellation(false);
            safeAccept(failed, "relocation-admission-cancel-failed");
        }
    }

    void commit(PendingRelocation pending,
                Dispatcher dispatcher,
                BiConsumer<CompanionRelocationAdmissionService.Decision, Throwable> completed) {
        CompanionRelocationAdmissionService.Admission admission = pending.beginCommit();
        CompanionRelocationAdmissionService service = authority;
        if (admission == null && pending.admissionCommitInProgress()) {
            return;
        }
        if (admission == null || service == null) {
            pending.finishCommit();
            safeComplete(completed, null,
                    new IllegalStateException("Relocation admission was unavailable."));
            return;
        }
        try {
            service.commit(admission).whenComplete((decision, failure) -> dispatch(
                    dispatcher,
                    () -> finishCommit(pending, decision, failure, completed),
                    () -> finishCommit(
                            pending, decision,
                            failure == null
                                    ? new IllegalStateException("Relocation commit dispatch failed.")
                                    : failure,
                            completed
                    )
            ));
        } catch (RuntimeException | LinkageError exception) {
            finishCommit(pending, null, exception, completed);
        }
    }

    private static void finishPreparation(
            PendingRelocation pending,
            CompanionRelocationAdmissionService service,
            @Nullable CompanionRelocationAdmissionService.Decision decision,
            @Nullable Throwable failure,
            Dispatcher dispatcher,
            BooleanSupplier stillCurrent,
            Runnable ready,
            Consumer<String> retrying,
            Consumer<String> denied
    ) {
        if (!isStillCurrent(stillCurrent)) {
            pending.finishUnclaimedPreparation(false);
            cancelOrphan(service, decision == null ? null : decision.admission());
            return;
        }
        if (failure != null || decision == null
                || decision.status() != CompanionRelocationAdmissionService.Status.RESERVED
                || decision.admission() == null) {
            boolean retryable = failure == null
                    && CommandRelocationAdmissionRetryPolicy.shouldRetry(decision);
            pending.finishUnclaimedPreparation(retryable);
            safeAccept(retryable ? retrying : denied, decision == null
                    ? "relocation-admission-failed" : decision.reason());
            return;
        }
        if (!pending.installReservedAdmission(decision)) {
            pending.terminateAdmission();
            cancelOrphan(service, decision.admission());
            safeAccept(denied, "relocation-admission-install-failed");
            return;
        }
        try {
            ready.run();
        } catch (RuntimeException | LinkageError exception) {
            CompanionRelocationAdmissionService.Admission admission = pending.beginCancellation();
            closeClaimFailure(
                    pending, service, admission, dispatcher, stillCurrent, denied,
                    "relocation-admission-ready-callback-failed"
            );
        }
    }

    private static void closePreparationAfterDispatchFailure(
            PendingRelocation pending,
            CompanionRelocationAdmissionService service,
            @Nullable CompanionRelocationAdmissionService.Decision decision,
            BooleanSupplier stillCurrent,
            Consumer<String> denied
    ) {
        pending.finishUnclaimedPreparation(false);
        cancelOrphan(service, decision == null ? null : decision.admission());
        if (isStillCurrent(stillCurrent)) {
            safeAccept(denied, "relocation-admission-prepare-dispatch-failed");
        }
    }

    private static void closeClaimFailure(
            PendingRelocation pending,
            CompanionRelocationAdmissionService service,
            @Nullable CompanionRelocationAdmissionService.Admission admission,
            Dispatcher dispatcher,
            BooleanSupplier stillCurrent,
            Consumer<String> denied,
            String reason
    ) {
        if (admission == null) {
            pending.terminateAdmission();
            if (isStillCurrent(stillCurrent)) {
                safeAccept(denied, reason);
            }
            return;
        }
        try {
            service.cancel(admission).whenComplete((decision, failure) -> dispatch(
                    dispatcher,
                    () -> {
                        pending.finishCancellation(false);
                        if (isStillCurrent(stillCurrent)) {
                            safeAccept(denied, reason);
                        }
                    },
                    () -> {
                        pending.finishCancellation(false);
                        if (isStillCurrent(stillCurrent)) {
                            safeAccept(denied, reason);
                        }
                    }
            ));
        } catch (RuntimeException | LinkageError exception) {
            pending.finishCancellation(false);
            if (isStillCurrent(stillCurrent)) {
                safeAccept(denied, reason);
            }
        }
    }

    private static void finishCancellation(
            PendingRelocation pending,
            boolean retry,
            @Nullable CompanionRelocationAdmissionService.Decision decision,
            @Nullable Throwable failure,
            BooleanSupplier stillCurrent,
            @Nullable Runnable continuation,
            Consumer<String> failed
    ) {
        boolean canceled = failure == null && decision != null
                && decision.status() == CompanionRelocationAdmissionService.Status.CANCELED;
        pending.finishCancellation(retry && canceled);
        if (retry && canceled && isStillCurrent(stillCurrent) && continuation != null) {
            safeRun(continuation);
        } else if (retry && isStillCurrent(stillCurrent)) {
            safeAccept(failed, "relocation-admission-cancel-failed");
        }
    }

    private static void finishCommit(
            PendingRelocation pending,
            @Nullable CompanionRelocationAdmissionService.Decision decision,
            @Nullable Throwable failure,
            BiConsumer<CompanionRelocationAdmissionService.Decision, Throwable> completed
    ) {
        pending.finishCommit();
        safeComplete(completed, decision, failure);
    }

    private static void cancelOrphan(
            CompanionRelocationAdmissionService service,
            @Nullable CompanionRelocationAdmissionService.Admission admission
    ) {
        if (admission == null) {
            return;
        }
        try {
            service.cancel(admission);
        } catch (RuntimeException | LinkageError ignored) {
            // The capability lease remains the final backstop when its owning world is unavailable.
        }
    }

    private static void dispatch(Dispatcher dispatcher,
                                  Runnable completion,
                                  Runnable rejected) {
        try {
            dispatcher.dispatch(completion, rejected);
        } catch (RuntimeException | LinkageError exception) {
            safeRun(rejected);
        }
    }

    private static void safeRun(@Nullable Runnable callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Admission state is already terminal; callbacks are deliberately best effort.
        }
    }

    private static boolean isStillCurrent(BooleanSupplier callback) {
        try {
            return callback.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void safeAccept(Consumer<String> callback, String value) {
        try {
            callback.accept(value);
        } catch (RuntimeException | LinkageError ignored) {
            // Admission state is already terminal; callbacks are deliberately best effort.
        }
    }

    private static void safeComplete(
            BiConsumer<CompanionRelocationAdmissionService.Decision, Throwable> callback,
            @Nullable CompanionRelocationAdmissionService.Decision decision,
            @Nullable Throwable failure
    ) {
        try {
            callback.accept(decision, failure);
        } catch (RuntimeException | LinkageError ignored) {
            // Admission state is already terminal; callbacks are deliberately best effort.
        }
    }

    @FunctionalInterface
    interface Dispatcher {
        void dispatch(Runnable task, Runnable rejected);
    }
}
