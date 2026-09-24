package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSwimRiderComponentTest {
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
