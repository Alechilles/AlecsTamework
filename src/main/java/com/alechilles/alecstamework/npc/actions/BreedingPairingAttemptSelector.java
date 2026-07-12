package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.BreedingAttemptIdentity;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects journal-backed replay attempts or creates a collision-resistant fresh attempt. */
final class BreedingPairingAttemptSelector {
    private static final String ATTEMPT_PREFIX = "breeding:";

    private final Supplier<UUID> admissionNonceSupplier;

    BreedingPairingAttemptSelector() {
        this(UUID::randomUUID);
    }

    BreedingPairingAttemptSelector(@Nonnull Supplier<UUID> admissionNonceSupplier) {
        this.admissionNonceSupplier = Objects.requireNonNull(
                admissionNonceSupplier, "admissionNonceSupplier"
        );
    }

    @Nullable
    BreedingPairingAttempt select(
            @Nonnull BreedingPreparedParents parents,
            @Nonnull BreedingPopulationAdmissionService service) {
        if (parents.sourceIdentity().profileId().equals(
                parents.partnerIdentity().profileId()
        )) {
            return null;
        }
        List<String> parentProfiles = List.of(
                parents.sourceIdentity().profileId(),
                parents.partnerIdentity().profileId()
        );
        BreedingPopulationReplayState pairReplay = service.replayStateForPair(
                parents.worldId(), parentProfiles
        );
        if (pairReplay.usable() && pairReplay.hasPendingChildren()) {
            UUID replayId = jobId(pairReplay.attemptKey());
            return replayId == null
                    ? null
                    : new BreedingPairingAttempt(replayId, pairReplay, true);
        }
        if (!pairReplay.usable()) {
            return null;
        }

        UUID newJobId = BreedingAttemptIdentity.forAppliedCooldowns(
                parents.sourceIdentity(), parents.sourceFingerprint(),
                parents.partnerIdentity(), parents.partnerFingerprint(),
                Objects.requireNonNull(admissionNonceSupplier.get(), "admission nonce")
        );
        BreedingPopulationReplayState fresh = service.replayState(
                BreedingAttemptIdentity.attemptKey(newJobId)
        );
        if (!fresh.usable() || fresh.birthPlan() != null
                || fresh.hasPendingChildren() || fresh.hasCommittedChildren()) {
            return null;
        }
        return new BreedingPairingAttempt(newJobId, fresh, false);
    }

    @Nullable
    private static UUID jobId(@Nullable String attemptKey) {
        if (attemptKey == null || !attemptKey.startsWith(ATTEMPT_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(attemptKey.substring(ATTEMPT_PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
