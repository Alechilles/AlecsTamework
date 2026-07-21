package com.alechilles.alecstamework.vessels.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselHeldItemLocatorRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationStatus;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.vessels.BondedVesselEvidenceAuthority;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ProductionBondedVesselEvidenceAuthorityTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BINDING = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID NPC = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void exactReadRevalidatesEveryAddressAndFingerprintField() {
        BondedVesselSourceItemEvidence expected = evidence(7L, "fingerprint-g4");
        FakeInventory inventory = new FakeInventory();
        inventory.read = found(expected, 4L);
        ProductionBondedVesselEvidenceAuthority authority = authority(inventory);

        BondedVesselEvidenceAuthority.SourceObservation source = authority.observe(
                context(expected)).toCompletableFuture().join();
        BondedVesselEvidenceAuthority.HeldItemObservation held = authority.resolveHeldItem(
                ACTOR, expected).toCompletableFuture().join();

        assertEquals(BondedVesselEvidenceAuthority.Status.EXACT, source.status());
        assertTrue(source.exactlyMatches(context(expected)));
        assertEquals(BondedVesselEvidenceAuthority.HeldItemStatus.EXACT, held.status());
        assertEquals(BINDING, held.bindingId());
        assertEquals("profile-1", held.profileId());
        assertEquals(4L, held.generation());
        assertEquals(ACTOR, inventory.lastActor);
    }

    @Test
    void movedOrRevisionChangedItemFailsClosed() {
        BondedVesselSourceItemEvidence expected = evidence(7L, "fingerprint-g4");
        BondedVesselSourceItemEvidence changed = evidence(8L, "fingerprint-g4");
        FakeInventory inventory = new FakeInventory();
        inventory.read = found(changed, 4L);
        ProductionBondedVesselEvidenceAuthority authority = authority(inventory);

        BondedVesselEvidenceAuthority.SourceObservation source = authority.observe(
                context(expected)).toCompletableFuture().join();
        BondedVesselEvidenceAuthority.HeldItemObservation held = authority.resolveHeldItem(
                ACTOR, expected).toCompletableFuture().join();

        assertEquals(BondedVesselEvidenceAuthority.Status.CHANGED, source.status());
        assertEquals(BondedVesselEvidenceAuthority.HeldItemStatus.CHANGED, held.status());
        assertFalse(held.exactlyMatches(expected));
    }

    @Test
    void sourceFinalizationDelegatesOneExactCasWithFrozenReplacementIdentity() {
        BondedVesselSourceItemEvidence expected = evidence(7L, "fingerprint-g4");
        FakeInventory inventory = new FakeInventory();
        inventory.cas = new ProductionBondedVesselEvidenceAuthority.ExactCasResult(
                ProductionBondedVesselEvidenceAuthority.ExactCasStatus.REPLACED,
                "source-replaced", "{\"holder\":\"owner\"}");
        ProductionBondedVesselEvidenceAuthority authority = authority(inventory);

        BondedVesselEvidenceAuthority.SourceFinalization result = authority.finalizeSource(
                operation(), context(expected)).toCompletableFuture().join();

        assertEquals(BondedVesselEvidenceAuthority.FinalizationStatus.FINALIZED, result.status());
        assertNotNull(inventory.lastCas);
        assertEquals(expected, inventory.lastCas.expected());
        assertEquals(BINDING, inventory.lastCas.replacement().bindingId());
        assertEquals("profile-1", inventory.lastCas.replacement().profileId());
        assertEquals(5L, inventory.lastCas.replacement().generation());
        assertEquals("stored-g5", inventory.lastCas.replacement().fingerprint());
    }

    @Test
    void copiedStaleProjectionCannotValidateAgainstCanonicalGeneration() {
        FakeInventory inventory = new FakeInventory();
        ProductionBondedVesselEvidenceAuthority.ProjectionEvidencePort projections =
                new ProductionBondedVesselEvidenceAuthority.ProjectionEvidencePort() {
                    @Override
                    public ProductionBondedVesselEvidenceAuthority.ProjectionObservation observe(
                            BondedVesselBindingRecord binding,
                            BondedVesselProjectionValidationRequest request) {
                        return new ProductionBondedVesselEvidenceAuthority.ProjectionObservation(
                                ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.EXACT,
                                "observed-copy", BINDING, "profile-1", 3L, "fingerprint-g4");
                    }

                    @Override
                    public ProductionBondedVesselEvidenceAuthority.PortReadiness readiness() {
                        return ready();
                    }
                };
        ProductionBondedVesselEvidenceAuthority authority =
                new ProductionBondedVesselEvidenceAuthority(inventory, projections);

        var result = authority.validateProjection(binding(), new BondedVesselProjectionValidationRequest(
                BINDING, 4L,
                BondedVesselProjectionValidationRequest.ProjectionKind.ITEM,
                "fingerprint-g4"));

        assertEquals(BondedVesselProjectionValidationStatus.STALE_GENERATION, result.status());
        assertTrue(result.authoritative());
    }

    @Test
    void partialPortReadinessNeverAdvertisesCapability() {
        FakeInventory inventory = new FakeInventory();
        inventory.readiness = new ProductionBondedVesselEvidenceAuthority.PortReadiness(
                true, true, false, false, "inventory-cas-unavailable");
        ProductionBondedVesselEvidenceAuthority authority = authority(inventory);

        assertFalse(authority.isCapabilityReady());
        assertFalse(authority.readiness().exactInventoryCasReady());
        assertEquals("inventory-cas-unavailable", authority.readiness().reason());
    }

    @Test
    void heldSlotLocatorGeneratesExactEvidenceWithoutCallerRevisionOrFingerprint() {
        FakeInventory inventory = new FakeInventory();
        inventory.snapshot = new ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshot(
                ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshotStatus.FOUND,
                "found", 2, 19L,
                new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        "dragon-stone-dead", BINDING, "profile-1", 4L,
                        "dragon-vessel", BondedVesselState.DEAD));
        ProductionBondedVesselEvidenceAuthority authority = authority(inventory);

        var located = authority.locateHeldSlot(new ProductionBondedVesselEvidenceAuthority.HeldSlotLocator(
                ACTOR, "player:" + ACTOR, "hotbar", 2, BondedVesselState.DEAD,
                "dragon-stone-dead")).toCompletableFuture().join();

        assertEquals(ProductionBondedVesselEvidenceAuthority.LocatedHeldItemStatus.EXACT,
                located.status());
        assertEquals(19L, located.sourceEvidence().inventoryRevision());
        assertTrue(located.sourceEvidence().itemFingerprint().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void unavailableMonotonicRevisionKeepsLocatorAndCapabilityFailClosed() {
        FakeInventory inventory = new FakeInventory();
        inventory.readiness = new ProductionBondedVesselEvidenceAuthority.PortReadiness(
                false, true, true, false, "inventory-revision-unavailable");
        ProductionBondedVesselEvidenceAuthority authority = authority(inventory);

        var located = authority.locateHeldSlot(new ProductionBondedVesselEvidenceAuthority.HeldSlotLocator(
                ACTOR, "player:" + ACTOR, "hotbar", 2, BondedVesselState.DEAD,
                null)).toCompletableFuture().join();

        assertFalse(authority.isCapabilityReady());
        assertEquals(ProductionBondedVesselEvidenceAuthority.LocatedHeldItemStatus.UNAVAILABLE,
                located.status());
        assertEquals("held-slot-revision-snapshot-unavailable", located.reason());
    }

    @Test
    void publicLocatorOverrideReturnsGeneratedExactObservationForCoordinatorResolution() {
        FakeInventory inventory = new FakeInventory();
        inventory.snapshot = new ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshot(
                ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshotStatus.FOUND,
                "found", 2, 19L,
                new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        "dragon-stone-dead", BINDING, "profile-1", 4L,
                        "dragon-vessel", BondedVesselState.DEAD));
        ProductionBondedVesselEvidenceAuthority authority = authority(inventory);

        var observation = authority.locateHeldItem(new BondedVesselHeldItemLocatorRequest(
                ACTOR, "player:" + ACTOR, "hotbar", 2,
                "dragon-stone-dead", BondedVesselState.DEAD))
                .toCompletableFuture().join();

        assertEquals(BondedVesselEvidenceAuthority.HeldItemStatus.EXACT, observation.status());
        assertEquals(19L, observation.observedEvidence().inventoryRevision());
        assertEquals(BINDING, observation.bindingId());
        assertEquals(4L, observation.generation());
    }

    private static ProductionBondedVesselEvidenceAuthority authority(FakeInventory inventory) {
        return new ProductionBondedVesselEvidenceAuthority(inventory,
                new ProductionBondedVesselEvidenceAuthority.ProjectionEvidencePort() {
                    @Override
                    public ProductionBondedVesselEvidenceAuthority.ProjectionObservation observe(
                            BondedVesselBindingRecord binding,
                            BondedVesselProjectionValidationRequest request) {
                        return new ProductionBondedVesselEvidenceAuthority.ProjectionObservation(
                                ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.EXACT,
                                "projection-exact", BINDING, "profile-1", 4L,
                                request.projectionFingerprint());
                    }

                    @Override
                    public ProductionBondedVesselEvidenceAuthority.PortReadiness readiness() {
                        return ready();
                    }
                });
    }

    private static ProductionBondedVesselEvidenceAuthority.PortReadiness ready() {
        return new ProductionBondedVesselEvidenceAuthority.PortReadiness(
                true, true, true, true, "ready");
    }

    private static ProductionBondedVesselEvidenceAuthority.ExactItemRead found(
            BondedVesselSourceItemEvidence evidence,
            long generation
    ) {
        return new ProductionBondedVesselEvidenceAuthority.ExactItemRead(
                ProductionBondedVesselEvidenceAuthority.ExactReadStatus.FOUND,
                "found", evidence, BINDING, "profile-1", generation);
    }

    private static BondedVesselSourceItemEvidence evidence(long revision, String fingerprint) {
        return new BondedVesselSourceItemEvidence(
                "dragon-stone-stored", "player:" + ACTOR, "hotbar", 2,
                revision, fingerprint);
    }

    private static BondedVesselTransitionContext context(BondedVesselSourceItemEvidence evidence) {
        return new BondedVesselTransitionContext(
                evidence.itemId(), evidence.holderEvidenceId(), evidence.containerPath(),
                evidence.inventorySlot(), evidence.inventoryRevision(), evidence.itemFingerprint(),
                NPC, null);
    }

    private static BondedVesselBindingRecord binding() {
        return new BondedVesselBindingRecord(
                BINDING.toString(), "profile-1", 4L, "dragon-vessel", 3L,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                ACTOR, 11L, NPC,
                new BondedVesselBindingRecord.PhysicalLocation("world", 1, 2),
                0L, "dragon-stone-active", "{}", "operation-1", null,
                2L, 100L, 110L, 0L);
    }

    private static BondedVesselOperationRecord operation() {
        return new BondedVesselOperationRecord(
                "operation-1", "hydragon", "store-1", null,
                BINDING.toString(), "profile-1", BondedVesselOperationRecord.Action.STORE,
                BondedVesselOperationRecord.State.APPLYING,
                4L, 5L, 11L, "dragon-vessel", 3L,
                BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, 100L, "dragon-stone-stored", "dragon-stone-stored",
                "fingerprint-g4", "stored-g5", "{}", "{}", null,
                NPC, null, "APPLYING", 0L, 100L, 110L, 0L, 0L);
    }

    private static final class FakeInventory
            implements ProductionBondedVesselEvidenceAuthority.ExactInventoryPort {
        private ProductionBondedVesselEvidenceAuthority.ExactItemRead read;
        private ProductionBondedVesselEvidenceAuthority.ExactCasResult cas =
                new ProductionBondedVesselEvidenceAuthority.ExactCasResult(
                        ProductionBondedVesselEvidenceAuthority.ExactCasStatus.UNAVAILABLE,
                        "not-configured", null);
        private ProductionBondedVesselEvidenceAuthority.PortReadiness readiness = ready();
        private ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshot snapshot;
        private UUID lastActor;
        private ProductionBondedVesselEvidenceAuthority.ExactCasRequest lastCas;

        @Override
        public CompletableFuture<ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshot> snapshotHeldSlot(
                ProductionBondedVesselEvidenceAuthority.HeldSlotLocator locator) {
            return CompletableFuture.completedFuture(snapshot == null
                    ? new ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshot(
                            ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshotStatus.UNAVAILABLE,
                            "not-configured", locator.inventorySlot(), -1L, null)
                    : snapshot);
        }

        @Override
        public CompletableFuture<ProductionBondedVesselEvidenceAuthority.ExactItemRead> readExact(
                UUID actorUuid,
                BondedVesselSourceItemEvidence expected) {
            lastActor = actorUuid;
            return CompletableFuture.completedFuture(read == null
                    ? ProductionBondedVesselEvidenceAuthority.ExactItemRead.unavailable(expected)
                    : read);
        }

        @Override
        public CompletableFuture<ProductionBondedVesselEvidenceAuthority.ExactCasResult> compareAndSet(
                ProductionBondedVesselEvidenceAuthority.ExactCasRequest request) {
            lastCas = request;
            return CompletableFuture.completedFuture(cas);
        }

        @Override
        public ProductionBondedVesselEvidenceAuthority.PortReadiness readiness() {
            return readiness;
        }
    }
}
