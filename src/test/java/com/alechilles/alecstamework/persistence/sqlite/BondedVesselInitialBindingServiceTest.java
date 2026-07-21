package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.BondedVesselBoundEvent;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselInitialBindingService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for the capture-to-generation-one crash boundary. */
class BondedVesselInitialBindingServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void successfulCaptureCreatesExactlyOneGenerationOneBindingAndEvent() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("initial-bind.sqlite")) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "dragon-role", "CAPTURED", "world", 7L);
            BondedVesselRepository repository = new BondedVesselRepository(
                    harness.connections, harness.queue);
            AtomicInteger finalized = new AtomicInteger();
            List<TameworkEvent> events = new ArrayList<>();
            AtomicLong clock = new AtomicLong(100L);
            BondedVesselInitialBindingService service = service(
                    repository,
                    request -> {
                        finalized.incrementAndGet();
                        return CompletableFuture.completedFuture(new BondedVesselInitialBindingService
                                .SourceFinalization(
                                BondedVesselInitialBindingService.SourceStatus.REPLACED,
                                "source-replaced"));
                    },
                    events,
                    clock);
            var request = request(profileId, owner);

            assertEquals(BondedVesselInitialBindingService.Status.COMMITTED,
                    service.bind(request).toCompletableFuture().join().status());
            assertEquals(BondedVesselInitialBindingService.Status.COMMITTED,
                    service.bind(request).toCompletableFuture().join().status());

            BondedVesselBindingRecord binding = repository.findBinding(
                    request.bindingId().toString());
            assertEquals(1L, binding.generation());
            assertEquals(BondedVesselBindingRecord.LifecycleState.STORED,
                    binding.lifecycleState());
            assertNull(binding.activeOperationId());
            assertEquals("stored-stone", binding.lastItemId());
            assertEquals(1, finalized.get());
            assertEquals(1, events.size());
            BondedVesselBoundEvent event = (BondedVesselBoundEvent) events.get(0);
            assertEquals(request.bindingId(), event.bindingId());
            assertFalse(event.recovered());
        }
    }

    @Test
    void sourceChangeAfterDurableBindQuarantinesWithoutCommitting() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("initial-bind-quarantine.sqlite")) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "dragon-role", "CAPTURED", "world", 7L);
            BondedVesselRepository repository = new BondedVesselRepository(
                    harness.connections, harness.queue);
            List<TameworkEvent> events = new ArrayList<>();
            BondedVesselInitialBindingService service = service(
                    repository,
                    request -> CompletableFuture.completedFuture(new BondedVesselInitialBindingService
                            .SourceFinalization(
                            BondedVesselInitialBindingService.SourceStatus.SOURCE_CHANGED,
                            "capture-source-changed")),
                    events,
                    new AtomicLong(200L));
            var request = request(profileId, owner);

            assertEquals(BondedVesselInitialBindingService.Status.QUARANTINED,
                    service.bind(request).toCompletableFuture().join().status());

            BondedVesselBindingRecord binding = repository.findBinding(
                    request.bindingId().toString());
            assertEquals(BondedVesselBindingRecord.ItemProjectionStatus.QUARANTINED,
                    binding.itemProjectionStatus());
            assertEquals(BondedVesselOperationRecord.State.QUARANTINED,
                    repository.findOperation(request.operationId().toString()).state());
            assertEquals(0, events.size());
        }
    }

    @Test
    void recoveryContinuesAppliedInitialBindWithoutCreatingAnotherBinding() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("initial-bind-recovery.sqlite")) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "dragon-role", "CAPTURED", "world", 7L);
            BondedVesselRepository repository = new BondedVesselRepository(
                    harness.connections, harness.queue);
            AtomicReference<BondedVesselInitialBindingService.SourceStatus> source =
                    new AtomicReference<>(BondedVesselInitialBindingService.SourceStatus.INDETERMINATE);
            List<TameworkEvent> events = new ArrayList<>();
            BondedVesselInitialBindingService service = service(
                    repository,
                    request -> CompletableFuture.completedFuture(new BondedVesselInitialBindingService
                            .SourceFinalization(source.get(), "source-state")),
                    events,
                    new AtomicLong(300L));
            var request = request(profileId, owner);

            assertEquals(BondedVesselInitialBindingService.Status.INDETERMINATE,
                    service.bind(request).toCompletableFuture().join().status());
            assertEquals(BondedVesselOperationRecord.State.APPLIED,
                    repository.findOperation(request.operationId().toString()).state());

            source.set(BondedVesselInitialBindingService.SourceStatus.ALREADY_REPLACED);
            assertEquals(BondedVesselInitialBindingService.Status.COMMITTED,
                    service.recover(request).toCompletableFuture().join().status());
            assertEquals(BondedVesselOperationRecord.State.COMMITTED,
                    repository.findOperation(request.operationId().toString()).state());
            assertEquals(1, events.size());
            assertEquals(true, ((BondedVesselBoundEvent) events.get(0)).recovered());
        }
    }

    private static BondedVesselInitialBindingService service(
            BondedVesselRepository repository,
            BondedVesselInitialBindingService.SourceFinalizer finalizer,
            List<TameworkEvent> events,
            AtomicLong clock) {
        return new BondedVesselInitialBindingService(
                repository, finalizer, events::add, Runnable::run, clock::getAndIncrement);
    }

    private static BondedVesselInitialBindingService.Request request(
            String profileId,
            UUID owner) {
        return new BondedVesselInitialBindingService.Request(
                UUID.randomUUID(), UUID.randomUUID(), "tamework", "capture-attempt-1",
                "capture-1", profileId, owner, 7L, "dragon-stone", 4L,
                "empty-stone", "stored-stone", "empty-fingerprint", "stored-fingerprint",
                "{\"holder\":\"owner\",\"slot\":2}",
                "{\"holder\":\"owner\",\"slot\":2,\"generation\":1}",
                "{\"mode\":\"BONDED\",\"generation\":1}", "population-capture-1");
    }

    private HydragonPersistenceTestHarness harness(String file) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(file));
    }
}
