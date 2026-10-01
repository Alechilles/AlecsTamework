package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/**
 * Finishes a capture that {@link CaptureFlow} reported CAPTURED (spec 8.2 steps 5 and 6): removes
 * the body and hands the item to the player. A capture into bonded storage gives no item; its
 * caller spent the source item before the commit. Safe to call from any thread; only stable ids
 * and immutable stacks cross threads, and live state is resolved inside world tasks.
 *
 * <p>On the body's world thread the record must still be ITEM (STORED for a capture into
 * storage) at the committed generation; when it is not, a newer change owns the companion: the
 * body (unregistered, and fenced by its stale generation) is removed and no item is handed over.
 * A body that vanished before the task ran moves an ITEM record to LOST; its snapshot is already
 * queued, so the owner can Recover it. A stored record needs no body and stays stored, and its
 * capture is still announced ({@link Handover#onCommitted}).
 *
 * <p>The snapshot the flow queued was taken before the commit, and the commit can wait on an
 * admission provider. So the body is snapshotted again here, in the task that removes it, and
 * that snapshot replaces the first at the same generation: whatever left the body in between
 * (an item taken out of its inventory, say) is not stored twice.
 *
 * <p>The item goes into the recorded hotbar slot when that slot still holds the exact source
 * stack, otherwise into the player's inventory, otherwise it is dropped where the body stood.
 */
public final class HytaleCaptureDelivery {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * One capture to finish. {@code item} is the presentation item without identity keys, or null
     * for a capture into bonded storage, which gives no item;
     * {@code expectedSource} is the exact stack the player held in {@code hotbarSlot}.
     * {@code onCaptured} runs on the body's world thread just before the body is removed (effects).
     * {@code capturedAtMs} is the wall-clock time the snapshot was taken. {@code entityPatch} is
     * the change the caller made to the entity document of the snapshot it committed (null for
     * none); it is applied again to the snapshot taken here. {@code onCommitted} announces the
     * capture (events, a message); it must touch no entity, because it runs on the body's world
     * thread before {@code onCaptured}, or, when the body of a stored capture has vanished, on
     * the calling thread.
     */
    public record Handover(@Nonnull Ref<EntityStore> body, @Nonnull CaptureItemKeys.Ref ref,
                           @Nullable ItemStack item, @Nonnull UUID playerUuid, int hotbarSlot,
                           @Nonnull ItemStack expectedSource, @Nullable Consumer<World> onCaptured,
                           long capturedAtMs, @Nullable UnaryOperator<BsonDocument> entityPatch,
                           @Nullable Runnable onCommitted) {
        public Handover {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(expectedSource, "expectedSource");
        }

        /** The record location this capture committed. */
        @Nonnull
        LocationKind committedKind() {
            return item == null ? LocationKind.STORED : LocationKind.ITEM;
        }
    }

    /** Runs on a world thread with the player's live state, resolved inside that task. */
    @FunctionalInterface
    public interface PlayerTask {
        void run(@Nonnull World world, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                 @Nonnull Player player);
    }

    private final CompanionIndex index;
    private final CompanionSnapshots snapshots;
    private final Consumer<SnapshotEnvelope> queueSnapshot;

    /**
     * @param snapshots     takes the snapshot of the body just before it is removed
     * @param queueSnapshot queues a snapshot write; {@code CompanionWriter::queueSnapshot}
     */
    public HytaleCaptureDelivery(@Nonnull CompanionIndex index, @Nonnull CompanionSnapshots snapshots,
                                 @Nonnull Consumer<SnapshotEnvelope> queueSnapshot) {
        this.index = Objects.requireNonNull(index, "index");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
    }

    /**
     * The snapshot data with {@code entityPatch} applied to its entity document; the snapshot's
     * own data when there is no patch. The input is not modified.
     */
    @Nonnull
    public static BsonDocument patched(@Nonnull SnapshotEnvelope snapshot,
                                       @Nullable UnaryOperator<BsonDocument> entityPatch) {
        if (entityPatch == null) {
            return snapshot.data();
        }
        BsonDocument data = new BsonDocument();
        data.putAll(snapshot.data());
        data.put("Entity", entityPatch.apply(CompanionSnapshots.entity(snapshot)));
        return data;
    }

    public void deliver(@Nonnull Handover handover) {
        World world = CompanionBodies.worldOf(handover.body());
        if (world == null || !world.isAlive()) {
            markLost(handover);
            return;
        }
        try {
            world.execute(() -> {
                try {
                    onBodyWorld(world, handover);
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.at(Level.WARNING).withCause(failure).log("Hand-over of captured companion %s failed",
                            handover.ref().profileId());
                }
            });
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            markLost(handover);
        }
    }

