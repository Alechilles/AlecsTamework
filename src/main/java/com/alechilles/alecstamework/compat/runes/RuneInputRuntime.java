package com.alechilles.alecstamework.compat.runes;

import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
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
import com.hypixel.hytale.component.system.RefChangeSystem;
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
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Claims Update 7 E/R only while a selected Tamework item needs them. */
public final class RuneInputRuntime {
    /**
     * Spike: bind E/R on the player's {@link Interactions} component, which the engine resolves
     * before the rune slots, so the player's runes are never moved. {@code false} restores the
     * temporary rune lease.
     */
    private static final boolean ENTITY_INPUT = true;
    private static final InteractionType[] TYPES = {InteractionType.Ability2, InteractionType.Ability3};
    private static final String[] ROOTS = {"Root_Tamework_Input_Rune_E", "Root_Tamework_Input_Rune_R"};
    private static final String TALISMAN_ID = "Tamework_Flightmasters_Talisman";
    private static final RuneInputLeaseService LEASES = new RuneInputLeaseService();
    private static final com.hypixel.hytale.logger.HytaleLogger LOGGER =
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass();

    private RuneInputRuntime() { }

    public static boolean isSupported() {
        return NativeRuneSlots.isSupported();
    }

    /** True when E/R reach Tamework through the player's entity interactions, not a slotted rune. */
    public static boolean usesEntityInput() {
        return ENTITY_INPUT;
    }

    /** The server-side rune interaction checks this again at cast time. */
    public static boolean isActiveLease(@Nonnull ComponentAccessor<EntityStore> accessor,
                                        @Nonnull Ref<EntityStore> ref,
                                        @Nonnull InteractionType type) {
        int line = line(type);
        if (ENTITY_INPUT) {
            if (line < 0 || !isSupported()) return false;
            Interactions bound = accessor.getComponent(ref, Interactions.getComponentType());
            return bound != null && ROOTS[line].equals(bound.getInteractionId(type))
                    && demand(accessor, ref).forLine(line);
        }
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

    /**
     * Adds or removes only Tamework's own E/R entries. An entry another owner already holds is
     * left alone, so that owner keeps the input.
     */
    private static void bindEntityInput(@Nonnull ComponentAccessor<EntityStore> accessor,
                                        @Nonnull Ref<EntityStore> ref) {
        if (!isSupported()) return;
        Demand wanted = demand(accessor, ref);
        Interactions current = accessor.getComponent(ref, Interactions.getComponentType());
        InventoryComponent.Hotbar spikeHotbar = accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        ItemStack spikeHeld = spikeHotbar == null ? null : spikeHotbar.getActiveItem();
        LOGGER.at(java.util.logging.Level.INFO).log("[RuneInputSpike] check held=%s weapon=%s wantedE=%s wantedR=%s",
                ItemStack.isEmpty(spikeHeld) ? "none" : spikeHeld.getItemId(),
                !ItemStack.isEmpty(spikeHeld) && spikeHeld.getItem() != null && spikeHeld.getItem().getWeapon() != null,
                wanted.ability2, wanted.ability3);
        Interactions updated = null;
        for (int line = 0; line < TYPES.length; line++) {
            String bound = current == null ? null : current.getInteractionId(TYPES[line]);
            boolean add = wanted.forLine(line) && bound == null;
            boolean remove = !wanted.forLine(line) && ROOTS[line].equals(bound);
            if (!add && !remove) continue;
            if (updated == null) updated = current == null ? new Interactions() : (Interactions) current.clone();
            if (add) updated.setInteractionId(TYPES[line], ROOTS[line]);
            else updated.removeInteractionId(TYPES[line]);
        }
        if (updated != null) {
            accessor.putComponent(ref, Interactions.getComponentType(), updated);
            // Spike diagnostic: shows whether the binding was applied before a key press.
            LOGGER.at(java.util.logging.Level.INFO).log("[RuneInputSpike] bound E=%s R=%s wantedE=%s wantedR=%s",
                    updated.getInteractionId(TYPES[0]), updated.getInteractionId(TYPES[1]),
                    wanted.ability2, wanted.ability3);
        }
    }

    private static void seed(@Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref,
                             @Nonnull CommandBuffer<EntityStore> buffer) {
        if (ENTITY_INPUT) {
            // Deferred so the read sees every earlier write from this tick.
            buffer.run(current -> {
                if (ref.isValid()) bindEntityInput(current, ref);
            });
            return;
        }
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
        // With entity input, a lease saved by an older build only hands its runes back.
        Demand wanted = ENTITY_INPUT ? Demand.NONE : demand(store, ref);
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
        if (item == null) return Demand.NONE;
        boolean tagged = item.getWeapon() != null && item.getData() != null
                && item.getData().getExpandedTagIndexes() != null
                && item.getData().getExpandedTagIndexes().contains(
                        AssetRegistry.getOrCreateTagIndex("Family=TameworkInput"));
        // Entity input needs no rune, so a configured command item qualifies without the weapon patch.
        if (!tagged && !(ENTITY_INPUT && isCommandItem(selected.getItemId()))) return Demand.NONE;
        if (TALISMAN_ID.equals(selected.getItemId())) {
            ComponentType<EntityStore, AvatarFlightComponent> flightType = AvatarFlightComponent.getComponentType();
            if (flightType == null || accessor.getComponent(ref, flightType) == null) return Demand.NONE;
        }
        Map<InteractionType, String> interactions = item.getInteractions();
        if (interactions == null) return Demand.NONE;
        return new Demand(root(interactions.get(InteractionType.Ability2)),
                root(interactions.get(InteractionType.Ability3)));
    }

    private static boolean isCommandItem(@Nullable String itemId) {
        var configs = TwCommandItemConfig.getAssetMap();
        if (itemId == null || configs == null) return false;
        for (TwCommandItemConfig config : configs.getAssetMap().values()) {
            if (config == null) continue;
            for (String configured : config.getItemIds()) {
                if (itemId.equalsIgnoreCase(configured)) return true;
            }
        }
        return false;
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

    /**
     * A talisman becomes eligible after flight starts and loses eligibility on exit. Adding a
     * component to a live entity fires only {@link RefChangeSystem} callbacks.
     */
    public static final class FlightChange extends RefChangeSystem<EntityStore, AvatarFlightComponent> {
        @Override
        public ComponentType<EntityStore, AvatarFlightComponent> componentType() {
            return AvatarFlightComponent.getComponentType();
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AvatarFlightComponent component,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull CommandBuffer<EntityStore> buffer) {
            seed(store, ref, buffer);
        }

        @Override
        public void onComponentSet(@Nonnull Ref<EntityStore> ref, @Nullable AvatarFlightComponent oldComponent,
                                   @Nonnull AvatarFlightComponent newComponent,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> buffer) {
            // Flight state updates do not change which inputs the talisman needs.
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull AvatarFlightComponent component,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull CommandBuffer<EntityStore> buffer) {
            restore(store, ref);
            // Runs after the flight component is gone, so a held talisman stops claiming E/R.
            if (ENTITY_INPUT) seed(store, ref, buffer);
        }

        @Override public Query<EntityStore> getQuery() {
            return Query.and(Player.getComponentType());
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
