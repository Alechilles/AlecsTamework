package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Fail-closed result from a side-effect-free custom capture requirement. */
public record CaptureRequirementDecision(boolean allowed, @Nonnull String reason) {
    public CaptureRequirementDecision {
        reason = Objects.requireNonNull(reason, "reason").trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("Capture requirement reason is required.");
        }
    }

    public static CaptureRequirementDecision allow() {
        return new CaptureRequirementDecision(true, "allowed");
    }

    public static CaptureRequirementDecision deny(@Nonnull String reason) {
        return new CaptureRequirementDecision(false, reason);
    }
}
