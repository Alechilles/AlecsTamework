package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.companion.bonded.BondedRecords;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionBodyCallbacks;
import com.alechilles.alecstamework.companion.live.CompanionSaves;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.live.FenceAction;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.runtime.ThrottledWarnings;
import com.alechilles.alecstamework.companion.store.CompanionWriter;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.items.CompanionRevivePolicy;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps companion records in step with their bodies (spec 6.8, 8.1, 8.6, 8.7). Runs in ECS
 * callbacks on the body's world thread: entity writes go through the command buffer, record
 * changes are in-memory index updates, and files are written later by the writer.
 */
public final class CompanionBodyLifecycle implements CompanionBodyCallbacks {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Release cause of a companion that died of old age; an owner or admin release has none. */
    public static final String CAUSE_OLD_AGE = "OLD_AGE";

    private final CompanionIndex index;
    private final CompanionWriter writer;
    private final LoadedBodies<Ref<EntityStore>> loaded;
    private final ComponentType<EntityStore, TameworkCompanionComponent> stampType;
    private final CompanionSnapshots snapshots;
    private final CompanionSummaries summaries;
    private final ThrottledWarnings warnings;
    private final LongSupplier clock;
    private final BiFunction<CompanionRecord, CompanionRecord, CompanionAdmissionGate.Admission> admission;
    private final BondedRecords.Families bondedFamilies;

    /**
     * {@code clock} is the wall clock used for death, revive and snapshot times. {@code admission}
     * checks a new record against the population caps and the cached admission provider decision
     * under the index lock ({@link CompanionAdmissionGate#admit}); a null answer admits it.
     * {@code bondedFamilies} gives a bonded companion's roster family, whose revive cooldown its
     * death uses (plan 6 R18).
     */
    public CompanionBodyLifecycle(@Nonnull CompanionIndex index, @Nonnull CompanionWriter writer,
                                  @Nonnull LoadedBodies<Ref<EntityStore>> loaded,
                                  @Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType,
                                  @Nonnull CompanionSnapshots snapshots, @Nonnull CompanionSummaries summaries,
                                  @Nonnull ThrottledWarnings warnings, @Nonnull LongSupplier clock,
                                  @Nonnull BiFunction<CompanionRecord, CompanionRecord,
                                          CompanionAdmissionGate.Admission> admission,
                                  @Nonnull BondedRecords.Families bondedFamilies) {
        this.index = Objects.requireNonNull(index, "index");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.stampType = Objects.requireNonNull(stampType, "stampType");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.bondedFamilies = Objects.requireNonNull(bondedFamilies, "bondedFamilies");
    }

    /**
     * Spec 8.1: a newly owned and tamed body gets a profile, a record and a stamp. Stamping a live
     * entity does not reach the body system, so the body is registered here.
     *
     * <p>Spec 8.10: {@code enforceCaps} is true only for a live ownership assignment (owner or
     * tamed state put on a loaded NPC). The tame sites check the caps first; this re-check under
     * the index lock covers the race where another change filled the slot in between, and the
     * pre-check makes it rare. A refused body is reverted to untamed through the command buffer:
     * its owner, tamed and command-link components are removed, and the owner is told why. A role
     * change the tame already made (for example wild to tamed livestock) is not undone; the body
     * keeps its current role. Bodies that arrive already owned (chunk loads, pre-rework bodies,
     * the startup pass) pass false: they are registered without the caps and never lose their owner.
     */
    public void tame(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> buffer, boolean enforceCaps) {
        if (store.getComponent(ref, stampType) != null) {
            return;
        }
        CompanionTransitions.BodyFacts body = CompanionBodyFacts.read(ref, store, summaries);
        if (body == null) {
            warn("tame-skipped", "Could not read an owned companion body; it was not registered");
            return;
        }
        if (body.ownerUuid() == null) {
            return;
        }
        UUID profileId = UUID.randomUUID();
        CompanionRegistration.Outcome outcome = CompanionRegistration.registerAdmitted(index, loaded,
                CompanionTransitions.newLive(profileId, 0, body), ref,
                record -> enforceCaps ? admission.apply(null, record) : null);
        if (outcome.refusal() != null) {
            buffer.tryRemoveComponent(ref, TameworkOwnerComponent.getComponentType());
            buffer.tryRemoveComponent(ref, TameworkTamedComponent.getComponentType());
            buffer.tryRemoveComponent(ref, TameworkCommandLinksComponent.getComponentType());
            tellOwnerAtLimit(store.getExternalData().getWorld(), body.ownerUuid(),
                    outcome.messageKey() != null ? outcome.messageKey()
                            : CompanionAdmissionGate.Denial.of(outcome.refusal()).messageKey());
            return;
        }
        if (!outcome.registered()) {
            warn("tame-skipped", "A record already names NPC %s; the tame was not registered", body.npcUuid());
            return;
        }
        buffer.addComponent(ref, stampType, new TameworkCompanionComponent(profileId, 0));
        CompanionSaves.markChanged(buffer, ref);
    }

