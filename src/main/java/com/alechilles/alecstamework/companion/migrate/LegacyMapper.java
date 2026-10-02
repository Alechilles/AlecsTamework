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
        Map<String, List<LegacyRows.Snapshot>> snapshotRows =
                group(state.currentSnapshots(), LegacyRows.Snapshot::profileId);
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
                List<Snapshot> states = readSnapshots(id, snapshotRows.getOrDefault(key, List.of()));
                mapProfile(id, owner, profile, life, aliases.getOrDefault(key, List.of()), states,
                        checkpoints.getOrDefault(key, List.of()), tools.getOrDefault(key, List.of()),
                        rosters.get(key), leases.get(key), coopSlots, residencies.get(key), origins.get(key),
                        extensions.getOrDefault(key, List.of()));
            }
        }
    }

    /** A state snapshot row with its readable state. */
    private record Snapshot(LegacyRows.Snapshot row, LegacyState.State state, @Nullable LegacyState.Death death) {
    }

    private List<Snapshot> readSnapshots(UUID profileId, List<LegacyRows.Snapshot> rows) {
        List<Snapshot> readable = new ArrayList<>();
        for (LegacyRows.Snapshot row : rows) {
            LegacyState.State state = LegacyState.state(row.payloadJson());
            if (state == null) {
                skip("companion_snapshot", row.snapshotId(), ImportResult.SKIP_UNREADABLE);
            } else {
                knownBodies.putIfAbsent(state.npcUuid(), profileId);
                readable.add(new Snapshot(row, state, LegacyState.death(row.payloadJson())));
            }
        }
        return readable;
    }

    private void mapProfile(UUID id, @Nullable UUID owner, LegacyRows.Profile profile, LegacyRows.Lifecycle life,
                            List<LegacyRows.Alias> aliases, List<Snapshot> states,
                            List<LegacyRows.EntityCheckpoint> checkpointRows, List<LegacyRows.ToolLink> tools,
                            @Nullable LegacyRows.RosterMembership roster, @Nullable LegacyRows.TimedLease lease,
                            Map<String, LegacyRows.CoopSlot> coopSlots, @Nullable LegacyRows.CoopResidency residency,
                            @Nullable LegacyRows.Provisioning origin, List<LegacyRows.ExtensionData> extensions) {
        CompanionRecord.Builder record = CompanionRecord.builder(id, profile.roleId().trim(), CompanionLocation.item())
                .revision(Math.max(0L, life.revision()))
                .ownerUuid(owner)
                .ownerName(metadata(profile.metadataJson(), "owner_name"))
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

        if ("RELEASED".equals(life.lifecycleState())) {
            // A tombstone keeps nothing but its identity: no snapshot, tools or extension data.
            records.put(id, record.location(CompanionLocation.released(null)).displayName(customName).build());
            return;
        }
        LinkedHashSet<String> toolIds = new LinkedHashSet<>();
        tools.forEach(link -> toolIds.add(link.toolUuid()));
        record.toolIds(List.copyOf(toolIds));
        for (LegacyRows.ExtensionData row : extensions) {
            if (LegacyReader.ENTITY_CHECKPOINT_NAMESPACE.equals(row.namespace())) {
                continue;
            }
            String rowKey = row.profileId() + "|" + row.namespace() + "|" + row.dataKey();
            if (row.namespace().contains("/")) {
                skip("profile_extension_data", rowKey, ImportResult.SKIP_NAMESPACE_SLASH);
            } else {
                // Profile data's public revision is the stored one, and both start at 1.
                record.extension(ExtensionEntries.key(row.namespace(), row.dataKey()),
                        new ExtensionEntry(Math.max(1L, row.revision()), row.jsonPayload()));
            }
        }

        CompanionLocation location;
        Snapshot state = null;
        LegacyState.Checkpoint checkpoint = null;
        String liveWorld = null;
        switch (life.lifecycleState()) {
            case "ACTIVE", "UNLOADED" -> {
                checkpoint = chooseCheckpoint(checkpointRows, currentAlias);
                String world = firstText(life.worldKey(), checkpoint == null ? null : checkpoint.worldKey(),
                        profile.lastKnownWorldKey());
                liveWorld = world;
                if (checkpoint == null) {
                    state = choose(states, null, List.of());
                }
                if (currentAlias == null || world == null) {
                    location = lost(id, CAUSE_NO_BODY);
                } else if (checkpoint != null) {
                    location = CompanionLocation.live(world, checkpoint.x(), checkpoint.y(), checkpoint.z());
                } else {
                    location = CompanionLocation.live(world, 0.0, 0.0, 0.0);
                    liveWithoutCheckpoint.add(id);
                }
            }
            case "CAPTURED" -> {
                location = CompanionLocation.item();
                state = choose(states, null, List.of("capture"));
            }
            case "COOP" -> {
                LegacyRows.CoopSlot slot = life.locationKey() == null ? null : coopSlots.get(life.locationKey());
                if (slot == null && residency != null) {
                    slot = coopSlots.get(residency.coopKey());
                }
                location = slot == null || blank(slot.worldKey()) || slot.residentSlot() < 0
                        ? lost(id, CAUSE_NO_BODY)
                        : CompanionLocation.coop(slot.worldKey(), slot.x(), slot.y(), slot.z(), slot.residentSlot());
                state = choose(states, residency == null ? null : residency.snapshotId(), List.of("coop"));
            }
            case "ROSTER_STORED", "PROVISIONED_DORMANT" -> {
                // A companion with a timed lease is summoned through it, whatever stored it (R6).
                StoredReason reason = lease != null ? StoredReason.TIMED
                        : "ROSTER_STORED".equals(life.lifecycleState()) ? StoredReason.ROSTER
                        : StoredReason.PROVISIONED;
                location = CompanionLocation.stored(reason);
                state = choose(states, null, List.of("timed_summon", "full_state_projection"));
            }
            case "DEAD_REVIVABLE" -> {
                state = choose(states, null, List.of("death"));
                LegacyState.Death death = state != null && state.death() != null ? state.death()
                        : states.stream().map(Snapshot::death).filter(d -> d != null).findFirst().orElse(null);
                location = CompanionLocation.dead(death == null ? null : death.cause());
                if (death != null) {
                    record.diedAtMs(death.diedAtMs()).reviveAvailableAtMs(death.reviveAvailableAtMs());
                }
            }
            case "LOST" -> {
                location = CompanionLocation.lost(null);
                state = choose(states, null, List.of("lost"));
            }
            default -> {
                location = lost(id, CAUSE_UNRESOLVED);
                state = choose(states, null, List.of());
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
                            .append("World", new BsonString(liveWorld == null ? "" : liveWorld))
                            .append("GameTimeMs", new BsonInt64(0L))));
            record.summary(checkpoint.summary()).lastSnapshotAtMs(checkpoint.capturedAtMs());
            record.roleId(bodySpelling(profile.roleId(), checkpoint.summary().roleId()));
            if (customName == null) {
                customName = checkpoint.customName();
            }
            if (checkpoint.dying()) {
                dyingCheckpoints.add(id);
            }
        } else if (state != null) {
            snapshots.put(id, SnapshotEnvelope.importedState(id, 0L, state.state().json()));
            record.summary(state.state().summary()).lastSnapshotAtMs(state.row().createdAtMs());
            record.roleId(bodySpelling(profile.roleId(), state.state().summary().roleId()));
            if (customName == null) {
                customName = state.state().customName();
            }
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
     * The old profile table lower-cased some role ids ({@code tamed_chicken}); role lookups need
     * the id as the body spelled it. A body role that differs by more than case is not taken.
     */
    private static String bodySpelling(String profileRole, @Nullable String bodyRole) {
        return bodyRole != null && bodyRole.equalsIgnoreCase(profileRole.trim()) ? bodyRole : profileRole.trim();
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

    /** The checkpoint keyed by the current alias, else the one captured last (R3). */
    @Nullable
    private LegacyState.Checkpoint chooseCheckpoint(List<LegacyRows.EntityCheckpoint> rows, @Nullable UUID currentAlias) {
        LegacyState.Checkpoint newest = null;
        for (LegacyRows.EntityCheckpoint row : rows) {
            LegacyState.Checkpoint checkpoint = LegacyState.checkpoint(row.jsonPayload(), row.updatedAtMs());
            if (checkpoint == null) {
                skip("profile_extension_data", row.profileId() + "|" + LegacyReader.ENTITY_CHECKPOINT_NAMESPACE
                        + "|" + row.dataKey(), ImportResult.SKIP_UNREADABLE);
            } else if (currentAlias != null && ("alias:" + currentAlias).equalsIgnoreCase(row.dataKey())) {
                return checkpoint;
            } else if (newest == null || checkpoint.capturedAtMs() > newest.capturedAtMs()) {
                newest = checkpoint;
            }
        }
        return newest;
    }

    /**
     * The snapshot a record is restored from: the one named by {@code snapshotId} (a coop
     * residency), else one of a kind that matches the lifecycle state, else the one with the
     * highest source lifecycle revision.
     */
    @Nullable
    private static Snapshot choose(List<Snapshot> states, @Nullable String snapshotId, List<String> kinds) {
        Comparator<Snapshot> newest = Comparator.<Snapshot>comparingLong(s -> s.row().sourceLifecycleRevision())
                .thenComparingLong(s -> s.row().createdAtMs());
        for (Snapshot state : states) {
            if (state.row().snapshotId().equals(snapshotId)) {
                return state;
            }
        }
        return states.stream().filter(s -> kinds.contains(s.row().snapshotKind())).max(newest)
                .orElseGet(() -> states.stream().max(newest).orElse(null));
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
                continue;
            }
            if (blank(profile.roleId())) {
                skip("bonded_companion_profile", profile.profileId(), ImportResult.SKIP_NO_ROLE);
                continue;
            }
            LegacyRows.BondedLease lease = leases.get(profile.profileId());
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
                        location = lost(id, CAUSE_NO_BODY);
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
                default -> location = lost(id, CAUSE_UNRESOLVED);
            }

            String payload = LegacyState.unwrapBonded(profile.snapshotJson());
            LegacyState.State state = payload == null ? null : LegacyState.state(payload);
            String customName = profile.displayName();
            if (state == null) {
                skip("bonded_companion_profile.snapshot_json", profile.profileId(), ImportResult.SKIP_UNREADABLE);
                snapshots.put(id, SnapshotEnvelope.importedState(id, 0L,
                        LegacyState.emptyState(liveNpc == null ? id : liveNpc)));
                withoutState.add(id);
            } else {
                knownBodies.putIfAbsent(state.npcUuid(), id);
                snapshots.put(id, SnapshotEnvelope.importedState(id, 0L, state.json()));
                record.summary(state.summary()).lastSnapshotAtMs(profile.updatedAtMs());
                if (blank(customName)) {
                    customName = state.customName();
                }
            }
            for (LegacyRows.BondedExtension row : extensions.getOrDefault(profile.profileId(), List.of())) {
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
            records.put(id, record.location(location).displayName(blank(customName) ? null : customName).build());
            bondedRecords++;
        }
    }

    // ---- result ----

    private ImportResult result(int unfinishedOperations) {
        List<UUID> collisions = resolveCollisions();
        Map<UUID, LegacyAliases.Entry> aliases = new LinkedHashMap<>();
        for (CompanionRecord record : records.values()) {
            if (record.location().kind() == LocationKind.LIVE && record.currentNpcUuid() != null) {
                aliases.put(record.currentNpcUuid(), new LegacyAliases.Entry(record.profileId(), true));
            }
        }
        knownBodies.forEach((npcUuid, profileId) ->
                aliases.putIfAbsent(npcUuid, new LegacyAliases.Entry(profileId, false)));
        Map<LocationKind, Integer> counts = new EnumMap<>(LocationKind.class);
        records.values().forEach(record -> counts.merge(record.location().kind(), 1, Integer::sum));
        return new ImportResult(new ArrayList<>(records.values()), snapshots, LegacyAliases.of(aliases),
                new ImportResult.Report(counts, bondedRecords, unfinishedOperations, quarantined, skipped,
                        liveWithoutCheckpoint, importedLost, collisions, withoutState, dyingCheckpoints));
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
                }
            }
        }
        return listed;
    }

    private CompanionLocation lost(UUID profileId, String cause) {
        importedLost.add(profileId);
        return CompanionLocation.lost(cause);
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
