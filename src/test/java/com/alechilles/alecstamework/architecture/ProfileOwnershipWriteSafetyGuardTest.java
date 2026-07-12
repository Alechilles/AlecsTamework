package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps dormant snapshot metadata from becoming a second profile-ownership authority. */
class ProfileOwnershipWriteSafetyGuardTest {
    private static final Path MAIN = Path.of("src", "main", "java", "com", "alechilles", "alecstamework");

    @Test
    void snapshotServicesUseOwnershipNeutralProfileUpserts() throws Exception {
        for (String file : List.of(
                "CommandLinkedNpcCaptureService.java",
                "CommandLinkedNpcStateSnapshotService.java"
        )) {
            String source = Files.readString(MAIN.resolve("items").resolve(file));
            assertTrue(source.contains("profileRepository.upsertSnapshotAsync("), file);
            assertFalse(source.contains("profileRepository.upsertAsync("), file);
        }
        String deathWriter = Files.readString(
                MAIN.resolve("items").resolve("CommandLinkedNpcDeathProfileWriter.java")
        );
        String deathService = Files.readString(
                MAIN.resolve("items").resolve("CommandLinkedNpcDeathService.java")
        );
        assertTrue(deathWriter.contains("profileRepository.upsertSnapshotAsync("));
        assertFalse(deathWriter.contains("profileRepository.upsertAsync("));
        assertTrue(deathService.contains("profileWriter.enqueue("));

        String managedFacade = Files.readString(
                MAIN.resolve("items").resolve("CommandLinkedNpcCoopService.java")
        );
        assertFalse(managedFacade.contains("upsertProfileInTransaction("));
        assertFalse(managedFacade.contains("upsertSnapshotAsync("));
        assertFalse(managedFacade.contains("upsertAsync("));

        String managedCaptureProfile = Files.readString(
                MAIN.resolve("persistence").resolve("sqlite")
                        .resolve("ManagedCoopCaptureProfileRepository.java")
        );
        assertTrue(managedCaptureProfile.contains("ProfileOwnerMutation.unchanged()"));
        assertFalse(managedCaptureProfile.contains("profiles.upsertAsync("));
    }

    @Test
    void snapshotRepositoriesPreserveThePopulationAuthorityOwnerColumn() throws Exception {
        for (String file : List.of(
                "CaptureRepository.java",
                "CoopLedgerRepository.java",
                "DeathRepository.java",
                "LostRepository.java"
        )) {
            String source = Files.readString(MAIN.resolve("persistence").resolve("sqlite").resolve(file));
            assertTrue(source.contains("ProfileOwnerMutation.unchanged()"), file);
        }
    }
}
