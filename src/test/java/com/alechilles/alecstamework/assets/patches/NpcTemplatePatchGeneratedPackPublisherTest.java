package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                new NpcTemplatePatchStatus()
        );

        assertEquals(List.of("unload:Generated"), reloader.events);
    }

    @Test
    void runtimeRefreshUnloadsExistingGeneratedBuildersWhileStaleFilesStillExist(@TempDir Path tempDir)
            throws Exception {
        Path staleTarget = tempDir.resolve("Server/NPC/Roles/_Core/Templates/Stale.json");
        Files.createDirectories(staleTarget.getParent());
        Files.writeString(staleTarget, "{}");
        RecordingBuilderCacheReloader reloader = new RecordingBuilderCacheReloader();
        reloader.staleTargetToCheck = staleTarget;
        NpcTemplatePatchGeneratedPackPublisher publisher = new NpcTemplatePatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        Map<String, JsonObject> generatedTemplates = new LinkedHashMap<>();
        generatedTemplates.put("Server/NPC/Roles/_Core/Templates/Current.json", new JsonObject());
        NpcTemplatePatchStatus status = new NpcTemplatePatchStatus();

        assertTrue(publisher.mutateCacheForPublication(
                generatedPack,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                tempDir,
                generatedTemplates,
                status
        ));

        assertEquals(List.of("unload:Generated:staleExists=true"), reloader.events);
        assertFalse(Files.exists(staleTarget));
        assertTrue(Files.exists(tempDir.resolve("Server/NPC/Roles/_Core/Templates/Current.json")));
        assertEquals(List.of("Server/NPC/Roles/_Core/Templates/Current.json"), status.getGeneratedTargets());
    }

    @Test
    void emptyRuntimeRefreshUnloadsExistingGeneratedBuildersBeforePruningAllJson(@TempDir Path tempDir)
            throws Exception {
        Path staleTarget = tempDir.resolve("Server/NPC/Roles/_Core/Templates/Stale.json");
        Files.createDirectories(staleTarget.getParent());
        Files.writeString(staleTarget, "{}");
        RecordingBuilderCacheReloader reloader = new RecordingBuilderCacheReloader();
        reloader.staleTargetToCheck = staleTarget;
        NpcTemplatePatchGeneratedPackPublisher publisher = new NpcTemplatePatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        assertTrue(publisher.mutateCacheForPublication(
                generatedPack,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                tempDir,
                Map.<String, JsonObject>of(),
                new NpcTemplatePatchStatus()
        ));

        assertEquals(List.of("unload:Generated:staleExists=true"), reloader.events);
        assertFalse(Files.exists(staleTarget));
    }

    @Test
    void runtimeRefreshAbortCacheMutationWhenGeneratedBuilderUnloadFails(@TempDir Path tempDir)
            throws Exception {
        Path staleTarget = tempDir.resolve("Server/NPC/Roles/_Core/Templates/Stale.json");
        Files.createDirectories(staleTarget.getParent());
        Files.writeString(staleTarget, "{}");
        RecordingBuilderCacheReloader reloader = new RecordingBuilderCacheReloader();
        reloader.throwOnUnload = true;
        NpcTemplatePatchGeneratedPackPublisher publisher = new NpcTemplatePatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);
        NpcTemplatePatchStatus status = new NpcTemplatePatchStatus();

        assertFalse(publisher.mutateCacheForPublication(
                generatedPack,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                tempDir,
                Map.<String, JsonObject>of(),
                status
        ));

        assertTrue(Files.exists(staleTarget));
        assertTrue(status.hasFailures());
        assertTrue(status.getFailed().getFirst().contains("failed to unload generated NPC builders"));
    }

    @Test
    void runtimeReloadOnlyReloadsNpcBuildersAfterSuccessfulExistingPackPublication() {
        assertFalse(NpcTemplatePatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                false,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                true
        ));
        assertTrue(NpcTemplatePatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                true
        ));
        assertTrue(NpcTemplatePatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                true
        ));
        assertFalse(NpcTemplatePatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                false
        ));
        assertFalse(NpcTemplatePatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.MISSING_RUNTIME_PACK,
                false
        ));
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
                NpcTemplatePatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                new NpcTemplatePatchStatus()
        );

        assertEquals(List.of("unload:Generated"), reloader.events);
    }

    private static final class RecordingBuilderCacheReloader
            implements NpcTemplatePatchGeneratedPackPublisher.BuilderCacheReloader {
        private final List<String> events = new ArrayList<>();
        private Path staleTargetToCheck;
        private boolean throwOnUnload;

        @Override
        public void unload(AssetPack generatedPack) {
            if (throwOnUnload) {
                throw new IllegalStateException("boom");
            }
            if (staleTargetToCheck != null) {
                events.add("unload:" + generatedPack.getName() + ":staleExists=" + Files.exists(staleTargetToCheck));
                return;
            }
            events.add("unload:" + generatedPack.getName());
        }

        @Override
        public void load(AssetPack generatedPack) {
            events.add("load:" + generatedPack.getName());
        }
    }
}
