package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs a bounded batch of due companion needs work on the current world thread.
 *
 * <p>The runner carries only UUIDs between callbacks. Current entity references, components, and
 * stores are resolved for the duration of one run and are not retained.</p>
 */
public final class CompanionNeedsBatchRunner {
    static final int MAX_UPDATES_PER_BATCH = 128;
    static final long MAX_BATCH_NANOS = 500_000L;
    private static final long BASE_INTERVAL_MS = 2_000L;
    private static final long WARNING_THROTTLE_MS = 60L * 1_000L;
    private static final long WARNING_PRUNE_WINDOW_MS = 24L * 60L * 60L * 1_000L;
    private static final String WARNING_SUFFIX = " linked NPCs dying from malnourishment";

    private final NpcUpdate testUpdate;
    private final TameworkUiMessageService uiMessageService = new TameworkUiMessageService();
    private final StoreScopedState<WarningState> warningStatesByStore =
            new StoreScopedState<>(WarningState::new);

    /** Creates a runner that resolves and updates NPCs through the current world and store. */
    public CompanionNeedsBatchRunner() {
        this.testUpdate = null;
    }

    /** Creates a runner with a package-local updater for deterministic scheduler tests. */
    CompanionNeedsBatchRunner(@Nonnull NpcUpdate testUpdate) {
        this.testUpdate = testUpdate;
    }

    /**
     * Processes due needs work and the active natural-regeneration suppression set.
     *
     * @param world current world-thread world; it may be {@code null} only with an injected test
     *             updater
     * @param state runtime state for the current entity store
     * @param nowMs current game/world time used for due timestamps
     * @param nanoClock monotonic clock used only for the batch budget
     */
    @Nonnull
    public BatchResult run(@Nullable World world,
                           @Nonnull CompanionNeedsRuntimeRegistry.WorldState state,
                           long nowMs,
                           @Nonnull LongSupplier nanoClock) {
        if (state == null) {
            return new BatchResult(0, false);
        }
        long startedAtNs = nanoClock.getAsLong();
        Store<EntityStore> store = resolveStore(world);
        Map<UUID, Integer> starvingLinkedByOwner = new HashMap<>();
        int processed = processDue(
                world,
                store,
                state,
                nowMs,
                nanoClock,
                startedAtNs,
                starvingLinkedByOwner
        );
        processSuppression(world, store, state);
        if (processed > 0 && store != null) {
            WarningState warningState = warningStatesByStore.get(store);
            notifyOwners(world, store, warningState, starvingLinkedByOwner, nowMs);
            pruneWarningThrottleEntries(warningState, nowMs);
        }
        return new BatchResult(processed, state.hasDue(nowMs));
    }

    private int processDue(@Nullable World world,
                           @Nullable Store<EntityStore> store,
                           @Nonnull CompanionNeedsRuntimeRegistry.WorldState state,
                           long nowMs,
                           @Nonnull LongSupplier nanoClock,
                           long startedAtNs,
                           @Nonnull Map<UUID, Integer> starvingLinkedByOwner) {
        int processed = 0;
        while (processed < MAX_UPDATES_PER_BATCH) {
            long dueAtMs = state.schedule().nextDueAtMs();
            UUID npcId = state.schedule().pollDue(nowMs);
            if (npcId == null) {
                break;
            }
            if (!state.hasMember(npcId)) {
                continue;
            }
            if (processed > 0 && elapsedNs(startedAtNs, nanoClock.getAsLong()) >= MAX_BATCH_NANOS) {
                state.schedule().reschedule(npcId, dueAtMs);
                break;
            }
            CompanionNeedsScheduledUpdate.Outcome outcome = updateNpc(world, state, npcId);
            if (outcome == null) {
                outcome = CompanionNeedsScheduledUpdate.retryOutcome();
            }
            reschedule(state, npcId, nowMs, outcome);
            collectMalnourishmentCount(store, world, npcId, outcome, starvingLinkedByOwner);
            processed++;
        }
        return processed;
    }

    @Nullable
    private CompanionNeedsScheduledUpdate.Outcome updateNpc(@Nullable World world,
                                                             @Nonnull CompanionNeedsRuntimeRegistry.WorldState state,
                                                             @Nonnull UUID npcId) {
        if (testUpdate != null) {
            return testUpdate.update(npcId);
        }
        return runProductionUpdate(world, state, npcId);
    }

