package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.DamagePolicyDecisionView;
import com.alechilles.alecstamework.damage.OwnerDamageFilterSystem;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.GlobalConfigScope;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.ParityExpectation;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.PolicyFixture;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageAdapterMatrix.Scenario;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.HytaleModuleScope;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.UniverseScope;
import com.alechilles.alecstamework.damage.SimpleClaimsDamageHytaleFixture.WorldFixture;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsDamageBridgeFixture;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.persistence.sqlite.LegacyNpcProfilesApi;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises identical SimpleClaims cases through both public production damage adapters. */
@ResourceLock("TwGlobalConfig-static-state")
@ResourceLock("SimpleClaims-damage-adapter-static-state")
class SimpleClaimsDamageAdapterParityTest {
    @TempDir
    Path tempDir;

    @TestFactory
    Collection<DynamicTest> runtimeAndApiAdapterMatrix() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Scenario scenario : SimpleClaimsDamageAdapterMatrix.scenarios()) {
            tests.add(DynamicTest.dynamicTest(scenario.name(), () -> assertScenario(scenario)));
        }
        return tests;
    }

    private void assertScenario(Scenario scenario) throws Exception {
        Path databasePath = tempDir.resolve(scenario.name());
        try (HytaleModuleScope ignoredModule = HytaleModuleScope.install();
             GlobalConfigScope ignoredConfig = GlobalConfigScope.install(scenario);
             PolicyFixture policyFixture = PolicyFixture.open(scenario);
             WorldFixture worldFixture = WorldFixture.open(scenario);
             UniverseScope ignoredUniverse = UniverseScope.install(
                     worldFixture.world(), scenario.apiTargetLive()
             );
             TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(databasePath, null)) {
            String profileId = insertProfile(runtime, worldFixture, scenario);

            Damage runtimeDamage = worldFixture.newDamage();
            OwnerDamageFilterSystem runtimeAdapter = new OwnerDamageFilterSystem(null, policyFixture.policy());
            worldFixture.invoke(runtimeAdapter, runtimeDamage);

            TameworkApiImpl apiAdapter = LegacyTameworkApiFactory.create(
                    runtime,
                    new TameworkEventBus(null),
                    null,
                    new InteractionExtensionRegistry(null),
                    new TraitEffectRegistry(
                            null,
                            new LegacyNpcProfilesApi(
                                    runtime.getNpcProfileRepository()
                            )
                    ),
                    policyFixture.policy()
            );
            DamagePolicyDecisionView apiDecision = apiAdapter.evaluateDamage(
                    profileId,
                    scenario.apiAttackerUuid()
            );

            assertRuntimeOutcome(scenario, runtimeDamage);
            assertApiOutcome(scenario, apiDecision);
            assertParityContract(scenario, runtimeDamage, apiDecision);
            assertNativeEvidence(scenario, policyFixture);
        }
    }

    private String insertProfile(TameworkPersistenceRuntime runtime,
                                 WorldFixture worldFixture,
                                 Scenario scenario) throws Exception {
        NpcProfileRepository repository = runtime.getNpcProfileRepository();
        if (scenario.liveOwnerEvidence()
                == SimpleClaimsDamageAdapterMatrix.LiveOwnerEvidence.COMMAND_LINK_ONLY
                || scenario.liveOwnerEvidence()
                == SimpleClaimsDamageAdapterMatrix.LiveOwnerEvidence.NPC_NAME_ONLY) {
            assertNotEquals(
                    SimpleClaimsDamageAdapterMatrix.ATTACKER,
                    scenario.persistedOwnerUuid(),
                    "fallback cases require conflicting persisted ownership"
            );
        }
        assertTrue(repository.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                worldFixture.targetUuid(),
                scenario.persistedOwnerUuid(),
                scenario.persistedOwnerUuid() != null ? "Fixture Owner" : null,
                scenario.targetTamed() ? "Tamed_DamageAdapter" : "Wild_DamageAdapter",
                "Damage Adapter Target",
                null,
                scenario.targetTamed(),
                null,
                null,
                null,
                null
        )));
        assertTrue(awaitUntil(() -> repository.resolveProfileId(worldFixture.targetUuid()) != null));
        return repository.resolveProfileId(worldFixture.targetUuid());
    }

    private void assertRuntimeOutcome(Scenario scenario, Damage damage) {
        assertEquals(
                scenario.expectedRuntimeCancelled(),
                damage.isCancelled(),
                "runtime cancellation"
        );
        assertEquals(
                scenario.expectedRuntimeAmount(),
                damage.getAmount(),
                0.0001f,
                "runtime amount"
        );
    }

    private void assertApiOutcome(Scenario scenario, DamagePolicyDecisionView decision) {
        assertEquals(scenario.expectedApiStatus(), decision.status(), "API status");
        assertEquals(scenario.expectedApiReason(), decision.reason(), "API reason");
        assertEquals(scenario.expectedApiAllowed(), decision.allowed(), "API allowed flag");
        assertEquals(scenario.apiAttackerUuid(), decision.attackerPlayerUuid(), "API attacker attribution");

        if (scenario.expectedApiStatus() == DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION) {
            assertNull(decision.claimAccess(), "owner precedence must short-circuit SimpleClaims");
            assertEquals(SimpleClaimsDamageAdapterMatrix.ATTACKER, decision.ownership().ownerUuid());
            return;
        }
        assertNotNull(decision.claimAccess(), "non-owner decisions expose their claim-policy result");
        if (scenario.expectedClaimPartyIdentity()) {
            assertEquals(
                    SimpleClaimsDamageBridgeFixture.CLAIM_PARTY_ID,
                    decision.claimAccess().claimPartyId(),
                    "claimed native results retain the resolved claim party"
            );
        } else {
            assertNull(decision.claimAccess().claimPartyId());
        }
    }

    private void assertParityContract(Scenario scenario,
                                      Damage runtimeDamage,
                                      DamagePolicyDecisionView apiDecision) {
        if (scenario.parityExpectation() == ParityExpectation.MATCH) {
            assertEquals(
                    runtimeDamage.isCancelled(),
                    !apiDecision.allowed(),
                    "runtime cancellation and API policy must agree"
            );
            return;
        }
        if (scenario.parityExpectation() == ParityExpectation.RUNTIME_EVENT_ALREADY_CANCELLED) {
            assertTrue(runtimeDamage.isCancelled());
            assertEquals(10.0f, runtimeDamage.getAmount(), 0.0001f,
                    "handle(...) must leave a previously-cancelled event untouched");
            assertFalse(apiDecision.allowed(),
                    "the API has no event cancellation state and therefore evaluates policy normally");
            return;
        }
        assertTrue(runtimeDamage.isCancelled(), "the live runtime target remains enforceable");
        assertTrue(apiDecision.allowed(), "unavailable API decisions fail open");
        assertEquals(DamagePolicyDecisionView.Status.UNAVAILABLE, apiDecision.status());
    }

    private void assertNativeEvidence(Scenario scenario, PolicyFixture fixture) {
        assertEquals(scenario.expectedNativeCalls(), fixture.nativeCalls(), "native policy calls");
        if (scenario.mode() == SimpleClaimsDamageBridgeFixture.Mode.PARTY_ALLY_ALLOWED) {
            assertEquals(
                    SimpleClaimsDamageBridgeFixture.ATTACKER_PARTY_ID,
                    fixture.lastPartyPermissionSubject()
            );
            assertNotEquals(SimpleClaimsDamageAdapterMatrix.ATTACKER, fixture.lastPartyPermissionSubject(),
                    "party permission must receive the resolved party ID, not the attacker UUID");
        }
        if (scenario.mode() == SimpleClaimsDamageBridgeFixture.Mode.MEMBER_ALLOWED
                || scenario.mode() == SimpleClaimsDamageBridgeFixture.Mode.MEMBER_DENIED
                || scenario.mode() == SimpleClaimsDamageBridgeFixture.Mode.PLAYER_ALLY_ALLOWED) {
            assertEquals(SimpleClaimsDamageAdapterMatrix.ATTACKER, fixture.lastPlayerPermissionSubject());
        }
    }

    private boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }
}
