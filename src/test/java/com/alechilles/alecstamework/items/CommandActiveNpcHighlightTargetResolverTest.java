package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for restored command companions whose live UUID changed. */
class CommandActiveNpcHighlightTargetResolverTest {
    @Test
    void restoredNpcResolvesFromItsProfileWhenTheRecordedUuidIsNoLongerLoaded() {
        UUID historicalUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID liveUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
        identities.recordAdded(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                liveUuid,
                liveUuid,
                new LoadedNpcIdentityIndex.Location("new", "store-a"),
                new LoadedNpcIdentityIndex.ProjectionKey(
                        "profile-a", "restore-a", "RESTORATION", null, historicalUuid, 1L
                )
        ));
        CommandActiveNpcHighlightTargetResolver resolver =
                new CommandActiveNpcHighlightTargetResolver(identities);

        UUID resolved = resolver.resolve(
                historicalUuid,
                "profile-a",
                candidate -> candidate.equals(liveUuid)
        );

        assertEquals(liveUuid, resolved);
    }

    @Test
    void restoredNpcResolvesFromItsSourceAliasWhenLegacyRecordHasNoProfile() {
        UUID historicalUuid = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID liveUuid = UUID.fromString("00000000-0000-0000-0000-000000000004");
        LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
        identities.recordAdded(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                liveUuid,
                liveUuid,
                new LoadedNpcIdentityIndex.Location("new", "store-a"),
                new LoadedNpcIdentityIndex.ProjectionKey(
                        "profile-b", "restore-b", "RESTORATION", null, historicalUuid, 1L
                )
        ));
        CommandActiveNpcHighlightTargetResolver resolver =
                new CommandActiveNpcHighlightTargetResolver(identities);

        UUID resolved = resolver.resolve(
                historicalUuid,
                null,
                candidate -> candidate.equals(liveUuid)
        );

        assertEquals(liveUuid, resolved);
    }
}
