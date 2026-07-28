package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds policy-aware public profile views from canonical bonded storage. */
final class BondedCompanionProfileViewService {
    private final BondedCompanionStore store;
    private final BondedCompanionRosterRegistry rosters;
    private final BondedCompanionPolicyResolver policies;
    private final BondedCompanionReviveQuoteSupport reviveQuotes;
    private final BondedCompanionViewFactory views = new BondedCompanionViewFactory();
    private final LongSupplier clock;

    BondedCompanionProfileViewService(
            @Nonnull BondedCompanionStore store,
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nonnull BondedCompanionPolicyResolver policies,
            @Nonnull BondedCompanionReviveQuoteSupport reviveQuotes,
            @Nonnull LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.rosters = Objects.requireNonNull(rosters, "rosters");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.reviveQuotes = Objects.requireNonNull(reviveQuotes, "reviveQuotes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    BondedCompanionProfileView view(
            @Nonnull BondedCompanionRecord.Profile profile,
            @Nullable BondedCompanionRecord.Lease lease,
            @Nonnull List<BondedCompanionRecord.Profile> rosterProfiles
    ) {
        BondedCompanionPolicy policy = policies.resolve(profile.rosterId(),
                profile.familyId(), rosters.snapshot().revision()).policy();
        boolean matches = policy != null && policy.rosterId().equals(profile.rosterId())
                && policy.familyId().equals(profile.familyId())
                && policy.allowedRoles().contains(profile.roleId());
        int active = BondedCompanionFamilyScope.counts(rosterProfiles,
                profile.familyId()).active();
        boolean revive = matches && profile.state() == BondedCompanionState.DEAD
                && policy.features().revive();
        return views.view(profile, lease,
                matches && profile.state() == BondedCompanionState.STORED
                        && policy.features().summon()
                        && remaining(profile.summonCooldownUntilMs(),
                                clock.getAsLong()) == 0L
                        && BondedCompanionFamilyScope.hasActiveCapacity(active,
                                policy.maximumActive()),
                matches && profile.state() == BondedCompanionState.ACTIVE
                        && policy.features().dismiss(),
                revive, extensions(profile),
                revive ? reviveQuotes.profileQuote(profile, policy) : null,
                BondedCompanionFamilyCapacityPresentation.attributes(policy, active));
    }

    private Map<String, String> extensions(
            BondedCompanionRecord.Profile profile
    ) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        List<BondedCompanionRecord.ExtensionData> stored = store.listExtensionData(
                profile.ownerUuid(), profile.rosterId(), profile.profileId());
        if (stored != null) stored.forEach(extension -> values.put(
                extension.namespace(), new String(extension.payload().bytes(),
                        StandardCharsets.UTF_8)));
        return values;
    }

    private long remaining(long until, long now) {
        return until == 0L || now >= until ? 0L : until - now;
    }
}
