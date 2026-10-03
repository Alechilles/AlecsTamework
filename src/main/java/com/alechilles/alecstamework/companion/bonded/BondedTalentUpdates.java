package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.api.BondedCompanionTalentActionRequest;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.flow.SnapshotPatch;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSettings;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * comes back with the new talents. A companion imported from 3.x or 4.x that has not been
 * restored yet keeps them in its format 0 state snapshot, which is read and patched the same way
 * and stays format 0.
 *
 * <p>The stored half also serves a dead or lost ordinary companion's talent page
 * ({@link #updateStored}, {@link #read}); nothing in it is specific to bonded records.</p>
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
    /** The talents member of the imported state JSON, as {@code CoopResidentStateSnapshotCodec} names it. */
    private static final String IMPORTED_TALENTS = "talents";
    private static final Gson GSON = new Gson();

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
     * @param rejection        the {@link CompanionTalentService} message of the rule that refused
     *                         a stored change; set when {@code status} is REJECTED by
     *                         {@link #updateStored} or a stored {@link #update}, else null
     */
    public record Outcome(@Nonnull Status status, @Nullable TameworkTalentsComponent talents, int level,
                          @Nullable String levelingConfigId, @Nullable String rejection) {
        public Outcome {
            Objects.requireNonNull(status, "status");
        }

        @Nonnull
        public static Outcome of(@Nonnull Status status) {
            return new Outcome(status, null, 0, null, null);
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

    /**
     * @param configs the tree a change is checked against
     * @param enabled whether talents are turned on
     */
    public BondedTalentUpdates(@Nonnull CompanionIndex index,
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
        return readSnapshot.apply(record.profileId()).thenApply(snapshot -> stored(record, request.action(),
                request.talentId(), request.talentConfigId(), snapshot));
    }

    /**
     * Applies a purchase or reset to the stored snapshot of {@code record}, a companion with no
     * body (dead, lost or stored), checked against its role's talent tree. The caller has checked
     * owner and generation; the change is fenced on {@code record.revision()} as a bonded one is.
     * An active record ends {@link Status#BODY_UNAVAILABLE}: its body holds the talents.
     *
     * <p>The snapshot is decoded on the thread that completes its read, which is the caller's
     * when the snapshot is still queued for writing, so a world-thread caller calls this from
     * another thread. A failed read fails the returned future.</p>
     */
    @Nonnull
    public CompletableFuture<Outcome> updateStored(@Nonnull CompanionRecord record,
                                                   @Nonnull BondedCompanionTalentActionRequest.Action action,
                                                   @Nullable String talentId) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(action, "action");
        if (!enabled.getAsBoolean()) {
            return CompletableFuture.completedFuture(Outcome.of(Status.DISABLED));
        }
        if (record.location().kind() == LocationKind.LIVE) {
            return CompletableFuture.completedFuture(Outcome.of(Status.BODY_UNAVAILABLE));
        }
        return readSnapshot.apply(record.profileId())
                .thenApply(snapshot -> stored(record, action, talentId, null, snapshot));
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
            if (snapshot.format() == SnapshotEnvelope.FORMAT_IMPORTED_STATE) {
                CoopResidentStateSnapshot state = RestoreRules.importedState(snapshot);
                return state == null ? null : new Stored(state.leveling(), state.talents());
            }
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

    private Outcome stored(CompanionRecord record, BondedCompanionTalentActionRequest.Action action,
                           @Nullable String talentId, @Nullable String presentedConfigId,
                           @Nullable SnapshotEnvelope snapshot) {
        Stored state = snapshot == null ? null : decode(snapshot);
        TameworkLevelingComponent leveling = state == null ? null : state.leveling();
        if (leveling == null) {
            return Outcome.of(Status.NO_LEVEL_DATA);
        }
        TwTalentConfig config = configs.resolve(presentedConfigId, record.roleId());
        Change change =
                changed(state.talents(), leveling.getLevel(), leveling.getConfigId(), config, action, talentId);
        TameworkTalentsComponent updated = change.talents();
        if (updated == null) {
            return new Outcome(Status.REJECTED, null, 0, null, change.rejection());
        }
        SnapshotEnvelope patched = withTalents(snapshot, updated);
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
        return queued ? new Outcome(Status.APPLIED, updated, leveling.getLevel(), leveling.getConfigId(), null)
                : Outcome.of(Status.CONFLICT);
    }

    /**
     * The snapshot with its talents replaced and everything else kept, in the format it came in.
     * An imported state gets its {@code talents} member rewritten as its codec encodes that
     * component; {@link #decode} has already read that JSON, so it parses.
     */
    private static SnapshotEnvelope withTalents(SnapshotEnvelope snapshot, TameworkTalentsComponent talents) {
        if (snapshot.format() == SnapshotEnvelope.FORMAT_IMPORTED_STATE) {
            JsonObject state = JsonParser.parseString(snapshot.importedStateJson()).getAsJsonObject();
            state.add(IMPORTED_TALENTS, GSON.toJsonTree(talents, TameworkTalentsComponent.class));
            return SnapshotEnvelope.importedState(snapshot.profileId(), snapshot.generation(), state.toString());
        }
        BsonDocument data = snapshot.data().clone();
        data.put("Entity", SnapshotPatch.withTalents(CompanionSnapshots.entity(snapshot), talents));
        return new SnapshotEnvelope(snapshot.profileId(), snapshot.format(), snapshot.generation(), data);
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

    /** The talents after a change, or the {@link CompanionTalentService} message of the rule that refused it. */
    private record Change(@Nullable TameworkTalentsComponent talents, @Nullable String rejection) {
    }

    /**
     * The talents after {@code action}, or the refusal when it is not allowed: no enabled tree, an
     * unknown or already bought talent, a level or prerequisite not met, too few points, or a
     * reset with nothing spent. Pure; {@code existing} is not changed.
     */
    private static Change changed(@Nullable TameworkTalentsComponent existing, int level,
                                  @Nullable String levelingConfigId, @Nullable TwTalentConfig config,
                                  @Nonnull BondedCompanionTalentActionRequest.Action action,
                                  @Nullable String talentId) {
        if (action == BondedCompanionTalentActionRequest.Action.RESET) {
            if (existing == null || existing.getSpentPoints() <= 0 && existing.getPurchasedTalentIds().length == 0) {
                return new Change(null, "No talent points are spent.");
            }
            // An allocation that no longer fits the tree comes back empty, which is what a reset wants.
            TameworkTalentsComponent reset = CompanionTalentService.reconcileAllocation(existing, config).clone();
            reset.setSpentPoints(0);
            reset.setPurchasedTalentIds(new String[0]);
            return new Change(reset, null);
        }
        // An allocation made under another tree or allocation revision is dropped first, as on a live body.
        CompanionTalentService.PurchaseResult purchase = CompanionTalentService.purchase(
                CompanionTalentService.reconcileAllocation(existing, config), config, level, levelingConfigId,
                talentId);
        return purchase.applied() ? new Change(purchase.component(), null) : new Change(null, purchase.message());
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
