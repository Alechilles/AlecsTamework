package com.alechilles.alecstamework.selftest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HyDragonBehavioralSelfTestFixturesTest {
    @Test
    void isolatedReleaseGateExercisesEveryHyDragonTransactionBoundary() {
        List<ApiSelfTestAssertion> assertions = HyDragonBehavioralSelfTestFixtures.run();

        List<String> names = assertions.stream().map(ApiSelfTestAssertion::name).toList();
        assertTrue(names.containsAll(List.of(
                        "isolated guaranteed capture commits without entropy",
                        "isolated failed capture is immutable and duplicate-safe",
                        "isolated capture restart quarantines ambiguous apply and reuses its outcome",
                        "isolated capture restart cancels an expired prepared checkpoint",
                        "isolated bonded vessel rejects stale generation",
                        "isolated bonded vessel bind recovers one generation-one authority",
                        "isolated bonded vessel summons and stores through one journal",
                        "isolated bonded vessel restart closes applied transition without reapplying",
                        "isolated bonded vessel death is duplicate-safe with offline item",
                        "isolated bonded vessel lost transition preserves exact item evidence",
                        "isolated population group rejects boundary overflow",
                        "isolated population reservations serialize and cancel exactly once",
                        "isolated population role change is evaluated all-or-none",
                        "isolated unavailable population config fails closed without reservation",
                        "isolated provisioning commits dormant profile",
                        "isolated provisioning projects active profile",
                        "isolated failed projection stays durable and recoverable",
                        "isolated restart reacquires lost active projection token")),
                () -> "Missing behavioral fixture assertions: " + names);
        assertTrue(names.size() == Set.copyOf(names).size(),
                () -> "Behavioral assertion names must remain unique: " + names);
        assertTrue(assertions.stream().allMatch(ApiSelfTestAssertion::passed),
                () -> assertions.stream()
                        .filter(assertion -> !assertion.passed())
                        .map(assertion -> assertion.name() + ": " + assertion.detail())
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("unknown fixture failure"));
    }
}
