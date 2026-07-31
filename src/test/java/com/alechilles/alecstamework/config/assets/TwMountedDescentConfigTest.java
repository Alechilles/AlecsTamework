package com.alechilles.alecstamework.config.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.movement.NativeMountedDescentPhysics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TwMountedDescentConfigTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void resolvesProfileIdsCaseInsensitively() {
        TwMountedDescentConfig config = config("animal-husbandry", Map.of(
                "AH_Mount_Tetrabird", new NativeMountedDescentPhysics.Settings(4.5, 0.55)
        ));

        Optional<NativeMountedDescentPhysics.Settings> resolved =
                TwMountedDescentConfig.resolveForMovementConfigIdForTest(List.of(config), " ah_mount_tetrabird ");

        assertTrue(resolved.isPresent());
        assertEquals(4.5, resolved.orElseThrow().maxDownwardSpeed(), EPSILON);
        assertEquals(0.55, resolved.orElseThrow().fallAccelerationMultiplier(), EPSILON);
    }

    @Test
    void skipsInvalidProfilesAndReturnsNeutralWhenNoValidProfileMatches() {
        TwMountedDescentConfig config = config("animal-husbandry", Map.of(
                "AH_Mount_Tetrabird", new NativeMountedDescentPhysics.Settings(0.0, 0.55)
        ));

        assertTrue(TwMountedDescentConfig.resolveForMovementConfigIdForTest(
                List.of(config), "AH_Mount_Trillodon").isEmpty());
        assertTrue(TwMountedDescentConfig.resolveForMovementConfigIdForTest(
                List.of(config), "AH_Mount_Tetrabird").isEmpty());
    }

    @Test
    void selectsTheLexicallyFirstConfigWhenAssetsDefineTheSameProfile() {
        TwMountedDescentConfig first = config("a-profile", Map.of(
                "AH_Mount_Tetrabird", new NativeMountedDescentPhysics.Settings(4.5, 0.55)
        ));
        TwMountedDescentConfig later = config("z-profile", Map.of(
                "AH_Mount_Tetrabird", new NativeMountedDescentPhysics.Settings(8.0, 0.75)
        ));

        Optional<NativeMountedDescentPhysics.Settings> resolved =
                TwMountedDescentConfig.resolveForMovementConfigIdForTest(List.of(later, first), "AH_Mount_Tetrabird");

        assertTrue(resolved.isPresent());
        assertEquals(4.5, resolved.orElseThrow().maxDownwardSpeed(), EPSILON);
        assertFalse(resolved.orElseThrow().maxDownwardSpeed() == 8.0);
    }

    private static TwMountedDescentConfig config(
            String id,
            Map<String, NativeMountedDescentPhysics.Settings> profiles) {
        TwMountedDescentConfig config = new TwMountedDescentConfig();
        config.setId(id);
        config.setProfiles(profiles);
        return config;
    }
}
