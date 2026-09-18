package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandOwnedPanelRecordSourceTest {
    /** Owned animals must remain discoverable without any link to the current tool. */
    @Test
    void includesOwnedProfilesAcrossLoadedAndStoredStatesRegardlessOfToolLinks() {
        UUID owner = UUID.randomUUID();
        Map<ProfileId, CompanionProfileProjectionState> profiles = new HashMap<>();
        for (LifecycleState state : new LifecycleState[]{LifecycleState.ACTIVE,
                LifecycleState.UNLOADED, LifecycleState.CAPTURED, LifecycleState.COOP,
                LifecycleState.DEAD_REVIVABLE, LifecycleState.LOST}) {
            var profile = profile(owner, state, UUID.randomUUID(),
                    state == LifecycleState.ACTIVE ? Set.of(UUID.randomUUID()) : Set.of());
            profiles.put(profile.profileId(), profile);
        }
        var records = new CommandOwnedPanelRecordSource(() -> profiles).recordsFor(owner);
        assertEquals(profiles.keySet(), records.stream()
                .map(record -> ProfileId.parse(record.profileId)).collect(java.util.stream.Collectors.toSet()));
        for (var record : records) {
            assertEquals(profiles.get(ProfileId.parse(record.profileId)).currentAlias().value(), record.npcUuid);
            assertEquals("My animal", record.cachedDisplayName);
            assertFalse(record.active, "Ownership alone must not select a companion on every flute");
        }
    }

    /** Released and transferred profiles must disappear on the next owner-list read. */
    @Test
    void excludesOtherOwnersOwnerlessAndReleasedProfiles() {
        UUID owner = UUID.randomUUID();
        Map<ProfileId, CompanionProfileProjectionState> profiles = new HashMap<>();
        for (var profile : new CompanionProfileProjectionState[]{
                profile(UUID.randomUUID(), LifecycleState.ACTIVE, UUID.randomUUID(), Set.of()),
                profile(null, LifecycleState.UNLOADED, UUID.randomUUID(), Set.of()),
                profile(owner, LifecycleState.RELEASED, UUID.randomUUID(), Set.of())}) {
            profiles.put(profile.profileId(), profile);
        }
        assertTrue(new CommandOwnedPanelRecordSource(() -> profiles).recordsFor(owner).isEmpty());
    }

    /** A stored companion with no current entity still needs a stable, visible row. */
    @Test
    void retainsProfileIdentityWithoutLiveAlias() {
        UUID owner = UUID.randomUUID();
        var profile = profile(owner, LifecycleState.CAPTURED, null, Set.of());
        var source = new CommandOwnedPanelRecordSource(() -> Map.of(profile.profileId(), profile));
        var record = source.recordsFor(owner).getFirst();
        assertEquals(profile.profileId().toString(), record.profileId);
        assertNotNull(record.npcUuid);
        assertEquals(record.npcUuid, source.recordsFor(owner).getFirst().npcUuid);
    }

    /** Capturing or remapping an animal must not drop its existing tool controls in Owned. */
    @Test
    void preservesLinkedRecordByProfileWhenAliasIsAbsentOrChanged() {
        UUID owner = UUID.randomUUID();
        for (UUID alias : new UUID[]{null, UUID.randomUUID()}) {
            var profile = profile(owner, LifecycleState.CAPTURED, alias, Set.of());
            var linked = new LinkedNpcRecord(UUID.randomUUID(), profile.profileId().toString(),
                    null, "OtherWorld", null, "My animal", null, "Cow", null, false, true, "favorites");
            var source = new CommandOwnedPanelRecordSource(() -> Map.of(profile.profileId(), profile));
            var record = source.recordsFor(owner, java.util.List.of(linked)).getFirst();
            assertEquals(linked.npcUuid, record.npcUuid);
            assertEquals("favorites", record.groupId);
            assertFalse(record.active);
            assertTrue(record.breedingEnabled);
        }
    }

    /** An unaliased Owned card can be abandoned only through its owner's stable profile. */
    @Test
    void resolvesUnaliasedRowForItsOwnerAndRejectsForgedOwner() {
        UUID owner = UUID.randomUUID();
        var profile = profile(owner, LifecycleState.LOST, null, Set.of());
        var source = new CommandOwnedPanelRecordSource(() -> Map.of(profile.profileId(), profile));
        UUID row = source.recordsFor(owner).getFirst().npcUuid;
        assertEquals(profile.profileId(), source.profileForRow(owner, row).orElseThrow());
        assertTrue(source.profileForRow(UUID.randomUUID(), row).isEmpty());
        assertTrue(source.profileForRow(owner, UUID.randomUUID()).isEmpty());
    }

    /** A generic Owned list keeps managed animals visible while withholding destructive actions. */
    @Test
    void marksManagedProfilesReadOnlyEvenWhenActiveAndUnlinked() {
        UUID owner = UUID.randomUUID();
        var managed = profile(owner, LifecycleState.ACTIVE, UUID.randomUUID(), Set.of());
        var ordinary = profile(owner, LifecycleState.UNLOADED, UUID.randomUUID(), Set.of());
        var source = new CommandOwnedPanelRecordSource(
                () -> Map.of(managed.profileId(), managed, ordinary.profileId(), ordinary),
                () -> Set.of(managed.profileId()));
        assertEquals(2, source.recordsFor(owner).size());
        var features = source.managedFeatures(owner, java.util.List.of());
        assertTrue(features.get(managed.currentAlias().value()).managesRosterRow());
        assertFalse(features.containsKey(ordinary.currentAlias().value()));
    }

    /** Capture can clear ownership, but carried and previously tracked captures must remain visible without authority. */
    @Test
    void showsKnownOwnerlessCapturesWithoutGrantingOwnership() {
        UUID owner = UUID.randomUUID();
        var carried = profile(null, LifecycleState.CAPTURED, UUID.randomUUID(), Set.of());
        var tracked = profile(null, LifecycleState.CAPTURED, UUID.randomUUID(), Set.of());
        var unrelated = profile(null, LifecycleState.CAPTURED, UUID.randomUUID(), Set.of());
        var transferred = profile(UUID.randomUUID(), LifecycleState.CAPTURED, UUID.randomUUID(), Set.of());
        var source = new CommandOwnedPanelRecordSource(() -> Map.of(carried.profileId(), carried,
                tracked.profileId(), tracked, unrelated.profileId(), unrelated, transferred.profileId(), transferred));
        var link = new LinkedNpcRecord(tracked.currentAlias().value(), tracked.profileId().toString(),
                null, null, null, "Tracked", null, "Cow", null, true, false, null);
        var visible = source.capturedRecordsFor(java.util.List.of(link),
                Set.of(carried.profileId().toString(), transferred.profileId().toString()));
        assertEquals(Set.of(carried.profileId().toString(), tracked.profileId().toString()),
                visible.stream().map(record -> record.profileId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(visible.stream().noneMatch(record -> record.active));
        assertTrue(source.recordsFor(owner, java.util.List.of(link)).isEmpty());
        for (var record : visible) assertTrue(source.profileForRow(owner, record.npcUuid).isEmpty());
    }

    private static CompanionProfileProjectionState profile(UUID owner, LifecycleState state,
            UUID alias, Set<UUID> links) {
        return new CompanionProfileProjectionState(new ProfileId(UUID.randomUUID()),
                alias == null ? null : new NpcAlias(alias), state,
                owner == null ? null : new OwnerId(owner), null, "Cow", "Cow", "My animal",
                true, null, null, links, Set.of(), 1L);
    }
}
