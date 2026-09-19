package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionChangePublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkBondedPermanentDeathEventTest {
    @Test
    void permanentDeathNotifiesSubscribersWithoutReloadingTheDeletedProfile() {
        var lease = new BondedCompanionProjectionValidator.LeaseExpectation(
                UUID.randomUUID(), "roster", "profile", "lease", UUID.randomUUID(),
                "world", -100L, 0L, BondedCompanionProjectionValidator.LeasePhase.LIVE);
        var events = new ArrayList<BondedCompanionChangedEvent>();
        try (var publisher = new BondedCompanionChangePublisher(null)) {
            publisher.subscribe(events::add);
            TameworkBondedCompanionComposition.publishLifecycleChange(null, publisher, lease,
                    new BondedCompanionProjectionService.ReconcileResult(
                            BondedCompanionProjectionService.ReconcileStatus.DEAD,
                            List.of(), 3L));
        }
        assertEquals(1, events.size());
        assertEquals(lease.profileId(), events.getFirst().profileId());
        assertEquals(lease.ownerUuid(), events.getFirst().ownerUuid());
        assertEquals(BondedCompanionStateView.DEAD, events.getFirst().newState());
        assertEquals("old_age", events.getFirst().reason());
        assertEquals(3L, events.getFirst().revision());
    }
}
