package com.alechilles.alecstamework.assets.patches;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
