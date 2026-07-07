package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.avatarflight.AvatarFlightInteractionControlService;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import javax.annotation.Nonnull;

/**
 * Queues a single avatar-flight forward boost from an item ability interaction.
 */
public class TameworkFlightBoostInteraction extends SimpleInteraction {
    public static final BuilderCodec<TameworkFlightBoostInteraction> CODEC = BuilderCodec.builder(
            TameworkFlightBoostInteraction.class,
            TameworkFlightBoostInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation("Queues one avatar-flight forward boost pulse for the interacting player.")
            .build();

    protected TameworkFlightBoostInteraction() {
        super();
    }

    public TameworkFlightBoostInteraction(String id) {
        super(id);
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void tick0(boolean firstRun,
                         float time,
                         @Nonnull InteractionType type,
                         @Nonnull InteractionContext context,
                         @Nonnull CooldownHandler cooldownHandler) {
        if (firstRun && !AvatarFlightInteractionControlService.queueBoost(context, System.currentTimeMillis())) {
            context.getState().state = InteractionState.Failed;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    @Override
    protected void simulateTick0(boolean firstRun,
                                 float time,
                                 @Nonnull InteractionType type,
                                 @Nonnull InteractionContext context,
                                 @Nonnull CooldownHandler cooldownHandler) {
        if (context.getServerState() != null && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }
}
