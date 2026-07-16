package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.Mod;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.BsonDocument;
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
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_ASSETS,
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
        Path manifest = tempDir.resolve("manifest.json");
        Path nonJsonArtifact = tempDir.resolve("Server/NPC/Roles/_Core/Templates/readme.txt");
        Files.createDirectories(staleTarget.getParent());
        Files.writeString(staleTarget, "{}");
        Files.writeString(currentTarget, "{}");
        Files.writeString(manifest, "{}");
        Files.writeString(nonJsonArtifact, "kept");

        AssetPatchGeneratedPackPublisher.pruneStaleGeneratedFiles(
                tempDir,
                Set.of("Server/NPC/Roles/_Core/Templates/Current.json")
        );

        assertFalse(Files.exists(staleTarget));
        assertTrue(Files.exists(currentTarget));
        assertTrue(Files.exists(manifest));
        assertTrue(Files.exists(nonJsonArtifact));
    }

    @Test
    void generatedPackWritesCodecValidManifestRequiredByDiskValidation(@TempDir Path tempDir) throws Exception {
        PluginManifest generatedManifest = manifest("Alechilles:Alec's Tamework!_GeneratedPatches");
        generatedManifest.injectDependency(
                PluginIdentifier.fromString("Alechilles:Alec's Tamework!"),
                SemverRange.WILDCARD
        );

        // Regression for the 2026-07-16 prefab-save path that unregistered the manifest-less runtime pack.
        AssetPatchGeneratedPackPublisher.writeGeneratedPatchManifest(tempDir, generatedManifest);

        Path output = tempDir.resolve("manifest.json");
        assertTrue(Files.isRegularFile(output));
        PluginManifest decoded = PluginManifest.CODEC.decode(BsonDocument.parse(Files.readString(output)));
        assertEquals("Alechilles", decoded.getGroup());
        assertEquals("Alec's Tamework!_GeneratedPatches", decoded.getName());
        assertEquals("1.0.0", decoded.getVersion().toString());
        assertTrue(decoded.getDependencies().containsKey(
                PluginIdentifier.fromString("Alechilles:Alec's Tamework!")
        ));
    }

    @Test
    void activeGeneratedPackAlwaysPersistsManifest() {
        assertTrue(AssetPatchGeneratedPackPublisher.shouldPersistManifest(
                AssetPatchGeneratedPackPublisher.PublicationAction.REGISTER_PACK,
                false
        ));
        assertTrue(AssetPatchGeneratedPackPublisher.shouldPersistManifest(
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                true
        ));
        assertTrue(AssetPatchGeneratedPackPublisher.shouldPersistManifest(
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_ASSETS,
                true
        ));
        assertFalse(AssetPatchGeneratedPackPublisher.shouldPersistManifest(
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_ASSETS,
                false
        ));
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
    void runtimeRefreshMutatesGeneratedCacheWithoutNpcBuilderUnload(@TempDir Path tempDir) throws Exception {
        Path staleTarget = tempDir.resolve("Server/NPC/Roles/_Core/Templates/Stale.json");
        Files.createDirectories(staleTarget.getParent());
        Files.writeString(staleTarget, "{}");
        AssetPatchGeneratedPackPublisher publisher = new AssetPatchGeneratedPackPublisher(null, "Generated");

        Map<String, JsonObject> generatedTemplates = new LinkedHashMap<>();
        generatedTemplates.put("Server/NPC/Roles/_Core/Templates/Current.json", new JsonObject());
        AssetPatchStatus status = new AssetPatchStatus();

        assertTrue(publisher.mutateCacheForPublication(
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                tempDir,
                generatedTemplates,
                status
        ).succeeded());

        assertFalse(Files.exists(staleTarget));
        assertTrue(Files.exists(tempDir.resolve("Server/NPC/Roles/_Core/Templates/Current.json")));
        assertEquals(List.of("Server/NPC/Roles/_Core/Templates/Current.json"), status.getGeneratedTargets());
        assertTrue(status.getFailed().isEmpty());
    }

    @Test
    void detectsUnchangedGeneratedContent(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("Server/Item/Items/Tool_Crate.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "{\n  \"Patched\": true\n}");

        assertTrue(AssetPatchGeneratedPackPublisher.isExistingContentSame(
                output,
                "{\n  \"Patched\": true\n}"
        ));
        assertFalse(AssetPatchGeneratedPackPublisher.isExistingContentSame(
                output,
                "{\n  \"Patched\": false\n}"
        ));
    }

    @Test
    void emptyRuntimeRefreshPrunesAllGeneratedJsonWithoutNpcBuilderUnload(@TempDir Path tempDir)
            throws Exception {
        Path staleTarget = tempDir.resolve("Server/NPC/Roles/_Core/Templates/Stale.json");
        Files.createDirectories(staleTarget.getParent());
        Files.writeString(staleTarget, "{}");
        AssetPatchGeneratedPackPublisher publisher = new AssetPatchGeneratedPackPublisher(null, "Generated");

        assertTrue(publisher.mutateCacheForPublication(
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_ASSETS,
                tempDir,
                Map.<String, JsonObject>of(),
                new AssetPatchStatus()
        ).succeeded());

        assertFalse(Files.exists(staleTarget));
    }

    @Test
    void runtimeReloadStillReturnsAffectedTargetsForWatcherObservation() {
        assertFalse(AssetPatchGeneratedPackPublisher.shouldReloadRuntimeTargetsAfterPublication(
                false,
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                true
        ));
        assertTrue(AssetPatchGeneratedPackPublisher.shouldReloadRuntimeTargetsAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.REFRESH_EXISTING_PACK,
                true
        ));
        assertTrue(AssetPatchGeneratedPackPublisher.shouldReloadRuntimeTargetsAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.REGISTER_PACK,
                false
        ));
        assertTrue(AssetPatchGeneratedPackPublisher.shouldReloadRuntimeTargetsAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_ASSETS,
                true
        ));
        assertFalse(AssetPatchGeneratedPackPublisher.shouldReloadRuntimeTargetsAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.NO_GENERATED_ASSETS,
                false
        ));
        assertFalse(AssetPatchGeneratedPackPublisher.shouldReloadRuntimeTargetsAfterPublication(
                true,
                AssetPatchGeneratedPackPublisher.PublicationAction.MISSING_RUNTIME_PACK,
                false
        ));
    }

    @Test
    void findsGeneratedPackByCacheRootWhenRuntimeNameDiffers(@TempDir Path tempDir) {
        Path generatedRoot = tempDir.resolve("GeneratedPatches");
        AssetPack generatedPack = pack("Alechilles:Alec's Tamework!", generatedRoot);

        assertEquals(generatedPack, AssetPatchGeneratedPackPublisher.findGeneratedPack(
                List.of(pack("Ceraph:Chocobo Tales", tempDir.resolve("Chocobo")), generatedPack),
                "Alechilles:Alec's Tamework!_GeneratedPatches",
                generatedRoot
        ));
    }

    @Test
    void movesGeneratedPackToLastWinningPriorityByCacheRoot(@TempDir Path tempDir) {
        Path generatedRoot = tempDir.resolve("GeneratedPatches");
        AssetPack contentPack = pack("Ceraph:Chocobo Tales", tempDir.resolve("Chocobo"));
        AssetPack generatedPack = pack("Alechilles:Alec's Tamework!", generatedRoot);
        List<AssetPack> packs = new ArrayList<>(List.of(contentPack, generatedPack));

        AssetPatchGeneratedPackPublisher.moveGeneratedPackToLastWinningPriority(
                packs,
                "Alechilles:Alec's Tamework!_GeneratedPatches",
                generatedRoot
        );

        assertEquals(contentPack, packs.getFirst());
        assertEquals(generatedPack, packs.get(1));
    }

    @Test
    void generatedPatchManifestSortsAfterCurrentAssetPacks(@TempDir Path tempDir) throws Exception {
        Path generatedRoot = tempDir.resolve("GeneratedPatches");
        AssetPack tameworkPack = pack("Alechilles:Alec's Tamework!", tempDir.resolve("Tamework"));
        AssetPack chocoboPack = pack("Ceraph:Chocobo Tales", tempDir.resolve("Chocobo"));
        PluginManifest generatedManifest = AssetPatchGeneratedPackPublisher.createGeneratedPatchManifest(
                manifest("Alechilles:Alec's Tamework!"),
                List.of(tameworkPack, chocoboPack),
                "Alechilles:Alec's Tamework!_GeneratedPatches",
                generatedRoot
        );
        AssetPack generatedPack = pack(
                "Alechilles:Alec's Tamework!_GeneratedPatches",
                generatedRoot,
                generatedManifest
        );

        Map<PluginIdentifier, AssetPack> pending = new HashMap<>();
        pending.put(PluginIdentifier.fromString(tameworkPack.getName()), tameworkPack);
        pending.put(PluginIdentifier.fromString(chocoboPack.getName()), chocoboPack);
        pending.put(PluginIdentifier.fromString(generatedPack.getName()), generatedPack);

        List<AssetPack> loadOrder = Mod.calculateLoadOrder(pending);

        assertEquals(generatedPack, loadOrder.getLast());
        assertTrue(loadOrder.indexOf(generatedPack) > loadOrder.indexOf(chocoboPack));
        assertTrue(loadOrder.indexOf(generatedPack) > loadOrder.indexOf(tameworkPack));
    }

    private static AssetPack pack(String name, Path root) {
        return pack(name, root, manifest(name));
    }

    private static AssetPack pack(String name, Path root, PluginManifest manifest) {
        return new AssetPack(root, name, root, FileSystems.getDefault(), false, manifest, AssetPack.PackSource.RUNTIME);
    }

    private static PluginManifest manifest(String id) {
        PluginIdentifier identifier = PluginIdentifier.fromString(id);
        PluginManifest manifest = new PluginManifest();
        manifest.setGroup(identifier.getGroup());
        manifest.setName(identifier.getName());
        manifest.setVersion(Semver.fromString("1.0.0"));
        manifest.setServerVersion(SemverRange.WILDCARD);
        return manifest;
    }
}
