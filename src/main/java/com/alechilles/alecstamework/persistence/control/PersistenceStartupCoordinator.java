package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Runs the one startup DAG and derives all public mutation admission from it.
 *
 * <p>Features cannot publish independent readiness flags. A bounded quarantine
 * changes only matching operation scopes; storage or startup ambiguity changes
 * the entire coordinator to global read-only.</p>
 */
public final class PersistenceStartupCoordinator
        implements PersistenceOperationAdmissionGate, AutoCloseable {
    private enum Mode {
        STARTING,
        ACTIVE,
        GLOBAL_READ_ONLY,
        CLOSED
    }

    private final Object monitor = new Object();
    private final PersistenceFeatureRegistry registry;
    private final Map<PersistenceStartupNode, PersistenceStartupAction> actions;
    private final java.util.EnumSet<PersistenceStartupNode> completed =
            java.util.EnumSet.noneOf(PersistenceStartupNode.class);
    private final Map<OperationScope, String> quarantines = new java.util.HashMap<>();
    private Mode mode = Mode.STARTING;
    private PersistenceStartupNode running;
    private PersistenceStartupNode deferred;
    private PersistenceStartupNode failed;
    private String detail;
    private CompletableFuture<PersistenceStartupReport> activeAdvance;

    public PersistenceStartupCoordinator(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull Map<PersistenceStartupNode, PersistenceStartupAction> actions
    ) {
        if (registry == null || actions == null
                || !actions.keySet().equals(Set.of(
                PersistenceStartupNode.values()
        )) || actions.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Every startup node requires exactly one action"
            );
        }
        this.registry = registry;
        this.actions = Map.copyOf(actions);
    }

    /**
     * Advances until every node completes, evidence defers a node, or a node fails.
     * Concurrent callers share the same in-flight advance.
     */
    @Nonnull
    public CompletionStage<PersistenceStartupReport> advance() {
        CompletableFuture<PersistenceStartupReport> future;
        synchronized (monitor) {
            if (activeAdvance != null) {
                return activeAdvance;
            }
            if (mode != Mode.STARTING) {
                return CompletableFuture.completedFuture(reportLocked());
            }
            future = new CompletableFuture<>();
            activeAdvance = future;
        }
        runNext(future);
        return future;
    }

    /** Returns a stable diagnostic snapshot without advancing startup. */
    @Nonnull
    public PersistenceStartupReport report() {
        synchronized (monitor) {
            return reportLocked();
        }
    }

    /** Returns readiness for one feature without collapsing bounded quarantine. */
    @Nonnull
    public PersistenceReadinessLevel readiness(
            @Nonnull PersistenceFeatureId featureId
    ) {
        synchronized (monitor) {
            return deriveReadiness(registry.requireFeature(featureId), List.of());
        }
    }

    /** Blocks one exact recoverable scope without disabling unrelated operations. */
    public void quarantine(
            @Nonnull OperationScope scope,
            @Nonnull String reason
    ) {
        if (scope == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Quarantine scope and reason are required"
            );
        }
        if (scope.type() == OperationScopeType.GLOBAL) {
            enterGlobalReadOnly(reason);
            return;
        }
        synchronized (monitor) {
            if (mode != Mode.CLOSED) {
                quarantines.put(scope, reason.trim());
            }
        }
    }

    /** Releases one exact scope after recovery proves it safe. */
    public void releaseQuarantine(@Nonnull OperationScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("Quarantine scope is required");
        }
        synchronized (monitor) {
            quarantines.remove(scope);
        }
    }

    /** Fails all mutation admission when evidence cannot be safely bounded. */
    public void enterGlobalReadOnly(@Nonnull String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Global read-only reason is required");
        }
        CompletableFuture<PersistenceStartupReport> pending;
        synchronized (monitor) {
            if (mode == Mode.CLOSED) {
                return;
            }
            mode = Mode.GLOBAL_READ_ONLY;
            running = null;
            deferred = null;
            detail = reason.trim();
            pending = activeAdvance;
            activeAdvance = null;
            if (pending != null) {
                pending.complete(reportLocked());
            }
        }
    }

    /** Enforces descriptor scope policy, readiness, and exact quarantines. */
    @Override
    public void requireAdmission(
            @Nonnull OperationKind kind,
            @Nonnull String featureScope,
            @Nonnull List<OperationScope> participants
    ) {
        if (kind == null || featureScope == null || featureScope.isBlank()
                || participants == null
                || participants.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Complete operation admission context is required"
            );
        }
        PersistenceFeatureDescriptor descriptor =
                registry.requireOperation(kind);
        requireDeclaredScopes(descriptor, kind, participants);
        java.util.ArrayList<OperationScope> candidates =
                new java.util.ArrayList<>(participants);
        candidates.add(new OperationScope(
                OperationScopeType.FEATURE,
                featureScope
        ));
        PersistenceReadinessLevel readiness;
        synchronized (monitor) {
            readiness = deriveReadiness(descriptor, candidates);
        }
        if (readiness != PersistenceReadinessLevel.MUTATION_READY) {
            throw new IllegalStateException(
                    "persistence_mutation_not_admitted:"
                            + readiness.name().toLowerCase(java.util.Locale.ROOT)
            );
        }
    }

    /** Permanently closes public admission; accepted workflows may still drain. */
    @Override
    public void close() {
        synchronized (monitor) {
            mode = Mode.CLOSED;
            running = null;
            deferred = null;
            CompletableFuture<PersistenceStartupReport> pending = activeAdvance;
            activeAdvance = null;
            if (pending != null) {
                pending.complete(reportLocked());
            }
        }
    }

    private void runNext(CompletableFuture<PersistenceStartupReport> future) {
        PersistenceStartupNode node;
        synchronized (monitor) {
            if (mode != Mode.STARTING || activeAdvance != future) {
                future.complete(reportLocked());
                return;
            }
            node = nextNode();
            if (node == null) {
                mode = Mode.ACTIVE;
                activeAdvance = null;
                future.complete(reportLocked());
                return;
            }
            if (!completed.containsAll(node.dependencies())) {
                failLocked(node, "startup_dependency_incomplete");
                activeAdvance = null;
                future.complete(reportLocked());
                return;
            }
            running = node;
            deferred = null;
        }
        CompletionStage<PersistenceStartupAction.Result> execution;
        try {
            execution = actions.get(node).execute();
            if (execution == null) {
                throw new IllegalStateException("startup_action_returned_null");
            }
        } catch (Throwable failure) {
            finishNode(future, node, null, failure);
            return;
        }
        execution.whenComplete(
                (result, failure) -> finishNode(future, node, result, failure)
        );
    }

    private void finishNode(
            CompletableFuture<PersistenceStartupReport> future,
            PersistenceStartupNode node,
            PersistenceStartupAction.Result result,
            Throwable failure
    ) {
        boolean continueStartup = false;
        synchronized (monitor) {
            if (mode != Mode.STARTING || activeAdvance != future) {
                future.complete(reportLocked());
                return;
            }
            running = null;
            if (failure != null || result == null) {
                failLocked(node, failureDetail(failure));
                activeAdvance = null;
                future.complete(reportLocked());
            } else if (result == PersistenceStartupAction.Result.DEFERRED) {
                deferred = node;
                detail = "startup_evidence_deferred";
                activeAdvance = null;
                future.complete(reportLocked());
            } else {
                completed.add(node);
                deferred = null;
                detail = null;
                continueStartup = true;
            }
        }
        if (continueStartup) {
            runNext(future);
        }
    }

    private PersistenceStartupNode nextNode() {
        for (PersistenceStartupNode node : PersistenceStartupNode.values()) {
            if (!completed.contains(node)) {
                return node;
            }
        }
        return null;
    }

    private void failLocked(PersistenceStartupNode node, String failureDetail) {
        mode = Mode.GLOBAL_READ_ONLY;
        failed = node;
        deferred = null;
        detail = failureDetail;
    }

    private String failureDetail(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null) {
            return "startup_action_returned_null";
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ":" + message);
    }

    private PersistenceStartupReport reportLocked() {
        return new PersistenceStartupReport(
                Set.copyOf(completed),
                running,
                deferred,
                failed,
                detail,
                deriveGlobalReadiness()
        );
    }

    private PersistenceReadinessLevel deriveGlobalReadiness() {
        if (mode == Mode.CLOSED) {
            return PersistenceReadinessLevel.CLOSED;
        }
        if (mode == Mode.GLOBAL_READ_ONLY) {
            return PersistenceReadinessLevel.GLOBAL_READ_ONLY;
        }
        if (mode == Mode.ACTIVE) {
            return PersistenceReadinessLevel.MUTATION_READY;
        }
        return deriveStartupReadiness(true);
    }

    private PersistenceReadinessLevel deriveReadiness(
            PersistenceFeatureDescriptor descriptor,
            List<OperationScope> candidates
    ) {
        PersistenceReadinessLevel global = deriveGlobalReadiness();
        if (global == PersistenceReadinessLevel.CLOSED
                || global == PersistenceReadinessLevel.GLOBAL_READ_ONLY) {
            return global;
        }
        if (candidates.stream().anyMatch(quarantines::containsKey)) {
            return PersistenceReadinessLevel.QUARANTINED;
        }
        if (mode == Mode.ACTIVE
                && completed.containsAll(descriptor.readinessEvidence())) {
            return PersistenceReadinessLevel.MUTATION_READY;
        }
        boolean needsWorld = descriptor.readinessEvidence()
                .contains(PersistenceStartupNode.RECONCILE_WORLD);
        return deriveStartupReadiness(needsWorld);
    }

    private PersistenceReadinessLevel deriveStartupReadiness(
            boolean needsWorld
    ) {
        if (!completed.contains(PersistenceStartupNode.LOAD_CANONICAL)) {
            return PersistenceReadinessLevel.CLOSED;
        }
        if (!completed.contains(PersistenceStartupNode.RECOVER_OPERATIONS)) {
            return running == PersistenceStartupNode.RECOVER_OPERATIONS
                    ? PersistenceReadinessLevel.RECOVERING
                    : PersistenceReadinessLevel.CANONICAL_READ_ONLY;
        }
        if (!completed.contains(PersistenceStartupNode.BUILD_PROJECTIONS)) {
            return PersistenceReadinessLevel.RECOVERING;
        }
        if (needsWorld
                && (deferred == PersistenceStartupNode.WAIT_WORLD_EVIDENCE
                || deferred == PersistenceStartupNode.RECONCILE_WORLD)) {
            return PersistenceReadinessLevel.WORLD_EVIDENCE_PENDING;
        }
        return PersistenceReadinessLevel.PROJECTION_READY;
    }

    private void requireDeclaredScopes(
            PersistenceFeatureDescriptor descriptor,
            OperationKind kind,
            List<OperationScope> participants
    ) {
        Set<OperationScopeType> actual = participants.stream()
                .map(OperationScope::type)
                .filter(type -> type != OperationScopeType.OPERATION)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<OperationScopeType> expected =
                descriptor.operationScopes().get(kind);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "operation_scope_policy_mismatch:" + kind
            );
        }
    }
}
