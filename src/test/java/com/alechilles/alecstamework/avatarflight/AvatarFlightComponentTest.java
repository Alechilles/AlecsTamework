package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightCombatAbilitySlot;
import com.hypixel.hytale.codec.ExtraInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarFlightComponentTest {
    private static final double EPSILON = 0.00001;

    @Test
    void clonePreservesVigourResourceState() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setVigourCharges(3.25);
        component.setLastVigourUpdateAtMs(-2500L);
        component.setVigourRechargeBlockedUntilMs(-1750L);
        component.setVigourRechargeMode("fast_flight");

        AvatarFlightComponent clone = component.clone();

        assertEquals(3.25, clone.getVigourCharges(), EPSILON);
        assertEquals(-2500L, clone.getLastVigourUpdateAtMs());
        assertEquals(-1750L, clone.getVigourRechargeBlockedUntilMs());
        assertEquals("FAST_FLIGHT", clone.getVigourRechargeMode());
    }

    @Test
    void clonePreservesFlightXpTrackerState() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setFlightXpQualifiedSeconds(12.5d);
        component.setFlightXpWindowAwardedXp(3.75d);
        component.setFlightXpWindowStartedAtMs(2000L);
        component.setFlightXpLastSampleAtMs(2500L);

        AvatarFlightComponent clone = component.clone();

        assertEquals(12.5d, clone.getFlightXpQualifiedSeconds(), EPSILON);
        assertEquals(3.75d, clone.getFlightXpWindowAwardedXp(), EPSILON);
        assertEquals(2000L, clone.getFlightXpWindowStartedAtMs());
        assertEquals(2500L, clone.getFlightXpLastSampleAtMs());
    }

    @Test
    void codecRoundTripPreservesFlightXpTrackerState() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setFlightXpQualifiedSeconds(12.5d);
        component.setFlightXpWindowAwardedXp(3.75d);
        component.setFlightXpWindowStartedAtMs(2000L);
        component.setFlightXpLastSampleAtMs(2500L);

        AvatarFlightComponent decoded = AvatarFlightComponent.CODEC.decode(
                AvatarFlightComponent.CODEC.encode(component, new ExtraInfo()), new ExtraInfo());

        assertEquals(12.5d, decoded.getFlightXpQualifiedSeconds(), EPSILON);
        assertEquals(3.75d, decoded.getFlightXpWindowAwardedXp(), EPSILON);
        assertEquals(2000L, decoded.getFlightXpWindowStartedAtMs());
        assertEquals(2500L, decoded.getFlightXpLastSampleAtMs());
    }

    @Test
    void maneuverLoadsAreFiniteAndClone() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setDiveLoad(0.75);
        component.setClimbLoad(Double.NaN);
        component.setNextLaunchAtMs(1500L);

        AvatarFlightComponent clone = component.clone();

        assertEquals(0.75, clone.getDiveLoad(), EPSILON);
        assertEquals(0.0, clone.getClimbLoad(), EPSILON);
        assertEquals(1500L, clone.getNextLaunchAtMs());
    }

    @Test
    void hudTelemetryIsFiniteClampedAndCloned() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setHudPitchRadians(Math.toRadians(30.0));
        component.setHudTargetSpeedRatio(1.5);

        AvatarFlightComponent clone = component.clone();

        assertEquals(Math.toRadians(30.0), clone.getHudPitchRadians(), EPSILON);
        assertEquals(1.0, clone.getHudTargetSpeedRatio(), EPSILON);

        component.setHudPitchRadians(Double.NaN);
        component.setHudTargetSpeedRatio(Double.NEGATIVE_INFINITY);

        assertEquals(0.0, component.getHudPitchRadians(), EPSILON);
        assertEquals(0.0, component.getHudTargetSpeedRatio(), EPSILON);
    }

    @Test
    void cloneAndClearPreserveLaunchAudioStateContract() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setNextLaunchChargeAudioAtMs(-250L);
        component.setLaunchFullChargeAudioPlayed(true);

        AvatarFlightComponent clone = component.clone();

        assertEquals(-250L, clone.getNextLaunchChargeAudioAtMs());
        assertTrue(clone.isLaunchFullChargeAudioPlayed());

        clone.clearLaunchAudioState();
        assertEquals(0L, clone.getNextLaunchChargeAudioAtMs());
        assertFalse(clone.isLaunchFullChargeAudioPlayed());
    }

    @Test
    void cloneAndClearPreserveAbilityAnimationStateContract() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setAbilityAnimationId("Dragon_Flap");
        component.setAbilityAnimationSlot("Movement");
        component.setAbilityAnimationKind("UPWARD_BOOST");
        component.setAbilityAnimationUntilMs(-250L);

        AvatarFlightComponent clone = component.clone();

        assertEquals("Dragon_Flap", clone.getAbilityAnimationId());
        assertEquals("Movement", clone.getAbilityAnimationSlot());
        assertEquals("UPWARD_BOOST", clone.getAbilityAnimationKind());
        assertEquals(-250L, clone.getAbilityAnimationUntilMs());

        clone.clearAbilityAnimationState();
        assertEquals("", clone.getAbilityAnimationId());
        assertEquals("Action", clone.getAbilityAnimationSlot());
        assertEquals("", clone.getAbilityAnimationKind());
        assertEquals(0L, clone.getAbilityAnimationUntilMs());
    }

    @Test
    void clonePreservesInventoryGuardState() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.setLockedHotbarSlot(3);
        component.setPreviousUtilitySlot(0);

        AvatarFlightComponent clone = component.clone();

        assertEquals(3, clone.getLockedHotbarSlot());
        assertEquals(0, clone.getPreviousUtilitySlot());
    }

    @Test
    void cloneAndClearPreserveGroundedMovementOverrideState() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);
        component.captureGroundedBaseSpeed(5.5);

        AvatarFlightComponent clone = component.clone();

        assertTrue(clone.isGroundedMoveSpeedApplied());
        assertEquals(5.5, clone.getOriginalGroundedBaseSpeed(), EPSILON);

        clone.clearGroundedBaseSpeed();
        assertFalse(clone.isGroundedMoveSpeedApplied());
        assertEquals(0.0, clone.getOriginalGroundedBaseSpeed(), EPSILON);
    }

    @Test
    void combatCooldownsAreIndependentPerSlotAndPersistThroughClone() {
        AvatarFlightComponent component = new AvatarFlightComponent("default", 1000L);

        assertTrue(component.tryStartCombatAbilityCooldown(
                AvatarFlightCombatAbilitySlot.ABILITY_2, 1_000L, 15.0));
        assertFalse(component.tryStartCombatAbilityCooldown(
                AvatarFlightCombatAbilitySlot.ABILITY_2, 15_999L, 15.0));
        assertTrue(component.tryStartCombatAbilityCooldown(
                AvatarFlightCombatAbilitySlot.ABILITY_3, 15_999L, 15.0));

        AvatarFlightComponent clone = component.clone();
        assertFalse(clone.tryStartCombatAbilityCooldown(
                AvatarFlightCombatAbilitySlot.ABILITY_2, 15_999L, 15.0));
        assertTrue(clone.tryStartCombatAbilityCooldown(
                AvatarFlightCombatAbilitySlot.ABILITY_2, 16_000L, 15.0));
    }
}
