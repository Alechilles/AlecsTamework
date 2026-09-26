package com.alechilles.alecstamework.compat.runes;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import java.util.Objects;
import javax.annotation.Nullable;

/** World-thread changes to two player-owned ability slots. */
final class RuneInputLeaseService {
    static final String ABILITY2_RUNE = "Tamework_Input_Rune_E";
    static final String ABILITY3_RUNE = "Tamework_Input_Rune_R";

    interface Filters {
        boolean canLock(ItemContainer container);
        void lock(ItemContainer container, short slot);
        void unlock(ItemContainer container, short slot);
    }

    static final Filters NATIVE_FILTERS = new Filters() {
        public boolean canLock(ItemContainer container) { return NativeRuneSlots.canLock(container); }
        public void lock(ItemContainer container, short slot) { NativeRuneSlots.lock(container, slot); }
        public void unlock(ItemContainer container, short slot) { NativeRuneSlots.unlock(container, slot); }
    };

    private final Filters filters;

    RuneInputLeaseService() {
        this(NATIVE_FILTERS);
    }

    RuneInputLeaseService(Filters filters) {
        this.filters = filters;
    }

    /** Capture on the player first. Call on a later ECS pass before any slot changes. */
    RuneInputLeaseComponent capture(ItemContainer slots, boolean ability2, boolean ability3) {
        if (!filters.canLock(slots)) return null;
        RuneInputLeaseComponent lease = new RuneInputLeaseComponent();
        if (ability2) {
            ItemStack current = slots.getItemStack(NativeRuneSlots.PRIMARY_SLOTS[0]);
            if (isRune(current, 0)) return null;
            lease.capture(0, current);
        }
        if (ability3) {
            ItemStack current = slots.getItemStack(NativeRuneSlots.PRIMARY_SLOTS[1]);
            if (isRune(current, 1)) return null;
            lease.capture(1, current);
        }
        return lease.empty() ? null : lease;
    }

    /** Returns false on an occupied-slot conflict or rejected mutation; the saved lease stays. */
    boolean reconcile(ItemContainer slots, RuneInputLeaseComponent lease,
                      boolean ability2, boolean ability3) {
        if (!filters.canLock(slots)) return false;
        boolean first = reconcileLine(slots, lease, 0, ability2);
        boolean second = reconcileLine(slots, lease, 1, ability3);
        return first && second;
    }

    boolean restore(ItemContainer slots, RuneInputLeaseComponent lease) {
        if (!filters.canLock(slots)) return false;
        boolean first = restoreLine(slots, lease, 0);
        boolean second = restoreLine(slots, lease, 1);
        return first && second;
    }

    boolean isInstalled(ItemContainer slots, RuneInputLeaseComponent lease, int line) {
        return lease.pending(line) && isRune(slots.getItemStack(NativeRuneSlots.PRIMARY_SLOTS[line]), line);
    }

    private boolean reconcileLine(ItemContainer slots, RuneInputLeaseComponent lease,
                                  int line, boolean desired) {
        short slot = NativeRuneSlots.PRIMARY_SLOTS[line];
        if (!desired) return restoreLine(slots, lease, line);
        if (!lease.pending(line)) {
            ItemStack current = slots.getItemStack(slot);
            if (isRune(current, line)) return false;
            lease.capture(line, current); // Component is already attached to this player.
        }
        ItemStack current = slots.getItemStack(slot);
        if (isRune(current, line)) {
            filters.lock(slots, slot); // Reapply filters after a saved inventory was decoded.
            return true;
        }
        if (!Objects.equals(current, lease.original(line))) {
            filters.unlock(slots, slot);
            return false;
        }
        filters.lock(slots, slot);
        ItemStack rune = new ItemStack(runeId(line));
        if (slots.replaceItemStackInSlot(slot, current, rune).succeeded()) return true;
        filters.unlock(slots, slot);
        return false;
    }

    private boolean restoreLine(ItemContainer slots, RuneInputLeaseComponent lease, int line) {
        if (!lease.pending(line)) return true;
        short slot = NativeRuneSlots.PRIMARY_SLOTS[line];
        ItemStack current = slots.getItemStack(slot);
        ItemStack original = lease.original(line);
        if (isRune(current, line)) {
            if (!slots.replaceItemStackInSlot(slot, current, original).succeeded()) return false;
        } else if (!Objects.equals(current, original)) {
            filters.unlock(slots, slot);
            return false;
        }
        filters.unlock(slots, slot);
        lease.clear(line); // Never drop the saved original before replacement succeeds.
        return true;
    }

    static boolean isRune(@Nullable ItemStack stack, int line) {
        return stack != null && !stack.isEmpty() && runeId(line).equals(stack.getItemId());
    }

    private static String runeId(int line) {
        return line == 0 ? ABILITY2_RUNE : ABILITY3_RUNE;
    }
}
