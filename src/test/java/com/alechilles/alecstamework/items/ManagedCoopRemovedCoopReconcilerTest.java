package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchOutcome;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchStatus;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSite;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSitePolicy;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.MutationStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable removal-marker and restart replay regressions for managed-coop ejection. */
class ManagedCoopRemovedCoopReconcilerTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final String COOP = "coop_chicken";

    @Test
    void confirmedRemovalCommitsDisabledMarkerBeforeRelease() {
        Fixture fixture = new Fixture(AuthorityState.TWORK_MANAGED);
        CompletableFuture<MutationResult> transition = new CompletableFuture<>();
        AtomicInteger releaseCalls = new AtomicInteger();
        AtomicReference<ReleaseSite> site = new AtomicReference<>();
        ManagedCoopRemovedCoopReconciler reconciler = fixture.reconciler(
                (key, nowMs) -> transition,
                () -> {
                    fixture.rebuild(AuthorityState.DISABLED);
                    return true;
                },
                (releaseSite, resident, nowMs) -> {
                    releaseCalls.incrementAndGet();
                    site.set(releaseSite);
                    return completedRelease();
                });

        reconciler.reconcileSnapshot(
                "world", Set.of(), -50L, ignored -> removed());

        assertEquals(0, releaseCalls.get(),
                "release must wait for the authority transition commit");

        transition.complete(new MutationResult(MutationStatus.APPLIED, null, null));

        assertEquals(1, releaseCalls.get());
        assertNotNull(site.get());
        assertEquals(ReleaseSitePolicy.EXACT_MANAGED_OR_DISABLED_REMOVAL,
                site.get().policy());
        assertEquals(AUTHORITY, site.get().authorityKey());
    }

    @Test
    void restartWithDisabledAuthorityResumesHousedReleaseWithoutAnotherTransition() {
        Fixture fixture = new Fixture(AuthorityState.DISABLED);
        AtomicInteger transitions = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        ManagedCoopRemovedCoopReconciler reconciler = fixture.reconciler(
                (key, nowMs) -> {
                    transitions.incrementAndGet();
                    return CompletableFuture.failedFuture(
                            new AssertionError("disabled marker must be replayed directly"));
                },
                () -> true,
                (site, resident, nowMs) -> {
                    releases.incrementAndGet();
                    return completedRelease();
                });

        reconciler.reconcileSnapshot(
                "world", Set.of(), -40L, ignored -> removed());

        assertEquals(0, transitions.get());
        assertEquals(1, releases.get());
    }

    @Test
    void unreliableOrMatchingMissingComponentEvidenceCannotDisableAuthority() {
        Fixture fixture = new Fixture(AuthorityState.TWORK_MANAGED);
        AtomicInteger transitions = new AtomicInteger();
        ManagedCoopRemovedCoopReconciler reconciler = fixture.reconciler(
                (key, nowMs) -> {
                    transitions.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new MutationResult(MutationStatus.APPLIED, null, null));
                },
                () -> true,
                (site, resident, nowMs) -> CompletableFuture.failedFuture(
                        new AssertionError("release must stay closed")));

        List<ManagedCoopRemovalEvidence.Status> deferred = List.of(
                ManagedCoopRemovalEvidence.Status.DEFERRED_UNLOADED,
                ManagedCoopRemovalEvidence.Status.DEFERRED_AMBIGUOUS,
                ManagedCoopRemovalEvidence.Status.DEFERRED_MATCHING_BLOCK_COMPONENT_MISSING,
                ManagedCoopRemovalEvidence.Status.EXACT_MANAGED_COOP);
        for (ManagedCoopRemovalEvidence.Status status : deferred) {
            reconciler.reconcileSnapshot("world", Set.of(), -30L,
                    ignored -> new ManagedCoopRemovalEvidence.Result(status, 0, status.name()));
        }

        assertEquals(0, transitions.get());
    }

    @Test
    void currentManagedCoopKeySuppressesRemovalProbeButNotDisabledRestart() {
        Fixture fixture = new Fixture(AuthorityState.TWORK_MANAGED);
        AtomicInteger evidenceReads = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        ManagedCoopRemovedCoopReconciler reconciler = fixture.reconciler(
                (key, nowMs) -> CompletableFuture.completedFuture(
                        new MutationResult(MutationStatus.APPLIED, null, null)),
                () -> true,
                (site, resident, nowMs) -> {
                    releases.incrementAndGet();
                    return completedRelease();
                });
        Set<String> active = Set.of(AUTHORITY.authorityId() + "|coop=" + COOP);

        reconciler.reconcileSnapshot("world", active, -20L, ignored -> {
            evidenceReads.incrementAndGet();
            return removed();
        });

        assertEquals(0, evidenceReads.get());
        fixture.rebuild(AuthorityState.DISABLED);
        reconciler.reconcileSnapshot("world", active, -10L, ignored -> {
            evidenceReads.incrementAndGet();
            return exactManaged();
        });

        assertEquals(1, evidenceReads.get());
        assertEquals(1, releases.get());
    }

    private static CompletableFuture<DispatchOutcome> completedRelease() {
        return CompletableFuture.completedFuture(
                new DispatchOutcome(DispatchStatus.RELEASED, "release-op", null));
    }

    private static ManagedCoopRemovalEvidence.Result removed() {
        return new ManagedCoopRemovalEvidence.Result(
                ManagedCoopRemovalEvidence.Status.CONFIRMED_REMOVED, 0, "removed");
    }

    private static ManagedCoopRemovalEvidence.Result exactManaged() {
        return new ManagedCoopRemovalEvidence.Result(
                ManagedCoopRemovalEvidence.Status.EXACT_MANAGED_COOP, 5, null);
    }

    private static final class Fixture {
        private final ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        private final ManagedCoopLifecycleOperationIndex operations =
                new ManagedCoopLifecycleOperationIndex();
        private final ResidentRecord resident = new ResidentRecord(
                "resident", AUTHORITY, COOP, 0, "profile", "mob_chicken",
                uuid(1), uuid(1), null, "{}", "a".repeat(64), 1,
                ResidentState.HOUSED, 0L, true, -100L, 0L, -100L, -90L);

        private Fixture(AuthorityState state) {
            assertTrue(operations.rebuild(
                    ManagedCoopReadResult.loaded(List.of())).rebuilt());
            rebuild(state);
        }

        private void rebuild(AuthorityState state) {
            AuthorityRecord authority = new AuthorityRecord(
                    AUTHORITY.authorityId(), AUTHORITY, COOP, state,
                    true, 1, -100L, -90L, null);
            assertTrue(residents.rebuild(
                    ManagedCoopReadResult.loaded(List.of(authority)),
                    ManagedCoopReadResult.loaded(List.of(resident))).rebuilt());
        }

        private ManagedCoopRemovedCoopReconciler reconciler(
                ManagedCoopRemovedCoopReconciler.AuthorityTransitionGateway transitions,
                ManagedCoopRemovedCoopReconciler.RefreshGateway refresh,
                ManagedCoopRemovedCoopReconciler.ReleaseGateway releases) {
            return new ManagedCoopRemovedCoopReconciler(
                    residents, operations, () -> true,
                    new HytaleManagedCoopRemovalEvidenceReader(),
                    transitions, refresh, releases);
        }
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format(
                "00000000-0000-0000-0000-%012d", suffix));
    }
}
