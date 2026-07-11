package com.alechilles.alecstamework.interactions;

import com.alechilles.alecstamework.items.HytaleManagedCoopItemInteractionSession;
import com.alechilles.alecstamework.items.HytaleManagedCoopItemTargetResolver;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemEnvelopeCodec;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemAuthoringService;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemAuthoringService.AuthoringResult;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemAuthoringService.AuthoringStatus;
import com.alechilles.alecstamework.items.ManagedCoopContext;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.IntakeRequest;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.IntakeStart;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeRuntime;
import com.alechilles.alecstamework.items.ManagedCoopItemRetirementReceiptCodec;
import com.hypixel.hytale.builtin.adventure.farming.interactions.UseCaptureCrateInteraction;
import com.hypixel.hytale.builtin.tagset.TagSetPlugin;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

import static com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionValidation
        .canPlayerInteractWithEntity;

/**
 * Capture-crate interaction that delegates ordinary unmanaged targets to vanilla and exclusively
 * handles configured managed coops through the durable Tamework item-intake transaction.
 *
 * <p>Hytale 0.5.6's deprecated PlayerInteractEvent is no longer fired. Replacing the item
 * interaction is therefore the reliable pre-mutation boundary: managed targets never reach
 * {@link UseCaptureCrateInteraction#interactWithBlock}. A filled crate carrying canonical
 * Tamework evidence also fails closed at an unmanaged coop: vanilla clears all item metadata on
 * successful placement, which would discard the portable snapshot before its canonical profile
 * can be transitioned safely.</p>
 */
