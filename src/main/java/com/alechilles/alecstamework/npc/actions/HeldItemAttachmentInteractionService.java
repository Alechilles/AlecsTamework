package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.InteractionEffectContext;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionRequirementContext;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.systems.CompanionMovementSpeedSyncSystem;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Supplies built-in interaction gates and effects for player-applied model attachments. */
public final class HeldItemAttachmentInteractionService {
    public static final String MODEL_SUPPORT_REQUIREMENT_ID = "tamework:model_supports_attachment";
    public static final String SET_FROM_HELD_ITEM_EFFECT_ID = "tamework:set_attachment_from_held_item";
    public static final String EXCHANGE_AVAILABLE_REQUIREMENT_ID = "tamework:attachment_exchange_available";
    public static final String EXCHANGE_ATTACHMENT_EFFECT_ID = "tamework:exchange_attachment";

    @Nullable
    private final HytaleLogger logger;
    private final AttachmentExchangeInventoryService exchangeInventory = new AttachmentExchangeInventoryService();
    private final CompanionMovementSpeedSyncSystem movementSpeedSync = new CompanionMovementSpeedSyncSystem();

    public HeldItemAttachmentInteractionService(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    /** Checks that the current NPC model exposes the requested attachment slot and optional values. */
    public boolean modelSupportsAttachment(@Nonnull InteractionRequirementContext context,
                                           @Nonnull InteractionRequirementSpec spec) {
        String slotId = spec.param();
        if (slotId == null || slotId.isBlank()) {
            return false;
        }
        Map<String, Set<String>> options = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(context.npcRef(), context.store())
        );
        return supportsOptions(options, slotId.trim(), spec.values());
    }

    /** Checks whether the player can equip, replace, or remove one mapped attachment right now. */
    public boolean attachmentExchangeAvailable(@Nonnull InteractionRequirementContext context,
                                               @Nonnull InteractionRequirementSpec spec) {
        HeldItemAttachmentMapping mapping = HeldItemAttachmentMapping.parseExchange(spec);
        AttachmentExchangePlan plan = mapping != null
                ? resolveExchangePlan(
                        context.npcRef(),
                        context.store(),
                        context.player(),
                        context.heldItemId(),
                        mapping
                )
                : null;
        return plan != null && exchangeInventory.canApply(context.player(), plan);
    }

    /** Applies one mapped attachment selection and consumes one matching live held item. */
    public boolean setAttachmentFromHeldItem(@Nonnull InteractionEffectContext context,
                                             @Nonnull InteractionEffectSpec spec) {
        HeldItemAttachmentMapping mapping = HeldItemAttachmentMapping.parse(spec);
        if (mapping == null || context.player() == null) {
            return false;
        }
        ItemStack liveItem = PlayerInventoryAccess.getActiveHotbarItem(context.player());
        String liveItemId = liveItem != null && !liveItem.isEmpty() ? liveItem.getItemId() : null;
        if (liveItemId == null
                || liveItem.getQuantity() < 1
                || context.heldItemId() == null
                || !liveItemId.equals(context.heldItemId())) {
            return false;
        }
        String attachmentValue = mapping.resolve(liveItemId);
        if (attachmentValue == null) {
            return false;
        }
        return applyAtomicMutation(context, mapping.slotId(), attachmentValue, liveItemId);
    }

    /** Atomically exchanges one mapped attachment and its corresponding player item. */
    public boolean exchangeAttachment(@Nonnull InteractionEffectContext context,
                                      @Nonnull InteractionEffectSpec spec) {
        HeldItemAttachmentMapping mapping = HeldItemAttachmentMapping.parseExchange(spec);
        AttachmentExchangePlan plan = mapping != null
                ? resolveExchangePlan(
                        context.npcRef(),
                        context.store(),
                        context.player(),
                        context.heldItemId(),
                        mapping
                )
                : null;
        if (plan == null || !exchangeInventory.canApply(context.player(), plan)) {
            return false;
        }
        return applyAtomicExchange(context, plan);
    }

