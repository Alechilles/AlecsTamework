package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.BlockAccess;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.CompositeEpoch;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.EpochGateway;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.InventoryApply;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.OutcomeStatus;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.RuntimeGateway;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchOutcome;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.DispatchStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for post-release managed produce and interaction-state behavior. */
class ManagedCoopAncillaryBehaviorTest {
    private static final ManagedCoopAuthorityKey KEY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final long START_GAME_TIME = -300_000_000L;

    @Test
    void productionWaitsForReleaseAndUsesThePostReleaseHousedSet() {
        ManagedCoopResidentIndex successfulIndex = index(ResidentState.HOUSED);
        FakeRuntime successfulRuntime = new FakeRuntime();
        ArrayList<Outcome> successfulOutcomes = new ArrayList<>();
        ManagedCoopAncillaryBehavior successful = behavior(
                successfulIndex, new TestEpochs(successfulIndex),
                successfulRuntime, successfulOutcomes::add);
        CompletableFuture<DispatchOutcome> successfulRelease = new CompletableFuture<>();

        successful.produceAfter(request(START_GAME_TIME, 48, 2), successfulRelease);

        assertEquals(0, successfulRuntime.enqueues);
        assertTrue(successfulRuntime.dropReferences.isEmpty());

        rebuild(successfulIndex, ResidentState.DEPLOYED);
        successfulRelease.complete(new DispatchOutcome(
                DispatchStatus.RELEASED, "release-op", null));

        assertEquals(1, successfulRuntime.enqueues);
        assertTrue(successfulRuntime.dropReferences.isEmpty());
        assertEquals(OutcomeStatus.NO_ELIGIBLE_RESIDENTS,
                successfulOutcomes.getLast().status());

        ManagedCoopResidentIndex failedIndex = index(ResidentState.HOUSED);
        FakeRuntime failedRuntime = new FakeRuntime();
        ArrayList<Outcome> failedOutcomes = new ArrayList<>();
        ManagedCoopAncillaryBehavior failed = behavior(
                failedIndex, new TestEpochs(failedIndex), failedRuntime, failedOutcomes::add);
        CompletableFuture<DispatchOutcome> failedRelease = new CompletableFuture<>();

        failed.produceAfter(request(START_GAME_TIME, 48, 2), failedRelease);
        failedRelease.complete(new DispatchOutcome(
                DispatchStatus.RELEASE_FAILED, "release-op", "projection_failed"));

        assertEquals(List.of("Drop_Chicken", "Drop_Chicken"), failedRuntime.dropReferences);
        assertEquals(ManagedCoopAncillaryBehavior.INTERACTION_STATE_PRESENT,
                failedRuntime.block.states.getLast());
        assertEquals(OutcomeStatus.PRODUCED, failedOutcomes.getLast().status());
        assertEquals(2, failedOutcomes.getLast().productionInvocations());
    }