    private void onBodyWorld(World world, Handover handover) {
        Ref<EntityStore> body = handover.body();
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (!body.isValid() || body.getStore() != store) {
            markLost(handover);
            return;
        }
        UUID profileId = handover.ref().profileId();
        CompanionRecord record = index.get(profileId);
        if (record == null || record.location().kind() != handover.committedKind()
                || record.generation() != handover.ref().generation()) {
            LOGGER.at(Level.WARNING).log("Companion %s changed before its capture was handed over; no item was given",
                    profileId);
            CompanionBodies.removeOnOwnWorld(body);
            return;
        }
        refreshSnapshot(world, store, handover);
        TransformComponent transform = store.getComponent(body, TransformComponent.getComponentType());
        Vector3d dropAt = transform == null || transform.getPosition() == null
                ? null : new Vector3d(transform.getPosition());
        announce(handover);
        if (handover.onCaptured() != null) {
            try {
                handover.onCaptured().accept(world);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.at(Level.FINE).withCause(failure).log("Capture effects failed for companion %s", profileId);
            }
        }
        // The flow unregistered the body at commit, so its removal raises no LOST transition.
        CompanionBodies.removeOnOwnWorld(body);
        if (handover.item() == null) {
            // Stored, not handed over; the source item was spent before the commit.
            return;
        }
        ItemStack item = CaptureItemKeys.write(handover.item(), handover.ref());
        boolean scheduled = onPlayerWorld(handover.playerUuid(),
                (playerWorld, playerStore, playerRef, player) -> {
                    ItemStack left = place(playerStore, playerRef, player, handover, item);
                    if (left != null) {
                        dropOn(world, dropAt, left, profileId);
                    }
                },
                () -> dropOn(world, dropAt, item, profileId));
        if (!scheduled) {
            drop(world, dropAt, item, profileId);
        }
    }

    /**
     * Snapshots the body again and queues that snapshot at the committed generation, in place of
     * the one taken before the commit. Runs in the world task that removes the body, so nothing
     * can change the body in between. When it fails, the earlier snapshot stands.
     */
    private void refreshSnapshot(World world, Store<EntityStore> store, Handover handover) {
        UUID profileId = handover.ref().profileId();
        try {
            SnapshotEnvelope fresh = snapshots.capture(handover.body(), store, profileId, handover.ref().generation(),
                    world.getName(), CompanionWorldTime.gameTimeMs(store));
            if (fresh == null) {
                LOGGER.at(Level.WARNING).log(
                        "Captured companion %s could not be snapshotted again; its earlier snapshot stands", profileId);
                return;
            }
            queueSnapshot.accept(new SnapshotEnvelope(profileId, fresh.format(), fresh.generation(),
                    patched(fresh, handover.entityPatch())));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log(
                    "Captured companion %s could not be snapshotted again; its earlier snapshot stands", profileId);
        }
    }

