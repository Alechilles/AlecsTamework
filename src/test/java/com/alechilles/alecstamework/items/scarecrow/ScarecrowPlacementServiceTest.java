package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScarecrowPlacementServiceTest {
    @Test
    void preservesDeployablePreviewPositionAndYawForPlacement() {
        Vector3d previewPosition = new Vector3d(10.75, 21.0, 30.25);

        ScarecrowPlacementService.Placement placement = ScarecrowPlacementService.plan(
                previewPosition,
                1.234f
        );

        assertEquals(previewPosition, placement.position());
        assertEquals(0.0f, placement.rotation().pitch(), 0.0001f);
        assertEquals(1.234f, placement.rotation().yaw(), 0.0001f);
        assertEquals(0.0f, placement.rotation().roll(), 0.0001f);
    }

    @Test
    void buildsPersistentCollectibleNativeSuppressorComponents() {
        ScarecrowPlacementService.Placement placement = ScarecrowPlacementService.plan(
                new Vector3d(1.5, 3.0, 3.5),
                0.75f
        );

        ScarecrowPlacementService.EntityComponents components =
                ScarecrowPlacementService.buildComponents(placement);

        assertEquals(ScarecrowIds.ITEM_ID, components.blockEntity().getBlockTypeKey());
        assertEquals(placement.position(), components.transform().getPosition());
        assertSame(PropComponent.get(), components.prop());
        assertEquals(
                ScarecrowIds.COLLECT_ROOT_INTERACTION_ID,
                components.interactions().getInteractionId(com.hypixel.hytale.protocol.InteractionType.Use)
        );
        assertEquals(ScarecrowIds.REMOVE_INTERACTION_HINT, components.interactions().getInteractionHint());
        assertEquals(ScarecrowIds.SUPPRESSION_ID, components.suppression().getSpawnSuppression());
        assertNotNull(components.uuid().getUuid());
    }
}
