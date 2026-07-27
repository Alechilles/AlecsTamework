package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Routes world and owner lifecycle signals into exact bonded reconciliation. */
public final class BondedCompanionWorldLifecycleObserver {
    private final BondedCompanionProjectionService projections;
    private final ProjectionSource source;
    private final ReconciliationCooldownResolver cooldowns;
    private final ReconciliationListener listener;

    public BondedCompanionWorldLifecycleObserver(
            @Nonnull BondedCompanionProjectionService projections,
            @Nonnull ProjectionSource source
    ) {
        this(projections, source, (lease, nowMs) -> OptionalLong.of(0L),
                (lease, cause, result) -> { });
    }

    public BondedCompanionWorldLifecycleObserver(
            @Nonnull BondedCompanionProjectionService projections,
            @Nonnull ProjectionSource source,
            @Nonnull ReconciliationListener listener
    ) {
        this(projections, source, (lease, nowMs) -> OptionalLong.of(0L),
                listener);
    }

    public BondedCompanionWorldLifecycleObserver(
            @Nonnull BondedCompanionProjectionService projections,
            @Nonnull ProjectionSource source,
            @Nonnull ReconciliationCooldownResolver cooldowns,
            @Nonnull ReconciliationListener listener
    ) {
        this.projections = Objects.requireNonNull(projections, "projections");
        this.source = Objects.requireNonNull(source, "source");
        this.cooldowns = Objects.requireNonNull(cooldowns, "cooldowns");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void onStartup(
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            long observedAtMs
    ) {
        reconcile(leases, BondedCompanionProjectionService.RecoveryCause.STARTUP,
                observedAtMs);
    }

    public void onWorldLoad(
            @Nonnull String worldKey,
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            long observedAtMs
    ) {
        String exactWorld = text(worldKey, "worldKey");
        reconcile(leases.stream()
                        .filter(lease -> lease.worldKey().equals(exactWorld)).toList(),
                BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD,
                observedAtMs);
    }

    public void onPlayerJoin(
            @Nonnull UUID ownerUuid,
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            long observedAtMs
    ) {
        reconcile(ownerLeases(ownerUuid, leases),
                BondedCompanionProjectionService.RecoveryCause.PLAYER_JOIN,
                observedAtMs);
    }

    /** Uses only stable owner/world IDs; no Player component crosses the callback. */
    public void onPlayerWorldTransfer(
            @Nonnull UUID ownerUuid,
            @Nonnull String fromWorldKey,
            @Nonnull String toWorldKey,
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            long observedAtMs
    ) {
        Objects.requireNonNull(toWorldKey, "toWorldKey");
        String from = text(fromWorldKey, "fromWorldKey");
        reconcile(ownerLeases(ownerUuid, leases).stream()
                        .filter(lease -> lease.worldKey().equals(from)).toList(),
                BondedCompanionProjectionService.RecoveryCause.WORLD_TRANSFER,
                observedAtMs);
    }

    public void onPlayerLogout(
            @Nonnull UUID ownerUuid,
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            long observedAtMs
    ) {
        reconcile(ownerLeases(ownerUuid, leases),
                BondedCompanionProjectionService.RecoveryCause.LOGOUT,
                observedAtMs);
    }

    public void onProjectionMissingScan(
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            long observedAtMs
    ) {
        reconcile(leases,
                BondedCompanionProjectionService.RecoveryCause.MISSING_SCAN,
                observedAtMs);
    }

    public void onLeaseExpired(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            long observedAtMs
    ) {
        BondedCompanionProjectionService.ReconcileResult result = reconcile(
                lease, source.projections(),
                BondedCompanionProjectionService.RecoveryCause.EXPIRED,
                observedAtMs);
        listener.onReconciled(lease,
                BondedCompanionProjectionService.RecoveryCause.EXPIRED, result);
    }

    public void onConfirmedDeath(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            @Nonnull BondedCompanionProjectionValidator.Projection projection,
            long diedAtMs
    ) {
        BondedCompanionProjectionService.ReconcileResult result =
                projections.confirmDeath(lease, projection, diedAtMs);
        listener.onReconciled(lease, null, result);
    }

    private void reconcile(
            List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        Objects.requireNonNull(leases, "leases");
        List<BondedCompanionProjectionValidator.Projection> observed =
                source.projections();
        for (var lease : leases) {
            if (lease != null) {
                BondedCompanionProjectionService.ReconcileResult result =
                        reconcile(lease, observed, cause, observedAtMs);
                listener.onReconciled(lease, cause, result);
            }
        }
    }

    private BondedCompanionProjectionService.ReconcileResult reconcile(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            List<BondedCompanionProjectionValidator.Projection> observed,
            BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        OptionalLong cooldown = cooldowns.resolve(lease, observedAtMs);
        return cooldown.isPresent()
                ? projections.reconcile(
                        lease, observed, cause, observedAtMs,
                        cooldown.getAsLong())
                : new BondedCompanionProjectionService.ReconcileResult(
                        BondedCompanionProjectionService.ReconcileStatus
                                .DURABILITY_REJECTED,
                        List.of());
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> ownerLeases(
            UUID ownerUuid,
            List<BondedCompanionProjectionValidator.LeaseExpectation> leases
    ) {
        UUID owner = Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(leases, "leases");
        return leases.stream().filter(lease -> lease != null
                && owner.equals(lease.ownerUuid())).toList();
    }

    /** Supplies a world-thread snapshot of loaded projections; it never scans Universe players. */
    public interface ProjectionSource {
        @Nonnull List<BondedCompanionProjectionValidator.Projection> projections();
    }

    /** Resolves the exact-family cooldown before a non-death store mutation. */
    @FunctionalInterface
    public interface ReconciliationCooldownResolver {
        @Nonnull OptionalLong resolve(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
                long observedAtMs);
    }

    /** Receives only completed durability/world outcomes for change publication. */
    @FunctionalInterface
    public interface ReconciliationListener {
        void onReconciled(
                @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
                BondedCompanionProjectionService.RecoveryCause cause,
                @Nonnull BondedCompanionProjectionService.ReconcileResult result
        );
    }

    private static String text(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
