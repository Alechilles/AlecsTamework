package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandTimedSummoningApi;
import com.alechilles.alecstamework.api.CommandTimedSummoningChangedEvent;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningResult;
import com.alechilles.alecstamework.api.CommandTimedSummoningState;
import com.alechilles.alecstamework.api.CommandTimedSummoningView;
import com.alechilles.alecstamework.api.PaidCommandRevivalApi;
import com.alechilles.alecstamework.api.PopulationGroupApi;
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
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    void snapshotUsesOneCanonicalMemberSetForRecordsAndFeatureRows() {
        ProfileId profileId = profile(7);
        CommandRosterPanelRecordSource source =
                new CommandRosterPanelRecordSource(() -> Map.of(
                        profileId,
                        action(
                                profileId,
                                OWNER_UUID,
                                FAMILY_ID,
                                new NpcAlias(UUID.fromString(
                                        "60000000-0000-0000-0000-000000000007"
                                )),
                                true,
                                null,
                                LifecycleState.ACTIVE
                        )
                ));

        CommandRosterPanelRecordSource.PanelSnapshot snapshot =
                source.snapshotFor(OWNER_UUID, FAMILY_ID);

        assertEquals(1, snapshot.members().size());
        assertEquals(1, snapshot.records().size());
        assertEquals(
                snapshot.members().getFirst().presentationUuid(),
                snapshot.records().getFirst().npcUuid
        );
        assertEquals(
                snapshot.members().getFirst().profileId(),
                snapshot.records().getFirst().profileId
        );
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

    @Test
    void featurePresentationObservesUnavailableToReadyApiTransition()
            throws Exception {
        ProfileId profileId = profile(6);
        CommandRosterPanelRecordSource roster =
                new CommandRosterPanelRecordSource(() -> Map.of(
                        profileId,
                        action(
                                profileId,
                                OWNER_UUID,
                                FAMILY_ID,
                                null,
                                true,
                                null,
                                LifecycleState.ROSTER_STORED
                        )
                ));
        AtomicReference<CommandTimedSummoningApi> timed =
                new AtomicReference<>(
                        CommandTimedSummoningApi.unavailable()
                );
        CommandPanelFeaturePresentationSource presentations =
                new CommandPanelFeaturePresentationSource(
                        roster,
                        timed::get,
                        PaidCommandRevivalApi::unavailable,
                        PopulationGroupApi::unavailable,
                        () -> 1_000L
                );
        TwCommandItemConfig config = ownerFamilyConfig(FAMILY_ID);

        CommandPanelFeaturePresentation unavailable =
                presentations.snapshot(OWNER_UUID, "world-a", config)
                        .values().iterator().next();
        assertEquals(
                CommandTimedSummoningState.ROSTER_STORED,
                unavailable.roster().state()
        );

        timed.set(readyTimedApi(profileId));
        CommandPanelFeaturePresentation ready =
                presentations.snapshot(OWNER_UUID, "world-a", config)
                        .values().iterator().next();
        assertEquals(
                CommandTimedSummoningState.ACTIVE,
                ready.roster().state()
        );
        assertEquals(2L, ready.roster().revision());
        assertEquals(
                Long.valueOf(5_000L), ready.roster().remainingMs()
        );
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

    private static CommandTimedSummoningApi readyTimedApi(
            ProfileId profileId
    ) {
        return new CommandTimedSummoningApi() {
            @Override
            public Optional<CommandTimedSummoningView> get(
                    CommandTimedSummoningRequest identity
            ) {
                return Optional.of(new CommandTimedSummoningView(
                        OWNER_UUID,
                        FAMILY_ID,
                        profileId.toString(),
                        2L,
                        CommandTimedSummoningState.ACTIVE,
                        "session-1",
                        5_000L,
                        false,
                        0L,
                        1_000L
                ));
            }

            @Override
            public CompletionStage<CommandTimedSummoningResult> summon(
                    CommandTimedSummoningRequest request
            ) {
                return CommandTimedSummoningApi.unavailable()
                        .summon(request);
            }

            @Override
            public CompletionStage<CommandTimedSummoningResult> dismiss(
                    CommandTimedSummoningRequest request
            ) {
                return CommandTimedSummoningApi.unavailable()
                        .dismiss(request);
            }

            @Override
            public AutoCloseable subscribe(
                    Consumer<CommandTimedSummoningChangedEvent> listener
            ) {
                return () -> {
                };
            }
        };
    }

    private static TwCommandItemConfig ownerFamilyConfig(
            String familyId
    ) throws Exception {
        var constructor =
                TwCommandItemConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCommandItemConfig config = constructor.newInstance();
        setField(config, "commandFamilyId", familyId);
        setField(
                config,
                "rosterStorage",
                TwCommandItemConfig.RosterStorage.OwnerCommandFamily
        );
        return config;
    }

    private static void setField(
            TwCommandItemConfig config,
            String name,
            Object value
    ) throws Exception {
        Field field = TwCommandItemConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }
}
