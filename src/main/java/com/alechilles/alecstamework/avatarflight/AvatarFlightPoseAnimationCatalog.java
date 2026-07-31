package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Catalog of generic avatar-flight pose animation ids and their mount-only AF_Origin rotation clips.
 */
public final class AvatarFlightPoseAnimationCatalog {
    private static final int[] PITCH_LEVELS = {15, 20, 30, 40};
    private static final int[] ROLL_LEVELS = {10, 20, 30};
    private static final String PATH_PREFIX = "NPC/Tamework/AvatarFlight/Animations/AF_Origin/";

    private AvatarFlightPoseAnimationCatalog() {
    }

    @Nonnull
    public static List<Definition> standardDefinitionsFor(
            @Nonnull TwAvatarFlightConfig.AnimationSettings animation) {
        if (!usesStandardPose(animation)) {
            return List.of();
        }
        List<Definition> definitions = new ArrayList<>();
        for (int pitch : PITCH_LEVELS) {
            definitions.add(new Definition(pitchId(true, pitch), pitchPath(true, pitch)));
            definitions.add(new Definition(pitchId(false, pitch), pitchPath(false, pitch)));
        }
        for (int roll : ROLL_LEVELS) {
            definitions.add(new Definition(rollId(true, roll), rollPath(true, roll)));
            definitions.add(new Definition(rollId(false, roll), rollPath(false, roll)));
        }
        for (int pitch : PITCH_LEVELS) {
            for (int roll : ROLL_LEVELS) {
                definitions.add(new Definition(combinedId(true, true, pitch, roll),
                        combinedPath(true, true, pitch, roll)));
                definitions.add(new Definition(combinedId(true, false, pitch, roll),
                        combinedPath(true, false, pitch, roll)));
                definitions.add(new Definition(combinedId(false, true, pitch, roll),
                        combinedPath(false, true, pitch, roll)));
                definitions.add(new Definition(combinedId(false, false, pitch, roll),
                        combinedPath(false, false, pitch, roll)));
            }
        }
        return definitions;
    }

    @Nonnull
    public static String pitchPoseAnimationFor(@Nonnull TwAvatarFlightConfig.AnimationSettings animation,
                                               double pitchDegrees) {
        if (!animation.isPoseAnimationsEnabled()) {
            return "";
        }
        if (pitchDegrees > animation.getPitchPoseThresholdDegrees()) {
            String configured = animation.getPitchUpPoseAnimation();
            return isPitchRoot(configured, true) ? pitchId(true, nearestPitchLevel(pitchDegrees)) : configured;
        }
        if (pitchDegrees < -animation.getPitchPoseThresholdDegrees()) {
            String configured = animation.getPitchDownPoseAnimation();
            return isPitchRoot(configured, false) ? pitchId(false, nearestPitchLevel(-pitchDegrees)) : configured;
        }
        return "";
    }

    @Nonnull
    public static String rollPoseAnimationFor(@Nonnull TwAvatarFlightConfig.AnimationSettings animation,
                                              double rollDegrees) {
        if (!animation.isPoseAnimationsEnabled()) {
            return "";
        }
        if (rollDegrees > animation.getRollPoseThresholdDegrees()) {
            String configured = animation.getBankRightPoseAnimation();
            return isRollRoot(configured, false) ? rollId(false, nearestRollLevel(rollDegrees)) : configured;
        }
        if (rollDegrees < -animation.getRollPoseThresholdDegrees()) {
            String configured = animation.getBankLeftPoseAnimation();
            return isRollRoot(configured, true) ? rollId(true, nearestRollLevel(-rollDegrees)) : configured;
        }
        return "";
    }

    @Nonnull
    public static String sharedPoseAnimationFor(@Nonnull TwAvatarFlightConfig.AnimationSettings animation,
                                                double pitchDegrees,
                                                double rollDegrees) {
        String pitchAnimation = pitchPoseAnimationFor(animation, pitchDegrees);
        String rollAnimation = rollPoseAnimationFor(animation, rollDegrees);
        if (!pitchAnimation.isBlank() && !rollAnimation.isBlank()) {
            String combinedAnimation = combinedPoseAnimationFor(animation, pitchDegrees, rollDegrees);
            if (!combinedAnimation.isBlank()) {
                return combinedAnimation;
            }
        }
        return !rollAnimation.isBlank() ? rollAnimation : pitchAnimation;
    }

