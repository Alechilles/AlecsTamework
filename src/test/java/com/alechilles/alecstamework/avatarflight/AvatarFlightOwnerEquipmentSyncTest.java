package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AvatarFlightOwnerEquipmentSyncTest {
    @Test
    void localOwnerHideIsResentOnlyWhenHiddenOrSourceEquipmentChanges() {
        // Regression: the equipment-race fix removed the local-owner hide update entirely.
        AvatarFlightRiderVisualComponent visual = new AvatarFlightRiderVisualComponent();
        visual.setHiddenOwnerEquipmentSignature("hidden-a");
        visual.setHiddenOwnerSourceEquipmentSignature("source-a");

        assertFalse(AvatarFlightEquipmentVisualSystem.shouldQueueHiddenOwnerEquipmentToSelf(
                visual, "hidden-a", "source-a"));
        assertTrue(AvatarFlightEquipmentVisualSystem.shouldQueueHiddenOwnerEquipmentToSelf(
                visual, "hidden-a", "source-b"));
        assertTrue(AvatarFlightEquipmentVisualSystem.shouldQueueHiddenOwnerEquipmentToSelf(
                visual, "hidden-b", "source-a"));
    }
}
