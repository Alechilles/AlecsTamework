package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.google.gson.Gson;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadedNpcBondedVesselProjectionEvidencePortTest {
    private static final UUID BINDING = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID NPC = UUID.randomUUID();

    @Test
    void validatesCommittedItemFingerprint() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        index.markInitializationComplete();
        BondedVesselSourceItemEvidence evidence = new BondedVesselSourceItemEvidence(
                "stored-stone", "player:" + OWNER, "hotbar", 1, 4, "item-fingerprint");
        BondedVesselBindingRecord binding = binding(null, new Gson().toJson(evidence));
        var port = new LoadedNpcBondedVesselProjectionEvidencePort(index);

        var observed = port.observe(binding, new BondedVesselProjectionValidationRequest(
                BINDING, 4,
                BondedVesselProjectionValidationRequest.ProjectionKind.ITEM,
                "item-fingerprint"));

        assertEquals(ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.EXACT,
                observed.status());
        assertEquals("item-fingerprint", observed.fingerprint());
    }

    @Test
    void validatesOneExactLoadedMarkerAndRejectsDuplicateLocation() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();
        var key = new LoadedNpcIdentityIndex.ProjectionKey(
                "profile-1", UUID.randomUUID().toString(),
                TameworkProjectionIdentityComponent.KIND_BONDED_VESSEL,
                BINDING.toString(), null, 4);
        index.recordAdded(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                NPC, NPC, new LoadedNpcIdentityIndex.Location("world", "store-a"), key));
        index.markInitializationComplete();
        BondedVesselBindingRecord binding = binding(NPC, "{}");
        var port = new LoadedNpcBondedVesselProjectionEvidencePort(index);

        var exact = port.observe(binding, new BondedVesselProjectionValidationRequest(
                BINDING, 4,
                BondedVesselProjectionValidationRequest.ProjectionKind.LIVE_ENTITY,
                BondedVesselProjectionFingerprint.live(BINDING, "profile-1", 4, NPC)));
        assertEquals(ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.EXACT,
                exact.status());

        index.recordAdded(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                NPC, NPC, new LoadedNpcIdentityIndex.Location("other", "store-b"), key));
        var duplicate = port.observe(binding, new BondedVesselProjectionValidationRequest(
                BINDING, 4,
                BondedVesselProjectionValidationRequest.ProjectionKind.LIVE_ENTITY,
                exact.fingerprint()));
        assertEquals(ProductionBondedVesselEvidenceAuthority.ProjectionEvidenceStatus.DUPLICATE,
                duplicate.status());
    }

    private static BondedVesselBindingRecord binding(UUID activeNpc, String itemEvidence) {
        return new BondedVesselBindingRecord(
                BINDING.toString(), "profile-1", 4, "dragon-stone", 2,
                activeNpc == null ? BondedVesselBindingRecord.LifecycleState.STORED
                        : BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                OWNER, 7, activeNpc,
                activeNpc == null ? null
                        : new BondedVesselBindingRecord.PhysicalLocation("world", 0, 0),
                0, "stored-stone", itemEvidence, null, null,
                3, 1, 2, 0);
    }
}
