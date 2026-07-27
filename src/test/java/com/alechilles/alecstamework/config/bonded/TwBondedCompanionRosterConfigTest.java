package com.alechilles.alecstamework.config.bonded;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.assets.TwItemCostComponent;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class TwBondedCompanionRosterConfigTest {
    @Test
    void bondedRevivePriceUsesOneOrderedCostsRecipe() throws Exception {
        TwBondedCompanionRosterConfig config = roster(
                "MultiCost",
                """
                {
                  "RosterId": "hydragon:dragons",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire"],
                  "RevivePrice": {
                    "Costs": [
                      {"ItemId": "Revitalizing_Essence", "Quantity": 2},
                      {"ItemId": "Draconic_Essence", "Quantity": 4}
                    ]
                  }
                }
                """
        );

        config.validateOrThrow();

        TwItemCostComponent[] costs = config.getRevivePrice().getCosts();
        assertEquals(2, costs.length);
        assertEquals("Revitalizing_Essence", costs[0].getItemId());
        assertEquals(2, costs[0].getQuantity());
        assertEquals("Draconic_Essence", costs[1].getItemId());
        assertEquals(4, costs[1].getQuantity());
    }

    @Test
    void validPolicyAssetDecodesAndCompilesIntoImmutableRegistry() throws Exception {
        TwBondedCompanionRosterConfig config = roster(
                "HyDragonRoster",
                """
                {
                  "Priority": 100,
                  "RosterId": "hydragon:dragons",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire", "Tamed_Dragon_Ice"],
                  "MaximumOwned": 3,
                  "MaximumActive": 1,
                  "SessionDurationSeconds": 600,
                  "SummonCooldownSeconds": 30,
                  "RevivePrice": {"Costs": [{"ItemId": "Ingredient_Life_Essence", "Quantity": 2}]},
                  "Features": {
                    "Capture": true,
                    "Provision": true,
                    "Summon": true,
                    "Dismiss": true,
                    "Revive": true
                  }
                }
                """
        );
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();

        BondedCompanionRosterRegistry.ReloadResult result =
                registry.replace(List.of(config), 4L);
        BondedCompanionRosterRegistry.RosterDefinition definition =
                registry.resolve("hydragon:dragons").orElseThrow();

        assertTrue(result.applied());
        assertEquals(4L, registry.snapshot().revision());
        assertEquals("hydragon:dragon", definition.familyId());
        assertEquals(3, definition.maximumOwned());
        assertEquals(1, definition.maximumActive());
        assertEquals(600L, definition.sessionDurationSeconds());
        assertEquals(30L, definition.summonCooldownSeconds());
        assertEquals(2, definition.revivePrice().costs().getFirst().quantity());
        assertTrue(definition.features().capture());
        assertThrows(
                UnsupportedOperationException.class,
                () -> registry.snapshot().byRosterId().clear()
        );
    }

    @Test
    void inheritanceUsesNestedFallbackAndExplicitArraysReplace() throws Exception {
        TwBondedCompanionRosterConfig parent = roster(
                "Parent",
                """
                {
                  "RosterId": "hydragon:dragons",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire", "Tamed_Dragon_Ice"],
                  "MaximumOwned": 4,
                  "RevivePrice": {"Costs": [{"ItemId": "Ingredient_Life_Essence", "Quantity": 3}]},
                  "Features": {"Capture": true, "Revive": true}
                }
                """
        );
        TwBondedCompanionRosterConfig child = roster(
                "Child",
                """
                {
                  "AllowedRoles": ["Tamed_Dragon_Storm"],
                  "RevivePrice": {"Costs": [{"ItemId": "Child_Essence", "Quantity": 5}]},
                  "Features": {"Capture": false}
                }
                """
        );

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("AllowedRoles", "RevivePrice", "Features"),
                Map.of(
                        "RevivePrice", Set.of("Costs"),
                        "Features", Set.of("Capture")
                )
        );

        assertArrayEquals(
                new String[] {"Tamed_Dragon_Storm"},
                child.getAllowedRoles()
        );
        assertEquals("Child_Essence", child.getRevivePrice().getCosts()[0].getItemId());
        assertEquals(5, child.getRevivePrice().getCosts()[0].getQuantity());
        assertFalse(child.getFeatures().isCapture());
        assertTrue(child.getFeatures().isRevive());
    }

    @Test
    void validationRejectsNegativeValuesDuplicateIdsAndDuplicateRoles() throws Exception {
        TwBondedCompanionRosterConfig negative = roster(
                "Negative",
                """
                {
                  "RosterId": "hydragon:negative",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire"],
                  "MaximumOwned": -1
                }
                """
        );
        TwBondedCompanionRosterConfig duplicateRole = roster(
                "DuplicateRole",
                """
                {
                  "RosterId": "hydragon:roles",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire", "Tamed_Dragon_Fire"]
                }
                """
        );
        TwBondedCompanionRosterConfig duplicateFamilyA = roster(
                "Alpha",
                minimalRosterJson("hydragon:same")
        );
        TwBondedCompanionRosterConfig duplicateFamilyB = roster(
                "Beta",
                minimalRosterJson("hydragon:same")
        );
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();

        assertThrows(IllegalArgumentException.class, negative::validateOrThrow);
        assertThrows(IllegalArgumentException.class, duplicateRole::validateOrThrow);
        BondedCompanionRosterRegistry.ReloadResult forward = registry.replace(
                List.of(duplicateFamilyA, duplicateFamilyB),
                1L
        );
        BondedCompanionRosterRegistry.ReloadResult reverse = registry.replace(
                List.of(duplicateFamilyB, duplicateFamilyA),
                1L
        );
        assertFalse(forward.applied());
        assertFalse(reverse.applied());
        assertEquals(forward.error(), reverse.error());
    }

    @Test
    void combinedDuplicateAssetAndFamilyDiagnosticsAreOrderIndependent()
            throws Exception {
        TwBondedCompanionRosterConfig first = roster(
                "SharedAsset",
                familyRosterJson(
                        "hydragon:shared", "hydragon:dragon", "Dragon", 1,
                        1, 0, 0, 1
                )
        );
        TwBondedCompanionRosterConfig duplicateFamily = roster(
                "UniqueAsset",
                familyRosterJson(
                        "hydragon:shared", "hydragon:dragon", "OtherDragon", 1,
                        1, 0, 0, 1
                )
        );
        TwBondedCompanionRosterConfig duplicateAsset = roster(
                "SharedAsset",
                familyRosterJson(
                        "hydragon:shared", "hydragon:mini", "Mini", 1,
                        1, 0, 0, 1
                )
        );
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();

        BondedCompanionRosterRegistry.ReloadResult familyFirst =
                registry.replace(
                        List.of(first, duplicateFamily, duplicateAsset), 1L
                );
        BondedCompanionRosterRegistry.ReloadResult assetFirst =
                registry.replace(
                        List.of(duplicateAsset, duplicateFamily, first), 1L
                );

        assertFalse(familyFirst.applied());
        assertFalse(assetFirst.applied());
        assertEquals(familyFirst.error(), assetFirst.error());
    }

    @Test
    void snapshotCanonicalConstructorRejectsIncoherentRepresentativeMap()
            throws Exception {
        BondedCompanionRosterRegistry registry = registryWith(roster(
                "Roster", minimalRosterJson("hydragon:dragons")
        ));
        BondedCompanionRosterRegistry.Snapshot coherent = registry.snapshot();

        assertThrows(
                IllegalArgumentException.class,
                () -> new BondedCompanionRosterRegistry.Snapshot(
                        coherent.revision(), Map.of(),
                        coherent.familiesByRosterId()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BondedCompanionRosterRegistry.Snapshot(
                        coherent.revision(),
                        Map.of("wrong:roster", coherent.byRosterId()
                                .get("hydragon:dragons")),
                        coherent.familiesByRosterId()
                )
        );
    }

    @Test
    void snapshotCanonicalConstructorRejectsRosterWithoutFamilies() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BondedCompanionRosterRegistry.Snapshot(
                        1L,
                        Map.of(),
                        Map.of("hydragon:horn", Map.of())
                )
        );
    }

    @Test
    void sharedRosterCompilesIndependentFamiliesAndKeepsExactLookup()
            throws Exception {
        TwBondedCompanionRosterConfig dragons = roster(
                "Dragons",
                familyRosterJson(
                        "hydragon:horn",
                        "hydragon:dragon",
                        "Tamed_Dragon_Fire",
                        3,
                        1,
                        600,
                        30,
                        2
                )
        );
        TwBondedCompanionRosterConfig miniwyverns = roster(
                "Miniwyverns",
                familyRosterJson(
                        "hydragon:horn",
                        "hydragon:miniwyvern",
                        "Tamed_Miniwyvern",
                        1,
                        1,
                        0,
                        0,
                        5
                )
        );
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();

        BondedCompanionRosterRegistry.ReloadResult result = registry.replace(
                List.of(miniwyverns, dragons),
                9L
        );

        assertTrue(result.applied(), result.error());
        assertTrue(registry.snapshot().byRosterId().isEmpty());
        assertEquals(2, registry.snapshot().families("hydragon:horn").size());
        assertEquals(
                3,
                registry.resolve("hydragon:horn", "hydragon:dragon")
                        .orElseThrow()
                        .maximumOwned()
        );
        assertEquals(
                5,
                registry.resolve("hydragon:horn", "hydragon:miniwyvern")
                        .orElseThrow()
                        .revivePrice()
                        .costs().getFirst().quantity()
        );
        assertTrue(registry.resolve("hydragon:horn").isEmpty());
        assertEquals(
                "hydragon:dragon",
                registry.resolveForRole(
                                "hydragon:horn",
                                "Tamed_Dragon_Fire"
                        )
                        .definition()
                        .familyId()
        );
        CommandItemRegistry commands = new CommandItemRegistry(registry);
        TwCommandItemConfig horn = command("""
                {
                  "Enabled": true,
                  "ItemIds": ["HyDragon_Dragon_Horn"],
                  "RosterStorage": "BondedCompanions",
                  "BondedRosterId": "hydragon:horn"
                }
                """);
        commands.register("HyDragon_Dragon_Horn", horn);
        assertEquals(horn, commands.get("HyDragon_Dragon_Horn"));

        BondedCompanionConfigReloadService.ReloadResult reloaded =
                new BondedCompanionConfigReloadService(registry, commands)
                        .reload(List.of(dragons, miniwyverns), List.of(horn));
        assertTrue(reloaded.applied(), () -> String.join(",", reloaded.errors()));
        assertEquals(1, reloaded.rosterCount());
        assertEquals(1, reloaded.commandCount());
        assertEquals(horn, commands.get("HyDragon_Dragon_Horn"));
    }

    @Test
    void roleLookupRejectsAmbiguousFamiliesAndReloadReplacesWholeRoster()
            throws Exception {
        TwBondedCompanionRosterConfig dragons = roster(
                "Dragons",
                familyRosterJson(
                        "hydragon:horn",
                        "hydragon:dragon",
                        "Shared_Role",
                        3,
                        1,
                        600,
                        30,
                        2
                )
        );
        TwBondedCompanionRosterConfig miniwyverns = roster(
                "Miniwyverns",
                familyRosterJson(
                        "hydragon:horn",
                        "hydragon:miniwyvern",
                        "Shared_Role",
                        1,
                        1,
                        0,
                        0,
                        5
                )
        );
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();

        assertTrue(registry.replace(List.of(dragons, miniwyverns), 4L).applied());
        assertEquals(
                BondedCompanionRosterRegistry.FamilyResolutionStatus.AMBIGUOUS,
                registry.resolveForRole("hydragon:horn", "Shared_Role").status()
        );
        assertEquals(
                "hydragon:miniwyvern",
                registry.resolve(
                                "hydragon:horn",
                                "hydragon:miniwyvern"
                        )
                        .orElseThrow()
                        .familyId()
        );

        assertTrue(registry.replace(List.of(dragons), 5L).applied());
        assertEquals(1, registry.snapshot().families("hydragon:horn").size());
        assertTrue(registry.resolve(
                "hydragon:horn",
                "hydragon:miniwyvern"
        ).isEmpty());
        assertEquals(
                "hydragon:dragon",
                registry.resolve("hydragon:horn").orElseThrow().familyId()
        );
    }

    @Test
    void zeroIsTheOnlyDisabledTimerSentinel() throws Exception {
        TwBondedCompanionRosterConfig zero = roster(
                "ZeroTimers",
                """
                {
                  "RosterId": "hydragon:zero",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire"],
                  "SessionDurationSeconds": 0,
                  "SummonCooldownSeconds": 0
                }
                """
        );
        TwBondedCompanionRosterConfig negativeTimer = roster(
                "NegativeTimer",
                """
                {
                  "RosterId": "hydragon:timer",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire"],
                  "SessionDurationSeconds": -1
                }
                """
        );

        zero.validateOrThrow();
        assertEquals(0L, zero.getSessionDurationSeconds());
        assertEquals(0L, zero.getSummonCooldownSeconds());
        assertThrows(
                IllegalArgumentException.class,
                negativeTimer::validateOrThrow
        );
    }

    @Test
    void bondedCommandStorageRequiresExistingRosterAndRejectsOwnerFamilyFields()
            throws Exception {
        BondedCompanionRosterRegistry bonded = registryWith(
                roster("Roster", minimalRosterJson("hydragon:dragons"))
        );
        CommandItemRegistry commands = new CommandItemRegistry(bonded);
        TwCommandItemConfig valid = command("""
                {
                  "RosterStorage": "BondedCompanions",
                  "BondedRosterId": "hydragon:dragons"
                }
                """);

        commands.register("HydragonHorn", "HyDragon_Dragon_Horn", valid);

        assertTrue(valid.usesBondedCompanionRoster());
        assertEquals("hydragon:dragons", valid.getBondedRosterId());
        assertFalse(valid.isProjectRosterToItemMetadata());
        assertThrows(
                IllegalArgumentException.class,
                () -> commands.register(
                        "Missing",
                        "HyDragon_Missing_Horn",
                        command("""
                                {
                                  "RosterStorage": "BondedCompanions",
                                  "BondedRosterId": "hydragon:missing"
                                }
                                """)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> commands.register(
                        "Mixed",
                        "HyDragon_Mixed_Horn",
                        command("""
                                {
                                  "RosterStorage": "BondedCompanions",
                                  "BondedRosterId": "hydragon:dragons",
                                  "CommandFamilyId": "hydragon:legacy"
                                }
                                """)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> commands.register(
                        "Projection",
                        "HyDragon_Projection_Horn",
                        command("""
                                {
                                  "RosterStorage": "BondedCompanions",
                                  "BondedRosterId": "hydragon:dragons",
                                  "ProjectRosterToItemMetadata": false
                                }
                                """)
                )
        );
    }

    @Test
    void resolverCanonicalizesAllowedRoleWhitespace() throws Exception {
        BondedCompanionRosterRegistry registry = registryWith(roster(
                "Whitespace",
                """
                {
                  "RosterId": "hydragon:dragons",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["  Tamed_Dragon_Fire  "]
                }
                """
        ));

        assertEquals(
                Set.of("Tamed_Dragon_Fire"),
                registry.resolve("hydragon:dragons")
                        .orElseThrow()
                        .allowedRoles()
        );
    }

    @Test
    void coherentReloadAddsAndRemovesRosterWithDependentCommands()
            throws Exception {
        BondedCompanionRosterRegistry rosters =
                new BondedCompanionRosterRegistry();
        CommandItemRegistry commands = new CommandItemRegistry(rosters);
        BondedCompanionConfigReloadService reloads =
                new BondedCompanionConfigReloadService(rosters, commands);
        TwBondedCompanionRosterConfig roster = roster(
                "Roster",
                minimalRosterJson("hydragon:dragons")
        );
        TwCommandItemConfig command = command("""
                {
                  "Enabled": true,
                  "ItemIds": ["HyDragon_Dragon_Horn"],
                  "RosterStorage": "BondedCompanions",
                  "BondedRosterId": "hydragon:dragons"
                }
                """);

        assertTrue(reloads.reload(List.of(roster), List.of(command)).applied());
        assertTrue(rosters.resolve("hydragon:dragons").isPresent());
        assertEquals(command, commands.get("HyDragon_Dragon_Horn"));
        long rosterRevision = rosters.snapshot().revision();
        long commandRevision = commands.revision();

        BondedCompanionConfigReloadService.ReloadResult rejected =
                reloads.reload(List.of(), List.of(command));

        assertFalse(rejected.applied());
        assertEquals(rosterRevision, rosters.snapshot().revision());
        assertEquals(commandRevision, commands.revision());
        assertTrue(rosters.resolve("hydragon:dragons").isPresent());
        assertEquals(command, commands.get("HyDragon_Dragon_Horn"));

        assertTrue(reloads.reload(List.of(), List.of()).applied());
        assertTrue(rosters.resolve("hydragon:dragons").isEmpty());
        assertNull(commands.get("HyDragon_Dragon_Horn"));
    }

    @Test
    void commandReplacementRejectsDuplicateItemAndConfigIds() throws Exception {
        TwBondedCompanionRosterConfig roster = roster(
                "Roster",
                minimalRosterJson("hydragon:dragons")
        );
        BondedCompanionRosterRegistry rosters = registryWith(roster);
        CommandItemRegistry commands = new CommandItemRegistry(rosters);
        TwCommandItemConfig first = command("""
                {
                  "Enabled": true,
                  "ItemIds": ["HyDragon_Shared_Horn"],
                  "RosterStorage": "BondedCompanions",
                  "BondedRosterId": "hydragon:dragons"
                }
                """);
        TwCommandItemConfig duplicateItem = command("""
                {
                  "Enabled": true,
                  "ItemIds": ["HyDragon_Shared_Horn"],
                  "RosterStorage": "BondedCompanions",
                  "BondedRosterId": "hydragon:dragons"
                }
                """);
        set(first, "id", "DragonHorn");
        set(duplicateItem, "id", "MiniwyvernHorn");

        IllegalArgumentException itemFailure = assertThrows(
                IllegalArgumentException.class,
                () -> commands.prepareReplacement(
                        List.of(first, duplicateItem), rosters.snapshot()
                )
        );
        assertEquals(
                "Duplicate command item id: HyDragon_Shared_Horn",
                itemFailure.getMessage()
        );

        set(duplicateItem, "id", "DragonHorn");
        set(duplicateItem, "itemIds", new String[]{"HyDragon_Other_Horn"});
        IllegalArgumentException configFailure = assertThrows(
                IllegalArgumentException.class,
                () -> commands.prepareReplacement(
                        List.of(first, duplicateItem), rosters.snapshot()
                )
        );
        assertEquals(
                "Duplicate command config id: DragonHorn",
                configFailure.getMessage()
        );
    }

    @Test
    void bondedCaptureRequiresRosterAndItemAccessWithoutGenericFamily()
            throws Exception {
        ItemFeatureConfig.CaptureItemMechanics valid = capture("""
                {
                  "SuccessDisposition": "StoreBondedCompanion",
                  "BondedRosterId": "hydragon:dragons",
                  "RequiredCommandConfigId": "HydragonHorn",
                  "RequireCommandAccessItem": true
                }
                """);

        assertEquals(
                CaptureSuccessDisposition.STORE_BONDED_COMPANION,
                valid.successDisposition()
        );
        assertEquals("hydragon:dragons", valid.bondedRosterId());
        assertNull(valid.commandFamilyId());
        assertThrows(
                IllegalArgumentException.class,
                () -> capture("""
                        {
                          "SuccessDisposition": "StoreBondedCompanion",
                          "RequiredCommandConfigId": "HydragonHorn",
                          "RequireCommandAccessItem": true
                        }
                        """)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> capture("""
                        {
                          "SuccessDisposition": "StoreBondedCompanion",
                          "BondedRosterId": "hydragon:dragons"
                        }
                        """)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> capture("""
                        {
                          "SuccessDisposition": "StoreBondedCompanion",
                          "BondedRosterId": "hydragon:dragons",
                          "CommandFamilyId": "hydragon:legacy",
                          "RequiredCommandConfigId": "HydragonHorn",
                          "RequireCommandAccessItem": true
                        }
                        """)
        );
    }

    private static BondedCompanionRosterRegistry registryWith(
            TwBondedCompanionRosterConfig config
    ) {
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        assertTrue(registry.replace(List.of(config), 1L).applied());
        return registry;
    }

    private static TwBondedCompanionRosterConfig roster(
            String id,
            String json
    ) throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse(json),
                        new ExtraInfo()
                );
        set(config, "id", id);
        return config;
    }

    private static TwCommandItemConfig command(String json)
            throws Exception {
        TwCommandItemConfig config = TwCommandItemConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
        set(config, "id", "Command");
        return config;
    }

    private static ItemFeatureConfig.CaptureItemMechanics capture(
            String captureJson
    ) throws Exception {
        TwSpawnerConfig config = TwSpawnerConfig.CODEC.decode(
                BsonDocument.parse("{\"Capture\":" + captureJson + "}"),
                new ExtraInfo()
        );
        return config.toItemFeatureConfig().getCaptureMechanics();
    }

    private static String minimalRosterJson(String rosterId) {
        return """
                {
                  "RosterId": "%s",
                  "FamilyId": "hydragon:dragon",
                  "AllowedRoles": ["Tamed_Dragon_Fire"]
                }
                """.formatted(rosterId);
    }

    private static String familyRosterJson(
            String rosterId,
            String familyId,
            String roleId,
            int maximumOwned,
            int maximumActive,
            long sessionDurationSeconds,
            long summonCooldownSeconds,
            int reviveQuantity
    ) {
        return """
                {
                  "RosterId": "%s",
                  "FamilyId": "%s",
                  "AllowedRoles": ["%s"],
                  "MaximumOwned": %d,
                  "MaximumActive": %d,
                  "SessionDurationSeconds": %d,
                  "SummonCooldownSeconds": %d,
                  "RevivePrice": {
                    "Costs": [{
                      "ItemId": "Ingredient_Life_Essence",
                      "Quantity": %d
                    }]
                  }
                }
                """.formatted(
                        rosterId,
                        familyId,
                        roleId,
                        maximumOwned,
                        maximumActive,
                        sessionDurationSeconds,
                        summonCooldownSeconds,
                        reviveQuantity
                );
    }

    private static void set(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
