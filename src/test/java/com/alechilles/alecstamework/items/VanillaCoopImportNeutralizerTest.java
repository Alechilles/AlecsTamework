package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-source neutralization regressions for shifted and changed vanilla residents. */
class VanillaCoopImportNeutralizerTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void removesExactSourceAfterAnEarlierResidentAlreadyDisappeared() {
        CoopBlock.CoopResident earlier = resident("Mob_Chicken", new UUID(0L, 1L), -100L);
        CoopBlock.CoopResident target = resident("Mob_Pigeon", new UUID(0L, 2L), -200L);
        VanillaCoopImportAuditPreparer.PreparedAudit prepared = prepare(List.of(earlier, target));
        SourceEvidence targetEvidence = prepared.beginRequest().sources().stream()
                .filter(source -> "mob_pigeon".equals(source.roleId())).findFirst().orElseThrow();
        CoopBlock shifted = coop(List.of(target));

        VanillaCoopImportNeutralizer.Result result =
                new VanillaCoopImportNeutralizer().neutralize(shifted, targetEvidence);

        assertEquals(VanillaCoopImportNeutralizer.Status.REMOVED, result.status());
        assertEquals(0, result.residentsAfter());
        assertTrue(new VanillaCoopImportAdapter().auditForImport(shifted).residents().isEmpty());
    }

    @Test
    void samePersistentIdentityWithChangedMetadataIsNeverReportedAbsent() {
        UUID uuid = new UUID(0L, 3L);
        SourceEvidence source = prepare(List.of(resident("Mob_Chicken", uuid, -300L)))
                .beginRequest().sources().getFirst();
        CoopBlock changed = coop(List.of(resident("Mob_Pigeon", uuid, -300L)));

        VanillaCoopImportNeutralizer.Result result =
                new VanillaCoopImportNeutralizer().neutralize(changed, source);

        assertEquals(VanillaCoopImportNeutralizer.Status.CHANGED_OR_UNKNOWN, result.status());
        assertEquals(1, new VanillaCoopImportAdapter().auditForImport(changed).residents().size());
    }

    private VanillaCoopImportAuditPreparer.PreparedAudit prepare(
            List<CoopBlock.CoopResident> residents) {
        return new VanillaCoopImportAuditPreparer().prepare(
                new VanillaCoopImportAuditPreparer.Request(
                        AUTHORITY, "coop_chicken", 4,
                        new VanillaCoopImportAdapter().auditForImport(coop(residents)),
                        List.of(), List.of(), uuid -> null, -1_000L));
    }

    private CoopBlock coop(List<CoopBlock.CoopResident> residents) {
        return new CoopBlock(
                "coop_chicken", residents, new SimpleItemContainer((short) 5));
    }

    private CoopBlock.CoopResident resident(String roleId, UUID uuid, long time) {
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        metadata.setNpcNameKey(roleId);
        metadata.setIconPath("Icons/" + roleId + ".png");
        metadata.setFullItemIcon("Icons/" + roleId + "_Full.png");
        return new CoopBlock.CoopResident(
                metadata, uuid == null ? null : new PersistentRef(uuid), Instant.ofEpochMilli(time));
    }
}
