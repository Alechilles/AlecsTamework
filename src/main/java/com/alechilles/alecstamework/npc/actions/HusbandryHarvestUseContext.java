package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.HusbandryToolContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import com.alechilles.alecstamework.output.CompanionOutputService.FinalizedOutput;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Captures one harvest tool at authorization time until the matching harvest output completes. */
public final class HusbandryHarvestUseContext {
    private static final ConcurrentHashMap<UUID, CapturedUse> USES = new ConcurrentHashMap<>();
    private static final long MAX_AGE_NANOS = 120_000_000_000L;
    private static final int MAX_PENDING_USES = 256;

    private HusbandryHarvestUseContext() {
    }

    static CapturedUse capture(@Nullable Player player, @Nullable ItemStack tool) {
        if (player == null || tool == null || tool.isEmpty()) {
            return CapturedUse.empty();
        }
        return new CapturedUse(
                player.getUuid(),
                PlayerInventoryAccess.getActiveHotbarSlot(player),
                HusbandryToolContext.from(tool),
                fingerprint(tool),
                1.0,
                HusbandryOutcomeModifiers.identity(),
                null,
                null,
                0L
        );
    }

    static void begin(@Nullable Ref<EntityStore> npcRef,
                      @Nullable Store<EntityStore> store,
                      CapturedUse use) {
        UUID npcId = npcId(npcRef, store);
        pruneExpired();
        if (npcId == null) {
            return;
        }
        CapturedUse captured = use == null ? CapturedUse.empty() : use;
        if (!captured.tool().present()) {
            USES.remove(npcId);
            return;
        }
        USES.put(npcId, captured.startedAtNanos(System.nanoTime()));
    }

    static CapturedUse current(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        UUID npcId = npcId(npcRef, store);
        if (npcId == null) {
            return CapturedUse.empty();
        }
        CapturedUse use = USES.get(npcId);
        if (use != null && use.expired(System.nanoTime())) {
            USES.remove(npcId, use);
            return CapturedUse.empty();
        }
        return use == null ? CapturedUse.empty() : use;
    }

    static CapturedUse finish(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        UUID npcId = npcId(npcRef, store);
        return npcId == null ? CapturedUse.empty() : USES.remove(npcId);
    }

    static void applyWear(@Nullable Player player, CapturedUse use, double multiplier) {
        if (player == null || use == null || !use.tool().present() || use.hotbarSlot() < 0
                || !Double.isFinite(multiplier) || multiplier <= 0.0) {
            return;
        }
        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        if (hotbar == null) {
            return;
        }
        short slot = findMatchingHotbarSlot(hotbar, use);
        if (slot < 0) {
            return;
        }
        ItemStack current = hotbar.getItemStack(slot);
        hotbar.setItemStackForSlot(slot, current.withIncreasedDurability(-multiplier));
    }

    static boolean stillMatches(@Nullable Player player, CapturedUse use) {
        if (use == null || !use.tool().present()) {
            return true;
        }
        if (player == null || use.hotbarSlot() < 0) {
            return false;
        }
        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        if (hotbar == null || use.hotbarSlot() >= hotbar.getCapacity()) {
            return false;
        }
        ItemStack current = hotbar.getItemStack(use.hotbarSlot());
        return current != null && !current.isEmpty() && use.fingerprint().equals(fingerprint(current));
    }

    private static short findMatchingHotbarSlot(ItemContainer hotbar, CapturedUse use) {
        if (use.hotbarSlot() >= 0 && use.hotbarSlot() < hotbar.getCapacity()) {
            ItemStack current = hotbar.getItemStack(use.hotbarSlot());
            if (matchesCapturedTool(current, use)) {
                return use.hotbarSlot();
            }
        }
        for (short slot = 0; slot < hotbar.getCapacity(); slot++) {
            ItemStack current = hotbar.getItemStack(slot);
            if (matchesCapturedTool(current, use)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean matchesCapturedTool(@Nullable ItemStack current, CapturedUse use) {
        return current != null && !current.isEmpty() && current.getMaxDurability() > 0.0
                && use.fingerprint().equals(fingerprint(current));
    }

    /** Clears bounded interaction state when the Tamework runtime unloads. */
    public static void clearPendingUses() {
        USES.clear();
    }

    private static void pruneExpired() {
        long now = System.nanoTime();
        USES.entrySet().removeIf(entry -> entry.getValue().expired(now));
        if (USES.size() > MAX_PENDING_USES) {
            int surplus = USES.size() - MAX_PENDING_USES;
            for (UUID npcId : USES.keySet()) {
                if (surplus-- <= 0) {
                    break;
                }
                USES.remove(npcId);
            }
        }
    }

    private static UUID npcId(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc == null ? null : npc.getUuid();
    }

    private static String fingerprint(ItemStack tool) {
        return tool.getItemId() + "\u001f" + tool.getMaxDurability() + "\u001f"
                + (tool.getMetadata() == null ? "" : tool.getMetadata().toJson());
    }

    record CapturedUse(UUID actorId, byte hotbarSlot, HusbandryToolContext tool, String fingerprint,
                       double wearMultiplier, HusbandryOutcomeModifiers genericModifiers, String preparedDropList,
                       FinalizedOutput preparedOutput, long startedAtNanos) {
        CapturedUse withWearMultiplier(double nextMultiplier) {
            return new CapturedUse(actorId, hotbarSlot, tool, fingerprint, nextMultiplier,
                    genericModifiers, preparedDropList, preparedOutput, startedAtNanos);
        }

        CapturedUse withModifiers(HusbandryOutcomeModifiers generic) {
            return new CapturedUse(actorId, hotbarSlot, tool, fingerprint, wearMultiplier,
                    generic == null ? HusbandryOutcomeModifiers.identity() : generic,
                    preparedDropList, preparedOutput, startedAtNanos);
        }

        CapturedUse withPreparedOutput(String dropList, FinalizedOutput output) {
            return new CapturedUse(actorId, hotbarSlot, tool, fingerprint, wearMultiplier,
                    genericModifiers, dropList, output, startedAtNanos);
        }

        CapturedUse startedAtNanos(long value) {
            return new CapturedUse(actorId, hotbarSlot, tool, fingerprint, wearMultiplier,
                    genericModifiers, preparedDropList, preparedOutput, value);
        }

        FinalizedOutput preparedOutputFor(String dropList, boolean manualHarvest) {
            return manualHarvest && preparedOutput != null && preparedDropList != null
                    && preparedDropList.equalsIgnoreCase(dropList == null ? "" : dropList)
                    ? preparedOutput : null;
        }

        boolean expired(long now) {
            return startedAtNanos > 0L && now - startedAtNanos > MAX_AGE_NANOS;
        }

        static CapturedUse empty() {
            return new CapturedUse(null, (byte) -1, new HusbandryToolContext(null, 0, 0.0, 0.0, null),
                    "", 1.0, HusbandryOutcomeModifiers.identity(),
                    null, null, 0L);
        }
    }
}
