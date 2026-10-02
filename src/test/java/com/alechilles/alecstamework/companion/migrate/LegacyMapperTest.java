package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.ExtensionEntries;
import com.alechilles.alecstamework.companion.index.ExtensionEntry;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.index.RecordScope;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.store.MemoryCompanionFileIo;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMapperTest {
    private static final long IMPORT_TIME = 1_000_000L;
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NPC = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OLD_NPC = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID STATE_NPC = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final LegacyRows.SourceFile SOURCE = new LegacyRows.SourceFile(Path.of("old.sqlite"), 1, 1);

    private final Rows rows = new Rows();

    @Test
    void everyLifecycleStateMapsToItsLocation() {
        UUID active = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        UUID unloaded = rows.profile("UNLOADED", "NONE", null, "world-b");
        rows.alias(OLD_NPC, unloaded, "CURRENT");
        UUID captured = rows.profile("CAPTURED", "CAPTURE_ITEM", "item-1", null);
        UUID coop = rows.profile("COOP", "COOP_SLOT", "coop-key", null);
        rows.coopSlots.add(new LegacyRows.CoopSlot("coop-key", "world-a", "coop", 10, 64, -20, 3, 0, null, null));
        UUID roster = rows.profile("ROSTER_STORED", "COMMAND_ROSTER", "slot-1", null);
        UUID provisioned = rows.profile("PROVISIONED_DORMANT", "PROVISIONING", "prov", null);
        UUID dead = rows.profile("DEAD_REVIVABLE", "NONE", null, null);
        UUID lost = rows.profile("LOST", "NONE", null, null);
        UUID released = rows.profile("RELEASED", "NONE", null, null);
        rows.tool(released, "tool-1");
        rows.extensions.add(new LegacyRows.ExtensionData(released.toString(), "Mod:Thing", "k", 1, "{}", 2, 1, 1));
        UUID unresolved = rows.profile("UNRESOLVED", "UNRESOLVED", null, null);
        UUID bondedStored = rows.bonded("STORED", plainState(STATE_NPC, 3));
        UUID bondedDead = rows.bonded("DEAD", plainState(UUID.randomUUID(), 3));

        ImportResult result = rows.map();

        assertEquals(CompanionLocation.live("world-a", 0, 0, 0), record(result, active).location());
        assertEquals(CompanionLocation.live("world-b", 0, 0, 0), record(result, unloaded).location());
        assertEquals(CompanionLocation.item(), record(result, captured).location());
        assertEquals(CompanionLocation.coop("world-a", 10, 64, -20, 3), record(result, coop).location());
        assertEquals(CompanionLocation.stored(StoredReason.ROSTER), record(result, roster).location());
        assertEquals(CompanionLocation.stored(StoredReason.PROVISIONED), record(result, provisioned).location());
        assertEquals(CompanionLocation.dead(null), record(result, dead).location());
        assertEquals(CompanionLocation.lost(null), record(result, lost).location());
        assertEquals(CompanionLocation.released(null), record(result, released).location());
        assertEquals(CompanionLocation.lost(LegacyMapper.CAUSE_UNRESOLVED), record(result, unresolved).location());
        assertEquals(CompanionLocation.stored(StoredReason.BONDED), record(result, bondedStored).location());
        assertEquals(CompanionLocation.dead(null), record(result, bondedDead).location());

        for (CompanionRecord imported : result.records()) {
            assertEquals(0L, imported.generation(), "4.x items carry no generation, so records start at 0");
            assertEquals(-1, imported.rosterSlot());
            assertEquals(List.of(), imported.domainClaims(), "provider claims are not backfilled");
        }
        assertEquals(RecordScope.PORTABLE, record(result, roster).scope());
        assertEquals(RecordScope.WORLD_BOUND, record(result, active).scope());
        assertNull(record(result, captured).currentNpcUuid(), "only a live companion names a body");
        // A tombstone keeps nothing that could bring the companion back.
        assertNull(result.snapshots().get(released));
        assertEquals(List.of(), record(result, released).toolIds());
        assertEquals(Map.of(), record(result, released).extensions());
        assertEquals(Map.of(LocationKind.LIVE, 2, LocationKind.ITEM, 1, LocationKind.COOP, 1, LocationKind.STORED, 3,
                LocationKind.DEAD, 2, LocationKind.LOST, 2, LocationKind.RELEASED, 1),
                result.report().recordsByLocation());
        assertEquals(2, result.report().bondedRecords());
        assertEquals(List.of(unresolved), result.report().importedLost());
    }

    @Test
    void aLiveCompanionWithACheckpointGetsAnEntitySnapshotAndItsPosition() {
        UUID id = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.profiles.set(0, new LegacyRows.Profile(id.toString(), null, "tamed_wolf", null, null, 1, 1, 1, 0));
        rows.alias(OLD_NPC, id, "RETIRED");
        rows.alias(NPC, id, "CURRENT");
        // The old body's checkpoint is newer, but the current alias's checkpoint is the one to use.
        rows.checkpoint(id, OLD_NPC, "world-old", "9.0", "9.0", "9.0", "9000", 1);
        rows.checkpoint(id, NPC, "world-a", "500.5", "118.0", "-1633.5", "7000", 4);

        ImportResult result = rows.map();

        CompanionRecord imported = record(result, id);
        assertEquals(CompanionLocation.live("world-a", 500.5, 118.0, -1633.5), imported.location());
        assertEquals(NPC, imported.currentNpcUuid());
        assertEquals(7000L, imported.lastSnapshotAtMs());
        assertEquals("Tamed_Wolf", imported.summary().roleId());
        assertEquals("Tamed_Wolf", imported.roleId(), "a role the old table lower-cased is spelled as the body has it");
        assertEquals(4, imported.summary().level());
        assertEquals("Rex", imported.displayName(), "the body's name component names the companion");
        SnapshotEnvelope snapshot = result.snapshots().get(id);
        assertEquals(CompanionSnapshots.FORMAT, snapshot.format());
        assertEquals(0L, snapshot.generation());
        assertEquals("world-a", CompanionSnapshots.world(snapshot));
        assertEquals(0L, CompanionSnapshots.gameTimeMs(snapshot), "the old checkpoint has no game time");
        assertEquals("Tamed_Wolf", CompanionSnapshots.entity(snapshot).getDocument("Components")
                .getDocument("NPC").getString("RoleName").getValue());
        assertEquals(List.of(), result.report().liveWithoutCheckpoint());
    }

    @Test
    void withoutACheckpointForTheCurrentAliasTheNewestCheckpointIsUsed() {
        UUID id = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.checkpoint(id, OLD_NPC, "world-a", "1.0", "2.0", "3.0", "5000", 2);
        rows.checkpoint(id, STATE_NPC, "world-a", "4.0", "5.0", "6.0", "8000", 6);

        ImportResult result = rows.map();

        assertEquals(CompanionLocation.live("world-a", 4.0, 5.0, 6.0), record(result, id).location());
        assertEquals(6, record(result, id).summary().level());
    }

    @Test
    void aLiveCompanionWithNoCheckpointStaysLiveAndRespawnsFromItsRole() {
        UUID id = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.alias(NPC, id, "CURRENT");

        ImportResult result = rows.map();

        CompanionRecord imported = record(result, id);
        assertEquals(CompanionLocation.live("world-a", 0, 0, 0), imported.location());
        assertEquals(NPC, imported.currentNpcUuid());
        assertEquals(CompanionSummary.EMPTY, imported.summary());
        assertEquals(0L, imported.lastSnapshotAtMs(), "the first unload must take a real snapshot");
        assertEquals("{\"npcUuid\":\"" + NPC + "\"}", result.snapshots().get(id).importedStateJson());
        assertEquals(List.of(id), result.report().liveWithoutCheckpoint());
    }

    @Test
    void aLiveCompanionWithNoKnownBodyIsImportedLost() {
        UUID id = rows.profile("ACTIVE", "LIVE_ENTITY", "not-a-uuid", "world-a");

        ImportResult result = rows.map();

        assertEquals(CompanionLocation.lost(LegacyMapper.CAUSE_NO_BODY), record(result, id).location());
        assertNull(record(result, id).currentNpcUuid());
        assertEquals(List.of(id), result.report().importedLost());
        assertNotNull(result.snapshots().get(id).importedStateJson(), "a recover can still respawn it from its role");
    }

    @Test
    void aDeadCompanionTakesItsTimersAndStateFromTheDeathSnapshot() {
        UUID id = rows.profile("DEAD_REVIVABLE", "NONE", null, null);
        String state = plainState(STATE_NPC, 7);
        // World-style negative values must survive, and the death wrapper must not.
        rows.snapshot(id, "snap-death", "death", "{\"fullState\":" + state + ",\"diedAtMs\":-900,"
                + "\"respawnAvailableAtMs\":-300,\"deathCauseKind\":\"NPC\",\"deathSourceName\":\"Trork\"}", 5, 2400);

        ImportResult result = rows.map();

        CompanionRecord imported = record(result, id);
        assertEquals(CompanionLocation.dead("NPC:Trork"), imported.location());
        assertEquals(-900L, imported.diedAtMs());
        assertEquals(-300L, imported.reviveAvailableAtMs());
        assertEquals(2400L, imported.lastSnapshotAtMs());
        SnapshotEnvelope snapshot = result.snapshots().get(id);
        assertEquals(SnapshotEnvelope.FORMAT_IMPORTED_STATE, snapshot.format());
        assertEquals(state, snapshot.importedStateJson());
        assertEquals(7, new CoopResidentStateSnapshotCodec().decode(snapshot.importedStateJson())
                .snapshotOrNull().leveling().getLevel(), "the restore path can read the stored state");
    }

    @Test
    void aBondedDeathKeepsItsTimeAndCanBeRevivedAtOnce() {
        UUID dead = rows.bonded("DEAD", plainState(STATE_NPC, 3));
        rows.bondedProfiles.set(0, withDeath(rows.bondedProfiles.get(0), -650L));

        CompanionRecord imported = record(rows.map(), dead);

        assertEquals(-650L, imported.diedAtMs());
        assertEquals(0L, imported.reviveAvailableAtMs(), "the old schema stored no revive time");
    }

    @Test
    void theSummaryComesFromTheStoredState() {
        UUID id = rows.profile("LOST", "NONE", null, null);
        rows.snapshot(id, "snap-lost", "lost", plainState(STATE_NPC, 7), 1, 2000);

        CompanionRecord imported = record(rows.map(), id);

        CompanionSummary summary = imported.summary();
        assertEquals("Rex", summary.customName());
        assertEquals("Rex", imported.displayName());
        assertEquals("Tamed_Wolf", summary.roleId());
        assertEquals(7, summary.level());
        assertEquals("Leveling_A", summary.levelingConfigId());
        assertEquals(12.0f, summary.healthCurrent());
        assertEquals(40.0f, summary.healthMax());
        assertEquals(1234L, summary.observedAtMs());
        assertTrue(summary.breedingPresent());
        assertEquals(-500L, summary.breedingCooldownUntilMs(), "a negative world time keeps its sign");
        assertEquals(0L, summary.breedingCooldownStartedAtMs(), "0 stays unset");
    }

    @Test
    void theSnapshotOfTheMatchingKindWinsThenTheHighestRevision() {
        UUID lost = rows.profile("LOST", "NONE", null, null);
        rows.snapshot(lost, "a-capture", "capture", plainState(STATE_NPC, 9), 8, 3000);
        rows.snapshot(lost, "b-lost", "lost", plainState(STATE_NPC, 2), 4, 2000);
        UUID stored = rows.profile("ROSTER_STORED", "COMMAND_ROSTER", "slot", null);
        rows.snapshot(stored, "c-capture", "capture", plainState(STATE_NPC, 3), 2, 1000);
        rows.snapshot(stored, "d-coop", "coop", plainState(STATE_NPC, 5), 6, 1500);
        rows.snapshot(stored, "e-broken", "lost", "{\"roleId\":\"no npc uuid\"}", 99, 9999);

        ImportResult result = rows.map();

        assertEquals(2, record(result, lost).summary().level(), "the lost snapshot serves a lost companion");
        assertEquals(5, record(result, stored).summary().level(), "no kind matches: highest lifecycle revision");
        assertEquals(List.of(new ImportResult.Skipped("companion_snapshot", "e-broken", ImportResult.SKIP_UNREADABLE)),
                result.report().skippedRows());
    }

    @Test
    void aCoopResidentUsesTheSnapshotItsResidencyNames() {
        UUID id = rows.profile("COOP", "COOP_SLOT", "coop-key", null);
        rows.coopSlots.add(new LegacyRows.CoopSlot("coop-key", "world-a", "coop", 1, 2, 3, 0, 0, null, null));
        rows.residencies.add(new LegacyRows.CoopResidency("coop-key", id.toString(), null, "housed", 1, 1));
        rows.snapshot(id, "housed", "capture", plainState(STATE_NPC, 4), 1, 1000);
        rows.snapshot(id, "newer", "coop", plainState(STATE_NPC, 8), 9, 2000);

        assertEquals(4, record(rows.map(), id).summary().level());
    }

    @Test
    void aRunningTimedSummonRestartsItsClockAtImportAndAStoredOneKeepsItsCooldown() {
        UUID live = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.leases.add(lease(live, 45_000L, null));
        UUID expired = rows.profile("ACTIVE", "LIVE_ENTITY", OLD_NPC.toString(), "world-a");
        rows.leases.add(lease(expired, -5_000L, null));
        UUID stored = rows.profile("ROSTER_STORED", "COMMAND_ROSTER", "slot", null);
        rows.leases.add(lease(stored, null, -7_000L));

        ImportResult result = rows.map();

        assertEquals(IMPORT_TIME + 45_000L, record(result, live).summonedUntilMs());
        assertEquals(IMPORT_TIME, record(result, expired).summonedUntilMs(), "never in the past");
        assertEquals(CompanionLocation.stored(StoredReason.TIMED), record(result, stored).location());
        assertEquals(-7_000L, record(result, stored).summonCooldownUntilMs(), "the stored value keeps its sign");
        assertEquals(0L, record(result, stored).summonedUntilMs());
    }

    @Test
    void rosterOriginToolsOwnerAndNamesAreCopied() {
        UUID id = rows.profile("ROSTER_STORED", "COMMAND_ROSTER", "slot-1", null);
        rows.profiles.set(0, new LegacyRows.Profile(id.toString(), "Wolf", "Tamed_Wolf",
                "{\"owner_name\":\"Alec\",\"custom_name\":\"Jade\",\"tamed\":true}", "world-a", 100, 2500, 150, 0));
        rows.lifecycles.set(0, new LegacyRows.Lifecycle(id.toString(), OWNER.toString(), "ROSTER_STORED",
                "COMMAND_ROSTER", "slot-1", null, "world-home", 7, null, 0, null));
        rows.rosters.add(new LegacyRows.RosterMembership("slot-1", id.toString(), OWNER.toString(), "wolves", 2,
                "pack", true, null, null, null, null, 1, 1));
        rows.origins.add(new LegacyRows.Provisioning(id.toString(), "Mod:Spawner", "wolf-1", null, 1));
        rows.tool(id, "tool-b");
        rows.tool(id, "tool-a");
        UUID bonded = rows.bonded("STORED", plainState(STATE_NPC, 3));

        ImportResult result = rows.map();

        CompanionRecord imported = record(result, id);
        assertEquals(7L, imported.revision());
        assertEquals(OWNER, imported.ownerUuid());
        assertEquals("Alec", imported.ownerName());
        assertEquals("Jade", imported.displayName(), "the player's name, not the role label in display_name");
        assertEquals("world-home", imported.homeWorld());
        assertEquals("wolves", imported.rosterId());
        assertFalse(imported.bonded());
        assertEquals("Mod:Spawner", imported.originNamespace());
        assertEquals("wolf-1", imported.originKey());
        assertEquals(List.of("tool-b", "tool-a"), imported.toolIds());
        assertEquals(2500L, imported.updatedAtMs());
        CompanionRecord bondedRecord = record(result, bonded);
        assertEquals("dragons", bondedRecord.rosterId());
        assertTrue(bondedRecord.bonded());
        assertEquals(29L, bondedRecord.revision());
        assertEquals(OWNER, bondedRecord.ownerUuid());
        assertEquals(-400L, bondedRecord.summonCooldownUntilMs());
        assertEquals("Rex", bondedRecord.displayName(), "an unnamed bonded row takes the name its body carried");
        assertEquals(plainState(STATE_NPC, 3), result.snapshots().get(bonded).importedStateJson(),
                "the bonded base64 and version wrappers are removed");
    }

    @Test
    void extensionDataKeepsItsPublicRevision() {
        UUID id = rows.profile("LOST", "NONE", null, null);
        rows.extensions.add(new LegacyRows.ExtensionData(id.toString(), "Mod:Thing", "k1", 1, "{\"level\":4}", 12, 1, 1));
        rows.extensions.add(new LegacyRows.ExtensionData(id.toString(), "Mod/Bad", "k1", 1, "{}", 3, 1, 1));
        rows.extensions.add(new LegacyRows.ExtensionData(id.toString(), LegacyReader.ENTITY_CHECKPOINT_NAMESPACE,
                "alias:" + NPC, 1, "{}", 3, 1, 1));
        UUID bonded = rows.bonded("STORED", plainState(STATE_NPC, 3));
        String hyDragon = "{\"schemaVersion\":1,\"speciesId\":\"miniwyvern\"}";
        rows.bondedExtensions.add(new LegacyRows.BondedExtension(bonded.toString(), "Alechilles:HyDragon",
                envelope(hyDragon), 1952, 1));
        rows.bondedExtensions.add(new LegacyRows.BondedExtension(bonded.toString(), "Bad/Namespace",
                envelope("{}"), 1, 1));

        ImportResult result = rows.map();

        // Profile data reports the stored revision as it is.
        assertEquals(Map.of("Mod:Thing/k1", new ExtensionEntry(12, "{\"level\":4}")),
                record(result, id).extensions());
        // The bonded API reports the stored revision minus one, and hands back the stored text.
        ExtensionEntry entry = record(result, bonded).extensions().get("Alechilles:HyDragon/bonded");
        assertEquals(hyDragon, entry.json());
        assertEquals(1952L, ExtensionEntries.storedRevision(entry) - 1L);
        assertEquals(1, record(result, bonded).extensions().size());
        assertEquals(List.of(
                new ImportResult.Skipped("profile_extension_data", id + "|Mod/Bad|k1",
                        ImportResult.SKIP_NAMESPACE_SLASH),
                new ImportResult.Skipped("bonded_companion_extension_data", bonded + "|Bad/Namespace",
                        ImportResult.SKIP_NAMESPACE_SLASH)),
                result.report().skippedRows());
    }

    @Test
    void theNewerOfTwoProfilesKeepsASharedNpcUuid() {
        UUID older = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.leases.add(lease(older, 45_000L, null));
        UUID bonded = rows.bonded("ACTIVE", plainState(STATE_NPC, 3));
        rows.bondedLeases.add(new LegacyRows.BondedLease(bonded.toString(), NPC.toString(), "world-a", 1, 0, "LIVE"));

        ImportResult result = rows.map();

        assertEquals(LocationKind.LIVE, record(result, bonded).location().kind());
        assertEquals(NPC, record(result, bonded).currentNpcUuid());
        assertEquals(CompanionLocation.lost(LegacyMapper.CAUSE_NPC_UUID_COLLISION), record(result, older).location());
        assertNull(record(result, older).currentNpcUuid());
        assertEquals(0L, record(result, older).summonedUntilMs());
        assertEquals(List.of(older, bonded), result.report().npcUuidCollisions());
        assertEquals(Optional.of(new LegacyAliases.Entry(bonded, true)), result.aliases().byNpcUuid(NPC));
    }

    @Test
    void aQuarantinedProfileImportsFromItsLifecycleRowAndUnfinishedOperationsAreCounted() {
        UUID id = rows.profile("ROSTER_STORED", "COMMAND_ROSTER", "slot", null);
        rows.lifecycles.set(0, new LegacyRows.Lifecycle(id.toString(), OWNER.toString(), "ROSTER_STORED",
                "COMMAND_ROSTER", "slot", null, null, 3, "op-open", 0, "incident-1"));
        rows.unfinishedOperations = 2;

        ImportResult result = rows.map();

        assertEquals(CompanionLocation.stored(StoredReason.ROSTER), record(result, id).location());
        assertEquals(List.of(id), result.report().quarantinedProfiles());
        assertEquals(2, result.report().unfinishedOperations());
    }

    @Test
    void rowsThatCannotBecomeARecordAreSkippedAndReported() {
        UUID noRole = rows.profile("LOST", "NONE", null, null);
        rows.profiles.set(0, new LegacyRows.Profile(noRole.toString(), null, null, null, null, 1, 1, 1, 0));
        UUID noLifecycle = UUID.randomUUID();
        rows.profiles.add(new LegacyRows.Profile(noLifecycle.toString(), null, "Tamed_Wolf", null, null, 1, 1, 1, 0));
        rows.profiles.add(new LegacyRows.Profile("p-not-a-uuid", null, "Tamed_Wolf", null, null, 1, 1, 1, 0));
        UUID bonded = rows.bonded("STORED", plainState(STATE_NPC, 3));
        rows.profiles.add(new LegacyRows.Profile(bonded.toString(), null, "Tamed_Wolf", null, null, 1, 1, 1, 0));

        ImportResult result = rows.map();

        assertEquals(List.of(bonded), result.records().stream().map(CompanionRecord::profileId).toList());
        assertEquals(List.of(
                new ImportResult.Skipped("companion_profile", noRole.toString(), ImportResult.SKIP_NO_ROLE),
                new ImportResult.Skipped("companion_profile", noLifecycle.toString(), ImportResult.SKIP_NO_LIFECYCLE),
                new ImportResult.Skipped("companion_profile", "p-not-a-uuid", ImportResult.SKIP_INVALID_ID),
                new ImportResult.Skipped("companion_profile", bonded.toString(), ImportResult.SKIP_BONDED_PROFILE)),
                result.report().skippedRows());
    }

    @Test
    void everyKnownBodyIsAnAliasAndOnlyTheLiveBodyIsCurrent() throws Exception {
        UUID live = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.alias(NPC, live, "CURRENT");
        rows.alias(OLD_NPC, live, "RETIRED");
        UUID lost = rows.profile("LOST", "NONE", null, null);
        UUID lostBody = UUID.randomUUID();
        // A lost companion's last alias is still marked CURRENT in the old table; its body is a leftover.
        rows.alias(lostBody, lost, "CURRENT");
        rows.snapshot(lost, "snap-lost", "lost", plainState(STATE_NPC, 1), 1, 1);
        UUID bonded = rows.bonded("STORED", plainState(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"), 3));
        UUID cleanup = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        UUID source = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
        rows.cleanupTargets.add(new LegacyRows.BondedCleanupTarget("c-1", bonded.toString(), "PROJECTION",
                cleanup.toString(), "PENDING", "world-a"));
        rows.captureSources.add(new LegacyRows.BondedCaptureSource(bonded.toString(), source.toString(), "world-a"));

        LegacyAliases aliases = rows.map().aliases();

        assertEquals(Map.of(
                NPC, new LegacyAliases.Entry(live, true),
                OLD_NPC, new LegacyAliases.Entry(live, false),
                lostBody, new LegacyAliases.Entry(lost, false),
                STATE_NPC, new LegacyAliases.Entry(lost, false),
                UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"), new LegacyAliases.Entry(bonded, false),
                cleanup, new LegacyAliases.Entry(bonded, false),
                source, new LegacyAliases.Entry(bonded, false)), aliases.entries());
        assertEquals(Optional.empty(), aliases.byNpcUuid(UUID.randomUUID()));

        // The file the importer writes loads back to the same map; a world never imported has none.
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();
        Path store = Path.of("Companions");
        assertEquals(LegacyAliases.EMPTY, LegacyAliases.load(io, store));
        aliases.save(io, store).join();
        assertTrue(io.exists(store.resolve("legacy-aliases.json")));
        assertEquals(aliases, LegacyAliases.load(io, store));
    }

    private static CompanionRecord record(ImportResult result, UUID id) {
        return result.records().stream().filter(r -> r.profileId().equals(id)).findFirst().orElseThrow();
    }

    /** A plain state snapshot as {@code CoopResidentStateSnapshotCodec} writes it. */
    private static String plainState(UUID npcUuid, int level) {
        return "{\"version\":\"1\",\"npcUuid\":\"" + npcUuid + "\",\"roleId\":\"Tamed_Wolf\",\"capturedAtMs\":1234,"
                + "\"npcName\":{\"name\":\"Rex\"},"
                + "\"breeding\":{\"configId\":\"Breeding_A\",\"enabled\":true,\"cooldownUntilMs\":-500,"
                + "\"cooldownStartedAtMs\":0,\"cooldownDurationMs\":0},"
                + "\"leveling\":{\"configId\":\"Leveling_A\",\"level\":" + level + ",\"currentXp\":1.5,\"totalXp\":20.0},"
                + "\"currentHealth\":12.0,\"maximumHealth\":40.0}";
    }

    /** The bonded store's {@code {encoding, payload}} envelope around {@code text}. */
    private static String envelope(String text) {
        return "{\"encoding\":\"base64\",\"payload\":\""
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\"}";
    }

    private static LegacyRows.TimedLease lease(UUID id, Long remainingMs, Long cooldownUntilMs) {
        return new LegacyRows.TimedLease(id.toString(), 1, remainingMs == null ? null : "session", remainingMs,
                cooldownUntilMs, "Timed_Wolf", 60_000, 30_000, true, remainingMs == null ? null : 1L, 1, 1);
    }

    private static LegacyRows.BondedProfile withDeath(LegacyRows.BondedProfile p, long diedAtMs) {
        return new LegacyRows.BondedProfile(p.profileId(), p.ownerUuid(), p.rosterId(), p.familyId(), p.roleId(),
                p.state(), p.revision(), p.snapshotJson(), p.createdAtMs(), p.updatedAtMs(), p.policyJson(),
                p.displayName(), p.species(), p.gender(), diedAtMs, p.summonCooldownUntilMs(), p.reviveCount(),
                p.quarantineReason(), p.quarantinedAtMs());
    }

    /** Old rows under construction; every profile is owned by {@link #OWNER} and has role Tamed_Wolf. */
    private static final class Rows {
        final List<LegacyRows.Profile> profiles = new ArrayList<>();
        final List<LegacyRows.Lifecycle> lifecycles = new ArrayList<>();
        final List<LegacyRows.Alias> aliases = new ArrayList<>();
        final List<LegacyRows.Snapshot> snapshots = new ArrayList<>();
        final List<LegacyRows.EntityCheckpoint> checkpoints = new ArrayList<>();
        final List<LegacyRows.ToolLink> tools = new ArrayList<>();
        final List<LegacyRows.RosterMembership> rosters = new ArrayList<>();
        final List<LegacyRows.TimedLease> leases = new ArrayList<>();
        final List<LegacyRows.CoopSlot> coopSlots = new ArrayList<>();
        final List<LegacyRows.CoopResidency> residencies = new ArrayList<>();
        final List<LegacyRows.Provisioning> origins = new ArrayList<>();
        final List<LegacyRows.ExtensionData> extensions = new ArrayList<>();
        final List<LegacyRows.BondedProfile> bondedProfiles = new ArrayList<>();
        final List<LegacyRows.BondedLease> bondedLeases = new ArrayList<>();
        final List<LegacyRows.BondedExtension> bondedExtensions = new ArrayList<>();
        final List<LegacyRows.BondedCleanupTarget> cleanupTargets = new ArrayList<>();
        final List<LegacyRows.BondedCaptureSource> captureSources = new ArrayList<>();
        int unfinishedOperations;
        private int next;

        UUID profile(String state, String locationKind, String locationKey, String worldKey) {
            UUID id = new UUID(0x1000L, ++next);
            profiles.add(new LegacyRows.Profile(id.toString(), "Wolf", "Tamed_Wolf", "{\"tamed\":true}", worldKey,
                    100, 200 + next, 150, 0));
            lifecycles.add(new LegacyRows.Lifecycle(id.toString(), OWNER.toString(), state, locationKind,
                    locationKey, worldKey, null, 1, null, 0, null));
            return id;
        }

        void alias(UUID npcUuid, UUID profileId, String state) {
            aliases.add(new LegacyRows.Alias(npcUuid.toString(), profileId.toString(), aliases.size(), state, 1, null));
        }

        void snapshot(UUID profileId, String snapshotId, String kind, String payloadJson, long lifecycleRevision,
                      long createdAtMs) {
            snapshots.add(new LegacyRows.Snapshot(snapshotId, profileId.toString(), kind, 2, payloadJson,
                    lifecycleRevision, createdAtMs));
        }

        /** A checkpoint as the old runtime wrote it: numbers as strings, the body as extended JSON. */
        void checkpoint(UUID profileId, UUID alias, String worldKey, String x, String y, String z,
                        String capturedAtMs, int level) {
            String holder = "{\\\"Components\\\": {\\\"NPC\\\": {\\\"RoleName\\\": \\\"Tamed_Wolf\\\"},"
                    + " \\\"TameworkNpcName\\\": {\\\"Name\\\": \\\"Rex\\\"},"
                    + " \\\"TameworkLeveling\\\": {\\\"ConfigId\\\": \\\"Leveling_A\\\", \\\"Level\\\": " + level + "}}}";
            checkpoints.add(new LegacyRows.EntityCheckpoint(profileId.toString(), "alias:" + alias,
                    "{\"version\":\"1\",\"worldKey\":\"" + worldKey + "\",\"x\":\"" + x + "\",\"y\":\"" + y
                            + "\",\"z\":\"" + z + "\",\"capturedAtMs\":\"" + capturedAtMs
                            + "\",\"holderExtendedJson\":\"" + holder + "\"}", 1, 1));
        }

        void tool(UUID profileId, String toolUuid) {
            tools.add(new LegacyRows.ToolLink(profileId.toString(), toolUuid, "command", 1, 1));
        }

        /** A bonded profile whose snapshot is {@code state} inside the version and base64 wrappers. */
        UUID bonded(String state, String stateJson) {
            UUID id = new UUID(0x2000L, ++next);
            bondedProfiles.add(new LegacyRows.BondedProfile(id.toString(), OWNER.toString(), "dragons", "fire",
                    "Bonded_Dragon", state, 29, envelope("{\"version\":1,\"fullState\":" + stateJson
                    + ",\"extensions\":{}}"), 100, 9000 + next, "{}", null, "dragon", null, null, -400, 0, null, null));
            return id;
        }

        ImportResult map() {
            return LegacyMapper.map(new LegacyRows(
                    new LegacyRows.State(SOURCE, LegacyRows.StateSchema.V2, profiles, lifecycles, aliases, snapshots,
                            checkpoints, tools, List.of(), rosters, leases, coopSlots, residencies, origins,
                            extensions, unfinishedOperations, 0),
                    new LegacyRows.Bonded(SOURCE, bondedProfiles, bondedLeases, bondedExtensions, cleanupTargets,
                            captureSources)), IMPORT_TIME);
        }
    }
}
