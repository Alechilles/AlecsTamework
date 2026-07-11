package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Maps authoritative index state into compatibility, V2, and diagnostics API views. */
final class PopulationPolicyViewService {
    private final OwnerPopulationIndex ownerIndex;
    private final ClaimAdmissionService claimAdmissionService;
    private final ClaimLookupMetrics lookupMetrics;
    private final Supplier<CompanionAdmissionPolicyResolver.Policy> ownerPolicySupplier;
    private final Supplier<CompanionAdmissionPolicyResolver.Policy> diagnosticsPolicySupplier;
    private volatile Supplier<PopulationDiagnosticsView.ReconciliationView> reconciliationSupplier =
            PopulationDiagnosticsView.ReconciliationView::unknown;

    PopulationPolicyViewService(@Nonnull OwnerPopulationIndex ownerIndex,
                                @Nonnull ClaimAdmissionService claimAdmissionService,
                                @Nonnull ClaimLookupMetrics lookupMetrics,
                                @Nonnull CompanionAdmissionPolicyResolver policyResolver) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.claimAdmissionService = Objects.requireNonNull(claimAdmissionService, "claimAdmissionService");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
        CompanionAdmissionPolicyResolver resolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.ownerPolicySupplier = () -> resolver.resolve(OwnerPopulationOperation.NEW_OWNERSHIP, false);
        this.diagnosticsPolicySupplier = () -> resolver.resolve(OwnerPopulationOperation.BREEDING, true);
    }

    PopulationPolicyViewService(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimAdmissionService claimAdmissionService,
            @Nonnull ClaimLookupMetrics lookupMetrics,
            @Nonnull Supplier<CompanionAdmissionPolicyResolver.Policy> policySupplier
    ) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.claimAdmissionService = Objects.requireNonNull(claimAdmissionService, "claimAdmissionService");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
        Supplier<CompanionAdmissionPolicyResolver.Policy> supplier =
                Objects.requireNonNull(policySupplier, "policySupplier");
        this.ownerPolicySupplier = supplier;
        this.diagnosticsPolicySupplier = supplier;
    }

    PopulationPolicyViewService(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull ClaimAdmissionService claimAdmissionService,
            @Nonnull ClaimLookupMetrics lookupMetrics,
            @Nonnull Supplier<CompanionAdmissionPolicyResolver.Policy> ownerPolicySupplier,
            @Nonnull Supplier<CompanionAdmissionPolicyResolver.Policy> diagnosticsPolicySupplier
    ) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.claimAdmissionService = Objects.requireNonNull(claimAdmissionService, "claimAdmissionService");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
        this.ownerPolicySupplier = Objects.requireNonNull(ownerPolicySupplier, "ownerPolicySupplier");
        this.diagnosticsPolicySupplier = Objects.requireNonNull(
                diagnosticsPolicySupplier, "diagnosticsPolicySupplier"
        );
    }

    void setReconciliationSupplier(
            @Nonnull Supplier<PopulationDiagnosticsView.ReconciliationView> supplier
    ) {
        reconciliationSupplier = Objects.requireNonNull(supplier, "supplier");
    }

    @Nonnull
    PopulationCapDecisionView evaluateLegacy(@Nullable UUID ownerUuid) {
        if (ownerUuid == null) {
            return new PopulationCapDecisionView(
                    null, true, false, 0, 0, Integer.MAX_VALUE,
                    OwnerPopulationLimitScope.PER_WORLD.name(), "owner-cap-no-owner"
            );
        }
        CompanionAdmissionPolicyResolver.Policy policy = ownerPolicy();
        int limit = Math.max(0, policy.limit());
        if (policy.scope() == OwnerPopulationLimitScope.PER_WORLD) {
            return new PopulationCapDecisionView(
                    ownerUuid,
                    false,
                    limit > 0,
                    limit,
                    -1,
                    0,
                    policy.scope().name(),
                    "owner-cap-world-context-required"
            );
        }
        OwnerPopulationReadiness readiness = ownerIndex.readiness(policy.scope());
        if (limit > 0 && readiness != OwnerPopulationReadiness.READY) {
            return new PopulationCapDecisionView(
                    ownerUuid, false, true, limit, -1, 0, policy.scope().name(),
                    readinessReason(readiness)
            );
        }
        OwnerPopulationCounts counts = ownerIndex.counts(ownerUuid, null);
        long current = counts.globalCommitted() + counts.globalPending();
        int safeCurrent = saturatingInt(current);
        if (limit <= 0) {
            return new PopulationCapDecisionView(
                    ownerUuid, true, false, 0, safeCurrent, Integer.MAX_VALUE,
                    policy.scope().name(), "owner-cap-disabled"
            );
        }
        int headroom = Math.max(0, limit - safeCurrent);
        return new PopulationCapDecisionView(
                ownerUuid,
                headroom > 0,
                true,
                limit,
                safeCurrent,
                headroom,
                policy.scope().name(),
                headroom > 0 ? "owner-cap-allow" : "owner-cap-reached"
        );
    }

    @Nonnull
    OwnerPopulationCapDecisionViewV2 evaluate(@Nonnull OwnerPopulationCapRequestV2 request) {
        Objects.requireNonNull(request, "request");
        CompanionAdmissionPolicyResolver.Policy policy = ownerPolicy();
        OwnerPopulationLimitScope scope = policy.scope();
        int limit = Math.max(0, policy.limit());
        boolean capEnabled = limit > 0;
        OwnerPopulationReadiness readiness = ownerIndex.readiness(scope);
        if (scope == OwnerPopulationLimitScope.PER_WORLD && request.worldName() == null) {
            return new OwnerPopulationCapDecisionViewV2(
                    request.ownerUuid(), null, request.requestedSlots(), !capEnabled, capEnabled,
                    false, limit, OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT,
                    OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT,
                    capEnabled ? 0L : Long.MAX_VALUE, mapScope(scope), mapReadiness(readiness),
                    "owner-cap-world-context-required"
            );
        }
        OwnerPopulationCounts counts = ownerIndex.counts(request.ownerUuid(), request.worldName());
        long committed = scope == OwnerPopulationLimitScope.GLOBAL
                ? counts.globalCommitted() : counts.worldCommitted();
        long pending = scope == OwnerPopulationLimitScope.GLOBAL
                ? counts.globalPending() : counts.worldPending();
        boolean authoritative = readiness == OwnerPopulationReadiness.READY;
        long headroom = capEnabled ? remaining(limit, committed, pending) : Long.MAX_VALUE;
        boolean allowed = !capEnabled || (authoritative && request.requestedSlots() <= headroom);
        String reason = !capEnabled
                ? "owner-cap-disabled"
                : !authoritative ? readinessReason(readiness)
                : allowed ? "owner-cap-allow" : "owner-cap-reached";
        return new OwnerPopulationCapDecisionViewV2(
                request.ownerUuid(), request.worldName(), request.requestedSlots(), allowed,
                capEnabled, authoritative, limit, committed, pending, headroom,
                mapScope(scope), mapReadiness(readiness), reason
        );
    }

    @Nonnull
    PopulationDiagnosticsView diagnostics() {
        CompanionAdmissionPolicyResolver.Policy policy = diagnosticsPolicy();
        OwnerPopulationMetrics.Snapshot owner = ownerIndex.metrics(policy.scope(), policy.limit());
        ClaimAdmissionMetrics.Snapshot claim = claimAdmissionService.metrics();
        ClaimLookupMetrics.Snapshot lookup = lookupMetrics.snapshot();
        return new PopulationDiagnosticsView(
                new PopulationDiagnosticsView.ReadinessView(
                        owner.globalReadiness().name(),
                        owner.perWorldReadiness().name(),
                        claim.readiness().name()
                ),
                new PopulationDiagnosticsView.CountView(
                        owner.profileCount(),
                        owner.committedGlobalSlots(),
                        owner.pendingGlobalSlots(),
                        claim.committedOccupiedProfiles(),
                        claim.pendingSlots(),
                        owner.overCapBuckets(),
                        claim.observedOverCapClaimBuckets()
                ),
                reservations(owner),
                reservations(claim),
                lookups(lookup, claim, policy.claimContext()),
                reconciliation()
        );
    }

    @Nonnull
    private CompanionAdmissionPolicyResolver.Policy ownerPolicy() {
        return Objects.requireNonNull(ownerPolicySupplier.get(), "owner population policy");
    }

    @Nonnull
    private CompanionAdmissionPolicyResolver.Policy diagnosticsPolicy() {
        return Objects.requireNonNull(diagnosticsPolicySupplier.get(), "diagnostics population policy");
    }

    @Nonnull
    private PopulationDiagnosticsView.ReconciliationView reconciliation() {
        try {
            PopulationDiagnosticsView.ReconciliationView view = reconciliationSupplier.get();
            return view == null ? PopulationDiagnosticsView.ReconciliationView.unknown() : view;
        } catch (RuntimeException | LinkageError ignored) {
            return PopulationDiagnosticsView.ReconciliationView.unknown();
        }
    }

    @Nonnull
    private static PopulationDiagnosticsView.ReservationMetricsView reservations(
            @Nonnull OwnerPopulationMetrics.Snapshot metrics
    ) {
        return new PopulationDiagnosticsView.ReservationMetricsView(
                metrics.reservationsCreated(), metrics.reservationsCommitted(),
                metrics.reservationsCanceled(), metrics.reservationsExpired(), 0L
        );
    }

    @Nonnull
    private static PopulationDiagnosticsView.ReservationMetricsView reservations(
            @Nonnull ClaimAdmissionMetrics.Snapshot metrics
    ) {
        return new PopulationDiagnosticsView.ReservationMetricsView(
                metrics.reservationsCreated(), metrics.reservationsCommitted(),
                metrics.reservationsCanceled(), metrics.reservationsExpired(),
                metrics.reservationsInvalidated()
        );
    }

    @Nonnull
    private static PopulationDiagnosticsView.LookupMetricsView lookups(
            @Nonnull ClaimLookupMetrics.Snapshot lookup,
            @Nonnull ClaimAdmissionMetrics.Snapshot claim,
            @Nonnull ClaimPolicyContext currentContext
    ) {
        return new PopulationDiagnosticsView.LookupMetricsView(
                lookup.sessions(), lookup.requests(), lookup.uniqueChunks(), lookup.providerCalls(),
                lookup.cacheHits(), lookup.providerStateChanges(), claim.snapshotCount(),
                claim.totalSnapshotNanos(), claim.lastSnapshotNanos(),
                lookup.lastProviderCallNanos(), provider(currentContext)
        );
    }

    private static PopulationDiagnosticsView.ProviderContextView provider(
            @Nonnull ClaimPolicyContext context
    ) {
        return new PopulationDiagnosticsView.ProviderContextView(
                context.requestedProvider() == null ? null : context.requestedProvider().name(),
                context.resolvedProvider() == null ? null : context.resolvedProvider().name(),
                context.providerId(), context.state().name(), context.reason(), context.pluginVersion(),
                generationToken(context.providerGeneration()), context.settingsRevision()
        );
    }

    @Nonnull
    static String generationToken(
            @Nonnull com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration generation
    ) {
        return generation.pluginInstanceToken() + ":" + generation.classLoaderToken()
                + ":" + generation.reflectedContractGeneration();
    }

    private static long remaining(int limit, long committed, long pending) {
        long remaining = Math.max(0L, (long) limit - committed);
        return Math.max(0L, remaining - pending);
    }

    private static int saturatingInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    @Nonnull
    private static String readinessReason(OwnerPopulationReadiness readiness) {
        return "owner-population-" + readiness.name().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static OwnerPopulationCapDecisionViewV2.Scope mapScope(OwnerPopulationLimitScope scope) {
        return scope == OwnerPopulationLimitScope.GLOBAL
                ? OwnerPopulationCapDecisionViewV2.Scope.GLOBAL
                : OwnerPopulationCapDecisionViewV2.Scope.PER_WORLD;
    }

    @Nonnull
    private static OwnerPopulationCapDecisionViewV2.Readiness mapReadiness(
            OwnerPopulationReadiness readiness
    ) {
        return OwnerPopulationCapDecisionViewV2.Readiness.valueOf(readiness.name());
    }
}
