package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for world-thread loaded-NPC identity bootstrap completeness. */
class LoadedNpcIdentityBootstrapServiceTest {
    private static final UUID COMPONENT_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LEGACY_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MISSING_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ALTERNATE_UUID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final LoadedNpcIdentityIndex.Location WORLD_A =
            new LoadedNpcIdentityIndex.Location("world-a", "store-a");
    private static final LoadedNpcIdentityIndex.Location WORLD_B =
            new LoadedNpcIdentityIndex.Location("world-b", "store-b");

    @Test
    void positivesAppearDuringIncompleteBootstrapAndAllSuccessesEarnAbsence() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        ManualScheduler scheduler = new ManualScheduler();
        List<String> warnings = new CopyOnWriteArrayList<>();
        LoadedNpcIdentityBootstrapService.ScanTarget first = target(
                WORLD_A,
                scheduler,
                recorder -> recorder.record(COMPONENT_UUID, LEGACY_UUID)
        );
        LoadedNpcIdentityBootstrapService.ScanTarget second = target(
                WORLD_B,
                scheduler,
                recorder -> recorder.record(
                        UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        null
                )
        );
        LoadedNpcIdentityBootstrapService service = service(index, List.of(first, second), warnings);
        CompletableFuture<LoadedNpcIdentitySnapshot> completion = service.awaitCurrentBootstrap();

        service.bootstrapUniverse();

