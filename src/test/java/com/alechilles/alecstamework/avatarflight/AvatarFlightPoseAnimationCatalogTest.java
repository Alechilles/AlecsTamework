package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvatarFlightPoseAnimationCatalogTest {

    @Test
    void standardRootIdsSelectNearestPitchAndRollBreakpoints() throws Exception {
        TwAvatarFlightConfig.AnimationSettings animation = standardAnimation();

        assertEquals("TameworkPitchUp15",
                AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor(animation, 14.0));
        assertEquals("TameworkPitchUp30",
                AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor(animation, 29.0));
        assertEquals("TameworkPitchUp40",
                AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor(animation, 38.0));
        assertEquals("TameworkPitchDown40",
                AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor(animation, -80.0));
        assertEquals("TameworkBankLeft10",
                AvatarFlightPoseAnimationCatalog.rollPoseAnimationFor(animation, -9.0));
        assertEquals("TameworkBankRight30",
                AvatarFlightPoseAnimationCatalog.rollPoseAnimationFor(animation, 28.0));
    }

    @Test
    void standardCombinedRootIdsSelectPitchAndRollGrid() throws Exception {
        TwAvatarFlightConfig.AnimationSettings animation = standardAnimation();

        assertEquals("TameworkPitchUp40BankLeft30",
                AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor(animation, 39.0, -28.0));
        assertEquals("TameworkPitchDown15BankRight10",
                AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor(animation, -12.0, 8.0));
        assertEquals("TameworkPitchUpBankRight",
                AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor(animation, 19.0, 19.0));
    }

    @Test
    void customAnimationIdsKeepSingleConfiguredSelection() throws Exception {
        TwAvatarFlightConfig.AnimationSettings animation = standardAnimation();
        setField(animation, "pitchUpPoseAnimation", "CustomPitchUp");
        setField(animation, "bankLeftPoseAnimation", "CustomBankLeft");
        setField(animation, "pitchUpBankLeftPoseAnimation", "CustomCombined");

        assertEquals("CustomPitchUp",
                AvatarFlightPoseAnimationCatalog.pitchPoseAnimationFor(animation, 40.0));
        assertEquals("CustomBankLeft",
                AvatarFlightPoseAnimationCatalog.rollPoseAnimationFor(animation, -30.0));
        assertEquals("CustomCombined",
                AvatarFlightPoseAnimationCatalog.sharedPoseAnimationFor(animation, 40.0, -30.0));
    }

    @Test
    void standardDefinitionsIncludeExpandedPitchRollGrid() throws Exception {
        List<AvatarFlightPoseAnimationCatalog.Definition> definitions =
                AvatarFlightPoseAnimationCatalog.standardDefinitionsFor(standardAnimation());

        assertTrue(definitions.stream().anyMatch(definition -> "TameworkPitchUp40".equals(definition.id())));
        assertTrue(definitions.stream().anyMatch(definition -> "TameworkBankRight30".equals(definition.id())));
        assertTrue(definitions.stream().anyMatch(definition ->
                "TameworkPitchDown40BankLeft30".equals(definition.id())));
        assertTrue(definitions.stream().allMatch(definition ->
                definition.path().contains("/Animations/AF_Origin/")));
    }

    @Test
    void standardPoseClipsCannotRotateTheFakePlayersStandardOrigin() throws Exception {
        Path poseRoot = Path.of(
                "src", "main", "resources", "Common", "NPC", "Tamework",
                "AvatarFlight", "Animations", "AF_Origin");
        List<Path> clips;
        try (var files = Files.list(poseRoot)) {
            clips = files.filter(path -> path.toString().endsWith(".blockyanim")).toList();
        }

        assertFalse(clips.isEmpty());
        for (Path clip : clips) {
            String json = Files.readString(clip);
            assertTrue(json.contains("\"AF_Origin\""), clip.toString());
            assertFalse(json.contains("\"Origin\":"), clip.toString());
        }
    }

    private static TwAvatarFlightConfig.AnimationSettings standardAnimation() throws Exception {
        TwAvatarFlightConfig.AnimationSettings animation = TwAvatarFlightConfig.defaultConfig().getAnimation();
        setField(animation, "poseAnimationsEnabled", true);
        setField(animation, "pitchPoseThresholdDegrees", 5.0);
        setField(animation, "rollPoseThresholdDegrees", 5.0);
        setField(animation, "pitchUpPoseAnimation", "TameworkPitchUp");
        setField(animation, "pitchDownPoseAnimation", "TameworkPitchDown");
        setField(animation, "bankLeftPoseAnimation", "TameworkBankLeft");
        setField(animation, "bankRightPoseAnimation", "TameworkBankRight");
        setField(animation, "pitchUpBankLeftPoseAnimation", "TameworkPitchUpBankLeft");
        setField(animation, "pitchUpBankRightPoseAnimation", "TameworkPitchUpBankRight");
        setField(animation, "pitchDownBankLeftPoseAnimation", "TameworkPitchDownBankLeft");
        setField(animation, "pitchDownBankRightPoseAnimation", "TameworkPitchDownBankRight");
        return animation;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
