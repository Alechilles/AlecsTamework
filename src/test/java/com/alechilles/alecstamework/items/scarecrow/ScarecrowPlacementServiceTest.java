package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScarecrowPlacementServiceTest {
    @Test
    void centersScarecrowAboveSurfaceAndFacesActor() {
        ScarecrowPlacementService.Placement placement = ScarecrowPlacementService.plan(
                10,
                20,
                30,
                new Vector3d(10.5, 24.0, 35.5)
        );

        assertEquals(new Vector3d(10.5, 21.01, 30.5), placement.position());
        assertEquals(0.0f, placement.rotation().pitch(), 0.0001f);
        assertEquals((float) Math.PI, placement.rotation().yaw(), 0.0001f);
        assertEquals(0.0f, placement.rotation().roll(), 0.0001f);
    }

    @Test
    void buildsBlockScaleSuppressorWithRemovalHint() {
        ScarecrowPlacementService.Placement placement = ScarecrowPlacementService.plan(
                1,
                2,
                3,
                new Vector3d(1.5, 3.0, 8.5)
        );

        ScarecrowPlacementService.EntityComponents components =
                ScarecrowPlacementService.buildComponents(placement);

        assertEquals(ScarecrowIds.ITEM_ID, components.blockEntity().getBlockTypeKey());
        assertEquals(placement.position(), components.transform().getPosition());
        assertEquals(2.0f, components.scale().getScale());
        assertSame(PropComponent.get(), components.prop());
        assertEquals(
                ScarecrowIds.COLLECT_ROOT_INTERACTION_ID,
                components.interactions().getInteractionId(com.hypixel.hytale.protocol.InteractionType.Use)
        );
        assertEquals(
                ScarecrowIds.REMOVE_INTERACTION_HINT,
                components.interactions().getInteractionHint()
        );
        assertEquals(ScarecrowIds.SUPPRESSION_ID, components.suppression().getSpawnSuppression());
        assertNotNull(components.uuid().getUuid());
    }

}
