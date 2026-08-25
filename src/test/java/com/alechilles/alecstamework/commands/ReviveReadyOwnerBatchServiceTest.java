package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviveReadyOwnerBatchServiceTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID OTHER_OWNER = UUID.fromString(
            "20000000-0000-0000-0000-000000000002"
    );
    private static final ProfileId TOOL_LINKED = profile(1);
    private static final ProfileId ROSTER_LINKED = profile(2);
    private static final ProfileId ALREADY_READY = profile(3);
    private static final ProfileId OTHER_OWNER_LINKED = profile(4);
    private static final ProfileId UNLINKED = profile(5);

    /** Protects the owner debug command from updating only one linked profile. */
    @Test
    void marksEveryDeadLinkedCompanionOwnedByThePlayer() {
        ArrayList<ProfileId> marked = new ArrayList<>();
        ReviveReadyOwnerBatchService service =
                new ReviveReadyOwnerBatchService(
                        () -> Map.of(
                                TOOL_LINKED, state(
                                        TOOL_LINKED, OWNER, Set.of(tool(1)), 2_000L
                                ),
                                ROSTER_LINKED, state(
                                        ROSTER_LINKED, OWNER, Set.of(), 3_000L
                                ),
                                ALREADY_READY, state(
                                        ALREADY_READY, OWNER, Set.of(tool(3)), 1_000L
                                ),
                                OTHER_OWNER_LINKED, state(
                                        OTHER_OWNER_LINKED,
                                        OTHER_OWNER,
                                        Set.of(tool(4)),
                                        4_000L
                                ),
                                UNLINKED, state(
                                        UNLINKED, OWNER, Set.of(), 5_000L
                                )
                        ),
                        () -> Set.of(ROSTER_LINKED),
                        Set::of,
                        (profileId, ownerId, requestedAtMs) -> {
                            assertEquals(new OwnerId(OWNER), ownerId);
                            marked.add(profileId);
                            return true;
                        },
                        () -> 1_000L
                );

        ReviveReadyOwnerBatchService.UpdateResult result =
                service.markAll(new OwnerId(OWNER));

        assertEquals(Set.of(TOOL_LINKED, ROSTER_LINKED), Set.copyOf(marked));
        assertEquals(3, result.total());
        assertEquals(2, result.accepted());
        assertEquals(1, result.alreadyReady());
        assertEquals(0, result.rejected());
        assertFalse(result.projectionLagging());
    }

    @Test
    void refusesBatchWhileAnOwnerRosterProfileIsLagging() {
        ReviveReadyOwnerBatchService service =
                new ReviveReadyOwnerBatchService(
                        () -> Map.of(
                                ROSTER_LINKED,
                                state(ROSTER_LINKED, OWNER, Set.of(), 3_000L)
                        ),
                        Set::of,
                        () -> Set.of(ROSTER_LINKED),
                        (profileId, ownerId, requestedAtMs) -> {
                            throw new AssertionError(
                                    "Lagging roster data must block the batch"
                            );
                        },
                        () -> 1_000L
                );

        ReviveReadyOwnerBatchService.UpdateResult result =
                service.markAll(new OwnerId(OWNER));

        assertTrue(result.projectionLagging());
        assertEquals(0, result.total());
    }

    private static CompanionProfileProjectionState state(
            ProfileId profileId,
            UUID owner,
            Set<UUID> toolIds,
            long restorationAvailableAtMs
    ) {
        return new CompanionProfileProjectionState(
                profileId,
                null,
                LifecycleState.DEAD_REVIVABLE,
                new OwnerId(owner),
                null,
                "server.npcRole.test",
                "Companion",
                null,
                true,
                null,
                null,
                toolIds,
                Set.of(),
                restorationAvailableAtMs,
                1L
        );
    }

    private static ProfileId profile(int suffix) {
        return ProfileId.parse(String.format(
                "00000000-0000-0000-0000-%012d", suffix
        ));
    }

    private static UUID tool(int suffix) {
        return UUID.fromString(String.format(
                "30000000-0000-0000-0000-%012d", suffix
        ));
    }
}