    @Test
    void negativeGameEpochRemainsARealCadenceTimestamp() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        FakeRuntime runtime = new FakeRuntime();
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, new TestEpochs(index), runtime, ignored -> { });
        long fortySevenHours = 47L * ManagedCoopAncillaryBehavior.GAME_MILLIS_PER_HOUR;
        long fortyEightHours = 48L * ManagedCoopAncillaryBehavior.GAME_MILLIS_PER_HOUR;

        behavior.produceAfter(request(START_GAME_TIME, 48, 1), null);
        behavior.produceAfter(request(START_GAME_TIME + fortySevenHours, 48, 1), null);

        assertEquals(1, runtime.dropReferences.size(),
                "a negative previous timestamp must not be treated as unset");

        behavior.produceAfter(request(START_GAME_TIME + fortyEightHours, 48, 1), null);

        assertEquals(2, runtime.dropReferences.size());
    }

    @Test
    void interactionStateUsesCurrentTypedContainerAndFailsClosedWithoutCompositeTrust() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        AtomicBoolean trusted = new AtomicBoolean(true);
        TestEpochs epochs = new TestEpochs(index);
        epochs.externalTrust = trusted;
        FakeRuntime runtime = new FakeRuntime();
        ArrayList<Outcome> outcomes = new ArrayList<>();
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, epochs, runtime, outcomes::add);
        ManagedCoopAncillaryRequest request = request(START_GAME_TIME, 48, 1);

        behavior.syncInteractionState(request);
        runtime.block.empty = false;
        behavior.syncInteractionState(request);
        trusted.set(false);
        behavior.syncInteractionState(request);

        assertEquals(List.of(
                ManagedCoopAncillaryBehavior.INTERACTION_STATE_EMPTY,
                ManagedCoopAncillaryBehavior.INTERACTION_STATE_PRESENT), runtime.block.states);
        assertEquals(OutcomeStatus.INDEX_UNTRUSTED, outcomes.getLast().status());
        assertEquals(2, runtime.resolves,
                "untrusted composite evidence must fail before resolving or mutating the block");
    }

    @Test
    void copiedRequestNormalizesAndDefensivelyCopiesRoleDrops() {
        HashMap<String, String> drops = new HashMap<>();
        drops.put(" Mob_Chicken ", " Drop_Chicken ");

        ManagedCoopAncillaryRequest request = new ManagedCoopAncillaryRequest(
                KEY, " COOP_CHICKEN ", 3, drops, 12, 4, -1L);
        drops.clear();

        assertEquals("coop_chicken", request.coopId());
        assertEquals(Map.of("mob_chicken", "Drop_Chicken"), request.dropsByRole());
        assertEquals(12, request.intervalGameHours());
        assertEquals(4, request.itemsPerTick());
    }

    @Test
    void cadenceStateIsPrunedWhenReliableSweepNoLongerContainsCoop() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        FakeRuntime runtime = new FakeRuntime();
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, new TestEpochs(index), runtime, ignored -> { });

        behavior.produceAfter(request(START_GAME_TIME, 48, 1), null);
        behavior.retainActiveCoops(java.util.Set.of());
        behavior.produceAfter(request(START_GAME_TIME + 1L, 48, 1), null);

        assertEquals(2, runtime.dropReferences.size());
    }

    @Test
    void runtimeAndDiagnosticExceptionsAreContainedAsOneFailedOutcome() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        ArrayList<Outcome> outcomes = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.resolveFailure = new IllegalStateException("block_race");
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, new TestEpochs(index), runtime, outcome -> {
                    outcomes.add(outcome);
                    if (outcome.status() == OutcomeStatus.FAILED) {
                        throw new IllegalStateException("diagnostic_failure");
                    }
                });

        behavior.syncInteractionState(request(START_GAME_TIME, 48, 1));

        assertEquals(1, outcomes.size());
        assertEquals(OutcomeStatus.FAILED, outcomes.getFirst().status());
        assertTrue(outcomes.getFirst().detail().contains("block_race"));
    }

    @Test
    void trustedButNewerResidentRevisionBlocksTheOldSnapshotBeforeMutation() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        FakeRuntime runtime = new FakeRuntime();
        ArrayList<Outcome> outcomes = new ArrayList<>();
        runtime.beforeResolve = () -> rebuild(index, ResidentState.DEPLOYED);
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, new TestEpochs(index), runtime, outcomes::add);

        behavior.produceAfter(request(START_GAME_TIME, 48, 1), null);

        assertTrue(runtime.dropReferences.isEmpty());
        assertTrue(runtime.block.states.isEmpty());
        assertEquals(OutcomeStatus.INDEX_UNTRUSTED, outcomes.getLast().status());
        assertTrue(outcomes.getLast().detail().contains("epoch_changed_before_apply"));
    }

    @Test
    void exactZeroGameEpochIsInitializedOnlyOnce() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        FakeRuntime runtime = new FakeRuntime();
        ArrayList<Outcome> outcomes = new ArrayList<>();
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, new TestEpochs(index), runtime, outcomes::add);

        behavior.produceAfter(request(0L, 48, 1), null);
        behavior.produceAfter(request(0L, 48, 1), null);

        assertEquals(1, runtime.addCalls);
        assertEquals(1, runtime.dropReferences.size());
        assertEquals(OutcomeStatus.NOT_DUE, outcomes.getLast().status());
    }

    @Test
    void possiblePartialExceptionConsumesCadenceAndCannotReplayTheWindow() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        FakeRuntime runtime = new FakeRuntime();
        runtime.mutateBeforeAddFailure = true;
        runtime.addFailure = new IllegalStateException("partial_inventory_failure");
        ArrayList<Outcome> outcomes = new ArrayList<>();
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, new TestEpochs(index), runtime, outcomes::add);

        behavior.produceAfter(request(START_GAME_TIME, 48, 1), null);
        runtime.mutateBeforeAddFailure = false;
        runtime.addFailure = null;
        behavior.produceAfter(request(START_GAME_TIME + 1L, 48, 1), null);

        assertEquals(1, runtime.addCalls,
                "an uncertain partial write must consume this cadence window");
        assertEquals(List.of("Drop_Chicken"), runtime.dropReferences);
        assertEquals(OutcomeStatus.NOT_DUE, outcomes.getLast().status());
    }

    @Test
    void replacedBlockFailsClosedBeforeInventoryOrInteractionMutation() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        FakeRuntime runtime = new FakeRuntime();
        runtime.replacementBlock = true;
        ArrayList<Outcome> outcomes = new ArrayList<>();
        ManagedCoopAncillaryBehavior behavior = behavior(
                index, new TestEpochs(index), runtime, outcomes::add);

        behavior.produceAfter(request(START_GAME_TIME, 48, 1), null);
        behavior.syncInteractionState(request(START_GAME_TIME, 48, 1));

        assertTrue(runtime.dropReferences.isEmpty());
        assertTrue(runtime.block.states.isEmpty());
        assertEquals(OutcomeStatus.BLOCK_UNAVAILABLE, outcomes.getLast().status());
    }

    @Test
    void operationEpochChangeDuringBlockResolutionFailsBeforeApply() {
        ManagedCoopResidentIndex index = index(ResidentState.HOUSED);
        TestEpochs epochs = new TestEpochs(index);
        FakeRuntime runtime = new FakeRuntime();
        runtime.beforeResolve = epochs::advanceOperation;
        ArrayList<Outcome> outcomes = new ArrayList<>();
        ManagedCoopAncillaryBehavior behavior = behavior(index, epochs, runtime, outcomes::add);

        behavior.produceAfter(request(START_GAME_TIME, 48, 1), null);

        assertEquals(0, runtime.addCalls);
        assertTrue(runtime.dropReferences.isEmpty());
        assertTrue(runtime.block.states.isEmpty());
        assertEquals(OutcomeStatus.INDEX_UNTRUSTED, outcomes.getLast().status());
        assertTrue(outcomes.getLast().detail().contains("epoch_changed_before_apply"));
    }

    private static ManagedCoopAncillaryBehavior behavior(
            ManagedCoopResidentIndex index,
            EpochGateway epochs,
            FakeRuntime runtime,
            ManagedCoopAncillaryBehavior.OutcomeSink outcomes) {
        return new ManagedCoopAncillaryBehavior(index, epochs, runtime, outcomes);
    }

    private static ManagedCoopAncillaryRequest request(long gameTimeMs,
                                                        int intervalHours,
                                                        int itemsPerTick) {
        return new ManagedCoopAncillaryRequest(
                KEY, "coop_chicken", 3,
                Map.of("mob_chicken", "Drop_Chicken"),
                intervalHours, itemsPerTick, gameTimeMs);
    }

    private static ManagedCoopResidentIndex index(ResidentState state) {
        ManagedCoopResidentIndex index = new ManagedCoopResidentIndex();
        rebuild(index, state);
        return index;
    }

    private static void rebuild(ManagedCoopResidentIndex index, ResidentState state) {
        AuthorityRecord authority = new AuthorityRecord(
                KEY.authorityId(), KEY, "coop_chicken", AuthorityState.TWORK_MANAGED,
                true, 1, -10L, -9L, null);
        ResidentRecord resident = resident(state);
        var result = index.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority)),
                ManagedCoopReadResult.loaded(List.of(resident)));
        assertTrue(result.rebuilt(), result.detail());
    }

    private static ResidentRecord resident(ResidentState state) {
        UUID source = new UUID(0L, 1L);
        UUID deployed = state == ResidentState.DEPLOYED ? new UUID(0L, 2L) : null;
        return new ResidentRecord(
                "resident", KEY, "coop_chicken", 0,
                "profile", "Mob_Chicken", source, source, deployed,
                "{}", "a".repeat(64), 1, state, 1L, true,
                -100L, state == ResidentState.DEPLOYED ? -50L : 0L, -100L, -50L);
    }

    private static final class TestEpochs implements EpochGateway {
        private final Object lock = new Object();
        private final ManagedCoopResidentIndex residents;
        private final AtomicLong operationRevision = new AtomicLong(1L);
        private AtomicBoolean externalTrust = new AtomicBoolean(true);

        private TestEpochs(ManagedCoopResidentIndex residents) {
            this.residents = residents;
        }

        @Override
        public Object lock() {
            return lock;
        }

        @Override
        public CompositeEpoch capture() {
            if (!externalTrust.get() || !residents.isTrusted()) {
                return null;
            }
            return new CompositeEpoch(
                    residents.snapshot().revision(), operationRevision.get());
        }

        @Override
        public boolean isCurrent(CompositeEpoch epoch) {
            return externalTrust.get()
                    && residents.isTrusted()
                    && residents.snapshot().revision() == epoch.residentRevision()
                    && operationRevision.get() == epoch.operationRevision();
        }

        private void advanceOperation() {
            operationRevision.incrementAndGet();
        }
    }

    private static final class FakeRuntime implements RuntimeGateway {
        private final FakeBlock block = new FakeBlock();
        private final ArrayList<String> dropReferences = new ArrayList<>();
        private int enqueues;
        private int resolves;
        private int addCalls;
        private RuntimeException resolveFailure;
        private RuntimeException addFailure;
        private Runnable beforeResolve;
        private boolean replacementBlock;
        private boolean mutateBeforeAddFailure;

        @Override
        public boolean enqueue(String worldName, Runnable task) {
            assertEquals("world", worldName);
            enqueues++;
            task.run();
            return true;
        }

        @Override
        public BlockAccess resolve(ManagedCoopAncillaryRequest request) {
            assertEquals(KEY, request.authorityKey());
            assertEquals("coop_chicken", request.coopId());
            resolves++;
            if (beforeResolve != null) {
                beforeResolve.run();
            }
            if (resolveFailure != null) {
                throw resolveFailure;
            }
            return replacementBlock ? null : block;
        }

        @Override
        public InventoryApply addOne(BlockAccess block, String dropReferenceId) {
            assertEquals(this.block, block);
            addCalls++;
            if (mutateBeforeAddFailure) {
                dropReferences.add(dropReferenceId);
                this.block.empty = false;
            }
            if (addFailure != null) {
                throw addFailure;
            }
            dropReferences.add(dropReferenceId);
            this.block.empty = false;
            return InventoryApply.applied();
        }
    }

    private static final class FakeBlock implements BlockAccess {
        private final ArrayList<String> states = new ArrayList<>();
        private boolean empty = true;

        @Override
        public boolean isEmpty() {
            return empty;
        }

        @Override
        public void setInteractionState(String state) {
            states.add(state);
        }
    }
}
