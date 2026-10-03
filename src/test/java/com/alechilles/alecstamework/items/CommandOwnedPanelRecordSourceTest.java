package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandOwnedPanelRecordSourceTest {
    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
    private final CommandOwnedPanelRecordSource source =
            new CommandOwnedPanelRecordSource(new CompanionQueries(index, new LoadedBodies<>()));

    /** Large menus must resolve owned rows freshly, without retaining stale ownership. */
    @Test
    void resolvesBatchRowIdentitiesFromTheCurrentIndex() {
        UUID owner = UUID.randomUUID();
        var active = profile(owner, LifecycleState.ACTIVE, UUID.randomUUID());
        var stored = profile(owner, LifecycleState.CAPTURED, null);
        profile(owner, LifecycleState.RELEASED, UUID.randomUUID());
        for (int i = 0; i < 10_000; i++) {
            profile(UUID.randomUUID(), LifecycleState.ACTIVE, UUID.randomUUID());
        }
        var rows = source.profilesByRow(owner);
        assertEquals(Map.of(active.currentNpcUuid(), id(active),
                CommandRosterPanelRecordSource.presentationUuid(id(active)), id(active),
                CommandRosterPanelRecordSource.presentationUuid(id(stored)), id(stored)), rows);
        assertTrue(index.update(active.profileId(), active.revision(), CompanionTransitions.released(active))
                .applied());
        assertFalse(source.profilesByRow(owner).containsKey(active.currentNpcUuid()));
    }

    /** Owned animals must remain discoverable without any link to the current tool. */
    @Test
    void includesOwnedProfilesAcrossLoadedAndStoredStatesRegardlessOfToolLinks() {
        UUID owner = UUID.randomUUID();
        Map<ProfileId, CompanionRecord> profiles = new java.util.HashMap<>();
        for (LifecycleState state : new LifecycleState[]{LifecycleState.ACTIVE, LifecycleState.CAPTURED,
                LifecycleState.COOP, LifecycleState.DEAD_REVIVABLE, LifecycleState.LOST}) {
            var profile = profile(owner, state, UUID.randomUUID());
            profiles.put(id(profile), profile);
        }
        var records = source.recordsFor(owner);
        assertEquals(profiles.keySet(), records.stream()
                .map(record -> ProfileId.parse(record.profileId)).collect(java.util.stream.Collectors.toSet()));
        for (var record : records) {
            assertEquals(profiles.get(ProfileId.parse(record.profileId)).currentNpcUuid(), record.npcUuid);
            assertEquals("My animal", record.cachedDisplayName);
            assertFalse(record.active, "Ownership alone must not select a companion on every flute");
        }
    }

    /** Released and transferred profiles must disappear on the next owner-list read. */
    @Test
    void excludesOtherOwnersOwnerlessAndReleasedProfiles() {
        UUID owner = UUID.randomUUID();
        profile(UUID.randomUUID(), LifecycleState.ACTIVE, UUID.randomUUID());
        profile(null, LifecycleState.ACTIVE, UUID.randomUUID());
        profile(owner, LifecycleState.RELEASED, UUID.randomUUID());
        assertTrue(source.recordsFor(owner).isEmpty());
    }

    /** A stored companion with no current entity still needs a stable, visible row. */
    @Test
    void retainsProfileIdentityWithoutLiveAlias() {
        UUID owner = UUID.randomUUID();
        var profile = profile(owner, LifecycleState.CAPTURED, null);
        var record = source.recordsFor(owner).getFirst();
        assertEquals(profile.profileId().toString(), record.profileId);
        assertNotNull(record.npcUuid);
        assertEquals(record.npcUuid, source.recordsFor(owner).getFirst().npcUuid);
    }

    /** Capturing or remapping an animal must not drop its existing tool controls in Owned. */
    @Test
    void preservesLinkedRecordByProfileWhenAliasIsAbsentOrChanged() {
        for (UUID alias : new UUID[]{null, UUID.randomUUID()}) {
            UUID owner = UUID.randomUUID();
            var profile = profile(owner, LifecycleState.CAPTURED, alias);
            var linked = new LinkedNpcRecord(UUID.randomUUID(), profile.profileId().toString(),
                    null, "OtherWorld", null, "My animal", null, "Cow", null, false, true, "favorites");
            var record = source.recordsFor(owner, java.util.List.of(linked)).getFirst();
            assertEquals(linked.npcUuid, record.npcUuid);
            assertEquals("favorites", record.groupId);
            assertFalse(record.active);
            assertTrue(record.breedingEnabled);
        }
    }

    /** A dead or lost linked card must resolve its retired NPC UUID to the owned profile. */
    @Test
    void resolvesRetiredLinkedAliasForRestoration() {
        for (LifecycleState state : new LifecycleState[]{LifecycleState.DEAD_REVIVABLE, LifecycleState.LOST}) {
            UUID owner = UUID.randomUUID();
            var profile = profile(owner, state, null);
            UUID retiredAlias = UUID.randomUUID();
            var linked = new LinkedNpcRecord(retiredAlias, profile.profileId().toString(),
                    null, null, null, "My animal", null, "Cow", null, true, false, null);

            var snapshot = source.snapshot(owner, java.util.List.of(linked), Set.of());

            assertEquals(retiredAlias, snapshot.ownedRecords().getFirst().npcUuid);
            assertEquals(id(profile), snapshot.profilesByRow().get(retiredAlias));
            assertEquals(id(profile), source.profileForRow(owner, retiredAlias,
                    java.util.List.of(linked)).orElseThrow());
            assertFalse(source.snapshot(UUID.randomUUID(), java.util.List.of(linked), Set.of())
                    .profilesByRow().containsKey(retiredAlias));
        }
    }

    /** An unaliased Owned card can be abandoned only through its owner's stable profile. */
    @Test
    void resolvesUnaliasedRowForItsOwnerAndRejectsForgedOwner() {
        UUID owner = UUID.randomUUID();
        var profile = profile(owner, LifecycleState.LOST, null);
        UUID row = source.recordsFor(owner).getFirst().npcUuid;
        assertEquals(id(profile), source.profileForRow(owner, row).orElseThrow());
        assertTrue(source.profileForRow(UUID.randomUUID(), row).isEmpty());
        assertTrue(source.profileForRow(owner, UUID.randomUUID()).isEmpty());
    }

    /** A command-family roster member's actions belong to its family item; a generic item shows it read-only. */
    @Test
    void indexRosterMembersAreReadOnlyOnGenericItems() {
        UUID owner = UUID.randomUUID();
        var index = new com.alechilles.alecstamework.companion.index.CompanionIndex(
                System::currentTimeMillis, (before, after) -> { });
        var member = indexRecord(owner, "dragons", false);
        var bonded = indexRecord(owner, "dragons", true);
        var ordinary = indexRecord(owner, null, false);
        for (var record : java.util.List.of(member, bonded, ordinary)) assertTrue(index.insert(record).applied());
        var source = new CommandOwnedPanelRecordSource(new com.alechilles.alecstamework.companion.runtime
                .CompanionQueries(index, new com.alechilles.alecstamework.companion.live.LoadedBodies<>()));

        var features = source.managedFeatures(owner, java.util.List.of());

        assertTrue(features.get(member.currentNpcUuid()).managesRosterRow());
        assertFalse(features.containsKey(bonded.currentNpcUuid()));
        assertFalse(features.containsKey(ordinary.currentNpcUuid()));
        var listed = source.recordsFor(owner).stream().map(record -> record.profileId)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(listed.contains(bonded.profileId().toString()), "a bonded companion belongs to its own panel");
        assertTrue(listed.contains(ordinary.profileId().toString()));
    }

    /** With capture clearing the owner, the index files the item unowned; the holder still sees what they carry. */
    @Test
    void indexShowsCarriedUnownedCapturesReadOnlyAndHidesOthers() {
        UUID owner = UUID.randomUUID();
        var index = new com.alechilles.alecstamework.companion.index.CompanionIndex(
                System::currentTimeMillis, (before, after) -> { });
        var carried = itemRecord(null);
        var tracked = itemRecord(null);
        var elsewhere = itemRecord(null);
        var someoneElses = itemRecord(UUID.randomUUID());
        for (var record : java.util.List.of(carried, tracked, elsewhere, someoneElses)) {
            assertTrue(index.insert(record).applied());
        }
        var source = new CommandOwnedPanelRecordSource(new com.alechilles.alecstamework.companion.runtime
                .CompanionQueries(index, new com.alechilles.alecstamework.companion.live.LoadedBodies<>()));
        var link = new LinkedNpcRecord(UUID.randomUUID(), tracked.profileId().toString(),
                null, null, null, "Tracked", null, "Cow", null, false, false, null);

        var snapshot = source.snapshot(owner, java.util.List.of(link),
                Set.of(carried.profileId().toString(), someoneElses.profileId().toString(), "not-a-uuid"));

        assertEquals(Set.of(carried.profileId().toString(), tracked.profileId().toString()),
                snapshot.capturedRecords().stream().map(record -> record.profileId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(snapshot.ownedRecords().isEmpty());
        for (var record : snapshot.capturedRecords()) {
            assertTrue(source.profileForRow(owner, record.npcUuid).isEmpty(), "Carrying grants no authority.");
        }
    }

    private static com.alechilles.alecstamework.companion.index.CompanionRecord itemRecord(UUID owner) {
        return com.alechilles.alecstamework.companion.index.CompanionRecord.builder(UUID.randomUUID(), "Cow",
                        com.alechilles.alecstamework.companion.index.CompanionLocation.item())
                .ownerUuid(owner).build();
    }

    private static com.alechilles.alecstamework.companion.index.CompanionRecord indexRecord(
            UUID owner, String rosterId, boolean bonded) {
        return com.alechilles.alecstamework.companion.index.CompanionRecord.builder(UUID.randomUUID(), "Cow",
                        com.alechilles.alecstamework.companion.index.CompanionLocation.live("default", 0, 0, 0))
                .ownerUuid(owner).currentNpcUuid(UUID.randomUUID()).rosterId(rosterId).bonded(bonded).build();
    }

    private CompanionRecord profile(UUID owner, LifecycleState state, UUID alias) {
        CompanionLocation location = switch (state) {
            case ACTIVE -> CompanionLocation.live("default", 0, 0, 0);
            case CAPTURED -> CompanionLocation.item();
            case COOP -> CompanionLocation.coop("default", 0, 0, 0, 0);
            case DEAD_REVIVABLE -> CompanionLocation.dead(null);
            case LOST -> CompanionLocation.lost(null);
            case RELEASED -> CompanionLocation.released(null);
            default -> throw new IllegalArgumentException(state.name());
        };
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), "Cow", location)
                .ownerUuid(owner).currentNpcUuid(alias).displayName("My animal").build();
        assertTrue(index.insert(record).applied());
        return index.get(record.profileId());
    }

    private static ProfileId id(CompanionRecord record) {
        return new ProfileId(record.profileId());
    }
}
