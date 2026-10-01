package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.companion.flow.SnapshotPatch;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.hypixel.hytale.codec.ExtraInfo;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Buys and resets the talents of a bonded companion. An active companion's body holds its
 * talents, so the change is made on the body, on its world thread ({@link LiveBody}). Any other
 * companion keeps them in its stored snapshot: the snapshot is read, checked against the level it
 * holds, patched ({@link SnapshotPatch#withTalents}) and queued for writing, so the next summon
 * comes back with the new talents.
 *
 * <p>The stored change is fenced on the record revision the caller saw. Under the index lock the
 * record's summary gets the new spent points and tree, at that revision, and the patched snapshot
 * is queued only when that update applied. Every other change to the record moves its revision
 * first: a summon that committed, or a second purchase prepared from the same snapshot, makes
 * this one end {@link Status#CONFLICT} with nothing queued.</p>
 *
 * <p>The queued snapshot is written by the companion writer's next flush; this class does not
 * wait for it. May be called from any thread and never blocks.</p>
 */
public final class BondedTalentUpdates {
    private static final String COMPONENTS = "Components";
    private static final String LEVELING = "TameworkLeveling";

    /** How an update ended. Only {@link #APPLIED} changed anything. */
    public enum Status {
        APPLIED,
        /** The talent tree, level, prerequisites or points do not allow the change. */
        REJECTED,
        /** Talents are turned off in the Tamework settings. */
        DISABLED,
        /** The companion has no level data to check against (no snapshot yet, or an unreadable one). */
        NO_LEVEL_DATA,
        /** The record moved on (summoned, stored, abandoned) while the change was prepared. */
        CONFLICT,
        /** The companion is active but its body is not loaded. */
        BODY_UNAVAILABLE,
        FAILED
    }

    /**
     * @param talents          the talents after the change; set when {@code status} is APPLIED
     * @param level            the level the change was checked against
     * @param levelingConfigId the leveling config that level belongs to, or null
     */
    public record Outcome(@Nonnull Status status, @Nullable TameworkTalentsComponent talents, int level,
                          @Nullable String levelingConfigId) {
        public Outcome {
            Objects.requireNonNull(status, "status");
        }

        @Nonnull
        public static Outcome of(@Nonnull Status status) {
            return new Outcome(status, null, 0, null);
        }
    }

    /** Changes the talents of a loaded body on its own world thread. */
    @FunctionalInterface
    public interface LiveBody {
        /**
         * @return the outcome once the body's world thread has run the change, or null when the
         *         profile has no loaded body
         */
        @Nullable
        CompletableFuture<Outcome> update(@Nonnull CompanionRecord record,
                                          @Nonnull BondedCompanionTalentActionRequest request);
    }

    /** Reads the stored talent state of one companion for the talent page; the bonded API implements it. */
    @FunctionalInterface
    public interface StoredReader {
        /** @return the stored state, or null when there is none to show; never fails */
        @Nonnull
        CompletableFuture<Stored> storedTalents(@Nonnull UUID ownerUuid, @Nonnull String rosterId,
                                                @Nonnull String profileId);
    }

    /** Finds the talent tree a change is checked against; null when there is none. */
    @FunctionalInterface
    public interface Configs {
        @Nullable
        TwTalentConfig resolve(@Nullable String presentedConfigId, @Nonnull String roleId);
    }

    private final CompanionIndex index;
    private final Function<UUID, CompletableFuture<SnapshotEnvelope>> readSnapshot;
    private final Consumer<SnapshotEnvelope> queueSnapshot;
    private final LiveBody live;
    private final Configs configs;
    private final BooleanSupplier enabled;

    /**
     * @param readSnapshot  a profile's latest snapshot, queued or on disk, without blocking;
     *                      {@code CompanionPersistenceModule::readSnapshot}
     * @param queueSnapshot {@code CompanionWriter::queueSnapshot}
     */
    public BondedTalentUpdates(@Nonnull CompanionIndex index,
                               @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> readSnapshot,
                               @Nonnull Consumer<SnapshotEnvelope> queueSnapshot, @Nonnull LiveBody live) {
        this(index, readSnapshot, queueSnapshot, live, BondedTalentUpdates::configuredTree,
                CompanionProgressionSettings::isTalentsEnabled);
    }

    BondedTalentUpdates(@Nonnull CompanionIndex index,
                        @Nonnull Function<UUID, CompletableFuture<SnapshotEnvelope>> readSnapshot,
                        @Nonnull Consumer<SnapshotEnvelope> queueSnapshot, @Nonnull LiveBody live,
                        @Nonnull Configs configs, @Nonnull BooleanSupplier enabled) {
        this.index = Objects.requireNonNull(index, "index");
        this.readSnapshot = Objects.requireNonNull(readSnapshot, "readSnapshot");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.live = Objects.requireNonNull(live, "live");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /** Applies {@code request} to {@code record}, which the caller has checked for owner, roster and generation. */
    @Nonnull
    public CompletableFuture<Outcome> update(@Nonnull CompanionRecord record,
                                             @Nonnull BondedCompanionTalentActionRequest request) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(request, "request");
        if (!enabled.getAsBoolean()) {
            return CompletableFuture.completedFuture(Outcome.of(Status.DISABLED));
        }
        if (record.location().kind() == LocationKind.LIVE) {
            CompletableFuture<Outcome> changed = live.update(record, request);
            return changed != null ? changed : CompletableFuture.completedFuture(Outcome.of(Status.BODY_UNAVAILABLE));
        }
        return readSnapshot.apply(record.profileId()).thenApply(snapshot -> stored(record, request, snapshot));
    }

    /**
     * The level and talents a companion that is not active holds in its stored snapshot, or null
     * when it has none that can be read (a provisioned companion never summoned). Never fails.
     */
    @Nonnull
    public CompletableFuture<Stored> read(@Nonnull CompanionRecord record) {
        CompletableFuture<SnapshotEnvelope> snapshot;
        try {
            snapshot = readSnapshot.apply(record.profileId());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(null);
        }
        return snapshot.handle((envelope, failure) -> failure != null || envelope == null ? null : decode(envelope));
    }

    /** The talent state in a stored snapshot. Either part is null when the snapshot has no such component. */
    public record Stored(@Nullable TameworkLevelingComponent leveling, @Nullable TameworkTalentsComponent talents) {
    }

    /** Decodes the leveling and talents components of a snapshot; null when the snapshot cannot be read. */
    @Nullable
    public static Stored decode(@Nonnull SnapshotEnvelope snapshot) {
        try {
            BsonDocument entity = CompanionSnapshots.entity(snapshot);
            BsonDocument components = entity.isDocument(COMPONENTS) ? entity.getDocument(COMPONENTS) : new BsonDocument();
            return new Stored(
                    components.isDocument(LEVELING)
                            ? TameworkLevelingComponent.CODEC.decode(components.getDocument(LEVELING), new ExtraInfo())
                            : null,
                    components.isDocument(SnapshotPatch.TALENTS)
                            ? TameworkTalentsComponent.CODEC.decode(components.getDocument(SnapshotPatch.TALENTS), new ExtraInfo())
                            : null);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private Outcome stored(CompanionRecord record, BondedCompanionTalentActionRequest request,
                           @Nullable SnapshotEnvelope snapshot) {
        Stored state = snapshot == null ? null : decode(snapshot);
        TameworkLevelingComponent leveling = state == null ? null : state.leveling();
        if (leveling == null) {
            return Outcome.of(Status.NO_LEVEL_DATA);
        }
        TwTalentConfig config = configs.resolve(request.talentConfigId(), record.roleId());
        TameworkTalentsComponent updated =
                changed(state.talents(), leveling.getLevel(), leveling.getConfigId(), config, request);
        if (updated == null) {
            return Outcome.of(Status.REJECTED);
        }
        BsonDocument data = snapshot.data().clone();
        data.put("Entity", SnapshotPatch.withTalents(CompanionSnapshots.entity(snapshot), updated));
        SnapshotEnvelope patched = new SnapshotEnvelope(snapshot.profileId(), snapshot.format(), snapshot.generation(), data);
        CompanionSummary summary = withTalents(record.summary(), updated);
        boolean queued = index.atomically(() -> {
            // The fence is the revision the caller read, not the current one: any change since
            // then (a summon, a store, another purchase) refuses this one.
            if (!index.update(record.profileId(), record.revision(), b -> b.summary(summary)).applied()) {
                return false;
            }
            queueSnapshot.accept(patched);
            return true;
        });
        return queued ? new Outcome(Status.APPLIED, updated, leveling.getLevel(), leveling.getConfigId())
                : Outcome.of(Status.CONFLICT);
    }

    /** The summary with the spent points and tree of {@code talents}; stored companions are listed from it. */
    private static CompanionSummary withTalents(CompanionSummary s, TameworkTalentsComponent talents) {
        return new CompanionSummary(s.customName(), s.nameKey(), s.roleId(), s.iconId(), s.healthCurrent(),
                s.healthMax(), s.happinessConfigId(), s.happiness(), s.needsConfigId(), s.hunger(), s.thirst(),
                s.breedingPresent(), s.breedingEnabled(), s.breedingCooldownUntilMs(), s.breedingCooldownStartedAtMs(),
                s.breedingCooldownDurationMs(), s.harvestAlarmUntilMs(), s.levelingConfigId(), s.level(), s.currentXp(),
                s.totalXp(), talents.getSpentPoints(), s.traits(), s.observedAtMs(), s.harvestAlarmStartedAtMs(),
                s.harvestAlarmDurationMs(), s.traitsConfigId(), talents.getConfigId(), s.progression());
    }

    /**
     * The talents after {@code request}, or null when it is not allowed: no enabled tree, an
     * unknown or already bought talent, a level or prerequisite not met, too few points, or a
     * reset with nothing spent. Pure; {@code existing} is not changed.
     */
    @Nullable
    static TameworkTalentsComponent changed(@Nullable TameworkTalentsComponent existing, int level,
                                            @Nullable String levelingConfigId, @Nullable TwTalentConfig config,
                                            @Nonnull BondedCompanionTalentActionRequest request) {
        if (request.action() == BondedCompanionTalentActionRequest.Action.RESET) {
            if (existing == null || existing.getSpentPoints() <= 0 && existing.getPurchasedTalentIds().length == 0) {
                return null;
            }
            // An allocation that no longer fits the tree comes back empty, which is what a reset wants.
            TameworkTalentsComponent reset = CompanionTalentService.reconcileAllocation(existing, config).clone();
            reset.setSpentPoints(0);
            reset.setPurchasedTalentIds(new String[0]);
            return reset;
        }
        // An allocation made under another tree or allocation revision is dropped first, as on a live body.
        CompanionTalentService.PurchaseResult purchase = CompanionTalentService.purchase(
                CompanionTalentService.reconcileAllocation(existing, config), config, level, levelingConfigId,
                request.talentId());
        return purchase.applied() ? purchase.component() : null;
    }

    /** The tree the panel showed when it names one that is enabled, else the role's tree. */
    @Nullable
    private static TwTalentConfig configuredTree(@Nullable String presentedConfigId, String roleId) {
        if (presentedConfigId != null && !presentedConfigId.isBlank()) {
            TwTalentConfig presented = TwTalentConfig.resolveById(presentedConfigId);
            if (presented != null && presented.isEnabled()) {
                return presented;
            }
        }
        if (roleId.isBlank()) {
            return null;
        }
        TwTalentConfig config = TwTalentConfig.resolveForRole(roleId);
        return config != null && config.isEnabled() ? config : null;
    }
}
