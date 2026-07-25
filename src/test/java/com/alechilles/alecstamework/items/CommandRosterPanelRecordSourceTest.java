package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterActionView;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for canonical owner/family command-panel sourcing. */
class CommandRosterPanelRecordSourceTest {
    private static final UUID OWNER_UUID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER_UUID =
            UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final String FAMILY_ID = "hydragon:dragon_horn";

    @Test
    void copiedPhysicalAccessItemsReadTheSameOwnerFamilyRoster() {
        ProfileId later = profile(2);
        ProfileId earlier = profile(1);
        LinkedHashMap<ProfileId, CommandRosterActionView> snapshot =
                new LinkedHashMap<>();
        snapshot.put(later, action(
                later, OWNER_UUID, FAMILY_ID, null, false, "sky",
                LifecycleState.ROSTER_STORED
        ));
        snapshot.put(
                profile(3),
                action(
                        profile(3), OWNER_UUID, "other-family", null,
                        true, null, LifecycleState.ROSTER_STORED
                )
        );
        snapshot.put(
                profile(4),
                action(
                        profile(4), OTHER_OWNER_UUID, FAMILY_ID, null,
                        true, null, LifecycleState.ROSTER_STORED
                )
        );
        NpcAlias liveAlias = new NpcAlias(
                UUID.fromString(
                        "60000000-0000-0000-0000-000000000001"
                )
        );
        snapshot.put(earlier, action(
                earlier, OWNER_UUID, FAMILY_ID, liveAlias, true,
                "favorites", LifecycleState.ACTIVE
        ));
        CommandRosterPanelRecordSource source =
                new CommandRosterPanelRecordSource(() -> snapshot);

        List<LinkedNpcRecord> firstCopy =
                source.recordsFor(OWNER_UUID, FAMILY_ID);
        List<LinkedNpcRecord> secondCopy =
                source.recordsFor(OWNER_UUID, FAMILY_ID);

        assertEquals(2, firstCopy.size());
        assertEquals(earlier.toString(), firstCopy.get(0).profileId);
        assertEquals(liveAlias.value(), firstCopy.get(0).npcUuid);
        assertTrue(firstCopy.get(0).active);
        assertEquals("favorites", firstCopy.get(0).groupId);
        assertEquals(later.toString(), firstCopy.get(1).profileId);
        assertEquals(
                CommandRosterPanelRecordSource.presentationUuid(later),
                firstCopy.get(1).npcUuid
        );
        assertFalse(firstCopy.get(1).active);
        assertEquals("sky", firstCopy.get(1).groupId);
        assertEquals(firstCopy.get(0).npcUuid, secondCopy.get(0).npcUuid);
        assertEquals(firstCopy.get(1).npcUuid, secondCopy.get(1).npcUuid);
    }

    @Test
    void recordCarriesCanonicalProfileRoleAndHomeWithoutCommandStateOverload() {
        ProfileId profileId = profile(5);
        CommandRosterPanelRecordSource source =
                new CommandRosterPanelRecordSource(() -> Map.of(
                        profileId,
                        action(
                                profileId,
                                OWNER_UUID,
                                FAMILY_ID,
                                null,
                                true,
                                "guardians",
                                LifecycleState.ROSTER_STORED
                        )
                ));

        LinkedNpcRecord record =
                source.recordsFor(OWNER_UUID, FAMILY_ID).get(0);

        assertEquals(profileId.toString(), record.profileId);
        assertEquals("Dragon", record.cachedRoleId);
        assertEquals("guardians", record.groupId);
        assertEquals(10.5, record.homePosition.x);
        assertEquals(-20.25, record.homePosition.y);
        assertEquals(30.75, record.homePosition.z);
        assertNull(record.cachedCommandState);
    }

    @Test
    void missingIdentityOrProjectionFailureFailsClosed() {
        CommandRosterPanelRecordSource source =
                new CommandRosterPanelRecordSource(() -> {
                    throw new IllegalStateException("projection unavailable");
                });

        assertTrue(source.recordsFor(null, FAMILY_ID).isEmpty());
        assertTrue(source.recordsFor(OWNER_UUID, " ").isEmpty());
        assertTrue(source.recordsFor(OWNER_UUID, FAMILY_ID).isEmpty());
    }

    private static CommandRosterActionView action(
            ProfileId profileId,
            UUID ownerUuid,
            String familyId,
            NpcAlias alias,
            boolean activeForBulkCommands,
            String groupId,
            LifecycleState lifecycleState
    ) {
        OwnerId ownerId = new OwnerId(ownerUuid);
        CommandRosterMembership membership = membership(
                profileId,
                ownerId,
                familyId,
                activeForBulkCommands,
                groupId
        );
        CompanionLifecycle lifecycle = lifecycle(
                profileId, ownerId, membership, lifecycleState
        );
        return new CommandRosterActionView(
                membership, "Dragon", 1, alias, lifecycle
        );
    }

    private static CommandRosterMembership membership(
            ProfileId profileId,
            OwnerId ownerId,
            String familyId,
            boolean activeForBulkCommands,
            String groupId
    ) {
        return new CommandRosterMembership(
                new CommandRosterSlotId(UUID.nameUUIDFromBytes(
                        ("slot-" + profileId).getBytes(
                                StandardCharsets.UTF_8
                        )
                )),
                new CommandFamilyKey(ownerId, familyId),
                profileId,
                1,
                groupId,
                activeForBulkCommands,
                new CommandRosterHome(
                        "world-a", 10.5, -20.25, 30.75
                ),
                -200,
                -100
        );
    }

    private static CompanionLifecycle lifecycle(
            ProfileId profileId,
            OwnerId ownerId,
            CommandRosterMembership membership,
            LifecycleState lifecycleState
    ) {
        LifecycleLocation location =
                lifecycleState == LifecycleState.ACTIVE
                        ? LifecycleLocation.liveEntity(
                                "entity-" + profileId, "world-a"
                        )
                        : LifecycleLocation.keyed(
                                LifecycleLocationKind.COMMAND_ROSTER,
                                membership.slotId().toString()
                        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                ownerId,
                lifecycleState,
                location,
                new LifecycleRevision(1),
                null,
                -100,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
        return lifecycle;
    }

    private static ProfileId profile(int suffix) {
        return new ProfileId(UUID.fromString(
                "20000000-0000-0000-0000-"
                        + String.format("%012d", suffix)
        ));
    }
}