    private boolean applyAtomicMutation(@Nonnull InteractionEffectContext context,
                                        @Nonnull String slotId,
                                        @Nonnull String attachmentValue,
                                        @Nonnull String heldItemId) {
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null || !supports(context.npcRef(), context.store(), slotId, attachmentValue)) {
            return false;
        }

        TameworkAttachmentsComponent previousStored = context.store().getComponent(context.npcRef(), attachmentsType);
        Map<String, String> previousLive = CompanionModelAttachmentService.resolveCurrentAttachments(
                context.npcRef(),
                context.store()
        );
        Map<String, String> updatedSelections = buildUpdatedSelections(
                previousStored != null ? previousStored.getAttachmentIds() : null,
                previousLive,
                slotId,
                attachmentValue
        );
        if (updatedSelections == null) {
            return false;
        }
        boolean modelApplied = false;
        try {
            NPCEntity npc = context.store().getComponent(context.npcRef(), NPCEntity.getComponentType());
            modelApplied = CompanionModelAttachmentService.applyAttachments(
                    context.npcRef(),
                    npc,
                    context.store(),
                    updatedSelections
            );
            if (!modelApplied) {
                return false;
            }
            context.store().putComponent(
                    context.npcRef(),
                    attachmentsType,
                    new TameworkAttachmentsComponent(
                            previousStored != null ? previousStored.getConfigId() : null,
                            updatedSelections
                    )
            );
            if (InteractionItemConsumption.removeHeldItemQuantity(context.player(), heldItemId, 1)) {
                movementSpeedSync.refreshImmediately(context.npcRef(), context.store());
                return true;
            }
        } catch (RuntimeException | LinkageError error) {
            logFailure("Failed to apply held-item attachment mutation.", error);
        }

