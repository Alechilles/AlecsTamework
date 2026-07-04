package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.avatarflight.AvatarFlightInteractionControlService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import javax.annotation.Nonnull;

/**
 * Applies a short avatar-flight airbrake window from an item secondary interaction.
 */
public class TameworkFlightAirbrakeInteraction extends SimpleInteraction {
    private static final long DEFAULT_DURATION_MS = 350L;

    public static final BuilderCodec<TameworkFlightAirbrakeInteraction> CODEC = BuilderCodec.builder(
            TameworkFlightAirbrakeInteraction.class,
            TameworkFlightAirbrakeInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation("Activates avatar-flight airbrake input for the interacting player.")
            .<Long>appendInherited(
                    new KeyedCodec<>("DurationMs", Codec.LONG),
                    (interaction, value) -> interaction.durationMs = value == null ? DEFAULT_DURATION_MS : value,
                    interaction -> interaction.durationMs,
                    (interaction, parent) -> interaction.durationMs = parent.durationMs
            )
            .add()
            .build();

    private long durationMs = DEFAULT_DURATION_MS;

    protected TameworkFlightAirbrakeInteraction() {
        super();
    }

    public TameworkFlightAirbrakeInteraction(String id) {
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
        if (firstRun && !AvatarFlightInteractionControlService.activateAirbrake(
                context,
                System.currentTimeMillis(),
                Math.max(0L, durationMs)
        )) {
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
