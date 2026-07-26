package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionLeaseView;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.util.Map;

/** Maps durable records to the immutable public bonded profile view. */
final class BondedCompanionViewFactory {
    BondedCompanionProfileView view(
            BondedCompanionRecord.Profile profile,
            BondedCompanionRecord.Lease lease
    ) {
        Map<String, String> presentation = profile.policy().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("presentation:"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().substring("presentation:".length()),
                        Map.Entry::getValue
                ));
        BondedCompanionLeaseView active = lease == null ? null
                : new BondedCompanionLeaseView(
                        lease.leaseToken(), lease.liveNpcUuid(), lease.worldKey(),
                        lease.startedAtMs(), lease.expiresAtMs()
                );
        return new BondedCompanionProfileView(
                profile.profileId(), profile.ownerUuid(), profile.rosterId(),
                profile.familyId(), profile.roleId(), profile.displayName(),
                profile.species(), profile.gender(), profile.revision(),
                profile.state(), profile.state() == BondedCompanionState.STORED,
                profile.state() == BondedCompanionState.ACTIVE,
                profile.state() == BondedCompanionState.DEAD,
                presentation, active, profile.reviveCooldownUntilMs(), null
        );
    }
}
