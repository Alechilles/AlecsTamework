package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards event-to-ECS arrival ordering for cross-world companion travel. */
class CommandWorldChangeArrivalArchitectureTest {
    @Test
    void travelRunsWhenTheDestinationPlayerRefIsAddedWithoutATimer() throws Exception {
        String events = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandWorldChangeTravelEventHandler.java"
        ));
        String arrival = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandWorldChangeArrivalSystem.java"
        ));
        String plugin = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/Tamework.java"
        ));

        assertTrue(events.contains("arrivals.mark(playerUuid, world.getName())"));
        assertTrue(events.contains("arrivals.consume(playerUuid, world.getName())"));
        assertFalse(events.contains("delayedExecutor"));
        assertFalse(events.contains("WORLD_CHANGE_SETTLE_DELAY_MS"));
        assertTrue(arrival.contains("extends RefSystem<EntityStore>"));
        assertTrue(arrival.contains("travelEvents.onPlayerAdded(world, playerUuid)"));
        assertTrue(plugin.contains("new CommandWorldChangeArrivalSystem(commandWorldChangeTravelEventHandler)"));
    }

    @Test
    void queuedTravelRechecksTheLiveSourceCommandState() throws Exception {
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java"
        ));

        assertTrue(handler.contains("settings.getFollowMasterOnWorldChangeStateFilter()"));
    }
}
