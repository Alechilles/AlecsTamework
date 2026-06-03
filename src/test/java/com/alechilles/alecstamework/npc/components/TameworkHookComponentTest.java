package com.alechilles.alecstamework.npc.components;

import org.joml.Vector3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests Tamework hook component payload behavior for optional target positions. */
class TameworkHookComponentTest {

    @Test
    void targetPositionIsStoredWhenProvided() {
        Vector3d target = new Vector3d(10.5, 65.0, -3.25);
        TameworkHookComponent component = new TameworkHookComponent(
                "Tamework.Command.MoveToPosition.RaycastHit",
                UUID.randomUUID(),
                "Tester",
                "Tamework_Command_Whistle_Example",
                System.currentTimeMillis(),
                true,
                target
        );

        assertTrue(component.hasTargetPosition());
        Vector3d saved = component.getTargetPosition();
        assertNotNull(saved);
        assertEquals(target.x, saved.x, 0.0001);
        assertEquals(target.y, saved.y, 0.0001);
        assertEquals(target.z, saved.z, 0.0001);
    }

    @Test
    void targetPositionClearsWhenNull() {
        TameworkHookComponent component = new TameworkHookComponent(
                "Tamework.Command.MoveToPosition.StoredHome",
                UUID.randomUUID(),
                "Tester",
                "Tamework_Command_Whistle_Example",
                System.currentTimeMillis(),
                true,
                new Vector3d(1.0, 2.0, 3.0)
        );

        component.setTargetPosition(null);

        assertFalse(component.hasTargetPosition());
        assertEquals(0.0, component.getTargetX(), 0.0);
        assertEquals(0.0, component.getTargetY(), 0.0);
        assertEquals(0.0, component.getTargetZ(), 0.0);
    }
}
