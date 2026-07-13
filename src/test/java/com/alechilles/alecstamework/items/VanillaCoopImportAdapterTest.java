package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.server.core.entity.reference.PersistentRef;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.VanillaCoopImportAdapter.AuditStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Import-only regression coverage for the exact Hytale 0.5.6 CoopBlock evidence layout. */
class VanillaCoopImportAdapterTest {
    @Test
    void supportedVanillaLayoutReturnsOrderedRawEvidenceWithoutMutation() {
        CapturedNPCMetadata metadata = new CapturedNPCMetadata();
        metadata.setIconPath("Icons/Chicken.png");
        metadata.setNpcNameKey("Mob_Chicken");
        metadata.setFullItemIcon("Icons/Chicken_Full.png");
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PersistentRef persistentRef = new PersistentRef(uuid);
        Instant lastProduced = Instant.ofEpochMilli(-123_456L);
        CoopBlock.CoopResident resident =
                new CoopBlock.CoopResident(metadata, persistentRef, lastProduced);
        resident.setDeployedToWorld(true);
        CoopBlock coop = new CoopBlock(
                "coop_chicken",
                List.of(resident),
                new SimpleItemContainer((short) 5)
        );
        VanillaCoopImportAdapter adapter = new VanillaCoopImportAdapter();

        VanillaCoopImportAdapter.AuditResult result = adapter.auditForImport(coop);

        assertEquals(AuditStatus.SUPPORTED, result.status());
        assertTrue(result.readable());
        assertEquals(VanillaCoopImportAdapter.SUPPORTED_LAYOUT_ID, result.layoutId());
        assertNull(result.detail());
        assertNotNull(result.coop());
        assertEquals("coop_chicken", result.coop().coopAssetId());
        assertEquals(1, result.coop().sourceResidentCount());
        assertNotNull(result.coop().rawProduceStorage());
        assertEquals(1, result.residents().size());
        VanillaCoopImportAdapter.ResidentEvidence evidence = result.residents().getFirst();
        assertEquals(0, evidence.residentSlot());
        assertEquals(0, evidence.sourceOrder());
        assertSame(metadata, evidence.rawMetadata());
        assertTrue(evidence.deployedToWorld());
        assertSame(persistentRef, evidence.rawPersistentRef());
        assertEquals(uuid, evidence.persistentUuid());
        assertSame(lastProduced, evidence.lastProduced());
        assertThrows(UnsupportedOperationException.class, () -> result.residents().clear());

        assertEquals("Icons/Chicken.png", metadata.getIconPath());
        assertEquals("Mob_Chicken", metadata.getNpcNameKey());
        assertEquals(uuid, persistentRef.getUuid());
        assertTrue(resident.getDeployedToWorld());
        assertSame(lastProduced, resident.getLastProduced());
    }

    @Test
    void emptySupportedCoopIsDistinguishedFromUnsupportedOrFailed() {
        VanillaCoopImportAdapter.AuditResult result =
                new VanillaCoopImportAdapter().auditForImport(new CoopBlock());

        assertEquals(AuditStatus.EMPTY, result.status());
        assertTrue(result.readable());
        assertNotNull(result.coop());
        assertEquals(0, result.coop().sourceResidentCount());
        assertNotNull(result.coop().rawProduceStorage());
        assertTrue(result.residents().isEmpty());
    }

