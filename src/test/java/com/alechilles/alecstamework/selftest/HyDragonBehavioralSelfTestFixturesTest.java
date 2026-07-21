package com.alechilles.alecstamework.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HyDragonBehavioralSelfTestFixturesTest {
    @Test
    void isolatedReleaseGateExercisesEveryHyDragonTransactionBoundary() {
        List<ApiSelfTestAssertion> assertions = HyDragonBehavioralSelfTestFixtures.run();

        assertEquals(List.of(
                        "isolated guaranteed capture commits without entropy",
                        "isolated failed capture is immutable and duplicate-safe",
                        "isolated bonded vessel rejects stale generation",
                        "isolated population group rejects boundary overflow",
                        "isolated provisioning commits dormant profile",
                        "isolated provisioning projects active profile",
                        "isolated failed projection stays durable and recoverable",
                        "isolated restart reacquires lost active projection token"),
                assertions.stream().map(ApiSelfTestAssertion::name).toList());
        assertTrue(assertions.stream().allMatch(ApiSelfTestAssertion::passed),
                () -> assertions.stream()
                        .filter(assertion -> !assertion.passed())
                        .map(assertion -> assertion.name() + ": " + assertion.detail())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("unknown fixture failure"));
    }
}
