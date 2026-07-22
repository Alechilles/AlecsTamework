package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.config.assets.TwPopulationGroupConfig;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationGroupPolicyTest {
    @Test
    void lifecycleCountingMatchesFixedContract() {
        for (CompanionLifecycleState state : CompanionLifecycleState.values()) {
            assertEquals(state != CompanionLifecycleState.RELEASED,
                    PopulationGroupLifecycleClassifier.consumesOwned(state), state.name());
            assertEquals(state == CompanionLifecycleState.ACTIVE
                            || state == CompanionLifecycleState.UNLOADED
                            || state == CompanionLifecycleState.RESTORING
                            || state == CompanionLifecycleState.STORING,
                    PopulationGroupLifecycleClassifier.consumesActive(state), state.name());
        }
    }

    @Test
    void unloadedToActiveIsZeroAndDormantProvisioningConsumesOnlyOwned() throws Exception {
        PopulationGroupIndex index = PopulationGroupIndex.compile(List.of(group()), 1L);
        PopulationGroupCountDeltaPlanner planner = new PopulationGroupCountDeltaPlanner(index);
        UUID owner = UUID.randomUUID();

        assertTrue(planner.plan(new PopulationGroupTransition(
                owner, "Mini", null, CompanionLifecycleState.UNLOADED,
                owner, "Mini", null, CompanionLifecycleState.ACTIVE)).isEmpty());
        Map<PopulationGroupBucket, PopulationGroupCountDelta> provisioned = planner.plan(
                new PopulationGroupTransition(null, null, null, null,
                        owner, "Mini", null, CompanionLifecycleState.PROVISIONED_DORMANT));
        assertEquals(new PopulationGroupCountDelta(1, 0), provisioned.values().iterator().next());
    }

    @Test
    void adminOverrideCannotBypassOwnedOrActiveCap() throws Exception {
        PopulationGroupIndex index = PopulationGroupIndex.compile(List.of(group()), 1L);
        PopulationGroupAdmissionPolicy policy = new PopulationGroupAdmissionPolicy(index);
        PopulationGroupBucket bucket = PopulationGroupBucket.of(
                UUID.randomUUID(), index.getDefinition("hydragon:soul").orElseThrow(), null);

        PopulationGroupAdmissionPolicy.Decision decision = policy.evaluate(
                Map.of(bucket, new PopulationGroupCounts(1, 0, 1, 0)),
                Map.of(bucket, new PopulationGroupCountDelta(1, 1)),
                PopulationAdmissionForcePolicy.ADMIN_OVERRIDE
        );

        assertFalse(decision.allowed());
        assertEquals(List.of("population-group-owned-limit", "population-group-active-limit"),
                decision.violations().stream().map(value -> value.reason()).toList());
    }

    @Test
    void perWorldGroupRequiresRetainedOwnershipWorld() throws Exception {
        TwPopulationGroupConfig config = group();
        set(field(config, "limits"), "scope", PopulationGroupScope.PER_WORLD);
        PopulationGroupIndex index = PopulationGroupIndex.compile(List.of(config), 1L);
        PopulationGroupCountDeltaPlanner planner = new PopulationGroupCountDeltaPlanner(index);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> planner.plan(
                new PopulationGroupTransition(null, null, null, null,
                        UUID.randomUUID(), "Mini", null, CompanionLifecycleState.PROVISIONED_DORMANT)));
    }

    private static TwPopulationGroupConfig group() throws Exception {
        var constructor = TwPopulationGroupConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwPopulationGroupConfig config = constructor.newInstance();
        set(config, "id", "Soul");
        set(config, "groupId", "hydragon:soul");
        set(config, "roleIds", new String[] { "Mini" });
        set(field(config, "limits"), "maxOwnedPerOwner", 1);
        set(field(config, "limits"), "maxActivePerOwner", 1);
        return config;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