    /**
     * Queues the refusal notification for the owner, resolved in the owner's language: {@code key}
     * is the refusal's message key (a limit, a provider's answer, or "checking requirements").
     * Runs as a world task so the ECS callback only queues it; the owner is looked up by UUID when
     * it runs.
     */
    private static void tellOwnerAtLimit(@Nonnull World world, @Nonnull UUID owner, @Nonnull String key) {
        world.execute(() -> {
            Universe universe = Universe.get();
            PlayerRef player = universe == null ? null : universe.getPlayer(owner);
            if (player != null && player.getPacketHandler() != null) {
                NotificationUtil.sendNotification(player.getPacketHandler(),
                        Message.raw(LocalizedText.resolve(player, key)), NotificationStyle.Warning);
            }
        });
    }

    /** The owner component of a stamped body changed (for example the set-owner command). */
    public void ownerChanged(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                             @Nullable UUID owner, @Nullable String ownerName) {
        TameworkCompanionComponent stamp = store.getComponent(ref, stampType);
        if (stamp == null || stamp.getProfileId() == null || !ref.equals(loaded.get(stamp.getProfileId()))) {
            return;
        }
        update(stamp.getProfileId(), r -> !Objects.equals(r.ownerUuid(), owner) || !Objects.equals(r.ownerName(), ownerName),
                r -> CompanionTransitions.ownerChanged(owner, ownerName));
    }

    /**
     * The command links of a stamped body changed (linked, relinked or unlinked). The body is the
     * authority for its tool links, so the record follows it; {@code links} is null on removal.
     */
    public void linksChanged(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                             @Nullable TameworkCommandLinksComponent links) {
        TameworkCompanionComponent stamp = store.getComponent(ref, stampType);
        if (stamp == null || stamp.getProfileId() == null || !ref.equals(loaded.get(stamp.getProfileId()))) {
            return;
        }
        List<String> tools = CompanionBodyFacts.toolIds(links);
        update(stamp.getProfileId(), r -> r.location().kind() == LocationKind.LIVE
                        && !CompanionTransitions.sameTools(r.toolIds(), tools),
                r -> CompanionTransitions.toolsChanged(tools));
    }

    /** Spec 8.6: record the death when DeathComponent is added, and drop the body from the loaded map. */
    public void died(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death, @Nonnull Store<EntityStore> store) {
        TameworkCompanionComponent stamp = store.getComponent(ref, stampType);
        if (stamp == null || stamp.getProfileId() == null || !ref.equals(loaded.get(stamp.getProfileId()))) {
            return;
        }
        recordDeath(stamp.getProfileId(), ref, death, store);
    }

    @Override
    public void onAccepted(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull TameworkCompanionComponent stamp,
                           boolean raised) {
        CompanionTransitions.BodyFacts body = CompanionBodyFacts.read(ref, store, summaries);
        if (body == null) {
            return;
        }
        if (raised) {
            update(stamp.getProfileId(), r -> true, r -> CompanionTransitions.raisedTo(stamp.getGeneration(), body));
            CompanionSaves.markChanged(buffer, ref);
        } else {
            update(stamp.getProfileId(), r -> CompanionTransitions.needsRefresh(r, body),
                    r -> CompanionTransitions.seenAt(body));
        }
    }

    @Override
    public void onAdopt(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                        @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull TameworkCompanionComponent stamp) {
        CompanionTransitions.BodyFacts body = CompanionBodyFacts.read(ref, store, summaries);
        boolean inserted = body != null
                && index.insert(CompanionTransitions.newLive(stamp.getProfileId(), stamp.getGeneration(), body)).applied();
        if (!inserted) {
            // The body system registered it; without a record it must not stay registered (plan 1 note).
            index.atomically(() -> loaded.removeIfSame(stamp.getProfileId(), ref));
            warn("adopt-failed", "Could not adopt companion body for profile %s", stamp.getProfileId());
        }
    }

