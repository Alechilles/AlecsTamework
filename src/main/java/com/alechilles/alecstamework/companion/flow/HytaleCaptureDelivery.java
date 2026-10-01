package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
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
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Finishes a capture that {@link CaptureFlow} reported CAPTURED (spec 8.2 steps 5 and 6): removes
 * the body and hands the item to the player. Safe to call from any thread; only stable ids and
 * immutable stacks cross threads, and live state is resolved inside world tasks.
 *
 * <p>On the body's world thread the record must still be ITEM at the item's generation; when it
 * is not, a newer change owns the companion, so the body stays and no item is handed over. A body
 * that vanished before the task ran moves the record to LOST; its snapshot is already queued, so
 * the owner can Recover it.
 *
 * <p>The item goes into the recorded hotbar slot when that slot still holds the exact source
 * stack, otherwise into the player's inventory, otherwise it is dropped where the body stood.
 */
public final class HytaleCaptureDelivery {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * One capture to finish. {@code item} is the presentation item without identity keys;
     * {@code expectedSource} is the exact stack the player held in {@code hotbarSlot}.
     * {@code onCaptured} runs on the body's world thread just before the body is removed (effects).
     */
    public record Handover(@Nonnull Ref<EntityStore> body, @Nonnull CaptureItemKeys.Ref ref,
                           @Nonnull ItemStack item, @Nonnull UUID playerUuid, int hotbarSlot,
                           @Nonnull ItemStack expectedSource, @Nullable Consumer<World> onCaptured) {
        public Handover {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(playerUuid, "playerUuid");
            Objects.requireNonNull(expectedSource, "expectedSource");
        }
    }

    /** Runs on a world thread with the player's live state, resolved inside that task. */
    @FunctionalInterface
    public interface PlayerTask {
        void run(@Nonnull World world, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                 @Nonnull Player player);
    }

    private final CompanionIndex index;
    private final LongSupplier clock;

    public HytaleCaptureDelivery(@Nonnull CompanionIndex index, @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void deliver(@Nonnull Handover handover) {
        World world = CompanionBodies.worldOf(handover.body());
        if (world == null || !world.isAlive()) {
            markLost(handover.ref());
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
            markLost(handover.ref());
        }
    }

    private void onBodyWorld(World world, Handover handover) {
        Ref<EntityStore> body = handover.body();
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (!body.isValid() || body.getStore() != store) {
            markLost(handover.ref());
            return;
        }
        UUID profileId = handover.ref().profileId();
        CompanionRecord record = index.get(profileId);
        if (record == null || record.location().kind() != LocationKind.ITEM
                || record.generation() != handover.ref().generation()) {
            LOGGER.at(Level.WARNING).log("Companion %s changed before its capture was handed over; no item was given",
                    profileId);
            return;
        }
        TransformComponent transform = store.getComponent(body, TransformComponent.getComponentType());
        Vector3d dropAt = transform == null || transform.getPosition() == null
                ? null : new Vector3d(transform.getPosition());
        if (handover.onCaptured() != null) {
            try {
                handover.onCaptured().accept(world);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.at(Level.FINE).withCause(failure).log("Capture effects failed for companion %s", profileId);
            }
        }
        // The flow unregistered the body at commit, so its removal raises no LOST transition.
        CompanionBodies.removeOnOwnWorld(body);
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
     * Puts the item in the recorded slot (compare-then-replace) or the inventory. Returns what is
     * left to drop, or null when everything was placed.
     */
    @Nullable
    private static ItemStack place(Store<EntityStore> store, Ref<EntityStore> ref, Player player,
                                   Handover handover, ItemStack item) {
        ItemContainer hotbar = player.getInventory() == null ? null : player.getInventory().getHotbar();
        int slot = handover.hotbarSlot();
        if (hotbar != null && slot >= 0 && slot < hotbar.getCapacity()
                && Objects.equals(hotbar.getItemStack((short) slot), handover.expectedSource())) {
            hotbar.setItemStackForSlot((short) slot, item);
            return null;
        }
        ItemStackTransaction given = Player.giveItem(item, ref, store);
        ItemStack remainder = given == null ? item : given.getRemainder();
        return ItemStack.isEmpty(remainder) ? null : remainder;
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
    private void markLost(CaptureItemKeys.Ref ref) {
        long now = clock.getAsLong();
        boolean lost = index.atomically(() -> {
            CompanionRecord current = index.get(ref.profileId());
            return current != null && current.location().kind() == LocationKind.ITEM
                    && current.generation() == ref.generation()
                    && index.update(ref.profileId(), current.revision(), CompanionTransitions.lost(current, null,
                    CompanionTransitions.CAUSE_REMOVED, now)).applied();
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
