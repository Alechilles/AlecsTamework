package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.migrate.LegacyAliases.Entry;
import com.alechilles.alecstamework.companion.migrate.LegacyAliases.Kind;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyResolution.Action;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyResolution.Body;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyResolution.Decision;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyBodyResolutionTest {
    private static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static Body owned(UUID npc) {
        return new Body(true, false, null, npc);
    }

    private static Body projectionOnly(UUID npc, UUID profile) {
        return new Body(false, true, profile, npc);
    }

    private static CompanionRecord record(CompanionLocation location, long generation, UUID currentNpc) {
        return CompanionRecord.builder(PROFILE, "Tamed_Sheep", location)
                .generation(generation).currentNpcUuid(currentNpc).build();
    }

    private static CompanionRecord live(UUID currentNpc) {
        return record(CompanionLocation.live("default", 0, 0, 0), 0, currentNpc);
    }

    private static Entry alias(Kind kind) {
        return new Entry(PROFILE, kind);
    }

    private static Action decide(Body body, Entry alias, CompanionRecord record) {
        return LegacyBodyResolution.decide(body, alias, record, false, false, false).action();
    }

    @Test
    void theCurrentBodyOfALiveRecordIsStampedWithTheRecordsGeneration() {
        Decision decision = LegacyBodyResolution.decide(owned(NPC), alias(Kind.CURRENT), live(NPC), false, false, false);

        assertEquals(Action.STAMP_CURRENT, decision.action());
        assertEquals(PROFILE, decision.profileId());
        assertEquals(0L, decision.generation());
    }

    @Test
    void anUnownedCurrentBodyIsStampedToo() {
        Body tamedOnly = new Body(false, false, null, NPC);

        assertEquals(Action.STAMP_CURRENT, decide(tamedOnly, alias(Kind.CURRENT), live(NPC)));
    }

    @Test
    void aRetiredAliasOfALiveCompanionIsStale() {
        Decision decision = LegacyBodyResolution.decide(owned(NPC), alias(Kind.STALE), live(OTHER_NPC), false, false, false);

        assertEquals(Action.REMOVE_STALE, decision.action());
        assertEquals(PROFILE, decision.profileId());
    }

    @Test
    void aBodyOfACompanionThatIsNotLiveIsStale() {
        List<CompanionLocation> elsewhere = List.of(CompanionLocation.dead(null), CompanionLocation.lost("REMOVED"),
                CompanionLocation.stored(StoredReason.values()[0]), CompanionLocation.item(),
                CompanionLocation.coop("default", 1, 2, 3, 0), CompanionLocation.released(null));
        for (CompanionLocation location : elsewhere) {
            assertEquals(Action.REMOVE_STALE, decide(owned(NPC), alias(Kind.STALE), record(location, 0, null)),
                    location.kind().name());
        }
    }

    @Test
    void aBondedCleanupTargetWithOnlyAProjectionIdentityIsStale() {
        CompanionRecord stored = record(CompanionLocation.stored(StoredReason.values()[0]), 0, null);

        assertEquals(Action.REMOVE_STALE, decide(projectionOnly(NPC, PROFILE), alias(Kind.STALE), stored));
        // No alias at all: the projection identity alone names the profile.
        assertEquals(Action.REMOVE_STALE, decide(projectionOnly(NPC, PROFILE), null, stored));
    }

    @Test
    void aBodyNoRecordKnowsIsAdoptedOnlyWhenOwnedAndTamed() {
        assertEquals(Action.ADOPT, decide(owned(NPC), null, null));
        assertEquals(Action.LEAVE, decide(projectionOnly(NPC, PROFILE), null, null));
        // Its record exists on disk but could not be read: never adopt it as a new companion.
        assertEquals(Action.LEAVE, LegacyBodyResolution.decide(owned(NPC), null, null, true, false, false).action());
    }

    @Test
    void anAnimalReleasedInTheOldVersionIsAdoptedWhenAPlayerTamesItAgain() {
        CompanionRecord tombstone = record(CompanionLocation.released(null), 1, null);

        // A live tame: the animal is wild again, so it becomes a new companion.
        Decision tamed = LegacyBodyResolution.decide(owned(NPC), alias(Kind.STALE), tombstone, false, false, true);
        assertEquals(Action.ADOPT, tamed.action());
        assertNull(tamed.profileId());
        // The same body arriving already owned with its chunk is a leftover of the released companion.
        assertEquals(Action.REMOVE_STALE, decide(owned(NPC), alias(Kind.STALE), tombstone));
        // A live tame of a leftover copy of a companion that still exists is still removed.
        assertEquals(Action.REMOVE_STALE, LegacyBodyResolution.decide(owned(NPC), alias(Kind.STALE),
                record(CompanionLocation.dead(null), 0, null), false, false, true).action());
    }

    @Test
    void theBodyOfACompanionImportedAsLostRejoinsIt() {
        CompanionRecord lost = record(CompanionLocation.lost(LegacyMapper.CAUSE_UNRESOLVED), 0, null);

        Decision decision = LegacyBodyResolution.decide(owned(NPC), alias(Kind.REJOIN), lost, false, false, false);

        assertEquals(Action.REJOIN, decision.action());
        assertEquals(0L, decision.generation());
    }

    @Test
    void aRejoinBodyIsStaleOnceTheOwnerRecoveredTheCompanion() {
        CompanionRecord recovered = record(CompanionLocation.live("default", 0, 0, 0), 1, OTHER_NPC);
        CompanionRecord storedAfterRecovery = record(CompanionLocation.stored(StoredReason.values()[0]), 2, null);

        assertEquals(Action.REMOVE_STALE, decide(owned(NPC), alias(Kind.REJOIN), recovered));
        assertEquals(Action.REMOVE_STALE, decide(owned(NPC), alias(Kind.REJOIN), storedAfterRecovery));
    }

    @Test
    void aCompanionsOwnBodyIsRemovedOnlyWhenTheRecordProvesAnotherHolder() {
        // Proof: the record names a different body, or was restored since the import.
        assertEquals(Action.REMOVE_STALE, decide(owned(NPC), alias(Kind.CURRENT), live(OTHER_NPC)));
        assertEquals(Action.REMOVE_STALE, decide(owned(NPC), alias(Kind.CURRENT),
                record(CompanionLocation.dead(null), 1, null)));
        // No proof: generation 0 and no other body named.
        assertEquals(Action.LEAVE, decide(owned(NPC), alias(Kind.CURRENT),
                record(CompanionLocation.stored(StoredReason.values()[0]), 0, null)));
        assertEquals(Action.LEAVE, decide(owned(NPC), alias(Kind.REJOIN),
                record(CompanionLocation.lost("REMOVED"), 0, null)));
        assertEquals(Action.LEAVE, decide(owned(NPC), alias(Kind.REJOIN),
                record(CompanionLocation.coop("default", 1, 2, 3, 0), 0, null)));
    }

    @Test
    void doubtfulBodiesAreLeftAlone() {
        // The alias says this body belongs to another profile than the record it resolved to.
        Entry otherProfile = new Entry(UUID.randomUUID(), Kind.CURRENT);
        assertEquals(Action.LEAVE, decide(owned(NPC), otherProfile, live(OTHER_NPC)));
        // A tamed animal without an owner or projection identity is not a Tamework body to remove.
        assertEquals(Action.LEAVE, decide(new Body(false, false, null, NPC), alias(Kind.STALE), live(OTHER_NPC)));
        // A live record that names no body cannot show this one is a duplicate.
        assertEquals(Action.LEAVE, decide(owned(NPC), alias(Kind.STALE), live(null)));
        // Another body is already registered for the profile.
        assertEquals(Action.LEAVE,
                LegacyBodyResolution.decide(owned(NPC), alias(Kind.CURRENT), live(NPC), false, true, false).action());
    }

    // ---- with the index ----

    private static CompanionIndex index(CompanionRecord... records) {
        CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (before, after) -> { });
        index.load(List.of(records));
        return index;
    }

    private static Decision admit(CompanionIndex index, LoadedBodies<String> loaded, LegacyAliases aliases, Body body) {
        return LegacyBodyResolution.admit(index, loaded, aliases, id -> false, ref -> true, body, "ref", false,
                record -> b -> b.location(CompanionLocation.live("flatworld", 10, 64, -5)).currentNpcUuid(NPC));
    }

    @Test
    void theCurrentBodyIsRegisteredEvenWhenTheImportGuessedAnotherWorld() {
        CompanionIndex index = index(live(NPC));
        LoadedBodies<String> loaded = new LoadedBodies<>();

        Decision decision = admit(index, loaded, new LegacyAliases(Map.of(NPC, alias(Kind.CURRENT))), owned(NPC));

        assertEquals(Action.STAMP_CURRENT, decision.action());
        assertEquals("ref", loaded.get(PROFILE));
        assertEquals("flatworld", index.get(PROFILE).location().world());
        assertEquals(10.0, index.get(PROFILE).location().x());
    }

    @Test
    void aRecordThatMovedOnSinceTheImportIsNotOverwrittenByItsOldBody() {
        // The owner recovered the companion: generation 1, a new body.
        CompanionRecord recovered = record(CompanionLocation.live("default", 3, 4, 5), 1, OTHER_NPC);
        CompanionIndex index = index(recovered);
        LoadedBodies<String> loaded = new LoadedBodies<>();

        for (Kind kind : Kind.values()) {
            Decision decision = admit(index, loaded, new LegacyAliases(Map.of(NPC, alias(kind))), owned(NPC));

            assertEquals(Action.REMOVE_STALE, decision.action(), kind.name());
            assertEquals(recovered, index.get(PROFILE), kind.name());
            assertNull(loaded.get(PROFILE), kind.name());
        }
    }

    @Test
    void aRejoiningBodyMovesItsRecordBackToLive() {
        CompanionIndex index = index(record(CompanionLocation.lost(LegacyMapper.CAUSE_NO_BODY), 0, null));
        LoadedBodies<String> loaded = new LoadedBodies<>();

        Decision decision = admit(index, loaded, new LegacyAliases(Map.of(NPC, alias(Kind.REJOIN))), owned(NPC));

        assertEquals(Action.REJOIN, decision.action());
        CompanionRecord after = index.get(PROFILE);
        assertEquals(LocationKind.LIVE, after.location().kind());
        assertEquals("flatworld", after.location().world());
        assertEquals(NPC, after.currentNpcUuid());
        assertEquals(0L, after.generation());
        assertEquals("ref", loaded.get(PROFILE));
        assertEquals(PROFILE, index.byNpcUuid(NPC).profileId());
    }

    @Test
    void aStaleBodyChangesNeitherTheRecordNorTheLoadedBodies() {
        CompanionRecord before = live(OTHER_NPC);
        CompanionIndex index = index(before);
        LoadedBodies<String> loaded = new LoadedBodies<>();
        loaded.put(PROFILE, "current");

        Decision decision = admit(index, loaded, new LegacyAliases(Map.of(NPC, alias(Kind.STALE))), owned(NPC));

        assertEquals(Action.REMOVE_STALE, decision.action());
        assertEquals(before, index.get(PROFILE));
        assertEquals("current", loaded.get(PROFILE));
    }

    @Test
    void theProfileIsFoundByProjectionIdentityThenAliasThenNpcUuid() {
        LoadedBodies<String> loaded = new LoadedBodies<>();
        // Projection identity only: no alias knows this NPC UUID.
        assertEquals(PROFILE, admit(index(live(OTHER_NPC)), loaded, LegacyAliases.EMPTY,
                projectionOnly(NPC, PROFILE)).profileId());
        // Alias only.
        assertEquals(PROFILE, admit(index(live(OTHER_NPC)), loaded,
                new LegacyAliases(Map.of(NPC, alias(Kind.STALE))), owned(NPC)).profileId());
        // The NPC UUID is the profile id.
        CompanionRecord selfNamed = CompanionRecord.builder(NPC, "Tamed_Sheep", CompanionLocation.dead(null)).build();
        assertEquals(NPC, admit(index(selfNamed), loaded, LegacyAliases.EMPTY, owned(NPC)).profileId());
        // Nothing knows it.
        Decision unknown = admit(index(), loaded, LegacyAliases.EMPTY, owned(NPC));
        assertEquals(Action.ADOPT, unknown.action());
        assertNull(unknown.profileId());
        assertNull(loaded.get(PROFILE));
    }

    @Test
    void anAliasThatMarksTheBodyAsTheCompanionsOwnWinsOverTheProjectionIdentity() {
        UUID projected = UUID.randomUUID();
        CompanionRecord other = CompanionRecord.builder(projected, "Tamed_Sheep", CompanionLocation.dead(null)).build();
        LoadedBodies<String> loaded = new LoadedBodies<>();

        Decision decision = admit(index(live(NPC), other), loaded, new LegacyAliases(Map.of(NPC, alias(Kind.CURRENT))),
                new Body(true, true, projected, NPC));

        assertEquals(Action.STAMP_CURRENT, decision.action());
        assertEquals(PROFILE, decision.profileId());
    }
}
