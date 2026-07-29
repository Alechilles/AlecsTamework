package com.alechilles.alecstamework.items;

import java.util.Map;

/**
 * Player-safe reasons for a refused capture attempt.
 *
 * <p>Policy codes remain an internal contract. Unknown and infrastructure
 * failures deliberately collapse to {@link #UNAVAILABLE} so they are never
 * shown to players.</p>
 */
public enum CaptureFeedbackReason {
    POWER_TOO_LOW,
    HEALTH_TOO_HIGH,
    REQUIRED_EFFECT_MISSING,
    TRANQUILIZATION_REQUIRED,
    COOLDOWN_ACTIVE,
    OUT_OF_RANGE,
    TARGET_INVALID,
    OWNER_DENIED,
    ROLE_DENIED,
    ROSTER_FULL,
    TOOL_REQUIRED,
    CHANCE_FAILED,
    UNAVAILABLE;

    private static final Map<String, CaptureFeedbackReason> POLICY_CODES = Map.ofEntries(
            Map.entry("capture-power-below-minimum", POWER_TOO_LOW),
            Map.entry("capture-health-too-high", HEALTH_TOO_HIGH),
            Map.entry("capture-required-effect-missing", REQUIRED_EFFECT_MISSING),
            Map.entry("capture-tranquilization-required", TRANQUILIZATION_REQUIRED),
            Map.entry("capture-cooldown-active", COOLDOWN_ACTIVE),
            Map.entry("capture-failure-cooldown-active", COOLDOWN_ACTIVE),
            Map.entry("capture-out-of-range", OUT_OF_RANGE),
            Map.entry("capture-target-invalid", TARGET_INVALID),
            Map.entry("capture-target-unavailable", TARGET_INVALID),
            Map.entry("capture-owner-denied", OWNER_DENIED),
            Map.entry("capture-role-denied", ROLE_DENIED),
            Map.entry("capture-roster-full", ROSTER_FULL),
            Map.entry("capture-tool-required", TOOL_REQUIRED),
            Map.entry("capture-zero-chance", CHANCE_FAILED),
            Map.entry("capture-probability-failure", CHANCE_FAILED),
            Map.entry("capture-chance-failed", CHANCE_FAILED)
    );

    /** Maps a known player-actionable policy code to a safe feedback reason. */
    public static CaptureFeedbackReason fromPolicyCode(String policyCode) {
        if (policyCode == null) return UNAVAILABLE;
        return POLICY_CODES.getOrDefault(policyCode.trim(), UNAVAILABLE);
    }
}
