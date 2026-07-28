package com.alechilles.alecstamework.config.assets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigIndex;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class TwPopulationGroupConfigTest {
    @Test
    void codecConvertsCanonicalLimitsToReplacementPolicy()
            throws Exception {
        TwPopulationGroupConfig config = decode(
                "HydragonFullDragons",
                """
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
                """
        );

        var policy = config.toPolicy(7L);

        assertTrue(config.isEnabled());
        assertEquals(100, config.getPriority());
        assertEquals("hydragon:full_dragons", policy.groupId());
        assertArrayEquals(
                new String[] {"NordicDrake_Tamed", "Hydra_Tamed"},
                config.getRoleIds()
        );
        assertEquals(0, policy.maxOwnedPerOwner());
        assertEquals(1, policy.maxActivePerOwner());
        assertEquals(PopulationGroupScope.GLOBAL, policy.scope());
        assertEquals(7L, policy.policyRevision());
    }

    @Test
    void validationRejectsInvalidScopeIdsRolesAndLimits()
            throws Exception {
        TwPopulationGroupConfig perWorld = decode(
                "PerWorldDragons",
                """
                {
                  "GroupId": "hydragon:world_dragons",
                  "RoleIds": ["Hydra_Tamed"],
                  "Limits": {
                    "MaxOwnedPerOwner": 2,
                    "MaxActivePerOwner": 0,
                    "Scope": "PerWorld"
                  }
                }
                """
        );
        TwPopulationGroupConfig negative = decode(
                "NegativeDragons",
                """
                {
                  "GroupId": "hydragon:negative_dragons",
                  "RoleIds": ["Hydra_Tamed"],
                  "Limits": { "MaxOwnedPerOwner": -1 }
                }
                """
        );
        TwPopulationGroupConfig duplicateRole = decode(
                "DuplicateRole",
                """
                {
                  "GroupId": "hydragon:duplicate",
                  "RoleIds": ["Hydra_Tamed", "Hydra_Tamed"]
                }
                """
        );
        TwPopulationGroupConfig bareId = decode(
                "BareId",
                """
                {
                  "GroupId": "not_namespaced",
                  "RoleIds": ["Hydra_Tamed"]
                }
                """
        );

        assertEquals(
                PopulationGroupScope.PER_WORLD,
                perWorld.toPolicy(1L).scope()
        );
        assertThrows(
                IllegalArgumentException.class,
                negative::validateOrThrow
        );
        assertThrows(
                IllegalArgumentException.class,
                duplicateRole::validateOrThrow
        );
        assertThrows(
                IllegalArgumentException.class,
                bareId::validateOrThrow
        );
        assertThrows(
                RuntimeException.class,
                () -> TwPopulationGroupConfig.CODEC.decode(
                        BsonDocument.parse(
                                "{\"Limits\":{\"Scope\":\"Everywhere\"}}"
                        ),
                        new ExtraInfo()
                )
        );
    }

    @Test
    void nestedLimitsInheritWhileExplicitRoleArrayReplaces()
            throws Exception {
        TwPopulationGroupConfig parent = group(
                "Parent",
                "hydragon:full",
                1,
                "Dragon",
                "Hydra"
        );
        TwPopulationGroupConfig child = group(
                "Child",
                "hydragon:full",
                2,
                "Mini"
        );
        set(field(parent, "limits"), "maxOwnedPerOwner", 4);
        set(field(parent, "limits"), "maxActivePerOwner", 1);
        set(
                field(parent, "limits"),
                "scope",
                PopulationGroupScope.PER_WORLD
        );
        set(field(child, "limits"), "maxOwnedPerOwner", 2);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("RoleIds", "Limits"),
                Map.of("Limits", Set.of("MaxOwnedPerOwner"))
        );

        assertArrayEquals(new String[] {"Mini"}, child.getRoleIds());
        assertEquals(2, child.getLimits().getMaxOwnedPerOwner());
        assertEquals(1, child.getLimits().getMaxActivePerOwner());
        assertEquals(
                PopulationGroupScope.PER_WORLD,
                child.getLimits().getScope()
        );
    }

    @Test
    void duplicateWinnerAndCompleteRolePolicySetAreDeterministic()
            throws Exception {
        TwPopulationGroupConfig low = group(
                "zeta",
                "hydragon:full",
                2,
                "Dragon"
        );
        TwPopulationGroupConfig winner = group(
                "Alpha",
                "hydragon:full",
                2,
                "Dragon"
        );
        TwPopulationGroupConfig second = group(
                "Soul",
                "hydragon:soul",
                1,
                "Dragon"
        );

        PopulationGroupConfigIndex index =
                PopulationGroupConfigIndex.compile(
                        List.of(low, second, winner),
                        6L
                );

        assertEquals(
                "Alpha",
                index.getDefinition("hydragon:full")
                        .orElseThrow()
                        .configId()
        );
        assertEquals(
                List.of("hydragon:full", "hydragon:soul"),
                index.resolvePoliciesForRole("Dragon").stream()
                        .map(policy -> policy.groupId())
                        .toList()
        );
        assertEquals(
                List.of(),
                index.resolvePoliciesForRole("Unmatched")
        );
    }

    @Test
    void invalidReloadRetainsLastValidPolicyIndex()
            throws Exception {
        PopulationGroupConfigRegistry registry =
                new PopulationGroupConfigRegistry();
        assertTrue(registry.replace(
                List.of(group(
                        "valid",
                        "hydragon:full",
                        0,
                        "Dragon"
                )),
                1L
        ).applied());
        TwPopulationGroupConfig invalid = group(
                "bad",
                "not-namespaced",
                0,
                "Dragon",
                "Dragon"
        );

        PopulationGroupConfigRegistry.ReloadResult result =
                registry.replace(List.of(invalid), 2L);

        assertFalse(result.applied());
        assertEquals(1L, result.active().revision());
        assertEquals(
                "hydragon:full",
                result.active().resolvePoliciesForRole("Dragon")
                        .getFirst()
                        .groupId()
        );
    }

    private static TwPopulationGroupConfig group(
            String id,
            String groupId,
            int priority,
            String... roles
    ) throws Exception {
        TwPopulationGroupConfig config = new TwPopulationGroupConfig();
        set(config, "id", id);
        set(config, "groupId", groupId);
        set(config, "priority", priority);
        set(config, "roleIds", roles);
        return config;
    }

    private static TwPopulationGroupConfig decode(
            String id,
            String json
    ) throws Exception {
        TwPopulationGroupConfig config =
                TwPopulationGroupConfig.CODEC.decode(
                        BsonDocument.parse(json),
                        new ExtraInfo()
                );
        set(config, "id", id);
        return config;
    }

    private static Object field(Object target, String name)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
