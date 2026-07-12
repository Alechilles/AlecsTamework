package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.VanillaCoopImportEvidenceCodec.PlannedDisposition;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.SourceEvidence;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.entity.reference.PersistentRef;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for immutable, order-independent vanilla import evidence. */
class VanillaCoopImportAuditPreparerTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private final VanillaCoopImportAdapter adapter = new VanillaCoopImportAdapter();
    private final VanillaCoopImportAuditPreparer preparer = new VanillaCoopImportAuditPreparer();
    private final VanillaCoopImportEvidenceCodec codec = new VanillaCoopImportEvidenceCodec();

    @Test
    void stableSourceFingerprintsDoNotDependOnShrinkingResidentOrder() {
        CoopBlock.CoopResident chicken = resident("Mob_Chicken", new UUID(0L, 1L), -100L);
        CoopBlock.CoopResident pigeon = resident("Mob_Pigeon", new UUID(0L, 2L), -200L);

        Map<String, String> first = fingerprints(prepare(List.of(chicken, pigeon)));
        Map<String, String> shifted = fingerprints(prepare(List.of(pigeon)));

        assertEquals(first.get("mob_pigeon"), shifted.get("mob_pigeon"));
        assertFalse(first.get("mob_chicken").equals(first.get("mob_pigeon")));
    }

    @Test
    void indistinguishableDuplicateResidentsBecomeOneQuarantinedGroup() {
        CoopBlock.CoopResident first = resident("Mob_Chicken", null, -300L);
        CoopBlock.CoopResident second = resident("Mob_Chicken", null, -300L);

        SourceEvidence source = prepare(List.of(first, second)).beginRequest().sources().getFirst();
        VanillaCoopImportEvidenceCodec.SourcePlan plan = codec.decodeSourcePlan(source);

        assertEquals(1, prepare(List.of(first, second)).beginRequest().sources().size());
        assertEquals(PlannedDisposition.QUARANTINED, plan.disposition());
        assertEquals(2, plan.multiplicity());
        assertEquals("ambiguous_indistinguishable_sources", plan.conflictKind());
        assertTrue(source.sourcePayload().contains("indistinguishable_duplicate_group"));
    }

    @Test
    void deployedSourceIsQuarantinedEvenWhenItMatchesADeployedManagedResident() {
        UUID uuid = new UUID(0L, 9L);
        CoopBlock.CoopResident vanilla = resident("Mob_Chicken", uuid, -400L);
        vanilla.setDeployedToWorld(true);
        String snapshot = codec.managedSnapshot(uuid, "coop_chicken", 0,
                "mob_chicken", -1_000L);
        ResidentRecord managed = new ResidentRecord(
                "resident-a", AUTHORITY, "coop_chicken", 0, "profile-a", "mob_chicken",
                uuid, uuid, uuid, snapshot,
                com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator
                        .snapshotSha256(snapshot),
                1, ResidentState.DEPLOYED, 0L, true,
                -1_000L, -900L, -1_000L, -900L);
        CoopBlock coop = new CoopBlock(
                "coop_chicken", List.of(vanilla), new SimpleItemContainer((short) 5));

        SourceEvidence source = preparer.prepare(new VanillaCoopImportAuditPreparer.Request(
                AUTHORITY, "coop_chicken", 4, adapter.auditForImport(coop),
                List.of(managed), List.of(), ignored -> "profile-a", -1_000L))
                .beginRequest().sources().getFirst();
        VanillaCoopImportEvidenceCodec.SourcePlan plan = codec.decodeSourcePlan(source);

        assertEquals(PlannedDisposition.QUARANTINED, plan.disposition());
        assertEquals("deployed_source_requires_live_projection_adoption", plan.conflictKind());
    }

    @Test
    void distinctHousedOverflowReceivesManagedSlotInsteadOfQuarantine() {
        CoopBlock.CoopResident first = resident("Mob_Chicken", new UUID(0L, 20L), -500L);
        CoopBlock.CoopResident overflow = resident("Mob_Pigeon", new UUID(0L, 21L), -600L);
        CoopBlock coop = new CoopBlock(
                "coop_chicken", List.of(first, overflow), new SimpleItemContainer((short) 5));

        List<SourceEvidence> sources = preparer.prepare(
                new VanillaCoopImportAuditPreparer.Request(
                        AUTHORITY, "coop_chicken", 1, adapter.auditForImport(coop),
                        List.of(), List.of(), ignored -> null, -1_000L))
                .beginRequest().sources();
        Map<String, SourceEvidence> byRole = sources.stream()
                .collect(Collectors.toMap(
                        source -> source.roleId().toLowerCase(),
                        Function.identity()));
        VanillaCoopImportEvidenceCodec.SourcePlan admitted =
                codec.decodeSourcePlan(byRole.get("mob_chicken"));
        VanillaCoopImportEvidenceCodec.SourcePlan overflowPlan =
                codec.decodeSourcePlan(byRole.get("mob_pigeon"));

        assertEquals(PlannedDisposition.IMPORTED, admitted.disposition());
        assertEquals(0, admitted.targetSlot());
        assertFalse(admitted.overflow());
        assertFalse(byRole.get("mob_chicken").sourceEnvelopeJson().contains("\"overflow\""),
                "missing legacy-compatible overflow evidence must decode as false");
        assertEquals(PlannedDisposition.IMPORTED, overflowPlan.disposition());
        assertEquals(1, overflowPlan.targetSlot());
        assertTrue(overflowPlan.overflow());
        assertTrue(byRole.get("mob_pigeon").sourceEnvelopeJson()
                .contains("\"overflow\":true"));
    }

    @Test
    void auditFingerprintBindsExactDurablePlanButNotSyntheticSnapshotTime() {
        UUID sourceUuid = new UUID(0L, 31L);
        CoopBlock.CoopResident source = resident("Mob_Chicken", sourceUuid, -700L);
        Function<UUID, String> profile = ignored -> "profile-source";

        VanillaCoopImportAuditPreparer.PreparedAudit imported = prepare(
                List.of(source), List.of(), profile, 4, -1_000L);
        VanillaCoopImportAuditPreparer.PreparedAudit importedLater = prepare(
                List.of(source), List.of(), profile, 4, -2_000L);
        VanillaCoopImportAuditPreparer.PreparedAudit shifted = prepare(
                List.of(source),
                List.of(managedResident("resident-other", 0, "profile-other",
                        new UUID(0L, 32L))),
                profile, 4, -1_000L);
        VanillaCoopImportAuditPreparer.PreparedAudit matched = prepare(
                List.of(source),
                List.of(managedResident("resident-source", 2, "profile-source", sourceUuid)),
                profile, 4, -1_000L);

        String importedFingerprint = imported.beginRequest().envelope().auditFingerprint();
        assertEquals(VanillaCoopImportEvidenceCodec.AUDIT_VERSION,
                imported.beginRequest().envelope().auditVersion());
        assertTrue(imported.beginRequest().envelope().auditEnvelopeJson()
                .contains("\"sourcePlans\""));
        assertEquals(importedFingerprint,
                importedLater.beginRequest().envelope().auditFingerprint(),
                "housed synthetic snapshot timestamps must not churn operator approval");
        assertNotEquals(importedFingerprint,
                shifted.beginRequest().envelope().auditFingerprint(),
                "a changed target slot must invalidate the prior approval fingerprint");
        assertNotEquals(shifted.beginRequest().envelope().auditFingerprint(),
                matched.beginRequest().envelope().auditFingerprint(),
                "a changed MATCHED versus IMPORTED plan must invalidate prior approval");
    }

    private VanillaCoopImportAuditPreparer.PreparedAudit prepare(
            List<CoopBlock.CoopResident> residents) {
        return prepare(residents, List.of(), uuid -> null, 4, -1_000L);
    }

    private VanillaCoopImportAuditPreparer.PreparedAudit prepare(
            List<CoopBlock.CoopResident> residents,
            List<ResidentRecord> managedResidents,
            Function<UUID, String> profileResolver,
            int maximumResidents,
            long auditedAtMs) {
        CoopBlock coop = new CoopBlock(
                "coop_chicken", residents, new SimpleItemContainer((short) 5));
        return preparer.prepare(new VanillaCoopImportAuditPreparer.Request(
                AUTHORITY,
                "coop_chicken",
                maximumResidents,
                adapter.auditForImport(coop),
                managedResidents,
                List.of(),
                profileResolver::apply,
                auditedAtMs
        ));
    }

    private ResidentRecord managedResident(String residentId,
                                            int slot,
                                            String profileId,
                                            UUID uuid) {
        String snapshot = codec.managedSnapshot(
                uuid, "coop_chicken", slot, "mob_chicken", -900L);
        return new ResidentRecord(
                residentId, AUTHORITY, "coop_chicken", slot, profileId, "mob_chicken",
                uuid, uuid, null, snapshot,
                com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator
                        .snapshotSha256(snapshot),
                1, ResidentState.HOUSED, 0L, true,
                -900L, 0L, -900L, -900L);
    }

    private Map<String, String> fingerprints(
            VanillaCoopImportAuditPreparer.PreparedAudit prepared) {
        return prepared.beginRequest().sources().stream().collect(Collectors.toMap(
                source -> source.roleId().toLowerCase(),
                SourceEvidence::sourceFingerprint
        ));
    }

    private CoopBlock.CoopResident resident(String roleId, UUID uuid, long lastProducedMs) {
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        metadata.setNpcNameKey(roleId);
        metadata.setIconPath("Icons/" + roleId + ".png");
        metadata.setFullItemIcon("Icons/" + roleId + "_Full.png");
        return new CoopBlock.CoopResident(
                metadata,
                uuid == null ? null : new PersistentRef(uuid),
                Instant.ofEpochMilli(lastProducedMs)
        );
    }
}