        assertFalse(completion.isDone());
        assertFalse(index.isInitializationComplete());
        assertEquals(2, service.pendingLocationCount());
        scheduler.tasks().get(0).run();
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, index.probe(COMPONENT_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, index.probe(LEGACY_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());
        assertEquals(1, service.pendingLocationCount());
        assertFalse(completion.isDone());

        scheduler.tasks().get(1).run();

        assertTrue(index.isInitializationComplete());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(MISSING_UUID).status());
        assertEquals(0, service.pendingLocationCount());
        assertTrue(warnings.isEmpty());
        assertTrue(completion.isDone());
        assertTrue(completion.getNow(null).initializationComplete());
        assertEquals(2, completion.getNow(null).observations().size());
    }

    @Test
    void bootstrapPublishesProjectionMarkersWithBothNpcIdentities() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityIndex.ProjectionKey key = projectionKey();
        LoadedNpcIdentityBootstrapService service = service(
                index,
                List.of(new LoadedNpcIdentityBootstrapService.ScanTarget(
                        WORLD_A,
                        Runnable::run,
                        recorder -> recorder.record(COMPONENT_UUID, LEGACY_UUID, key)
                )),
                new CopyOnWriteArrayList<>()
        );

        service.bootstrapUniverse();

        LoadedNpcIdentityIndex.ProjectionProbe probe = index.probeProjection(key);
        assertEquals(LoadedNpcIdentityIndex.ProjectionProbeStatus.ONE_MATCH, probe.status());
        assertEquals(COMPONENT_UUID, probe.matches().getFirst().componentUuid());
        assertEquals(LEGACY_UUID, probe.matches().getFirst().legacyNpcUuid());
        assertEquals(WORLD_A, probe.matches().getFirst().location());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION,
                index.probe(COMPONENT_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION,
                index.probe(LEGACY_UUID).status());
    }

    @Test
    void lifecycleExactProjectionAddedDuringScanCannotBeOverwritten() {
        // Regression: scan replacement previously erased an exact marker added by a callback.
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityIndex.ProjectionKey key = projectionKey();
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger scans = new AtomicInteger();
        List<String> warnings = new CopyOnWriteArrayList<>();
        LoadedNpcIdentityBootstrapService service = service(
                index,
                List.of(target(WORLD_A, scheduler, recorder -> {
                    if (scans.getAndIncrement() == 0) {
                        index.recordAdded(observation(COMPONENT_UUID, key));
                    } else {
                        recorder.record(COMPONENT_UUID, COMPONENT_UUID, key);
                    }
                })),
                warnings
        );

        service.bootstrapUniverse();
        scheduler.tasks().getFirst().run();

        LoadedNpcIdentityIndex.ProjectionProbe probe = index.probeProjection(key);
        assertFalse(index.isInitializationComplete());
        assertEquals(1, service.pendingLocationCount());
        assertEquals(2, scheduler.tasks().size());
        assertTrue(warnings.isEmpty());
        assertEquals(LoadedNpcIdentityIndex.ProjectionProbeStatus.ONE_MATCH, probe.status());
        assertEquals(COMPONENT_UUID, probe.matches().getFirst().componentUuid());
        assertTrue(index.probe(COMPONENT_UUID).isKnownLive());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());

        scheduler.tasks().get(1).run();

        assertTrue(index.isInitializationComplete());
        assertEquals(0, service.pendingLocationCount());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(MISSING_UUID).status());
    }

    @Test
    void lifecycleAlternateProjectionAddedDuringScanCannotBeReplacedByScannedExactIdentity() {
        // Regression: a stale exact snapshot previously hid an alternate-UUID marker conflict.
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityIndex.ProjectionKey key = projectionKey();
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger scans = new AtomicInteger();
        LoadedNpcIdentityBootstrapService service = service(
                index,
                List.of(target(WORLD_A, scheduler, recorder -> {
                    if (scans.getAndIncrement() == 0) {
                        recorder.record(COMPONENT_UUID, COMPONENT_UUID, key);
                        index.recordAdded(observation(ALTERNATE_UUID, key));
                    } else {
                        recorder.record(ALTERNATE_UUID, ALTERNATE_UUID, key);
                    }
                })),
                new CopyOnWriteArrayList<>()
        );

        service.bootstrapUniverse();
        scheduler.tasks().getFirst().run();

        LoadedNpcIdentityIndex.ProjectionProbe probe = index.probeProjection(key);
        assertFalse(index.isInitializationComplete());
        assertEquals(LoadedNpcIdentityIndex.ProjectionProbeStatus.ONE_MATCH, probe.status());
        assertEquals(ALTERNATE_UUID, probe.matches().getFirst().componentUuid());
        assertTrue(index.probe(ALTERNATE_UUID).isKnownLive());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(COMPONENT_UUID).status());

        scheduler.tasks().get(1).run();

        assertTrue(index.isInitializationComplete());
        assertEquals(ALTERNATE_UUID,
                index.probeProjection(key).matches().getFirst().componentUuid());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(COMPONENT_UUID).status());
    }

    @Test
    void repeatedLifecycleChurnExhaustsRetryWithoutAuthoritativeAbsence() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        LoadedNpcIdentityIndex.ProjectionKey key = projectionKey();
        ManualScheduler scheduler = new ManualScheduler();
        List<String> warnings = new CopyOnWriteArrayList<>();
        LoadedNpcIdentityBootstrapService service = service(
                index,
                List.of(target(WORLD_A, scheduler, recorder ->
                        index.recordAdded(observation(ALTERNATE_UUID, key)))),
                warnings
        );

        service.bootstrapUniverse();
        scheduler.tasks().getFirst().run();
        scheduler.tasks().get(1).run();

        assertFalse(index.isInitializationComplete());
        assertEquals(0, service.pendingLocationCount());
        assertEquals(1, warnings.size());
        assertEquals(LoadedNpcIdentityIndex.ProjectionProbeStatus.ONE_MATCH,
                index.probeProjection(key).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());
    }

    @Test
    void concurrentWorldScansCompleteOnlyAfterEveryTaskSucceeds() throws Exception {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch scansStarted = new CountDownLatch(6);
        CountDownLatch releaseScans = new CountDownLatch(1);
        List<LoadedNpcIdentityBootstrapService.ScanTarget> targets = new ArrayList<>();
        for (int worldIndex = 0; worldIndex < 6; worldIndex++) {
            int stableIndex = worldIndex;
            targets.add(new LoadedNpcIdentityBootstrapService.ScanTarget(
                    new LoadedNpcIdentityIndex.Location("world-" + stableIndex, "store-" + stableIndex),
                    executor::execute,
                    recorder -> {
                        scansStarted.countDown();
                        await(releaseScans);
                        recorder.record(new UUID(0L, stableIndex + 1L), null);
                    }
            ));
        }
        LoadedNpcIdentityBootstrapService service = service(index, targets, new CopyOnWriteArrayList<>());
        try {
            service.bootstrapUniverse();

            assertTrue(scansStarted.await(5, TimeUnit.SECONDS));
            assertFalse(index.isInitializationComplete());
            assertEquals(6, service.pendingLocationCount());
            releaseScans.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertTrue(index.isInitializationComplete());
            assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(MISSING_UUID).status());
            for (int worldIndex = 0; worldIndex < 6; worldIndex++) {
                assertTrue(index.probe(new UUID(0L, worldIndex + 1L)).isKnownLive());
            }
        } finally {
            releaseScans.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void schedulingAndScanFailuresStayUnknownAndLogOneActionableWarning() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.recordAdded(COMPONENT_UUID, WORLD_B);
        index.markInitializationComplete();
        List<String> warnings = new CopyOnWriteArrayList<>();
        LoadedNpcIdentityBootstrapService.ScanTarget schedulingFailure =
                new LoadedNpcIdentityBootstrapService.ScanTarget(
                        WORLD_A,
                        task -> {
                            throw new IllegalStateException("schedule failed");
                        },
                        recorder -> {
                        }
                );
        LoadedNpcIdentityBootstrapService.ScanTarget scanFailure =
                new LoadedNpcIdentityBootstrapService.ScanTarget(
                        WORLD_B,
                        Runnable::run,
                        recorder -> {
                            throw new IllegalStateException("scan failed");
                        }
                );
        LoadedNpcIdentityBootstrapService service =
                service(index, List.of(schedulingFailure, scanFailure), warnings);
        CompletableFuture<LoadedNpcIdentitySnapshot> completion = service.awaitCurrentBootstrap();

        service.bootstrapUniverse();

        assertFalse(completion.isDone());
        assertFalse(index.isInitializationComplete());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, index.probe(COMPONENT_UUID).status());
        assertTrue(index.probe(COMPONENT_UUID).isKnownLive());
        assertEquals(0, service.pendingLocationCount());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("absence checks remain UNKNOWN"));
        assertTrue(warnings.getFirst().contains("Retry"));
    }

    @Test
    void supersededScanCannotPublishAfterReplacementAttemptCompletes() throws Exception {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        UUID staleUuid = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID freshUuid = UUID.fromString("66666666-6666-6666-6666-666666666666");
        CountDownLatch staleScanCollected = new CountDownLatch(1);
        CountDownLatch releaseStaleScan = new CountDownLatch(1);
        ExecutorService staleExecutor = Executors.newSingleThreadExecutor();
        LoadedNpcIdentityBootstrapService.ScanTarget staleTarget =
                new LoadedNpcIdentityBootstrapService.ScanTarget(
                        WORLD_A,
                        staleExecutor::execute,
                        recorder -> {
                            recorder.record(staleUuid, null);
                            staleScanCollected.countDown();
                            await(releaseStaleScan);
                        }
                );
        LoadedNpcIdentityBootstrapService.ScanTarget freshTarget =
                new LoadedNpcIdentityBootstrapService.ScanTarget(
                        WORLD_A,
                        Runnable::run,
                        recorder -> recorder.record(freshUuid, null)
                );
        LoadedNpcIdentityBootstrapService service = new LoadedNpcIdentityBootstrapService(
                index,
                new SequencedTargetSource(List.of(List.of(staleTarget), List.of(freshTarget))),
                (message, error) -> {
                }
        );
        CompletableFuture<LoadedNpcIdentitySnapshot> completion = service.awaitCurrentBootstrap();
        try {
            service.bootstrapUniverse();
            assertTrue(staleScanCollected.await(5, TimeUnit.SECONDS));

            service.bootstrapUniverse();

            assertTrue(index.isInitializationComplete());
            assertTrue(index.probe(freshUuid).isKnownLive());
            assertTrue(completion.isDone());
            assertEquals(freshUuid,
                    completion.getNow(null).observations().getFirst().componentUuid());
            releaseStaleScan.countDown();
            staleExecutor.shutdown();
            assertTrue(staleExecutor.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(staleUuid).status());
            assertEquals(List.of("world-a [store-a]"), index.probe(freshUuid).locationNames());
        } finally {
            releaseStaleScan.countDown();
            staleExecutor.shutdownNow();
        }
    }

    @Test
    void startedWorldRetriesPreviouslyFailedUniverseCoverage() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        UUID recoveredUuid = UUID.fromString("77777777-7777-7777-7777-777777777777");
        LoadedNpcIdentityBootstrapService.ScanTarget failedTarget =
                new LoadedNpcIdentityBootstrapService.ScanTarget(
                        WORLD_A,
                        Runnable::run,
                        recorder -> {
                            throw new IllegalStateException("first scan failed");
                        }
                );
        LoadedNpcIdentityBootstrapService.ScanTarget recoveredTarget =
                new LoadedNpcIdentityBootstrapService.ScanTarget(
                        WORLD_A,
                        Runnable::run,
                        recorder -> recorder.record(recoveredUuid, null)
                );
        LoadedNpcIdentityBootstrapService service = new LoadedNpcIdentityBootstrapService(
                index,
                new SequencedTargetSource(List.of(List.of(failedTarget), List.of(recoveredTarget))),
                (message, error) -> {
                }
        );

        service.bootstrapUniverse();
        service.scheduleStartedTarget(recoveredTarget);

        assertTrue(index.isInitializationComplete());
        assertTrue(index.probe(recoveredUuid).isKnownLive());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(MISSING_UUID).status());
    }

    @Test
    void emptyUniverseStaysUnknownUntilAStartedWorldScanSealsCoverage() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        ManualScheduler scheduler = new ManualScheduler();
        LoadedNpcIdentityBootstrapService service = service(index, List.of(), new CopyOnWriteArrayList<>());
        service.bootstrapUniverse();
        CompletableFuture<LoadedNpcIdentitySnapshot> initial = service.awaitCurrentBootstrap();
        assertFalse(initial.isDone());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());

        service.scheduleStartedTarget(target(
                WORLD_A,
                scheduler,
                recorder -> recorder.record(COMPONENT_UUID, COMPONENT_UUID)
        ));

        CompletableFuture<LoadedNpcIdentitySnapshot> startedWorld = service.awaitCurrentBootstrap();
        assertFalse(startedWorld.isDone());
        assertFalse(index.isInitializationComplete());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());
        scheduler.tasks().getFirst().run();
        assertTrue(index.isInitializationComplete());
        assertTrue(startedWorld.isDone());
        assertTrue(index.probe(COMPONENT_UUID).isKnownLive());

        index.recordAdded(observation(ALTERNATE_UUID, projectionKey()));
        LoadedNpcIdentitySnapshot refreshed = service.awaitCurrentBootstrap().getNow(null);
        assertTrue(refreshed.mutationRevision()
                > startedWorld.getNow(null).mutationRevision());
        assertEquals(2, refreshed.observations().size());

        service.clearLocation(WORLD_A);
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(COMPONENT_UUID).status());
    }

    @Test
    void productionEntryPointsUseWorldExecutorScanAndDoNotTrustRemoveWorldEvent() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/LoadedNpcIdentityBootstrapService.java"
        ));
        String snapshotSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLinkedNpcStateSnapshotService.java"
        ));
        String compositionSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/TameworkPersistenceComposition.java"
        ));

        assertTrue(source.contains("public void onStartWorld(@Nonnull StartWorldEvent event)"));
        assertTrue(source.contains("world::execute"));
        assertTrue(source.contains("Query.and(npcType, uuidType)"));
        assertTrue(source.contains("TameworkProjectionIdentityComponent.getComponentType()"));
        assertTrue(source.contains("CommandLinkedNpcStateSnapshotService.projectionKey(marker)"));
        assertTrue(source.contains("identityIndex.replaceLocationObservations"));
        assertFalse(source.contains("RemoveWorldEvent"));
        assertTrue(source.contains("LoadedNpcLocationResolver.resolve(store)"));
        assertTrue(snapshotSource.contains("LoadedNpcLocationResolver.resolve(store)"));
        assertTrue(snapshotSource.contains("LoadedNpcIdentityIndex.LoadedNpcObservation"));
        assertTrue(snapshotSource.contains("projectionKey(marker)"));
        int startWorldRegistration = compositionSource.indexOf("StartWorldEvent.class");
        int allWorldsRegistration = compositionSource.indexOf("AllWorldsLoadedEvent.class");
        int sealedBootstrap = compositionSource.indexOf("identityBootstrap.bootstrapUniverse()");
        assertTrue(startWorldRegistration >= 0
                && allWorldsRegistration > startWorldRegistration
                && sealedBootstrap > allWorldsRegistration);
        assertTrue(compositionSource.contains("identityBootstrap.onStartWorld(event)"));
        assertTrue(compositionSource.contains("startupWorldsLoaded.set(true)"));
        assertTrue(compositionSource.contains("composition.resumeAfterWorldEvidence()"));
        int dormantRegistration = compositionSource.indexOf(
                "TameworkDormantPersistenceRegistration.register("
        );
        int compositionReturn = compositionSource.indexOf(
                "return composition;", dormantRegistration
        );
        assertFalse(compositionSource.substring(
                dormantRegistration, compositionReturn
        ).contains("composition.resumeAfterWorldEvidence()"));
        assertTrue(compositionSource.contains(
                "identityBootstrap.awaitCurrentBootstrap().whenComplete("),
                "World evidence readiness must resume asynchronously without blocking a world thread.");
    }

    private static LoadedNpcIdentityBootstrapService service(
            LoadedNpcIdentityIndex index,
            List<LoadedNpcIdentityBootstrapService.ScanTarget> targets,
            List<String> warnings) {
        return new LoadedNpcIdentityBootstrapService(
                index,
                new FixedTargetSource(targets),
                (message, error) -> warnings.add(message)
        );
    }

    private static LoadedNpcIdentityBootstrapService.ScanTarget target(
            LoadedNpcIdentityIndex.Location location,
            ManualScheduler scheduler,
            LoadedNpcIdentityBootstrapService.IdentityScanner scanner) {
        return new LoadedNpcIdentityBootstrapService.ScanTarget(location, scheduler::schedule, scanner);
    }

    private static LoadedNpcIdentityIndex.ProjectionKey projectionKey() {
        return new LoadedNpcIdentityIndex.ProjectionKey(
                "profile-a", "operation-a", "MANAGED_COOP_RELEASE",
                "slot-a", COMPONENT_UUID, 1L
        );
    }

    private static LoadedNpcIdentityIndex.LoadedNpcObservation observation(
            UUID componentUuid,
            LoadedNpcIdentityIndex.ProjectionKey key) {
        return new LoadedNpcIdentityIndex.LoadedNpcObservation(
                componentUuid, componentUuid, WORLD_A, key
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating bootstrap test.", error);
        }
    }

    private static final class ManualScheduler {
        private final List<Runnable> tasks = new CopyOnWriteArrayList<>();

        private void schedule(Runnable task) {
            tasks.add(task);
        }

        private List<Runnable> tasks() {
            return tasks;
        }
    }

    private record FixedTargetSource(List<LoadedNpcIdentityBootstrapService.ScanTarget> targets)
            implements LoadedNpcIdentityBootstrapService.TargetSource {
        private FixedTargetSource {
            targets = List.copyOf(targets);
        }

        @Override
        public List<LoadedNpcIdentityBootstrapService.ScanTarget> snapshotUniverseTargets() {
            return targets;
        }

        @Override
        public LoadedNpcIdentityBootstrapService.ScanTarget targetForWorld(World world) {
            if (targets.isEmpty()) {
                throw new IllegalStateException("No started-world target configured.");
            }
            return targets.getFirst();
        }
    }

    private static final class SequencedTargetSource
            implements LoadedNpcIdentityBootstrapService.TargetSource {
        private final List<List<LoadedNpcIdentityBootstrapService.ScanTarget>> attempts;
        private final AtomicInteger nextAttempt = new AtomicInteger();

        private SequencedTargetSource(
                List<List<LoadedNpcIdentityBootstrapService.ScanTarget>> attempts) {
            this.attempts = attempts.stream().map(List::copyOf).toList();
        }

        @Override
        public List<LoadedNpcIdentityBootstrapService.ScanTarget> snapshotUniverseTargets() {
            int index = nextAttempt.getAndIncrement();
            if (index >= attempts.size()) {
                throw new IllegalStateException("No bootstrap attempt configured at index " + index + ".");
            }
            return attempts.get(index);
        }

        @Override
        public LoadedNpcIdentityBootstrapService.ScanTarget targetForWorld(World world) {
            throw new UnsupportedOperationException("Started-world lookup is not used by this test.");
        }
    }
}
