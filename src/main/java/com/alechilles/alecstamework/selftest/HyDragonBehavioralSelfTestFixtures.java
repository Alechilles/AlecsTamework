package com.alechilles.alecstamework.selftest;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic release-gate fixtures for HyDragon-facing transactional behavior.
 *
 * <p>Every fixture uses isolated in-memory ports and fixed identities. Running the suite never
 * reads or mutates live players, inventories, profiles, worlds, or the production database.</p>
 */
final class HyDragonBehavioralSelfTestFixtures {
    private HyDragonBehavioralSelfTestFixtures() {
    }

    static List<ApiSelfTestAssertion> run() {
        ArrayList<ApiSelfTestAssertion> assertions = new ArrayList<>();
        assertions.addAll(HyDragonCaptureSelfTestFixture.run());
        assertions.add(HyDragonBondedVesselSelfTestFixture.run());
        assertions.add(HyDragonPopulationGroupSelfTestFixture.run());
        assertions.addAll(HyDragonProvisioningSelfTestFixture.run());
        return List.copyOf(assertions);
    }
}
