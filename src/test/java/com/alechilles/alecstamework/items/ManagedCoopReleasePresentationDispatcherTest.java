package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.ComponentResult;
import com.alechilles.alecstamework.items.ManagedCoopCompositeIndexRefreshService.ComponentStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleasePresentationDispatcher.DispatchStatus;
import com.alechilles.alecstamework.items.ManagedCoopReleasePresentationDispatcher.LiveProjection;
import com.alechilles.alecstamework.items.ManagedCoopReleasePresentationDispatcher.LiveResolution;
import com.alechilles.alecstamework.items.ManagedCoopReleasePresentationDispatcher.StateEvidence;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.PresentationCommand;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCoopReleasePresentationDispatcherTest {
    private static final UUID SOURCE = uuid(1);
    private static final UUID PLANNED = uuid(2);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 10, 20, 30);
    private ComponentRegistry<EntityStore> registry;
    private Store<EntityStore> store;

    @BeforeEach
    void setUp() {
        registry = new ComponentRegistry<>();
        store = registry.addStore(null, null);
    }

    @AfterEach
    void tearDown() {
        registry.removeStore(store);
        registry.shutdown();
    }

    @Test
    void immutableQueueReResolvesAndAppliesDeferredWorkExactlyOnce() {
        Bundle bundle = bundle();
        FakeWorlds worlds = new FakeWorlds();
        AtomicInteger stateLoads = new AtomicInteger();
        AtomicInteger applies = new AtomicInteger();
        AtomicReference<CoopResidentStateRestorer.PostAddWork> appliedWork =
                new AtomicReference<>();
        ManagedCoopReleasePresentationDispatcher dispatcher = dispatcher(
                command -> {
                    stateLoads.incrementAndGet();
                    return StateEvidence.found(bundle.resident());
                },
                worlds,
                (command, projection, work) -> {
                    applies.incrementAndGet();
                    appliedWork.set(work);
                }
        );

        dispatcher.dispatch(bundle.command());
        dispatcher.dispatch(bundle.command());

        assertEquals(1, worlds.enqueueCalls);
        assertEquals(bundle.command(), worlds.queuedCommand);
        assertEquals(DispatchStatus.QUEUED,
                dispatcher.view(bundle.command().operationId()).status());
        assertEquals(0, worlds.resolveCalls);
        assertEquals(0, stateLoads.get());
        assertEquals(0, applies.get());
        worlds.resolution = LiveResolution.found(liveProjection(marker()));
        worlds.runQueued();
        dispatcher.dispatch(bundle.command());

        assertEquals(DispatchStatus.APPLIED,
                dispatcher.view(bundle.command().operationId()).status());
        assertEquals(1, worlds.enqueueCalls);
        assertEquals(1, worlds.resolveCalls);
        assertEquals(1, stateLoads.get());
        assertEquals(1, applies.get());
        assertEquals("Henrietta", appliedWork.get().displayName());
        assertEquals(0.75, appliedWork.get().healthPercent());
        assertTrue(appliedWork.get().hasAttachmentWork());
    }

    @Test
    void untrustedOrNonDeployedStateRejectsBeforeLiveResolution() {
        Bundle bundle = bundle();
        FakeWorlds unavailableWorlds = new FakeWorlds();
        ManagedCoopReleasePresentationDispatcher unavailable = dispatcher(
                command -> StateEvidence.unavailable("indexes_untrusted"),
                unavailableWorlds,
                (command, projection, work) -> { }
        );
        unavailable.dispatch(bundle.command());
        unavailableWorlds.runQueued();

        FakeWorlds releasingWorlds = new FakeWorlds();
        ResidentRecord releasing = residentWith(
                bundle.resident(), ResidentState.RELEASING, SOURCE, null, 1L,
                bundle.resident().snapshotJson(), bundle.resident().snapshotHash());
        ManagedCoopReleasePresentationDispatcher releasingDispatcher = dispatcher(
                command -> StateEvidence.found(releasing),
                releasingWorlds,
                (command, projection, work) -> { }
        );
        releasingDispatcher.dispatch(bundle.command());
        releasingWorlds.runQueued();

        assertEquals(DispatchStatus.REJECTED,
                unavailable.view(bundle.command().operationId()).status());
        assertTrue(unavailable.view(bundle.command().operationId()).detail()
                .contains("indexes_untrusted"));
        assertEquals(0, unavailableWorlds.resolveCalls);
        assertEquals(DispatchStatus.REJECTED,
                releasingDispatcher.view(bundle.command().operationId()).status());
        assertTrue(releasingDispatcher.view(bundle.command().operationId()).detail()
                .contains("exact finalized deployment"));
        assertEquals(0, releasingWorlds.resolveCalls);
    }

    @Test
    void corruptSnapshotAndMarkerMismatchNeverApplyPresentation() {
        Bundle bundle = bundle();
        AtomicInteger applies = new AtomicInteger();
        ResidentRecord corrupt = residentWith(
                bundle.resident(), ResidentState.DEPLOYED, PLANNED, PLANNED, 2L,
                "{", bundle.resident().snapshotHash());
        FakeWorlds corruptWorlds = new FakeWorlds();
        ManagedCoopReleasePresentationDispatcher corruptDispatcher = dispatcher(
                command -> StateEvidence.found(corrupt),
                corruptWorlds,
                (command, projection, work) -> applies.incrementAndGet()
        );
        corruptDispatcher.dispatch(bundle.command());
        corruptWorlds.runQueued();

        FakeWorlds markerWorlds = new FakeWorlds();
        markerWorlds.resolution = LiveResolution.found(liveProjection(
                marker("other-operation", 1L)));
        ManagedCoopReleasePresentationDispatcher markerDispatcher = dispatcher(
                command -> StateEvidence.found(bundle.resident()),
                markerWorlds,
                (command, projection, work) -> applies.incrementAndGet()
        );
        markerDispatcher.dispatch(bundle.command());
        markerWorlds.runQueued();

        assertEquals(DispatchStatus.REJECTED,
                corruptDispatcher.view(bundle.command().operationId()).status());
        assertEquals(0, corruptWorlds.resolveCalls);
        assertEquals(DispatchStatus.REJECTED,
                markerDispatcher.view(bundle.command().operationId()).status());
        assertEquals(1, markerWorlds.resolveCalls);
        assertEquals(0, applies.get());
    }

    @Test
    void queueFailureIsTerminalAndNeverLoadsState() {
        Bundle bundle = bundle();
        FakeWorlds worlds = new FakeWorlds();
        worlds.acceptQueue = false;
        AtomicInteger stateLoads = new AtomicInteger();
        ManagedCoopReleasePresentationDispatcher dispatcher = dispatcher(
                command -> {
                    stateLoads.incrementAndGet();
                    return StateEvidence.found(bundle.resident());
                },
                worlds,
                (command, projection, work) -> { }
        );

        dispatcher.dispatch(bundle.command());
        dispatcher.dispatch(bundle.command());

        assertEquals(DispatchStatus.REJECTED,
                dispatcher.view(bundle.command().operationId()).status());
        assertEquals(1, worlds.enqueueCalls);
        assertEquals(0, stateLoads.get());
        assertEquals(0, worlds.resolveCalls);
    }

    @Test
    void trustedPairedIndexesRequireFinalizedOperationAbsenceAndDeployedResident() {
        Bundle bundle = bundle();
        ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        ManagedCoopLifecycleOperationIndex operations = new ManagedCoopLifecycleOperationIndex();
        ManagedCoopCompositeIndexRefreshService composite =
                new ManagedCoopCompositeIndexRefreshService(
                        residents,
                        operations,
                        () -> {
                            residents.rebuild(
                                    ManagedCoopReadResult.loaded(List.of(authority())),
                                    ManagedCoopReadResult.loaded(List.of(bundle.resident())));
                            return new ComponentResult(
                                    ComponentStatus.REFRESHED,
                                    residents.snapshot().revision(), null);
                        },
                        () -> {
                            operations.rebuild(ManagedCoopReadResult.loaded(List.of()));
                            return new ComponentResult(
                                    ComponentStatus.REFRESHED,
                                    operations.snapshot().revision(), null);
                        }
                );
        assertTrue(composite.refresh().refreshed());
        ManagedCoopReleasePresentationDispatcher.IndexStateEvidence evidence =
                new ManagedCoopReleasePresentationDispatcher.IndexStateEvidence(
                        composite, residents, operations);

        StateEvidence found = evidence.load(bundle.command());
        composite.refresh();
        residents.revokeTrust();
        StateEvidence untrusted = evidence.load(bundle.command());

        assertEquals(ManagedCoopReleasePresentationDispatcher.EvidenceStatus.FOUND,
                found.status());
        assertEquals(bundle.resident(), found.resident());
        assertEquals(ManagedCoopReleasePresentationDispatcher.EvidenceStatus.UNAVAILABLE,
                untrusted.status());
    }

    @Test
    void queuedStateRetainsNoLiveHandlesAndProductionPathNeverSpawnsOrRemaps()
            throws Exception {
        Class<?> entryType = Class.forName(
                ManagedCoopReleasePresentationDispatcher.class.getName() + "$DispatchEntry");
        for (Field field : entryType.getDeclaredFields()) {
            String type = field.getType().getName();
            assertFalse(type.contains("component.Ref"));
            assertFalse(type.contains("component.Store"));
            assertFalse(type.contains("universe.world.World"));
            assertFalse(type.contains("NPCEntity"));
        }
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "ManagedCoopReleasePresentationDispatcher.java"));
        String applier = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "HytaleManagedCoopReleasePresentationApplier.java"));
        assertTrue(source.contains("world.execute(() -> consumer.accept(command))"));
        assertTrue(source.contains("new HytaleManagedCoopReleasePresentationApplier("));
        int stateRestore = applier.indexOf("postAdd.apply(");
        int effects = applier.indexOf("effects.playTransitionEffects(");
        assertTrue(stateRestore >= 0 && effects > stateRestore,
                "release effects must run only after finalized state presentation");
        assertFalse(source.contains("spawnEntity("));
        assertFalse(source.contains("projectionSpawner"));
        assertFalse(source.contains("remap("));
        assertFalse(applier.contains("spawnEntity("));
        assertFalse(applier.contains("remap("));
    }

    private ManagedCoopReleasePresentationDispatcher dispatcher(
            ManagedCoopReleasePresentationDispatcher.StateEvidenceGateway evidence,
            FakeWorlds worlds,
            ManagedCoopReleasePresentationDispatcher.PresentationApplier applier) {
        return new ManagedCoopReleasePresentationDispatcher(
                evidence,
                worlds,
                new CoopResidentStateSnapshotCodec(),
                new CoopResidentStateRestorer(),
                applier
        );
    }

    private LiveProjection liveProjection(TameworkProjectionIdentityComponent marker) {
        NPCEntity npc = new NPCEntity();
        npc.setLegacyUUID(PLANNED);
        return new LiveProjection(new Ref<>(null, 7), npc, store, marker);
    }

    private Bundle bundle() {
        CoopResidentStateSnapshotCodec codec = new CoopResidentStateSnapshotCodec();
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot snapshot =
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        SOURCE, "coop-a", 2, "tamed_test",
                        null, null, null,
                        new TameworkNpcNameComponent(
                                "Henrietta", SOURCE, -100L,
                                TameworkNpcNameComponent.NameSource.Player),
                        null, null, null, null, null, null, null,
                        new TameworkAttachmentsComponent("attachments", Map.of("head", "crest")),
                        0.75, -100L
                );
        String snapshotJson = codec.encode(snapshot);
        String hash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshotJson);
        ResidentRecord resident = new ResidentRecord(
                "resident-a", AUTHORITY, "coop-a", 2, "profile-a", "tamed_test",
                PLANNED, SOURCE, PLANNED, snapshotJson, hash, 1,
                ResidentState.DEPLOYED, 2L, true, -100L, 500L, -100L, 500L
        );
        PresentationCommand command = new PresentationCommand(
                "operation-a", "profile-a", "resident-a", AUTHORITY, "coop-a", 2,
                SOURCE, PLANNED, PLANNED, hash, 0L
        );
        return new Bundle(command, resident);
    }

    private ResidentRecord residentWith(ResidentRecord original,
                                        ResidentState state,
                                        UUID residentUuid,
                                        UUID deployedUuid,
                                        long generation,
                                        String snapshotJson,
                                        String snapshotHash) {
        return new ResidentRecord(
                original.residentId(), original.authorityKey(), original.coopId(),
                original.residentSlot(), original.profileId(), original.roleId(), residentUuid,
                original.sourceNpcUuid(), deployedUuid, snapshotJson, snapshotHash,
                original.snapshotVersion(), state, generation, true, original.capturedAtMs(),
                original.releasedAtMs(), original.createdAtMs(), original.updatedAtMs()
        );
    }

    private TameworkProjectionIdentityComponent marker() {
        return marker("operation-a", 1L);
    }

    private TameworkProjectionIdentityComponent marker(String operationId, long generation) {
        return new TameworkProjectionIdentityComponent(
                "profile-a", operationId,
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                AUTHORITY.slotKey(2), SOURCE, generation
        );
    }

    private AuthorityRecord authority() {
        return new AuthorityRecord(
                AUTHORITY.authorityId(), AUTHORITY, "coop-a", AuthorityState.TWORK_MANAGED,
                true, 1, -100L, -90L, null
        );
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private record Bundle(PresentationCommand command, ResidentRecord resident) {
    }

    private static final class FakeWorlds
            implements ManagedCoopReleasePresentationDispatcher.WorldThreadGateway {
        private int enqueueCalls;
        private int resolveCalls;
        private boolean acceptQueue = true;
        private PresentationCommand queuedCommand;
        private ManagedCoopReleasePresentationDispatcher.WorldThreadConsumer queuedConsumer;
        private LiveResolution resolution =
                LiveResolution.unavailable("projection_not_configured");

        @Override
        public boolean enqueue(PresentationCommand command,
                               ManagedCoopReleasePresentationDispatcher.WorldThreadConsumer consumer) {
            enqueueCalls++;
            if (!acceptQueue) {
                return false;
            }
            queuedCommand = command;
            queuedConsumer = consumer;
            return true;
        }

        @Override
        public LiveResolution resolve(PresentationCommand command) {
            resolveCalls++;
            return resolution;
        }

        private void runQueued() {
            assertNotNull(queuedCommand);
            assertNotNull(queuedConsumer);
            ManagedCoopReleasePresentationDispatcher.WorldThreadConsumer consumer = queuedConsumer;
            PresentationCommand command = queuedCommand;
            queuedConsumer = null;
            queuedCommand = null;
            consumer.accept(command);
        }
    }
}