    @Nonnull
    private static String combinedPoseAnimationFor(@Nonnull TwAvatarFlightConfig.AnimationSettings animation,
                                                   double pitchDegrees,
                                                   double rollDegrees) {
        boolean pitchUp = pitchDegrees > animation.getPitchPoseThresholdDegrees();
        boolean pitchDown = pitchDegrees < -animation.getPitchPoseThresholdDegrees();
        boolean bankRight = rollDegrees > animation.getRollPoseThresholdDegrees();
        boolean bankLeft = rollDegrees < -animation.getRollPoseThresholdDegrees();
        if (pitchUp && bankLeft) {
            String configured = animation.getPitchUpBankLeftPoseAnimation();
            return isCombinedRoot(configured, true, true)
                    ? combinedId(true, true, nearestPitchLevel(pitchDegrees), nearestRollLevel(-rollDegrees))
                    : configured;
        }
        if (pitchUp && bankRight) {
            String configured = animation.getPitchUpBankRightPoseAnimation();
            return isCombinedRoot(configured, true, false)
                    ? combinedId(true, false, nearestPitchLevel(pitchDegrees), nearestRollLevel(rollDegrees))
                    : configured;
        }
        if (pitchDown && bankLeft) {
            String configured = animation.getPitchDownBankLeftPoseAnimation();
            return isCombinedRoot(configured, false, true)
                    ? combinedId(false, true, nearestPitchLevel(-pitchDegrees), nearestRollLevel(-rollDegrees))
                    : configured;
        }
        if (pitchDown && bankRight) {
            String configured = animation.getPitchDownBankRightPoseAnimation();
            return isCombinedRoot(configured, false, false)
                    ? combinedId(false, false, nearestPitchLevel(-pitchDegrees), nearestRollLevel(rollDegrees))
                    : configured;
        }
        return "";
    }

    private static boolean usesStandardPose(@Nonnull TwAvatarFlightConfig.AnimationSettings animation) {
        return isPitchRoot(animation.getPitchUpPoseAnimation(), true)
                || isPitchRoot(animation.getPitchDownPoseAnimation(), false)
                || isRollRoot(animation.getBankLeftPoseAnimation(), true)
                || isRollRoot(animation.getBankRightPoseAnimation(), false)
                || isCombinedRoot(animation.getPitchUpBankLeftPoseAnimation(), true, true)
                || isCombinedRoot(animation.getPitchUpBankRightPoseAnimation(), true, false)
                || isCombinedRoot(animation.getPitchDownBankLeftPoseAnimation(), false, true)
                || isCombinedRoot(animation.getPitchDownBankRightPoseAnimation(), false, false);
    }

    private static boolean isPitchRoot(@Nullable String value, boolean up) {
        return (up ? "TameworkPitchUp" : "TameworkPitchDown").equals(value);
    }

    private static boolean isRollRoot(@Nullable String value, boolean left) {
        return (left ? "TameworkBankLeft" : "TameworkBankRight").equals(value);
    }

    private static boolean isCombinedRoot(@Nullable String value, boolean up, boolean left) {
        String pitch = up ? "PitchUp" : "PitchDown";
        String bank = left ? "BankLeft" : "BankRight";
        return ("Tamework" + pitch + bank).equals(value);
    }

    private static int nearestPitchLevel(double degrees) {
        return nearestLevel(Math.abs(degrees), PITCH_LEVELS);
    }

    private static int nearestRollLevel(double degrees) {
        return nearestLevel(Math.abs(degrees), ROLL_LEVELS);
    }

    private static int nearestLevel(double degrees, int[] levels) {
        int nearest = levels[0];
        double distance = Math.abs(degrees - nearest);
        for (int i = 1; i < levels.length; i++) {
            double nextDistance = Math.abs(degrees - levels[i]);
            if (nextDistance <= distance) {
                nearest = levels[i];
                distance = nextDistance;
            }
        }
        return nearest;
    }

    @Nonnull
    private static String pitchId(boolean up, int degrees) {
        if (degrees == 20) {
            return up ? "TameworkPitchUp" : "TameworkPitchDown";
        }
        return (up ? "TameworkPitchUp" : "TameworkPitchDown") + degrees;
    }

    @Nonnull
    private static String rollId(boolean left, int degrees) {
        if (degrees == 20) {
            return left ? "TameworkBankLeft" : "TameworkBankRight";
        }
        return (left ? "TameworkBankLeft" : "TameworkBankRight") + degrees;
    }

    @Nonnull
    private static String combinedId(boolean up, boolean left, int pitchDegrees, int rollDegrees) {
        if (pitchDegrees == 20 && rollDegrees == 20) {
            return "Tamework" + (up ? "PitchUp" : "PitchDown") + (left ? "BankLeft" : "BankRight");
        }
        return "Tamework"
                + (up ? "PitchUp" : "PitchDown") + pitchDegrees
                + (left ? "BankLeft" : "BankRight") + rollDegrees;
    }

    @Nonnull
    private static String pitchPath(boolean up, int degrees) {
        return PATH_PREFIX + (up ? "Pitch_Up_" : "Pitch_Down_") + degrees + ".blockyanim";
    }

    @Nonnull
    private static String rollPath(boolean left, int degrees) {
        return PATH_PREFIX + (left ? "Bank_Left_" : "Bank_Right_") + degrees + ".blockyanim";
    }

    @Nonnull
    private static String combinedPath(boolean up, boolean left, int pitchDegrees, int rollDegrees) {
        return PATH_PREFIX
                + (up ? "Pitch_Up_" : "Pitch_Down_") + pitchDegrees
                + (left ? "_Bank_Left_" : "_Bank_Right_") + rollDegrees
                + ".blockyanim";
    }

    public record Definition(@Nonnull String id, @Nonnull String path) {
    }
}
