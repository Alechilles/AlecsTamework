package com.alechilles.alecstamework.companion.admission;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.DomainClaim;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Each owner has two built-in limits with one scope. The owned limit counts every record that
 * {@link CompanionRecord#countsAsOwned() counts as owned}: out in the world, stored, in an item,
 * in a coop, dead or lost. The deployed limit counts the records that
 * {@link CompanionRecord#countsAsDeployed() count as deployed}: out in the world (LIVE), loaded or
 * not, except an import whose body has not been seen yet. Population groups count their deployed
 * records the same way.</p>
 *
 * <p>A per-world limit counts a record in {@link #scopeWorld its world}. A record that has been in
 * no world yet (a provisioned companion before its first summon, which has no home world) was
 * admitted as owned when it was made, so the first world it enters is not a new bucket for it.
 * From then on that world is its home ({@code CompanionTransitions.restored}).</p>
 *
 * <p>An admission provider's domain limits follow the same rule, with these differences: a claim
 * counts by its weight, a domain limit of 0 admits nothing, and a claim on a domain with no limit
 * entry is refused, because the provider's answer is incomplete.</p>
 */
public final class CompanionAdmission {
    /** Message key of a refused owned-domain claim. */
    public static final String OWNED_LIMIT_MESSAGE_KEY = "tamework.ui.population.ownedLimit";
    /** Message key of a refused deployable-domain claim and of {@link Refusal#DEPLOYED}. */
    public static final String DEPLOYED_LIMIT_MESSAGE_KEY = "tamework.ui.population.deployedLimit";
    /** Message key of a {@link Refusal#PROVIDER_DENIED} that reached a presenter without its own key. */
    public static final String PROVIDER_DENIED_MESSAGE_KEY = "tamework.ui.population.providerDenied";
    /** Message key of {@link Refusal#PROVIDER_UNAVAILABLE}. */
    public static final String PROVIDER_UNAVAILABLE_MESSAGE_KEY = "tamework.ui.population.providerUnavailable";

    /**
     * {@code OWNED} and {@code DEPLOYED}: the owner's built-in owned or deployed limit is reached.
     * {@code PROVIDER_DENIED}: an admission provider denied the change, or one of its domain
     * limits is reached ({@link #checkDomains} names the message key). {@code PROVIDER_UNAVAILABLE}:
     * the provider gave no decision; {@link #check} never returns it.
     */
    public enum Refusal { OWNED, DEPLOYED, GROUP_OWNED, GROUP_DEPLOYED, PROVIDER_DENIED, PROVIDER_UNAVAILABLE }

    /**
     * What an admission provider allowed for the record being changed: the domain claims the
     * record will carry and the owner's limit per domain id.
     */
    public record Provided(@Nonnull List<DomainClaim> claims, @Nonnull Map<String, Integer> domainLimits) {
        private static final Provided NONE = new Provided(List.of(), Map.of());

        public Provided {
            claims = List.copyOf(claims);
            domainLimits = Map.copyOf(domainLimits);
        }

        /** No provider is involved: no claims and no domain limits. */
        @Nonnull
        public static Provided none() {
            return NONE;
        }
    }

    /** A domain limit that refused a change, with the translation key to show the player. */
    public record DomainRefusal(@Nonnull String domainId, @Nonnull String messageKey) {
    }

    /**
     * @param ownedLimit    owned companions per owner (0 = none)
     * @param deployedLimit companions out in the world per owner (0 = none)
     * @param perWorld      count the owned and deployed limits per world instead of across worlds
     * @param groupsForRole the population groups a role belongs to, with their limits
     */
    public record Rules(int ownedLimit, int deployedLimit, boolean perWorld,
                        @Nonnull Function<String, List<PopulationGroupPolicy>> groupsForRole) {
        public Rules {
            Objects.requireNonNull(groupsForRole, "groupsForRole");
        }

        /** Rules with no deployed limit. */
        public Rules(int ownedLimit, boolean perWorld,
                     @Nonnull Function<String, List<PopulationGroupPolicy>> groupsForRole) {
            this(ownedLimit, 0, perWorld, groupsForRole);
        }
    }

    private CompanionAdmission() {
    }

    /**
     * @param ownerRecords every record filed under {@code after}'s owner; tombstones are skipped
     * @param provided     the provider's claims and domain limits for {@code after}, or
     *                     {@link Provided#none()}
     */
    @Nullable
    public static Refusal check(@Nonnull Collection<CompanionRecord> ownerRecords, @Nullable CompanionRecord before,
                                @Nonnull CompanionRecord after, @Nonnull Rules rules, @Nonnull Provided provided) {
        UUID owner = after.ownerUuid();
        if (owner == null || !after.countsAsOwned()) {
            return null;
        }
        CompanionRecord prior = before != null && owner.equals(before.ownerUuid()) && before.countsAsOwned() ? before : null;
        Predicate<CompanionRecord> ownerBucket = sameScope(rules.perWorld(), after);
        if (rules.ownedLimit() > 0
                && (prior == null || !(ownerBucket.test(prior) || unplaced(prior)))
                && 1 + count(ownerRecords, after, ownerBucket) > rules.ownedLimit()) {
            return Refusal.OWNED;
        }
        // The record itself is read as LIVE or not: a never-seen import that gets its body was
        // LIVE before, so matching it adds nothing and is never refused.
        if (rules.deployedLimit() > 0 && after.isDeployed()
                && !(prior != null && prior.isDeployed() && ownerBucket.test(prior))
                && 1 + count(ownerRecords, after, ownerBucket.and(CompanionRecord::countsAsDeployed))
                > rules.deployedLimit()) {
            return Refusal.DEPLOYED;
        }
        for (PopulationGroupPolicy group : rules.groupsForRole().apply(after.roleId())) {
            Predicate<CompanionRecord> bucket = sameScope(group.scope() == PopulationGroupScope.PER_WORLD, after)
                    .and(r -> inGroup(rules, r, group.groupId()));
            boolean wasInGroup = prior != null
                    && (bucket.test(prior) || unplaced(prior) && inGroup(rules, prior, group.groupId()));
            if (group.maxOwnedPerOwner() > 0 && !wasInGroup
                    && 1 + count(ownerRecords, after, bucket) > group.maxOwnedPerOwner()) {
                return Refusal.GROUP_OWNED;
            }
            if (group.maxActivePerOwner() > 0 && after.isDeployed() && !(wasInGroup && prior.isDeployed())
                    && 1 + count(ownerRecords, after, bucket.and(CompanionRecord::countsAsDeployed))
                    > group.maxActivePerOwner()) {
                return Refusal.GROUP_DEPLOYED;
            }
        }
        return checkDomains(ownerRecords, before, after, provided) == null ? null : Refusal.PROVIDER_DENIED;
    }

    /**
     * The provider domain limit that refuses the change, or null. An owned claim counts on every
     * record of the owner that counts as owned, a deployable claim on the deployed (LIVE) ones;
     * the claims already stored on the owner's other records are summed by weight. {@code before}
     * is read with the claims stored on it: a bucket is checked only when the record's weight in
     * it grows, so a move that adds nothing passes. A claim whose domain has no limit is refused.
     */
    @Nullable
    public static DomainRefusal checkDomains(@Nonnull Collection<CompanionRecord> ownerRecords,
                                             @Nullable CompanionRecord before, @Nonnull CompanionRecord after,
                                             @Nonnull Provided provided) {
        UUID owner = after.ownerUuid();
        if (owner == null || !after.countsAsOwned() || provided.claims().isEmpty()) {
            return null;
        }
        CompanionRecord prior = before != null && owner.equals(before.ownerUuid()) && before.countsAsOwned() ? before : null;
        for (DomainClaim claim : provided.claims()) {
            Integer limit = provided.domainLimits().get(claim.domainId());
            if (limit == null) {
                return new DomainRefusal(claim.domainId(),
                        claim.owned() ? OWNED_LIMIT_MESSAGE_KEY : DEPLOYED_LIMIT_MESSAGE_KEY);
            }
            if (claim.owned() && claim.weight() > claimWeight(prior, claim.domainId(), false)
                    && claim.weight() + claimed(ownerRecords, after, claim.domainId(), false) > limit) {
                return new DomainRefusal(claim.domainId(), OWNED_LIMIT_MESSAGE_KEY);
            }
            if (claim.deployable() && after.isDeployed() && claim.weight() > claimWeight(prior, claim.domainId(), true)
                    && claim.weight() + claimed(ownerRecords, after, claim.domainId(), true) > limit) {
                return new DomainRefusal(claim.domainId(), DEPLOYED_LIMIT_MESSAGE_KEY);
            }
        }
        return null;
    }

    /** The world a record counts in: where it is (LIVE, COOP), else where it was tamed. Never null. */
    @Nonnull
    public static String scopeWorld(@Nonnull CompanionRecord record) {
        String world = record.location().world() != null ? record.location().world() : record.homeWorld();
        return world == null ? "" : world;
    }

    /** True when the record has been in no world yet, so it counts in no world's bucket. */
    private static boolean unplaced(CompanionRecord record) {
        return scopeWorld(record).isEmpty();
    }

    private static Predicate<CompanionRecord> sameScope(boolean perWorld, CompanionRecord after) {
        if (!perWorld) {
            return r -> true;
        }
        String world = scopeWorld(after);
        return r -> scopeWorld(r).equals(world);
    }

    /** True when {@code record}'s role belongs to population group {@code groupId}. */
    public static boolean inGroup(@Nonnull Rules rules, @Nonnull CompanionRecord record, @Nonnull String groupId) {
        for (PopulationGroupPolicy policy : rules.groupsForRole().apply(record.roleId())) {
            if (policy.groupId().equals(groupId)) {
                return true;
            }
        }
        return false;
    }

    /** The summed weight of the owner's other records in a domain's owned or deployed bucket. */
    private static long claimed(Collection<CompanionRecord> records, CompanionRecord self, String domainId,
                                boolean deployed) {
        long sum = 0;
        for (CompanionRecord record : records) {
            if (!record.profileId().equals(self.profileId())) {
                sum += claimWeight(record, domainId, deployed);
            }
        }
        return sum;
    }

    /** What {@code record} holds in a domain's owned or deployed bucket; 0 when it is not counted there. */
    private static long claimWeight(@Nullable CompanionRecord record, String domainId, boolean deployed) {
        if (record == null || !record.countsAsOwned() || (deployed && !record.isDeployed())) {
            return 0;
        }
        long sum = 0;
        for (DomainClaim claim : record.domainClaims()) {
            if (claim.domainId().equals(domainId) && (deployed ? claim.deployable() : claim.owned())) {
                sum += claim.weight();
            }
        }
        return sum;
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
