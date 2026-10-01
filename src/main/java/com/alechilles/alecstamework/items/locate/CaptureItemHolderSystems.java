package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.flow.CompanionTransitions;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.item.AdmissionCache;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Decision;
import com.alechilles.alecstamework.companion.store.CompanionWriter;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Kind;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainerUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Player inventory systems for capture items, all on the player's world thread. They keep the
 * captured-item locator current (player sightings on add and on capture-related changes, no tick
 * or player-list scan) and, when the companion index is ready, move a capture item's companion to
 * the player now holding it and keeps the pickup filters ({@link CaptureItemPickupFilter}) on
 * the player's current containers (spec 8.14).
 */
public final class CaptureItemHolderSystems {
    private CaptureItemHolderSystems() {
    }

    /** A player added to or removed from a world: sighting, join check and per-player cleanup. */
    public static final class Lifecycle extends RefSystem<EntityStore> {
        private final CapturedItemTracker tracker;
        @Nullable
        private final Transfers transfers;

        /** {@code transfers} is null when the companion index is not ready; only the locator runs then. */
        public Lifecycle(@Nonnull CapturedItemTracker tracker, @Nullable Transfers transfers) {
            this.tracker = Objects.requireNonNull(tracker, "tracker");
            this.transfers = transfers;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            Player player = buffer.getComponent(ref, Player.getComponentType());
            if (player == null || player.getUuid() == null) {
                return;
            }
            if (transfers != null) {
                transfers.joined(ref, player, store.getExternalData().getWorld(), buffer);
            }
            tracker.queue(CapturedItemTracker.entityHolder(
                    Kind.PLAYER, store.getExternalData().getWorld().getName(), player.getUuid()));
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer) {
            Player player = buffer.getComponent(ref, Player.getComponentType());
            if (player == null || player.getUuid() == null) {
                return;
            }
            if (transfers != null) {
                transfers.left(ref, player.getUuid());
            }
            tracker.index().unload(CapturedItemTracker.entityHolder(
                    Kind.PLAYER, store.getExternalData().getWorld().getName(), player.getUuid()));
        }
    }

    /** A player's inventory changed: only capture-related changes do anything. */
    public static final class Changes extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
        private final CapturedItemTracker tracker;
        @Nullable
        private final Transfers transfers;

