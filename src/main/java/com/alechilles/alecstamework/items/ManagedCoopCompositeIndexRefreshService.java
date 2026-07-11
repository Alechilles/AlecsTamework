package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * All-or-nothing trust boundary for the resident and lifecycle-operation runtime indexes.
 *
 * <p>Both component refreshes are attempted on every call. If either rejects, throws, or leaves
 * its owned index untrusted, this service revokes trust on both indexes through their public
 * rebuild APIs. The indexes retain their last immutable evidence and revisions, but consumers can
 * no longer combine a fresh projection with a stale trusted counterpart.</p>
 */
public final class ManagedCoopCompositeIndexRefreshService {
    public enum RefreshStatus {
        REFRESHED,
        REJECTED
    }

    public enum ComponentStatus {
        REFRESHED,
        REJECTED,
        EXCEPTION
    }

    /** Typed outcome for one component refresh attempt. */
    public record ComponentResult(@Nonnull ComponentStatus status,
                                  long revision,
                                  @Nullable String detail) {
        public ComponentResult {
            Objects.requireNonNull(status, "status");
        }

        public boolean refreshed() {
            return status == ComponentStatus.REFRESHED;
        }
    }

    /** Aggregate outcome with the revisions that remain visible after trust reconciliation. */
    public record RefreshResult(@Nonnull RefreshStatus status,
                                long residentRevision,
                                long operationRevision,
                                @Nonnull ComponentResult residentResult,
                                @Nonnull ComponentResult operationResult,
                                @Nullable String detail) {
        public RefreshResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(residentResult, "residentResult");
            Objects.requireNonNull(operationResult, "operationResult");
        }

