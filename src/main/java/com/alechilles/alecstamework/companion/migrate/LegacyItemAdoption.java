package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * What a capture item written before 5.0 is to the companion index (plan 7 R17, R18), and the
 * one-time adoption of a 2.x item. Everything about old item shapes lives here. This class does
 * not go when the importer does: an old item can sit in a chest for years, so it stays for as
 * long as worlds that were imported exist (see the package note).
 *
 * <p>The shapes, by the keys an item carries:
 * <ul>
 * <li><b>5.0</b>: profile id and generation. Not handled here.</li>
 * <li><b>4.x</b>: profile id and the old capture snapshot id, no generation. It is an index item
 *     at generation 0 ({@link CaptureItemKeys#readIndexItem}); its state is the record's imported
 *     snapshot. Not handled here.</li>
 * <li><b>2.x</b>: {@code Tamework.TargetUuid} (the NPC UUID of the captured body) and no profile
 *     id. Its state is in the item's own metadata keys. 3.x and 4.x never rewrote these items,
 *     so an imported record for one has no snapshot.</li>
 * <li>A snapshot id without a profile id, or an id that is not a UUID: damaged, refused.</li>
 * </ul>
 * No death or lost item shape exists: those version 1 payloads were database rows, and the
 * importer turned them into snapshots.
 *
 * <p><b>Which record a 2.x item means.</b> The profile the import's alias file names for the
 * item's NPC UUID, else the NPC UUID itself as the profile id. The id is therefore the same for
 * every copy of the item, and no copy can make a second companion: the first use adopts the
 * record at generation 0 and the release moves it on, so every later copy finds a record that is
 * no longer an item at generation 0 and is refused. A released companion stays in the index as a
 * tombstone, so this holds after the companion is gone too.
 *
 * <p>{@link #decide} is pure. {@link #adopt} changes the index and queues a snapshot; call it on
 * the releasing player's world thread, as part of the release.
 */
public final class LegacyItemAdoption {
    /** What an old item is. */
    public enum Kind {
        /** No old shape: a 5.0 item, or no capture item at all. */
        NOT_LEGACY,
        /** A record exists and holds the companion's state: release through the index at generation 0. */
        INDEX_ITEM,
        /** An imported record at generation 0 with no state of its own, whose companion is in this item: the item's state restores it. */
        RESTORE_FROM_ITEM,
        /** A 2.x item no record knows: its first use creates the record from the item. */
        CREATE_FROM_PAYLOAD,
        /** Damaged, or a 2.x copy whose companion is elsewhere or has moved on from generation 0. */
        REFUSE
    }

    /**
     * @param profileId the record the item means; null for {@link Kind#NOT_LEGACY} and for a
     *                  damaged item
     * @param npcUuid   the NPC UUID a 2.x item names; null for any other shape
     */
    public record Decision(@Nonnull Kind kind, @Nullable UUID profileId, @Nullable UUID npcUuid) {
    }

    /** How an adoption ended. Only {@link #ADOPTED} lets the release go on. */
    public enum Result {
        /** The item is now backed by a record in an item at generation 0, with its state stored. */
        ADOPTED,
        /** Not an old capture item, or a damaged one. */
        INVALID,
        /** A copy of an item that was already released, or whose companion is elsewhere. */
        STALE,
        /** The item's state could not be read; nothing was changed, so the item keeps it. */
        UNREADABLE,
        /** The new owner may not own another companion; {@link Adoption#messageKey} says why. */
        LIMIT
    }

    /**
     * @param ref        the identity to stamp on the item; set only for {@link Result#ADOPTED}
     * @param messageKey the translation key of a population refusal; set only for {@link Result#LIMIT}
     */
    public record Adoption(@Nonnull Result result, @Nullable CaptureItemKeys.Ref ref, @Nullable String messageKey) {
        static Adoption of(Result result) {
            return new Adoption(result, null, null);
        }
    }

    /**
     * The alias file of the running server, for callers that cannot be handed one (the command
     * item view). Set once at plugin start, reset at shutdown; the value is immutable.
     */
    private static volatile LegacyAliases installed = LegacyAliases.EMPTY;

    private final CompanionIndex index;
    private final LegacyAliases aliases;
    private final Consumer<SnapshotEnvelope> queueSnapshot;
    private final BiFunction<CompanionRecord, CompanionRecord, CompanionAdmissionGate.Admission> admit;
    private final LongSupplier clock;

    /**
     * @param queueSnapshot queues a snapshot to the companion writer
     * @param admit         the admission check ({@code CompanionAdmissionGate::admit}), asked with a
     *                      null "before" for a new record; called under the index lock
     * @param clock         wall clock
     */
    public LegacyItemAdoption(@Nonnull CompanionIndex index, @Nonnull LegacyAliases aliases,
                              @Nonnull Consumer<SnapshotEnvelope> queueSnapshot,
                              @Nonnull BiFunction<CompanionRecord, CompanionRecord, CompanionAdmissionGate.Admission> admit,
                              @Nonnull LongSupplier clock) {
        this.index = Objects.requireNonNull(index, "index");
        this.aliases = Objects.requireNonNull(aliases, "aliases");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.admit = Objects.requireNonNull(admit, "admit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Makes {@code aliases} the alias file {@link #profileOf} reads; {@link LegacyAliases#EMPTY} clears it. */
    public static void install(@Nonnull LegacyAliases aliases) {
        installed = Objects.requireNonNull(aliases, "aliases");
    }

    /**
     * The profile an imported 3.x/4.x world knew {@code npcUuid} for, whatever the alias's state
     * (plan 7 R19); null when the world was never imported or never saw that body. Any thread.
     */
    @Nullable
    public static UUID profileOf(@Nonnull UUID npcUuid) {
        return installed.byNpcUuid(npcUuid).map(LegacyAliases.Entry::profileId).orElse(null);
    }

    /**
     * What the item with this metadata is. Pure: reads the record through {@code records} and
     * changes nothing. An item with a profile id is never handled here (5.0 and 4.x items are
     * index items), and a snapshot id without one is damaged.
     *
     * @param hasBody whether a body is registered for a profile id right now
     */
    @Nonnull
    public static Decision decide(@Nullable BsonDocument metadata, @Nonnull Function<UUID, CompanionRecord> records,
                                  @Nonnull LegacyAliases aliases, @Nonnull Predicate<UUID> hasBody) {
        if (metadata == null || !metadata.containsKey(TameworkMetadataKeys.TARGET_UUID)) {
            return new Decision(Kind.NOT_LEGACY, null, null);
        }
        UUID npcUuid = uuid(metadata, TameworkMetadataKeys.TARGET_UUID);
        if (npcUuid == null || metadata.containsKey(TameworkMetadataKeys.COMPANION_PROFILE_ID)
                || metadata.containsKey(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID)) {
            return new Decision(Kind.REFUSE, null, null);
        }
        UUID profileId = aliases.byNpcUuid(npcUuid).map(LegacyAliases.Entry::profileId).orElse(npcUuid);
        CompanionRecord record = records.apply(profileId);
        if (record == null) {
            return new Decision(Kind.CREATE_FROM_PAYLOAD, profileId, npcUuid);
        }
        if (record.generation() != 0L) {
            return new Decision(Kind.REFUSE, profileId, npcUuid);
        }
        // The importer stores no snapshot for a capture whose state is in its item, and then the
        // record has no snapshot time. A record that has one holds newer state than this item.
        boolean stateInItem = record.lastSnapshotAtMs() == 0L;
        return switch (record.location().kind()) {
            case ITEM -> new Decision(stateInItem ? Kind.RESTORE_FROM_ITEM : Kind.INDEX_ITEM, profileId, npcUuid);
            // 4.x imported some 2.x captures as bodies in unloaded chunks: the old rows name the
            // captured body as the current one and hold no state for it. With no such body in
            // the world the companion is in this item. A registered body means it is out there
            // and the item is a stale copy. So does a record 5.0 has matched to its body before
            // (it has a real position) even while that body is unloaded and not yet snapshotted.
            case LIVE -> new Decision(stateInItem && npcUuid.equals(record.currentNpcUuid())
                    && LegacyBodyResolution.neverSighted(record) && !hasBody.test(profileId)
                    ? Kind.RESTORE_FROM_ITEM : Kind.REFUSE, profileId, npcUuid);
            // The saved-chunk pass found no body for such a record and listed it as lost. With its
            // state in this item and no body anywhere the pass could read, the companion is in the item.
            case LOST -> new Decision(stateInItem && !hasBody.test(profileId)
                    && LegacyBodyResolution.rejoins(aliases.byNpcUuid(npcUuid).orElse(null), record)
                    && LegacyBodyResolution.CAUSE_BODY_NOT_FOUND.equals(record.location().cause())
                    ? Kind.RESTORE_FROM_ITEM : Kind.REFUSE, profileId, npcUuid);
            default -> new Decision(Kind.REFUSE, profileId, npcUuid);
        };
    }

    /**
     * Makes the item with this metadata releasable through the index: creates its record when no
     * record knows it, and stores the item's state as the record's snapshot when the record has
     * none. A record imported as a live body that is really in this item (see {@link #decide}) is
     * moved into the item at generation 0 first; if its old body ever loads, the record has moved
     * on and the old-body pass removes that body. The snapshot is queued before this returns and
     * before the record changes, so a stop right after keeps the state. The item is not changed:
     * repeating this for the same item or a copy queues the same state again and creates nothing.
     *
     * <p>A new record is owned as a capture would leave it ({@link CaptureItemOwnership#captureOwner}):
     * by the owner the item names, else by {@code player} when the item says the companion is
     * tamed, else by no one. The release then applies the ownership mode as for any item.
     *
     * @param player  the releasing player
     * @param hasBody whether a body is registered for a profile id right now
     */
    @Nonnull
    public Adoption adopt(@Nullable BsonDocument metadata, @Nonnull UUID player, @Nullable String playerName,
                          @Nonnull Predicate<UUID> hasBody) {
        // One locked step: a second copy used at the same moment sees the record the first made.
        return index.atomically(() -> {
            Decision decision = decide(metadata, index::get, aliases, hasBody);
            UUID profileId = decision.profileId();
            return switch (decision.kind()) {
                case NOT_LEGACY -> Adoption.of(Result.INVALID);
                case REFUSE -> Adoption.of(profileId == null ? Result.INVALID : Result.STALE);
                case INDEX_ITEM -> adopted(profileId);
                case RESTORE_FROM_ITEM -> restoreFromItem(metadata, index.get(profileId), decision.npcUuid());
                case CREATE_FROM_PAYLOAD -> create(metadata, profileId, decision.npcUuid(), player, playerName);
            };
        });
    }

    private Adoption restoreFromItem(BsonDocument metadata, CompanionRecord record, UUID npcUuid) {
        UUID profileId = record.profileId();
        LegacyState.State state = LegacyState.itemState(metadata, clock.getAsLong(), new LegacyState.Identity(
                npcUuid, record.roleId(), record.ownerUuid(), record.ownerName(),
                record.displayName(), tamed(metadata, record.ownerUuid() != null), record.toolIds()));
        if (state == null) {
            return Adoption.of(Result.UNREADABLE);
        }
        queueSnapshot.accept(SnapshotEnvelope.importedState(profileId, 0L, state.json()));
        if (record.location().kind() == LocationKind.ITEM) {
            return adopted(profileId);
        }
        // Owner and generation stay, so the owner's counts and the item's generation 0 still hold.
        return index.update(profileId, record.revision(), b -> b.location(CompanionLocation.item())
                .currentNpcUuid(null).summonedUntilMs(0L)).applied() ? adopted(profileId) : Adoption.of(Result.STALE);
    }

    private Adoption create(BsonDocument metadata, UUID profileId, UUID npcUuid, UUID player,
                            @Nullable String playerName) {
        String roleId = text(metadata, TameworkMetadataKeys.CAPTURE_ROLE_ID);
        if (roleId == null) {
            return Adoption.of(Result.UNREADABLE);
        }
        if (index.byNpcUuid(npcUuid) != null) {
            // A living body with this NPC UUID has its own record (for example after start-fresh):
            // the item must not make a second companion of it.
            return Adoption.of(Result.STALE);
        }
        UUID itemOwner = uuid(metadata, TameworkMetadataKeys.OWNER_UUID);
        boolean tamed = tamed(metadata, itemOwner != null);
        UUID owner = CaptureItemOwnership.captureOwner(itemOwner, tamed, false, player);
        String ownerName = owner == null ? null
                : owner.equals(itemOwner) ? text(metadata, TameworkMetadataKeys.OWNER_NAME) : playerName;
        long now = clock.getAsLong();
        LegacyState.State state = LegacyState.itemState(metadata, now,
                new LegacyState.Identity(npcUuid, roleId, owner, ownerName, null, tamed, List.of()));
        if (state == null) {
            return Adoption.of(Result.UNREADABLE);
        }
        CompanionAdmissionGate.Admission admission = admit.apply(null,
                CompanionRecord.builder(profileId, roleId, CompanionLocation.item())
                        .ownerUuid(owner).ownerName(ownerName).displayName(state.customName())
                        .summary(state.summary()).build());
        if (!admission.admitted()) {
            return new Adoption(Result.LIMIT, null, admission.denial().messageKey());
        }
        queueSnapshot.accept(SnapshotEnvelope.importedState(profileId, 0L, state.json()));
        return index.insert(admission.record()).applied() ? adopted(profileId) : Adoption.of(Result.STALE);
    }

    private static Adoption adopted(UUID profileId) {
        return new Adoption(Result.ADOPTED, new CaptureItemKeys.Ref(profileId, 0L), null);
    }

    /** The item's tamed flag; {@code fallback} when the item has none. */
    private static boolean tamed(BsonDocument metadata, boolean fallback) {
        BsonValue value = metadata.get(TameworkMetadataKeys.TAMED);
        return value != null && value.isBoolean() ? value.asBoolean().getValue() : fallback;
    }

    @Nullable
    private static String text(BsonDocument metadata, String key) {
        BsonValue value = metadata.get(key);
        return value != null && value.isString() && !value.asString().getValue().isBlank()
                ? value.asString().getValue().trim() : null;
    }

    @Nullable
    private static UUID uuid(BsonDocument metadata, String key) {
        String value = text(metadata, key);
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
