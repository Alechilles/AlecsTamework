package com.alechilles.alecstamework.config.assets;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers defaults and nested parent fallback for avatar-flight trail settings. */
class AvatarFlightTrailSettingsTest {

    @Test
    void defaultsAreInertAndUseFastGlideHysteresis() {
        AvatarFlightTrailSettings settings = new AvatarFlightTrailSettings();

        assertTrue(settings.isEnabled());
        assertEquals("", settings.getLaunchRootInteraction());
        assertEquals("", settings.getFlapRootInteraction());
        assertEquals("", settings.getBoostRootInteraction());
        assertEquals("", settings.getFastGlideRootInteraction());
        assertEquals(0.92, settings.getFastGlideStartSpeedRatio(), 0.00001);
        assertEquals(0.86, settings.getFastGlideStopSpeedRatio(), 0.00001);
    }

    @Test
    void explicitNestedRootWinsAndMissingTrailKeysInherit() {
        AvatarFlightTrailSettings parent = new AvatarFlightTrailSettings();
        parent.launchRootInteraction = "Parent_Launch";
        parent.flapRootInteraction = "Parent_Flap";
        parent.fastGlideRootInteraction = "Parent_Glide";
        parent.fastGlideStartSpeedRatio = 0.95;
        parent.fastGlideStopSpeedRatio = 0.8;
        AvatarFlightTrailSettings child = new AvatarFlightTrailSettings();
        child.flapRootInteraction = "";

        child.inheritMissingFrom(parent, Set.of("FlapRootInteraction"));

        assertEquals("Parent_Launch", child.getLaunchRootInteraction());
        assertEquals("", child.getFlapRootInteraction());
        assertEquals("Parent_Glide", child.getFastGlideRootInteraction());
        assertEquals(0.95, child.getFastGlideStartSpeedRatio(), 0.00001);
        assertEquals(0.8, child.getFastGlideStopSpeedRatio(), 0.00001);
    }

    @Test
    void avatarConfigTracksTopLevelAndNestedTrailOverrides() {
        TwAvatarFlightConfig parent = new TwAvatarFlightConfig();
        parent.trails.launchRootInteraction = "Parent_Launch";
        parent.trails.fastGlideRootInteraction = "Parent_Glide";
        parent.trails.fastGlideStartSpeedRatio = 0.95;
        TwAvatarFlightConfig child = new TwAvatarFlightConfig();
        child.trails.launchRootInteraction = "Child_Launch";

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Trails"),
                Map.of("Trails", Set.of("LaunchRootInteraction"))
        );

        assertEquals("Child_Launch", child.getTrails().getLaunchRootInteraction());
        assertEquals("Parent_Glide", child.getTrails().getFastGlideRootInteraction());
        assertEquals(0.95, child.getTrails().getFastGlideStartSpeedRatio(), 0.00001);
    }

    @Test
    void omittedTrailSectionInheritsCompleteParentSection() {
        TwAvatarFlightConfig parent = new TwAvatarFlightConfig();
        parent.trails.launchRootInteraction = "Parent_Launch";
        parent.trails.fastGlideRootInteraction = "Parent_Glide";
        parent.trails.fastGlideStartSpeedRatio = 0.97;
        TwAvatarFlightConfig child = new TwAvatarFlightConfig();

        child.inheritMissingTopLevelFrom(parent, Set.of());

        assertEquals("Parent_Launch", child.getTrails().getLaunchRootInteraction());
        assertEquals("Parent_Glide", child.getTrails().getFastGlideRootInteraction());
        assertEquals(0.97, child.getTrails().getFastGlideStartSpeedRatio(), 0.00001);
    }

    @Test
    void stopRatioCannotExceedStartRatio() {
        AvatarFlightTrailSettings settings = new AvatarFlightTrailSettings();
        settings.fastGlideStartSpeedRatio = 0.7;
        settings.fastGlideStopSpeedRatio = 0.9;

        assertEquals(0.7, settings.getFastGlideStopSpeedRatio(), 0.00001);
    }
}
