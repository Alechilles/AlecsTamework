package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

        service.bootstrapUniverse();

        assertFalse(index.isInitializationComplete());
        assertEquals(2, service.pendingLocationCount());
        scheduler.tasks().get(0).run();
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, index.probe(COMPONENT_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ONE_LOCATION, index.probe(LEGACY_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());
        assertEquals(1, service.pendingLocationCount());

        scheduler.tasks().get(1).run();

        assertTrue(index.isInitializationComplete());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(MISSING_UUID).status());
        assertEquals(0, service.pendingLocationCount());
        assertTrue(warnings.isEmpty());
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

        service.bootstrapUniverse();

        assertFalse(index.isInitializationComplete());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(COMPONENT_UUID).status());
        assertFalse(index.probe(COMPONENT_UUID).isKnownLive());
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
        try {
            service.bootstrapUniverse();
            assertTrue(staleScanCollected.await(5, TimeUnit.SECONDS));

            service.bootstrapUniverse();

            assertTrue(index.isInitializationComplete());
            assertTrue(index.probe(freshUuid).isKnownLive());
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
    void startedWorldRevokesAbsenceUntilItsScanAndExplicitClearRemovesEvidence() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        ManualScheduler scheduler = new ManualScheduler();
        LoadedNpcIdentityBootstrapService service = service(index, List.of(), new CopyOnWriteArrayList<>());
        service.bootstrapUniverse();
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.ABSENT, index.probe(MISSING_UUID).status());

        service.scheduleStartedTarget(target(
                WORLD_A,
                scheduler,
                recorder -> recorder.record(COMPONENT_UUID, COMPONENT_UUID)
        ));

        assertFalse(index.isInitializationComplete());
        assertEquals(LoadedNpcIdentityIndex.ProbeStatus.UNKNOWN, index.probe(MISSING_UUID).status());
        scheduler.tasks().getFirst().run();
        assertTrue(index.isInitializationComplete());
        assertTrue(index.probe(COMPONENT_UUID).isKnownLive());

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
        String pluginSource = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"
        ));

        assertTrue(source.contains("public void onStartWorld(@Nonnull StartWorldEvent event)"));
        assertTrue(source.contains("world::execute"));
        assertTrue(source.contains("Query.and(npcType, uuidType)"));
        assertTrue(source.contains("legacyNpcUuid != null && !legacyNpcUuid.equals(componentUuid)"));
        assertFalse(source.contains("RemoveWorldEvent"));
        assertTrue(source.contains("LoadedNpcLocationResolver.resolve(store)"));
        assertTrue(snapshotSource.contains("LoadedNpcLocationResolver.resolve(store)"));
        assertTrue(snapshotSource.contains("legacyNpcUuid != null && !legacyNpcUuid.equals(componentUuid)"));
        int startWorldRegistration = pluginSource.indexOf("StartWorldEvent.class");
        int initialBootstrap = pluginSource.indexOf("loadedNpcIdentityBootstrapService.bootstrapUniverse()");
        assertTrue(startWorldRegistration >= 0 && initialBootstrap > startWorldRegistration);
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
