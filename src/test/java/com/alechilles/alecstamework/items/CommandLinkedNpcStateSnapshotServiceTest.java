package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for operation-owned projection profile persistence. */
class CommandLinkedNpcStateSnapshotServiceTest {
    @Test
    void exposesInjectedLoadedIdentityIndex() {
        LoadedNpcIdentityIndex index = new LoadedNpcIdentityIndex();

        CommandLinkedNpcStateSnapshotService service =
                new CommandLinkedNpcStateSnapshotService(null, index);

        assertSame(index, service.getLoadedNpcIdentityIndex());
    }

    @Test
    void lifecycleIndexUpdatesSurroundSnapshotFiltering() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLinkedNpcStateSnapshotService.java"
        ));
        String addedBody = methodBody(source, "public void onNpcAdded");
        String removedBody = methodBody(source, "public void onNpcRemoved");
        String indexBody = methodBody(source, "private void indexNpcAdded");

        int addedIndex = addedBody.indexOf("indexNpcAdded(reference, store)");
        int refreshIndex = addedBody.indexOf("refreshFromEntity(reference, store)");
        int removedIndex = removedBody.indexOf("loadedNpcIdentityIndex.recordRemoved");
        int snapshotReasonIndex = removedBody.indexOf("if (reason == RemoveReason.REMOVE)");
        int npcGuardIndex = indexBody.indexOf("if (npc == null)");
        int addEvidenceIndex = indexBody.indexOf("loadedNpcIdentityIndex.recordAdded");
        assertTrue(addedIndex >= 0 && refreshIndex > addedIndex);
        assertTrue(removedIndex >= 0 && snapshotReasonIndex > removedIndex);
        assertTrue(npcGuardIndex >= 0 && addEvidenceIndex > npcGuardIndex);
        assertFalse(source.contains("markInitializationComplete"));
    }

    @Test
    void recoveryAndManagedReleaseMarkersDeferGenericProfileUpsert() {
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_RECOVERY
        )));
        assertTrue(CommandLinkedNpcStateSnapshotService.shouldDeferProfileUpsert(marker(
                TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE
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