    @Test
    void genericLayoutMismatchFailsClosedBeforeReadingSourceValues() {
        VanillaCoopImportAdapter adapter =
                new VanillaCoopImportAdapter(WrongGenericCoop.class, FixtureResident.class);

        VanillaCoopImportAdapter.AuditResult result =
                adapter.auditFixtureForTest(new WrongGenericCoop());

        assertEquals(AuditStatus.UNSUPPORTED, result.status());
        assertFalse(result.readable());
        assertNull(result.coop());
        assertTrue(result.residents().isEmpty());
        assertTrue(result.detail().contains("residents:unexpected_generic_element"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void unexpectedResidentRuntimeElementFailsClosedWithoutPartialEvidence() {
        FixtureCoop coop = new FixtureCoop();
        ((List) coop.residents).add("not-a-resident");
        VanillaCoopImportAdapter adapter =
                new VanillaCoopImportAdapter(FixtureCoop.class, FixtureResident.class);

        VanillaCoopImportAdapter.AuditResult result = adapter.auditFixtureForTest(coop);

        assertEquals(AuditStatus.UNSUPPORTED, result.status());
        assertNotNull(result.coop());
        assertTrue(result.residents().isEmpty());
        assertEquals("unsupported_vanilla_coop_resident_element:0", result.detail());
    }

    @Test
    void compatibleCoopRuntimeSubclassKeepsImportGateReadable() {
        FixtureCoopSubclass coop = new FixtureCoopSubclass();
        coop.residents.add(new FixtureResident());
        VanillaCoopImportAdapter adapter =
                new VanillaCoopImportAdapter(FixtureCoop.class, FixtureResident.class);

        VanillaCoopImportAdapter.AuditResult result = adapter.auditFixtureForTest(coop);

        assertEquals(AuditStatus.SUPPORTED, result.status());
        assertTrue(result.readable());
        assertEquals("fixture_coop", result.coop().coopAssetId());
        assertEquals(1, result.residents().size());
    }

    @Test
    void evidenceReadExceptionIsFailedAndNeverPresentedAsEmpty() {
        FixtureCoop coop = new FixtureCoop();
        coop.residents = new ExplodingList<>();
        VanillaCoopImportAdapter adapter =
                new VanillaCoopImportAdapter(FixtureCoop.class, FixtureResident.class);

        VanillaCoopImportAdapter.AuditResult result = adapter.auditFixtureForTest(coop);

        assertEquals(AuditStatus.FAILED, result.status());
        assertFalse(result.readable());
        assertNull(result.coop());
        assertTrue(result.residents().isEmpty());
        assertTrue(result.detail().startsWith("vanilla_coop_evidence_read_failed:"));
    }

    @Test
    void missingSourceIsFailedRatherThanMisreportedAsEmpty() {
        VanillaCoopImportAdapter.AuditResult result =
                new VanillaCoopImportAdapter().auditForImport(null);

        assertEquals(AuditStatus.FAILED, result.status());
        assertEquals("vanilla_coop_source_required", result.detail());
        assertFalse(result.readable());
    }

    @Test
    void publicSurfaceAndSourceStayStrictlyReadOnlyAndImportOnly() throws IOException {
        Set<String> publicMethods = new HashSet<>();
        for (Method method : VanillaCoopImportAdapter.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                publicMethods.add(method.getName());
            }
        }
        assertEquals(Set.of("auditForImport"), publicMethods);

        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/VanillaCoopImportAdapter.java"
        ));
        List<String> forbiddenMutationCalls = Arrays.asList(
                "tryPutResident(",
                "tryPutWildResidentFromWild(",
                "ensureSpawnResidentsInWorld(",
                "ensureNoResidentsInWorld(",
                "handleResidentDespawn(",
                "generateProduceToInventory(",
                "gatherProduceFromContainer(",
                ".setPersistentRef(",
                ".setDeployedToWorld(",
                "residents.remove("
        );
        for (String forbidden : forbiddenMutationCalls) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("auditForImport"));
        assertTrue(source.contains("SUPPORTED_LAYOUT_ID"));
    }

    private static class FixtureCoop {
        protected String coopAssetId = "fixture_coop";
        protected List<FixtureResident> residents = new java.util.ArrayList<>();
        protected ItemContainer itemContainer;
    }

    private static final class FixtureCoopSubclass extends FixtureCoop {
    }

    private static final class WrongGenericCoop {
        protected String coopAssetId = "wrong_generic";
        protected List<String> residents = List.of("wrong");
        protected ItemContainer itemContainer;
    }

    private static final class FixtureResident {
        protected CapturedNPCMetadata metadata;
        protected PersistentRef persistentRef;
        protected boolean deployedToWorld;
        protected Instant lastProduced;
    }

    private static final class ExplodingList<E> extends AbstractList<E> {
        @Override
        public E get(int index) {
            throw new IllegalStateException("fixture read exploded");
        }

        @Override
        public int size() {
            return 1;
        }
    }
}
