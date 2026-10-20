package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.OwnedTraitSnapshot;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexNpcProfilesApiTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final CompanionIndex index = new CompanionIndex(() -> 1_000L, (before, after) -> { });
    private final IndexNpcProfilesApi profiles = new IndexNpcProfilesApi(
            new CompanionQueries(index, new LoadedBodies<>()), id -> null);

    private CompanionRecord insert(UUID profileId, UUID owner, CompanionLocation location) {
        CompanionRecord record = CompanionRecord.builder(profileId, "Sheep", location).ownerUuid(owner).build();
        index.insert(record);
        return record;
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    @Test
    void aProfileIsFoundByItsIdAndByItsCurrentBody() {
        UUID body = UUID.randomUUID();
        CompanionRecord record = CompanionRecord.builder(id(1), "Sheep", CompanionLocation.live("default", 1, 64, 1))
                .ownerUuid(ALICE).currentNpcUuid(body).build();
        index.insert(record);

        assertEquals(Optional.of(id(1).toString()), profiles.resolveProfileId(body));
        assertEquals(ALICE, profiles.getByProfileId(id(1).toString()).orElseThrow().ownerUuid());
        assertEquals(id(1).toString(), profiles.getByNpcUuid(body).orElseThrow().profileId());
    }

    @Test
    void unknownBlankAndMalformedIdsReadAsNoProfile() {
        assertTrue(profiles.getByProfileId(id(9).toString()).isEmpty());
        assertTrue(profiles.getByProfileId(" ").isEmpty());
        assertTrue(profiles.getByProfileId(null).isEmpty());
        assertTrue(profiles.getByProfileId("not-a-uuid").isEmpty());
        assertTrue(profiles.getByNpcUuid(UUID.randomUUID()).isEmpty());
        assertTrue(profiles.resolveProfileId(null).isEmpty());
        assertTrue(profiles.listActiveSnapshotTypes("not-a-uuid").isEmpty());
    }

    @Test
    void aReleasedCompanionIsNoLongerAProfile() {
        insert(id(1), ALICE, CompanionLocation.released("owner_release"));

        assertTrue(profiles.getByProfileId(id(1).toString()).isEmpty());
        assertTrue(profiles.listActiveSnapshotTypes(id(1).toString()).isEmpty());
    }

    @Test
    void theActiveSnapshotTypeFollowsWhereTheCompanionIs() {
        insert(id(1), ALICE, CompanionLocation.item());
        insert(id(2), ALICE, CompanionLocation.dead("combat"));
        insert(id(3), ALICE, CompanionLocation.lost(null));
        insert(id(4), ALICE, CompanionLocation.live("default", 1, 64, 1));
        insert(id(5), ALICE, CompanionLocation.stored(StoredReason.ROSTER));

        assertEquals(Set.of("capture"), profiles.listActiveSnapshotTypes(id(1).toString()));
        assertEquals(Set.of("death"), profiles.listActiveSnapshotTypes(id(2).toString()));
        assertEquals(Set.of("lost"), profiles.listActiveSnapshotTypes(id(3).toString()));
        assertTrue(profiles.listActiveSnapshotTypes(id(4).toString()).isEmpty());
        assertTrue(profiles.listActiveSnapshotTypes(id(5).toString()).isEmpty());

        assertTrue(profiles.getActiveSnapshot(id(1).toString(), "capture").isPresent());
        assertTrue(profiles.getActiveSnapshot(id(1).toString(), " Capture ").isPresent());
        assertTrue(profiles.getActiveSnapshot(id(1).toString(), "death").isEmpty());
        assertTrue(profiles.getActiveSnapshot(id(2).toString(), "death").isPresent());
        assertTrue(profiles.getActiveSnapshot(id(3).toString(), "lost").isPresent());
        assertTrue(profiles.getActiveSnapshot(id(4).toString(), "capture").isEmpty());
        assertTrue(profiles.getActiveSnapshot(id(1).toString(), null).isEmpty());
    }

    @Test
    void ownedTraitPagesAreOrderedByProfileAndSkipDeadReleasedAndOtherOwners() {
        insert(id(3), ALICE, CompanionLocation.item());
        insert(id(1), ALICE, CompanionLocation.live("default", 1, 64, 1));
        insert(id(2), ALICE, CompanionLocation.stored(StoredReason.ROSTER));
        insert(id(4), ALICE, CompanionLocation.dead("combat"));
        insert(id(5), ALICE, CompanionLocation.released("owner_release"));
        insert(id(6), BOB, CompanionLocation.item());

        List<String> firstPage = page(ALICE, 0, 2);
        List<String> secondPage = page(ALICE, 2, 2);

        assertEquals(List.of(id(1).toString(), id(2).toString()), firstPage);
        assertEquals(List.of(id(3).toString()), secondPage);
    }

    @Test
    void ownedTraitPageRejectsAnInvalidRange() {
        assertThrows(IllegalArgumentException.class, () -> profiles.getOwnedTraitSnapshots(ALICE, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> profiles.getOwnedTraitSnapshots(ALICE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> profiles.getOwnedTraitSnapshots(ALICE, 0, 65));
        assertThrows(NullPointerException.class, () -> profiles.getOwnedTraitSnapshots(null, 0, 10));
    }

    @Test
    void savedTraitsAreServedOnlyWhileTheirConfigResolves() throws Exception {
        insertWithTraits(id(1), "Size");
        TwTraitConfig config = traitConfig("size");

        OwnedTraitSnapshot resolved = firstRow(id -> "Sheep_Traits".equals(id) ? config : null);
        OwnedTraitSnapshot unresolved = firstRow(id -> null);

        assertTrue(resolved.traitDataAvailable());
        assertEquals("Size", resolved.traits().get(0).id());
        assertEquals(1.25, resolved.traits().get(0).value());
        assertEquals(7_000L, resolved.snapshotCreatedAtMs());
        assertFalse(unresolved.traitDataAvailable());
        assertTrue(unresolved.traits().isEmpty());
        assertEquals("Sheep_Traits", unresolved.traitConfigId());
    }

    @Test
    void aSavedTraitWithNoDefinitionMakesTheWholeRowUnavailable() throws Exception {
        insertWithTraits(id(1), "size", "retired_trait");
        TwTraitConfig config = traitConfig("size");

        OwnedTraitSnapshot row = firstRow(id -> config);

        assertFalse(row.traitDataAvailable());
        assertTrue(row.traits().isEmpty());
        assertEquals(7_000L, row.snapshotCreatedAtMs());
    }

    private void insertWithTraits(UUID profileId, String... traitIds) {
        Map<String, Double> traits = new LinkedHashMap<>();
        for (String traitId : traitIds) {
            traits.put(traitId, 1.25);
        }
        CompanionSummary summary = new CompanionSummary(null, null, null, null,
                0f, 0f, null, 0.0, null, 0.0, 0.0, false, false, 0L, 0L, 0L, 0L,
                null, 0, 0.0, 0.0, 0, traits, 7_000L, 0L, 0L, "Sheep_Traits", null, null);
        index.insert(CompanionRecord.builder(profileId, "Sheep", CompanionLocation.item())
                .ownerUuid(ALICE).summary(summary).build());
    }

    private OwnedTraitSnapshot firstRow(java.util.function.Function<String, TwTraitConfig> traitConfigs) {
        return new IndexNpcProfilesApi(new CompanionQueries(index, new LoadedBodies<>()), traitConfigs)
                .getOwnedTraitSnapshots(ALICE, 0, 10).toCompletableFuture().join().orElseThrow().get(0);
    }

    private static TwTraitConfig traitConfig(String... traitIds) throws Exception {
        Constructor<TwTraitConfig> constructor = TwTraitConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwTraitConfig config = constructor.newInstance();
        TwTraitConfig.TraitDefinition[] definitions = new TwTraitConfig.TraitDefinition[traitIds.length];
        for (int i = 0; i < traitIds.length; i++) {
            definitions[i] = new TwTraitConfig.TraitDefinition();
            set(definitions[i], "id", traitIds[i]);
        }
        set(config, "traits", definitions);
        return config;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private List<String> page(UUID owner, int offset, int limit) {
        return profiles.getOwnedTraitSnapshots(owner, offset, limit).toCompletableFuture().join().orElseThrow()
                .stream().map(OwnedTraitSnapshot::profileId).toList();
    }
}
