package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwPopulationGroupConfigTest {
    @Test
    void nestedLimitsInheritAndRoleArrayReplaces() throws Exception {
        TwPopulationGroupConfig parent = group("Parent", "hydragon:full", 1, "Dragon", "Hydra");
        TwPopulationGroupConfig child = group("Child", "hydragon:full", 2, "Mini");
        set(field(parent, "limits"), "maxOwnedPerOwner", 4);
        set(field(parent, "limits"), "maxActivePerOwner", 1);
        set(field(parent, "limits"), "scope", PopulationGroupScope.PER_WORLD);
        set(field(child, "limits"), "maxOwnedPerOwner", 2);

        child.inheritMissingTopLevelFrom(parent, Set.of("RoleIds", "Limits"),
                Map.of("Limits", Set.of("MaxOwnedPerOwner")));

        assertEquals(Set.of("Mini"), child.toView(3L).roleIds());
        assertEquals(2, child.getLimits().getMaxOwnedPerOwner());
        assertEquals(1, child.getLimits().getMaxActivePerOwner());
        assertEquals(PopulationGroupScope.PER_WORLD, child.getLimits().getScope());
    }

    @Test
    void duplicateGroupWinnerAndMultiGroupMembershipAreDeterministic() throws Exception {
        TwPopulationGroupConfig low = group("zeta", "hydragon:full", 1, "Dragon");
        TwPopulationGroupConfig winner = group("Alpha", "hydragon:full", 2, "Dragon");
        TwPopulationGroupConfig second = group("Soul", "hydragon:soul", 1, "Dragon");

        PopulationGroupIndex index = PopulationGroupIndex.compile(List.of(low, second, winner), 6L);

        assertEquals("Alpha", index.getDefinition("hydragon:full").orElseThrow().configId());
        assertEquals(List.of("hydragon:full", "hydragon:soul"),
                index.resolveForRole("Dragon").stream().map(value -> value.groupId()).toList());
    }

    @Test
    void invalidUpdateRetainsLastValidIndexAndRejectsDuplicates() throws Exception {
        PopulationGroupRegistry registry = new PopulationGroupRegistry();
        assertTrue(registry.replace(List.of(group("valid", "hydragon:full", 0, "Dragon")), 1L).applied());
        TwPopulationGroupConfig invalid = group("bad", "not-namespaced", 0, "Dragon", "Dragon");
        assertThrows(IllegalArgumentException.class, invalid::validateOrThrow);

        PopulationGroupRegistry.ReloadResult result = registry.replace(List.of(invalid), 2L);
        assertFalse(result.applied());
        assertEquals(1L, result.active().revision());
    }

    private static TwPopulationGroupConfig group(
            String id, String groupId, int priority, String... roles
    ) throws Exception {
        TwPopulationGroupConfig config = new TwPopulationGroupConfig();
        set(config, "id", id);
        set(config, "groupId", groupId);
        set(config, "priority", priority);
        set(config, "roleIds", roles);
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