    @Nullable
    private CompanionNeedsScheduledUpdate.Outcome runProductionUpdate(
            @Nullable World world,
            @Nonnull CompanionNeedsRuntimeRegistry.WorldState state,
            @Nonnull UUID npcId) {
        Store<EntityStore> store = resolveStore(world);
        if (store == null || !state.hasMember(npcId) || world == null) {
            return CompanionNeedsScheduledUpdate.retryOutcome();
        }
        Ref<EntityStore> npcRef;
        try {
            npcRef = world.getEntityRef(npcId);
        } catch (IllegalStateException ignored) {
            return CompanionNeedsScheduledUpdate.retryOutcome();
        }
        if (npcRef == null || !npcRef.isValid() || npcRef.getStore() != store) {
            return CompanionNeedsScheduledUpdate.retryOutcome();
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        return CompanionNeedsScheduledUpdate.run(npcRef, store, roleId, BASE_INTERVAL_MS);
    }

    private void reschedule(@Nonnull CompanionNeedsRuntimeRegistry.WorldState state,
                            @Nonnull UUID npcId,
                            long nowMs,
                            @Nonnull CompanionNeedsScheduledUpdate.Outcome outcome) {
        long nextDelayMs = Math.max(0L, outcome.nextDelayMs());
        state.schedule().reschedule(npcId, safeAdd(nowMs, nextDelayMs));
        state.setSuppressionActive(npcId, outcome.suppressionActive());
    }

    private void processSuppression(@Nullable World world,
                                    @Nullable Store<EntityStore> store,
                                    @Nonnull CompanionNeedsRuntimeRegistry.WorldState state) {
        if (world == null || store == null || !state.hasSuppressionActive()) {
            return;
        }
        Iterator<UUID> iterator = state.suppressionIds().iterator();
        while (iterator.hasNext()) {
            UUID npcId = iterator.next();
            if (!state.hasMember(npcId)) {
                iterator.remove();
                continue;
            }
            Ref<EntityStore> npcRef = resolveRef(world, store, npcId);
            if (npcRef == null) {
                iterator.remove();
                continue;
            }
            String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
            CompanionNeedsService.tickNaturalRegenSuppressionOnly(npcRef, store, roleId);
            if (!CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(npcRef, store, roleId)) {
                iterator.remove();
            }
        }
    }

    @Nullable
    private static Ref<EntityStore> resolveRef(@Nonnull World world,
                                               @Nonnull Store<EntityStore> store,
                                               @Nonnull UUID npcId) {
        try {
            Ref<EntityStore> npcRef = world.getEntityRef(npcId);
            return npcRef != null && npcRef.isValid() && npcRef.getStore() == store ? npcRef : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static void collectMalnourishmentCount(@Nullable Store<EntityStore> store,
                                                   @Nullable World world,
                                                   @Nonnull UUID npcId,
                                                   @Nonnull CompanionNeedsScheduledUpdate.Outcome outcome,
                                                   @Nonnull Map<UUID, Integer> counts) {
        if (store == null || world == null || !outcome.needsDamageActive()) {
            return;
        }
        Ref<EntityStore> npcRef = resolveRef(world, store, npcId);
        if (npcRef == null) {
            return;
        }
        TameworkCommandLinksComponent links = readLinks(store, npcRef);
        if (links == null || links.getOwnerId() == null || links.getToolIds() == null
                || links.getToolIds().length == 0) {
            return;
        }
        counts.merge(links.getOwnerId(), 1, Integer::sum);
    }

    @Nullable
    private static TameworkCommandLinksComponent readLinks(@Nonnull Store<EntityStore> store,
                                                            @Nonnull Ref<EntityStore> npcRef) {
        try {
            if (TameworkCommandLinksComponent.getComponentType() == null) {
                return null;
            }
            return store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void notifyOwners(@Nullable World world,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull WarningState warningState,
                              @Nonnull Map<UUID, Integer> counts,
                              long nowMs) {
        if (world == null || counts.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            UUID ownerId = entry.getKey();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (ownerId == null || count <= 0 || !shouldSendWarning(warningState, ownerId, nowMs)) {
                continue;
            }
            Player player = resolveOnlinePlayer(world, store, ownerId);
            if (player == null) {
                continue;
            }
            if (uiMessageService.show(
                    player,
                    count + WARNING_SUFFIX,
                    NotificationStyle.Danger
            )) {
                warningState.lastWarningByOwner.put(ownerId, nowMs);
            }
        }
    }

    private static boolean shouldSendWarning(@Nonnull WarningState state,
                                             @Nonnull UUID ownerId,
                                             long nowMs) {
        Long lastSentMs = state.lastWarningByOwner.get(ownerId);
        return lastSentMs == null || nowMs - lastSentMs >= WARNING_THROTTLE_MS;
    }

    private static void pruneWarningThrottleEntries(@Nonnull WarningState state, long nowMs) {
        if (state.lastWarningByOwner.isEmpty()) {
            return;
        }
        state.lastWarningByOwner.entrySet().removeIf(entry -> {
            Long value = entry.getValue();
            return value == null || nowMs - value > WARNING_PRUNE_WINDOW_MS;
        });
    }

    @Nullable
    private static Player resolveOnlinePlayer(@Nonnull World world,
                                              @Nonnull Store<EntityStore> store,
                                              @Nonnull UUID ownerId) {
        Ref<EntityStore> playerRef = resolveRef(world, store, ownerId);
        if (playerRef == null || Player.getComponentType() == null) {
            return null;
        }
        try {
            return store.getComponent(playerRef, Player.getComponentType());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @Nullable
    private static Store<EntityStore> resolveStore(@Nullable World world) {
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        return world.getEntityStore().getStore();
    }

    private static long elapsedNs(long startedAtNs, long currentNs) {
        long elapsed = currentNs - startedAtNs;
        return elapsed < 0L ? 0L : elapsed;
    }

    private static long safeAdd(long value, long increment) {
        if (increment <= 0L || value > Long.MAX_VALUE - increment) {
            return increment <= 0L ? value : Long.MAX_VALUE;
        }
        return value + increment;
    }

    /** Returns the number of normal updates and whether another due UUID remains. */
    public record BatchResult(int processed, boolean hasRemainingDue) {
    }

    @FunctionalInterface
    interface NpcUpdate {
        @Nullable
        CompanionNeedsScheduledUpdate.Outcome update(@Nonnull UUID npcId);
    }

    private static final class WarningState {
        private final Map<UUID, Long> lastWarningByOwner = new HashMap<>();
    }
}
