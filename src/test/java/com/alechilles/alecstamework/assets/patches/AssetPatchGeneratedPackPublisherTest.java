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
final class AssetPatchGeneratedPackPublisherTest {

    @Test
    void startupRegistersGeneratedPackWhenMissing() {
        assertEquals(
                AssetPatchGeneratedPackPublisher.PublicationAction.REGISTER_PACK,
                AssetPatchGeneratedPackPublisher.publicationAction(
                        false,
                        true,
                        AssetPatchGeneratedPackPublisher.RegistrationMode.ALLOW_REGISTRATION
                )
        );
    }

    @Test
    void runtimeReloadRefreshesExistingGeneratedPackWithoutRegistrationMutation() {
        assertEquals(
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                AssetPatchGeneratedPackPublisher.publicationAction(
                        true,
                        true,
                        AssetPatchGeneratedPackPublisher.RegistrationMode.REFRESH_EXISTING_ONLY
                )
        );
    }

    @Test
    void runtimeReloadDoesNotRegisterMissingGeneratedPackFromWorldThread() {
        assertEquals(
                AssetPatchGeneratedPackPublisher.PublicationAction.MISSING_RUNTIME_PACK,
                AssetPatchGeneratedPackPublisher.publicationAction(
                        false,
                        true,
                        AssetPatchGeneratedPackPublisher.RegistrationMode.REFRESH_EXISTING_ONLY
                )
        );
    }

    @Test
    void startupRegistrationRecreatesCacheBeforeRegisteringPack() {
        assertTrue(AssetPatchGeneratedPackPublisher.shouldRecreateCache(
                AssetPatchGeneratedPackPublisher.PublicationAction.REGISTER_PACK
        ));
    }

    @Test
    void runtimeRefreshPreservesWatchedGeneratedPatchDirectories() {
        assertFalse(AssetPatchGeneratedPackPublisher.shouldRecreateCache(
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK
        ));
    }

    @Test
    void emptyGenerationDoesNotMutatePackRegistration() {
        assertEquals(
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                AssetPatchGeneratedPackPublisher.publicationAction(
                        true,
                        false,
                        AssetPatchGeneratedPackPublisher.RegistrationMode.REFRESH_EXISTING_ONLY
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

        AssetPatchGeneratedPackPublisher.pruneStaleGeneratedFiles(
                tempDir,
                Set.of("Server/NPC/Roles/_Core/Templates/Current.json")
        );

        assertFalse(Files.exists(staleTarget));
        assertTrue(Files.exists(currentTarget));
        assertTrue(Files.exists(nonJsonArtifact));
    }

    @Test
    void runtimeRefreshPrunesGeneratedJsonLikeTargetsThatAreNoLongerProduced(@TempDir Path tempDir) throws Exception {
        Path staleParticle = tempDir.resolve("Server/Particles/Old.particlesystem");
        Path staleSpawner = tempDir.resolve("Server/Particles/Spawners/Old.particlespawner");
        Path currentParticle = tempDir.resolve("Server/Particles/Current.particlesystem");
        Path unrelatedText = tempDir.resolve("Server/Particles/readme.txt");
        Files.createDirectories(staleSpawner.getParent());
        Files.writeString(staleParticle, "{}");
        Files.writeString(staleSpawner, "{}");
        Files.writeString(currentParticle, "{}");
        Files.writeString(unrelatedText, "kept");

        AssetPatchGeneratedPackPublisher.pruneStaleGeneratedFiles(
                tempDir,
                Set.of("Server/Particles/Current.particlesystem")
        );

        assertFalse(Files.exists(staleParticle));
        assertFalse(Files.exists(staleSpawner));
        assertTrue(Files.exists(currentParticle));
        assertTrue(Files.exists(unrelatedText));
    }

    @Test
    void runtimeRefreshUnloadsExistingGeneratedBuildersBeforeCacheFilesArePruned(@TempDir Path tempDir) {
        RecordingBuilderCacheReloader reloader = new RecordingBuilderCacheReloader();
        AssetPatchGeneratedPackPublisher publisher = new AssetPatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        publisher.unloadExistingGeneratedBuildersBeforeCacheMutation(
                generatedPack,
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                new AssetPatchStatus()
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
        AssetPatchGeneratedPackPublisher publisher = new AssetPatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        Map<String, JsonObject> generatedTemplates = new LinkedHashMap<>();
        generatedTemplates.put("Server/NPC/Roles/_Core/Templates/Current.json", new JsonObject());
        AssetPatchStatus status = new AssetPatchStatus();

        assertTrue(publisher.mutateCacheForPublication(
                generatedPack,
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
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
        AssetPatchGeneratedPackPublisher publisher = new AssetPatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        assertTrue(publisher.mutateCacheForPublication(
                generatedPack,
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                tempDir,
                Map.<String, JsonObject>of(),
                new AssetPatchStatus()
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
        AssetPatchGeneratedPackPublisher publisher = new AssetPatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);
        AssetPatchStatus status = new AssetPatchStatus();

        assertFalse(publisher.mutateCacheForPublication(
                generatedPack,
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
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
        assertFalse(AssetPatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                false,
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                true
        ));
        assertTrue(AssetPatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                true
        ));
        assertTrue(AssetPatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                true
        ));
        assertFalse(AssetPatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                false
        ));
        assertFalse(AssetPatchGeneratedPackPublisher.shouldReloadNpcBuildersAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.MISSING_RUNTIME_PACK,
                false
        ));
    }

    @Test
    void emptyRuntimeRefreshAlsoUnloadsExistingGeneratedBuilders(@TempDir Path tempDir) {
        RecordingBuilderCacheReloader reloader = new RecordingBuilderCacheReloader();
        AssetPatchGeneratedPackPublisher publisher = new AssetPatchGeneratedPackPublisher(
                null,
                "Generated",
                reloader
        );
        AssetPack generatedPack = new AssetPack(tempDir, "Generated", tempDir, null, false, null);

        publisher.unloadExistingGeneratedBuildersBeforeCacheMutation(
                generatedPack,
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_TEMPLATES,
                new AssetPatchStatus()
        );

        assertEquals(List.of("unload:Generated"), reloader.events);
    }

    private static final class RecordingBuilderCacheReloader
            implements AssetPatchGeneratedPackPublisher.BuilderCacheReloader {
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
