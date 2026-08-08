package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkCollectScarecrowInteractionTest {
    @Test
    void acceptsScarecrowUsingInheritedVisualBlockKey() {
        assertTrue(TameworkCollectScarecrowInteraction.isScarecrow(
                new BlockEntity("Deco_Scarecrow"),
                new TransformComponent(),
                new SpawnSuppressionComponent(ScarecrowIds.SUPPRESSION_ID)
        ));
    }
}
