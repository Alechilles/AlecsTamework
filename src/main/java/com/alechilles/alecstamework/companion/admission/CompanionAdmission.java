package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Built-in and group population caps, checked under the index lock in the step that changes the
 * record (spec 8.10). Refuses only where the change adds the companion to a bucket it was not
 * counted in and that bucket would then pass its limit, so moves that add nothing always pass,
 * even for an owner already over a limit lowered by config. A limit of 0 means no limit.
 */
public final class CompanionAdmission {
    public enum Refusal { OWNED, GROUP_OWNED, GROUP_DEPLOYED }

    /**
     * @param ownedLimit    owned companions per owner (0 = none)
     * @param ownedPerWorld count the owned limit per world instead of across worlds
     * @param groupsForRole the population groups a role belongs to, with their limits
     */
    public record Rules(int ownedLimit, boolean ownedPerWorld,
                        @Nonnull Function<String, List<PopulationGroupPolicy>> groupsForRole) {
        public static final Rules NONE = new Rules(0, false, role -> List.of());

        public Rules {
            Objects.requireNonNull(groupsForRole, "groupsForRole");
        }
    }

    private CompanionAdmission() {
    }

    /** @param ownerRecords every record filed under {@code after}'s owner; tombstones are skipped */
    @Nullable
    public static Refusal check(@Nonnull Collection<CompanionRecord> ownerRecords, @Nullable CompanionRecord before,
                                @Nonnull CompanionRecord after, @Nonnull Rules rules) {
        UUID owner = after.ownerUuid();
        if (owner == null || !after.countsAsOwned()) {
            return null;
        }
        CompanionRecord prior = before != null && owner.equals(before.ownerUuid()) && before.countsAsOwned() ? before : null;
        if (rules.ownedLimit() > 0) {
            Predicate<CompanionRecord> bucket = sameScope(rules.ownedPerWorld(), after);
            if ((prior == null || !bucket.test(prior)) && 1 + count(ownerRecords, after, bucket) > rules.ownedLimit()) {
                return Refusal.OWNED;
            }
        }
        for (PopulationGroupPolicy group : rules.groupsForRole().apply(after.roleId())) {
            Predicate<CompanionRecord> bucket = sameScope(group.scope() == PopulationGroupScope.PER_WORLD, after)
                    .and(r -> inGroup(rules, r, group.groupId()));
            boolean wasInGroup = prior != null && bucket.test(prior);
            if (group.maxOwnedPerOwner() > 0 && !wasInGroup
                    && 1 + count(ownerRecords, after, bucket) > group.maxOwnedPerOwner()) {
                return Refusal.GROUP_OWNED;
            }
            if (group.maxActivePerOwner() > 0 && after.isDeployed() && !(wasInGroup && prior.isDeployed())
                    && 1 + count(ownerRecords, after, bucket.and(CompanionRecord::isDeployed)) > group.maxActivePerOwner()) {
                return Refusal.GROUP_DEPLOYED;
            }
        }
        return null;
    }

    /** Spec 8.11: a litter is admitted as a whole or not at all. */
    @Nullable
    public static Refusal checkBatch(@Nonnull Collection<CompanionRecord> ownerRecords,
                                     @Nonnull List<CompanionRecord> candidates, @Nonnull Rules rules) {
        List<CompanionRecord> working = new ArrayList<>(ownerRecords);
        for (CompanionRecord candidate : candidates) {
            Refusal refusal = check(working, null, candidate, rules);
            if (refusal != null) {
                return refusal;
            }
            working.add(candidate);
        }
        return null;
    }

    /** The world a record counts in: where it is (LIVE, COOP), else where it was tamed. Never null. */
    @Nonnull
    public static String scopeWorld(@Nonnull CompanionRecord record) {
        String world = record.location().world() != null ? record.location().world() : record.homeWorld();
        return world == null ? "" : world;
    }

    private static Predicate<CompanionRecord> sameScope(boolean perWorld, CompanionRecord after) {
        if (!perWorld) {
            return r -> true;
        }
        String world = scopeWorld(after);
        return r -> scopeWorld(r).equals(world);
    }

    private static boolean inGroup(Rules rules, CompanionRecord record, String groupId) {
        for (PopulationGroupPolicy policy : rules.groupsForRole().apply(record.roleId())) {
            if (policy.groupId().equals(groupId)) {
                return true;
            }
        }
        return false;
    }

    private static int count(Collection<CompanionRecord> records, CompanionRecord self, Predicate<CompanionRecord> filter) {
        int n = 0;
        for (CompanionRecord record : records) {
            if (!record.profileId().equals(self.profileId()) && record.countsAsOwned() && filter.test(record)) {
                n++;
            }
        }
        return n;
    }
}
