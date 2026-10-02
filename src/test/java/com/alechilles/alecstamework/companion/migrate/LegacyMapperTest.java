package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.flow.RestoreRules;
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
        rows.profiles.set(0, new LegacyRows.Profile(id.toString(), null, "Wolf_Pup", null, null, 1, 1, 1, 0));
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
        assertEquals("Tamed_Wolf", imported.roleId(), "the body is the authority for its role: it grew up since the row was written");
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
    void aCheckpointOfADyingBodyIsMadeRestorable() {
        UUID id = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.checkpoint(id, NPC, "world-a", "1.0", "2.0", "3.0", "7000", 4, "\"Death\": {\"DeathCause\": \"Fall\"},"
                + " \"EntityStats\": {\"Stats\": {\"Health\": {\"Id\": \"Health\", \"Value\": 0.0}}},");

        ImportResult result = rows.map();

        assertEquals(LocationKind.LIVE, record(result, id).location().kind(), "the lifecycle row is the truth");
        SnapshotEnvelope snapshot = result.snapshots().get(id);
        assertEquals(RestoreRules.Verdict.ALLOWED, RestoreRules.forSnapshot(
                record(result, id).toBuilder().location(CompanionLocation.lost(null)).build(), snapshot,
                RestoreRules.Reason.RECOVER), "if the body is gone, a recover can use the snapshot");
        assertTrue(CompanionSnapshots.entity(snapshot).getDocument("Components").getDocument("EntityStats")
                .getDocument("Stats").getDocument("Health").getNumber("Value").doubleValue() > 0.0);
        assertEquals(4, record(result, id).summary().level());
        assertEquals(List.of(id), result.report().checkpointsOfDyingBodies());
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
        rows.profiles.set(0, new LegacyRows.Profile(id.toString(), null, "Wolf_Pup", null, null, 1, 1, 1, 0));

        CompanionRecord imported = record(rows.map(), id);

        CompanionSummary summary = imported.summary();
        assertEquals("Tamed_Wolf", imported.roleId(), "the snapshot of its own kind has the role the body had");
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
        // Every link type names a command tool; the 2.x import typed a link by the table it came from.
        rows.tool(id, "tool-b", "command");
        rows.tool(id, "tool-a", "profile");
        rows.tool(id, "tool-c", "death");
        rows.tool(id, "tool-a", "capture");
        rows.tool(id, "tool-b", "coop");
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
        assertEquals(List.of("tool-b", "tool-a", "tool-c"), imported.toolIds());
        assertEquals(2500L, imported.updatedAtMs());
        CompanionRecord bondedRecord = record(result, bonded);
        assertEquals("dragons", bondedRecord.rosterId());
        assertTrue(bondedRecord.bonded());
        assertEquals(29L, bondedRecord.revision());
        assertEquals(OWNER, bondedRecord.ownerUuid());
        assertEquals(-400L, bondedRecord.summonCooldownUntilMs());
        assertEquals("Rex", bondedRecord.displayName(), "a bonded companion is named by its body's name component");
        assertEquals("Tamed_Wolf", bondedRecord.roleId(),
                "the state has the form the companion is in now; the row keeps the role it was bonded as");
        assertEquals(plainState(STATE_NPC, 3), result.snapshots().get(bonded).importedStateJson(),
                "the bonded base64 and version wrappers are removed");
    }

    @Test
    void extensionDataKeepsItsPublicRevision() {
        UUID id = rows.profile("LOST", "NONE", null, null);
        rows.extensions.add(new LegacyRows.ExtensionData(id.toString(), "Mod:Thing", "k1", 1, "{\"level\":4}", 12, 1, 1));
        rows.extensions.add(new LegacyRows.ExtensionData(id.toString(), "Mod/Bad", "k1", 1, "{}", 3, 1, 1));
        rows.extensions.add(new LegacyRows.ExtensionData(id.toString(), "Alechilles:Tamework",
                "managed-coop-production-v1", 1, "{\"eligibleMs\":5,\"version\":1}", 3, 1, 1));
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
                // The 4.x coop production watermark: a 5.0 coop keeps its own on the coop block.
                new ImportResult.Skipped("profile_extension_data",
                        id + "|Alechilles:Tamework|managed-coop-production-v1", ImportResult.SKIP_RESERVED_NAMESPACE),
                new ImportResult.Skipped("bonded_companion_extension_data", bonded + "|Bad/Namespace",
                        ImportResult.SKIP_NAMESPACE_SLASH)),
                result.report().skippedRows());
    }

    @Test
    void theNewerOfTwoProfilesKeepsASharedNpcUuid() {
        UUID older = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.leases.add(lease(older, 45_000L, null));
        UUID bonded = rows.bonded("ACTIVE", plainState(STATE_NPC, 3));
        rows.bondedLeases.add(new LegacyRows.BondedLease(bonded.toString(), NPC.toString(), "world-a", 0));

        ImportResult result = rows.map();

        assertEquals(LocationKind.LIVE, record(result, bonded).location().kind());
        assertEquals(NPC, record(result, bonded).currentNpcUuid());
        assertEquals(CompanionLocation.lost(LegacyMapper.CAUSE_NPC_UUID_COLLISION), record(result, older).location());
        assertNull(record(result, older).currentNpcUuid());
        assertEquals(0L, record(result, older).summonedUntilMs());
        assertEquals(List.of(older, bonded), result.report().npcUuidCollisions());
        assertEquals(Optional.of(new LegacyAliases.Entry(bonded, LegacyAliases.Kind.CURRENT)), result.aliases().byNpcUuid(NPC));
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
                NPC, new LegacyAliases.Entry(live, LegacyAliases.Kind.CURRENT),
                OLD_NPC, new LegacyAliases.Entry(live, LegacyAliases.Kind.STALE),
                lostBody, new LegacyAliases.Entry(lost, LegacyAliases.Kind.STALE),
                STATE_NPC, new LegacyAliases.Entry(lost, LegacyAliases.Kind.STALE),
                UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"), new LegacyAliases.Entry(bonded, LegacyAliases.Kind.STALE),
                cleanup, new LegacyAliases.Entry(bonded, LegacyAliases.Kind.STALE),
                source, new LegacyAliases.Entry(bonded, LegacyAliases.Kind.STALE)), aliases.entries());
        assertEquals(Optional.empty(), aliases.byNpcUuid(UUID.randomUUID()));

        // The file the importer writes loads back to the same map; a world never imported has none.
        MemoryCompanionFileIo io = new MemoryCompanionFileIo();
        Path store = Path.of("Companions");
        assertEquals(LegacyAliases.EMPTY, LegacyAliases.load(io, store));
        aliases.save(io, store).join();
        assertTrue(io.exists(store.resolve("legacy-aliases.json")));
        assertEquals(aliases, LegacyAliases.load(io, store));
    }

    @Test
    void aVersion1DeathPayloadBecomesAFullStateWithItsTimers() {
        UUID id = rows.profile("DEAD_REVIVABLE", "NONE", null, null);
        rows.profiles.set(0, new LegacyRows.Profile(id.toString(), "Wolf", "tamed_wolf",
                "{\"owner_name\":\"Alec\",\"custom_name\":\"Rex\",\"tamed\":true}", null, 1, 1, 1, 0));
        rows.alias(NPC, id, "CURRENT");
        rows.tool(id, "tool-a", "death");
        String skin = Base64.getUrlEncoder().encodeToString("Skin".getBytes(StandardCharsets.UTF_8)) + ","
                + Base64.getUrlEncoder().encodeToString("Blue".getBytes(StandardCharsets.UTF_8));
        // The flat shape the 2.x persistence wrote: no npcUuid, identity kept in the profile rows.
        rows.snapshot(id, "snap-death", "death", 1, "{\"ownerId\":\"" + OWNER + "\",\"roleId\":\"Tamed_Wolf\","
                + "\"tamed\":true,\"diedAtMs\":5000,\"respawnAvailableAtMs\":6000,\"deathCauseKind\":\"PLAYER\","
                + "\"deathSourceName\":\"Bob\",\"levelingConfigId\":\"Leveling_A\",\"levelingLevel\":9,"
                + "\"levelingTotalXp\":300.0,\"traitsConfigId\":\"Traits_A\",\"traitsRollSeed\":7,"
                + "\"traitsValues\":\"[{\\\"id\\\":\\\"Trait_Health\\\",\\\"value\\\":1.25}]\","
                + "\"talentsConfigId\":\"Talents_A\",\"talentsSpentPoints\":2,\"purchasedTalentIds\":\"Bite|Howl\","
                + "\"attachmentsValues\":\"" + skin + "\",\"breedingConfigId\":\"Breeding_A\","
                + "\"breedingCooldownUntilMs\":-700,\"lifeStage\":\"Adult\",\"lifeStageGender\":\"Female\"}", 3, 2400, true);

        ImportResult result = rows.map();

        CompanionRecord imported = record(result, id);
        assertEquals(CompanionLocation.dead("PLAYER:Bob"), imported.location());
        assertEquals(5000L, imported.diedAtMs());
        assertEquals(6000L, imported.reviveAvailableAtMs());
        assertEquals("Tamed_Wolf", imported.roleId());
        assertEquals(9, imported.summary().level());
        assertEquals(Map.of("Trait_Health", 1.25), imported.summary().traits());
        assertEquals(-700L, imported.summary().breedingCooldownUntilMs());
        var state = new CoopResidentStateSnapshotCodec().decode(result.snapshots().get(id).importedStateJson())
                .snapshotOrNull();
        assertNotNull(state, "the restore path can read the rebuilt state");
        assertEquals(NPC, state.npcUuid());
        assertEquals(9, state.leveling().getLevel());
        assertEquals(300.0, state.leveling().getTotalXp());
        assertEquals(List.of("Bite", "Howl"), List.of(state.talents().getPurchasedTalentIds()));
        assertEquals(Map.of("Skin", "Blue"), state.attachments().getAttachmentIds());
        assertEquals("Female", state.lifeStage().getGender());
        assertEquals(OWNER, state.owner().getOwnerId());
        assertEquals("Rex", state.npcName().getName());
        assertEquals(List.of("tool-a"), List.of(state.commandLinks().getToolIds()));
        assertEquals(List.of(), result.report().skippedRows());
        assertEquals(List.of(), result.report().withoutState());
    }

    @Test
    void aVersion1LostPayloadKeepsIdentityAndAnOlderFullStateIsPreferred() {
        String lostV1 = "{\"lastKnownPosition\":{\"x\":1.0,\"y\":2.0,\"z\":3.0},\"homePosition\":{\"x\":4.0,"
                + "\"y\":5.0,\"z\":6.0},\"lostAtMs\":900,\"relocationRetryAttempts\":2,\"recoveredAtMs\":0}";
        UUID identityOnly = rows.profile("LOST", "NONE", null, null);
        rows.profiles.set(0, new LegacyRows.Profile(identityOnly.toString(), null, "Tamed_Wolf",
                "{\"owner_name\":\"Alec\",\"custom_name\":\"Jade\",\"tamed\":true}", null, 1, 1, 1, 0));
        rows.snapshot(identityOnly, "lost-1", "lost", 1, lostV1, 4, 2000, true);
        UUID withHistory = rows.profile("LOST", "NONE", null, null);
        rows.snapshot(withHistory, "lost-2", "lost", 1, lostV1, 9, 3000, true);
        rows.snapshot(withHistory, "older-capture", "capture", 2, plainState(STATE_NPC, 6), 5, 1000, false);

        ImportResult result = rows.map();

        var state = new CoopResidentStateSnapshotCodec().decode(
                result.snapshots().get(identityOnly).importedStateJson()).snapshotOrNull();
        assertEquals(identityOnly, state.npcUuid(), "with no alias the profile id stands in for the body");
        assertEquals(OWNER, state.owner().getOwnerId());
        assertEquals("Jade", state.npcName().getName());
        assertTrue(state.tamed().isTamed());
        assertEquals(5.0, state.commandLinks().getHomePosition().y());
        assertEquals("Jade", record(result, identityOnly).displayName());
        assertEquals(6, record(result, withHistory).summary().level(),
                "an older snapshot with progression beats a current one that holds only identity");
        assertEquals(List.of(), result.report().withoutState());
        assertEquals(List.of(), result.report().skippedRows());
    }

    @Test
    void aCapturedCompanionIsAnItemAndA2xCaptureHasItsStateInTheItem() {
        UUID owned = rows.profile("CAPTURED", "CAPTURE_ITEM", "capture-v1", null);
        rows.alias(NPC, owned, "CURRENT");
        rows.snapshot(owned, "capture-v1", "capture", 1, "{\"capturedAtMs\":1000,\"roleId\":\"Tamed_Wolf\","
                + "\"displayName\":\"Wolf\",\"lastKnownPosition\":{\"x\":1.0,\"y\":2.0,\"z\":3.0}}", 2, 1000, true);
        UUID unowned = rows.profile("CAPTURED", "CAPTURE_ITEM", "capture-v2", null);
        rows.lifecycles.set(1, new LegacyRows.Lifecycle(unowned.toString(), null, "CAPTURED", "CAPTURE_ITEM",
                "capture-v2", null, null, 1, null, 0, null));
        rows.snapshot(unowned, "capture-v2", "capture", 2, plainState(STATE_NPC, 5), 1, 1000, true);

        ImportResult result = rows.map();

        assertEquals(CompanionLocation.item(), record(result, owned).location());
        assertNull(record(result, owned).currentNpcUuid());
        assertNull(result.snapshots().get(owned), "an empty state would let a release replace what the item holds");
        assertEquals(List.of(owned), result.report().stateInItem());
        assertEquals(CompanionLocation.item(), record(result, unowned).location());
        assertNull(record(result, unowned).ownerUuid());
        assertEquals(RecordScope.WORLD_BOUND, record(result, unowned).scope());
        assertEquals(5, record(result, unowned).summary().level());
        assertEquals(plainState(STATE_NPC, 5), result.snapshots().get(unowned).importedStateJson());
        assertEquals(List.of(), result.report().skippedRows());
        assertEquals(Optional.of(new LegacyAliases.Entry(owned, LegacyAliases.Kind.STALE)), result.aliases().byNpcUuid(NPC),
                "the captured body's alias is stale");
    }

    @Test
    void anUnloadedBodyWithNoWorldOnRecordStaysLive() {
        rows.profile("ACTIVE", "LIVE_ENTITY", UUID.randomUUID().toString(), "world-a");
        UUID withCheckpoint = rows.profile("UNLOADED", "NONE", null, null);
        rows.alias(NPC, withCheckpoint, "CURRENT");
        rows.checkpoint(withCheckpoint, NPC, "world-b", "1.0", "2.0", "3.0", "7000", 4);
        UUID bare = rows.profile("UNLOADED", "NONE", null, null);
        rows.alias(OLD_NPC, bare, "CURRENT");
        UUID withHistory = rows.profile("UNLOADED", "NONE", null, null);
        rows.alias(STATE_NPC, withHistory, "CURRENT");
        rows.snapshot(withHistory, "old-capture", "capture", 2, plainState(STATE_NPC, 8), 3, 1500, false);
        UUID diedBefore = rows.profile("UNLOADED", "NONE", null, null);
        UUID revivedBody = UUID.randomUUID();
        rows.alias(revivedBody, diedBefore, "CURRENT");
        rows.snapshot(diedBefore, "old-death", "death", 2, "{\"fullState\":" + plainState(STATE_NPC, 9)
                + ",\"diedAtMs\":1,\"respawnAvailableAtMs\":2,\"deathCauseKind\":\"NPC\"}", 3, 1500, false);

        ImportResult result = rows.map();

        assertEquals(CompanionLocation.live("world-b", 1.0, 2.0, 3.0), record(result, withCheckpoint).location());
        assertEquals(NPC, record(result, withCheckpoint).currentNpcUuid());
        // The old runtime cleared the world of an unloaded body; the world most rows name stands in.
        assertEquals(CompanionLocation.live("world-a", 0, 0, 0), record(result, bare).location());
        assertEquals(OLD_NPC, record(result, bare).currentNpcUuid());
        assertEquals("{\"npcUuid\":\"" + OLD_NPC + "\"}", result.snapshots().get(bare).importedStateJson());
        assertEquals(8, record(result, withHistory).summary().level(),
                "an old snapshot is the best saved state of a body that is out in the world");
        assertEquals(List.of(withHistory), result.report().liveUsedHistory());
        assertEquals("{\"npcUuid\":\"" + revivedBody + "\"}", result.snapshots().get(diedBefore).importedStateJson(),
                "an old death snapshot is the state of a life the body has left behind");
        assertEquals(List.of(bare, withHistory, diedBefore), result.report().liveWorldGuessed());
        assertEquals(List.of(), result.report().importedLost());
        assertEquals(Optional.of(new LegacyAliases.Entry(bare, LegacyAliases.Kind.CURRENT)), result.aliases().byNpcUuid(OLD_NPC));
    }

    @Test
    void withNoWorldInAnyRowTheDefaultWorldIsAssumed() {
        UUID id = rows.profile("UNLOADED", "NONE", null, null);
        rows.alias(NPC, id, "CURRENT");

        assertEquals(CompanionLocation.live("default", 0, 0, 0), record(rows.map(), id).location());
    }

    @Test
    void aRecallRecoverySnapshotIsTheStateOfALiveBodyWithNoCheckpoint() {
        UUID id = rows.profile("ACTIVE", "LIVE_ENTITY", NPC.toString(), "world-a");
        rows.snapshot(id, "recovery", "public_import_recovery", 1, plainState(NPC, 11), 0, 1200, true);

        ImportResult result = rows.map();

        assertEquals(LocationKind.LIVE, record(result, id).location().kind());
        assertEquals(11, record(result, id).summary().level());
        assertEquals(plainState(NPC, 11), result.snapshots().get(id).importedStateJson());
        assertEquals(1200L, record(result, id).lastSnapshotAtMs());
    }

    @Test
    void aQuarantinedUnresolvedProfileKeepsTheStateOfItsOldSnapshots() {
        UUID id = rows.profile("UNRESOLVED", "UNRESOLVED", null, null);
        rows.alias(NPC, id, "CURRENT");
        rows.snapshot(id, "old-death", "death", 1, "{\"tamed\":true,\"diedAtMs\":5,\"respawnAvailableAtMs\":6,"
                + "\"levelingConfigId\":\"Leveling_A\",\"levelingLevel\":4,\"levelingTotalXp\":50.0}", 2, 1000, false);
        rows.snapshot(id, "old-lost", "lost", 1, "{\"lostAtMs\":900}", 5, 2000, false);

        ImportResult result = rows.map();

        assertEquals(CompanionLocation.lost(LegacyMapper.CAUSE_UNRESOLVED), record(result, id).location());
        assertEquals(4, record(result, id).summary().level());
        assertEquals(List.of(), result.report().withoutState());
        assertEquals(Optional.of(new LegacyAliases.Entry(id, LegacyAliases.Kind.REJOIN)), result.aliases().byNpcUuid(NPC),
                "if its last body turns up, that body is the companion");
    }

    @Test
    void oneProfileThatCannotBeMappedDoesNotStopTheImport() {
        UUID bad = rows.profile("LOST", "NONE", null, null);
        // A record refuses an origin with no key; any such refusal must cost only this profile.
        rows.origins.add(new LegacyRows.Provisioning(bad.toString(), "Mod:Spawner", null, null, 1));
        UUID good = rows.profile("LOST", "NONE", null, null);

        ImportResult result = rows.map();

        assertEquals(List.of(good), result.records().stream().map(CompanionRecord::profileId).toList());
        assertNull(result.snapshots().get(bad));
        assertEquals(List.of(good), result.report().withoutState());
        assertEquals(1, result.report().skippedRows().size());
        ImportResult.Skipped skipped = result.report().skippedRows().get(0);
        assertEquals(ImportResult.SKIP_MAPPING_FAILED, skipped.reason());
        assertTrue(skipped.key().startsWith(bad + " (IllegalArgumentException: "), skipped.key());
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
        return new LegacyRows.TimedLease(id.toString(), remainingMs, cooldownUntilMs);
    }

    private static LegacyRows.BondedProfile withDeath(LegacyRows.BondedProfile p, long diedAtMs) {
        return new LegacyRows.BondedProfile(p.profileId(), p.ownerUuid(), p.rosterId(), p.roleId(), p.state(),
                p.revision(), p.snapshotJson(), p.updatedAtMs(), diedAtMs, p.summonCooldownUntilMs());
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

        /** A current snapshot with payload version 2. */
        void snapshot(UUID profileId, String snapshotId, String kind, String payloadJson, long lifecycleRevision,
                      long createdAtMs) {
            snapshot(profileId, snapshotId, kind, 2, payloadJson, lifecycleRevision, createdAtMs, true);
        }

        void snapshot(UUID profileId, String snapshotId, String kind, int version, String payloadJson,
                      long lifecycleRevision, long createdAtMs, boolean current) {
            snapshots.add(new LegacyRows.Snapshot(snapshotId, profileId.toString(), kind, version, payloadJson,
                    lifecycleRevision, createdAtMs, current));
        }

        /** A checkpoint as the old runtime wrote it: numbers as strings, the body as extended JSON. */
        void checkpoint(UUID profileId, UUID alias, String worldKey, String x, String y, String z,
                        String capturedAtMs, int level) {
            checkpoint(profileId, alias, worldKey, x, y, z, capturedAtMs, level, "");
        }

        /** {@code extraComponents} is extended JSON placed first inside {@code Components}, ending with a comma. */
        void checkpoint(UUID profileId, UUID alias, String worldKey, String x, String y, String z,
                        String capturedAtMs, int level, String extraComponents) {
            String holder = "{\\\"Components\\\": {" + extraComponents.replace("\"", "\\\"")
                    + "\\\"NPC\\\": {\\\"RoleName\\\": \\\"Tamed_Wolf\\\"},"
                    + " \\\"TameworkNpcName\\\": {\\\"Name\\\": \\\"Rex\\\"},"
                    + " \\\"TameworkLeveling\\\": {\\\"ConfigId\\\": \\\"Leveling_A\\\", \\\"Level\\\": " + level + "}}}";
            checkpoints.add(new LegacyRows.EntityCheckpoint(profileId.toString(), "alias:" + alias,
                    "{\"version\":\"1\",\"worldKey\":\"" + worldKey + "\",\"x\":\"" + x + "\",\"y\":\"" + y
                            + "\",\"z\":\"" + z + "\",\"capturedAtMs\":\"" + capturedAtMs
                            + "\",\"holderExtendedJson\":\"" + holder + "\"}", 1, Long.parseLong(capturedAtMs)));
        }

        void tool(UUID profileId, String toolUuid) {
            tool(profileId, toolUuid, "command");
        }

        void tool(UUID profileId, String toolUuid, String linkType) {
            tools.add(new LegacyRows.ToolLink(profileId.toString(), toolUuid, linkType, 1, 1));
        }

        /** A bonded profile whose snapshot is {@code state} inside the version and base64 wrappers. */
        UUID bonded(String state, String stateJson) {
            UUID id = new UUID(0x2000L, ++next);
            bondedProfiles.add(new LegacyRows.BondedProfile(id.toString(), OWNER.toString(), "dragons",
                    "Bonded_Dragon", state, 29, envelope("{\"version\":1,\"fullState\":" + stateJson
                    + ",\"extensions\":{}}"), 9000 + next, null, -400));
            return id;
        }

        ImportResult map() {
            return LegacyMapper.map(new LegacyRows(
                    new LegacyRows.State(SOURCE, LegacyRows.StateSchema.V2, profiles, lifecycles, aliases, snapshots,
                            checkpoints, tools, rosters, leases, coopSlots, residencies, origins,
                            extensions, unfinishedOperations),
                    new LegacyRows.Bonded(SOURCE, bondedProfiles, bondedLeases, bondedExtensions, cleanupTargets,
                            captureSources)), IMPORT_TIME);
        }
    }
}
