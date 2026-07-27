package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionWorldLifecycleObserver;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.util.Objects;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/** Resolves one recovery store cooldown from an exact roster-family generation. */
public final class BondedCompanionReconciliationCooldownResolver implements
        BondedCompanionWorldLifecycleObserver.ReconciliationCooldownResolver {
    private final BondedCompanionStore store;
    private final BondedCompanionRosterRegistry rosters;

    public BondedCompanionReconciliationCooldownResolver(
            @Nonnull BondedCompanionStore store,
            @Nonnull BondedCompanionRosterRegistry rosters
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.rosters = Objects.requireNonNull(rosters, "rosters");
    }

    @Override
    @Nonnull
    public OptionalLong resolve(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease,
            long observedAtMs
    ) {
        Objects.requireNonNull(lease, "lease");
        BondedCompanionRecord.Profile profile = store.findProfile(
                lease.ownerUuid(), lease.rosterId(), lease.profileId()
        ).orElse(null);
        if (profile == null || profile.state() != BondedCompanionState.ACTIVE) {
            return OptionalLong.empty();
        }
        BondedCompanionRosterRegistry.Snapshot generation = rosters.snapshot();
        var family = generation.resolve(
                profile.rosterId(), profile.familyId()).orElse(null);
        if (family == null || !family.allowedRoles().contains(profile.roleId())) {
            return OptionalLong.empty();
        }
        try {
            long seconds = family.summonCooldownSeconds();
            if (seconds == 0L) return OptionalLong.of(0L);
            long cooldown = Math.addExact(
                    observedAtMs, Math.multiplyExact(seconds, 1_000L));
            return cooldown == 0L
                    ? OptionalLong.empty() : OptionalLong.of(cooldown);
        } catch (ArithmeticException invalidTime) {
            return OptionalLong.empty();
        }
    }
}
