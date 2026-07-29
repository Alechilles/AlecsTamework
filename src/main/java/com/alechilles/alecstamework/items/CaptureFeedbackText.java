package com.alechilles.alecstamework.items;

import java.util.Objects;

/** Builds localized, player-safe text payloads for denied capture attempts. */
public final class CaptureFeedbackText {
    private static final String PREFIX = "tamework.ui.notifications.capture.";

    private CaptureFeedbackText() {
    }

    /** Produces the localization key and only the parameters required by its message. */
    public static Text denial(CaptureFeedbackReason reason, Context context) {
        CaptureFeedbackReason resolvedReason = reason == null
                ? CaptureFeedbackReason.UNAVAILABLE : reason;
        Context resolvedContext = context == null ? Context.empty() : context;
        return switch (resolvedReason) {
            case POWER_TOO_LOW -> text("powerTooLow", resolvedContext.sourceName(),
                    resolvedContext.targetName(), resolvedContext.requiredPower());
            case HEALTH_TOO_HIGH -> text("healthTooHigh", resolvedContext.targetName());
            case REQUIRED_EFFECT_MISSING -> text("effectRequired", resolvedContext.sourceName(),
                    resolvedContext.targetName(), resolvedContext.requiredEffect());
            case TRANQUILIZATION_REQUIRED -> text("tranquilizedRequired", resolvedContext.sourceName(),
                    resolvedContext.targetName());
            case COOLDOWN_ACTIVE -> text("cooldownActive", resolvedContext.sourceName());
            case OUT_OF_RANGE -> text("outOfRange", resolvedContext.targetName());
            case TARGET_INVALID -> text("targetInvalid", resolvedContext.targetName());
            case OWNER_DENIED -> text("ownerDenied", resolvedContext.targetName());
            case ROLE_DENIED -> text("roleDenied", resolvedContext.targetName());
            case ROSTER_FULL -> text("rosterFull");
            case TOOL_REQUIRED -> text("toolRequired", resolvedContext.sourceName());
            case CHANCE_FAILED -> text("chanceFailed", resolvedContext.sourceName(),
                    resolvedContext.targetName());
            case UNAVAILABLE -> text("unavailable");
        };
    }

    private static Text text(String suffix, Object... arguments) {
        return new Text(PREFIX + suffix, arguments);
    }

    /** Immutable display data collected at the capture boundary. */
    public record Context(String sourceName, String targetName, int capturePower,
                          int requiredPower, String requiredEffect) {
        public Context {
            sourceName = safeLabel(sourceName, "capture item");
            targetName = safeLabel(targetName, "target");
            requiredEffect = safeLabel(requiredEffect, "required effect");
        }

        private static Context empty() {
            return new Context(null, null, 0, 0, null);
        }

        private static String safeLabel(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /** Localized message key with a defensive copy of its formatting arguments. */
    public record Text(String key, Object[] arguments) {
        public Text {
            key = Objects.requireNonNull(key, "key");
            arguments = arguments == null ? new Object[0] : arguments.clone();
        }

        @Override
        public Object[] arguments() {
            return arguments.clone();
        }
    }
}
