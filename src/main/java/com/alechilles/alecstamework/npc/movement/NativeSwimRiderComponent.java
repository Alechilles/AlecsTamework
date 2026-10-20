package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Native rider propulsion state. Saves the previous Q binding for cleanup after a reload. */
public final class NativeSwimRiderComponent implements Component<EntityStore> {
    public static final String BOOST_ROOT = "Tamework_Native_Swim_Boost";
    public static final BuilderCodec<NativeSwimRiderComponent> CODEC = BuilderCodec.builder(
            NativeSwimRiderComponent.class, NativeSwimRiderComponent::new)
            .append(new KeyedCodec<>("PreviousAbility", Codec.STRING),
                    (c, v) -> c.previousAbility = v, c -> c.previousAbility).add().build();
    private static ComponentType<EntityStore, NativeSwimRiderComponent> type;
    private String previousAbility = "";
    public java.util.UUID mountUuid;
    public NativeSwimPhysics.Settings settings;
    public boolean breathesInAir;
    public boolean breathesInWater;
    public boolean invulnerable;
    public double speed;
    public double cooldown;
    public double forward;
    public long lastInputMs;
    public boolean boost;
    public boolean crouching;
    public boolean jumping;
    public double verticalWish;
    public long lastMovementPacketMs;

    /** Movement states are optional deltas, so omission keeps the last observed held state. */
    public void captureInput(Double forward, Double vertical, Boolean crouching, Boolean jumping, long now) {
        if (forward != null && Double.isFinite(forward)) {
            this.forward = Math.max(-1.0, Math.min(1.0, forward));
            lastInputMs = now;
        }
        if (vertical != null && Double.isFinite(vertical)) verticalWish = vertical;
        if (crouching != null) this.crouching = crouching;
        if (jumping != null) this.jumping = jumping;
        lastMovementPacketMs = now;
    }

    public boolean isDescending(long now) {
        return now - lastMovementPacketMs <= 500 && !jumping && (crouching || verticalWish < -0.1);
    }

    /** Matches Role.canBreathe while the native mount is parked in Empty_Role. */
    public boolean canBreathe(BlockMaterial material, int fluidId) {
        if (invulnerable) return true;
        if (fluidId != Fluid.EMPTY_ID) return breathesInWater;
        return material == BlockMaterial.Empty && breathesInAir;
    }

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(
                NativeSwimRiderComponent.class, "TameworkNativeSwimRider", CODEC);
    }

    public static ComponentType<EntityStore, NativeSwimRiderComponent> getComponentType() { return type; }

    /** Installs only Q; all other entity and held-item interactions remain available. */
    public Interactions bindAbility(Interactions existing) {
        Interactions updated = existing == null ? new Interactions() : (Interactions) existing.clone();
        String prior = updated.getInteractionId(InteractionType.Ability1);
        previousAbility = prior == null || BOOST_ROOT.equals(prior) ? "" : prior;
        updated.setInteractionId(InteractionType.Ability1, BOOST_ROOT);
        return updated;
    }

    /** Restores our override without overwriting a newer owner's change. */
    public Interactions restoreAbility(Interactions existing) {
        if (existing == null || !BOOST_ROOT.equals(existing.getInteractionId(InteractionType.Ability1))) {
            return existing;
        }
        Interactions restored = (Interactions) existing.clone();
        if (previousAbility == null || previousAbility.isEmpty()) {
            restored.removeInteractionId(InteractionType.Ability1);
        } else {
            restored.setInteractionId(InteractionType.Ability1, previousAbility);
        }
        return restored;
    }

    @Override @Nonnull
    public NativeSwimRiderComponent clone() {
        NativeSwimRiderComponent copy = new NativeSwimRiderComponent();
        copy.previousAbility = previousAbility;
        copy.mountUuid = mountUuid;
        copy.settings = settings;
        copy.breathesInAir = breathesInAir;
        copy.breathesInWater = breathesInWater;
        copy.invulnerable = invulnerable;
        copy.speed = speed;
        copy.cooldown = cooldown;
        copy.forward = forward;
        copy.lastInputMs = lastInputMs;
        copy.boost = boost;
        copy.crouching = crouching;
        copy.jumping = jumping;
        copy.verticalWish = verticalWish;
        copy.lastMovementPacketMs = lastMovementPacketMs;
        return copy;
    }
}
