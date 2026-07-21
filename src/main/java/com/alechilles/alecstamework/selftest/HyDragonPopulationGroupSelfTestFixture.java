package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupAdmissionPolicy;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupBucket;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCountDelta;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupCounts;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Isolated population-group limit fixture with no profile or player writes. */
final class HyDragonPopulationGroupSelfTestFixture {
    private static final UUID OWNER_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final String GROUP_ID = "hydragon:self-test-miniwyvern";

    private HyDragonPopulationGroupSelfTestFixture() {
    }

    static ApiSelfTestAssertion run() {
        try {
            PopulationGroupDefinitionView definition = new PopulationGroupDefinitionView(
                    "HyDragon_SelfTest_Miniwyvern",
                    1L,
                    GROUP_ID,
                    Set.of("HyDragon_SelfTest_Miniwyvern"),
                    1L,
                    1L,
                    PopulationGroupScope.GLOBAL);
            PopulationGroupIndex index = isolatedIndex(definition);
            PopulationGroupBucket bucket = PopulationGroupBucket.of(OWNER_ID, definition, null);
            PopulationGroupAdmissionPolicy.Decision decision = new PopulationGroupAdmissionPolicy(index).evaluate(
                    Map.of(bucket, new PopulationGroupCounts(1L, 0L, 1L, 0L)),
                    Map.of(bucket, new PopulationGroupCountDelta(1, 1)),
                    PopulationAdmissionForcePolicy.ADMIN_OVERRIDE);
            List<String> reasons = decision.violations().stream()
                    .map(PopulationGroupAdmissionPolicy.Violation::reason)
                    .toList();
            boolean passed = !decision.allowed()
                    && reasons.equals(List.of(
                    "population-group-owned-limit",
                    "population-group-active-limit"));
            return new ApiSelfTestAssertion(
                    "isolated population group rejects boundary overflow",
                    passed,
                    "allowed=" + decision.allowed() + " violations=" + reasons);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return new ApiSelfTestAssertion(
                    "isolated population group rejects boundary overflow",
                    false,
                    failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
        }
    }

    /** Builds the immutable production index directly so the fixture is independent of live assets. */
    private static PopulationGroupIndex isolatedIndex(PopulationGroupDefinitionView definition)
            throws ReflectiveOperationException {
        Constructor<PopulationGroupIndex> constructor = PopulationGroupIndex.class.getDeclaredConstructor(
                long.class, Map.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                1L,
                Map.of(definition.groupId(), definition),
                Map.of("HyDragon_SelfTest_Miniwyvern", List.of(definition)));
    }
}
