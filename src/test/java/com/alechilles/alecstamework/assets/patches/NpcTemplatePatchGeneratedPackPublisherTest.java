package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.assetstore.AssetPack;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Covers generated patch pack publication decisions without mutating the live AssetModule in tests.
 */
final class NpcTemplatePatchGeneratedPackPublisherTest {

    @Test
    void startupRegistersGeneratedPackWhenMissing() {
        assertEquals(
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REGISTER_PACK,
                NpcTemplatePatchGeneratedPackPublisher.publicationAction(
                        false,
                        true,
                        NpcTemplatePatchGeneratedPackPublisher.RegistrationMode.ALLOW_REGISTRATION
                )
        );
    }

    @Test
    void runtimeReloadRefreshesExistingGeneratedPackWithoutRegistrationMutation() {
        assertEquals(
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                NpcTemplatePatchGeneratedPackPublisher.publicationAction(
                        true,
                        true,
                        NpcTemplatePatchGeneratedPackPublisher.RegistrationMode.REFRESH_EXISTING_ONLY
                )
        );
    }

    @Test
    void runtimeReloadDoesNotRegisterMissingGeneratedPackFromWorldThread() {
        assertEquals(
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.MISSING_RUNTIME_PACK,
                NpcTemplatePatchGeneratedPackPublisher.publicationAction(
                        false,
                        true,
                        NpcTemplatePatchGeneratedPackPublisher.RegistrationMode.REFRESH_EXISTING_ONLY
                )
        );
    }

    @Test
    void startupRegistrationRecreatesCacheBeforeRegisteringPack() {
        assertTrue(NpcTemplatePatchGeneratedPackPublisher.shouldRecreateCache(
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REGISTER_PACK
        ));
    }

    @Test
    void runtimeRefreshPreservesWatchedGeneratedPatchDirectories() {
        assertFalse(NpcTemplatePatchGeneratedPackPublisher.shouldRecreateCache(
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK
        ));
    }

    @Test
    void emptyGenerationDoesNotMutatePackRegistration() {
        assertEquals(
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                NpcTemplatePatchGeneratedPackPublisher.publicationAction(
                        true,
                        false,
                        NpcTemplatePatchGeneratedPackPublisher.RegistrationMode.REFRESH_EXISTING_ONLY
                )
        );
    }

    @Test
    void runtimeRefreshPrunesGeneratedJsonTargetsThatAreNoLongerProduced(@TempDir Path tempDir) throws Exception {
        Path staleTarget = tempDir.resolve("Server/NPC/Roles/_Core/Templates/Stale.json");
        Path currentTarget = tempDir.resolve("Server/NPC/Roles/_Core/Templates/Current.json");
        Path nonJsonArtifact = tempDir.resolve("Server/NPC/Roles/_Core/Templates/readme.txt");
        Files.createDirectories(staleTarget.getParent());
        Files.writeString(staleTarget, "{}");
        Files.writeString(currentTarget, "{}");
        Files.writeString(nonJsonArtifact, "kept");

        NpcTemplatePatchGeneratedPackPublisher.pruneStaleGeneratedFiles(
                tempDir,
                Set.of("Server/NPC/Roles/_Core/Templates/Current.json")
        );

        assertFalse(Files.exists(staleTarget));
        assertTrue(Files.exists(currentTarget));
        assertTrue(Files.exists(nonJsonArtifact));
    }

    @Test
    void runtimeRefreshUnloadsExistingGeneratedBuildersBeforeCacheFilesArePruned(@TempDir Path tempDir) {
        RecordingBuilderCacheReloader reloader = new RecordingBuilderCacheReloader();
        NpcTemplatePatchGeneratedPackPublisher publisher = new NpcTemplatePatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        publisher.unloadExistingGeneratedBuildersBeforeCacheMutation(
                generatedPack,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK
        );

        assertEquals(List.of("unload:Generated"), reloader.events);
    }

    @Test
    void emptyRuntimeRefreshAlsoUnloadsExistingGeneratedBuilders(@TempDir Path tempDir) {
        RecordingBuilderCacheReloader reloader = new RecordingBuilderCacheReloader();
        NpcTemplatePatchGeneratedPackPublisher publisher = new NpcTemplatePatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        publisher.unloadExistingGeneratedBuildersBeforeCacheMutation(
                generatedPack,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES
        );

        assertEquals(List.of("unload:Generated"), reloader.events);
    }

    private static final class RecordingBuilderCacheReloader
            implements NpcTemplatePatchGeneratedPackPublisher.BuilderCacheReloader {
        private final List<String> events = new ArrayList<>();

        @Override
        public void unload(AssetPack generatedPack) {
            events.add("unload:" + generatedPack.getName());
        }

        @Override
        public void load(AssetPack generatedPack) {
            events.add("load:" + generatedPack.getName());
        }
    }
}
