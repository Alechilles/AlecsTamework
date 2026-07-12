package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects one physical representation over stale dormant aliases of the same profile. */
final class CompanionPopulationRepairEvidenceSelector {
    private CompanionPopulationRepairEvidenceSelector() {
    }

    @Nonnull
    static Selection select(
            @Nonnull Connection connection,
            @Nonnull List<CompanionPopulationEvidenceSet.ResolvedEvidence> evidence
    ) throws Exception {
        Map<String, List<CompanionPopulationEvidenceSet.ResolvedEvidence>> byProfile =
                new LinkedHashMap<>();
        for (CompanionPopulationEvidenceSet.ResolvedEvidence observation : evidence) {
            String profileId = CompanionPopulationRepairIdentitySql.resolveProfileId(
                    connection,
                    observation.npcUuid()
            );
            String groupingKey = profileId == null
                    ? "new:" + CompanionPopulationRepairIdentitySql.deterministicProfileId(
                            observation.npcUuid()
                    )
                    : "profile:" + profileId;
            byProfile.computeIfAbsent(groupingKey, ignored -> new ArrayList<>()).add(observation);
        }
        List<CompanionPopulationEvidenceSet.ResolvedEvidence> selected = new ArrayList<>();
        for (List<CompanionPopulationEvidenceSet.ResolvedEvidence> representations : byProfile.values()) {
            List<CompanionPopulationEvidenceSet.ResolvedEvidence> physical = representations.stream()
                    .filter(CompanionPopulationEvidenceSet.ResolvedEvidence::physical)
                    .toList();
            if (physical.size() > 1) {
                return new Selection(List.of(), "duplicate-physical-profile-representation");
            }
            if (physical.size() == 1) {
                selected.add(physical.getFirst());
            } else {
                selected.addAll(representations);
            }
        }
        return new Selection(List.copyOf(selected), null);
    }

    record Selection(
            @Nonnull List<CompanionPopulationEvidenceSet.ResolvedEvidence> evidence,
            @Nullable String conflictReason
    ) {
    }
}
