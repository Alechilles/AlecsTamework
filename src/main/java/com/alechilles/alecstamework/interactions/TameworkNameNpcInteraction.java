package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.items.NamingFeatureHandler;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.items.NamingOverrides;
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
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Custom interaction handler used by naming items.
 */
public class TameworkNameNpcInteraction extends SimpleInteraction {
    private static final long SLOW_INTERACTION_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(20L);

    public static final BuilderCodec<TameworkNameNpcInteraction> CODEC = BuilderCodec.builder(
            TameworkNameNpcInteraction.class,
            TameworkNameNpcInteraction::new,
            SimpleInteraction.CODEC
    )
        .documentation("Starts the Tamework NPC naming flow using the held item.")
        .<String>appendInherited(
            new KeyedCodec<>("ConfigId", Codec.STRING),
            (interaction, value) -> interaction.configId = value,
            interaction -> interaction.configId,
            (interaction, parent) -> interaction.configId = parent.configId
        )
        .add()
        .<Boolean>appendInherited(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (interaction, value) -> interaction.requireTamed = value,
            interaction -> interaction.requireTamed,
            (interaction, parent) -> interaction.requireTamed = parent.requireTamed
        )
        .add()
        .<Boolean>appendInherited(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (interaction, value) -> interaction.requireOwner = value,
            interaction -> interaction.requireOwner,
            (interaction, parent) -> interaction.requireOwner = parent.requireOwner
        )
        .add()
        .<Boolean>appendInherited(
            new KeyedCodec<>("AllowUnownedWhenRequireOwner", Codec.BOOLEAN),
            (interaction, value) -> interaction.allowUnownedWhenRequireOwner = value,
            interaction -> interaction.allowUnownedWhenRequireOwner,
            (interaction, parent) -> interaction.allowUnownedWhenRequireOwner = parent.allowUnownedWhenRequireOwner
        )
        .add()
        .<Boolean>appendInherited(
            new KeyedCodec<>("AllowRename", Codec.BOOLEAN),
            (interaction, value) -> interaction.allowRename = value,
            interaction -> interaction.allowRename,
            (interaction, parent) -> interaction.allowRename = parent.allowRename
        )
        .add()
        .<Integer>appendInherited(
            new KeyedCodec<>("MinLength", Codec.INTEGER),
            (interaction, value) -> interaction.minLength = value,
            interaction -> interaction.minLength,
            (interaction, parent) -> interaction.minLength = parent.minLength
        )
        .add()
        .<Integer>appendInherited(
            new KeyedCodec<>("MaxLength", Codec.INTEGER),
            (interaction, value) -> interaction.maxLength = value,
            interaction -> interaction.maxLength,
            (interaction, parent) -> interaction.maxLength = parent.maxLength
        )
        .add()
        .<String>appendInherited(
            new KeyedCodec<>("AllowedChars", Codec.STRING),
            (interaction, value) -> interaction.allowedChars = value,
            interaction -> interaction.allowedChars,
            (interaction, parent) -> interaction.allowedChars = parent.allowedChars
        )
        .add()
        .<Boolean>appendInherited(
            new KeyedCodec<>("TrimWhitespace", Codec.BOOLEAN),
            (interaction, value) -> interaction.trimWhitespace = value,
            interaction -> interaction.trimWhitespace,
            (interaction, parent) -> interaction.trimWhitespace = parent.trimWhitespace
        )
        .add()
        .<Boolean>appendInherited(
            new KeyedCodec<>("ReplaceExisting", Codec.BOOLEAN),
            (interaction, value) -> interaction.replaceExisting = value,
            interaction -> interaction.replaceExisting,
            (interaction, parent) -> interaction.replaceExisting = parent.replaceExisting
        )
        .add()
        .<Boolean>appendInherited(
            new KeyedCodec<>("ConsumeItem", Codec.BOOLEAN),
            (interaction, value) -> interaction.consumeItem = value,
            interaction -> interaction.consumeItem,
            (interaction, parent) -> interaction.consumeItem = parent.consumeItem
        )
        .add()
        .<Double>appendInherited(
            new KeyedCodec<>("CooldownSeconds", Codec.DOUBLE),
            (interaction, value) -> interaction.cooldownSeconds = value,
            interaction -> interaction.cooldownSeconds,
            (interaction, parent) -> interaction.cooldownSeconds = parent.cooldownSeconds
        )
        .add()
        .build();

    private String configId;
    private Boolean requireTamed;
    private Boolean requireOwner;
    private Boolean allowUnownedWhenRequireOwner;
    private Boolean allowRename;
    private Integer minLength;
    private Integer maxLength;
    private String allowedChars;
    private Boolean trimWhitespace;
    private Boolean replaceExisting;
    private Boolean consumeItem;
    private Double cooldownSeconds;

    protected TameworkNameNpcInteraction() {
        super();
    }

    public TameworkNameNpcInteraction(String id) {
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
        if (!firstRun) {
            super.tick0(false, time, type, context, cooldownHandler);
            return;
        }
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> ref = context.getEntity();
        if (commandBuffer == null || ref == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null || heldItem.isEmpty()) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Tamework plugin = Tamework.getInstance();
        NamingFeatureHandler handler = plugin != null ? plugin.getNamingFeatureHandler() : null;
        if (handler == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Ref<EntityStore> targetEntity = context.getTargetEntity();
        if (targetEntity == null) {
            context.getState().state = InteractionState.Failed;
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }

        Integer cooldownMs = null;
        if (cooldownSeconds != null) {
            cooldownMs = (int) Math.round(cooldownSeconds * 1000.0);
        }
        NamingOverrides overrides = new NamingOverrides(
                requireTamed,
                requireOwner,
                allowUnownedWhenRequireOwner,
                allowRename,
                minLength,
                maxLength,
                allowedChars,
                trimWhitespace,
                replaceExisting,
                consumeItem,
                cooldownMs
        );

        boolean debugLag = plugin != null && plugin.isDebugLagEnabled();
        long startedNs = debugLag ? System.nanoTime() : 0L;
        commandBuffer.run(store -> handler.beginNaming(
                player,
                heldItem,
                targetEntity,
                configId,
                overrides
        ));
        if (debugLag) {
            logSlowInteraction(plugin, startedNs, player, heldItem, targetEntity);
        }
        context.setHeldItem(heldItem);
        super.tick0(true, time, type, context, cooldownHandler);
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

    private void logSlowInteraction(Tamework plugin,
                                    long startedNs,
                                    Player player,
                                    ItemStack heldItem,
                                    Ref<EntityStore> targetEntity) {
        if (plugin == null || startedNs <= 0L || plugin.getLogger() == null) {
            return;
        }
        long elapsedNs = System.nanoTime() - startedNs;
        if (elapsedNs < SLOW_INTERACTION_THRESHOLD_NS) {
            return;
        }
        double elapsedMs = elapsedNs / 1_000_000.0;
        String playerName = player != null ? OwnerNameUtil.resolve(player) : "<unknown>";
        String itemId = heldItem != null ? heldItem.getItemId() : "<none>";
        plugin.getLogger().at(Level.WARNING).log(
                "Tamework lag probe: naming interaction took "
                        + elapsedMs
                        + "ms (player="
                        + playerName
                        + ", item="
                        + itemId
                        + ", configId="
                        + configId
                        + ", target="
                        + targetEntity
                        + ")."
        );
    }
}