    @Override
    public void onLoadedBodyRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                    @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                                    @Nonnull UUID profileId) {
        DeathComponent death = store.getComponent(ref, DeathComponent.getComponentType());
        if (reason == RemoveReason.REMOVE && death != null) {
            // A corpse whose death was not seen when DeathComponent was added (for example saved
            // while dying and loaded later with the same generation).
            recordDeath(profileId, null, death, store);
            return;
        }
        CompanionRecord record = index.get(profileId);
        if (record == null) {
            return;
        }
        if (reason == RemoveReason.REMOVE) {
            Long snapshotAt = snapshot(ref, store, record, record.generation() + 1);
            CompanionTransitions.BodyFacts body = CompanionBodyFacts.read(ref, store, summaries);
            update(profileId, r -> r.location().kind() == LocationKind.LIVE,
                    r -> CompanionTransitions.lost(r, body == null ? null : body.summary(),
                            CompanionTransitions.CAUSE_REMOVED, snapshotAt));
            return;
        }
        // UNLOAD and builder-tools undo: the body is saved with its chunk; refresh where it is.
        refreshUnloaded(ref, store, record, profileId);
    }

    @Override
    public void onDisplaced(@Nonnull Ref<EntityStore> displacedBody, @Nonnull UUID profileId) {
        CompanionBodies.removeOnOwnWorld(displacedBody);
    }

    @Override
    public void onFenced(@Nonnull FenceAction action, @Nonnull UUID profileId) {
        warn("fenced-" + action, "Removed or ignored a stale companion body for profile %s (%s)", profileId, action);
    }

    /**
     * Spec 8.8: the registered body's delete-on-remove world is being removed, and its entities
     * get no removal callbacks. Takes a snapshot at generation+1 and records LOST with cause
     * WORLD_REMOVED, then unregisters the body. Runs on the body's world thread.
     */
    void worldRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull UUID profileId) {
        CompanionRecord record = index.get(profileId);
        if (record == null || record.location().kind() != LocationKind.LIVE || !ref.equals(loaded.get(profileId))) {
            return;
        }
        Long snapshotAt = snapshot(ref, store, record, record.generation() + 1);
        CompanionTransitions.BodyFacts facts = CompanionBodyFacts.read(ref, store, summaries);
        boolean recorded = index.atomically(() -> {
            // Re-read under the lock: apply only to the same LIVE holder the snapshot was taken for.
            CompanionRecord current = index.get(profileId);
            boolean sameHolder = current != null && current.location().kind() == LocationKind.LIVE
                    && current.generation() == record.generation() && ref.equals(loaded.get(profileId));
            loaded.removeIfSame(profileId, ref);
            return sameHolder && index.update(profileId, current.revision(), CompanionTransitions.lost(current,
                    facts == null ? current.summary() : facts.summary(),
                    CompanionTransitions.CAUSE_WORLD_REMOVED, snapshotAt)).applied();
        });
        if (!recorded) {
            warn("world-removed-skipped", "Companion record %s changed before its world removal was recorded", profileId);
        }
    }

    /**
     * Spec 8.8: the registered body's world is being removed but kept on disk, and its entities
     * get no removal callbacks. Unregisters the body and refreshes the record as the UNLOAD path
     * does, with a snapshot only when one is due. Runs on the body's world thread.
     */
    void worldUnloaded(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull UUID profileId) {
        CompanionRecord record = index.get(profileId);
        if (record == null || !ref.equals(loaded.get(profileId))) {
            return;
        }
        index.atomically(() -> loaded.removeIfSame(profileId, ref));
        refreshUnloaded(ref, store, record, profileId);
    }

    /**
     * Moves a LIVE record to DEAD, or to RELEASED for an old-age death. {@code registered} is the
     * dying body when it is still the registered body; null when the death is seen only at
     * removal (no snapshot is taken then).
     */
    private void recordDeath(UUID profileId, @Nullable Ref<EntityStore> registered, DeathComponent death,
                             Store<EntityStore> store) {
        CompanionRecord record = index.get(profileId);
        if (record == null || record.location().kind() != LocationKind.LIVE) {
            if (registered != null) {
                index.atomically(() -> loaded.removeIfSame(profileId, registered));
            }
            return;
        }
        if (CompanionRevivePolicy.isOldAgeDeath(death)) {
            recordEndOfLife(profileId, registered, record);
            return;
        }
        long now = clock.getAsLong();
        CompanionDeathTiming.Timing timing = CompanionDeathTiming.resolve(registered, store,
                record.currentNpcUuid(), record.roleId(), death, now, bondedPolicy(record));
        Long snapshotAt = registered == null ? null : snapshot(registered, store, record, record.generation() + 1);
        CompanionTransitions.BodyFacts facts = registered == null
                ? null : CompanionBodyFacts.read(registered, store, summaries);
        boolean recorded = index.atomically(() -> {
            if (registered != null) {
                loaded.removeIfSame(profileId, registered);
            }
            // Re-read under the lock: apply only to the same LIVE holder the snapshot was taken for.
            CompanionRecord current = index.get(profileId);
            if (current == null || current.location().kind() != LocationKind.LIVE
                    || current.generation() != record.generation()) {
                return false;
            }
            return index.update(profileId, current.revision(), CompanionTransitions.died(current,
                    facts == null ? current.summary() : facts.summary(), now, timing.reviveAvailableAtMs(),
                    timing.cause(), snapshotAt)).applied();
        });
        if (!recorded) {
            warn("death-skipped", "Companion record %s changed before its death was recorded", profileId);
        }
    }

    /** A failed roster lookup falls back to the companion config cooldown: the death must be recorded. */
    @Nullable
    private BondedCompanionPolicy bondedPolicy(CompanionRecord record) {
        try {
            return BondedRecords.policy(record, bondedFamilies);
        } catch (RuntimeException | LinkageError failure) {
            warn("bonded-death-policy", "Could not resolve the bonded roster of companion %s; "
                    + "its death uses the companion config revive cooldown", record.profileId());
            return null;
        }
    }

    /**
     * An old-age death ends the companion's life: the record becomes a RELEASED tombstone, as in
     * the old dormant path. The writer deletes the snapshot only after the owner file with the
     * tombstone is written, so a failed write never leaves a record without its snapshot.
     */
    private void recordEndOfLife(UUID profileId, @Nullable Ref<EntityStore> registered, CompanionRecord record) {
        boolean released = index.atomically(() -> {
            if (registered != null) {
                loaded.removeIfSame(profileId, registered);
            }
            CompanionRecord current = index.get(profileId);
            if (current == null || current.location().kind() != LocationKind.LIVE
                    || current.generation() != record.generation()) {
                return false;
            }
            return index.update(profileId, current.revision(),
                    CompanionTransitions.released(current, CAUSE_OLD_AGE)).applied();
        });
        if (!released) {
            warn("death-skipped", "Companion record %s changed before its death was recorded", profileId);
            return;
        }
        // The writer holds the delete until the owner file with the tombstone is written, and retries it.
        writer.queueSnapshotDelete(profileId);
    }

    /**
     * Refreshes a LIVE record from a body that is leaving memory with its chunk or world, taking a
     * snapshot at the current generation only when one is due.
     */
    private void refreshUnloaded(Ref<EntityStore> ref, Store<EntityStore> store, CompanionRecord record,
                                 UUID profileId) {
        CompanionTransitions.BodyFacts body = CompanionBodyFacts.read(ref, store, summaries);
        if (body == null) {
            return;
        }
        Long snapshotAt = CompanionTransitions.snapshotDue(record, clock.getAsLong())
                ? snapshot(ref, store, record, record.generation()) : null;
        update(profileId, r -> r.location().kind() == LocationKind.LIVE,
                r -> CompanionTransitions.unloaded(body, snapshotAt));
    }

    /** Takes and queues a snapshot; returns its time, or null when capture failed (old one kept, spec 6.5). */
    @Nullable
    private Long snapshot(Ref<EntityStore> ref, Store<EntityStore> store, CompanionRecord record, long generation) {
        World world = store.getExternalData().getWorld();
        SnapshotEnvelope envelope = snapshots.capture(ref, store, record.profileId(), generation,
                world.getName(), CompanionWorldTime.gameTimeMs(store));
        if (envelope == null) {
            return null;
        }
        writer.queueSnapshot(envelope);
        return clock.getAsLong();
    }

    private void update(UUID profileId, Predicate<CompanionRecord> needed,
                        Function<CompanionRecord, UnaryOperator<CompanionRecord.Builder>> change) {
        for (int attempt = 0; attempt < 2; attempt++) {
            CompanionRecord current = index.get(profileId);
            if (current == null || !needed.test(current)) {
                return;
            }
            if (index.update(profileId, current.revision(), change.apply(current)).applied()) {
                return;
            }
        }
        warn("update-conflict", "Companion record %s changed concurrently; update skipped", profileId);
    }

    private void warn(String key, String message, Object... args) {
        if (warnings.shouldLog(key)) {
            LOGGER.at(Level.WARNING).logVarargs(message, args);
        }
    }
}
