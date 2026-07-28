package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Classifies a frozen capture against canonical identity and lifecycle state.
 *
 * <p>Only a positive observation of the already-current alias may repair an
 * unloaded or stale-world location. Historical aliases always fail closed.</p>
 */
final class SpawnerCaptureProfileGate {

    @Nonnull
    Decision evaluate(
            @Nonnull SpawnerCaptureContext context,
            @Nonnull CompanionProfileReadModel profile,
            long requestedAtMs
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(profile, "profile");
        CompanionLifecycle lifecycle = profile.lifecycle();
        if (!profile.identity().profileId().equals(context.profileId())) {
            return Decision.conflict("capture_profile_id_mismatch");
        }
        CompanionAlias alias = profile.currentAlias();
        if (alias == null
                || alias.state() != CompanionAlias.State.CURRENT
                || !alias.alias().equals(context.sourceAlias())) {
            return Decision.conflict("capture_alias_not_current");
        }
        if (lifecycle.quarantined()) {
            return Decision.conflict("capture_profile_quarantined");
        }
        if (lifecycle.activeOperationId() != null) {
            return Decision.conflict("capture_operation_in_progress");
        }
        if (lifecycle.state() == LifecycleState.UNLOADED) {
            return Decision.reconcile(reconciliation(
                    context, lifecycle, requestedAtMs
            ));
        }
        if (lifecycle.state() != LifecycleState.ACTIVE) {
            return Decision.conflict("capture_lifecycle_not_active");
        }
        if (lifecycle.location().kind()
                != LifecycleLocationKind.LIVE_ENTITY
                || !context.sourceAlias().toString().equals(
                lifecycle.location().key()
        )) {
            return Decision.conflict("capture_location_alias_mismatch");
        }
        if (!context.worldKey().equals(lifecycle.location().worldKey())) {
            return Decision.reconcile(reconciliation(
                    context, lifecycle, requestedAtMs
            ));
        }
        return Decision.exact();
    }

    private CompanionProfileMutation.ReconcileLoaded reconciliation(
            SpawnerCaptureContext context,
            CompanionLifecycle lifecycle,
            long requestedAtMs
    ) {
        return new CompanionProfileMutation.ReconcileLoaded(
                context.profileId(),
                lifecycle.revision(),
                lifecycle.lastReconciledGeneration(),
                context.sourceAlias(),
                context.sourceAlias(),
                context.worldKey(),
                requestedAtMs
        );
    }

    record Decision(
            @Nonnull Status status,
            @Nullable String detail,
            @Nullable CompanionProfileMutation.ReconcileLoaded reconciliation
    ) {
        Decision {
            Objects.requireNonNull(status, "status");
        }

        static Decision exact() {
            return new Decision(Status.EXACT, null, null);
        }

        static Decision conflict(String detail) {
            return new Decision(
                    Status.CONFLICT,
                    Objects.requireNonNull(detail, "detail"),
                    null
            );
        }

        static Decision reconcile(
                CompanionProfileMutation.ReconcileLoaded reconciliation
        ) {
            return new Decision(
                    Status.RECONCILE,
                    null,
                    Objects.requireNonNull(reconciliation, "reconciliation")
            );
        }
    }

    enum Status {
        EXACT,
        RECONCILE,
        CONFLICT
    }
}
