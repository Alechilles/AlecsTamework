package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * Fixture-free live checks for every public capability required by HyDragon.
 */
final class HyDragonApiSelfTestSuite {
    private static final List<TameworkApiCapability> REQUIRED = List.of(
            TameworkApiCapability.CAPTURE_POLICY,
            TameworkApiCapability.PROFILE_DATA_TRANSACTIONS,
            TameworkApiCapability.PERSISTENCE_RESILIENCE,
            TameworkApiCapability.POPULATION_GROUPS,
            TameworkApiCapability.COMPANION_PROVISIONING,
            TameworkApiCapability.COMMAND_TIMED_SUMMONING,
            TameworkApiCapability.PAID_COMMAND_REVIVAL,
            TameworkApiCapability.COMMAND_FAMILY_ROSTERS,
            TameworkApiCapability.CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION,
            TameworkApiCapability.CAPTURE_TAME_AND_LINK
    );

    private HyDragonApiSelfTestSuite() {
    }

    @Nonnull
    static ApiSelfTestSuiteResult run(
            @Nonnull ApiSelfTestContext context,
            @Nonnull String captureFixtureItemId
    ) {
        TameworkApi api = context.api();
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>(
                capabilityAssertions(api.getCapabilities())
        );
        boolean fixtureReady = api.configs()
                .resolveSpawnerCaptureMechanicsForItemId(captureFixtureItemId)
                .isPresent();
        assertions.add(new ApiSelfTestAssertion(
                "capture mechanics fixture resolves",
                fixtureReady,
                "item=" + captureFixtureItemId
        ));
        return new ApiSelfTestSuiteResult(
                "hydragon-integrations",
                assertions
        );
    }

    @Nonnull
    static List<ApiSelfTestAssertion> capabilityAssertions(
            @Nonnull EnumSet<TameworkApiCapability> available
    ) {
        ArrayList<ApiSelfTestAssertion> assertions =
                new ArrayList<>(REQUIRED.size());
        for (TameworkApiCapability capability : REQUIRED) {
            assertions.add(new ApiSelfTestAssertion(
                    capability.name().toLowerCase(Locale.ROOT)
                            + " capability ready",
                    available.contains(capability),
                    "capability=" + capability
            ));
        }
        return List.copyOf(assertions);
    }
}
