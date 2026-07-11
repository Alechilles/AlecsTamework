package com.alechilles.alecstamework.npc.systems;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static ECS and threading guards for managed-coop stale-entity suppression. */
class ManagedCoopStaleEntitySuppressionSystemArchitectureTest {
    @Test
    void addCallbackReadsOnlyThroughCommandBufferAndPerformsNoDirectEcsWrites() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/"
                        + "ManagedCoopStaleEntitySuppressionSystem.java"
        ));

        assertTrue(source.contains("commandBuffer.getComponent(reference, npcType)"));
        assertTrue(source.contains("commandBuffer.getComponent(reference, uuidType)"));
        assertTrue(source.contains("commandBuffer.getComponent(reference, projectionType)"));
        assertTrue(source.contains("decision.action() != Action.SUPPRESS"));
        assertTrue(source.contains("npc.setToDespawn()"));
        assertTrue(source.contains("decisionSink.onSuppressed(new SuppressionEvent("));
        assertFalse(source.contains("store.getComponent("));
        assertFalse(source.contains("store.putComponent("));
        assertFalse(source.contains("store.removeComponent("));
        assertFalse(source.contains("store.tryRemoveComponent("));
        assertFalse(source.contains("commandBuffer.putComponent("));
        assertFalse(source.contains("commandBuffer.removeComponent("));
    }

    @Test
    void systemCarriesNoLiveEcsStateIntoDeferredWork() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/"
                        + "ManagedCoopStaleEntitySuppressionSystem.java"
        ));

        assertFalse(source.contains("CompletableFuture"));
        assertFalse(source.contains("Executor"));
        assertFalse(source.contains("new Thread("));
        assertFalse(source.contains("world.execute("));
        assertFalse(source.contains("PlayerRef"));
        assertFalse(source.contains("Universe"));
        assertFalse(source.contains("java.sql"));
    }

    @Test
    void policyUsesOnlyImmutableIndexesAndDoesNotInventFinalizedOperationIdentity() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "ManagedCoopStaleEntityPolicy.java"
        ));

        assertTrue(source.contains("ManagedCoopResidentIndex.Snapshot"));
        assertTrue(source.contains("ManagedCoopLifecycleOperationIndex.Snapshot"));
        assertTrue(source.contains("BooleanSupplier compositeTrust"));
        assertTrue(source.contains("canonicalReleaseOperationId(marker.operationId())"));
        assertFalse(source.contains("TameworkProjectionIdentityComponent"));
        assertFalse(source.contains("ComponentType"));
        assertFalse(source.contains("Store<EntityStore>"));
        assertFalse(source.contains("java.sql"));
        assertFalse(source.contains("snapshotSha256"));
        assertFalse(source.contains("deterministicOperationId"));
    }
}
