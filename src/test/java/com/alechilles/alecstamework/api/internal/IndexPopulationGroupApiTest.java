package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.PopulationGroupCountsView;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexPopulationGroupApiTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String FLOCK = "test:flock";
    private static final String HERD = "test:herd";

    private final CompanionIndex index = new CompanionIndex(() -> 1_000L, (before, after) -> { });
    private PopulationGroupConfigIndex config = PopulationGroupFixtures.index(
            PopulationGroupFixtures.group("Flock", FLOCK, "\"Sheep\", \"Goat\"", 3, 1, "Global"),
            PopulationGroupFixtures.group("Herd", HERD, "\"Cow\", \"Goat\"", 0, 0, "PerWorld"));
    private final IndexPopulationGroupApi groups = new IndexPopulationGroupApi(index, () -> config);

    private void insert(UUID owner, String role, CompanionLocation location) {
        insert(owner, role, location, null);
    }

    private void insert(UUID owner, String role, CompanionLocation location, String homeWorld) {
        index.insert(CompanionRecord.builder(UUID.randomUUID(), role, location)
                .ownerUuid(owner).homeWorld(homeWorld).build());
    }

    @Test
    void definitionsComeFromTheCurrentConfig() {
        PopulationGroupDefinitionView flock = groups.getDefinition(" " + FLOCK + " ").orElseThrow();

        assertEquals("Flock", flock.configId());
        assertEquals(Set.of("Sheep", "Goat"), flock.roleIds());
        assertEquals(3L, flock.maxOwnedPerOwner());
        assertEquals(1L, flock.maxActivePerOwner());
        assertEquals(PopulationGroupScope.GLOBAL, flock.scope());
        assertEquals(Set.of(FLOCK, HERD),
                Set.copyOf(groups.resolveForRole("Goat").stream().map(PopulationGroupDefinitionView::groupId).toList()));
        assertTrue(groups.resolveForRole("Wolf").isEmpty());
        assertTrue(groups.getDefinition("test:unknown").isEmpty());

        config = PopulationGroupConfigIndex.empty();
        assertTrue(groups.getDefinition(FLOCK).isEmpty(), "a reload applies to the next read");
    }

    @Test
    void countsCoverEveryOwnedRecordAndOnlyLiveOnesAsDeployed() {
        insert(ALICE, "Sheep", CompanionLocation.live("default", 0, 0, 0));
        insert(ALICE, "Sheep", CompanionLocation.live("default", 0, 0, 0));
        insert(ALICE, "Goat", CompanionLocation.item());
        insert(ALICE, "Sheep", CompanionLocation.stored(StoredReason.ROSTER));
        insert(ALICE, "Sheep", CompanionLocation.dead("fall"));
        insert(ALICE, "Sheep", CompanionLocation.released("released"));
        insert(ALICE, "Wolf", CompanionLocation.live("default", 0, 0, 0));
        insert(BOB, "Sheep", CompanionLocation.live("default", 0, 0, 0));

        PopulationGroupCountsView counts = groups.getCounts(ALICE, FLOCK, null).orElseThrow();

        assertEquals(5L, counts.committedOwned());
        assertEquals(2L, counts.committedActive());
        assertEquals(0L, counts.pendingOwned());
        assertEquals(0L, counts.pendingActive());
        assertEquals(3L, counts.maxOwned());
        assertTrue(counts.overOwnedLimit());
        assertTrue(counts.overActiveLimit());
        assertNull(counts.ownershipWorldName());
        assertEquals(OptionalLong.of(5L), groups.getDurableOwnedCount(ALICE, Set.of(FLOCK)));
        assertEquals(OptionalLong.of(2L), groups.getDurableDeployableCount(ALICE, Set.of(FLOCK)));
        assertEquals(OptionalLong.of(0L), groups.getDurableDeployableCount(UUID.randomUUID(), Set.of(FLOCK)));
    }

    @Test
    void aPerWorldGroupCountsOneWorldAndNeedsThatWorld() {
        insert(ALICE, "Cow", CompanionLocation.live("default", 0, 0, 0));
        insert(ALICE, "Cow", CompanionLocation.live("nether", 0, 0, 0));
        insert(ALICE, "Cow", CompanionLocation.item(), "nether");

        assertTrue(groups.getCounts(ALICE, HERD, null).isEmpty());
        PopulationGroupCountsView nether = groups.getCounts(ALICE, HERD, "nether").orElseThrow();
        assertEquals(2L, nether.committedOwned());
        assertEquals(1L, nether.committedActive());
        assertEquals("nether", nether.ownershipWorldName());
        assertFalse(nether.overOwnedLimit(), "0 is no limit");
        assertTrue(groups.getCounts(ALICE, "test:unknown", "nether").isEmpty());
    }

    @Test
    void aRecordInTwoRequestedGroupsCountsOnceAndAnUnknownGroupGivesNoCount() {
        insert(ALICE, "Goat", CompanionLocation.live("default", 0, 0, 0));
        insert(ALICE, "Cow", CompanionLocation.live("default", 0, 0, 0));

        assertEquals(OptionalLong.of(2L), groups.getDurableDeployableCount(ALICE, Set.of(FLOCK, HERD)));
        assertEquals(OptionalLong.of(2L), groups.getDurableOwnedCount(ALICE, Set.of(FLOCK, HERD)));
        assertEquals(OptionalLong.empty(), groups.getDurableDeployableCount(ALICE, Set.of(FLOCK, "test:unknown")));
        assertEquals(OptionalLong.of(0L), groups.getDurableOwnedCount(ALICE, Set.of()));
    }
}
