package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopArtifactClaim;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopAuthor;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopTarget;
import com.alechilles.alecstamework.items.persistence.HytaleCapturedArtifactAdapter;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionValidation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Shared Hytale boundary for placing canonical captured items into managed coops.
 *
 * <p>The boundary returns {@link Result#NOT_MANAGED} whenever the caller must preserve its
 * ordinary item behavior. Once a canonical artifact targets a managed coop, missing exact
 * inventory evidence or runtime composition fails closed so no competing spawn or vanilla coop
 * mutation can consume the same source item.</p>
 */
public final class HytaleCapturedItemCoopInteractionService {
    private final HytaleManagedCoopItemTargetResolver targets;
    private final HytaleCapturedArtifactAdapter artifacts;

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
        CapturedArtifact artifact = artifact(held);
        if (CapturedItemCoopArtifactClaim.parse(artifact) == null) {
            return Result.NOT_MANAGED;
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
        return submit(source, target);
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

    @Nonnull
    private Result submit(
            CapturedItemCoopAuthor.Source source,
            CapturedItemCoopTarget target
    ) {
        CapturedItemCoopRuntime.Submission submission =
                CapturedItemCoopRuntime.current();
        if (submission == null) {
            return Result.FAILED_CLOSED;
        }
        try {
            CompletionStage<CapturedItemCoopAuthor.Outcome> started =
                    submission.submit(source, target);
            return started == null
                    ? Result.FAILED_CLOSED
                    : Result.STARTED;
        } catch (RuntimeException | LinkageError failure) {
            return Result.FAILED_CLOSED;
        }
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
