package com.alechilles.alecstamework.companion.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behavioral contract for the isolated three-state bonded lifecycle. */
class BondedCompanionStateMachineTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID STRANGER = UUID.fromString(
            "10000000-0000-0000-0000-000000000002"
    );
    private static final String ROSTER = "hydragon:companions";
    private static final String ROLE = "Tamed_Dragon_Fire";
    private BondedCompanionRosterRegistry registry;
    private BondedCompanionTransitionService service;

    @BeforeEach
    void setUp() throws Exception {
        registry = new BondedCompanionRosterRegistry();
        installPolicy(1L, 2, 1, 10, 5, true, true, true, true, true);
        service = new BondedCompanionTransitionService(
                new BondedCompanionPolicyResolver(registry)
        );
    }

    @Test
    void captureAndProvisionBeginStoredAndUseCurrentPolicyFamily() {
        BondedCompanionTransitionService.TransitionResult captured =
                service.createCaptured(creation("capture-1"), counts(0, 0));
        BondedCompanionTransitionService.TransitionResult provisioned =
                service.createProvisioned(
                        creation("provision-1", "mini-1", "Bonded_Miniwyvern"),
                        counts(1, 0)
                );

        assertEquals(BondedCompanionTransitionService.ResultCode.APPLIED,
                captured.code());
        assertEquals(BondedCompanionState.STORED, captured.profile().state());
        assertEquals("hydragon:dragon", captured.profile().familyId());
        assertNull(captured.profile().activeLease());
        assertEquals(BondedCompanionTransitionService.ResultCode.APPLIED,
                provisioned.code());
        assertEquals(BondedCompanionState.STORED,
                provisioned.profile().state());
    }

    @Test
    void permitsStoredActiveStoredAndActiveDeadDeadStored() {
        BondedCompanionProfile stored = createStored();
        BondedCompanionTransitionService.TransitionResult active = service.summon(
                mutation("summon-1", stored, -20_000L), stored,
                counts(1, 0), "lease-1", "world:alpha"
        );
        BondedCompanionTransitionService.TransitionResult storedAgain =
                service.store(
                        mutation("store-1", active.profile(), -19_000L),
                        active.profile(), snapshot(ROLE, "Ember")
                );
        BondedCompanionTransitionService.TransitionResult activeAgain =
                service.summon(
                        mutation("summon-2", storedAgain.profile(), -14_000L),
                        storedAgain.profile(), counts(1, 0),
                        "lease-2", "world:alpha"
                );
        BondedCompanionTransitionService.TransitionResult dead =
                service.confirmDeath(
                        mutation("death-1", activeAgain.profile(), -13_000L),
                        activeAgain.profile()
                );
        BondedCompanionTransitionService.TransitionResult revived =
                service.revive(
                        mutation("revive-1", dead.profile(), -12_000L),
                        dead.profile(),
                        new BondedCompanionTransitionService.RevivePayment(
                                "Ingredient_Life_Essence", 2
                        )
                );

        assertEquals(BondedCompanionState.ACTIVE, active.profile().state());
        assertEquals(-10_000L, active.profile().activeLease().expiresAtMs());
        assertEquals(BondedCompanionState.STORED, storedAgain.profile().state());
        assertEquals(-14_000L, storedAgain.profile().summonCooldownUntilMs());
        assertEquals(BondedCompanionState.DEAD, dead.profile().state());
        assertEquals(-13_000L, dead.profile().diedAtMs());
        assertEquals(BondedCompanionState.STORED, revived.profile().state());
        assertEquals(1L, revived.profile().reviveCount());
        assertNull(revived.profile().diedAtMs());
    }

    @Test
    void rejectsEveryOtherStateTransition() {
        BondedCompanionProfile stored = createStored();
        BondedCompanionProfile active = service.summon(
                mutation("summon-valid", stored, 10_000L), stored,
                counts(1, 0), "lease-valid", "world:alpha"
        ).profile();
        BondedCompanionProfile dead = service.confirmDeath(
                mutation("death-valid", active, 11_000L), active
        ).profile();
        BondedCompanionTransitionService.RevivePayment payment =
                new BondedCompanionTransitionService.RevivePayment(
                        "Ingredient_Life_Essence", 2
                );

        assertInvalid(service.summon(
                mutation("summon-active", active, 12_000L), active,
                counts(1, 0), "lease-x", "world:alpha"));
        assertInvalid(service.summon(
                mutation("summon-dead", dead, 12_000L), dead,
                counts(1, 0), "lease-y", "world:alpha"));
        assertInvalid(service.store(
                mutation("store-stored", stored, 12_000L), stored,
                snapshot(ROLE, "Ember")));
        assertInvalid(service.store(
                mutation("store-dead", dead, 12_000L), dead,
                snapshot(ROLE, "Ember")));
        assertInvalid(service.confirmDeath(
                mutation("death-stored", stored, 12_000L), stored));
        assertInvalid(service.confirmDeath(
                mutation("death-dead", dead, 12_000L), dead));
        assertInvalid(service.revive(
                mutation("revive-stored", stored, 12_000L), stored, payment));
        assertInvalid(service.revive(
                mutation("revive-active", active, 12_000L), active, payment));
    }

    @Test
    void cooldownUsesOrderingAtTheBoundaryAndAllowsNegativeWorldTime() {
        BondedCompanionProfile stored = createStored();
        BondedCompanionProfile active = service.summon(
                mutation("summon-1", stored, -20_000L), stored,
                counts(1, 0), "lease-1", "world:alpha"
        ).profile();
        BondedCompanionProfile cooled = service.store(
                mutation("store-1", active, -19_000L), active,
                snapshot(ROLE, "Ember")
        ).profile();

        BondedCompanionTransitionService.TransitionResult early = service.summon(
                mutation("summon-early", cooled, -14_001L), cooled,
                counts(1, 0), "lease-2", "world:alpha"
        );
        BondedCompanionTransitionService.TransitionResult boundary = service.summon(
                mutation("summon-boundary", cooled, -14_000L), cooled,
                counts(1, 0), "lease-3", "world:alpha"
        );

        assertEquals(BondedCompanionTransitionService.ResultCode.COOLDOWN_ACTIVE,
                early.code());
        assertEquals(BondedCompanionTransitionService.ResultCode.APPLIED,
                boundary.code());
    }

    @Test
    void zeroSessionAndCooldownRemainUnlimitedAndImmediatelyReusable()
            throws Exception {
        installPolicy(2L, 2, 1, 0, 0, true, true, true, true, true);
        BondedCompanionProfile stored = service.createCaptured(
                creation("capture-zero", 2L), counts(0, 0)
        ).profile();
        BondedCompanionProfile active = service.summon(
                mutation("summon-zero", stored, 2L, -500L), stored,
                counts(1, 0), "lease-zero", "world:alpha"
        ).profile();
        BondedCompanionProfile returned = service.store(
                mutation("store-zero", active, 2L, -499L), active,
                snapshot(ROLE, "Ember")
        ).profile();

        assertEquals(0L, active.activeLease().expiresAtMs());
        assertEquals(0L, returned.summonCooldownUntilMs());
        assertEquals(BondedCompanionTransitionService.ResultCode.APPLIED,
                service.summon(
                        mutation("summon-again", returned, 2L, -499L),
                        returned, counts(1, 0), "lease-again", "world:alpha"
                ).code());
    }

    @Test
    void ownedAndActiveCapacityRejectAtTheExactLimit() {
        assertEquals(
                BondedCompanionTransitionService.ResultCode.OWNED_CAPACITY_REACHED,
                service.createCaptured(creation("capture-full"), counts(2, 0)).code()
        );
        BondedCompanionProfile stored = createStored();
        assertEquals(
                BondedCompanionTransitionService.ResultCode.ACTIVE_CAPACITY_REACHED,
                service.summon(
                        mutation("summon-full", stored, 1_000L), stored,
                        counts(1, 1), "lease-full", "world:alpha"
                ).code()
        );
        assertEquals(
                BondedCompanionTransitionService.ResultCode.APPLIED,
                service.summon(
                        mutation("summon-space", stored, 1_000L), stored,
                        counts(1, 0), "lease-space", "world:alpha"
                ).code()
        );
    }

    @Test
    void duplicateOperationReturnsTheAlreadyAppliedProfileWithoutAnotherMutation() {
        BondedCompanionProfile stored = createStored();
        BondedCompanionTransitionService.MutationRequest request =
                mutation("summon-once", stored, 1_000L);
        BondedCompanionTransitionService.TransitionResult first = service.summon(
                request, stored, counts(1, 0), "lease-once", "world:alpha"
        );
        BondedCompanionTransitionService.TransitionResult replay = service.summon(
                request, first.profile(), counts(1, 0),
                "lease-different", "world:beta"
        );

        assertEquals(BondedCompanionTransitionService.ResultCode.IDEMPOTENT_REPLAY,
                replay.code());
        assertSame(first.profile(), replay.profile());
        assertEquals(first.profile().revision(), replay.profile().revision());
        assertEquals("lease-once", replay.profile().activeLease().leaseToken());
    }

    @Test
    void ownershipRolePolicyRevisionAndRevivePriceAreCheckedBeforeMutation()
            throws Exception {
        BondedCompanionProfile stored = createStored();
        assertEquals(BondedCompanionTransitionService.ResultCode.NOT_OWNER,
                service.summon(
                        mutation("stranger", stored, 1L, STRANGER, 1_000L),
                        stored, counts(1, 0), "lease", "world:alpha"
                ).code());
        assertEquals(BondedCompanionTransitionService.ResultCode.ROLE_NOT_ALLOWED,
                service.createCaptured(
                        creation("bad-role", "bad", "Tamed_Dragon_Ice"),
                        counts(0, 0)
                ).code());

        BondedCompanionProfile active = service.summon(
                mutation("summon", stored, 1_000L), stored,
                counts(1, 0), "lease", "world:alpha"
        ).profile();
        BondedCompanionProfile dead = service.confirmDeath(
                mutation("death", active, 2_000L), active
        ).profile();
        assertEquals(BondedCompanionTransitionService.ResultCode.REVIVE_PRICE_MISMATCH,
                service.revive(
                        mutation("wrong-price", dead, 3_000L), dead,
                        new BondedCompanionTransitionService.RevivePayment(
                                "Ingredient_Life_Essence", 1
                        )
                ).code());

        installPolicy(2L, 2, 1, 10, 5, true, true, false, true, true);
        assertEquals(BondedCompanionTransitionService.ResultCode.POLICY_REVISION_CONFLICT,
                service.summon(
                        mutation("stale-policy", stored, 1L, 4_000L), stored,
                        counts(1, 0), "lease-stale", "world:alpha"
                ).code());
        assertEquals(BondedCompanionTransitionService.ResultCode.FEATURE_DISABLED,
                service.summon(
                        mutation("fresh-policy", stored, 2L, 4_000L), stored,
                        counts(1, 0), "lease-fresh", "world:alpha"
                ).code());
    }

    private void assertInvalid(BondedCompanionTransitionService.TransitionResult result) {
        assertEquals(BondedCompanionTransitionService.ResultCode.INVALID_STATE,
                result.code());
    }

    private BondedCompanionProfile createStored() {
        BondedCompanionTransitionService.TransitionResult result =
                service.createCaptured(creation("capture-base"), counts(0, 0));
        assertTrue(result.applied());
        return result.profile();
    }

    private BondedCompanionTransitionService.CreationRequest creation(String op) {
        return creation(op, "dragon-1", ROLE);
    }

    private BondedCompanionTransitionService.CreationRequest creation(
            String op, String profileId, String roleId
    ) {
        return creation(op, profileId, roleId, 1L);
    }

    private BondedCompanionTransitionService.CreationRequest creation(
            String op, long policyRevision
    ) {
        return creation(op, "dragon-1", ROLE, policyRevision);
    }

    private BondedCompanionTransitionService.CreationRequest creation(
            String op, String profileId, String roleId, long policyRevision
    ) {
        return new BondedCompanionTransitionService.CreationRequest(
                op, OWNER, ROSTER, profileId, roleId,
                snapshot(roleId, "Ember"), policyRevision, -30_000L
        );
    }

    private BondedCompanionTransitionService.MutationRequest mutation(
            String op, BondedCompanionProfile profile, long nowMs
    ) {
        return mutation(op, profile, 1L, OWNER, nowMs);
    }

    private BondedCompanionTransitionService.MutationRequest mutation(
            String op, BondedCompanionProfile profile,
            long policyRevision, long nowMs
    ) {
        return mutation(op, profile, policyRevision, OWNER, nowMs);
    }

    private BondedCompanionTransitionService.MutationRequest mutation(
            String op, BondedCompanionProfile profile, long policyRevision,
            UUID owner, long nowMs
    ) {
        return new BondedCompanionTransitionService.MutationRequest(
                op, owner, profile.revision(), policyRevision, nowMs
        );
    }

    private BondedCompanionTransitionService.RosterCounts counts(int owned, int active) {
        return new BondedCompanionTransitionService.RosterCounts(owned, active);
    }

    private BondedCompanionSnapshot snapshot(String roleId, String name) {
        return BondedCompanionSnapshot.of(
                new com.alechilles.alecstamework.items
                        .CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                        UUID.fromString("20000000-0000-0000-0000-000000000001"),
                        null, -1, roleId, null, null, null,
                        name == null ? null : new com.alechilles.alecstamework.npc
                                .components.TameworkNpcNameComponent(
                                name, OWNER, -100L,
                                com.alechilles.alecstamework.npc.components
                                        .TameworkNpcNameComponent.NameSource.Player
                        ),
                        null, null, null, null, null, null, null, null,
                        100.0D, -200L
                ),
                Map.of()
        );
    }

    private void installPolicy(
            long revision, int maximumOwned, int maximumActive,
            long sessionSeconds, long cooldownSeconds,
            boolean capture, boolean provision, boolean summon,
            boolean dismiss, boolean revive
    ) throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "%s",
                                  "FamilyId": "hydragon:dragon",
                                  "AllowedRoles": ["%s", "Bonded_Miniwyvern"],
                                  "MaximumOwned": %d,
                                  "MaximumActive": %d,
                                  "SessionDurationSeconds": %d,
                                  "SummonCooldownSeconds": %d,
                                  "RevivePrice": {
                                    "ItemId": "Ingredient_Life_Essence",
                                    "Quantity": 2
                                  },
                                  "Features": {
                                    "Capture": %s,
                                    "Provision": %s,
                                    "Summon": %s,
                                    "Dismiss": %s,
                                    "Revive": %s
                                  }
                                }
                                """.formatted(
                                ROSTER, ROLE, maximumOwned, maximumActive,
                                sessionSeconds, cooldownSeconds,
                                capture, provision, summon, dismiss, revive
                        )),
                        new ExtraInfo()
                );
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "HyDragonRoster");
        assertTrue(registry.replace(List.of(config), revision).applied());
    }
}
