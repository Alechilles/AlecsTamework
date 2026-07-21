package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.PopulationGroupScope;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupIndex;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwPopulationGroupConfigTest {
    @Test
    void codecDecodesUnlimitedLimitsAndCanonicalScope() throws Exception {
        TwPopulationGroupConfig config = decode("HydragonFullDragons", """
                {
                  "Enabled": true,
                  "Priority": 100,
                  "GroupId": "hydragon:full_dragons",
                  "RoleIds": ["NordicDrake_Tamed", "Hydra_Tamed"],
                  "Limits": {
                    "MaxOwnedPerOwner": 0,
                    "MaxActivePerOwner": 1,
                    "Scope": "Global"
                  }
                }
                """);

        config.validateOrThrow();

        assertTrue(config.isEnabled());
        assertEquals(100, config.getPriority());
        assertEquals("hydragon:full_dragons", config.getGroupId());
        assertArrayEquals(new String[] { "NordicDrake_Tamed", "Hydra_Tamed" }, config.getRoleIds());
        assertEquals(0, config.getLimits().getMaxOwnedPerOwner());
        assertEquals(1, config.getLimits().getMaxActivePerOwner());
        assertEquals(PopulationGroupScope.GLOBAL, config.getLimits().getScope());
    }

    @Test
    void codecDecodesPerWorldAndRejectsInvalidScopeOrNegativeLimit() throws Exception {
        TwPopulationGroupConfig perWorld = decode("PerWorldDragons", """
                {
                  "GroupId": "hydragon:world_dragons",
                  "RoleIds": ["Hydra_Tamed"],
                  "Limits": {
                    "MaxOwnedPerOwner": 2,
                    "MaxActivePerOwner": 0,
                    "Scope": "PerWorld"
                  }
                }
                """);
        TwPopulationGroupConfig negative = decode("NegativeDragons", """
                {
                  "GroupId": "hydragon:negative_dragons",
                  "RoleIds": ["Hydra_Tamed"],
                  "Limits": { "MaxOwnedPerOwner": -1 }
                }
                """);

        perWorld.validateOrThrow();
        assertEquals(PopulationGroupScope.PER_WORLD, perWorld.getLimits().getScope());
        assertEquals(0, perWorld.getLimits().getMaxActivePerOwner());
        assertThrows(IllegalArgumentException.class, negative::validateOrThrow);
        assertThrows(IllegalArgumentException.class, () -> TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse("{\"GroupId\":\"hydragon:bad\",\"RoleIds\":[\"Hydra_Tamed\"],\"Limits\":{\"Scope\":\"Everywhere\"}}"),
                new ExtraInfo()
        ));
    }

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

    private static TwPopulationGroupConfig decode(String id, String json) throws Exception {
        TwPopulationGroupConfig config = TwPopulationGroupConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
        set(config, "id", id);
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
