package com.alechilles.alecstamework.config.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class TwManagedActivityConfigTest {
    private static final String PROFILE = "runeteria:husbandry";

    @Test
    void codecAndInheritanceUseExplicitNestedReplacementRules()
            throws Exception {
        TwManagedActivityConfig parent = decode(
                "parent",
                profileJson(
                        "runeteria:parent",
                        "runeteria:parent_gate",
                        "ParentRole",
                        "runeteria:parent/feed"
                )
        );
        TwManagedActivityConfig child = decode(
                "child",
                """
                {
                  "ProfileId": "runeteria:child",
                  "Activities": {
                    "Feed": "runeteria:child/feed"
                  }
                }
                """
        );

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("ProfileId", "Activities"),
                Map.of("Activities", Set.of("Feed"))
        );

        assertEquals("runeteria:child", child.getProfileId());
        assertEquals("runeteria:child/feed", child.getActivities().getFeed());
        assertEquals(
                "runeteria:milk",
                child.getActivities().getHarvestContexts().get("Milk")
        );
        assertEquals("runeteria:family_a", child.getFamilies()[0].getGroupId());

        TwManagedActivityConfig replacementChild = decode(
                "replacement",
                """
                {
                  "Families": []
                }
                """
        );
        replacementChild.inheritMissingTopLevelFrom(
                parent,
                Set.of("Families"),
                Map.of()
        );
        assertEquals(0, replacementChild.getFamilies().length);
    }

    @Test
    void priorityTieAndRoleResolutionAreDeterministic() throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(
                        group("groupA", "runeteria:family_a", "RoleA"),
                        group("groupB", "runeteria:family_b", "RoleB")
                ),
                1L
        ).applied());
        ManagedActivityConfigRegistry registry =
                new ManagedActivityConfigRegistry(groups);

        TwManagedActivityConfig low = decode(
                "zeta",
                profileJson(
                        "runeteria:profile",
                        "runeteria:gate_b",
                        "RoleB",
                        "runeteria:profile/feed_b"
                )
        );
        TwManagedActivityConfig tieWinner = decode(
                "Alpha",
                profileJson(
                        "runeteria:profile",
                        "runeteria:gate_a",
                        "RoleA",
                        "runeteria:profile/feed_a"
                )
        );
        set(tieWinner, "priority", 4);
        set(low, "priority", 4);

        ManagedActivityConfigRegistry.ReloadResult result = registry.replace(
                List.of(low, tieWinner),
                4L
        );

        assertTrue(result.applied());
        assertEquals(
                "runeteria:profile/feed_a",
                registry.resolveRole("RoleA").orElseThrow()
                        .profile().activities().feed()
        );
        assertFalse(registry.resolveRole("RoleB").isPresent());
        assertEquals(
                "runeteria:profile",
                registry.resolveRole("RoleA").orElseThrow().profile().profileId()
        );
    }

    @Test
    void invalidCandidatesFailClosedAndRetainLastValidSnapshot()
            throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(
                        group("groupA", "runeteria:family_a", "RoleA"),
                        group("groupB", "runeteria:family_b", "RoleB")
                ),
                1L
        ).applied());
        ManagedActivityConfigRegistry registry =
                new ManagedActivityConfigRegistry(groups);
        TwManagedActivityConfig valid = decode(
                "valid",
                profileJson(
                        PROFILE,
                        "runeteria:gate_a",
                        "RoleA",
                        "runeteria:husbandry/feed"
                )
        );
        assertTrue(registry.replace(List.of(valid), 1L).applied());
        long revision = registry.snapshot().revision();

        TwManagedActivityConfig missingGroup = decode(
                "missing",
                profileJson(
                        "runeteria:missing",
                        "runeteria:gate_missing",
                        "RoleMissing",
                        "runeteria:missing/feed"
                )
        );
        assertFalse(registry.replace(List.of(missingGroup), 2L).applied());
        assertEquals(revision, registry.snapshot().revision());
        assertTrue(registry.resolveRole("RoleA").isPresent());
        assertEquals(
                "managed-profile-missing-group:runeteria:missing:runeteria:family_missing",
                registry.replace(List.of(missingGroup), 3L).error()
        );

        TwManagedActivityConfig duplicateRole = decode(
                "duplicate",
                """
                {
                  "ProfileId": "runeteria:duplicate",
                  "ProviderId": "runeteria:provider",
                  "ProviderContractVersion": 1,
                  "RequiredCapabilities": ["PROFILES"],
                  "Domains": [
                    {"DomainId":"runeteria:owned", "Owned":true}
                  ],
                  "Families": [
                    {"GroupId":"runeteria:family_a", "GateKey":"runeteria:a", "Weight":1},
                    {"GroupId":"runeteria:family_b", "GateKey":"runeteria:b", "Weight":1}
                  ],
                  "Activities": {
                    "Feed":"runeteria:feed",
                    "HarvestContexts":{"Milk":"runeteria:milk"},
                    "PendingOutputItems":{"Food_Egg":"runeteria:egg"},
                    "BreedingSuccess":"runeteria:breed"
                  }
                }
                """
        );
        PopulationGroupConfigRegistry duplicateGroups =
                new PopulationGroupConfigRegistry();
        assertTrue(duplicateGroups.replace(
                List.of(
                        group("groupA", "runeteria:family_a", "RoleA"),
                        group("groupB", "runeteria:family_b", "RoleA")
                ),
                1L
        ).applied());
        ManagedActivityConfigRegistry duplicateRegistry =
                new ManagedActivityConfigRegistry(duplicateGroups);
        assertFalse(duplicateRegistry.replace(List.of(duplicateRole), 4L).applied());
        assertEquals(0L, duplicateRegistry.snapshot().revision());

        TwManagedActivityConfig zeroWeight = decode(
                "zero",
                profileJson(
                        "runeteria:zero",
                        "runeteria:gate_zero",
                        "RoleA",
                        "runeteria:zero/feed"
                )
        );
        set(zeroWeight.getFamilies()[0], "weight", 0);
        assertFalse(registry.replace(List.of(zeroWeight), 5L).applied());
        assertEquals(revision, registry.snapshot().revision());
    }

    @Test
    void readinessReportsUnknownDisabledAbsentAndIncompleteProfiles()
            throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(group("groupA", "runeteria:family_a", "RoleA")),
                1L
        ).applied());
        ManagedActivityConfigRegistry registry =
                new ManagedActivityConfigRegistry(groups);

        ManagedActivityConfigRegistry.Readiness absent =
                registry.readiness(PROFILE);
        assertFalse(absent.available());
        assertEquals("profile-not-found", absent.detail());
        assertEquals(PROFILE, absent.profileId());

        TwManagedActivityConfig disabled = decode(
                "disabled",
                """
                {
                  "Enabled": false,
                  "ProfileId": "runeteria:disabled",
                  "ProviderId": "runeteria:provider",
                  "ProviderContractVersion": 1
                }
                """
        );
        assertTrue(registry.replace(List.of(disabled), 1L).applied());
        ManagedActivityConfigRegistry.Readiness disabledReadiness =
                registry.readiness("runeteria:disabled");
        assertFalse(disabledReadiness.available());
        assertEquals("profile-disabled", disabledReadiness.detail());

        TwManagedActivityConfig incomplete = decode(
                "incomplete",
                profileJson(
                        "runeteria:incomplete",
                        "runeteria:gate_incomplete",
                        "RoleA",
                        "runeteria:incomplete/feed"
                )
        );
        set(
                incomplete,
                "requiredCapabilities",
                new String[] {"NOT_A_CAPABILITY"}
        );
        ManagedActivityConfigRegistry.ReloadResult rejected =
                registry.replace(List.of(incomplete), 2L);
        assertFalse(rejected.applied());
        assertTrue(rejected.error().contains("unknown-required-capability"));
        ManagedActivityConfigRegistry.Readiness incompleteReadiness =
                registry.readiness("runeteria:incomplete");
        assertFalse(incompleteReadiness.available());
        assertEquals(
                "unknown-required-capability:runeteria:incomplete:NOT_A_CAPABILITY",
                incompleteReadiness.detail()
        );
    }

    @Test
    void validReplacementAdvancesRevisionAndRemovalClearsRoleState()
            throws Exception {
        PopulationGroupConfigRegistry groups = new PopulationGroupConfigRegistry();
        assertTrue(groups.replace(
                List.of(
                        group("groupA", "runeteria:family_a", "RoleA"),
                        group("groupB", "runeteria:family_b", "RoleB")
                ),
                1L
        ).applied());
        ManagedActivityConfigRegistry registry =
                new ManagedActivityConfigRegistry(groups);
        TwManagedActivityConfig first = decode(
                "first",
                profileJson(PROFILE, "runeteria:gate_a", "RoleA", "runeteria:first/feed")
        );
        assertTrue(registry.replace(List.of(first), 1L).applied());
        long firstRevision = registry.snapshot().revision();

        TwManagedActivityConfig second = decode(
                "second",
                profileJson(PROFILE, "runeteria:gate_b", "RoleB", "runeteria:second/feed")
        );
        assertTrue(registry.replace(List.of(second), firstRevision).applied());
        assertTrue(registry.snapshot().revision() > firstRevision);
        assertFalse(registry.resolveRole("RoleA").isPresent());
        assertEquals(
                "runeteria:second/feed",
                registry.resolveRole("RoleB").orElseThrow()
                        .profile().activities().feed()
        );
        assertEquals("ready", registry.readiness(PROFILE).detail());
    }

    private static String profileJson(
            String profileId,
            String gateKey,
            String roleId,
            String feed
    ) {
        String groupId = roleId.equals("RoleB")
                ? "runeteria:family_b"
                : roleId.equals("RoleMissing")
                        ? "runeteria:family_missing"
                        : "runeteria:family_a";
        return """
                {
                  "ProfileId": "%s",
                  "ProviderId": "runeteria:provider",
                  "ProviderContractVersion": 1,
                  "RequiredCapabilities": ["PROFILES"],
                  "Domains": [
                    {"DomainId":"runeteria:owned", "Owned":true},
                    {"DomainId":"runeteria:deployable", "Deployable":true}
                  ],
                  "Families": [
                    {"GroupId":"%s", "GateKey":"%s", "Weight":1}
                  ],
                  "Activities": {
                    "Feed":"%s",
                    "HarvestContexts":{"Milk":"runeteria:milk"},
                    "PendingOutputItems":{"Food_Egg":"runeteria:egg"},
                    "BreedingSuccess":"runeteria:breed"
                  }
                }
                """.formatted(profileId, groupId, gateKey, feed);
    }

    private static TwManagedActivityConfig decode(String id, String json)
            throws Exception {
        TwManagedActivityConfig config = TwManagedActivityConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
        set(config, "id", id);
        return config;
    }

    private static TwPopulationGroupConfig group(
            String id,
            String groupId,
            String... roles
    ) throws Exception {
        TwPopulationGroupConfig config =
                TwPopulationGroupConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "GroupId":"%s",
                                  "RoleIds":["%s"]
                                }
                                """.formatted(groupId, String.join("\",\"", roles))),
                        new ExtraInfo()
                );
        set(config, "id", id);
        set(config, "limits", new TwPopulationGroupConfig.LimitSettings());
        set(field(config, "limits"), "scope", PopulationGroupScope.GLOBAL);
        return config;
    }

    private static Object field(Object target, String name) throws Exception {
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
