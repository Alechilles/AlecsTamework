package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.NpcCapturedEvent;
import com.alechilles.alecstamework.api.NpcDeathRecordedEvent;
import com.alechilles.alecstamework.api.NpcLostRecordedEvent;
import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.PopulationDomainClaim;
import com.alechilles.alecstamework.api.ProfileChangeType;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives a real index with the publisher attached and reads what a subscriber receives. */
class CompanionEventPublisherTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BODY = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String TOOL = "00000000-0000-0000-0000-0000000000c0";
    private static final long NOW = 9_000L;

    private final TameworkEventBus bus = new TameworkEventBus(null);
    private final List<TameworkEvent> events = new ArrayList<>();
    private final CompanionIndex index = new CompanionIndex(() -> 1_000L, (before, after) -> { });

    CompanionEventPublisherTest() {
        bus.subscribe(TameworkEvent.class, events::add);
        index.addAfterUnlockListener(new CompanionEventPublisher(
                bus, roleId -> "Sheep".equals(roleId) ? List.of("livestock") : List.of(), () -> NOW));
    }

    private CompanionRecord insertLive() {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), "Sheep",
                        CompanionLocation.live("default", 10.5, 64, -3))
                .ownerUuid(ALICE).ownerName("Alice").displayName("Sheep").currentNpcUuid(BODY)
                .toolIds(List.of(TOOL)).build();
        CompanionRecord inserted = index.insert(record).after();
        events.clear();
        return inserted;
    }

    private <E extends TameworkEvent> E only(Class<E> type) {
        List<E> matches = events.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), () -> type.getSimpleName() + " in " + events);
        return matches.get(0);
    }

    @Test
    void aNewCompanionIsReportedAsCreatedWithItsLocationGroupsAndClaims() {
        CompanionRecord record = CompanionRecord.builder(UUID.randomUUID(), "Sheep",
                        CompanionLocation.live("default", 1, 64, 1))
                .ownerUuid(ALICE).currentNpcUuid(BODY)
                .domainClaims(List.of(new DomainClaim("runeteria:husbandry_deployable", 2, false, true))).build();

        index.insert(record);

        assertEquals(1, events.size());
        NpcProfileChangedEvent event = assertInstanceOf(NpcProfileChangedEvent.class, events.get(0));
        assertEquals(record.profileId().toString(), event.profileId());
        assertTrue(event.changeTypes().containsAll(
                EnumSet.of(ProfileChangeType.CREATED, ProfileChangeType.OWNER, ProfileChangeType.LOCATION)));
        assertNull(event.before());
        assertEquals(ALICE, event.after().ownerUuid());
        assertNull(event.oldLocationKind());
        assertEquals("LIVE", event.newLocationKind());
        assertEquals(Set.of("livestock"), event.groupIds());
        assertEquals(Set.of(new PopulationDomainClaim("runeteria:husbandry_deployable", 2, false, true)),
                event.domainClaims());
        assertEquals(NOW, event.emittedAtMs());
    }

    @Test
    void storingReportsTheOldAndNewLocationKindsAndAPositionRefreshReportsNothing() {
        CompanionRecord live = insertLive();

        CompanionRecord moved = index.update(live.profileId(), live.revision(),
                b -> b.location(CompanionLocation.live("default", 40, 64, 40))).after();

        assertTrue(events.isEmpty());

        index.update(moved.profileId(), moved.revision(),
                CompanionTransitions.stored(moved, StoredReason.ROSTER, null, null, 0L));

        NpcProfileChangedEvent event = only(NpcProfileChangedEvent.class);
        assertEquals("LIVE", event.oldLocationKind());
        assertEquals("STORED", event.newLocationKind());
        assertTrue(event.changeTypes().contains(ProfileChangeType.LOCATION));
        assertEquals(BODY, event.before().currentNpcUuid());
        assertNull(event.after().currentNpcUuid());
        assertEquals(1, events.size());
    }

    @Test
    void anOwnerChangeIsReportedWithBothOwners() {
        CompanionRecord live = insertLive();

        index.update(live.profileId(), live.revision(), CompanionTransitions.ownerChanged(BOB, "Bob"));

        NpcProfileChangedEvent event = only(NpcProfileChangedEvent.class);
        assertEquals(EnumSet.of(ProfileChangeType.OWNER), event.changeTypes());
        assertEquals(ALICE, event.before().ownerUuid());
        assertEquals(BOB, event.after().ownerUuid());
        assertEquals("LIVE", event.oldLocationKind());
        assertEquals("LIVE", event.newLocationKind());
    }

    @Test
    void captureIntoAnItemPublishesTheCapturedEventWithTheBodyItLeft() {
        CompanionRecord live = insertLive();

        index.update(live.profileId(), live.revision(),
                CompanionTransitions.capturedToItem(live, live.summary(), ALICE, "Alice"));

        NpcCapturedEvent captured = only(NpcCapturedEvent.class);
        assertEquals(BODY, captured.npcUuid());
        assertEquals(ALICE, captured.ownerUuid());
        assertEquals("Sheep", captured.roleId());
        assertEquals(Set.of(TOOL), captured.toolIds());
        assertEquals(new Vector3View(10.5, 64, -3), captured.lastKnownPosition());
        assertNull(captured.homePosition());
        assertEquals(1_000L, captured.capturedAtMs());
        assertEquals(Set.of("capture"), captured.profile().activeSnapshotTypes());
        assertEquals("ITEM", only(NpcProfileChangedEvent.class).newLocationKind());
    }

    @Test
    void aWildBodyCapturedStraightIntoAnItemPublishesTheCapturedEvent() {
        CompanionTransitions.BodyFacts facts = new CompanionTransitions.BodyFacts(BODY, null, null, "Sheep", "Sheep",
                "default", 1, 64, 1, List.of(), CompanionSummary.EMPTY);

        index.insert(CompanionTransitions.newItem(UUID.randomUUID(), facts, BOB, "Bob"));

        NpcCapturedEvent captured = only(NpcCapturedEvent.class);
        assertEquals(BODY, captured.npcUuid());
        assertEquals(BOB, captured.ownerUuid());
        assertNull(captured.lastKnownPosition());
    }

    @Test
    void deathPublishesTheDeathEventWithItsTimesAndLastPosition() {
        CompanionRecord live = insertLive();

        index.update(live.profileId(), live.revision(),
                CompanionTransitions.died(live, live.summary(), 5_000L, 65_000L, "combat", null));

        NpcDeathRecordedEvent death = only(NpcDeathRecordedEvent.class);
        assertEquals(BODY, death.npcUuid());
        assertEquals(ALICE, death.ownerUuid());
        assertEquals("Alice", death.ownerName());
        assertTrue(death.tamed());
        assertEquals(Set.of(TOOL), death.toolIds());
        assertEquals(new Vector3View(10.5, 64, -3), death.lastKnownPosition());
        assertEquals(5_000L, death.diedAtMs());
        assertEquals(65_000L, death.respawnAvailableAtMs());
        assertEquals(Set.of("death"), death.profile().activeSnapshotTypes());
        assertEquals("DEAD", only(NpcProfileChangedEvent.class).newLocationKind());
    }

    @Test
    void aLostBodyPublishesTheLostEvent() {
        CompanionRecord live = insertLive();

        index.update(live.profileId(), live.revision(),
                CompanionTransitions.lost(live, null, CompanionTransitions.CAUSE_REMOVED, null));

        NpcLostRecordedEvent lost = only(NpcLostRecordedEvent.class);
        assertEquals(BODY, lost.npcUuid());
        assertEquals(new Vector3View(10.5, 64, -3), lost.lastKnownPosition());
        assertEquals(1_000L, lost.lostAtMs());
        assertEquals(Set.of("lost"), lost.profile().activeSnapshotTypes());
        assertEquals("LOST", only(NpcProfileChangedEvent.class).newLocationKind());
    }

    @Test
    void aReleaseHasNoAfterProfileAndNamesTheClaimsItGaveUp() {
        CompanionRecord live = insertLive();
        CompanionRecord claimed = index.update(live.profileId(), live.revision(),
                b -> b.domainClaims(List.of(new DomainClaim("domain", 1, true, false)))).after();
        events.clear();

        CompanionRecord released = index.update(claimed.profileId(), claimed.revision(),
                CompanionTransitions.released(claimed, "owner_release")).after();

        NpcProfileChangedEvent event = only(NpcProfileChangedEvent.class);
        assertTrue(event.changeTypes().contains(ProfileChangeType.RELEASED));
        assertTrue(event.changeTypes().contains(ProfileChangeType.LOCATION));
        assertEquals(ALICE, event.before().ownerUuid());
        assertNull(event.after());
        assertEquals("LIVE", event.oldLocationKind());
        assertEquals("RELEASED", event.newLocationKind());
        assertEquals(Set.of("livestock"), event.groupIds());
        assertEquals(Set.of(new PopulationDomainClaim("domain", 1, true, false)), event.domainClaims());
        events.clear();

        // A change to the tombstone is not a profile change.
        index.update(released.profileId(), released.revision(), b -> b.displayName("gone"));

        assertTrue(events.isEmpty());
    }

    @Test
    void aSubscriberCanReadAndChangeTheIndexFromAnEvent() {
        CompanionRecord live = insertLive();
        List<String> seen = new ArrayList<>();
        bus.subscribe(NpcProfileChangedEvent.class, event -> {
            CompanionRecord current = index.get(UUID.fromString(event.profileId()));
            seen.add(current.displayName());
            if ("first".equals(current.displayName())) {
                index.update(current.profileId(), current.revision(), b -> b.displayName("second"));
            }
        });

        index.update(live.profileId(), live.revision(), b -> b.displayName("first"));

        assertEquals(List.of("first", "second"), seen);
    }
}
