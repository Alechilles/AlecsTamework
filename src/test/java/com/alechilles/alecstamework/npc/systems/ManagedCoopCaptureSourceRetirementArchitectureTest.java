package com.alechilles.alecstamework.npc.systems;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static ECS, async-boundary, and decomposition guards for capture source retirement. */
class ManagedCoopCaptureSourceRetirementArchitectureTest {
    private static final Path ITEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/items");
    private static final Path SYSTEMS = Path.of(
            "src/main/java/com/alechilles/alecstamework/npc/systems");

    @Test
    void removalSystemCopiesIdentityThroughCommandBufferAndPerformsNoEcsWrites()
            throws Exception {
        String source = Files.readString(SYSTEMS.resolve(
                "ManagedCoopCaptureSourceRetirementSystem.java"));

        assertTrue(source.contains(
                "commandBuffer.getComponent(reference, uuidType)"));
        assertTrue(source.contains(
                "commandBuffer.getComponent(reference, markerType)"));
        assertTrue(source.contains("new RemovalObservation("));
        assertTrue(source.contains("retirementService.confirmRemoved(observation)"));
        assertFalse(source.contains("store.getComponent("));
        assertFalse(source.contains("store.putComponent("));
        assertFalse(source.contains("store.removeComponent("));
        assertFalse(source.contains("store.tryRemoveComponent("));
        assertFalse(source.contains("commandBuffer.putComponent("));
        assertFalse(source.contains("commandBuffer.removeComponent("));
        assertFalse(source.contains("world.execute("));
        assertFalse(source.contains("CompletableFuture"));
        assertFalse(source.contains("Executor"));
    }

    @Test
    void worldGatewayAssertsThreadBeforeResolvingAndMutatingExactSource()
            throws Exception {
        String source = Files.readString(ITEMS.resolve(
                "HytaleManagedCoopCaptureSourceGateway.java"));

        assertTrue(source.contains("world.execute(Objects.requireNonNull(task"));
        assertTrue(source.contains("store.assertThread()"));
        assertTrue(source.contains(
                "world.getEntityRef(command.sourceNpcUuid())"));
        assertTrue(source.contains("store.putComponent(reference, markerType, expected)"));
        assertTrue(source.contains("npc.setToDespawn()"));
        assertTrue(source.contains(
                "KIND_MANAGED_COOP_CAPTURE_SOURCE"));
    }

    @Test
    void asyncCompletionCarriesOnlyImmutableCommandAndUsesPairedRefresh()
            throws Exception {
        String source = Files.readString(ITEMS.resolve(
                "ManagedCoopCaptureSourceRetirementService.java"));

        assertTrue(source.contains("record RetirementCommand("));
        assertTrue(source.contains("required.completeCapture("));
        assertTrue(source.contains("ManagedCoopCompositeIndexRefreshService"));
        assertTrue(source.contains("result.refreshed() && required.isTrusted()"));
        assertFalse(source.contains("CompletableFuture<Ref"));
        assertFalse(source.contains("CompletionStage<Ref"));
        assertFalse(source.contains("CompletableFuture<Store"));
        assertFalse(source.contains("CompletionStage<Store"));
        assertFalse(source.contains("CompletableFuture<NPCEntity"));
        assertFalse(source.contains("CompletionStage<NPCEntity"));
        assertFalse(source.contains("PlayerRef"));
        assertFalse(source.contains("Universe.getPlayers"));
    }

    @Test
    void retirementClassesStayBelowHardDecompositionTarget() throws Exception {
        assertUnder500(ITEMS.resolve(
                "ManagedCoopCaptureSourceRetirementService.java"));
        assertUnder500(ITEMS.resolve(
                "ManagedCoopCaptureRetirementIndexEvidence.java"));
        assertUnder500(ITEMS.resolve(
                "HytaleManagedCoopCaptureSourceGateway.java"));
        assertUnder500(SYSTEMS.resolve(
                "ManagedCoopCaptureSourceRetirementSystem.java"));
    }

    private void assertUnder500(Path path) throws Exception {
        assertTrue(Files.readAllLines(path).size() <= 500,
                path.getFileName() + " must stay at or below 500 lines");
    }
}
