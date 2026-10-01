package com.alechilles.alecstamework.items.locate;

import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.item.AdmissionCache;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Decision;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The ADD filter on one player's Hotbar, Storage, Backpack and Tool slots (spec 8.14,
 * {@code Capture.BlockIneligibleHolders}): refuses a capture item whose companion would move to
 * this player when the player could not take ownership of it.
 *
 * <p>The engine runs filters under the container's write lock, usually but not always on the
 * world thread. So {@link #test} reads only the lock-free index, the item config and the
 * {@link AdmissionCache}; it touches no ECS state and calls no admission rule. A refusal queues a
 * follow-up on the player's world (resolved there from the stored UUID) that evaluates a missing
 * decision, sends the throttled notice and, at most once a second, marks the inventories dirty
 * so the client drops a predicted move. A refused add fires no change event, so nothing else
 * would do this.</p>
 */
public final class CaptureItemPickupFilter implements SlotFilter {
    static final String PICKUP_BLOCKED_KEY = "tamework.ui.notifications.captureItem.pickupBlocked";

    /** State shared by every player's filter; owned by {@link CaptureItemHolderSystems.Transfers}. */
    static final class Shared {
        private final CompanionIndex index;
        private final CompanionAdmissionGate gate;
        private final ItemFeatureRegistry configs;
        private final AdmissionCache cache;
        private final TameworkUiMessageService messages;
        /** Whether any item config blocks, for the config map it was computed from. */
        private volatile Blocking blocking;

        private record Blocking(Map<String, ItemFeatureConfig> configs, boolean any) {
        }

        Shared(@Nonnull CompanionIndex index, @Nonnull CompanionAdmissionGate gate,
               @Nonnull ItemFeatureRegistry configs, @Nonnull AdmissionCache cache,
               @Nonnull TameworkUiMessageService messages) {
            this.index = Objects.requireNonNull(index, "index");
            this.gate = Objects.requireNonNull(gate, "gate");
            this.configs = Objects.requireNonNull(configs, "configs");
            this.cache = Objects.requireNonNull(cache, "cache");
            this.messages = Objects.requireNonNull(messages, "messages");
        }

        /**
         * True when at least one item config has {@code OwnershipFollowsHolder} and
         * {@code BlockIneligibleHolders} on and capture keeps the owner, so filters are worth
         * installing. Otherwise no filter is installed and another mod's slot filters are left
         * alone. The config scan is redone after each item config reload (a new config map).
         */
        boolean anyBlocks() {
            Map<String, ItemFeatureConfig> current = configs.snapshot();
            Blocking known = blocking;
            if (known == null || known.configs() != current) {
                boolean any = false;
                for (ItemFeatureConfig config : current.values()) {
                    if (config != null && config.isCaptureOwnershipFollowsHolder()
                            && config.isCaptureBlockIneligibleHolders()) {
                        any = true;
                        break;
                    }
                }
                known = new Blocking(current, any);
                blocking = known;
            }
            return known.any() && !TameworkRuntimeSettings.current().captureClearsOwner();
        }
    }

    private final Shared shared;
    private final UUID player;
    private final World world;

    /** {@code world} is the world the player was in when the filter was installed. */
    CaptureItemPickupFilter(@Nonnull Shared shared, @Nonnull UUID player, @Nonnull World world) {
        this.shared = Objects.requireNonNull(shared, "shared");
        this.player = Objects.requireNonNull(player, "player");
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public boolean test(FilterActionType action, ItemContainer container, short slot, @Nullable ItemStack stack) {
        try {
            if (action != FilterActionType.ADD) {
                return true;
            }
            CaptureItemKeys.Ref item = CaptureItemKeys.readIndexItem(stack);
            if (item == null) {
                return true;
            }
            // A stale copy, the owner's own item and an unowned capture move nothing, so they pass.
            CompanionRecord record = shared.index.get(item.profileId());
            if (CaptureItemOwnership.decide(record, item.generation(), player, null) != Decision.TRANSFER) {
                return true;
            }
            if (shared.cache.get(player, AdmissionCache.family(record)) == AdmissionCache.Cached.ALLOW) {
                return true;
            }
            ItemFeatureConfig config = shared.configs.getForFilledOrEmpty(stack.getItemId());
            if (!CaptureItemHolderSystems.Transfers.follows(config) || !config.isCaptureBlockIneligibleHolders()) {
                return true;
            }
            queueFollowUp(item);
            return false;
        } catch (RuntimeException unexpected) {
            // Never break an inventory operation; the holder systems still refuse the transfer.
            return true;
        }
    }

    private void queueFollowUp(CaptureItemKeys.Ref item) {
        // At most one queued per player and profile; ground pickup retries 4 times a second.
        if (!shared.cache.followUpDue(player, item.profileId())) {
            return;
        }
        try {
            world.execute(() -> {
                try {
                    followUp(item);
                } finally {
                    shared.cache.followUpDone(player, item.profileId());
                }
            });
        } catch (RuntimeException notQueued) {
            shared.cache.followUpDone(player, item.profileId());
        }
    }

    /** On the player's world thread. */
    private void followUp(CaptureItemKeys.Ref item) {
        Ref<EntityStore> ref = world.getEntityRef(player);
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity == null) {
            return;
        }
        if (!admitted(item) && shared.cache.noticeDue(player, item.profileId())) {
            shared.messages.showKey(entity, NotificationStyle.Warning, PICKUP_BLOCKED_KEY);
        }
        if (!shared.cache.resyncDue(player, item.profileId())) {
            return;
        }
        for (ComponentType<EntityStore, ? extends InventoryComponent> type
                : CaptureItemHolderSystems.Transfers.holderInventories()) {
            InventoryComponent inventory = store.getComponent(ref, type);
            if (inventory != null) {
                inventory.markDirty();
            }
        }
    }

    /**
     * The cached decision, or a new one taken under the index lock and cached there, so an index
     * change cannot invalidate the player between the count and the put. True when the item no
     * longer moves its companion to this player.
     */
    private boolean admitted(CaptureItemKeys.Ref item) {
        Boolean allowed = shared.index.atomically(() -> {
            CompanionRecord record = shared.index.get(item.profileId());
            if (CaptureItemOwnership.decide(record, item.generation(), player, null) != Decision.TRANSFER) {
                return null;
            }
            String family = AdmissionCache.family(record);
            AdmissionCache.Cached cached = shared.cache.get(player, family);
            if (cached != AdmissionCache.Cached.MISS) {
                return cached == AdmissionCache.Cached.ALLOW;
            }
            boolean ok = shared.gate.refuse(record, CaptureItemOwnership.asOwnedBy(record, player, null)) == null;
            shared.cache.put(player, family, ok);
            return ok;
        });
        return allowed == null || allowed;
    }
}