        public boolean refreshed() {
            return status == RefreshStatus.REFRESHED;
        }
    }

    private final ManagedCoopResidentIndex residentIndex;
    private final ManagedCoopLifecycleOperationIndex operationIndex;
    private final RefreshAction residentRefresh;
    private final RefreshAction operationRefresh;
    private final AtomicBoolean trusted = new AtomicBoolean();
    private volatile long publishedResidentRevision;
    private volatile long publishedOperationRevision;

    public ManagedCoopCompositeIndexRefreshService(
            @Nonnull ManagedCoopResidentIndexRefreshService residentRefreshService,
            @Nonnull ManagedCoopLifecycleOperationIndexRefreshService operationRefreshService,
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex) {
        this(
                residentIndex,
                operationIndex,
                residentAction(residentRefreshService),
                operationAction(operationRefreshService)
        );
    }

    ManagedCoopCompositeIndexRefreshService(
            @Nonnull ManagedCoopResidentIndex residentIndex,
            @Nonnull ManagedCoopLifecycleOperationIndex operationIndex,
            @Nonnull RefreshAction residentRefresh,
            @Nonnull RefreshAction operationRefresh) {
        this.residentIndex = Objects.requireNonNull(residentIndex, "residentIndex");
        this.operationIndex = Objects.requireNonNull(operationIndex, "operationIndex");
        this.residentRefresh = Objects.requireNonNull(residentRefresh, "residentRefresh");
        this.operationRefresh = Objects.requireNonNull(operationRefresh, "operationRefresh");
    }

    /** Attempts both refreshes and publishes aggregate trust only when both complete successfully. */
    @Nonnull
    public synchronized RefreshResult refresh() {
        trusted.set(false);
        revokeBothTrusts();
        ComponentResult resident = invoke("resident", residentRefresh, residentRevision());
        ComponentResult operations = invoke("operations", operationRefresh, operationRevision());

        String rejection = rejectionDetail(resident, operations);
        if (rejection == null && residentIndex.isTrusted() && operationIndex.isTrusted()) {
            publishedResidentRevision = residentRevision();
            publishedOperationRevision = operationRevision();
            trusted.set(true);
            return new RefreshResult(
                    RefreshStatus.REFRESHED,
                    residentRevision(),
                    operationRevision(),
                    resident,
                    operations,
                    null
            );
        }
        if (rejection == null) {
            rejection = trustFailureDetail();
        }
        String revocationFailure = revokeBothTrusts();
        if (revocationFailure != null) {
            rejection = rejection + ";" + revocationFailure;
        }
        return new RefreshResult(
                RefreshStatus.REJECTED,
                residentRevision(),
                operationRevision(),
                resident,
                operations,
                rejection
        );
    }

    /** Returns whether the latest complete paired refresh published one coherent trust epoch. */
    public boolean isTrusted() {
        return trusted.get()
                && residentIndex.isTrusted()
                && operationIndex.isTrusted()
                && residentRevision() == publishedResidentRevision
                && operationRevision() == publishedOperationRevision;
    }

    /**
     * Runs the paired refresh while exposing the resident revision shape used by lifecycle
     * coordinators. A REFRESHED result still means both indexes published coherently.
     */
    @Nonnull
    public ManagedCoopResidentIndexRefreshService.RefreshResult refreshForLifecycleMutation() {
        RefreshResult result = refresh();
        return new ManagedCoopResidentIndexRefreshService.RefreshResult(
                result.refreshed()
                        ? ManagedCoopResidentIndexRefreshService.RefreshStatus.REFRESHED
                        : ManagedCoopResidentIndexRefreshService.RefreshStatus.REJECTED,
                result.residentRevision(),
                result.detail()
        );
    }

    @Nonnull
    private ComponentResult invoke(String label,
                                   RefreshAction action,
                                   long revisionBeforeAttempt) {
        try {
            ComponentResult result = action.refresh();
            if (result == null) {
                return new ComponentResult(
                        ComponentStatus.EXCEPTION,
                        revisionBeforeAttempt,
                        label + "_refresh_returned_null"
                );
            }
            return result;
        } catch (RuntimeException exception) {
            return new ComponentResult(
                    ComponentStatus.EXCEPTION,
                    currentRevision(label, revisionBeforeAttempt),
                    label + "_refresh_exception:" + exceptionDetail(exception)
            );
        }
    }

    @Nullable
    private String rejectionDetail(ComponentResult resident, ComponentResult operations) {
        ArrayList<String> failures = new ArrayList<>(2);
        addFailure(failures, "resident", resident);
        addFailure(failures, "operations", operations);
        return failures.isEmpty() ? null : String.join(";", failures);
    }

    private void addFailure(List<String> failures, String label, ComponentResult result) {
        if (result.refreshed()) {
            return;
        }
        String detail = result.detail();
        failures.add(label + ":" + result.status().name().toLowerCase()
                + (detail == null || detail.isBlank() ? "" : ":" + detail));
    }

    @Nonnull
    private String trustFailureDetail() {
        if (!residentIndex.isTrusted() && !operationIndex.isTrusted()) {
            return "both_indexes_untrusted_after_refresh";
        }
        return residentIndex.isTrusted()
                ? "operation_index_untrusted_after_refresh"
                : "resident_index_untrusted_after_refresh";
    }

    @Nullable
    private String revokeBothTrusts() {
        ArrayList<String> failures = new ArrayList<>(2);
        try {
            residentIndex.revokeTrust();
        } catch (RuntimeException exception) {
            failures.add("resident_trust_revocation_exception:" + exceptionDetail(exception));
        }
        try {
            operationIndex.revokeTrust();
        } catch (RuntimeException exception) {
            failures.add("operation_trust_revocation_exception:" + exceptionDetail(exception));
        }
        return failures.isEmpty() ? null : String.join(";", failures);
    }

    private long currentRevision(String label, long fallback) {
        if ("resident".equals(label)) {
            return residentRevision();
        }
        if ("operations".equals(label)) {
            return operationRevision();
        }
        return fallback;
    }

    private long residentRevision() {
        return residentIndex.snapshot().revision();
    }

    private long operationRevision() {
        return operationIndex.snapshot().revision();
    }

    @Nonnull
    private static RefreshAction residentAction(
            @Nonnull ManagedCoopResidentIndexRefreshService service) {
        Objects.requireNonNull(service, "residentRefreshService");
        return () -> {
            ManagedCoopResidentIndexRefreshService.RefreshResult result = service.refresh();
            return new ComponentResult(
                    result.refreshed() ? ComponentStatus.REFRESHED : ComponentStatus.REJECTED,
                    result.revision(),
                    result.detail()
            );
        };
    }

    @Nonnull
    private static RefreshAction operationAction(
            @Nonnull ManagedCoopLifecycleOperationIndexRefreshService service) {
        Objects.requireNonNull(service, "operationRefreshService");
        return () -> {
            ManagedCoopLifecycleOperationIndexRefreshService.RefreshResult result = service.refresh();
            return new ComponentResult(
                    result.refreshed() ? ComponentStatus.REFRESHED : ComponentStatus.REJECTED,
                    result.revision(),
                    result.detail()
            );
        };
    }

    @Nonnull
    private static String exceptionDetail(@Nonnull RuntimeException exception) {
        String detail = exception.getMessage();
        return detail == null || detail.isBlank()
                ? exception.getClass().getSimpleName()
                : detail;
    }

    @FunctionalInterface
    interface RefreshAction {
        @Nullable
        ComponentResult refresh();
    }
}
