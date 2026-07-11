package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guardrails for the v5-only managed-coop ancillary boundary. */
class ManagedCoopAncillaryArchitectureTest {
    private static final Path MAIN = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");
    private static final List<String> FILES = List.of(
            "ManagedCoopAncillaryRequest.java",
            "ManagedCoopAncillaryBehavior.java",
            "HytaleManagedCoopAncillaryGateway.java");

    @Test
    void ancillaryClassesStayFocusedAndNeverConsultLegacyOrVanillaOccupancy() throws Exception {
        for (String file : FILES) {
            Path path = MAIN.resolve(file);
            String source = Files.readString(path);
            assertTrue(Files.readAllLines(path).size() <= 500, file + " exceeds 500 lines");
            assertFalse(source.contains("CommandLinkedNpcCoopService"), file);
            assertFalse(source.contains("CoopLedger"), file);
            assertFalse(source.contains("tryPutResident"), file);
            assertFalse(source.contains("tryPutWildResidentFromWild"), file);
            assertFalse(source.contains("generateProduceToInventory"), file);
            assertFalse(source.contains("ensureSpawnResidentsInWorld"), file);
            assertFalse(source.contains("java.lang.reflect"), file);
            assertFalse(source.contains("Class.forName("), file);
        }
    }

    @Test
    void asyncBoundaryCarriesOnlyCopiedRequestAndReResolvesTypedContainer() throws Exception {
        String request = Files.readString(MAIN.resolve("ManagedCoopAncillaryRequest.java"));
        String behavior = Files.readString(MAIN.resolve("ManagedCoopAncillaryBehavior.java"));
        String gateway = Files.readString(MAIN.resolve("HytaleManagedCoopAncillaryGateway.java"));
        String orchestrator = Files.readString(
                MAIN.resolve("ManagedCoopRuntimeSweepOrchestrator.java"));

        assertFalse(request.contains(" World "));
        assertFalse(request.contains("Store<"));
        assertFalse(request.contains("ItemContainer"));
        assertFalse(request.contains("private final ManagedCoopContext"));
        assertTrue(behavior.contains("settled.handle((ignored, failure) -> request)"));
        assertTrue(behavior.contains("resident.state() == ResidentState.HOUSED"));
        assertTrue(behavior.contains("CompositeEpoch"));
        assertTrue(behavior.contains("operationRevision"));
        assertTrue(behavior.contains("synchronized (epochs.lock())"));
        assertTrue(gateway.contains("getBlockComponentEntity("));
        assertTrue(gateway.contains("ItemContainerBlock.getComponentType()"));
        assertTrue(gateway.contains("CoopBlock.getComponentType()"));
        assertTrue(gateway.contains("BlockStateInfo.getComponentType()"));
        assertTrue(gateway.contains("FarmingCoopAsset"));
        assertTrue(gateway.contains("matchesCoopAsset(coopAsset.getId(), request.coopId())"));
        assertTrue(gateway.contains("authorityResolver.resolve("));
        assertTrue(gateway.contains("current.matchesExact("));
        assertTrue(gateway.contains("canAddItemStacks(generated)"));
        assertTrue(gateway.contains("addItemStacks(generated, true, false, true)"));
        assertTrue(orchestrator.contains("ancillary.produceAfter(ancillaryRequest, precedingRelease)"));
        assertFalse(orchestrator.contains("ancillary.produce(world"));
    }
}
