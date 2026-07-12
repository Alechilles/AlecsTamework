package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static safety gates for the two-world historical-alias proof pipeline. */
class ManagedCoopCrossWorldAliasRetirementArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");

    @Test
    void coordinatorCarriesOnlyImmutableEvidenceAcrossWorldHops() throws Exception {
        Path path = ITEMS.resolve("ManagedCoopCrossWorldAliasRetirementCoordinator.java");
        String source = Files.readString(path);

        assertTrue(Files.readAllLines(path).size() <= 500);
        assertTrue(source.contains("ProbeStatus.ONE_LOCATION"));
        assertTrue(source.contains("pending.putIfAbsent(key, admitted)"));
        assertTrue(source.contains("ManagedCoopStaleEntityPolicy.exactRetainedProjectionProof("));
        assertTrue(source.contains("decision.reason() == Reason.HISTORICAL_RESIDENT_ALIAS"));
        assertTrue(source.contains("!current(proof) || !locationsCurrent(admitted)"));
        assertFalse(source.contains("Store<EntityStore>"));
        assertFalse(source.contains("Ref<EntityStore>"));
        assertFalse(source.contains("NPCEntity"));
        assertFalse(source.contains("Universe"));
        assertFalse(source.contains("getPlayers("));
        assertFalse(source.contains("CompletableFuture"));
    }

    @Test
    void hytaleGatewayReResolvesExactStoreUuidMarkerOnWorldThreads() throws Exception {
        Path path = ITEMS.resolve("HytaleManagedCoopCrossWorldAliasRuntimeGateway.java");
        String source = Files.readString(path);

        assertTrue(Files.readAllLines(path).size() <= 500);
        assertTrue(source.contains("world.execute(action)"));
        assertTrue(source.contains("store.assertThread()"));
        assertTrue(source.contains("location.equals(LoadedNpcLocationResolver.resolve(store))"));
        assertTrue(source.contains("npcUuid.equals(identity.getUuid())"));
        assertTrue(source.contains("Objects.equals(observation.marker(), markerEvidence(marker))"));
        assertTrue(source.contains("resolved.npc().setToDespawn()"));
        assertFalse(source.contains("getPlayers("));
        assertFalse(source.contains("Universe.get().getPlayers"));
    }

    @Test
    void compositionAndSystemWireEveryInvalidationBoundary() throws Exception {
        String composition = Files.readString(ITEMS.resolve(
                "ManagedCoopRuntimeComposition.java"));
        String system = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/"
                        + "ManagedCoopStaleEntitySuppressionSystem.java"));
        Path policy = ITEMS.resolve("ManagedCoopStaleEntityPolicy.java");

        assertTrue(Files.readAllLines(policy).size() <= 500);
        assertTrue(composition.contains("crossWorldAliasRetirement.invalidateWorld(worldName)"));
        assertTrue(composition.contains("crossWorldAliasRetirement.invalidateAll()"));
        assertTrue(composition.contains("crossWorldAliasRetirement.close()"));
        assertTrue(system.contains("crossWorldRetirement.request(new RetirementRequest("));
        assertTrue(system.contains("crossWorldRetirement.invalidateNpc(identity.getUuid())"));
    }
}
