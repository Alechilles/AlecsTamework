package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.items.ImportedRecallRecoverySink.RecallFailure;
import javax.annotation.Nullable;

/**
 * Authors the non-destructive first phase for a stale active Recall target.
 */
final class MissingActiveRecallReconciliationAuthor {
    @Nullable
    CompanionProfileMutation.ReconcileMissingActive author(
            CompanionProfileReadModel profile,
            RecallFailure failure
    ) {
        if (!eligible(profile, failure)) {
            return null;
        }
        return new CompanionProfileMutation.ReconcileMissingActive(
                profile.identity().profileId(),
                profile.lifecycle().revision(),
                profile.lifecycle().lastReconciledGeneration(),
                profile.currentAlias().alias(),
                profile.lifecycle().ownerId(),
                failure.probedWorldName(),
                failure.queuedAtMs(),
                failure.failedAtMs()
        );
    }

    private boolean eligible(
            CompanionProfileReadModel profile,
            RecallFailure failure
    ) {
        if (profile == null || failure == null
                || profile.currentAlias() == null
                || profile.lifecycle().ownerId() == null
                || profile.lifecycle().state() != LifecycleState.ACTIVE
                || profile.lifecycle().activeOperationId() != null
                || profile.lifecycle().quarantined()) {
            return false;
        }
        CompanionAlias alias = profile.currentAlias();
        LifecycleLocation location = profile.lifecycle().location();
        String probedWorld = normalize(failure.probedWorldName());
        return alias.state() == CompanionAlias.State.CURRENT
                && alias.alias().value().equals(failure.npcUuid())
                && profile.lifecycle().ownerId().value().equals(
                failure.ownerUuid()
        )
                && location.kind() == LifecycleLocationKind.LIVE_ENTITY
                && alias.alias().toString().equals(location.key())
                && probedWorld != null
                && probedWorld.equals(normalize(location.worldKey()));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
