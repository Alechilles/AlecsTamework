package com.alechilles.alecstamework.items.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components
        .TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Verifies capture/release ownership normalization without mutating frozen source components.
 */
class SpawnerFullStateOwnershipNormalizerTest {
    private static final UUID SOURCE_OWNER = UUID.fromString(
            "79000000-0000-0000-0000-000000000001"
    );
    private static final UUID REASSIGNED_OWNER = UUID.fromString(
            "79000000-0000-0000-0000-000000000002"
    );
    private static final UUID NPC = UUID.fromString(
            "79000000-0000-0000-0000-000000000003"
    );
    private static final String[] TOOL_IDS = {"command-one", "command-two"};
    private static final Vector3d HOME = new Vector3d(12.5, -4.0, 88.25);

    private final SpawnerFullStateOwnershipNormalizer normalizer =
            new SpawnerFullStateOwnershipNormalizer();

    @Test
    void clearOwnerClearsBothOwnershipComponentsWithoutMutatingSource() {
        TameworkOwnerComponent sourceOwner = sourceOwner();
        TameworkCommandLinksComponent sourceLinks = sourceLinks();
        CoopResidentStateSnapshot source = snapshot(sourceOwner, sourceLinks);

        CoopResidentStateSnapshot normalized = normalizer.normalize(
                source, null, "ignored"
        );

        assertNull(normalized.owner());
        assertNull(normalized.commandLinks().getOwnerId());
        assertSourceUnchanged(source, sourceOwner, sourceLinks);
        assertCommandStatePreserved(normalized.commandLinks(), sourceLinks);
    }

    @Test
    void sameOwnerIsPreservedInFreshComponentsWithoutMutatingSource() {
        TameworkOwnerComponent sourceOwner = sourceOwner();
        TameworkCommandLinksComponent sourceLinks = sourceLinks();
        CoopResidentStateSnapshot source = snapshot(sourceOwner, sourceLinks);

        CoopResidentStateSnapshot normalized = normalizer.normalize(
                source, new OwnerId(SOURCE_OWNER), "  Preserved Owner  "
        );

        assertEquals(SOURCE_OWNER, normalized.owner().getOwnerId());
        assertEquals("Preserved Owner", normalized.owner().getOwnerName());
        assertEquals(SOURCE_OWNER, normalized.commandLinks().getOwnerId());
        assertNotSame(sourceOwner, normalized.owner());
        assertNotSame(sourceLinks, normalized.commandLinks());
        assertSourceUnchanged(source, sourceOwner, sourceLinks);
        assertCommandStatePreserved(normalized.commandLinks(), sourceLinks);
    }

    @Test
    void differentOwnerReassignsFreshComponentsWithoutMutatingSource() {
        TameworkOwnerComponent sourceOwner = sourceOwner();
        TameworkCommandLinksComponent sourceLinks = sourceLinks();
        CoopResidentStateSnapshot source = snapshot(sourceOwner, sourceLinks);

        CoopResidentStateSnapshot normalized = normalizer.normalize(
                source, new OwnerId(REASSIGNED_OWNER), "Reassigned Owner"
        );

        assertEquals(REASSIGNED_OWNER, normalized.owner().getOwnerId());
        assertEquals("Reassigned Owner", normalized.owner().getOwnerName());
        assertEquals(REASSIGNED_OWNER, normalized.commandLinks().getOwnerId());
        assertNotSame(sourceOwner, normalized.owner());
        assertNotSame(sourceLinks, normalized.commandLinks());
        assertSourceUnchanged(source, sourceOwner, sourceLinks);
        assertCommandStatePreserved(normalized.commandLinks(), sourceLinks);
    }

    private void assertSourceUnchanged(
            CoopResidentStateSnapshot source,
            TameworkOwnerComponent sourceOwner,
            TameworkCommandLinksComponent sourceLinks
    ) {
        assertSame(sourceOwner, source.owner());
        assertSame(sourceLinks, source.commandLinks());
        assertEquals(SOURCE_OWNER, sourceOwner.getOwnerId());
        assertEquals("Original Owner", sourceOwner.getOwnerName());
        assertEquals(SOURCE_OWNER, sourceLinks.getOwnerId());
        assertArrayEquals(TOOL_IDS, sourceLinks.getToolIds());
        assertHomeEquals(HOME, sourceLinks.getHomePosition());
    }

    private void assertCommandStatePreserved(
            TameworkCommandLinksComponent normalized,
            TameworkCommandLinksComponent source
    ) {
        assertArrayEquals(TOOL_IDS, normalized.getToolIds());
        assertHomeEquals(HOME, normalized.getHomePosition());
        assertNotSame(source.getToolIds(), normalized.getToolIds());
    }

    private void assertHomeEquals(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x);
        assertEquals(expected.y, actual.y);
        assertEquals(expected.z, actual.z);
    }

    private TameworkOwnerComponent sourceOwner() {
        return new TameworkOwnerComponent(SOURCE_OWNER, "Original Owner");
    }

    private TameworkCommandLinksComponent sourceLinks() {
        return new TameworkCommandLinksComponent(
                SOURCE_OWNER, TOOL_IDS, HOME
        );
    }

    private CoopResidentStateSnapshot snapshot(
            TameworkOwnerComponent owner,
            TameworkCommandLinksComponent links
    ) {
        return new CoopResidentStateSnapshot(
                NPC,
                "coop-one",
                2,
                "Test_Role",
                links,
                owner,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0.75D,
                -500L
        );
    }
}
