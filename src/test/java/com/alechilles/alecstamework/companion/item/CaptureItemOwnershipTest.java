package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Decision;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Pickup;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Release;
import com.alechilles.alecstamework.settings.CaptureItemOwnershipMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureItemOwnershipTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID holder = UUID.randomUUID();

    private CompanionRecord inItem(long generation) {
        return CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.item())
                .ownerUuid(owner).ownerName("Owner").homeWorld("w").generation(generation).build();
    }

    @Test
    void aStaleOrDuplicatedCopyNeverMovesOwnership() {
        assertEquals(Decision.STALE, CaptureItemOwnership.decide(inItem(3), 2, holder, null));
        CompanionRecord deployed = inItem(3).toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build();
        assertEquals(Decision.STALE, CaptureItemOwnership.decide(deployed, 3, holder, null));
    }

    @Test
    void theOwnerPickingItUpIsAlreadyOwned() {
        assertEquals(Decision.ALREADY_OWNED, CaptureItemOwnership.decide(inItem(1), 1, owner, null));
    }

    @Test
    void nothingMovesForAnUnownedCaptureOrAMissingRecord() {
        CompanionRecord unowned = inItem(1).toBuilder().ownerUuid(null).ownerName(null).build();
        assertEquals(Decision.IGNORE, CaptureItemOwnership.decide(unowned, 1, holder, null));
        assertEquals(Decision.IGNORE, CaptureItemOwnership.decide(null, 1, holder, null));
    }

    @Test
    void aHolderAtTheirOwnedLimitIsRefusedAndTheOldOwnerKeepsIt() {
        CompanionRecord record = inItem(1);
        List<CompanionRecord> holderRecords = List.of(
                CompanionRecord.builder(UUID.randomUUID(), "Sheep", CompanionLocation.stored(StoredReason.ROSTER))
                        .ownerUuid(holder).homeWorld("w").build());
        CompanionAdmission.Rules oneOwned = new CompanionAdmission.Rules(1, false, role -> List.of());
        CompanionAdmission.Refusal refusal = CompanionAdmission.check(holderRecords, record,
                CaptureItemOwnership.asOwnedBy(record, holder, "Holder"), oneOwned,
                CompanionAdmission.Provided.none());

        assertEquals(CompanionAdmission.Refusal.OWNED, refusal);
        assertEquals(Decision.REFUSE, CaptureItemOwnership.decide(record, 1, holder, refusal));
    }

    @Test
    void anAdmittedHolderTakesOwnership() {
        assertEquals(Decision.TRANSFER, CaptureItemOwnership.decide(inItem(1), 1, holder, null));
    }

    @Test
    void theOwnerHoldingTheirOwnItemChangesNothingInAnyMode() {
        for (CaptureItemOwnershipMode mode : CaptureItemOwnershipMode.values()) {
            assertEquals(Decision.ALREADY_OWNED, CaptureItemOwnership.decide(mode, inItem(1), 1, owner, null), mode.name());
            assertEquals(Pickup.ALLOW, CaptureItemOwnership.pickup(mode, inItem(1), 1, owner, true), mode.name());
            assertEquals(Release.KEEP_OWNER, CaptureItemOwnership.release(mode, owner, owner), mode.name());
        }
    }

    @Test
    void onlyFollowsItemMovesTheCompanionToAnotherPlayerHoldingTheItem() {
        CompanionRecord record = inItem(1);

        assertEquals(Decision.TRANSFER,
                CaptureItemOwnership.decide(CaptureItemOwnershipMode.FOLLOWS_ITEM, record, 1, holder, null));
        assertEquals(Decision.REFUSE, CaptureItemOwnership.decide(CaptureItemOwnershipMode.FOLLOWS_ITEM, record, 1,
                holder, CompanionAdmission.Refusal.OWNED));
        assertEquals(Decision.IGNORE,
                CaptureItemOwnership.decide(CaptureItemOwnershipMode.OWNER_ONLY, record, 1, holder, null));
        assertEquals(Decision.IGNORE,
                CaptureItemOwnership.decide(CaptureItemOwnershipMode.CHANGES_ON_RELEASE, record, 1, holder, null));
    }

    @Test
    void anotherPlayerPickingUpTheItemIsCheckedRefusedOrAllowedByMode() {
        CompanionRecord record = inItem(1);

        assertEquals(Pickup.CHECK_LIMITS,
                CaptureItemOwnership.pickup(CaptureItemOwnershipMode.FOLLOWS_ITEM, record, 1, holder, true));
        // BlockIneligibleHolders off: the item gets in and the transfer is refused there instead.
        assertEquals(Pickup.ALLOW,
                CaptureItemOwnership.pickup(CaptureItemOwnershipMode.FOLLOWS_ITEM, record, 1, holder, false));
        assertEquals(Pickup.REFUSE_NOT_OWNER,
                CaptureItemOwnership.pickup(CaptureItemOwnershipMode.OWNER_ONLY, record, 1, holder, false));
        assertEquals(Pickup.ALLOW,
                CaptureItemOwnership.pickup(CaptureItemOwnershipMode.CHANGES_ON_RELEASE, record, 1, holder, true));
    }

    @Test
    void ownerOnlyNeverBlocksAStaleCopyOrAnUnownedWildCapture() {
        CompanionRecord unowned = inItem(1).toBuilder().ownerUuid(null).ownerName(null).build();

        assertEquals(Pickup.ALLOW,
                CaptureItemOwnership.pickup(CaptureItemOwnershipMode.OWNER_ONLY, inItem(3), 2, holder, true));
        assertEquals(Pickup.ALLOW,
                CaptureItemOwnership.pickup(CaptureItemOwnershipMode.OWNER_ONLY, unowned, 1, holder, true));
    }

    @Test
    void anotherPlayerReleasingBecomesTheOwnerExceptInOwnerOnly() {
        assertEquals(Release.ASSIGN_RELEASER,
                CaptureItemOwnership.release(CaptureItemOwnershipMode.FOLLOWS_ITEM, owner, holder));
        assertEquals(Release.ASSIGN_RELEASER,
                CaptureItemOwnership.release(CaptureItemOwnershipMode.CHANGES_ON_RELEASE, owner, holder));
        assertEquals(Release.REFUSE_NOT_OWNER,
                CaptureItemOwnership.release(CaptureItemOwnershipMode.OWNER_ONLY, owner, holder));
    }

    @Test
    void anUnownedWildCaptureIsReleasedUnownedInEveryMode() {
        for (CaptureItemOwnershipMode mode : CaptureItemOwnershipMode.values()) {
            assertEquals(Release.UNOWNED, CaptureItemOwnership.release(mode, null, holder), mode.name());
        }
    }

    @Test
    void aCaptureNeverClearsTheOwnerAndGivesATamedOrTamingCaptureToTheCapturer() {
        // An owned body keeps its owner, also when someone else captures it.
        assertEquals(owner, CaptureItemOwnership.captureOwner(owner, true, false, holder));
        assertEquals(owner, CaptureItemOwnership.captureOwner(owner, true, true, holder));
        // A wild body caught by an item that tames, and a tamed body without an owner.
        assertEquals(holder, CaptureItemOwnership.captureOwner(null, false, true, holder));
        assertEquals(holder, CaptureItemOwnership.captureOwner(null, true, false, holder));
        // A wild body caught by an item that does not tame stays an unowned wild capture.
        assertNull(CaptureItemOwnership.captureOwner(null, false, false, holder));
    }

    @Test
    void anItemIsStaleOnceItsRecordLeftTheItemOrMovedToAnotherGeneration() {
        assertFalse(CaptureItemOwnership.isStale(inItem(2), 2));
        assertTrue(CaptureItemOwnership.isStale(inItem(3), 2));
        assertTrue(CaptureItemOwnership.isStale(
                inItem(2).toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build(), 2));
        assertTrue(CaptureItemOwnership.isStale(null, 2));
    }

    @Test
    void ownerOnlyRefusesAReleaseBySomeoneElseOnlyWhileTheItemStillHoldsItsCompanion() {
        Release byOther = CaptureItemOwnership.release(CaptureItemOwnershipMode.OWNER_ONLY, owner, holder);

        assertTrue(CaptureItemOwnership.releaseRefused(byOther, inItem(2), 2));
        // A stale copy is not refused, so the release goes on and empties it.
        assertFalse(CaptureItemOwnership.releaseRefused(byOther, inItem(3), 2));
        assertFalse(CaptureItemOwnership.releaseRefused(byOther,
                inItem(2).toBuilder().location(CompanionLocation.live("w", 0, 0, 0)).build(), 2));
        assertFalse(CaptureItemOwnership.releaseRefused(Release.KEEP_OWNER, inItem(2), 2));
    }

    @Test
    void onlyOwnerOnlyRefusesACaptureOfSomeoneElsesCompanion() {
        assertTrue(CaptureItemOwnership.captureRefused(CaptureItemOwnershipMode.OWNER_ONLY, owner, holder));
        assertFalse(CaptureItemOwnership.captureRefused(CaptureItemOwnershipMode.OWNER_ONLY, owner, owner));
        assertFalse(CaptureItemOwnership.captureRefused(CaptureItemOwnershipMode.OWNER_ONLY, null, holder));
        assertFalse(CaptureItemOwnership.captureRefused(CaptureItemOwnershipMode.FOLLOWS_ITEM, owner, holder));
        assertFalse(CaptureItemOwnership.captureRefused(CaptureItemOwnershipMode.CHANGES_ON_RELEASE, owner, holder));
    }
}
