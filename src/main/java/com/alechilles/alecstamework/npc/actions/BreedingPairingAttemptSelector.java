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
        return select(parents, new ReplayLookup() {
            @Override
            public BreedingPopulationReplayState stateForPair(
                    String worldId, List<String> parentProfiles) {
                return service.replayStateForPair(worldId, parentProfiles);
            }

            @Override
            public BreedingPopulationReplayState state(String attemptKey) {
                return service.replayState(attemptKey);
            }
        });
    }

    @Nullable
    BreedingPairingAttempt select(
            @Nonnull BreedingPreparedParents parents,
            @Nonnull ReplayLookup replayLookup) {
        if (parents.sourceIdentity().profileId().equals(
                parents.partnerIdentity().profileId()
        )) {
            return null;
        }
        List<String> parentProfiles = List.of(
                parents.sourceIdentity().profileId(),
                parents.partnerIdentity().profileId()
        );
        BreedingPopulationReplayState pairReplay = replayLookup.stateForPair(
                parents.worldId(), parentProfiles
        );
        if (pairReplay.usable() && pairReplay.hasPendingChildren()) {
            UUID replayId = jobId(pairReplay.attemptKey());
            return replayId == null
                    ? null
                    : new BreedingPairingAttempt(replayId, pairReplay, true);
        }
        if (!pairReplay.usable()) {
            return selectLegacyExactReplay(parents, pairReplay, replayLookup);
        }

        UUID newJobId = BreedingAttemptIdentity.forAppliedCooldowns(
                parents.sourceIdentity(), parents.sourceFingerprint(),
                parents.partnerIdentity(), parents.partnerFingerprint(),
                Objects.requireNonNull(admissionNonceSupplier.get(), "admission nonce")
        );
        BreedingPopulationReplayState fresh = replayLookup.state(
                BreedingAttemptIdentity.attemptKey(newJobId)
        );
        if (!fresh.usable() || fresh.birthPlan() != null
                || fresh.hasPendingChildren() || fresh.hasCommittedChildren()) {
            return null;
        }
        return new BreedingPairingAttempt(newJobId, fresh, false);
    }

    @Nullable
    private static BreedingPairingAttempt selectLegacyExactReplay(
            BreedingPreparedParents parents,
            BreedingPopulationReplayState pairReplay,
            ReplayLookup replayLookup) {
        if (!"breeding-replay-pair-metadata-missing".equals(pairReplay.reason())) {
            return null;
        }
        UUID legacyJobId = BreedingAttemptIdentity.forPersistedCooldowns(
                parents.sourceIdentity(), parents.sourceSnapshot(),
                parents.partnerIdentity(), parents.partnerSnapshot()
        );
        String attemptKey = BreedingAttemptIdentity.attemptKey(legacyJobId);
        BreedingPopulationReplayState exactReplay = replayLookup.state(attemptKey);
        if (!exactReplay.usable()
                || !exactReplay.hasPendingChildren()
                || !attemptKey.equals(exactReplay.attemptKey())) {
            return null;
        }
        return new BreedingPairingAttempt(legacyJobId, exactReplay, true);
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

    interface ReplayLookup {
        BreedingPopulationReplayState stateForPair(
                String worldId, List<String> parentProfiles);

        BreedingPopulationReplayState state(String attemptKey);
    }
}
