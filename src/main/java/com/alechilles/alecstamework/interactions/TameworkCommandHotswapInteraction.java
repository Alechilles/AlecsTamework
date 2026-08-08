package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.CommandHotswapAction;
import com.alechilles.alecstamework.items.CommandHotswapAssignmentStore;
import com.alechilles.alecstamework.items.CommandHotswapAssignmentStore.Slot;
import com.alechilles.alecstamework.items.CommandItemFeatureHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Dispatches the command assigned to one fixed command-item ability slot. */
public final class TameworkCommandHotswapInteraction extends SimpleInteraction {
    public static final BuilderCodec<TameworkCommandHotswapInteraction> CODEC = BuilderCodec.builder(
            TameworkCommandHotswapInteraction.class,
            TameworkCommandHotswapInteraction::new,
            SimpleInteraction.CODEC
    )
            .documentation("Dispatches the command assigned to this flute hotswap slot.")
            .<String>appendInherited(
                    new KeyedCodec<>("Slot", Codec.STRING),
                    (interaction, value) -> interaction.slot = parseSlot(value),
                    interaction -> interaction.slot.name(),
                    (interaction, parent) -> interaction.slot = parent.slot
            )
            .documentation("Fixed hotswap slot: Q, E, or R.")
            .add()
            .build();

    private Slot slot = Slot.Q;
    private final CommandHotswapAssignmentStore assignments = new CommandHotswapAssignmentStore();

    protected TameworkCommandHotswapInteraction() { }
    public TameworkCommandHotswapInteraction(String id) { super(id); }

    @Nonnull @Override
    public WaitForDataFrom getWaitForDataFrom() { return WaitForDataFrom.Server; }

    @Override
    protected void tick0(boolean firstRun, float time, @Nonnull InteractionType type,
                         @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (firstRun && !dispatch(context)) context.getState().state = InteractionState.Failed;
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    @Override
    protected void simulateTick0(boolean firstRun, float time, @Nonnull InteractionType type,
                                 @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (context.getServerState() != null && context.getServerState().state == InteractionState.Failed) {
            context.getState().state = InteractionState.Failed;
        }
        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    private boolean dispatch(InteractionContext context) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> playerRef = context.getEntity();
        ItemStack held = context.getHeldItem();
        Tamework plugin = Tamework.getInstance();
        CommandItemFeatureHandler handler = plugin == null ? null : plugin.getCommandItemFeatureHandler();
        if (buffer == null || playerRef == null || held == null || held.isEmpty() || handler == null) return false;
        Player player = buffer.getComponent(playerRef, Player.getComponentType());
        if (player == null) return false;
        if (CommandHotswapAction.isCycleGroup(assignments.read(held, slot))) {
            return persistGroupCycle(context, handler.cycleHotswapGroup(held));
        }
        buffer.run(store -> handler.handleHotswapUse(player, held, context.getTargetEntity(), slot));
        context.setHeldItem(held);
        return true;
    }

    private boolean persistGroupCycle(@Nonnull InteractionContext context, @Nonnull ItemStack updated) {
        ItemStack held = context.getHeldItem();
        if (updated == held) {
            context.setHeldItem(held);
            return true;
        }
        ItemContainer container = context.getHeldItemContainer();
        if (container == null) {
            return false;
        }
        ItemStackSlotTransaction transaction = container.setItemStackForSlot(
                context.getHeldItemSlot(), updated);
        if (transaction == null || !transaction.succeeded()) {
            return false;
        }
        context.setHeldItem(updated);
        return true;
    }

    private static Slot parseSlot(String value) {
        if (value != null) for (Slot candidate : Slot.values()) {
            if (candidate.name().equalsIgnoreCase(value.trim())) return candidate;
        }
        return Slot.Q;
    }
}
