package com.alechilles.alecstamework.companion.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Proves one player roster can host independently governed policy families. */
class BondedCompanionMultiFamilyPolicyTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000011"
    );
    private static final String ROSTER = "hydragon:horn";
    private static final String DRAGON_FAMILY = "hydragon:dragon";
    private static final String MINI_FAMILY = "hydragon:miniwyvern";
    private static final String DRAGON_ROLE = "Tamed_Dragon_Fire";
    private static final String MINI_ROLE = "Bonded_Miniwyvern";
    private BondedCompanionRosterRegistry registry;
    private BondedCompanionTransitionService transitions;

    @BeforeEach
    void setUp() throws Exception {
        registry = new BondedCompanionRosterRegistry();
        install(
                7L,
                policy("Dragons", DRAGON_FAMILY, DRAGON_ROLE, 1, 1, 600, 30, 2),
                policy("Miniwyverns", MINI_FAMILY, MINI_ROLE, 1, 1, 0, 0, 5)
        );
        transitions = new BondedCompanionTransitionService(
                new BondedCompanionPolicyResolver(registry)
        );
    }

    @Test
    void familiesApplyIndependentCapacityTimersCooldownAndRevivePrice() {
        BondedCompanionProfile dragon = create("dragon", DRAGON_ROLE, null);
        assertEquals(
                BondedCompanionTransitionService.ResultCode.OWNED_CAPACITY_REACHED,
                transitions.createCaptured(
                        creation("dragon-full", "dragon-2", DRAGON_ROLE, null),
                        counts(1, 0)
                ).code()
        );
        BondedCompanionProfile mini = transitions.createProvisioned(
                creation("mini", "mini-1", MINI_ROLE, null),
                counts(0, 0)
        ).profile();

        assertEquals(DRAGON_FAMILY, dragon.familyId());
        assertEquals(MINI_FAMILY, mini.familyId());

        BondedCompanionProfile activeDragon = summon(
                "summon-dragon", dragon, counts(1, 0), 1_000L
        );
        BondedCompanionProfile activeMini = summon(
                "summon-mini", mini, counts(1, 0), 1_000L
        );
        assertEquals(601_000L, activeDragon.activeLease().expiresAtMs());
        assertEquals(0L, activeMini.activeLease().expiresAtMs());

        BondedCompanionProfile storedDragon = store(
                "store-dragon", activeDragon, 2_000L
        );
        BondedCompanionProfile storedMini = store(
                "store-mini", activeMini, 2_000L
        );
        assertEquals(32_000L, storedDragon.summonCooldownUntilMs());
        assertEquals(0L, storedMini.summonCooldownUntilMs());

        BondedCompanionProfile deadDragon = death(
                "death-dragon",
                summon("resummon-dragon", storedDragon, counts(1, 0), 32_000L),
                33_000L
        );
        BondedCompanionProfile deadMini = death(
                "death-mini",
                summon("resummon-mini", storedMini, counts(1, 0), 2_000L),
                3_000L
        );

        assertEquals(
                BondedCompanionTransitionService.ResultCode.REVIVE_PRICE_MISMATCH,
                revive("wrong-mini-price", deadMini, 2).code()
        );
        assertEquals(
                BondedCompanionTransitionService.ResultCode.APPLIED,
                revive("revive-dragon", deadDragon, 2).code()
        );
        assertEquals(
                BondedCompanionTransitionService.ResultCode.APPLIED,
                revive("revive-mini", deadMini, 5).code()
        );
    }

    @Test
    void ambiguousRoleRequiresExplicitFamilySelection() throws Exception {
        install(
                8L,
                policy("Dragons", DRAGON_FAMILY, "Shared_Role", 1, 1, 60, 5, 2),
                policy("Miniwyverns", MINI_FAMILY, "Shared_Role", 1, 1, 0, 0, 5)
        );

        assertEquals(
                BondedCompanionTransitionService.ResultCode.POLICY_AMBIGUOUS,
                transitions.createCaptured(
                        creation("ambiguous", "shared-1", "Shared_Role", null, 8L),
                        counts(0, 0)
                ).code()
        );
        assertEquals(
                DRAGON_FAMILY,
                transitions.createCaptured(
                                creation(
                                        "explicit-dragon", "shared-2",
                                        "Shared_Role", DRAGON_FAMILY, 8L
                                ),
                                counts(0, 0)
                        )
                        .profile()
                        .familyId()
        );
        assertEquals(
                MINI_FAMILY,
                transitions.createProvisioned(
                                creation(
                                        "explicit-mini", "shared-3",
                                        "Shared_Role", MINI_FAMILY, 8L
                                ),
                                counts(0, 0)
                        )
                        .profile()
                        .familyId()
        );
    }

    @Test
    void roleResolutionNeverReturnsFamilyThatRejectsTheRole() {
        BondedCompanionPolicyResolver resolver =
                new BondedCompanionPolicyResolver(registry);

        assertEquals(BondedCompanionPolicyResolver.Status.ROLE_NOT_ALLOWED,
                resolver.resolveForRole(
                        ROSTER, DRAGON_FAMILY, MINI_ROLE, 7L
                ).status());
        assertEquals(BondedCompanionPolicyResolver.Status.ROLE_NOT_ALLOWED,
                resolver.resolveForRole(
                        ROSTER, null, "Unknown_Role", 7L
                ).status());
    }

    @Test
    void zeroOwnedAndActiveLimitsMeanUnlimited() throws Exception {
        install(
                9L,
                policy("Unlimited", DRAGON_FAMILY, DRAGON_ROLE, 0, 0, 0, 0, 2)
        );
        BondedCompanionProfile stored = transitions.createCaptured(
                creation("unlimited", "dragon-many", DRAGON_ROLE, null, 9L),
                counts(100, 99)
        ).profile();

        assertEquals(DRAGON_FAMILY, stored.familyId());
        assertEquals(
                BondedCompanionTransitionService.ResultCode.APPLIED,
                transitions.summon(
                        mutation("summon-unlimited", stored, 9L, 10_000L),
                        stored, counts(100, 100), "lease-unlimited", "world:test"
                ).code()
        );
    }

    private BondedCompanionProfile create(
            String id,
            String role,
            String family
    ) {
        BondedCompanionTransitionService.TransitionResult result =
                transitions.createCaptured(
                        creation("capture-" + id, id, role, family),
                        counts(0, 0)
                );
        assertTrue(result.applied());
        return result.profile();
    }

    private BondedCompanionProfile summon(
            String operation,
            BondedCompanionProfile profile,
            BondedCompanionTransitionService.RosterCounts counts,
            long nowMs
    ) {
        return transitions.summon(
                mutation(operation, profile, 7L, nowMs),
                profile, counts, "lease-" + operation, "world:test"
        ).profile();
    }

    private BondedCompanionProfile store(
            String operation,
            BondedCompanionProfile profile,
            long nowMs
    ) {
        return transitions.store(
                mutation(operation, profile, 7L, nowMs),
                profile, snapshot(profile.roleId())
        ).profile();
    }

    private BondedCompanionProfile death(
            String operation,
            BondedCompanionProfile profile,
            long nowMs
    ) {
        return transitions.confirmDeath(
                mutation(operation, profile, 7L, nowMs),
                profile
        ).profile();
    }

    private BondedCompanionTransitionService.TransitionResult revive(
            String operation,
            BondedCompanionProfile profile,
            int quantity
    ) {
        return transitions.revive(
                mutation(operation, profile, 7L, 40_000L),
                profile,
                new BondedCompanionTransitionService.RevivePayment(
                        "Ingredient_Life_Essence", quantity
                )
        );
    }

    private BondedCompanionTransitionService.CreationRequest creation(
            String operation,
            String profileId,
            String roleId,
            String familyId
    ) {
        return creation(operation, profileId, roleId, familyId, 7L);
    }

    private BondedCompanionTransitionService.CreationRequest creation(
            String operation,
            String profileId,
            String roleId,
            String familyId,
            long policyRevision
    ) {
        return new BondedCompanionTransitionService.CreationRequest(
                operation, OWNER, ROSTER, profileId, roleId, snapshot(roleId),
                policyRevision, 0L, familyId
        );
    }

    private BondedCompanionTransitionService.MutationRequest mutation(
            String operation,
            BondedCompanionProfile profile,
            long policyRevision,
            long nowMs
    ) {
        return new BondedCompanionTransitionService.MutationRequest(
                operation, OWNER, profile.revision(), policyRevision, nowMs
        );
    }

    private BondedCompanionTransitionService.RosterCounts counts(
            int owned,
            int active
    ) {
        return new BondedCompanionTransitionService.RosterCounts(owned, active);
    }

    private BondedCompanionSnapshot snapshot(String roleId) {
        return BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        UUID.fromString("20000000-0000-0000-0000-000000000011"),
                        null, -1, roleId, null,
                        new TameworkOwnerComponent(OWNER, "Owner"),
                        null, null, null, null, null, null, null, null, null,
                        null, 100.0D, 0L
                ),
                Map.of()
        );
    }

    private void install(
            long revision,
            TwBondedCompanionRosterConfig... policies
    ) {
        BondedCompanionRosterRegistry.ReloadResult result = registry.replace(
                List.of(policies), revision
        );
        assertTrue(result.applied(), result.error());
    }

    private TwBondedCompanionRosterConfig policy(
            String id,
            String familyId,
            String roleId,
            int maximumOwned,
            int maximumActive,
            long sessionSeconds,
            long cooldownSeconds,
            int reviveQuantity
    ) throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "%s",
                                  "FamilyId": "%s",
                                  "AllowedRoles": ["%s"],
                                  "MaximumOwned": %d,
                                  "MaximumActive": %d,
                                  "SessionDurationSeconds": %d,
                                  "SummonCooldownSeconds": %d,
                                  "RevivePrice": {"Costs": [{
                                    "ItemId": "Ingredient_Life_Essence",
                                    "Quantity": %d
                                  }]}
                                }
                                """.formatted(
                                ROSTER, familyId, roleId,
                                maximumOwned, maximumActive,
                                sessionSeconds, cooldownSeconds, reviveQuantity
                        )),
                        new ExtraInfo()
                );
        Field configId = config.getClass().getDeclaredField("id");
        configId.setAccessible(true);
        configId.set(config, id);
        return config;
    }
}
