package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselReadinessView;
import com.alechilles.alecstamework.api.internal.BondedVesselsApiDelegate;
import com.alechilles.alecstamework.api.internal.TameworkApiImpl;
import com.alechilles.alecstamework.vessels.BondedVesselCoordinator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;

/**
 * Single production activation boundary for the BONDED_VESSELS capability. Recovery must finish
 * and both exact-evidence and mutation authorities must still be ready at publication time.
 */
public final class BondedVesselRuntimeBootstrap {
    private final TameworkApiImpl api;
    private final BondedVesselsApiDelegate runtime;
    private final BooleanSupplier exactEvidenceReady;
    private final BooleanSupplier mutationAuthorityReady;
    private final BondedVesselInitialBindingService initialBindings;

    public BondedVesselRuntimeBootstrap(
            @Nonnull TameworkApiImpl api,
            @Nonnull BondedVesselsApiDelegate runtime,
            @Nonnull BondedVesselInitialBindingService initialBindings,
            @Nonnull BooleanSupplier exactEvidenceReady,
            @Nonnull BooleanSupplier mutationAuthorityReady) {
        this.api = Objects.requireNonNull(api, "api");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.initialBindings = Objects.requireNonNull(initialBindings, "initialBindings");
        this.exactEvidenceReady = Objects.requireNonNull(
                exactEvidenceReady, "exactEvidenceReady");
        this.mutationAuthorityReady = Objects.requireNonNull(
                mutationAuthorityReady, "mutationAuthorityReady");
    }

    @Nonnull
    public CompletionStage<Activation> recoverAndActivate() {
        final CompletionStage<BondedVesselInitialBindingService.RecoveryReport> initialRecovery;
        try {
            initialRecovery = initialBindings.recoverPending(128);
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(Activation.unavailable(
                    "bonded-vessel-initial-binding-recovery-dispatch-failed"));
        }
        if (initialRecovery == null) {
            return CompletableFuture.completedFuture(Activation.unavailable(
                    "bonded-vessel-initial-binding-recovery-stage-missing"));
        }
        return initialRecovery.handle((report, failure) -> failure == null && report != null)
                .thenCompose(ready -> ready ? recoverTransitionsAndActivate()
                        : CompletableFuture.completedFuture(Activation.unavailable(
                        "bonded-vessel-initial-binding-recovery-failed")));
    }

    private CompletionStage<Activation> recoverTransitionsAndActivate() {
        final CompletionStage<BondedVesselCoordinator.RecoveryReport> recovery;
        try {
            recovery = runtime.coordinator().recoverPending();
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(Activation.unavailable(
                    "bonded-vessel-recovery-dispatch-failed"));
        }
        if (recovery == null) {
            return CompletableFuture.completedFuture(Activation.unavailable(
                    "bonded-vessel-recovery-stage-missing"));
        }
        return recovery.handle((report, failure) -> {
            if (failure != null || report == null || report.failed() > 0) {
                return Activation.unavailable("bonded-vessel-recovery-failed");
            }
            BondedVesselReadinessView readiness = runtime.readiness();
            if (readiness.readiness() != BondedVesselReadinessView.Readiness.READY) {
                return Activation.unavailable(readiness.reason());
            }
            boolean evidenceReady = safeReady(exactEvidenceReady);
            boolean mutationReady = safeReady(mutationAuthorityReady);
            if (!evidenceReady || !mutationReady) {
                return Activation.unavailable(!evidenceReady
                        ? "bonded-vessel-exact-evidence-not-ready"
                        : "bonded-vessel-mutation-authority-not-ready");
            }
            boolean activated;
            try {
                activated = api.activateBondedVesselsRuntime(
                        runtime, evidenceReady, mutationReady);
            } catch (RuntimeException | LinkageError failureDuringPublish) {
                activated = false;
            }
            return activated
                    ? new Activation(true, "bonded-vessel-runtime-active", report)
                    : Activation.unavailable("bonded-vessel-capability-publication-rejected");
        });
    }

    private static boolean safeReady(BooleanSupplier readiness) {
        try {
            return readiness.getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    public record Activation(boolean active,
                             @Nonnull String reason,
                             BondedVesselCoordinator.RecoveryReport recovery) {
        public Activation {
            reason = requireText(reason);
            if (active && recovery == null) {
                throw new IllegalArgumentException("Active vessel runtime requires recovery evidence.");
            }
        }

        static Activation unavailable(String reason) {
            return new Activation(false, reason, null);
        }
    }

    private static String requireText(String value) {
        String normalized = Objects.requireNonNull(value, "reason").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("reason is required");
        return normalized;
    }
}
