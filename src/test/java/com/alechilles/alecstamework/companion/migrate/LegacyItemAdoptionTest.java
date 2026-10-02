package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.RestoreRules;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.hypixel.hytale.assetstore.TestItemAssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyItemAdoptionTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NPC = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private final CompanionIndex index = new CompanionIndex(System::currentTimeMillis, (b, a) -> { });
    private final Map<UUID, SnapshotEnvelope> snapshots = new HashMap<>();
    private final List<UUID> queued = new ArrayList<>();
    /** Profiles with a body registered right now. */
    private final Set<UUID> bodies = new HashSet<>();
    private CompanionAdmission.Refusal refusal;
    private Field itemAssets;
    private Object previousItemAssets;

    /** An {@link ItemStack} looks its item up in the asset store. */
    @BeforeEach
    void items() throws Exception {
        itemAssets = Item.class.getDeclaredField("ASSET_STORE");
        itemAssets.setAccessible(true);
        previousItemAssets = itemAssets.get(null);
        itemAssets.set(null, new TestItemAssetStore(new DefaultAssetMap<>(Map.of(
                "Test_Capture", new Item("Test_Capture")))));
    }

    @AfterEach
    void restoreItems() throws Exception {
        itemAssets.set(null, previousItemAssets);
    }

    private LegacyItemAdoption adoption(LegacyAliases aliases) {
        return new LegacyItemAdoption(index, aliases, snapshot -> {
            queued.add(snapshot.profileId());
            snapshots.put(snapshot.profileId(), snapshot);
        }, (before, record) -> new CompanionAdmissionGate.Admission(
                refusal == null ? null : CompanionAdmissionGate.Denial.of(refusal), record), () -> 5_000L);
    }

    /** A release through the real restore flow, with a spawner that always succeeds. */
    private RestoreFlow.Result release(CaptureItemKeys.Ref item) {
        RestoreFlow<String> flow = new RestoreFlow<>(index, new LoadedBodies<>(),
                id -> CompletableFuture.completedFuture(snapshots.get(id)),
                owner -> CompletableFuture.completedFuture(null),
                (committed, snapshot, destination, reason) -> CompletableFuture.completedFuture(true),
                (id, body) -> { }, System::currentTimeMillis, (before, after) -> null);
        return flow.restore(RestoreFlow.Request.of(item.profileId(), RestoreRules.Reason.RELEASE,
                new RestoreFlow.Destination("default", 1, 2, 3, 0f, 0f)).withGeneration(item.generation())).join();
    }

    private CompanionRecord importedItem(UUID profileId, long lastSnapshotAtMs) {
        index.insert(CompanionRecord.builder(profileId, "Tamed_Sheep", CompanionLocation.item())
                .ownerUuid(OWNER).ownerName("Alec").lastSnapshotAtMs(lastSnapshotAtMs).build());
        return index.get(profileId);
    }

    /** A 2.x item: the captured body's NPC UUID, the role and the state, and no profile id. */
    private static ItemStack oldItem(int level) {
        return new ItemStack("Test_Capture", 1)
                .withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.STRING, NPC.toString())
                .withMetadata(TameworkMetadataKeys.CAPTURE_ROLE_ID, Codec.STRING, "Tamed_Sheep")
                .withMetadata(TameworkMetadataKeys.TAMED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.NPC_NAME, Codec.STRING, "Dolly")
                .withMetadata(TameworkMetadataKeys.NPC_NAME_UPDATED_MS, Codec.LONG, 10L)
                .withMetadata(TameworkMetadataKeys.LEVELING_LEVEL, Codec.INTEGER, level)
                .withMetadata(TameworkMetadataKeys.LEVELING_TOTAL_XP, Codec.DOUBLE, 420.0);
    }

    private CoopResidentStateSnapshot storedState(UUID profileId) {
        SnapshotEnvelope snapshot = snapshots.get(profileId);
        assertNotNull(snapshot, "a snapshot was queued");
        assertEquals(0L, snapshot.generation());
        CoopResidentStateSnapshot state = RestoreRules.importedState(snapshot);
        assertNotNull(state, "the queued snapshot is a readable format 0 state");
        return state;
    }

    @Test
    void aFourXItemOfAnImportedRecordReleasesOnceAndACopyIsRefused() {
        UUID profileId = UUID.randomUUID();
        importedItem(profileId, 1L);
        snapshots.put(profileId, SnapshotEnvelope.importedState(profileId, 0L, LegacyState.emptyState(NPC)));
        ItemStack item = new ItemStack("Test_Capture", 1)
                .withMetadata(TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING, profileId.toString())
                .withMetadata(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID, Codec.STRING, UUID.randomUUID().toString())
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.STRING, NPC.toString());
        CaptureItemKeys.Ref ref = CaptureItemKeys.readIndexItem(item);

        assertEquals(new CaptureItemKeys.Ref(profileId, 0L), ref);
        assertEquals(RestoreFlow.Result.RESTORED, release(ref));
        assertEquals(LocationKind.LIVE, index.get(profileId).location().kind());
        assertEquals(RestoreFlow.Result.NOT_ALLOWED, release(CaptureItemKeys.readIndexItem(item)),
                "a copy of the released item brings nothing back");
        assertEquals(1L, index.get(profileId).generation());
    }

    @Test
    void aFourXItemWithNoRecordIsRefused() {
        ItemStack item = new ItemStack("Test_Capture", 1)
                .withMetadata(TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING, UUID.randomUUID().toString())
                .withMetadata(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID, Codec.STRING, UUID.randomUUID().toString());

        assertEquals(RestoreFlow.Result.NOT_FOUND, release(CaptureItemKeys.readIndexItem(item)));
    }

    @Test
    void aTwoXItemNoRecordKnowsCreatesOneRecordAndACopyIsRefusedAfterItsRelease() {
        ItemStack item = oldItem(7);
        LegacyItemAdoption adoption = adoption(LegacyAliases.EMPTY);

        LegacyItemAdoption.Adoption first = adoption.adopt(item.getMetadata(), PLAYER, "Player", bodies::contains);

        assertEquals(LegacyItemAdoption.Result.ADOPTED, first.result());
        assertEquals(new CaptureItemKeys.Ref(NPC, 0L), first.ref(), "every copy of the item means this profile");
        CompanionRecord created = index.get(NPC);
        assertEquals(LocationKind.ITEM, created.location().kind());
        assertEquals("Tamed_Sheep", created.roleId());
        assertEquals(PLAYER, created.ownerUuid(), "a tamed companion with no owner on the item goes to the releaser");
        assertEquals("Dolly", created.displayName());
        assertEquals(7, storedState(NPC).leveling().getLevel());
        assertEquals("Dolly", storedState(NPC).npcName().getName());

        assertEquals(RestoreFlow.Result.RESTORED, release(first.ref()));
        LegacyItemAdoption.Adoption copy = adoption.adopt(item.getMetadata(), OWNER, "Alec", bodies::contains);

        assertEquals(LegacyItemAdoption.Result.STALE, copy.result());
        assertNull(copy.ref());
        List<CompanionRecord> all = new ArrayList<>();
        index.forEach(all::add);
        assertEquals(1, all.size(), "the copy made no second companion");
        assertEquals(PLAYER, index.get(NPC).ownerUuid());
    }

    @Test
    void aTwoXItemKeepsTheOwnerItNamesAndAnUntamedOneStaysUnowned() {
        ItemStack owned = oldItem(2).withMetadata(TameworkMetadataKeys.OWNER_UUID, Codec.STRING, OWNER.toString());
        assertEquals(LegacyItemAdoption.Result.ADOPTED,
                adoption(LegacyAliases.EMPTY).adopt(owned.getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertEquals(OWNER, index.get(NPC).ownerUuid());

        UUID wildNpc = UUID.randomUUID();
        ItemStack wild = oldItem(1)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.STRING, wildNpc.toString())
                .withMetadata(TameworkMetadataKeys.TAMED, Codec.BOOLEAN, false);
        assertEquals(LegacyItemAdoption.Result.ADOPTED,
                adoption(LegacyAliases.EMPTY).adopt(wild.getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertNull(index.get(wildNpc).ownerUuid());
    }

    @Test
    void anImportedRecordWithNoStateIsRestoredFromItsItem() {
        UUID profileId = UUID.randomUUID();
        importedItem(profileId, 0L);
        LegacyAliases aliases = new LegacyAliases(Map.of(NPC, new LegacyAliases.Entry(profileId, LegacyAliases.Kind.STALE)));
        ItemStack item = oldItem(9);

        assertEquals(LegacyItemAdoption.Kind.RESTORE_FROM_ITEM,
                LegacyItemAdoption.decide(item.getMetadata(), index::get, aliases, bodies::contains).kind());
        LegacyItemAdoption.Adoption adopted = adoption(aliases).adopt(item.getMetadata(), PLAYER, "Player", bodies::contains);

        assertEquals(new CaptureItemKeys.Ref(profileId, 0L), adopted.ref());
        CoopResidentStateSnapshot state = storedState(profileId);
        assertEquals(9, state.leveling().getLevel());
        assertEquals(OWNER, state.owner().getOwnerId(), "the record's owner, not the releasing player");
        assertEquals(OWNER, index.get(profileId).ownerUuid());
        assertEquals(RestoreFlow.Result.RESTORED, release(adopted.ref()));
        assertEquals(LegacyItemAdoption.Result.STALE,
                adoption(aliases).adopt(item.getMetadata(), PLAYER, "Player", bodies::contains).result());
    }

    /** 4.x imported some 2.x captures as bodies in unloaded chunks; the companion is really in the item. */
    @Test
    void anImportedLiveRecordWhoseBodyIsTheItemsAndIsNotLoadedIsReleasedFromItsItem() {
        UUID profileId = UUID.randomUUID();
        index.insert(CompanionRecord.builder(profileId, "Tamed_Sheep", CompanionLocation.live("default", 0, 0, 0))
                .ownerUuid(OWNER).ownerName("Alec").currentNpcUuid(NPC).build());
        LegacyAliases aliases = new LegacyAliases(Map.of(NPC, new LegacyAliases.Entry(profileId, LegacyAliases.Kind.CURRENT)));
        ItemStack item = oldItem(6);

        bodies.add(profileId);
        assertEquals(LegacyItemAdoption.Result.STALE,
                adoption(aliases).adopt(item.getMetadata(), PLAYER, "Player", bodies::contains).result(),
                "the companion is out in the world: the item is a stale copy");
        assertEquals(LocationKind.LIVE, index.get(profileId).location().kind());
        assertTrue(queued.isEmpty());

        bodies.clear();
        LegacyItemAdoption.Adoption adopted = adoption(aliases).adopt(item.getMetadata(), PLAYER, "Player", bodies::contains);

        assertEquals(new CaptureItemKeys.Ref(profileId, 0L), adopted.ref());
        CompanionRecord inItem = index.get(profileId);
        assertEquals(LocationKind.ITEM, inItem.location().kind());
        assertEquals(0L, inItem.generation());
        assertNull(inItem.currentNpcUuid(), "the old body, if it ever loads, no longer belongs to the record");
        assertEquals(OWNER, inItem.ownerUuid());
        assertEquals(6, storedState(profileId).leveling().getLevel());
        assertEquals(RestoreFlow.Result.RESTORED, release(adopted.ref()));
        assertEquals(LegacyItemAdoption.Result.STALE,
                adoption(aliases).adopt(item.getMetadata(), PLAYER, "Player", bodies::contains).result());
    }

    @Test
    void anImportedLiveRecordWithADifferentBodyOrStoredStateIsNotTakenFromTheWorld() {
        UUID otherBody = UUID.randomUUID();
        index.insert(CompanionRecord.builder(otherBody, "Tamed_Sheep", CompanionLocation.live("default", 0, 0, 0))
                .ownerUuid(OWNER).currentNpcUuid(UUID.randomUUID()).build());
        UUID checkpointed = UUID.randomUUID();
        index.insert(CompanionRecord.builder(checkpointed, "Tamed_Sheep", CompanionLocation.live("default", 0, 0, 0))
                .ownerUuid(OWNER).currentNpcUuid(NPC).lastSnapshotAtMs(50L).build());
        // 5.0 matched this one to its body (it has a real position); the body is unloaded again
        // and no snapshot was taken yet. The companion is in the world, not in the item.
        UUID sighted = UUID.randomUUID();
        index.insert(CompanionRecord.builder(sighted, "Tamed_Sheep", CompanionLocation.live("default", 40.5, 64, -3))
                .ownerUuid(OWNER).currentNpcUuid(NPC).build());

        assertEquals(LegacyItemAdoption.Result.STALE, adoption(new LegacyAliases(Map.of(NPC,
                new LegacyAliases.Entry(otherBody, LegacyAliases.Kind.STALE))))
                .adopt(oldItem(6).getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertEquals(LegacyItemAdoption.Result.STALE, adoption(new LegacyAliases(Map.of(NPC,
                new LegacyAliases.Entry(checkpointed, LegacyAliases.Kind.CURRENT))))
                .adopt(oldItem(6).getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertEquals(LegacyItemAdoption.Result.STALE, adoption(new LegacyAliases(Map.of(NPC,
                new LegacyAliases.Entry(sighted, LegacyAliases.Kind.CURRENT))))
                .adopt(oldItem(6).getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertEquals(LocationKind.LIVE, index.get(sighted).location().kind());
        assertTrue(queued.isEmpty());
    }

    @Test
    void anItemNamingABodyThatHasItsOwnRecordCreatesNothing() {
        UUID living = UUID.randomUUID();
        index.insert(CompanionRecord.builder(living, "Tamed_Sheep", CompanionLocation.live("default", 0, 0, 0))
                .ownerUuid(OWNER).currentNpcUuid(NPC).generation(3).build());

        assertEquals(LegacyItemAdoption.Result.STALE, adoption(LegacyAliases.EMPTY)
                .adopt(oldItem(6).getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertNull(index.get(NPC));
        assertTrue(queued.isEmpty());
    }

    @Test
    void anImportedRecordThatHoldsStateIsNotOverwrittenByAnOlderItem() {
        UUID profileId = UUID.randomUUID();
        importedItem(profileId, 77L);
        LegacyAliases aliases = new LegacyAliases(Map.of(NPC, new LegacyAliases.Entry(profileId, LegacyAliases.Kind.STALE)));

        LegacyItemAdoption.Adoption adopted = adoption(aliases).adopt(oldItem(9).getMetadata(), PLAYER, "Player", bodies::contains);

        assertEquals(LegacyItemAdoption.Result.ADOPTED, adopted.result());
        assertEquals(new CaptureItemKeys.Ref(profileId, 0L), adopted.ref());
        assertTrue(queued.isEmpty(), "the stored snapshot stays the record's state");
    }

    @Test
    void anItemWhoseStateCannotBeReadChangesNothing() {
        UUID profileId = UUID.randomUUID();
        importedItem(profileId, 0L);
        LegacyAliases aliases = new LegacyAliases(Map.of(NPC, new LegacyAliases.Entry(profileId, LegacyAliases.Kind.STALE)));
        // A level stored as text: the leveling group cannot be read.
        ItemStack broken = oldItem(3).withMetadata(TameworkMetadataKeys.LEVELING_LEVEL, Codec.STRING, "three");

        assertEquals(LegacyItemAdoption.Result.UNREADABLE,
                adoption(aliases).adopt(broken.getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertTrue(queued.isEmpty(), "no empty state replaces what the item holds");

        ItemStack unknown = broken.withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.STRING, UUID.randomUUID().toString());
        assertEquals(LegacyItemAdoption.Result.UNREADABLE,
                adoption(LegacyAliases.EMPTY).adopt(unknown.getMetadata(), PLAYER, "Player", bodies::contains).result());
        List<CompanionRecord> all = new ArrayList<>();
        index.forEach(all::add);
        assertEquals(1, all.size(), "no record is created from an unreadable item");
    }

    @Test
    void aNewOwnerAtTheirLimitGetsNoRecord() {
        refusal = CompanionAdmission.Refusal.OWNED;

        LegacyItemAdoption.Adoption refused = adoption(LegacyAliases.EMPTY).adopt(oldItem(4).getMetadata(), PLAYER, "Player", bodies::contains);

        assertEquals(LegacyItemAdoption.Result.LIMIT, refused.result());
        assertNotNull(refused.messageKey());
        assertNull(index.get(NPC));
        assertTrue(queued.isEmpty());
    }

    @Test
    void aDamagedItemIsRefused() {
        ItemStack snapshotOnly = oldItem(1)
                .withMetadata(TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID, Codec.STRING, UUID.randomUUID().toString());

        assertEquals(LegacyItemAdoption.Kind.REFUSE,
                LegacyItemAdoption.decide(snapshotOnly.getMetadata(), index::get, LegacyAliases.EMPTY, bodies::contains).kind());
        assertEquals(LegacyItemAdoption.Result.INVALID,
                adoption(LegacyAliases.EMPTY).adopt(snapshotOnly.getMetadata(), PLAYER, "Player", bodies::contains).result());
        assertNull(index.get(NPC));
    }
}
