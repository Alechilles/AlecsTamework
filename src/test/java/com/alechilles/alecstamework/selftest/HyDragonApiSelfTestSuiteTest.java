package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyDragonApiSelfTestSuiteTest {
    @Test
    void reportsEveryRestoredCapabilityIndependently() {
        List<ApiSelfTestAssertion> assertions =
                HyDragonApiSelfTestSuite.capabilityAssertions(
                        EnumSet.allOf(TameworkApiCapability.class)
                );

        assertEquals(10, assertions.size());
        assertTrue(assertions.stream().allMatch(ApiSelfTestAssertion::passed));
        assertTrue(assertions.stream().anyMatch(assertion ->
                assertion.name().contains("companion_provisioning")));
        assertTrue(assertions.stream().anyMatch(assertion ->
                assertion.name().contains("capture_tame_and_link")));
    }

    @Test
    void namesOnlyTheUnavailableCapabilityAsFailed() {
        EnumSet<TameworkApiCapability> available =
                EnumSet.allOf(TameworkApiCapability.class);
        available.remove(TameworkApiCapability.PAID_COMMAND_REVIVAL);

        List<ApiSelfTestAssertion> assertions =
                HyDragonApiSelfTestSuite.capabilityAssertions(available);

        assertEquals(1, assertions.stream()
                .filter(assertion -> !assertion.passed())
                .count());
        ApiSelfTestAssertion failure = assertions.stream()
                .filter(assertion -> !assertion.passed())
                .findFirst()
                .orElseThrow();
        assertFalse(failure.passed());
        assertTrue(failure.name().contains("paid_command_revival"));
    }
}
