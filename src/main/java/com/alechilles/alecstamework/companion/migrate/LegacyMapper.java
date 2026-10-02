package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.ExtensionEntries;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * Maps the rows of a 3.x/4.x world to companion records, snapshots and aliases (plan 7 task 2;
 * spec 6.3, rulings R3 and R5 to R13). Pure: it reads {@link LegacyRows} and returns an
 * {@link ImportResult}; writing the store is the import run's job.
 *
 * <p>The lifecycle row decides where a companion is (R11). Every record starts at generation 0
 * (R8) with no domain claims (R13) and roster slot -1. Old time values are copied as they are:
 * signs are kept and 0 stays "unset". The one time that is computed is a running timed summon's
 * end, which restarts at the import time (R6).</p>
 */
public final class LegacyMapper {
    /** LOST cause of a profile the old runtime had as UNRESOLVED, or in a state this build does not know. */
    public static final String CAUSE_UNRESOLVED = "IMPORTED_UNRESOLVED";
    /** LOST cause of a profile whose old row named no usable body, world or coop slot. */
    public static final String CAUSE_NO_BODY = "IMPORTED_NO_BODY";
    /** LOST cause of the older of two profiles that claimed the same current NPC UUID (R9). */
    public static final String CAUSE_NPC_UUID_COLLISION = "IMPORTED_NPC_UUID_COLLISION";

    private static final String BONDED_EXTENSION_KEY = "bonded";
    /** The world assumed when no old row names any; Hytale's default world name. */
    private static final String DEFAULT_WORLD = "default";

    private final long importTimeMs;
    private final Map<UUID, CompanionRecord> records = new LinkedHashMap<>();
    private final Map<UUID, SnapshotEnvelope> snapshots = new LinkedHashMap<>();
    /** Stale alias candidates in priority order; current ones are derived from the final records. */
    private final Map<UUID, UUID> knownBodies = new LinkedHashMap<>();
    private final List<ImportResult.Skipped> skipped = new ArrayList<>();
    private final List<UUID> quarantined = new ArrayList<>();
    private final List<UUID> liveWithoutCheckpoint = new ArrayList<>();
    private final List<UUID> importedLost = new ArrayList<>();
    private final List<UUID> withoutState = new ArrayList<>();
    private final List<UUID> dyingCheckpoints = new ArrayList<>();
    private final List<UUID> liveWorldGuessed = new ArrayList<>();
    private final List<UUID> stateInItem = new ArrayList<>();
    private final List<UUID> liveUsedHistory = new ArrayList<>();
    private final List<UUID> liveUsedOldDeathState = new ArrayList<>();
    /** The last body of each record imported LOST for want of a place, by NPC UUID. */
    private final Map<UUID, UUID> rejoinBodies = new LinkedHashMap<>();
    private int bondedRecords;

    private LegacyMapper(long importTimeMs) {
        this.importTimeMs = importTimeMs;
    }

    /**
     * @param importTimeMs the wall-clock time of the import; a running timed summon ends this
     *                     long after it plus its remaining time
     */
    @Nonnull
    public static ImportResult map(@Nonnull LegacyRows rows, long importTimeMs) {
        LegacyMapper mapper = new LegacyMapper(importTimeMs);
        LegacyRows.Bonded bonded = rows.bonded();
        Set<UUID> bondedIds = new HashSet<>();
        if (bonded != null) {
            for (LegacyRows.BondedProfile profile : bonded.profiles()) {
                UUID id = uuid(profile.profileId());
                if (id != null && !blank(profile.roleId()) && uuid(profile.ownerUuid()) != null) {
                    bondedIds.add(id);
                }
            }
        }
        if (rows.state() != null) {
            mapper.mapState(rows.state(), bondedIds);
        }
        if (bonded != null) {
            mapper.mapBonded(bonded);
        }
        return mapper.result(rows.state() == null ? 0 : rows.state().unfinishedOperations());
    }

    // ---- tamework-state.sqlite ----

