package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.CommandFamilyRosterMemberState;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandFamilyRosterRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void membershipIsOwnerAndRevisionFencedAndIdempotent() throws Exception {
        try (HydragonPersistenceTestHarness harness =
                     new HydragonPersistenceTestHarness(tempDir.resolve("roster.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "dragon-role", "PROVISIONED_DORMANT", "default", 3L);
            CommandFamilyRosterRepository repository =
                    new CommandFamilyRosterRepository(harness.connections, harness.queue);
            CommandFamilyRosterMutationRequest request = request(
                    "link-1", owner, profileId, 0L, 3L, CommandFamilyRosterMemberState.ROSTER_STORED);

            var applied = HydragonPersistenceTestHarness.await(repository.mutateAsync(
                    CommandFamilyRosterRepository.MutationKind.UPSERT, request, (role, ignored) -> null));
            assertEquals(CommandFamilyRosterMutationStatus.APPLIED, applied.status());
            assertEquals(1L, applied.roster().revision());
            assertEquals("dragon-role", applied.currentMembership().roleId());

            var replay = HydragonPersistenceTestHarness.await(repository.mutateAsync(
                    CommandFamilyRosterRepository.MutationKind.UPSERT, request, (role, ignored) -> null));
            assertEquals(CommandFamilyRosterMutationStatus.IDEMPOTENT, replay.status());
            assertTrue(replay.idempotentReplay());

            CommandFamilyRosterMutationRequest wrongOwner = request(
                    "link-wrong-owner", UUID.randomUUID(), profileId, 0L, 3L,
                    CommandFamilyRosterMemberState.ROSTER_STORED);
            var denied = HydragonPersistenceTestHarness.await(repository.mutateAsync(
                    CommandFamilyRosterRepository.MutationKind.UPSERT, wrongOwner, (role, ignored) -> null));
            assertEquals(CommandFamilyRosterMutationStatus.CONFLICT, denied.status());
            assertEquals("profile-owner-changed", denied.reason());
        }
    }

    @Test
    void removeDoesNotDeleteCanonicalProfile() throws Exception {
        try (HydragonPersistenceTestHarness harness =
                     new HydragonPersistenceTestHarness(tempDir.resolve("remove.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "dragon-role", "PROVISIONED_DORMANT", "default", 2L);
            CommandFamilyRosterRepository repository =
                    new CommandFamilyRosterRepository(harness.connections, harness.queue);
            HydragonPersistenceTestHarness.await(repository.mutateAsync(
                    CommandFamilyRosterRepository.MutationKind.UPSERT,
                    request("add", owner, profileId, 0L, 2L, CommandFamilyRosterMemberState.ROSTER_STORED),
                    (role, ignored) -> null));

            var removed = HydragonPersistenceTestHarness.await(repository.mutateAsync(
                    CommandFamilyRosterRepository.MutationKind.REMOVE,
                    request("remove", owner, profileId, 1L, 2L, CommandFamilyRosterMemberState.ROSTER_STORED),
                    (role, ignored) -> null));

            assertEquals(CommandFamilyRosterMutationStatus.APPLIED, removed.status());
            assertNull(removed.currentMembership());
            assertNotNull(loadProfile(harness, profileId));
        }
    }

    private CommandFamilyRosterMutationRequest request(String key, UUID owner, String profileId,
                                                       long rosterRevision, long profileRevision,
                                                       CommandFamilyRosterMemberState state) {
        return new CommandFamilyRosterMutationRequest(
                "test", key, null, owner, "dragons", profileId,
                "dragon-horn", "HyDragon_Dragon_Horn", state, null,
                true, null, rosterRevision, profileRevision);
    }

    private String loadProfile(HydragonPersistenceTestHarness harness, String profileId) throws Exception {
        try (Connection connection = harness.connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT profile_id FROM npc_profiles WHERE profile_id = ?")) {
            statement.setString(1, profileId);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
