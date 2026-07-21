package com.alechilles.alecstamework.ownership.groups;

import com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy;
import com.alechilles.alecstamework.api.PopulationGroupDefinitionView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Pure all-or-none group-cap evaluation. ADMIN_OVERRIDE intentionally has no bypass semantics. */
public final class PopulationGroupAdmissionPolicy {
    private final PopulationGroupIndex index;

    public PopulationGroupAdmissionPolicy(@Nonnull PopulationGroupIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    public Decision evaluate(@Nonnull Map<PopulationGroupBucket, PopulationGroupCounts> counts,
                             @Nonnull Map<PopulationGroupBucket, PopulationGroupCountDelta> deltas,
                             @Nonnull PopulationAdmissionForcePolicy forcePolicy) {
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(deltas, "deltas");
        Objects.requireNonNull(forcePolicy, "forcePolicy");
        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<PopulationGroupBucket, PopulationGroupCountDelta> entry : deltas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            PopulationGroupCountDelta delta = entry.getValue();
            if (!delta.hasPositive()) continue;
            PopulationGroupBucket bucket = entry.getKey();
            PopulationGroupDefinitionView definition = index.getDefinition(bucket.groupId()).orElse(null);
            if (definition == null) {
                violations.add(new Violation(bucket, "population-group-definition-unavailable"));
                continue;
            }
            PopulationGroupCounts current = counts.getOrDefault(bucket, PopulationGroupCounts.ZERO);
            long ownedAfter = Math.addExact(Math.addExact(current.committedOwned(), current.pendingOwned()),
                    Math.max(0, delta.owned()));
            long activeAfter = Math.addExact(Math.addExact(current.committedActive(), current.pendingActive()),
                    Math.max(0, delta.active()));
            if (definition.maxOwnedPerOwner() > 0L && ownedAfter > definition.maxOwnedPerOwner()) {
                violations.add(new Violation(bucket, "population-group-owned-limit"));
            }
            if (definition.maxActivePerOwner() > 0L && activeAfter > definition.maxActivePerOwner()) {
                violations.add(new Violation(bucket, "population-group-active-limit"));
            }
        }
        return violations.isEmpty() ? Decision.allow() : Decision.deny(violations);
    }

    public record Violation(@Nonnull PopulationGroupBucket bucket, @Nonnull String reason) {
        public Violation {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Decision(boolean allowed, @Nonnull List<Violation> violations) {
        public Decision { violations = violations == null ? List.of() : List.copyOf(violations); }
        public static Decision allow() { return new Decision(true, List.of()); }
        public static Decision deny(List<Violation> violations) { return new Decision(false, violations); }
    }
}
