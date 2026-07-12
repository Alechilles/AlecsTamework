package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.InspectionResult;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.PlannedDisposition;
import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.SourcePlan;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.entity.reference.PersistentRef;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Full live-snapshot admission for deployed vanilla import sources. */
class VanillaCoopDeployedProjectionAuditTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world-a", 1, 2, 3);
    private static final UUID SOURCE = new UUID(0L, 301L);
    private final VanillaCoopImportAdapter adapter = new VanillaCoopImportAdapter();
    private final VanillaCoopImportEvidenceCodec codec = new VanillaCoopImportEvidenceCodec();
    private final VanillaCoopImportAuditPreparer preparer =
            new VanillaCoopImportAuditPreparer();

    @Test
    void verifiedUniqueProjectionUsesItsFullSnapshotAndRemainsImportable() {
        String snapshot = codec.managedSnapshot(
                SOURCE, "coop_chicken", 0, "mob_chicken", -1_500L);
        InspectionResult live = InspectionResult.verified(
                snapshot,
                ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot),
                Integer.parseInt(CoopResidentStateSnapshotCodec.CURRENT_VERSION));

        SourceEvidence source = prepare(request -> {
            assertEquals(SOURCE, request.sourceNpcUuid());
            assertEquals(0, request.managedResidentSlot());
            return live;
        });
        SourcePlan plan = codec.decodeSourcePlan(source);

        assertEquals(PlannedDisposition.IMPORTED, plan.disposition());
        assertEquals(SOURCE, plan.residentUuid());
        assertEquals(snapshot, source.managedSnapshotJson());
        assertEquals(live.snapshotHash(), source.managedSnapshotHash());
    }

    @Test
    void ambiguousLiveEvidenceIsDurablyQuarantinedWithoutInventingAProjection() {
        SourceEvidence source = prepare(ignored -> InspectionResult.conflict(
                "deployed_projection_uuid_ambiguous", "two_live_rows"));

        SourcePlan plan = codec.decodeSourcePlan(source);

        assertEquals(PlannedDisposition.QUARANTINED, plan.disposition());
        assertEquals("deployed_projection_uuid_ambiguous", plan.conflictKind());
    }

    @Test
    void transientlyUnavailableEvidenceBlocksAuditInsteadOfAuthorizingNeutralization() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                prepare(ignored -> InspectionResult.unavailable("identity_index_incomplete")));

        assertEquals(
                "deployed_projection_evidence_unavailable:identity_index_incomplete",
                failure.getMessage());
    }

    private SourceEvidence prepare(
            VanillaCoopImportAuditPreparer.DeployedProjectionEvidenceResolver resolver) {
        CoopBlock coop = deployedCoop();
        return preparer.prepare(new VanillaCoopImportAuditPreparer.Request(
                AUTHORITY,
                "coop_chicken",
                4,
                adapter.auditForImport(coop),
                List.of(),
                List.of(),
                ignored -> "profile-a",
                resolver,
                -1_000L)).beginRequest().sources().getFirst();
    }

    private CoopBlock deployedCoop() {
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        metadata.setNpcNameKey("Mob_Chicken");
        CoopBlock.CoopResident resident = new CoopBlock.CoopResident(
                metadata, new PersistentRef(SOURCE), Instant.ofEpochMilli(-500L));
        resident.setDeployedToWorld(true);
        return new CoopBlock(
                "coop_chicken", List.of(resident), new SimpleItemContainer((short) 5));
    }
}
