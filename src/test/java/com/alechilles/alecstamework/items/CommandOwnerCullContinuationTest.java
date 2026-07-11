package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for cull callbacks that previously mutated state before admission. */
class CommandOwnerCullContinuationTest {
    @Test
    void oneFailedAppliedEffectDoesNotSkipFatalDamageOrLaterEffects() {
        List<String> events = new ArrayList<>();
        CommandOwnerCullContinuation continuation = new CommandOwnerCullContinuation(
                (action, failure) -> events.add("failed:" + action)
        );

        assertDoesNotThrow(() -> continuation.run(
                new CommandOwnerCullContinuation.Step("clear-links", () -> {
                    events.add("clear-links");
                    throw new IllegalStateException("fixture failure");
                }),
                new CommandOwnerCullContinuation.Step("fatal-damage", () -> events.add("fatal-damage")),
                new CommandOwnerCullContinuation.Step("feedback", () -> events.add("feedback"))
        ));

        assertEquals(
                List.of("clear-links", "failed:clear-links", "fatal-damage", "feedback"),
                events
        );
    }

    @Test
    void failedDiagnosticSinkCannotAbortRemainingAppliedEffects() {
        List<String> events = new ArrayList<>();
        CommandOwnerCullContinuation continuation = new CommandOwnerCullContinuation(
                (action, failure) -> {
                    throw new IllegalStateException("diagnostic failure");
                }
        );

        assertDoesNotThrow(() -> continuation.run(
                new CommandOwnerCullContinuation.Step("first", () -> {
                    throw new IllegalStateException("fixture failure");
                }),
                new CommandOwnerCullContinuation.Step("fatal-damage", () -> events.add("fatal-damage"))
        ));

        assertEquals(List.of("fatal-damage"), events);
    }
}
