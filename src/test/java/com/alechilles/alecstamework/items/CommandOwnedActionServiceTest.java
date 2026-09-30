package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.*;
import com.alechilles.alecstamework.companion.lifecycle.*;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandOwnedActionServiceTest {
    /** Unlinked saved animals can act, but a stale card cannot authorize a new owner's animal. */
    @Test void authorizesUnlinkedDormantProfilesAndRejectsTransferredOrReleasedProfiles() {
        UUID owner = UUID.randomUUID();
        for (var state : new LifecycleState[]{LifecycleState.UNLOADED,
                LifecycleState.DEAD_REVIVABLE, LifecycleState.LOST}) {
            var profile = profile(owner, state);
            assertTrue(CommandOwnedActionService.allows(owner, profile, java.util.Set.of(), java.util.Set.of()));
            assertFalse(CommandOwnedActionService.allows(UUID.randomUUID(), profile, java.util.Set.of(), java.util.Set.of()));
        }
        assertFalse(CommandOwnedActionService.allows(owner, profile(owner, LifecycleState.RELEASED), java.util.Set.of(), java.util.Set.of()));
        assertFalse(CommandOwnedActionService.allows(owner, null, java.util.Set.of(), java.util.Set.of()));
    }

    /** Requests retain the durable profile while targeting its current alias, without creating a link. */
    @Test void laggingManagedRosterCannotFallThroughToGenericActions() {
        UUID owner = UUID.randomUUID();
        var profile = profile(owner, LifecycleState.UNLOADED);
        assertFalse(CommandOwnedActionService.allows(owner, profile, java.util.Set.of(),
                java.util.Set.of(profile.identity().profileId())));
        assertFalse(CommandOwnedActionService.allows(owner, profile,
                java.util.Set.of(profile.identity().profileId()), java.util.Set.of()));
    }

    /** Captured and cooped profiles are addressable by Locate while mutation actions remain closed. */
    @Test void capturedAndCoopedProfilesAreLocateOnlyAndRetainOwnershipGates() {
        UUID owner = UUID.randomUUID();
        for (var profile : new CompanionProfileReadModel[]{
                profile(owner, LifecycleState.CAPTURED),
                profile(owner, LifecycleState.COOP)}) {
            assertFalse(CommandOwnedActionService.allows(owner, profile,
                    java.util.Set.of(), java.util.Set.of()));
            assertTrue(CommandOwnedActionService.allowsLocate(owner, profile,
                    java.util.Set.of(), java.util.Set.of()));
            assertFalse(CommandOwnedActionService.allowsLocate(UUID.randomUUID(), profile,
                    java.util.Set.of(), java.util.Set.of()));
            assertFalse(CommandOwnedActionService.allowsLocate(owner, profile,
                    java.util.Set.of(profile.identity().profileId()), java.util.Set.of()));
            assertFalse(CommandOwnedActionService.allowsLocate(owner, profile,
                    java.util.Set.of(), java.util.Set.of(profile.identity().profileId())));
        }
    }

    /** Index records open actions only to their owner, and locate also reaches captured and cooped ones. */
    @Test void indexRecordsGateOnTheirOwnerAndWhereTheCompanionIs() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        for (var location : List.of(CompanionLocation.live("default", 0, 0, 0),
                CompanionLocation.dead(null), CompanionLocation.lost(null))) {
            var companion = companion(owner, location);
            assertTrue(CommandOwnedActionService.allows(owner, companion));
            assertFalse(CommandOwnedActionService.allows(stranger, companion));
        }
        for (var location : List.of(CompanionLocation.item(), CompanionLocation.coop("default", 1, 2, 3, 0))) {
            var companion = companion(owner, location);
            assertFalse(CommandOwnedActionService.allows(owner, companion));
            assertTrue(CommandOwnedActionService.allowsLocate(owner, companion));
            assertFalse(CommandOwnedActionService.allowsLocate(stranger, companion));
        }
        for (var location : List.of(CompanionLocation.stored(StoredReason.ROSTER), CompanionLocation.released(null))) {
            assertFalse(CommandOwnedActionService.allowsLocate(owner, companion(owner, location)));
        }
        assertFalse(CommandOwnedActionService.allows(owner, (CompanionRecord) null));
    }

    private static CompanionRecord companion(UUID owner, CompanionLocation location) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", location).ownerUuid(owner).build();
    }

    @Test void actionRecordUsesCurrentAliasInsteadOfStaleCardId() {
        var profile = profile(UUID.randomUUID(), LifecycleState.UNLOADED);
        var record = CommandOwnedActionService.record(profile, UUID.randomUUID());
        assertEquals(profile.currentAlias().alias().value(), record.npcUuid);
        assertEquals(profile.identity().profileId().toString(), record.profileId);
    }

    private static CompanionProfileReadModel profile(UUID owner, LifecycleState state) {
        CoopSlotKey coopKey = state == LifecycleState.COOP
                ? new CoopSlotKey("world-a", "coop-a", 10, 64, 20, 0) : null;
        LifecycleLocation location = switch (state) {
            case CAPTURED -> LifecycleLocation.keyed(LifecycleLocationKind.CAPTURE_ITEM,
                    "capture-item");
            case COOP -> LifecycleLocation.keyed(LifecycleLocationKind.COOP_SLOT,
                    coopKey.toString());
            default -> LifecycleLocation.none();
        };
        var id = new ProfileId(UUID.randomUUID());
        return new CompanionProfileReadModel(
                new CompanionIdentity(id, "Sheep", "Sheep", null, null, "OtherWorld", -3, -2, -1, 0),
                new CompanionAlias(new NpcAlias(UUID.randomUUID()), id, 0, CompanionAlias.State.CURRENT,
                        null, -1, null),
                new CompanionLifecycle(id, new OwnerId(owner), state, location,
                        new LifecycleRevision(0), null, -1, ReconciliationGeneration.INITIAL, null, null),
                List.of(), List.of(), coopKey == null ? null : CoopSlot.unoccupied(coopKey));
    }
}
