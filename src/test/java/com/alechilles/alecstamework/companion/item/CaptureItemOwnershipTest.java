package com.alechilles.alecstamework.companion.item;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.companion.item.CaptureItemOwnership.Decision;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                CaptureItemOwnership.asOwnedBy(record, holder, "Holder"), oneOwned);

        assertEquals(CompanionAdmission.Refusal.OWNED, refusal);
        assertEquals(Decision.REFUSE, CaptureItemOwnership.decide(record, 1, holder, refusal));
    }

    @Test
    void anAdmittedHolderTakesOwnership() {
        assertEquals(Decision.TRANSFER, CaptureItemOwnership.decide(inItem(1), 1, holder, null));
    }
}
