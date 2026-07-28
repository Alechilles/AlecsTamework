package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.items.HytaleCapturedItemCoopInteractionService;
import com.hypixel.hytale.builtin.adventure.farming.interactions.UseCaptureCrateInteraction;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Capture-crate cutover for current canonical artifacts placed into configured managed coops.
 *
 * <p>Unmanaged blocks and noncanonical artifacts retain vanilla behavior. A canonical artifact
 * aimed at a managed coop is submitted to the shared receipt-first operation before vanilla can
 * copy metadata into {@code CoopBlock} and clear the item. Receipt-marked artifacts always fail
 * closed and can never re-enter vanilla placement.</p>
 */
public final class TameworkManagedCoopCaptureCrateInteraction
        extends UseCaptureCrateInteraction {
    private static final int FAILED_LABEL_INDEX = 0;
    public static final String TYPE_ID =
            "TameworkManagedCoopCaptureCrate";
    public static final BuilderCodec<
            TameworkManagedCoopCaptureCrateInteraction> CODEC =
            BuilderCodec.builder(
                    TameworkManagedCoopCaptureCrateInteraction.class,
                    TameworkManagedCoopCaptureCrateInteraction::new,
                    UseCaptureCrateInteraction.CODEC
            ).build();

    private final HytaleCapturedItemCoopInteractionService intake =
            new HytaleCapturedItemCoopInteractionService();

    public TameworkManagedCoopCaptureCrateInteraction() {
        super();
    }

    @Override
    protected void tick0(
            boolean firstRun,
            float time,
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        if (firstRun && intake.receiptMarked(context.getHeldItem())) {
            fail(context);
            return;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull Vector3i targetBlock,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        HytaleCapturedItemCoopInteractionService.Result result =
                intake.attempt(world, commandBuffer, context);
        switch (result) {
            case NOT_MANAGED -> delegate(
                    world, commandBuffer, type, context, itemInHand,
                    targetBlock, cooldownHandler
            );
            case STARTED ->
                    context.getState().state = InteractionState.Finished;
            case FAILED_CLOSED -> fail(context);
        }
    }

    private void delegate(
            World world,
            CommandBuffer<EntityStore> commandBuffer,
            InteractionType type,
            InteractionContext context,
            @Nullable ItemStack itemInHand,
            Vector3i targetBlock,
            CooldownHandler cooldownHandler
    ) {
        super.interactWithBlock(
                world,
                commandBuffer,
                type,
                context,
                itemInHand,
                targetBlock,
                cooldownHandler
        );
    }

    private void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
        if (context.hasLabels()) {
            // SimpleInteraction compiles its failed route as the first label.
            context.jump(context.getLabel(FAILED_LABEL_INDEX));
        }
    }
}
