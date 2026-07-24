package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for operation-owned projection profile persistence. */
class CommandLinkedNpcStateSnapshotServiceTest {
    @Test
    void exposesInjectedLoadedIdentityIndex() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();

        CommandLinkedNpcStateSnapshotService service =
                new CommandLinkedNpcStateSnapshotService(
                        CompanionProfileSnapshotSink.ignore(), index
                );

        assertSame(index, service.getLoadedNpcIdentityIndex());
    }

    @Test
    void lifecycleIndexUpdatesSurroundRemovalSnapshotBoundary() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLinkedNpcStateSnapshotService.java"
        ));
        String addedBody = methodBody(source, "public void onNpcAdded");
        String removedBody = methodBody(source, "public void onNpcRemoved");
        String beginRemovalBody = methodBody(source, "public UUID beginNpcRemoval");
        String completeRemovalBody = methodBody(source, "public void completeNpcRemoval");
        String indexBody = methodBody(source, "private void indexNpcAdded");

        int addedIndex = addedBody.indexOf("indexNpcAdded(reference, store)");
        int refreshIndex = addedBody.indexOf("refreshFromEntity(reference, store)");
        int beginRemovalIndex = removedBody.indexOf("beginNpcRemoval(reference, reason, store)");
        int completeRemovalIndex = removedBody.indexOf("completeNpcRemoval(reference, reason, store, npcUuid)");
        int finalRefreshIndex = beginRemovalBody.indexOf("refreshFromEntity(reference, store)");
        int removedIndex = beginRemovalBody.indexOf("loadedNpcIdentityIndex.recordRemoved");
        int snapshotReasonIndex = completeRemovalBody.indexOf("if (reason == RemoveReason.REMOVE)");
        int snapshotClearIndex = completeRemovalBody.indexOf("snapshotsByNpc.remove(npcUuid)");
        int npcGuardIndex = indexBody.indexOf("if (npc == null)");
        int addEvidenceIndex = indexBody.indexOf("loadedNpcIdentityIndex.recordAdded");
        assertTrue(addedIndex >= 0 && refreshIndex > addedIndex);
        assertTrue(beginRemovalIndex >= 0 && completeRemovalIndex > beginRemovalIndex);
        assertTrue(finalRefreshIndex >= 0 && removedIndex > finalRefreshIndex);
        assertTrue(snapshotReasonIndex >= 0 && snapshotClearIndex > snapshotReasonIndex);
        assertTrue(npcGuardIndex >= 0 && addEvidenceIndex > npcGuardIndex);
        assertTrue(source.contains("LoadedNpcIdentityIndex.LoadedNpcObservation"));
        assertTrue(source.contains("projectionKey(marker)"));
        assertFalse(source.contains("markInitializationComplete"));
    }

    @Test
    void projectionMarkerIsCopiedIntoAnImmutableIndexKey() {
        UUID sourceUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        TameworkProjectionIdentityComponent marker = new TameworkProjectionIdentityComponent(
                " profile-a ",
                " operation-a ",
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                " slot-a ",
                sourceUuid,
                2L
        );

        LoadedNpcIdentityIndex.ProjectionKey key =
                CommandLinkedNpcStateSnapshotService.projectionKey(marker);

        assertEquals("profile-a", key.profileId());
        assertEquals("operation-a", key.operationId());
        assertEquals(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE,
                key.projectionKind());
        assertEquals("slot-a", key.slotKey());
        assertEquals(sourceUuid, key.sourceNpcUuid());
        assertEquals(2L, key.generation());
    }

    @Test
    void incompleteProjectionMarkerIsExcludedFromExactIdentityIndexing() {
        assertNull(CommandLinkedNpcStateSnapshotService.projectionKey(null));
        assertNull(CommandLinkedNpcStateSnapshotService.projectionKey(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "operation-a", " ", null, null, 0L
                )
        ));
        assertNull(CommandLinkedNpcStateSnapshotService.projectionKey(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "operation-a", "RECOVERY", null, null, -1L
                )
        ));
    }

    @Test
    void operationOwnedRecoveryReleaseAndCaptureMarkersDeferGenericProfileUpsert() {
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_RECOVERY
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_CAPTURE_RELEASE
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_CAPTURE_SOURCE
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_BREEDING_CHILD
        )));
    }

    @Test
    void missingIncompleteAndUnknownMarkersDoNotSuppressNormalPersistence() {
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(null));
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "", TameworkProjectionIdentityComponent.KIND_RECOVERY,
                        null, null, 0L
                )
        ));
        assertFalse(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(
                new TameworkProjectionIdentityComponent(
                        "profile-a", "operation-a", "UNKNOWN", null, null, 0L
                )
        ));
    }

    private TameworkProjectionIdentityComponent marker(String kind) {
        return new TameworkProjectionIdentityComponent(
                "profile-a", "operation-a", kind, null, null, 0L
        );
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        int nextMethod = source.indexOf("\n    public ", start + signature.length());
        if (nextMethod < 0) {
            nextMethod = source.length();
        }
        return source.substring(start, nextMethod);
    }
}
