package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionActiveCapacity;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;

/** Performs the family-scoped active-capacity fence in the summon write itself. */
final class SqliteBondedCompanionSummonWriter {
    boolean activate(
            Connection connection,
            BondedCompanionProjectionService.SummonRequest request,
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) throws Exception {
        BondedCompanionActiveCapacity capacity = request.activeCapacity();
        if (capacity == null) return false;
        String familyId = capacity.familyId();
        int maximumActive = capacity.maximumActive();
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE bonded_companion_profile
                SET state = 'ACTIVE', revision = revision + 1,
                    updated_at_ms = ?
                WHERE profile_id = ? AND owner_uuid = ? AND roster_id = ?
                  AND revision = ? AND state = 'STORED'
                  AND family_id = ?
                  AND (? = 0 OR (
                    SELECT COUNT(*) FROM bonded_companion_profile active
                    WHERE active.owner_uuid = ? AND active.roster_id = ?
                      AND active.family_id = ? AND active.state = 'ACTIVE'
                  ) < ?)
                """)) {
            update.setLong(1, request.nowMs());
            update.setString(2, lease.profileId());
            update.setString(3, lease.ownerUuid().toString());
            update.setString(4, lease.rosterId());
            update.setLong(5, request.expectedRevision());
            update.setString(6, familyId);
            update.setInt(7, maximumActive);
            update.setString(8, lease.ownerUuid().toString());
            update.setString(9, lease.rosterId());
            update.setString(10, familyId);
            update.setInt(11, maximumActive);
            return update.executeUpdate() == 1;
        }
    }
}
