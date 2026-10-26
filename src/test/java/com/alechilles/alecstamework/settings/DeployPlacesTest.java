package com.alechilles.alecstamework.settings;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployPlacesTest {
    @Test
    void aPlaceIsAWorldAServerOrAServerWorld() {
        DeployPlaces except = new DeployPlaces(DeployPlaces.Mode.EVERYWHERE_EXCEPT,
                DeployPlaces.parsePlaces("NA01-Hub, arena, NA02-Survival/nether"));

        assertFalse(except.allows("na01-hub", "default"), "a server id blocks all of its worlds, any case");
        assertFalse(except.allows("NA02-Survival", "Arena"), "a world name blocks that world on every server");
        assertFalse(except.allows("NA02-Survival", "nether"), "server/world blocks one world of one server");
        assertTrue(except.allows("NA03-Survival", "nether"), "the same world on another server is fine");
        assertTrue(except.allows("NA02-Survival", "default"));
        assertTrue(except.allows(null, "default"), "without a network store, server ids never match");
        assertTrue(except.allows(null, "nether"));
    }

    @Test
    void onlyListedAllowsNothingElseAndAnEmptyExceptListAllowsEverything() {
        DeployPlaces only = new DeployPlaces(DeployPlaces.Mode.ONLY_LISTED, List.of("NA01-Survival", "NA02-Survival"));

        assertTrue(only.allows("NA02-Survival", "default"));
        assertFalse(only.allows("NA01-Hub", "default"));
        assertFalse(only.allows(null, "default"), "only listed, and this server is not named");
        assertTrue(DeployPlaces.DEFAULT.allows("NA01-Hub", "default"));
        assertTrue(DeployPlaces.DEFAULT.allows(null, null));
    }
}
