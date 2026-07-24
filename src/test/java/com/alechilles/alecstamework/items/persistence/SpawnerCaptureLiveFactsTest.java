package com.alechilles.alecstamework.items.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components
        .TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components
        .TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components
        .TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components
        .TameworkTamedComponent;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Regression coverage for the component-to-value capture boundary. */
class SpawnerCaptureLiveFactsTest {

    @Test
    void retainedCaptureBoundaryTypesAreEngineNeutral() {
        for (java.lang.reflect.RecordComponent component
                : SpawnerCaptureEvidenceFreezer.FrozenCapture.class
                .getRecordComponents()) {
            String type = component.getType().getName();
            assertFalse(type.startsWith("com.hypixel.hytale"), type);
            assertFalse(type.contains("CoopResidentStateSnapshot"), type);
            assertFalse(type.contains(".npc.components."), type);
        }
    }

    @Test
    void componentMutationCannotChangeFrozenAsyncFacts() {
        UUID npc = UUID.fromString(
                "86000000-0000-0000-0000-000000000001"
        );
        UUID tool = UUID.fromString(
                "86000000-0000-0000-0000-000000000002"
        );
        TameworkCommandLinksComponent links =
                new TameworkCommandLinksComponent(
                        null,
                        new String[]{tool.toString()},
                        new Vector3d(1.0, 2.0, 3.0)
                );
        TameworkNpcNameComponent name = new TameworkNpcNameComponent();
        name.setName("Before");
        TameworkOwnerComponent owner =
                new TameworkOwnerComponent(null, "Owner Before");
        TameworkTamedComponent tamed = new TameworkTamedComponent(true);

        SpawnerCaptureLiveFacts frozen = SpawnerCaptureLiveFacts.freeze(
                snapshot(npc, links, name, owner, tamed)
        );
        links.setToolIds(new String[0]);
        links.setHomePosition(new Vector3d(9.0, 9.0, 9.0));
        name.setName("After");
        owner.setOwnerName("Owner After");
        tamed.setTamed(false);

        assertEquals("Before", frozen.displayName());
        assertEquals(java.util.List.of(tool), frozen.toolIds());
        assertEquals(1.0, frozen.homePosition().x());
        assertEquals(2.0, frozen.homePosition().y());
        assertEquals(3.0, frozen.homePosition().z());
        assertTrue(frozen.metadataJson().contains("Owner Before"));
        assertTrue(frozen.metadataJson().contains("\"tamed\":true"));
    }

    private CoopResidentStateSnapshot snapshot(
            UUID npc,
            TameworkCommandLinksComponent links,
            TameworkNpcNameComponent name,
            TameworkOwnerComponent owner,
            TameworkTamedComponent tamed
    ) {
        return new CoopResidentStateSnapshot(
                npc,
                null,
                -1,
                "test_role",
                links,
                owner,
                tamed,
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1.0,
                -100L
        );
    }
}
