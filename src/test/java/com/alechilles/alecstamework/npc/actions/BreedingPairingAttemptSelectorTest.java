package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingAttemptIdentity;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthAnchor;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.npc.breeding.ParentBreedingSnapshot;
import com.alechilles.alecstamework.ownership.BreedingPopulationReplayState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for exact legacy replay and collision-resistant fresh attempts. */
class BreedingPairingAttemptSelectorTest {
    @Test
    void metadataLessLegacyJournalReplaysOnlyThePersistedCooldownDerivedKey() {
        BreedingPreparedParents parents = parents();
        UUID expectedJobId = BreedingAttemptIdentity.forPersistedCooldowns(
                parents.sourceIdentity(), parents.sourceSnapshot(),
                parents.partnerIdentity(), parents.partnerSnapshot()
        );
        String expectedKey = BreedingAttemptIdentity.attemptKey(expectedJobId);
        RecordingLookup lookup = new RecordingLookup(
                state(false, null, Set.of(), "breeding-replay-pair-metadata-missing"),
                state(true, expectedKey, Set.of("child-0000"), "legacy-exact-pending")
        );

        BreedingPairingAttempt selected = new BreedingPairingAttemptSelector().select(
                parents, lookup
        );

        assertEquals(expectedJobId, selected.jobId());
        assertTrue(selected.replay());
        assertEquals(expectedKey, lookup.requestedAttemptKey);
    }

    @Test
    void pairConflictDoesNotBypassThePairIndexThroughLegacyLookup() {
        RecordingLookup lookup = new RecordingLookup(
                state(false, null, Set.of(), "breeding-replay-parent-conflict"),
                state(true, "unused", Set.of("child-0000"), "unused")
        );

        BreedingPairingAttempt selected = new BreedingPairingAttemptSelector().select(
                parents(), lookup
        );

        assertNull(selected);
        assertNull(lookup.requestedAttemptKey);
    }

    @Test
    void blockedSelectionRetainsTheExactPairReplayReason() {
        RecordingLookup lookup = new RecordingLookup(
                state(false, null, Set.of(), "breeding-replay-projection-evidence-unavailable"),
                state(true, "unused", Set.of(), "unused")
        );

        BreedingPairingAttemptSelector.Selection selection =
                new BreedingPairingAttemptSelector().selectDetailed(parents(), lookup);

        assertNull(selection.attempt());
        assertEquals("breeding-replay-projection-evidence-unavailable", selection.reason());
    }

    @Test
    void emptyPairIndexUsesTheInjectedNonceForAFreshAttempt() {
        UUID nonce = new UUID(0L, 55L);
        BreedingPreparedParents parents = parents();
        RecordingLookup lookup = new RecordingLookup(
                state(true, null, Set.of(), "breeding-replay-pair-empty"),
                state(true, null, Set.of(), "breeding-replay-empty")
        );

        BreedingPairingAttempt selected = new BreedingPairingAttemptSelector(() -> nonce)
                .select(parents, lookup);

        UUID expected = BreedingAttemptIdentity.forAppliedCooldowns(
                parents.sourceIdentity(), parents.sourceFingerprint(),
                parents.partnerIdentity(), parents.partnerFingerprint(), nonce
        );
        assertEquals(expected, selected.jobId());
        assertFalse(selected.replay());
        assertEquals(BreedingAttemptIdentity.attemptKey(expected), lookup.requestedAttemptKey);
    }

    private static BreedingPreparedParents parents() {
        ParentBreedingSnapshot sourceSnapshot = snapshot(101L, 201L, 100L, 501L);
        ParentBreedingSnapshot partnerSnapshot = snapshot(102L, 202L, 100L, 501L);
        return new BreedingPreparedParents(
                null,
                null,
                null,
                null,
                null,
                null,
                new BreedingParentIdentity(new UUID(0L, 1L), "profile-a"),
                new BreedingParentIdentity(new UUID(0L, 2L), "profile-b"),
                sourceSnapshot,
                partnerSnapshot,
                fingerprint(301L, 401L, 100L, 601L),
                fingerprint(302L, 402L, 100L, 601L),
                null,
                null,
                0L,
                0L,
                new BreedingBirthAnchor(0.0, 0.0, 0.0),
                "world",
                null,
                null,
                BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                BreedingOffspringProgressionService.OwnerSnapshot.empty()
        );
    }

    private static AppliedCooldownFingerprint fingerprint(long started,
                                                          long until,
                                                          long duration,
                                                          long generation) {
        return new AppliedCooldownFingerprint(
                true, false, until, started, duration, null, generation,
                null, 0L, ParentBreedingSnapshot.AlarmSnapshot.missing()
        );
    }

    private static ParentBreedingSnapshot snapshot(long started,
                                                   long until,
                                                   long duration,
                                                   long generation) {
        return new ParentBreedingSnapshot(
                null, 0.0, generation, false, true, until, started, duration,
                null, null, 0L, ParentBreedingSnapshot.AlarmSnapshot.missing()
        );
    }

    private static BreedingPopulationReplayState state(boolean usable,
                                                       String attemptKey,
                                                       Set<String> pending,
                                                       String reason) {
        return new BreedingPopulationReplayState(
                usable, attemptKey, null, pending, Set.of(), reason
        );
    }

    private static final class RecordingLookup
            implements BreedingPairingAttemptSelector.ReplayLookup {
        private final BreedingPopulationReplayState pairState;
        private final BreedingPopulationReplayState exactState;
        private String requestedAttemptKey;

        private RecordingLookup(BreedingPopulationReplayState pairState,
                                BreedingPopulationReplayState exactState) {
            this.pairState = pairState;
            this.exactState = exactState;
        }

        @Override
        public BreedingPopulationReplayState stateForPair(
                String worldId, List<String> parentProfiles) {
            return pairState;
        }

        @Override
        public BreedingPopulationReplayState state(String attemptKey) {
            requestedAttemptKey = attemptKey;
            return exactState;
        }
    }
}