    private void mapState(LegacyRows.State state, Set<UUID> bondedIds) {
        Map<String, LegacyRows.Lifecycle> lifecycles = byKey(state.lifecycles(), LegacyRows.Lifecycle::profileId);
        Map<String, List<LegacyRows.Alias>> aliases = group(state.aliases(), LegacyRows.Alias::profileId);
        Map<String, List<LegacyRows.Snapshot>> snapshotRows = group(state.snapshots(), LegacyRows.Snapshot::profileId);
        Map<String, List<LegacyRows.EntityCheckpoint>> checkpoints =
                group(state.entityCheckpoints(), LegacyRows.EntityCheckpoint::profileId);
        Map<String, List<LegacyRows.ToolLink>> tools = group(state.toolLinks(), LegacyRows.ToolLink::profileId);
        Map<String, LegacyRows.RosterMembership> rosters =
                byKey(state.rosterMemberships(), LegacyRows.RosterMembership::profileId);
        Map<String, LegacyRows.TimedLease> leases = byKey(state.timedLeases(), LegacyRows.TimedLease::profileId);
        Map<String, LegacyRows.CoopSlot> coopSlots = byKey(state.coopSlots(), LegacyRows.CoopSlot::coopKey);
        Map<String, LegacyRows.CoopResidency> residencies =
                byKey(state.coopResidencies(), LegacyRows.CoopResidency::profileId);
        Map<String, LegacyRows.Provisioning> origins =
                byKey(state.provisioningRecords(), LegacyRows.Provisioning::profileId);
        Map<String, List<LegacyRows.ExtensionData>> extensions =
                group(state.extensionData(), LegacyRows.ExtensionData::profileId);
        String commonWorld = commonWorld(state);

        for (LegacyRows.Alias alias : state.aliases()) {
            knownBody(alias.npcUuid(), alias.profileId());
        }
        for (LegacyRows.Profile profile : state.profiles()) {
            String key = profile.profileId();
            UUID id = uuid(key);
            LegacyRows.Lifecycle life = lifecycles.get(key);
            UUID owner = life == null ? null : uuid(life.ownerUuid());
            if (id == null || life != null && life.ownerUuid() != null && owner == null) {
                skip("companion_profile", key, ImportResult.SKIP_INVALID_ID);
            } else if (bondedIds.contains(id)) {
                skip("companion_profile", key, ImportResult.SKIP_BONDED_PROFILE);
            } else if (life == null) {
                skip("companion_profile", key, ImportResult.SKIP_NO_LIFECYCLE);
            } else if (blank(profile.roleId())) {
                skip("companion_profile", key, ImportResult.SKIP_NO_ROLE);
            } else {
                try {
                    mapProfile(id, owner, profile, life, aliases.getOrDefault(key, List.of()),
                            snapshotRows.getOrDefault(key, List.of()), checkpoints.getOrDefault(key, List.of()),
                            tools.getOrDefault(key, List.of()), rosters.get(key), leases.get(key), coopSlots,
                            residencies.get(key), origins.get(key), extensions.getOrDefault(key, List.of()),
                            commonWorld);
                } catch (RuntimeException failure) {
                    failed("companion_profile", id, failure);
                }
            }
        }
    }