public final class TameworkManagedCoopCaptureCrateInteraction
        extends UseCaptureCrateInteraction {
    public static final BuilderCodec<TameworkManagedCoopCaptureCrateInteraction> CODEC =
            BuilderCodec.builder(
                    TameworkManagedCoopCaptureCrateInteraction.class,
                    TameworkManagedCoopCaptureCrateInteraction::new,
                    UseCaptureCrateInteraction.CODEC
            ).build();

    private final HytaleManagedCoopItemTargetResolver targets =
            new HytaleManagedCoopItemTargetResolver();
    private final HytaleCapturedNpcMetadataFactory metadataFactory =
            new HytaleCapturedNpcMetadataFactory();

    public TameworkManagedCoopCaptureCrateInteraction() {
        super();
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
        ItemStack item = context.getHeldItem();
        if (commandBuffer == null || item == null) {
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        if (hasRetirementReceipt(item)) {
            fail(context);
            return;
        }
        if (hasManagedEnvelope(item)) {
            if (hasDecodableVanillaCapturedResident(item)) {
                // Vanilla's filled-crate branch performs block dispatch; interactWithBlock then
                // selects the managed transaction or the explicit unmanaged evidence boundary.
                super.tick0(true, time, type, context, cooldownHandler);
            } else {
                fail(context);
            }
            return;
        }
        if (hasVanillaCapturedResident(item)) {
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        Ref<EntityStore> target = context.getTargetEntity();
        if (target == null) {
            super.tick0(true, time, type, context, cooldownHandler);
            return;
        }
        CaptureDisposition disposition = authorCapturedItem(
                commandBuffer, context, item, target);
        if (disposition == CaptureDisposition.DELEGATE_TO_VANILLA) {
            super.tick0(true, time, type, context, cooldownHandler);
        } else if (disposition == CaptureDisposition.FAILED_CLOSED) {
            fail(context);
        }
    }

    @Override
    protected void interactWithBlock(@Nonnull World world,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull InteractionType type,
                                     @Nonnull InteractionContext context,
                                     @Nullable ItemStack itemInHand,
                                     @Nonnull Vector3i targetBlock,
                                     @Nonnull CooldownHandler cooldownHandler) {
        ManagedCoopContext managed = targets.resolve(world, targetBlock);
        ItemStack held = context.getHeldItem();
        if (managed == null) {
            if (hasTameworkItemEvidence(held)) {
                // Do not let vanilla clear canonical evidence without a matching profile transition.
                fail(context);
            } else {
                super.interactWithBlock(
                        world, commandBuffer, type, context, itemInHand,
                        targetBlock, cooldownHandler);
            }
            return;
        }
        if (hasRetirementReceipt(held)) {
            fail(context);
            return;
        }
        if (!containsCapturedResident(held)) {
            super.interactWithBlock(
                    world, commandBuffer, type, context, itemInHand, targetBlock, cooldownHandler);
            return;
        }
        handleManaged(world, commandBuffer, context, managed, held);
    }

    private void handleManaged(World world,
                               CommandBuffer<EntityStore> commandBuffer,
                               InteractionContext context,
                               ManagedCoopContext managed,
                               @Nullable ItemStack held) {
        if (held == null || held.getItemId() == null || held.getItemId().isBlank()) {
            fail(context);
            return;
        }
        UUIDComponent identity = commandBuffer.getComponent(
                context.getEntity(), UUIDComponent.getComponentType());
        InventoryComponent.Hotbar hotbar = commandBuffer.getComponent(
                context.getEntity(), InventoryComponent.Hotbar.getComponentType());
        if (identity == null || identity.getUuid() == null || hotbar == null) {
            fail(context);
            return;
        }
        UUID playerUuid = identity.getUuid();
        short slot = hotbar.getActiveSlot();
        HytaleManagedCoopItemInteractionSession session =
                new HytaleManagedCoopItemInteractionSession(
                        world, playerUuid, slot, held.getItemId());
        ManagedCoopItemIntakeHandler handler = ManagedCoopItemIntakeRuntime.current();
        if (handler == null) {
            session.send("Managed coop intake is unavailable; the captured item was not consumed.");
            fail(context);
            return;
        }
        String rawEnvelope = held.getFromMetadataOrNull(
                ManagedCoopCapturedItemEnvelopeCodec.METADATA_KEY, Codec.STRING);
        IntakeStart start = handler.handle(new IntakeRequest(
                managed,
                playerUuid,
                slot,
                held.getItemId(),
                rawEnvelope,
                session,
                session
        ));
        context.getState().state = start.accepted()
                ? InteractionState.Finished
                : InteractionState.Failed;
    }

    private CaptureDisposition authorCapturedItem(
            CommandBuffer<EntityStore> commandBuffer,
            InteractionContext context,
            ItemStack item,
            Ref<EntityStore> target) {
        NPCEntity npc = commandBuffer.getComponent(target, NPCEntity.getComponentType());
        if (npc == null || commandBuffer.getComponent(
                target, DeathComponent.getComponentType()) != null) {
            return CaptureDisposition.DELEGATE_TO_VANILLA;
        }
        if (!acceptedGroupContains(npc.getRoleIndex())
                || !canPlayerInteractWithEntity(
                        context.getEntity(), commandBuffer, item, target)) {
            return CaptureDisposition.DELEGATE_TO_VANILLA;
        }
        PersistentModel model = commandBuffer.getComponent(
                target, PersistentModel.getComponentType());
        CapturedNPCMetadata vanilla = model != null
                ? metadataFactory.create(npc, model, fullIcon) : null;
        if (vanilla == null) {
            return CaptureDisposition.DELEGATE_TO_VANILLA;
        }
        UUID sourceUuid = resolveNpcUuid(commandBuffer, target, npc);
        String roleId = vanilla.getNpcNameKey();
        ManagedCoopCapturedItemAuthoringService authoring =
                ManagedCoopItemIntakeRuntime.currentAuthoring();
        if (authoring == null || sourceUuid == null) {
            return CaptureDisposition.FAILED_CLOSED;
        }
        Store<EntityStore> store = resolveStore(commandBuffer);
        if (store == null) {
            return CaptureDisposition.FAILED_CLOSED;
        }
        AuthoringResult prepared = authoring.prepare(target, store, sourceUuid, roleId);
        if (prepared.status() == AuthoringStatus.NOT_ELIGIBLE) {
            return CaptureDisposition.DELEGATE_TO_VANILLA;
        }
        if (!prepared.prepared()) {
            return CaptureDisposition.FAILED_CLOSED;
        }
        return replaceThenRemove(commandBuffer, context, item, target, vanilla, prepared);
    }

    private CaptureDisposition replaceThenRemove(
            CommandBuffer<EntityStore> commandBuffer,
            InteractionContext context,
            ItemStack interactionItem,
            Ref<EntityStore> target,
            CapturedNPCMetadata vanilla,
            AuthoringResult prepared) {
        InventoryComponent.Hotbar hotbar = commandBuffer.getComponent(
                context.getEntity(), InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null) {
            return CaptureDisposition.FAILED_CLOSED;
        }
        short activeSlot = hotbar.getActiveSlot();
        ItemStack current = hotbar.getActiveItem();
        if (current == null || !interactionItem.isStackableWith(current)) {
            return CaptureDisposition.FAILED_CLOSED;
        }
        ItemStack captured = current
                .withMetadata(CapturedNPCMetadata.KEYED_CODEC, vanilla)
                .withMetadata(
                        ManagedCoopCapturedItemEnvelopeCodec.METADATA_KEY,
                        Codec.STRING,
                        prepared.envelopeJson());
        if (!hotbar.getInventory().replaceItemStackInSlot(
                activeSlot, interactionItem, captured).succeeded()) {
            return CaptureDisposition.FAILED_CLOSED;
        }
        context.setHeldItem(captured);
        commandBuffer.removeEntity(target, RemoveReason.REMOVE);
        return CaptureDisposition.CAPTURED;
    }

    private boolean acceptedGroupContains(int roleIndex) {
        if (acceptedNpcGroupIndexes == null || acceptedNpcGroupIndexes.length == 0) {
            return false;
        }
        TagSetPlugin.TagSetLookup groups = TagSetPlugin.get(NPCGroup.class);
        if (groups == null) {
            return false;
        }
        for (int group : acceptedNpcGroupIndexes) {
            if (groups.tagInSet(group, roleIndex)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private UUID resolveNpcUuid(CommandBuffer<EntityStore> commandBuffer,
                                Ref<EntityStore> target,
                                NPCEntity npc) {
        if (npc.getUuid() != null) {
            return npc.getUuid();
        }
        UUIDComponent uuid = commandBuffer.getComponent(
                target, UUIDComponent.getComponentType());
        return uuid != null ? uuid.getUuid() : null;
    }

    @Nullable
    private Store<EntityStore> resolveStore(CommandBuffer<EntityStore> commandBuffer) {
        if (commandBuffer.getExternalData() == null) {
            return null;
        }
        World world = commandBuffer.getExternalData().getWorld();
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.assertThread();
        return store;
    }

    private boolean containsCapturedResident(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        String envelope = item.getFromMetadataOrNull(
                ManagedCoopCapturedItemEnvelopeCodec.METADATA_KEY, Codec.STRING);
        if (envelope != null) {
            return true;
        }
        try {
            return item.getFromMetadataOrNull(
                    CapturedNPCMetadata.KEY, CapturedNPCMetadata.CODEC) != null;
        } catch (RuntimeException exception) {
            return item.getMetadata() != null
                    && item.getMetadata().containsKey(CapturedNPCMetadata.KEY);
        }
    }

    private boolean hasVanillaCapturedResident(ItemStack item) {
        try {
            return item.getFromMetadataOrNull(
                    CapturedNPCMetadata.KEY, CapturedNPCMetadata.CODEC) != null;
        } catch (RuntimeException exception) {
            return item.getMetadata() != null
                    && item.getMetadata().containsKey(CapturedNPCMetadata.KEY);
        }
    }

    private boolean hasDecodableVanillaCapturedResident(ItemStack item) {
        try {
            return item.getFromMetadataOrNull(
                    CapturedNPCMetadata.KEY, CapturedNPCMetadata.CODEC) != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasTameworkItemEvidence(@Nullable ItemStack item) {
        return hasManagedEnvelope(item) || hasRetirementReceipt(item);
    }

    private boolean hasManagedEnvelope(@Nullable ItemStack item) {
        return hasStringMetadata(item, ManagedCoopCapturedItemEnvelopeCodec.METADATA_KEY);
    }

    private boolean hasRetirementReceipt(@Nullable ItemStack item) {
        return hasStringMetadata(item, ManagedCoopItemRetirementReceiptCodec.METADATA_KEY);
    }

    private boolean hasStringMetadata(@Nullable ItemStack item, String key) {
        if (item == null) {
            return false;
        }
        try {
            return item.getFromMetadataOrNull(key, Codec.STRING) != null;
        } catch (RuntimeException exception) {
            return item.getMetadata() != null && item.getMetadata().containsKey(key);
        }
    }

    private void fail(InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    private enum CaptureDisposition {
        CAPTURED,
        DELEGATE_TO_VANILLA,
        FAILED_CLOSED
    }
}
