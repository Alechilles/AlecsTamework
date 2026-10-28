package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.compat.runes.RuneInputRuntime;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Binds flight controls on the flying player's own {@link Interactions} component, so flight needs
 * no held item. The engine resolves an entity binding before the held item and before rune slots,
 * and the client mirrors that lookup.
 *
 * <p>The bindings are added and removed together with {@link AvatarFlightComponent}. Both components
 * are saved with the player, so they also stay in step across a save and load.</p>
 */
final class AvatarFlightEntityInput {
    private static final Map<InteractionType, String> ROOTS = Map.of(
            InteractionType.Primary, "Root_Tamework_Flight_Flap",
            InteractionType.Secondary, "Root_Tamework_Flight_Airbrake",
            InteractionType.Ability1, "Root_Tamework_Flight_Boost",
            InteractionType.Ability2, "Root_Tamework_Flight_Ability2",
            InteractionType.Ability3, "Root_Tamework_Flight_Ability3");

    private AvatarFlightEntityInput() {
    }

    /** Binds every flight input that is free or held by a Tamework command item. */
    static void bind(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        Interactions current = accessor.getComponent(ref, Interactions.getComponentType());
        Interactions updated = current == null ? new Interactions() : (Interactions) current.clone();
        boolean changed = false;
        for (Map.Entry<InteractionType, String> root : ROOTS.entrySet()) {
            String bound = updated.getInteractionId(root.getKey());
            if (bound == null || RuneInputRuntime.isRuneInputRoot(bound)) {
                updated.setInteractionId(root.getKey(), root.getValue());
                changed = true;
            }
        }
        if (changed) accessor.putComponent(ref, Interactions.getComponentType(), updated);
    }

    /** Removes only flight's own bindings; an input another owner holds is left alone. */
    static void unbind(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        Interactions current = accessor.getComponent(ref, Interactions.getComponentType());
        if (current == null) return;
        Interactions updated = null;
        for (Map.Entry<InteractionType, String> root : ROOTS.entrySet()) {
            if (!root.getValue().equals(current.getInteractionId(root.getKey()))) continue;
            if (updated == null) updated = (Interactions) current.clone();
            updated.removeInteractionId(root.getKey());
        }
        if (updated != null) accessor.putComponent(ref, Interactions.getComponentType(), updated);
    }
}