    /**
     * The world most lifecycle rows name, for bodies whose row names none. The old runtime cleared
     * the world of an UNLOADED profile, so a server's unloaded companions have no world on record.
     */
    private static String commonWorld(LegacyRows.State state) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LegacyRows.Lifecycle life : state.lifecycles()) {
            String world = firstText(life.worldKey(), life.ownerWorldKey());
            if (world != null) {
                counts.merge(world, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            for (LegacyRows.Profile profile : state.profiles()) {
                if (!blank(profile.lastKnownWorldKey())) {
                    counts.merge(profile.lastKnownWorldKey(), 1, Integer::sum);
                }
            }
        }
        String common = DEFAULT_WORLD;
        int most = 0;
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (count.getValue() > most) {
                most = count.getValue();
                common = count.getKey();
            }
        }
        return common;
    }

    /**
     * A state snapshot row with its readable state.
     *
     * @param matchesState whether the row is the one the lifecycle state calls for: the row a
     *                     coop residency names, or a row of the state's own kind
     */
    private record Snapshot(LegacyRows.Snapshot row, LegacyState.State state, boolean matchesState) {
    }

    private void mapProfile(UUID id, @Nullable UUID owner, LegacyRows.Profile profile, LegacyRows.Lifecycle life,
                            List<LegacyRows.Alias> aliases, List<LegacyRows.Snapshot> snapshotRows,
                            List<LegacyRows.EntityCheckpoint> checkpointRows, List<LegacyRows.ToolLink> tools,
                            @Nullable LegacyRows.RosterMembership roster, @Nullable LegacyRows.TimedLease lease,
                            Map<String, LegacyRows.CoopSlot> coopSlots, @Nullable LegacyRows.CoopResidency residency,
                            @Nullable LegacyRows.Provisioning origin, List<LegacyRows.ExtensionData> extensions,
                            String commonWorld) {
        String ownerName = metadata(profile.metadataJson(), "owner_name");
        CompanionRecord.Builder record = CompanionRecord.builder(id, profile.roleId().trim(), CompanionLocation.item())
                .revision(Math.max(0L, life.revision()))
                .ownerUuid(owner)
                .ownerName(ownerName)
                .homeWorld(life.ownerWorldKey())
                .rosterId(roster == null ? null : roster.familyId())
                .updatedAtMs(profile.updatedAtMs());
        if (origin != null) {
            record.origin(origin.callerNamespace(), origin.callerKey());
        }
        if (life.quarantineIncidentId() != null) {
            quarantined.add(id);
        }
        UUID currentAlias = currentAlias(aliases, life);
        String customName = metadata(profile.metadataJson(), "custom_name");
        for (LegacyRows.Snapshot row : snapshotRows) {
            UUID body = LegacyState.npcUuid(row.payloadJson());
            if (body != null) {
                knownBodies.putIfAbsent(body, id);
            }
        }

        if ("RELEASED".equals(life.lifecycleState())) {
            // A tombstone keeps nothing but its identity: no snapshot, tools or extension data.
            records.put(id, record.location(CompanionLocation.released(null)).displayName(customName).build());
            return;
        }
        // Every link type names a command tool: the 2.x import typed a link by the table it came
        // from, and the 4.x restore put them all into the body's command links.
        LinkedHashSet<String> toolIds = new LinkedHashSet<>();
        tools.forEach(link -> toolIds.add(link.toolUuid()));
        record.toolIds(List.copyOf(toolIds));
        for (LegacyRows.ExtensionData row : extensions) {
            String rowKey = row.profileId() + "|" + row.namespace() + "|" + row.dataKey();
            if (row.namespace().contains("/")) {
                skip("profile_extension_data", rowKey, ImportResult.SKIP_NAMESPACE_SLASH);
            } else if (!ExtensionEntries.publicNamespace(row.namespace())) {
                // Tamework's own rows (the 4.x managed coop production watermark) have no place on
                // a record: a 5.0 coop keeps its production watermark on the coop block.
                skip("profile_extension_data", rowKey, ImportResult.SKIP_RESERVED_NAMESPACE);
            } else {
                // Profile data's public revision is the stored one, and both start at 1.
                record.extension(ExtensionEntries.key(row.namespace(), row.dataKey()),
                        new ExtensionEntry(Math.max(1L, row.revision()), row.jsonPayload()));
            }
        }
        String tamed = metadata(profile.metadataJson(), "tamed");
        LegacyState.Identity identity = new LegacyState.Identity(currentAlias == null ? id : currentAlias,
                profile.roleId().trim(), owner, ownerName, customName,
                tamed == null ? null : Boolean.valueOf(tamed), List.copyOf(toolIds));

        CompanionLocation location;
        Snapshot state = null;
        LegacyState.Checkpoint checkpoint = null;
        String liveWorld = null;
        switch (life.lifecycleState()) {
            case "ACTIVE", "UNLOADED" -> {
                checkpoint = chooseCheckpoint(checkpointRows, currentAlias);
                String world = firstText(life.worldKey(), checkpoint == null ? null : checkpoint.worldKey(),
                        profile.lastKnownWorldKey(), life.ownerWorldKey());
                liveWorld = world;
                if (checkpoint == null) {
                    // An old snapshot is better than nothing for a body that is out in the world,
                    // but not an old death: that is the state of a life it has since left behind.
                    state = choose(snapshotRows.stream().filter(row -> row.current()
                            || !LegacyState.KIND_DEATH.equals(row.snapshotKind())).toList(), null, List.of(), identity);
                    if (state != null && !state.row().current()) {
                        liveUsedHistory.add(id);
                    }
                    if (state == null) {
                        // Last resort: the state the body had when it died in an earlier life,
                        // without the needs that killed it. The record is alive, so no death timers.
                        Snapshot died = choose(snapshotRows.stream().filter(row -> !row.current()
                                && LegacyState.KIND_DEATH.equals(row.snapshotKind())).toList(), null, List.of(), identity);
                        LegacyState.State alive = died == null ? null : LegacyState.withoutNeeds(died.state());
                        if (alive != null) {
                            state = new Snapshot(died.row(), alive, false);
                            liveUsedOldDeathState.add(id);
                        }
                    }
                }
                if (currentAlias == null) {
                    location = lost(id, CAUSE_NO_BODY, null);
                } else {
                    if (world == null) {
                        // The body exists, so the record must be LIVE or the body would be fenced
                        // out when its chunk loads. Its first sighting corrects the world.
                        world = commonWorld;
                        liveWorldGuessed.add(id);
                    }
                    if (checkpoint != null) {
                        location = CompanionLocation.live(world, checkpoint.x(), checkpoint.y(), checkpoint.z());
                    } else {
                        location = CompanionLocation.live(world, 0.0, 0.0, 0.0);
                        liveWithoutCheckpoint.add(id);
                    }
                }
            }
            case "CAPTURED" -> {
                location = CompanionLocation.item();
                state = choose(snapshotRows, null, List.of(LegacyState.KIND_CAPTURE), identity);
            }
            case "COOP" -> {
                LegacyRows.CoopSlot slot = life.locationKey() == null ? null : coopSlots.get(life.locationKey());
                if (slot == null && residency != null) {
                    slot = coopSlots.get(residency.coopKey());
                }
                location = slot == null || blank(slot.worldKey()) || slot.residentSlot() < 0
                        ? lost(id, CAUSE_NO_BODY, currentAlias)
                        : CompanionLocation.coop(slot.worldKey(), slot.x(), slot.y(), slot.z(), slot.residentSlot());
                state = choose(snapshotRows, residency == null ? null : residency.snapshotId(), List.of("coop"),
                        identity);
            }
            case "ROSTER_STORED", "PROVISIONED_DORMANT" -> {
                // A companion with a timed lease is summoned through it, whatever stored it (R6).
                StoredReason reason = lease != null ? StoredReason.TIMED
                        : "ROSTER_STORED".equals(life.lifecycleState()) ? StoredReason.ROSTER
                        : StoredReason.PROVISIONED;
                location = CompanionLocation.stored(reason);
                state = choose(snapshotRows, null, List.of("timed_summon", "full_state_projection"), identity);
            }
            case "DEAD_REVIVABLE" -> {
                state = choose(snapshotRows, null, List.of(LegacyState.KIND_DEATH), identity);
                LegacyState.Death death = death(snapshotRows);
                location = CompanionLocation.dead(death == null ? null : death.cause());
                if (death != null) {
                    record.diedAtMs(death.diedAtMs()).reviveAvailableAtMs(death.reviveAvailableAtMs());
                }
            }
            case "LOST" -> {
                location = CompanionLocation.lost(null);
                state = choose(snapshotRows, null, List.of(LegacyState.KIND_LOST), identity);
            }
            default -> {
                location = lost(id, CAUSE_UNRESOLVED, currentAlias);
                state = choose(snapshotRows, null, List.of(), identity);
            }
        }

        boolean live = location.kind() == LocationKind.LIVE;
        if (live) {
            record.currentNpcUuid(currentAlias);
            if (lease != null && lease.remainingMs() != null) {
                record.summonedUntilMs(saturatedAdd(importTimeMs, Math.max(0L, lease.remainingMs())));
            }
        } else if (lease != null && lease.cooldownUntilMs() != null) {
            record.summonCooldownUntilMs(lease.cooldownUntilMs());
        }
        if (checkpoint != null) {
            snapshots.put(id, new SnapshotEnvelope(id, CompanionSnapshots.FORMAT, 0L,
                    new BsonDocument("Entity", checkpoint.entity())
                            .append("World", new BsonString(liveWorld == null ? commonWorld : liveWorld))
                            .append("GameTimeMs", new BsonInt64(0L))));
            record.summary(checkpoint.summary()).lastSnapshotAtMs(checkpoint.capturedAtMs());
            record.roleId(bodyRole(profile.roleId(), checkpoint.summary().roleId(), true));
            if (customName == null) {
                customName = checkpoint.customName();
            }
            if (checkpoint.dying()) {
                dyingCheckpoints.add(id);
            }
        } else if (state != null) {
            snapshots.put(id, SnapshotEnvelope.importedState(id, 0L, state.state().json()));
            record.summary(state.state().summary()).lastSnapshotAtMs(state.row().createdAtMs());
            record.roleId(bodyRole(profile.roleId(), state.state().summary().roleId(), state.matchesState()));
            if (customName == null) {
                customName = state.state().customName();
            }
        } else if (location.kind() == LocationKind.ITEM) {
            // The old rows hold no state for this capture: a 2.x capture kept it in the item. An
            // empty state here would let a release silently replace it, so there is no snapshot.
            stateInItem.add(id);
        } else {
            // No readable state: a restore builds the body from the role (R5 as amended).
            snapshots.put(id, SnapshotEnvelope.importedState(id, 0L,
                    LegacyState.emptyState(currentAlias == null ? id : currentAlias)));
            if (!live) {
                withoutState.add(id);
            }
        }
        records.put(id, record.location(location).displayName(customName).build());
    }

    /**
     * The record's role. The body is the authority in 5.0, so the role of a checkpoint, or of a
     * snapshot that describes the companion where it is now ({@code authoritative}), is taken
     * outright: a restore spawns from the record's role, and the profile row can name a form the
     * companion has since left. Any other snapshot only corrects the spelling, because the old
     * profile table lower-cased some role ids ({@code tamed_chicken}).
     */
    private static String bodyRole(String profileRole, @Nullable String bodyRole, boolean authoritative) {
        return !blank(bodyRole) && (authoritative || bodyRole.equalsIgnoreCase(profileRole.trim()))
                ? bodyRole.trim() : profileRole.trim();
    }

    /** The profile's CURRENT alias, else the body its lifecycle row names. */
    @Nullable
    private static UUID currentAlias(List<LegacyRows.Alias> aliases, LegacyRows.Lifecycle life) {
        for (LegacyRows.Alias alias : aliases) {
            UUID npcUuid = "CURRENT".equals(alias.aliasState()) ? uuid(alias.npcUuid()) : null;
            if (npcUuid != null) {
                return npcUuid;
            }
        }
        return "LIVE_ENTITY".equals(life.locationKind()) ? uuid(life.locationKey()) : null;
    }

    /**
     * The checkpoint keyed by the current alias, else the one updated last (R3). The reader
     * already hands over one row per profile; rows are matched by key and time here, so only the
     * chosen row's body is parsed.
     */
    @Nullable
    private LegacyState.Checkpoint chooseCheckpoint(List<LegacyRows.EntityCheckpoint> rows, @Nullable UUID currentAlias) {
        List<LegacyRows.EntityCheckpoint> ordered = new ArrayList<>(rows);
        String currentKey = currentAlias == null ? null : "alias:" + currentAlias;
        ordered.sort(Comparator.comparing((LegacyRows.EntityCheckpoint row) -> row.dataKey().equalsIgnoreCase(currentKey))
                .thenComparingLong(LegacyRows.EntityCheckpoint::updatedAtMs).reversed());
        for (LegacyRows.EntityCheckpoint row : ordered) {
            LegacyState.Checkpoint checkpoint = LegacyState.checkpoint(row.jsonPayload(), row.updatedAtMs());
            if (checkpoint != null) {
                return checkpoint;
            }
            skip("profile_extension_data", row.profileId() + "|" + LegacyReader.ENTITY_CHECKPOINT_NAMESPACE
                    + "|" + row.dataKey(), ImportResult.SKIP_UNREADABLE);
        }
        return null;
    }

    /**
     * The snapshot a record is restored from. In order of weight: the row named by
     * {@code snapshotId} (a coop residency); a row that holds progression over one that holds
     * only identity (a version 1 lost payload); a current row over an old one; a row of a kind
     * that matches the lifecycle state; the highest source lifecycle revision; the newest.
     * Rows are decoded in that order until one is readable, so old rows cost nothing when a
     * better one exists. A version 1 capture payload holds no state and is never a candidate.
     */
    @Nullable
    private Snapshot choose(List<LegacyRows.Snapshot> rows, @Nullable String snapshotId, List<String> kinds,
                            LegacyState.Identity identity) {
        List<LegacyRows.Snapshot> ordered = new ArrayList<>(rows);
        ordered.removeIf(row -> row.payloadVersion() == 1 && LegacyState.KIND_CAPTURE.equals(row.snapshotKind()));
        ordered.sort(Comparator
                .comparing((LegacyRows.Snapshot row) -> row.snapshotId().equals(snapshotId))
                .thenComparing(row -> !(row.payloadVersion() == 1 && LegacyState.KIND_LOST.equals(row.snapshotKind())))
                .thenComparing(LegacyRows.Snapshot::current)
                .thenComparing(row -> kinds.contains(row.snapshotKind()))
                .thenComparingLong(LegacyRows.Snapshot::sourceLifecycleRevision)
                .thenComparingLong(LegacyRows.Snapshot::createdAtMs)
                .reversed());
        for (LegacyRows.Snapshot row : ordered) {
            LegacyState.State state = LegacyState.state(row.payloadJson());
            if (state == null && row.payloadVersion() == 1) {
                state = LegacyState.legacyState(row.snapshotKind(), row.payloadJson(), row.createdAtMs(), identity);
            }
            if (state != null) {
                return new Snapshot(row, state,
                        row.snapshotId().equals(snapshotId) || kinds.contains(row.snapshotKind()));
            }
            skip("companion_snapshot", row.snapshotId(), ImportResult.SKIP_UNREADABLE);
        }
        return null;
    }

    /** The death timers of a dead profile: from its current death snapshot, else its newest one. */
    @Nullable
    private static LegacyState.Death death(List<LegacyRows.Snapshot> rows) {
        return rows.stream()
                .filter(row -> LegacyState.KIND_DEATH.equals(row.snapshotKind()))
                .sorted(Comparator.comparing(LegacyRows.Snapshot::current)
                        .thenComparingLong(LegacyRows.Snapshot::sourceLifecycleRevision)
                        .thenComparingLong(LegacyRows.Snapshot::createdAtMs).reversed())
                .map(row -> LegacyState.death(row.payloadJson()))
                .filter(death -> death != null)
                .findFirst().orElse(null);
    }

    // ---- bonded-companions.sqlite ----

    private void mapBonded(LegacyRows.Bonded bonded) {
        Map<String, LegacyRows.BondedLease> leases = byKey(bonded.leases(), LegacyRows.BondedLease::profileId);
        Map<String, List<LegacyRows.BondedExtension>> extensions =
                group(bonded.extensionData(), LegacyRows.BondedExtension::profileId);
        bonded.leases().forEach(lease -> knownBody(lease.liveNpcUuid(), lease.profileId()));
        bonded.cleanupTargets().forEach(target -> knownBody(target.targetNpcUuid(), target.profileId()));
        bonded.captureSources().forEach(source -> knownBody(source.sourceNpcUuid(), source.profileId()));

        for (LegacyRows.BondedProfile profile : bonded.profiles()) {
            UUID id = uuid(profile.profileId());
            UUID owner = uuid(profile.ownerUuid());
            if (id == null || owner == null) {
                skip("bonded_companion_profile", profile.profileId(), ImportResult.SKIP_INVALID_ID);
            } else if (blank(profile.roleId())) {
                skip("bonded_companion_profile", profile.profileId(), ImportResult.SKIP_NO_ROLE);
            } else {
                try {
                    mapBondedProfile(id, owner, profile, leases.get(profile.profileId()),
                            extensions.getOrDefault(profile.profileId(), List.of()));
                } catch (RuntimeException failure) {
                    failed("bonded_companion_profile", id, failure);
                }
            }
        }
    }

    private void mapBondedProfile(UUID id, UUID owner, LegacyRows.BondedProfile profile,
                                  @Nullable LegacyRows.BondedLease lease, List<LegacyRows.BondedExtension> extensions) {
        UUID liveNpc = lease == null ? null : uuid(lease.liveNpcUuid());
        CompanionRecord.Builder record = CompanionRecord.builder(id, profile.roleId().trim(), CompanionLocation.item())
                .revision(Math.max(0L, profile.revision()))
                .ownerUuid(owner)
                .rosterId(profile.rosterId())
                .bonded(true)
                .summonCooldownUntilMs(profile.summonCooldownUntilMs())
                .updatedAtMs(profile.updatedAtMs());
        CompanionLocation location;
        switch (profile.state()) {
            case "ACTIVE" -> {
                if (liveNpc == null || blank(lease.worldKey())) {
                    location = lost(id, CAUSE_NO_BODY, liveNpc);
                } else {
                    // The bonded file kept no position; the body's first sighting fills it.
                    location = CompanionLocation.live(lease.worldKey(), 0.0, 0.0, 0.0);
                    record.currentNpcUuid(liveNpc).homeWorld(lease.worldKey());
                    if (lease.expiresAtMs() != 0L) {
                        record.summonedUntilMs(Math.max(importTimeMs, lease.expiresAtMs()));
                    }
                }
            }
            case "DEAD" -> {
                // The old schema has no revive time: revive is available at once (R7).
                location = CompanionLocation.dead(null);
                record.diedAtMs(profile.diedAtMs() == null ? 0L : profile.diedAtMs());
            }
            case "STORED" -> location = CompanionLocation.stored(StoredReason.BONDED);
            default -> location = lost(id, CAUSE_UNRESOLVED, liveNpc);
        }

        String payload = LegacyState.unwrapBonded(profile.snapshotJson());
        LegacyState.State state = payload == null ? null : LegacyState.state(payload);
        // The row's display_name is a label ("Bonded Miniwyvern"), not a name a player gave.
        String customName = null;
        if (state == null) {
            skip("bonded_companion_profile.snapshot_json", profile.profileId(), ImportResult.SKIP_UNREADABLE);
            snapshots.put(id, SnapshotEnvelope.importedState(id, 0L,
                    LegacyState.emptyState(liveNpc == null ? id : liveNpc)));
            withoutState.add(id);
        } else {
            knownBodies.putIfAbsent(state.npcUuid(), id);
            snapshots.put(id, SnapshotEnvelope.importedState(id, 0L, state.json()));
            record.summary(state.summary()).lastSnapshotAtMs(profile.updatedAtMs());
            // The row keeps the role the companion was bonded as; its state has the form it is in now.
            record.roleId(bodyRole(profile.roleId(), state.summary().roleId(), true));
            customName = state.customName();
        }
        for (LegacyRows.BondedExtension row : extensions) {
            String rowKey = row.profileId() + "|" + row.namespace();
            String json = LegacyState.unwrapBonded(row.jsonPayload());
            if (row.namespace().contains("/")) {
                skip("bonded_companion_extension_data", rowKey, ImportResult.SKIP_NAMESPACE_SLASH);
            } else if (json == null) {
                skip("bonded_companion_extension_data", rowKey, ImportResult.SKIP_UNREADABLE);
            } else {
                // The bonded API's public revision is the stored revision minus one.
                record.extension(ExtensionEntries.key(row.namespace(), BONDED_EXTENSION_KEY),
                        new ExtensionEntry(saturatedAdd(Math.max(0L, row.revision()), 1L), json));
            }
        }
        records.put(id, record.location(location).displayName(customName).build());
        bondedRecords++;
    }

    // ---- result ----

    private ImportResult result(int unfinishedOperations) {
        List<UUID> collisions = resolveCollisions();
        Map<UUID, LegacyAliases.Entry> aliases = new LinkedHashMap<>();
        for (CompanionRecord record : records.values()) {
            if (record.location().kind() == LocationKind.LIVE && record.currentNpcUuid() != null) {
                aliases.put(record.currentNpcUuid(),
                        new LegacyAliases.Entry(record.profileId(), LegacyAliases.Kind.CURRENT));
            }
        }
        rejoinBodies.forEach((npcUuid, profileId) ->
                aliases.putIfAbsent(npcUuid, new LegacyAliases.Entry(profileId, LegacyAliases.Kind.REJOIN)));
        knownBodies.forEach((npcUuid, profileId) ->
                aliases.putIfAbsent(npcUuid, new LegacyAliases.Entry(profileId, LegacyAliases.Kind.STALE)));
        Map<LocationKind, Integer> counts = new EnumMap<>(LocationKind.class);
        records.values().forEach(record -> counts.merge(record.location().kind(), 1, Integer::sum));
        return new ImportResult(new ArrayList<>(records.values()), snapshots, new LegacyAliases(aliases),
                new ImportResult.Report(counts, bondedRecords, unfinishedOperations, quarantined, skipped,
                        liveWithoutCheckpoint, importedLost, collisions, withoutState, dyingCheckpoints,
                        liveWorldGuessed, stateInItem, liveUsedHistory, liveUsedOldDeathState));
    }

    /** R9: of the LIVE records that name one NPC UUID, the newest keeps it and the others become LOST. */
    private List<UUID> resolveCollisions() {
        Map<UUID, List<CompanionRecord>> claims = new LinkedHashMap<>();
        for (CompanionRecord record : records.values()) {
            if (record.location().kind() == LocationKind.LIVE && record.currentNpcUuid() != null) {
                claims.computeIfAbsent(record.currentNpcUuid(), k -> new ArrayList<>()).add(record);
            }
        }
        List<UUID> listed = new ArrayList<>();
        for (List<CompanionRecord> claim : claims.values()) {
            if (claim.size() < 2) {
                continue;
            }
            // Equal times keep the first in import order, so two imports of one world agree.
            CompanionRecord keeper = claim.get(0);
            for (CompanionRecord record : claim) {
                if (record.updatedAtMs() > keeper.updatedAtMs()) {
                    keeper = record;
                }
            }
            for (CompanionRecord record : claim) {
                listed.add(record.profileId());
                if (record != keeper) {
                    records.put(record.profileId(), record.toBuilder()
                            .location(CompanionLocation.lost(CAUSE_NPC_UUID_COLLISION))
                            .currentNpcUuid(null).summonedUntilMs(0L).build());
                    liveWithoutCheckpoint.remove(record.profileId());
                    liveWorldGuessed.remove(record.profileId());
                }
            }
        }
        return listed;
    }

    /**
     * A record the old rows could not place. {@code lastBody} is the body the profile last had,
     * if any: should it turn up in the world it is the companion, so its alias is kept as one to
     * rejoin instead of a stale one.
     */
    private CompanionLocation lost(UUID profileId, String cause, @Nullable UUID lastBody) {
        importedLost.add(profileId);
        if (lastBody != null) {
            rejoinBodies.putIfAbsent(lastBody, profileId);
        }
        return CompanionLocation.lost(cause);
    }

    /** One profile that could not be mapped is skipped and reported; the import goes on (file errors still abort). */
    private void failed(String table, UUID profileId, RuntimeException failure) {
        records.remove(profileId);
        snapshots.remove(profileId);
        rejoinBodies.values().remove(profileId);
        for (List<UUID> list : List.of(quarantined, liveWithoutCheckpoint, importedLost, withoutState,
                dyingCheckpoints, liveWorldGuessed, stateInItem, liveUsedHistory, liveUsedOldDeathState)) {
            list.remove(profileId);
        }
        skip(table, profileId + " (" + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")",
                ImportResult.SKIP_MAPPING_FAILED);
    }

    private void skip(String table, String key, String reason) {
        skipped.add(new ImportResult.Skipped(table, key, reason));
    }

    private void knownBody(String npcUuid, String profileId) {
        UUID npc = uuid(npcUuid);
        UUID profile = uuid(profileId);
        if (npc != null && profile != null) {
            knownBodies.putIfAbsent(npc, profile);
        }
    }

    /** A string field of {@code companion_profile.metadata_json}; null when absent or blank. */
    @Nullable
    private static String metadata(@Nullable String metadataJson, String field) {
        if (blank(metadataJson)) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(metadataJson);
            JsonElement value = root.isJsonObject() ? root.getAsJsonObject().get(field) : null;
            String text = value != null && value.isJsonPrimitive() ? value.getAsString() : null;
            return blank(text) ? null : text;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @Nullable
    private static UUID uuid(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    @Nullable
    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Both arguments are non-negative; a sum past the end of time stays at the end. */
    private static long saturatedAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static <T> Map<String, T> byKey(List<T> rows, Function<T, String> key) {
        Map<String, T> map = new LinkedHashMap<>();
        rows.forEach(row -> map.putIfAbsent(key.apply(row), row));
        return map;
    }

    private static <T> Map<String, List<T>> group(List<T> rows, Function<T, String> key) {
        Map<String, List<T>> map = new LinkedHashMap<>();
        rows.forEach(row -> map.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row));
        return map;
    }
}
