package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.movement.NativeSwimRiderComponent;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import javax.annotation.Nonnull;

/** Q boost for a native aquatic mount, independent of held items and avatar flight. */
public final class TameworkNativeSwimBoostInteraction extends SimpleInteraction {
    public static final BuilderCodec<TameworkNativeSwimBoostInteraction> CODEC = BuilderCodec.builder(
            TameworkNativeSwimBoostInteraction.class, TameworkNativeSwimBoostInteraction::new,
            SimpleInteraction.CODEC).build();

    @Override @Nonnull
    public WaitForDataFrom getWaitForDataFrom() { return WaitForDataFrom.Server; }

    @Override
    protected void tick0(boolean firstRun, float time, @Nonnull InteractionType type,
                         @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldown) {
        if (firstRun) {
            var buffer = context.getCommandBuffer();
            var entity = context.getEntity();
            var componentType = NativeSwimRiderComponent.getComponentType();
            NativeSwimRiderComponent rider = buffer == null || entity == null || componentType == null
                    ? null : buffer.getComponent(entity, componentType);
            if (rider == null || rider.settings == null) {
                context.getState().state = InteractionState.Failed;
            } else {
                rider.boost = true;
                buffer.putComponent(entity, componentType, rider);
            }
            var plugin = Tamework.getInstance();
            if (plugin != null && plugin.isDebugRideEnabled()) {
                plugin.getLogger().atInfo().log("Tamework native swim boost: input received, activeRider=%s",
                        rider != null && rider.settings != null);
            }
        }
        super.tick0(firstRun, time, type, context, cooldown);
    }
}
