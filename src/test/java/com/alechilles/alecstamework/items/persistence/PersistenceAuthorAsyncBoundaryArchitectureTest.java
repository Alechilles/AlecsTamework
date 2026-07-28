package com.alechilles.alecstamework.items.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the freeze-before-async seam of replacement lifecycle authors. */
class PersistenceAuthorAsyncBoundaryArchitectureTest {
    private static final Path ROOT = Path.of(
            "src/main/java/com/alechilles/alecstamework/items/persistence"
    );

    @Test
    void dormantContinuationCarriesFrozenEvidenceNotLiveIntent() throws Exception {
        String source = Files.readString(
                ROOT.resolve("PositiveEvidenceDormantAuthor.java")
        );

        assertTrue(source.contains(
                "thenCompose(read -> author(frozen, read))"
        ));
        assertFalse(source.contains(
                "thenCompose(read -> author(intent"
        ));
        assertFalse(source.contains(
                "findProfile(intent.observation()"
        ));
    }

    @Test
    void restorationContinuationCarriesFrozenContextNotOriginalIntent()
            throws Exception {
        String source = Files.readString(
                ROOT.resolve("FreeCompanionRestorationAuthor.java")
        );

        assertTrue(source.contains(
                "thenCompose(read -> author(frozen, read))"
        ));
        assertTrue(source.contains(
                "thenApply(value -> dispatch(frozen, value))"
        ));
        assertFalse(source.contains(
                "thenApply(value -> dispatch(intent"
        ));
        assertFalse(source.contains(
                "findProfile(intent.profileId())"
        ));
    }

    @Test
    void spawnerCaptureContinuationCarriesFrozenContextNotOriginalIntent()
            throws Exception {
        String source = Files.readString(
                ROOT.resolve("SpawnerCaptureAuthor.java")
        );
        String freezer = Files.readString(
                ROOT.resolve("SpawnerCaptureEvidenceFreezer.java")
        );
        String facts = Files.readString(
                ROOT.resolve("SpawnerCaptureLiveFacts.java")
        );
        String frozenRecord = freezer.substring(
                freezer.indexOf("record FrozenCapture(")
        );

        assertTrue(source.contains(
                "thenApply(value -> dispatch(context, value))"
        ));
        assertFalse(source.contains(
                "thenApply(value -> dispatch(intent"
        ));
        assertFalse(source.contains(
                "resolveProfile(intent"
        ));
        assertTrue(freezer.indexOf(
                "SpawnerCaptureLiveFacts.freeze(fullState)"
        ) < freezer.indexOf("return new FrozenCapture("));
        assertTrue(frozenRecord.contains(
                "SnapshotCodecRegistry.EncodedSnapshot encoded"
        ));
        assertTrue(frozenRecord.contains(
                "SpawnerCaptureLiveFacts liveFacts"
        ));
        assertFalse(frozenRecord.contains("CoopResidentStateSnapshot"));
        assertFalse(frozenRecord.contains("fullState"));
        assertFalse(frozenRecord.contains("Ref<"));
        assertFalse(frozenRecord.contains("Store<"));
        assertTrue(facts.contains("toolIds = List.copyOf(toolIds)"));
        assertFalse(facts.substring(
                facts.indexOf("record SpawnerCaptureLiveFacts("),
                facts.indexOf(") {")
        ).contains("Component"));
    }

    @Test
    void spawnerReleaseFreezesPlacementBeforeAsyncAndDropsOriginalIntent()
            throws Exception {
        String source = Files.readString(
                ROOT.resolve("SpawnerCapturedArtifactReleaseAuthor.java")
        );
        String context = Files.readString(
                ROOT.resolve("SpawnerCaptureReleaseContext.java")
        );

        assertTrue(source.contains(
                "placement = placementResolver.freeze(intent)"
        ));
        assertTrue(source.contains(
                "return resolveProfile(frozen, placement)"
        ));
        assertTrue(source.contains(
                "thenApply(value -> dispatch(context, value))"
        ));
        assertFalse(source.contains(
                "thenApply(value -> dispatch(intent"
        ));
        assertFalse(source.contains(
                "resolveProfile(\n"
                        + "            SpawnerCapturedArtifactReleaseIntent"
        ));
        assertFalse(context.contains("ItemStack"));
        assertFalse(context.contains("Ref<"));
        assertFalse(context.contains("Store<"));
    }
}
