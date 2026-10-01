package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopIntakeFlow;
import com.alechilles.alecstamework.companion.coop.HytaleCoopIntake;
import com.alechilles.alecstamework.companion.flow.HytaleCaptureDelivery;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopAuthor;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopTarget;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionValidation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Shared Hytale boundary for placing capture items into managed coops.
 *
 * <p>The boundary returns {@link Result#NOT_MANAGED} whenever the caller must preserve its
 * ordinary item behavior. Once a 5.0 capture item (profile id and generation) targets a managed
 * coop, missing exact inventory evidence or runtime composition fails closed so no competing
 * spawn or vanilla coop mutation can use the same source item. The intake itself is the
 * commit-first {@link CoopIntakeFlow}: the exact source slot is emptied only after the coop slot
 * is written.</p>
 */
public final class HytaleCapturedItemCoopInteractionService {
    private final HytaleManagedCoopItemTargetResolver targets;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final TameworkUiMessageService messages = new TameworkUiMessageService();

    public HytaleCapturedItemCoopInteractionService() {
        this(
                new HytaleManagedCoopItemTargetResolver(),
                new HytaleCapturedArtifactAdapter()
        );
    }

    HytaleCapturedItemCoopInteractionService(
            HytaleManagedCoopItemTargetResolver targets,
            HytaleCapturedArtifactAdapter artifacts
    ) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    /**
     * Attempts managed-coop intake using the exact block and inventory coordinate in the context.
     */
    @Nonnull
    public Result attempt(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionContext context
    ) {
        ItemStack held = context.getHeldItem();
        if (receiptMarked(held)) {
            return Result.FAILED_CLOSED;
        }
        var block = context.getTargetBlock();
        if (block == null) {
            return Result.NOT_MANAGED;
        }
        Vector3i position = new Vector3i(block.x, block.y, block.z);
        CapturedItemCoopTarget target = targets.resolve(world, position);
        if (target == null) {
            return Result.NOT_MANAGED;
        }
        CaptureItemKeys.Ref item = CaptureItemKeys.readIndexItem(held);
        if (item == null) {
            // An older capture item must not fall through to vanilla crate handling.
            return olderCaptureItem(held) ? Result.FAILED_CLOSED : Result.NOT_MANAGED;
        }
        CapturedArtifact artifact = artifact(held);
        if (artifact == null) {
            return Result.FAILED_CLOSED;
        }
        if (!InteractionValidation.canPlayerInteractWithBlock(
                context.getEntity(),
                commandBuffer,
                held,
                position
        )) {
            return Result.FAILED_CLOSED;
        }
        CapturedItemCoopAuthor.Source source = exactSource(
                target.worldKey(), commandBuffer, context, artifact
        );
        if (source == null) {
            return Result.FAILED_CLOSED;
        }
        return submit(world, source, target, item);
    }

    /** Capture metadata that is not a 5.0 index item: a 4.x item (snapshot id) or an alias-only one. */
    private static boolean olderCaptureItem(@Nullable ItemStack held) {
        if (held == null || held.isEmpty()) {
            return false;
        }
        if (CaptureItemKeys.read(held) != null) {
            return true;
        }
        try {
            return held.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID, Codec.STRING) != null
                    || held.getFromMetadataOrNull(TameworkMetadataKeys.TARGET_UUID, Codec.STRING) != null;
        } catch (RuntimeException | LinkageError unreadable) {
            return true;
        }
    }

    /** Returns whether an item is carrying an in-flight durable retirement receipt. */
    public boolean receiptMarked(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        try {
            return item.getFromMetadataOrNull(
                    CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                    Codec.STRING
            ) != null;
        } catch (RuntimeException | LinkageError invalidReceipt) {
            return true;
        }
    }

    /**
     * Starts the commit-first intake on the interaction's world thread. Refusals known now fail
     * closed with a message; later failures are reported to the player when the intake ends.
     */
    @Nonnull
    private Result submit(
            World world,
            CapturedItemCoopAuthor.Source source,
            CapturedItemCoopTarget target,
            CaptureItemKeys.Ref item
    ) {
        HytaleCoopIntake intake = HytaleCoopIntake.current();
        if (intake == null) {
            return Result.FAILED_CLOSED;
        }
        UUID actor = source.actorUuid();
        try {
            CompanionRecord record = intake.record(item.profileId());
            if (record == null || record.location().kind() != LocationKind.ITEM
                    || record.generation() != item.generation()) {
                warnLater(actor, "releaseProfileConflict");
                return Result.FAILED_CLOSED;
            }
            if (source.sourceArtifact().quantity() != 1 || !policyAllows(target, record, actor)) {
                warnLater(actor, "coopRejected");
                return Result.FAILED_CLOSED;
            }
            CompletableFuture<CoopIntakeFlow.Result> started = intake.offerItem(world, target.coopId(),
                    target.x(), target.y(), target.z(), target.maxResidents(), item,
                    () -> consumeLater(actor, source.inventoryPosition(), source.sourceArtifact()));
            if (started == null) {
                warnLater(actor, "coopRejected");
                return Result.FAILED_CLOSED;
            }
            started.thenAccept(result -> {
                String key = switch (result) {
                    case TAKEN_IN -> null;
                    case NOT_ELIGIBLE, CONFLICT -> "releaseProfileConflict";
                    case BUSY -> "coopRejected";
                    case COMMIT_FAILED, SLOT_FAILED -> "compensated";
                };
                if (key != null) {
                    warnLater(actor, key);
                }
            });
            return Result.STARTED;
        } catch (RuntimeException | LinkageError failure) {
            return Result.FAILED_CLOSED;
        }
    }

    /**
     * The coop's capture policy against the companion's record. A record without an owner counts
     * as untamed for {@code RequireTamed}: the tamed state lives in the stored snapshot, which is
     * not read here.
     */
    private static boolean policyAllows(CapturedItemCoopTarget target, CompanionRecord record, UUID actor) {
        UUID owner = record.ownerUuid();
        if (!target.acceptsRole(record.roleId())
                || (target.requireTamed() || target.requireOwner()) && owner == null) {
            return false;
        }
        return !target.ownerRestricted() || actor.equals(owner);
    }

    /** Empties the exact source slot on the player's world, only if it still holds the source stack. */
    private void consumeLater(UUID actor, CoopCapturedItemInventoryPosition position, CapturedArtifact source) {
        HytaleCaptureDelivery.onPlayerWorld(actor, (world, store, ref, player) -> {
            ItemContainer container = container(store, ref, position.section());
            if (container == null || position.slot() >= container.getCapacity()) {
                return;
            }
            short slot = (short) position.slot();
            ItemStack current = container.getItemStack(slot);
            if (artifacts.matches(current, source)) {
                container.replaceItemStackInSlot(slot, current, ItemStack.EMPTY);
            }
        }, null);
    }

    @Nullable
    private static ItemContainer container(Store<EntityStore> store, Ref<EntityStore> ref,
                                           CoopCapturedItemInventoryPosition.Section section) {
        InventoryComponent inventory = switch (section) {
            case HOTBAR -> store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            case STORAGE -> store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            case BACKPACK -> store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        };
        return inventory == null ? null : inventory.getInventory();
    }

    private void warnLater(UUID actor, String key) {
        HytaleCaptureDelivery.onPlayerWorld(actor, (world, store, ref, player) -> messages.showKey(player,
                NotificationStyle.Warning, "tamework.ui.notifications.spawner." + key), null);
    }

    @Nullable
    private CapturedItemCoopAuthor.Source exactSource(
            String worldKey,
            CommandBuffer<EntityStore> commandBuffer,
            InteractionContext context,
            CapturedArtifact artifact
    ) {
        UUIDComponent identity = commandBuffer.getComponent(
                context.getEntity(), UUIDComponent.getComponentType()
        );
        CoopCapturedItemInventoryPosition.Section section =
                sectionForEvidence(context.getHeldItemSectionId());
        ItemContainer container = exactContainer(
                section, commandBuffer, context
        );
        byte heldSlot = context.getHeldItemSlot();
        if (identity == null || identity.getUuid() == null
                || section == null || container == null
                || heldSlot == InventoryComponent.INACTIVE_SLOT_INDEX) {
            return null;
        }
        int localSlot = Byte.toUnsignedInt(heldSlot);
        if (localSlot >= container.getCapacity()) {
            return null;
        }
        ItemStack exact = container.getItemStack((short) localSlot);
        if (!artifacts.matches(exact, artifact)) {
            return null;
        }
        return new CapturedItemCoopAuthor.Source(
                identity.getUuid(),
                worldKey,
                new CoopCapturedItemInventoryPosition(
                        section, localSlot
                ),
                artifact
        );
    }

    @Nullable
    private ItemContainer exactContainer(
            @Nullable CoopCapturedItemInventoryPosition.Section section,
            CommandBuffer<EntityStore> commandBuffer,
            InteractionContext context
    ) {
        if (section == null || context.getHeldItemContainer() == null) {
            return null;
        }
        InventoryComponent inventory = switch (section) {
            case HOTBAR -> commandBuffer.getComponent(
                    context.getEntity(),
                    InventoryComponent.Hotbar.getComponentType()
            );
            case STORAGE -> commandBuffer.getComponent(
                    context.getEntity(),
                    InventoryComponent.Storage.getComponentType()
            );
            case BACKPACK -> commandBuffer.getComponent(
                    context.getEntity(),
                    InventoryComponent.Backpack.getComponentType()
            );
        };
        if (inventory == null
                || inventory.getInventory()
                != context.getHeldItemContainer()) {
            return null;
        }
        return inventory.getInventory();
    }

    @Nullable
    static CoopCapturedItemInventoryPosition.Section sectionForEvidence(
            int sectionId
    ) {
        return switch (sectionId) {
            case InventoryComponent.HOTBAR_SECTION_ID ->
                    CoopCapturedItemInventoryPosition.Section.HOTBAR;
            case InventoryComponent.STORAGE_SECTION_ID ->
                    CoopCapturedItemInventoryPosition.Section.STORAGE;
            case InventoryComponent.BACKPACK_SECTION_ID ->
                    CoopCapturedItemInventoryPosition.Section.BACKPACK;
            default -> null;
        };
    }

    @Nullable
    private CapturedArtifact artifact(@Nullable ItemStack held) {
        if (held == null || held.isEmpty()) {
            return null;
        }
        try {
            return artifacts.toArtifact(held);
        } catch (RuntimeException | LinkageError invalidArtifact) {
            return null;
        }
    }

    /** Result of attempting the optional managed-coop path. */
    public enum Result {
        NOT_MANAGED,
        STARTED,
        FAILED_CLOSED
    }
}
