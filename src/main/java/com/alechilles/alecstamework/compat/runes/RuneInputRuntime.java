package com.alechilles.alecstamework.compat.runes;

import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Installs temporary native runes only while a selected Tamework item needs E/R. */
public final class RuneInputRuntime {
    private static final String TALISMAN_ID = "Tamework_Flightmasters_Talisman";
    private static final RuneInputLeaseService LEASES = new RuneInputLeaseService();

    private RuneInputRuntime() { }

    public static boolean isSupported() {
        return NativeRuneSlots.isSupported();
    }

    /** The server-side rune interaction checks this again at cast time. */
    public static boolean isActiveLease(@Nonnull ComponentAccessor<EntityStore> accessor,
                                        @Nonnull Ref<EntityStore> ref,
                                        @Nonnull InteractionType type) {
        int line = line(type);
        ComponentType<EntityStore, RuneInputLeaseComponent> leaseType = RuneInputLeaseComponent.getComponentType();
        if (line < 0 || leaseType == null || !isSupported()) return false;
        RuneInputLeaseComponent lease = accessor.getComponent(ref, leaseType);
        ItemContainer slots = NativeRuneSlots.container(accessor, ref);
        if (lease == null || slots == null || slots.getCapacity() <= NativeRuneSlots.PRIMARY_SLOTS[line]) return false;
        Demand demand = demand(accessor, ref);
        return demand.forLine(line) && LEASES.isInstalled(slots, lease, line);
    }

    private static int line(InteractionType type) {
        return type == InteractionType.Ability2 ? 0 : type == InteractionType.Ability3 ? 1 : -1;
    }

    private static void seed(@Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref,
                             @Nonnull CommandBuffer<EntityStore> buffer) {
        ComponentType<EntityStore, RuneInputLeaseComponent> leaseType = RuneInputLeaseComponent.getComponentType();
        if (leaseType == null || !isSupported() || store.getComponent(ref, leaseType) != null) return;
        ItemContainer slots = NativeRuneSlots.container(store, ref);
        if (slots == null) return;
        Demand wanted = demand(store, ref);
        if (!wanted.any()) return;
        RuneInputLeaseComponent lease = LEASES.capture(slots, wanted.ability2, wanted.ability3);
        if (lease != null) buffer.putComponent(ref, leaseType, lease);
        // Tick sees this saved component on the next ECS pass, then touches the inventory.
    }

    private static void reconcile(@Nonnull Store<EntityStore> store,
                                  @Nonnull Ref<EntityStore> ref,
                                  @Nonnull CommandBuffer<EntityStore> buffer) {
        ComponentType<EntityStore, RuneInputLeaseComponent> leaseType = RuneInputLeaseComponent.getComponentType();
        if (leaseType == null) return;
        RuneInputLeaseComponent lease = store.getComponent(ref, leaseType);
        if (lease == null) {
            seed(store, ref, buffer);
            return;
        }
        ItemContainer slots = NativeRuneSlots.container(store, ref);
        if (slots == null) return;
        Demand wanted = demand(store, ref);
        LEASES.reconcile(slots, lease, wanted.ability2, wanted.ability3);
        if (lease.empty()) buffer.removeComponent(ref, leaseType);
    }

    private static void restore(@Nonnull Store<EntityStore> store,
                                @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, RuneInputLeaseComponent> leaseType = RuneInputLeaseComponent.getComponentType();
        if (leaseType == null) return;
        RuneInputLeaseComponent lease = store.getComponent(ref, leaseType);
        ItemContainer slots = NativeRuneSlots.container(store, ref);
        if (lease != null && slots != null) LEASES.restore(slots, lease);
    }

    private static Demand demand(@Nonnull ComponentAccessor<EntityStore> accessor,
                                 @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Hotbar hotbar = accessor.getComponent(
                ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Tool tools = accessor.getComponent(
                ref, InventoryComponent.Tool.getComponentType());
        if (hotbar == null || (tools != null && tools.isUsingToolsItem())) return Demand.NONE;
        ItemStack selected = hotbar.getActiveItem();
        if (ItemStack.isEmpty(selected)) return Demand.NONE;
        Item item = selected.getItem();
        if (item == null || item.getWeapon() == null || item.getData() == null
                || item.getData().getExpandedTagIndexes() == null
                || !item.getData().getExpandedTagIndexes().contains(
                        AssetRegistry.getOrCreateTagIndex("Family=TameworkInput"))) return Demand.NONE;
        if (TALISMAN_ID.equals(selected.getItemId())) {
            ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
            if (flightType == null || accessor.getComponent(ref, flightType) == null) return Demand.NONE;
        }
        Map<InteractionType, String> interactions = item.getInteractions();
        if (interactions == null) return Demand.NONE;
        return new Demand(root(interactions.get(InteractionType.Ability2)),
                root(interactions.get(InteractionType.Ability3)));
    }

    private static boolean root(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private record Demand(boolean ability2, boolean ability3) {
        static final Demand NONE = new Demand(false, false);
        boolean any() { return ability2 || ability3; }
        boolean forLine(int line) { return line == 0 ? ability2 : ability3; }
    }

    /** Restores saved runes before ordinary runtime work on load and when a player leaves. */
    public static final class Load extends RefSystem<EntityStore> {
        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemDependency<>(Order.AFTER, PlayerSystems.PlayerInitSystem.class));

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull CommandBuffer<EntityStore> buffer) {
            restore(store, ref);
            seed(store, ref, buffer);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            restore(store, ref);
        }

        @Override public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType());
        }
    }

    /** Handles hotbar and tool selection changes for players without a lease. */
    public static final class ActiveSlot extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {
        public ActiveSlot() { super(InventorySetActiveSlotEvent.class); }

        @Override public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType(), InventoryComponent.Hotbar.getComponentType());
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> buffer,
                           @Nonnull InventorySetActiveSlotEvent event) {
            if (event.getInventorySectionId() != InventoryComponent.HOTBAR_SECTION_ID
                    && event.getInventorySectionId() != InventoryComponent.TOOLS_SECTION_ID) return;
            seed(store, chunk.getReferenceTo(index), buffer);
        }
    }

    /** Handles replacement of a selected command item without scanning all players. */
    public static final class HotbarChange extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
        public HotbarChange() { super(InventoryChangeEvent.class); }

        @Override public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType(), InventoryComponent.Hotbar.getComponentType());
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> buffer,
                           @Nonnull InventoryChangeEvent event) {
            InventoryComponent.Hotbar hotbar = chunk.getComponent(index, InventoryComponent.Hotbar.getComponentType());
            if (hotbar != null && event.getItemContainer() == hotbar.getInventory()) {
                seed(store, chunk.getReferenceTo(index), buffer);
            }
        }
    }

    /** A talisman becomes eligible after flight starts and loses eligibility on exit. */
    public static final class FlightChange extends RefSystem<EntityStore> {
        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull CommandBuffer<EntityStore> buffer) {
            seed(store, ref, buffer);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            restore(store, ref);
        }

        @Override public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType(), AvatarFlightComponent.getComponentType());
        }
    }

    /** Reconciles only players with a saved lease, including interrupted installs. */
    public static final class Tick extends EntityTickingSystem<EntityStore> {
        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                         @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> buffer) {
            reconcile(store, chunk.getReferenceTo(index), buffer);
        }

        @Override public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType(), RuneInputLeaseComponent.getComponentType());
        }
    }
}
