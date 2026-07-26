package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves each mutation against the current immutable roster generation. */
public final class BondedCompanionPolicyResolver {
    private final BondedCompanionRosterRegistry registry;

    public BondedCompanionPolicyResolver(
            @Nonnull BondedCompanionRosterRegistry registry
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Resolves an exact roster and optionally fences its asset generation. */
    @Nonnull
    public Resolution resolve(String rosterId, long expectedRevision) {
        BondedCompanionRosterRegistry.Snapshot snapshot = registry.snapshot();
        if (expectedRevision != snapshot.revision()) {
            return new Resolution(
                    Status.REVISION_CONFLICT, null, snapshot.revision()
            );
        }
        BondedCompanionRosterRegistry.RosterDefinition definition =
                snapshot.byRosterId().get(normalize(rosterId));
        if (definition == null) {
            return new Resolution(Status.NOT_FOUND, null, snapshot.revision());
        }
        return new Resolution(
                Status.FOUND,
                map(snapshot.revision(), definition),
                snapshot.revision()
        );
    }

    private static BondedCompanionPolicy map(
            long revision,
            BondedCompanionRosterRegistry.RosterDefinition source
    ) {
        BondedCompanionRosterRegistry.RevivePrice price = source.revivePrice();
        BondedCompanionRosterRegistry.FeatureFlags flags = source.features();
        return new BondedCompanionPolicy(
                revision, source.rosterId(), source.familyId(),
                source.allowedRoles(), source.maximumOwned(),
                source.maximumActive(), source.sessionDurationSeconds(),
                source.summonCooldownSeconds(),
                price == null ? null : new BondedCompanionPolicy.RevivePrice(
                        price.itemId(), price.quantity()
                ),
                new BondedCompanionPolicy.FeatureFlags(
                        flags.capture(), flags.provision(), flags.summon(),
                        flags.dismiss(), flags.revive()
                )
        );
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    /** Stable lookup outcome independent from the asset implementation. */
    public enum Status {
        FOUND,
        NOT_FOUND,
        REVISION_CONFLICT
    }

    /** One current-generation lookup result. */
    public record Resolution(
            @Nonnull Status status,
            @Nullable BondedCompanionPolicy policy,
            long activeRevision
    ) {
        public Resolution {
            status = Objects.requireNonNull(status, "status");
            if ((status == Status.FOUND) != (policy != null)) {
                throw new IllegalArgumentException("invalid policy resolution");
            }
        }
    }
}