        if (modelApplied) {
            rollback(context.npcRef(), context.store(), attachmentsType, previousStored, previousLive);
        }
        return false;
    }

    private boolean applyAtomicExchange(@Nonnull InteractionEffectContext context,
                                        @Nonnull AttachmentExchangePlan plan) {
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null) {
            return false;
        }

        TameworkAttachmentsComponent previousStored = context.store().getComponent(context.npcRef(), attachmentsType);
        Map<String, String> previousLive = CompanionModelAttachmentService.resolveCurrentAttachments(
                context.npcRef(),
                context.store()
        );
        Map<String, String> updatedSelections = buildExchangeSelections(
                previousStored != null ? previousStored.getAttachmentIds() : null,
                previousLive,
                plan.slotId(),
                plan.targetValue()
        );
        boolean modelApplied = false;
        try {
            NPCEntity npc = context.store().getComponent(context.npcRef(), NPCEntity.getComponentType());
            modelApplied = CompanionModelAttachmentService.applyAttachments(
                    context.npcRef(),
                    npc,
                    context.store(),
                    updatedSelections
            );
            if (!modelApplied) {
                return false;
            }
            context.store().putComponent(
                    context.npcRef(),
                    attachmentsType,
                    new TameworkAttachmentsComponent(
                            previousStored != null ? previousStored.getConfigId() : null,
                            updatedSelections
                    )
            );
            if (exchangeInventory.apply(context.player(), plan)) {
                movementSpeedSync.refreshImmediately(context.npcRef(), context.store());
                return true;
            }
        } catch (RuntimeException | LinkageError error) {
            logFailure("Failed to apply attachment exchange mutation.", error);
        }

        if (modelApplied) {
            rollback(context.npcRef(), context.store(), attachmentsType, previousStored, previousLive);
        }
        return false;
    }

    @Nullable
    private AttachmentExchangePlan resolveExchangePlan(@Nonnull Ref<EntityStore> npcRef,
                                                       @Nonnull Store<EntityStore> store,
                                                       @Nullable Player player,
                                                       @Nullable String capturedHeldItemId,
                                                       @Nonnull HeldItemAttachmentMapping mapping) {
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null || player == null) {
            return null;
        }
        Map<String, Set<String>> options = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(npcRef, store)
        );
        Set<String> supportedValues = options.get(mapping.slotId());
        if (supportedValues == null) {
            return null;
        }
        TameworkAttachmentsComponent stored = store.getComponent(npcRef, attachmentsType);
        Map<String, String> storedSelections = stored != null ? stored.getAttachmentIds() : Map.of();
        Map<String, String> liveSelections = CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        String currentValue = resolveCurrentSelection(
                storedSelections,
                liveSelections,
                mapping.slotId()
        );
        ItemStack liveItem = PlayerInventoryAccess.getActiveHotbarItem(player);
        String liveItemId = liveItem != null && !liveItem.isEmpty() ? liveItem.getItemId() : null;
        int liveQuantity = liveItemId != null ? liveItem.getQuantity() : 0;
        return AttachmentExchangePlan.resolve(
                mapping,
                currentValue,
                liveItemId,
                liveQuantity,
                capturedHeldItemId,
                supportedValues
        );
    }

    private boolean supports(@Nonnull Ref<EntityStore> npcRef,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull String slotId,
                             @Nonnull String attachmentValue) {
        Map<String, Set<String>> options = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(npcRef, store)
        );
        Set<String> supportedValues = options.get(slotId);
        return supportedValues != null && supportedValues.contains(attachmentValue);
    }

    static boolean supportsOptions(@Nullable Map<String, Set<String>> options,
                                   @Nullable String slotId,
                                   @Nullable List<String> requiredValues) {
        if (options == null || slotId == null || slotId.isBlank()) {
            return false;
        }
        Set<String> supportedValues = options.get(slotId);
        if (supportedValues == null || supportedValues.isEmpty()) {
            return false;
        }
        if (requiredValues == null || requiredValues.isEmpty()) {
            return true;
        }
        for (String requiredValue : requiredValues) {
            if (requiredValue != null && supportedValues.contains(requiredValue.trim())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static Map<String, String> buildUpdatedSelections(@Nullable Map<String, String> storedSelections,
                                                      @Nullable Map<String, String> liveSelections,
                                                      @Nonnull String slotId,
                                                      @Nonnull String attachmentValue) {
        Map<String, String> base = storedSelections != null && !storedSelections.isEmpty()
                ? storedSelections
                : liveSelections;
        if (base != null && attachmentValue.equals(base.get(slotId))) {
            return null;
        }
        HashMap<String, String> updated = new HashMap<>();
        if (base != null) {
            updated.putAll(base);
        }
        updated.put(slotId, attachmentValue);
        return Map.copyOf(updated);
    }

    @Nullable
    static String resolveCurrentSelection(@Nullable Map<String, String> storedSelections,
                                          @Nullable Map<String, String> liveSelections,
                                          @Nonnull String slotId) {
        if (storedSelections != null && storedSelections.containsKey(slotId)) {
            return storedSelections.get(slotId);
        }
        return liveSelections != null ? liveSelections.get(slotId) : null;
    }

    @Nonnull
    static Map<String, String> buildExchangeSelections(@Nullable Map<String, String> storedSelections,
                                                       @Nullable Map<String, String> liveSelections,
                                                       @Nonnull String slotId,
                                                       @Nonnull String attachmentValue) {
        HashMap<String, String> updated = new HashMap<>();
        if (liveSelections != null) {
            updated.putAll(liveSelections);
        }
        if (storedSelections != null) {
            updated.putAll(storedSelections);
        }
        updated.put(slotId, attachmentValue);
        return Map.copyOf(updated);
    }

    private void rollback(@Nonnull Ref<EntityStore> npcRef,
                          @Nonnull Store<EntityStore> store,
                          @Nonnull ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType,
                          @Nullable TameworkAttachmentsComponent previousStored,
                          @Nonnull Map<String, String> previousLive) {
        try {
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            CompanionModelAttachmentService.applyAttachments(npcRef, npc, store, previousLive);
            if (previousStored == null) {
                store.tryRemoveComponent(npcRef, attachmentsType);
            } else {
                store.putComponent(npcRef, attachmentsType, previousStored.clone());
            }
        } catch (RuntimeException | LinkageError error) {
            logFailure("Failed to roll back held-item attachment mutation.", error);
        }
    }

    private void logFailure(@Nonnull String message, @Nonnull Throwable error) {
        if (logger != null) {
            logger.at(Level.WARNING).withCause(error).log(message);
        }
    }
}
