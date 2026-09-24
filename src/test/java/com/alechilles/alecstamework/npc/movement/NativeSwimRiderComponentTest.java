package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSwimRiderComponentTest {
    @Test
    void mountedBreathingRetainsAquaticRulesWithoutAllowingSolidBlockBreathing() {
        var rider = new NativeSwimRiderComponent();
        rider.breathesInWater = true;
        assertTrue(rider.canBreathe(BlockMaterial.Empty, Fluid.EMPTY_ID + 1));
        assertFalse(rider.canBreathe(BlockMaterial.Empty, Fluid.EMPTY_ID));
        rider.breathesInAir = true;
        assertTrue(rider.canBreathe(BlockMaterial.Empty, Fluid.EMPTY_ID));
        assertFalse(rider.canBreathe(BlockMaterial.Solid, Fluid.EMPTY_ID));
        rider.invulnerable = true;
        assertTrue(rider.canBreathe(BlockMaterial.Solid, Fluid.EMPTY_ID));
    }

    @Test
    void crouchDescendsWithoutWishMovementAndReleaseStopsDescent() {
        var rider = new NativeSwimRiderComponent();
        rider.captureInput(null, null, true, false, 1000);
        assertTrue(rider.isDescending(1000));
        rider.captureInput(null, null, null, null, 1400);
        assertTrue(rider.isDescending(1600));
        rider.captureInput(null, null, false, false, 1700);
        assertFalse(rider.isDescending(1700));
    }

    @Test
    void downwardWishDescendsButJumpAndStaleInputDoNot() {
        var rider = new NativeSwimRiderComponent();
        rider.captureInput(0.0, -1.0, false, false, 1000);
        assertTrue(rider.isDescending(1000));
        assertFalse(rider.isDescending(1501));
        rider.captureInput(null, null, null, true, 1600);
        assertFalse(rider.isDescending(1600));
        rider.captureInput(null, 0.0, false, false, 1700);
        assertFalse(rider.isDescending(1700));
    }

    @Test
    void reloadCanRestoreDisplacedAbilityWithoutResumingOldPropulsion() {
        var prior = new Interactions();
        prior.setInteractionId(InteractionType.Ability1, "Other_Ability");
        var rider = new NativeSwimRiderComponent();
        rider.settings = NativeSwimPhysics.Settings.defaults();
        var mounted = rider.bindAbility(prior);
        var loaded = NativeSwimRiderComponent.CODEC.decode(
                NativeSwimRiderComponent.CODEC.encode(rider, new ExtraInfo()), new ExtraInfo());
        assertEquals("Other_Ability", loaded.restoreAbility(mounted).getInteractionId(InteractionType.Ability1));
        assertNull(loaded.settings);
    }

    @Test
    void restoresPriorAbilityWithoutChangingOtherControls() {
        var prior = new Interactions();
        prior.setInteractionId(InteractionType.Ability1, "Other_Ability");
        prior.setInteractionId(InteractionType.Primary, "Other_Primary");
        var rider = new NativeSwimRiderComponent();
        var mounted = rider.bindAbility(prior);
        assertEquals(NativeSwimRiderComponent.BOOST_ROOT, mounted.getInteractionId(InteractionType.Ability1));
        assertEquals("Other_Ability", prior.getInteractionId(InteractionType.Ability1));
        var restored = rider.restoreAbility(mounted);
        assertEquals("Other_Ability", restored.getInteractionId(InteractionType.Ability1));
        assertEquals("Other_Primary", restored.getInteractionId(InteractionType.Primary));
    }

    @Test
    void releasesAbilityToHeldItemWhenNoOverrideExisted() {
        var rider = new NativeSwimRiderComponent();
        var restored = rider.restoreAbility(rider.bindAbility(null));
        assertNull(restored.getInteractionId(InteractionType.Ability1));
        assertFalse(restored.isOverrideAll());
    }

    @Test
    void cleanupPreservesNewerAbilityOwner() {
        var rider = new NativeSwimRiderComponent();
        var mounted = rider.bindAbility(null);
        mounted.setInteractionId(InteractionType.Ability1, "New_Ability");
        assertEquals("New_Ability", rider.restoreAbility(mounted).getInteractionId(InteractionType.Ability1));
    }
}