    /** Runs {@link Handover#onCommitted}; a failure is logged and never stops the hand-over. */
    private static void announce(Handover handover) {
        if (handover.onCommitted() == null) {
            return;
        }
        try {
            handover.onCommitted().run();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Captured companion %s could not be announced",
                    handover.ref().profileId());
        }
    }

    /**
     * Puts the item in the recorded slot (compare-then-replace) or the inventory. Returns what is
     * left to drop, or null when everything was placed. When the source left its slot, one
     * matching item is taken from elsewhere in the inventory, so the spent source is not kept.
     */
    @Nullable
    private static ItemStack place(Store<EntityStore> store, Ref<EntityStore> ref, Player player,
                                   Handover handover, ItemStack item) {
        ItemContainer hotbar = player.getInventory() == null ? null : player.getInventory().getHotbar();
        int slot = handover.hotbarSlot();
        if (hotbar != null && slot >= 0 && slot < hotbar.getCapacity()
                && Objects.equals(hotbar.getItemStack((short) slot), handover.expectedSource())) {
            if (hotbar.setItemStackForSlot((short) slot, item).succeeded()) {
                return null;
            }
            // A slot filter refused it (a player who may not hold this companion, spec 8.14). The
            // source was spent on the capture, so take it; the item then goes to the inventory or
            // is dropped at the body.
            hotbar.replaceItemStackInSlot((short) slot, handover.expectedSource(), ItemStack.EMPTY);
        } else {
            takeMovedSource(player, handover.expectedSource(), handover.ref().profileId());
        }
        ItemStackTransaction given = Player.giveItem(item, ref, store);
        ItemStack remainder = given == null ? item : given.getRemainder();
        return ItemStack.isEmpty(remainder) ? null : remainder;
    }

    /**
     * Best-effort: takes one item matching the spent source (same item id and metadata, as the
     * slot check compares, any quantity) from the player's inventory, hotbar first.
     */
    private static void takeMovedSource(Player player, ItemStack source, UUID profileId) {
        if (ItemStack.isEmpty(source) || player.getInventory() == null) {
            return;
        }
        ItemContainer all = player.getInventory().getCombinedBackpackStorageHotbarFirst();
        for (short i = 0; all != null && i < all.getCapacity(); i++) {
            ItemStack held = all.getItemStack(i);
            if (!ItemStack.isEmpty(held) && held.isStackableWith(source)) {
                var taken = all.removeItemStackFromSlot(i, 1);
                if (taken != null && taken.succeeded()) {
                    return;
                }
            }
        }
        LOGGER.at(Level.FINE).log("The source item of the capture of companion %s was no longer held", profileId);
    }

    private static void dropOn(World world, @Nullable Vector3d at, ItemStack item, UUID profileId) {
        try {
            world.execute(() -> drop(world, at, item, profileId));
        } catch (RuntimeException notAccepting) {
            LOGGER.at(Level.WARNING).log("Could not drop the capture item of companion %s; its world stopped", profileId);
        }
    }

    /** Drops the item where the body stood. Runs on that world's thread. */
    private static void drop(World world, @Nullable Vector3d at, ItemStack item, UUID profileId) {
        if (at == null || !world.isAlive()) {
            LOGGER.at(Level.WARNING).log("Could not drop the capture item of companion %s; no position", profileId);
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(store, List.of(item), at, Rotation3f.IDENTITY);
        if (drops.length > 0) {
            store.addEntities(drops, AddReason.SPAWN);
        }
    }

    /**
     * Sets an ITEM record whose body vanished before the hand-over to LOST. The snapshot queued at
     * capture is no newer than the LOST record, so Recover can restore from it.
     */
    private void markLost(Handover handover) {
        CaptureItemKeys.Ref ref = handover.ref();
        if (handover.item() == null) {
            // The capture is committed and paid for; only its effects have no body to play on.
            LOGGER.at(Level.WARNING).log("The body of captured companion %s vanished before it was removed; "
                    + "the companion stays stored", ref.profileId());
            announce(handover);
            return;
        }
        boolean lost = index.atomically(() -> {
            CompanionRecord current = index.get(ref.profileId());
            return current != null && current.location().kind() == LocationKind.ITEM
                    && current.generation() == ref.generation()
                    && index.update(ref.profileId(), current.revision(), CompanionTransitions.lost(current, null,
                    CompanionTransitions.CAUSE_REMOVED, handover.capturedAtMs())).applied();
        });
        LOGGER.at(Level.WARNING).log("The body of captured companion %s vanished before the hand-over; %s",
                ref.profileId(), lost ? "it is now lost and can be recovered" : "its record had already changed");
    }

    /**
     * Runs {@code task} on the player's current world thread, found through the player's
     * {@link PlayerRef}. When the player is no longer there by then, {@code missing} runs on that
     * thread instead. Returns false when nothing could be scheduled (offline or no world).
     */
    public static boolean onPlayerWorld(@Nonnull UUID playerUuid, @Nonnull PlayerTask task,
                                        @Nullable Runnable missing) {
        Universe universe = Universe.get();
        PlayerRef playerRef = universe == null ? null : universe.getPlayer(playerUuid);
        UUID worldUuid = playerRef == null ? null : playerRef.getWorldUuid();
        World world = worldUuid == null ? null : universe.getWorld(worldUuid);
        if (world == null || !world.isAlive()) {
            return false;
        }
        try {
            world.execute(() -> {
                try {
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    Ref<EntityStore> ref = world.getEntityRef(playerUuid);
                    Player player = ref == null || !ref.isValid() || ref.getStore() != store
                            ? null : store.getComponent(ref, Player.getComponentType());
                    if (player == null) {
                        if (missing != null) {
                            missing.run();
                        }
                        return;
                    }
                    task.run(world, store, ref, player);
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.at(Level.WARNING).withCause(failure).log("A capture task for player %s failed", playerUuid);
                }
            });
            return true;
        } catch (RuntimeException notAccepting) {
            return false;
        }
    }
}
