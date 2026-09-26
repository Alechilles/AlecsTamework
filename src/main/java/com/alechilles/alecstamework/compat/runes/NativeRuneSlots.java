package com.alechilles.alecstamework.compat.runes;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/** Loads Update 7 ability slots without linking Update 5/6 to the new classes. */
final class NativeRuneSlots {
    static final short[] PRIMARY_SLOTS = {0, 3}; // Update 7 ABILITIES_LINE_WIDTH.
    @Nullable private static final Class<?> ABILITY_CLASS = findAbilityClass();
    @Nullable private static ComponentType<EntityStore, ?> abilityType;
    @Nullable private static SlotFilter primaryAddFilter;

    private static synchronized void initialize() {
        if (ABILITY_CLASS == null || (abilityType != null && primaryAddFilter != null)) return;
        try {
            Method getType = ABILITY_CLASS.getMethod("getComponentType");
            ComponentType<EntityStore, ?> type = (ComponentType<EntityStore, ?>) getType.invoke(null);
            Class<?> slotClass = Class.forName("com.hypixel.hytale.protocol.AbilitySlot");
            Object primary = Enum.valueOf((Class) slotClass, "Primary");
            Class<?> filterClass = Class.forName(
                    "com.hypixel.hytale.server.core.inventory.container.filter.AbilitySlotAddFilter");
            Constructor<?> constructor = filterClass.getConstructor(slotClass);
            SlotFilter filter = (SlotFilter) constructor.newInstance(primary);
            if (type != null) {
                abilityType = type;
                primaryAddFilter = filter;
            }
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
            // Update 5/6 have no ability inventory. A later setup pass may initialize Update 7.
        }
    }

    private NativeRuneSlots() { }

    static boolean isSupported() {
        // Setup can run before EntityModule has registered its component types.
        return ABILITY_CLASS != null;
    }

    @Nullable
    private static Class<?> findAbilityClass() {
        try {
            return Class.forName("com.hypixel.hytale.server.core.inventory.InventoryComponent$AbilitySlots",
                    false, NativeRuneSlots.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static ItemContainer container(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        if (!isSupported()) return null;
        initialize();
        if (abilityType == null) return null;
        @SuppressWarnings({"rawtypes", "unchecked"})
        Component<EntityStore> component = accessor.getComponent(ref, (ComponentType) abilityType);
        return component instanceof InventoryComponent inventory ? inventory.getInventory() : null;
    }

    static boolean canLock(ItemContainer container) {
        initialize();
        return primaryAddFilter != null && container instanceof SimpleItemContainer
                && container.getCapacity() > PRIMARY_SLOTS[1];
    }

    static void lock(ItemContainer container, short slot) {
        container.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.DENY);
        container.setSlotFilter(FilterActionType.REMOVE, slot, SlotFilter.DENY);
        container.setSlotFilter(FilterActionType.DROP, slot, SlotFilter.DENY);
    }

    static void unlock(ItemContainer container, short slot) {
        // Restore the native ability-primary input rule; leave support slots untouched.
        container.setSlotFilter(FilterActionType.ADD, slot, primaryAddFilter);
        container.setSlotFilter(FilterActionType.REMOVE, slot, null);
        container.setSlotFilter(FilterActionType.DROP, slot, null);
    }
}
