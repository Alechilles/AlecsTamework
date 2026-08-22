package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityIds;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.AvatarFlightActivityView;
import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySlot;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import com.alechilles.alecstamework.avatarflight.AvatarFlightCombatAbilityResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the native interaction bridge's single delegated root execution. */
class TameworkAvatarFlightCombatAbilityInteractionTest {
    private static final UUID PLAYER = UUID.fromString(
            "10000000-0000-0000-0000-000000000702");

    @AfterEach
    void clearActivityRuntime() {
        ActivityRuntime.clear();
    }

    @Test
    void unavailableAbilityDoesNotDelegate() {
        AtomicInteger delegationCount = new AtomicInteger();

        assertFalse(TameworkAvatarFlightCombatAbilityInteraction.delegate(
                AvatarFlightCombatAbilityResolver.Resolution.unavailable(),
                ignored -> delegationCount.incrementAndGet()));
        assertEquals(0, delegationCount.get());
    }

    @Test
    void configuredAbilityDelegatesItsRootExactlyOnce() {
        AtomicInteger delegationCount = new AtomicInteger();
        AtomicReference<String> delegatedRoot = new AtomicReference<>();

        assertTrue(TameworkAvatarFlightCombatAbilityInteraction.delegate(
                AvatarFlightCombatAbilityResolver.Resolution.available("Root_Test", 0.0),
                rootId -> {
                    delegationCount.incrementAndGet();
                    delegatedRoot.set(rootId);
                }));
        assertEquals(1, delegationCount.get());
        assertEquals("Root_Test", delegatedRoot.get());
    }

    @Test
    void acceptedCooldownAndDelegationPublishCombatAbilityAfterRootExecution() {
        List<ActivityView> activities = new ArrayList<>();
        ActivityRuntime.install(
                activities::add,
                new ManagedActivityConfigRegistry(
                        new PopulationGroupConfigRegistry()));
        AtomicInteger rootExecutions = new AtomicInteger();

        assertTrue(TameworkAvatarFlightCombatAbilityInteraction.delegateAccepted(
                true,
                AvatarFlightCombatAbilityResolver.Resolution.available(
                        "Root_Test", 5.0),
                ignored -> {
                    assertTrue(activities.isEmpty());
                    rootExecutions.incrementAndGet();
                },
                rootId -> TameworkAvatarFlightCombatAbilityInteraction
                        .publishAcceptedAbility(
                                PLAYER,
                                "NordicDrakeFlight",
                                AvatarFlightCombatAbilitySlot.ABILITY_2,
                                rootId)));

        assertEquals(1, rootExecutions.get());
        AvatarFlightActivityView activity = (AvatarFlightActivityView)
                activities.getFirst();
        assertEquals(ActivityIds.FLIGHT_COMBAT_ABILITY,
                activity.header().actionId());
        assertEquals(PLAYER, activity.playerId());
        assertEquals("NordicDrakeFlight", activity.flightConfigId());
        assertEquals("Ability2", activity.abilitySlot());
        assertEquals("Root_Test", activity.rootInteractionId());
    }

    @Test
    void cooldownRejectionAndFailedDelegationPublishNothing() {
        List<ActivityView> activities = new ArrayList<>();
        ActivityRuntime.install(
                activities::add,
                new ManagedActivityConfigRegistry(
                        new PopulationGroupConfigRegistry()));
        AvatarFlightCombatAbilityResolver.Resolution available =
                AvatarFlightCombatAbilityResolver.Resolution.available(
                        "Root_Test", 5.0);

        assertFalse(TameworkAvatarFlightCombatAbilityInteraction.delegateAccepted(
                false,
                available,
                ignored -> { throw new AssertionError("must not delegate"); },
                ignored -> { throw new AssertionError("must not publish"); }));
        assertThrows(IllegalStateException.class,
                () -> TameworkAvatarFlightCombatAbilityInteraction
                        .delegateAccepted(
                                true,
                                available,
                                ignored -> {
                                    throw new IllegalStateException("rejected");
                                },
                                rootId -> TameworkAvatarFlightCombatAbilityInteraction
                                        .publishAcceptedAbility(
                                                PLAYER,
                                                "NordicDrakeFlight",
                                                AvatarFlightCombatAbilitySlot.ABILITY_2,
                                                rootId)));
        assertTrue(activities.isEmpty());
    }

    @Test
    void invalidSlotFailsDecodingWithTheInteractionTypeInItsError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TameworkAvatarFlightCombatAbilityInteraction.CODEC.decode(
                        BsonDocument.parse("{ \"Slot\": \"Ability1\" }"), new ExtraInfo()));

        assertTrue(error.getMessage().contains("TameworkAvatarFlightCombatAbility"));
    }
}