        /** {@code transfers} is null when the companion index is not ready; only the locator runs then. */
        public Changes(@Nonnull CapturedItemTracker tracker, @Nullable Transfers transfers) {
            super(InventoryChangeEvent.class);
            this.tracker = Objects.requireNonNull(tracker, "tracker");
            this.transfers = transfers;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull InventoryChangeEvent event) {
            Player player = chunk.getComponent(index, Player.getComponentType());
            if (player == null || player.getUuid() == null) {
                return;
            }
            if (transfers != null) {
                // Every change, so a resized or replaced container is noticed without a capture item.
                transfers.containerSeen(chunk.getReferenceTo(index), player.getUuid(),
                        store.getExternalData().getWorld(), event);
            }
            if (!CapturedItemMetadata.affectsCapture(event.getTransaction())) {
                return;
            }
            tracker.queue(CapturedItemTracker.entityHolder(
                    Kind.PLAYER, store.getExternalData().getWorld().getName(), player.getUuid()));
            if (transfers != null) {
                transfers.changed(player, event);
            }
        }
    }

    /**
     * Ownership follows the holder (spec 8.14). One instance serves every world; each method runs
     * on the calling player's world thread, and the shared maps are concurrent because players in
     * different worlds are handled on different threads. Per-player entries are dropped when the
     * player leaves the world. It also installs the pickup filters on each container that does
     * not have them yet, while an item config blocks pickup: when the container is first seen or
     * replaced, on its next change, and for every tracked player when a settings change or config
     * reload turns blocking on ({@link #refreshFilters()}).
     */
    public static final class Transfers {
        static final String TRANSFER_REFUSED_KEY = "tamework.ui.notifications.captureItem.transferRefused";
        static final String ANOTHER_PLAYER_KEY = "tamework.ui.notifications.captureItem.anotherPlayer";
        private static volatile ComponentType<EntityStore, ? extends InventoryComponent>[] holderTypes;

        /** The decision taken under the index lock and the record it was taken on. */
        private record Attempt(@Nonnull Decision decision, @Nullable CompanionRecord before) {
        }

        /**
         * The inventory containers a player had when last seen, compared by identity, and the ones
         * that carry the pickup filter. The maps are used only on {@code world}'s thread.
         */
        private static final class Held {
            private final Ref<EntityStore> ref;
            private final World world;
            private final Map<ComponentType<EntityStore, ? extends InventoryComponent>, ItemContainer> containers =
                    new HashMap<>();
            private final Map<ComponentType<EntityStore, ? extends InventoryComponent>, ItemContainer> filtered =
                    new HashMap<>();

            private Held(Ref<EntityStore> ref, World world) {
                this.ref = ref;
                this.world = world;
            }
        }

        private final CompanionIndex index;
        private final CompanionWriter writer;
        private final CompanionAdmissionGate gate;
        private final ItemFeatureRegistry configs;
        private final AdmissionCache cache;
        private final TameworkUiMessageService messages = new TameworkUiMessageService();
        private final CaptureItemPickupFilter.Shared filters;
        private final Map<UUID, Held> held = new ConcurrentHashMap<>();

        /** {@code cache} also throttles the refusal notices; its owner clears it on config reload. */
        public Transfers(@Nonnull CompanionIndex index, @Nonnull CompanionWriter writer,
                         @Nonnull CompanionAdmissionGate gate, @Nonnull ItemFeatureRegistry configs,
                         @Nonnull AdmissionCache cache) {
            this.index = Objects.requireNonNull(index, "index");
            this.writer = Objects.requireNonNull(writer, "writer");
            this.gate = Objects.requireNonNull(gate, "gate");
            this.configs = Objects.requireNonNull(configs, "configs");
            this.cache = Objects.requireNonNull(cache, "cache");
            this.filters = new CaptureItemPickupFilter.Shared(index, gate, configs, cache, messages);
        }

        /** Join check: items that arrived while the player was away (spec 8.14). */
        void joined(@Nonnull Ref<EntityStore> ref, @Nonnull Player player, @Nonnull World world,
                    @Nonnull CommandBuffer<EntityStore> buffer) {
            held.put(player.getUuid(), new Held(ref, world));
            for (ComponentType<EntityStore, ? extends InventoryComponent> type : holderInventories()) {
                InventoryComponent inventory = buffer.getComponent(ref, type);
                if (inventory != null) {
                    installFilter(noteContainer(player.getUuid(), ref, world, type, inventory.getInventory()),
                            player.getUuid(), type);
                    checkContainer(player, inventory.getInventory());
                }
            }
        }

        void left(@Nonnull Ref<EntityStore> ref, @Nonnull UUID player) {
            // A player moving worlds may be added to the new world first; keep that world's entry.
            held.computeIfPresent(player, (id, entry) -> entry.ref.equals(ref) ? null : entry);
            cache.forgetPlayer(player);
        }

        /**
         * Any change to one of the player's inventories: records, by identity, the container it
         * uses now. The component's current container is used, since a resize replaces it and
         * leaves the event's one detached.
         *
         * When it has no pickup filter yet (first seen, it replaced the recorded one, or blocking
         * was off when it was last seen), installs one: filters are not saved, and a resize copies
         * items but not filters.
         */
        void containerSeen(@Nonnull Ref<EntityStore> ref, @Nonnull UUID player, @Nonnull World world,
                           @Nonnull InventoryChangeEvent event) {
            ComponentType<EntityStore, ? extends InventoryComponent> type = event.getComponentType();
            if (!isHolderInventory(type)) {
                return;
            }
            ItemContainer container = event.getInventory().getInventory();
            installFilter(noteContainer(player, ref, world, type, container), player, type);
        }

        /**
         * A runtime settings change or config reload, on any thread: when pickup blocking is on
         * now, installs the filters for every tracked player who lacks them. Each player is
         * handled on the thread of the world they were last seen in, and only the player id and
         * that world cross threads; the entry, ref and inventories are resolved inside the task.
         * A player who left or changed world meanwhile is skipped: the new world's join check
         * installs the filters.
         */
        public void refreshFilters() {
            if (!filters.anyBlocks()) {
                return;
            }
            for (Map.Entry<UUID, Held> tracked : held.entrySet()) {
                UUID player = tracked.getKey();
                World world = tracked.getValue().world;
                try {
                    world.execute(() -> installFilters(player, world));
                } catch (RuntimeException notQueued) {
                    // The world is stopping; its players get filters when they join a world again.
                }
            }
        }

        /** World thread: filters the current holder containers of a player still tracked in {@code world}. */
        private void installFilters(UUID player, World world) {
            Held entry = held.get(player);
            if (entry == null || entry.world != world || !entry.ref.isValid()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            for (ComponentType<EntityStore, ? extends InventoryComponent> type : holderInventories()) {
                InventoryComponent inventory = store.getComponent(entry.ref, type);
                if (inventory != null) {
                    entry.containers.put(type, inventory.getInventory());
                    installFilter(entry, player, type);
                }
            }
        }

        /** A capture-related change: checks the changed inventory's capture items. */
        void changed(@Nonnull Player player, @Nonnull InventoryChangeEvent event) {
            if (isHolderInventory(event.getComponentType())) {
                // The whole container rather than the transaction's slots: a move's transaction also
                // names the other container's slots, and the queued event can be older than the slots.
                checkContainer(player, event.getInventory().getInventory());
            }
        }

        /** Records the container a holder inventory uses now and returns the player's entry. */
        private Held noteContainer(UUID player, Ref<EntityStore> ref, World world,
                                   ComponentType<EntityStore, ? extends InventoryComponent> type,
                                   ItemContainer container) {
            Held entry = held.get(player);
            if (entry == null || !entry.ref.equals(ref)) {
                entry = new Held(ref, world);
                held.put(player, entry);
            }
            entry.containers.put(type, container);
            return entry;
        }

        /**
         * Sets the ADD filter on every slot of the recorded container of {@code type}, unless that
         * container already has it. Armor and Utility are not holder inventories, so they keep
         * their vanilla filters; Tool has none to replace. The engine skips an empty container; a
         * resize replaces it and the new one is filtered on its first change. Nothing is installed
         * while no item config blocks, so other mods' slot filters stay in place; the container
         * stays unfiltered until blocking turns on, and then {@link #refreshFilters()} or its next
         * change installs the filter.
         */
        private void installFilter(Held entry, UUID player,
                                   ComponentType<EntityStore, ? extends InventoryComponent> type) {
            ItemContainer container = entry.containers.get(type);
            if (container != null && entry.filtered.get(type) != container && filters.anyBlocks()) {
                ItemContainerUtil.trySetSlotFilters(container,
                        new CaptureItemPickupFilter(filters, player, entry.world));
                entry.filtered.put(type, container);
            }
        }

        private void checkContainer(Player player, @Nullable ItemContainer container) {
            if (container == null) {
                return;
            }
            String name = OwnerNameUtil.resolve(player);
            for (short slot = 0, capacity = container.getCapacity(); slot < capacity; slot++) {
                ItemStack stack = container.getItemStack(slot);
                CaptureItemKeys.Ref item = CaptureItemKeys.readIndexItem(stack);
                if (item != null) {
                    take(player, name, container, slot, stack, item);
                }
            }
        }

        private void take(Player player, @Nullable String name, ItemContainer container, short slot,
                          ItemStack stack, CaptureItemKeys.Ref item) {
            UUID holder = player.getUuid();
            // Lock-free pre-check: only a current item owned by someone else can move or be refused.
            if (CaptureItemOwnership.decide(index.get(item.profileId()), item.generation(), holder, null)
                    != Decision.TRANSFER || !follows(configs.getForFilledOrEmpty(stack.getItemId()))) {
                return;
            }
            Attempt attempt = index.atomically(() -> {
                CompanionRecord record = index.get(item.profileId());
                CompanionAdmission.Refusal refusal = record == null
                        ? null : gate.refuse(record, CaptureItemOwnership.asOwnedBy(record, holder, name));
                Decision decided = CaptureItemOwnership.decide(record, item.generation(), holder, refusal);
                if (decided == Decision.TRANSFER && !index.update(item.profileId(), record.revision(),
                        CompanionTransitions.ownerChanged(holder, name)).applied()) {
                    return new Attempt(Decision.IGNORE, record);
                }
                return new Attempt(decided, record);
            });
            if (attempt.decision() == Decision.TRANSFER) {
                // The rewrite fires another change event, which finds the holder already the owner.
                container.replaceItemStackInSlot(slot, stack,
                        stack.withMetadata(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING, holder));
                // The writer writes the old owner's file only after the new owner's (spec 7).
                writer.flushNow(holder);
                writer.flushNow(attempt.before().ownerUuid());
            } else if (attempt.decision() == Decision.REFUSE) {
                noticeRefused(player, item.profileId(), attempt.before());
            }
        }

        private void noticeRefused(Player player, UUID profileId, CompanionRecord record) {
            if (!cache.noticeDue(player.getUuid(), profileId)) {
                return;
            }
            String owner = record.ownerName() != null && !record.ownerName().isBlank()
                    ? record.ownerName() : LocalizedText.resolve(player, ANOTHER_PLAYER_KEY);
            messages.showKey(player, NotificationStyle.Warning, TRANSFER_REFUSED_KEY, owner);
        }

        /** {@code OwnershipFollowsHolder}, which applies only while capture keeps the owner. */
        static boolean follows(@Nullable ItemFeatureConfig config) {
            return config != null && config.isCaptureOwnershipFollowsHolder()
                    && !TameworkRuntimeSettings.current().captureClearsOwner();
        }

        /**
         * Hotbar, Storage, Backpack and Tool: where a player can hold a capture item. Built on first
         * use, after the entity module registered the types; a racing first use builds equal arrays.
         */
        @SuppressWarnings("unchecked")
        static ComponentType<EntityStore, ? extends InventoryComponent>[] holderInventories() {
            ComponentType<EntityStore, ? extends InventoryComponent>[] types = holderTypes;
            if (types == null) {
                types = new ComponentType[]{InventoryComponent.Hotbar.getComponentType(),
                        InventoryComponent.Storage.getComponentType(), InventoryComponent.Backpack.getComponentType(),
                        InventoryComponent.Tool.getComponentType()};
                holderTypes = types;
            }
            return types;
        }

        private static boolean isHolderInventory(ComponentType<EntityStore, ? extends InventoryComponent> type) {
            for (ComponentType<EntityStore, ? extends InventoryComponent> holder : holderInventories()) {
                if (holder == type) {
                    return true;
                }
            }
            return false;
        }
    }
}
