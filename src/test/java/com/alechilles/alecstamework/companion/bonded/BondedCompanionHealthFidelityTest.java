package com.alechilles.alecstamework.companion.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Regression coverage for health that survives bonded-only state transitions. */
class BondedCompanionHealthFidelityTest {
    private static final UUID OWNER = UUID.fromString(
            "c1000000-0000-0000-0000-000000000001");
    private static final String ROSTER = "test:bonded-health";
    private static final String ROLE = "Bonded_Test_Dragon";

    @Test
    void decodeStoreReloadAndPresentationKeepExactCurrentAndMaximumHealth()
            throws Exception {
        BondedCompanionSnapshotCodec codec = new BondedCompanionSnapshotCodec();
        BondedCompanionSnapshot decoded = codec.decode(codec.encode(
                snapshot(250D, 400D, 62.5D))).snapshot();
        assertNotNull(decoded);
        BondedCompanionSnapshot reloaded = codec.decode(codec.encode(decoded))
                .snapshot();
        assertNotNull(reloaded);
        var presentation = new BondedCompanionSnapshotPresentationMapper(
                ignored -> new BondedCompanionSnapshotPresentationMapper
                        .RolePresentation(null, null, null, Map.of())
        ).map(reloaded);

        assertEquals("250.0", presentation.data().get("currentHealth"));
        assertEquals("400.0", presentation.data().get("maxHealth"));
        JsonObject state = JsonParser.parseString(codec.encode(reloaded))
                .getAsJsonObject().getAsJsonObject("fullState");
        assertEquals(250D, state.get("currentHealth").getAsDouble());
        assertEquals(400D, state.get("maximumHealth").getAsDouble());
    }

    @Test
    void paidReviveNormalizesZeroHealthSnapshotBeforeLaterSummon()
            throws Exception {
        BondedCompanionTransitionService service = new BondedCompanionTransitionService(
                new BondedCompanionPolicyResolver(registry())
        );
        BondedCompanionProfile stored = service.createCaptured(
                new BondedCompanionTransitionService.CreationRequest(
                        "capture", OWNER, ROSTER, "dragon", ROLE,
                        snapshot(0D), 1L, -10_000L),
                new BondedCompanionTransitionService.RosterCounts(0, 0)
        ).profile();
        BondedCompanionProfile active = service.summon(
                mutation("summon", stored, -9_000L), stored,
                new BondedCompanionTransitionService.RosterCounts(1, 0),
                "lease", "world").profile();
        BondedCompanionProfile dead = service.confirmDeath(
                mutation("death", active, -8_000L), active).profile();
        BondedCompanionProfile revived = service.revive(
                mutation("revive", dead, -7_000L), dead,
                new BondedCompanionTransitionService.RevivePayment(
                        "Ingredient_Life_Essence", 2)).profile();
        BondedCompanionTransitionService.TransitionResult summoned = service.summon(
                mutation("summon-after-revive", revived, -6_000L), revived,
                new BondedCompanionTransitionService.RosterCounts(1, 0),
                "lease-after-revive", "world");

        assertEquals(BondedCompanionState.STORED, revived.state());
        assertEquals(100D, revived.snapshot().fullState().healthPercent());
        assertEquals(BondedCompanionTransitionService.ResultCode.APPLIED,
                summoned.code());
        assertEquals(BondedCompanionState.ACTIVE, summoned.profile().state());
        assertEquals(100D, summoned.profile().snapshot().fullState()
                .healthPercent());
    }

    private static BondedCompanionSnapshot snapshot(double healthPercent) {
        return snapshot(null, null, healthPercent);
    }

    private static BondedCompanionSnapshot snapshot(Double currentHealth,
                                                     Double maximumHealth,
                                                     double healthPercent) {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                UUID.fromString("c1000000-0000-0000-0000-000000000010"),
                null, -1, ROLE, null, new TameworkOwnerComponent(OWNER, null),
                null, null, null, null, null, null, null, null, null, null,
                currentHealth, maximumHealth, healthPercent, -20_000L), Map.of());
    }

    private static BondedCompanionTransitionService.MutationRequest mutation(
            String operationId, BondedCompanionProfile profile, long nowMs) {
        return new BondedCompanionTransitionService.MutationRequest(
                operationId, OWNER, profile.revision(), 1L, nowMs);
    }

    private static BondedCompanionRosterRegistry registry() throws Exception {
        TwBondedCompanionRosterConfig config = TwBondedCompanionRosterConfig.CODEC
                .decode(BsonDocument.parse("""
                        {
                          "RosterId": "%s",
                          "FamilyId": "test:dragon",
                          "AllowedRoles": ["%s"],
                          "MaximumOwned": 2,
                          "MaximumActive": 1,
                          "RevivePrice": {"Costs": [{
                            "ItemId": "Ingredient_Life_Essence",
                            "Quantity": 2
                          }]},
                          "Features": {
                            "Capture": true,
                            "Provision": true,
                            "Summon": true,
                            "Dismiss": true,
                            "Revive": true
                          }
                        }
                        """.formatted(ROSTER, ROLE)), new ExtraInfo());
        return registry(config);
    }

    private static BondedCompanionRosterRegistry registry(
            TwBondedCompanionRosterConfig config) throws Exception {
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "BondedHealthRoster");
        BondedCompanionRosterRegistry registry = new BondedCompanionRosterRegistry();
        assertEquals(true, registry.replace(List.of(config), 1L).applied());
        return registry;
    }
}
